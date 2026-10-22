package com.rootguard.app.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import com.rootguard.app.helper.IRootGuardHelper
import com.rootguard.app.helper.RootGuardHelperService
import com.rootguard.core.protocol.ErrorCode
import com.rootguard.core.protocol.Operation
import com.rootguard.core.protocol.Payload
import com.rootguard.core.protocol.Request
import com.rootguard.core.protocol.Response
import com.rootguard.core.protocol.RgProtocol
import com.rootguard.core.protocol.decodeResponse
import com.rootguard.core.protocol.encode
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.coroutines.resume

enum class RootState { UNKNOWN, CONNECTING, DENIED, CONNECTED, HELPER_LOST }

/** Gateway interface so the controller can be tested without root. */
interface PrivilegedGateway {
    val state: StateFlow<RootState>
    suspend fun connect(): Boolean
    suspend fun request(op: Operation, payload: Payload = Payload.None, expectedRevision: String? = null): Response
}

/** libsu-based gateway. Root is requested only when the user starts setup (wizard), never silently. */
class LibsuGateway(private val context: Context) : PrivilegedGateway {
    private val _state = MutableStateFlow(RootState.UNKNOWN)
    override val state: StateFlow<RootState> = _state.asStateFlow()
    @Volatile private var helper: IRootGuardHelper? = null
    @Volatile private var bootId: String? = null
    private val lock = Mutex()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
        override fun onServiceDisconnected(name: ComponentName?) {
            helper = null
            bootId = null
            _state.value = RootState.HELPER_LOST
        }
    }

    override suspend fun connect(): Boolean = lock.withLock {
        if (helper?.asBinder()?.isBinderAlive == true && bootId != null) return true
        _state.value = RootState.CONNECTING
        val granted = withContext(Dispatchers.IO) { runCatching { Shell.getShell().isRoot }.getOrDefault(false) }
        if (!granted) {
            _state.value = RootState.DENIED
            return false
        }
        val binder = withTimeoutOrNull(20_000) { bind() }
        val h = binder?.let { IRootGuardHelper.Stub.asInterface(it) }
        val hs = h?.let { runCatching { it.handshake() }.getOrNull() }
        val parsed = hs?.let { runCatching { RgProtocol.json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val version = parsed?.get("protocolVersion")?.jsonPrimitive?.int
        val boot = parsed?.get("bootId")?.jsonPrimitive?.content
        if (h == null || version != RgProtocol.VERSION || boot.isNullOrBlank()) {
            _state.value = RootState.HELPER_LOST
            return false
        }
        helper = h
        bootId = boot
        _state.value = RootState.CONNECTED
        true
    }

    private suspend fun bind(): IBinder? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (cont.isActive) cont.resume(service)
                }
                override fun onServiceDisconnected(name: ComponentName?) = connection.onServiceDisconnected(name)
            }
            RootService.bind(Intent(context, RootGuardHelperService::class.java), conn)
        }
    }

    override suspend fun request(op: Operation, payload: Payload, expectedRevision: String?): Response = withContext(Dispatchers.IO) {
        val h = helper
        val boot = bootId
        if (h == null || boot == null) return@withContext Response.error(null, ErrorCode.ROOT_DENIED)
        val req = Request(RgProtocol.VERSION, UUID.randomUUID().toString(), op, boot, SystemClock.elapsedRealtime() + 30_000, expectedRevision, payload)
        val raw = try {
            h.call(req.encode())
        } catch (_: Exception) {
            helper = null
            _state.value = RootState.HELPER_LOST
            return@withContext Response.error(req.requestId, ErrorCode.INTERNAL)
        }
        decodeResponse(raw) ?: Response.error(req.requestId, ErrorCode.MALFORMED)
    }
}

package com.bright.onepluscare.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.bright.onepluscare.BuildConfig
import com.bright.onepluscare.IAdvancedMaintenanceService
import com.bright.onepluscare.core.advanced.AdvancedAllowList
import com.bright.onepluscare.core.advanced.AdvancedDiagnostic
import com.bright.onepluscare.data.SystemIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

enum class ShizukuMode { ROOT, SHELL, OTHER, UNKNOWN }

data class ShizukuStatus(
    val installed: Boolean = false,
    val running: Boolean = false,
    val permissionGranted: Boolean = false,
    val permissionDeniedPermanently: Boolean = false,
    val serviceConnected: Boolean = false,
    val mode: ShizukuMode = ShizukuMode.UNKNOWN,
    val apiVersion: Int? = null,
)

data class AdvancedReport(
    val identity: String,
    val properties: Map<String, String>,
    val diagnostics: Map<AdvancedDiagnostic, String>,
)

/**
 * App-side Shizuku integration. Shizuku is optional: every call degrades to "not available" and the rest
 * of the app never depends on it. All Binder calls run on Dispatchers.IO with a timeout.
 */
class ShizukuGateway(private val context: Context) {
    private val _status = MutableStateFlow(ShizukuStatus())
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    @Volatile private var remote: IAdvancedMaintenanceService? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        remote = null
        refresh()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, _ -> if (code == REQUEST_CODE) refresh() }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, AdvancedMaintenanceService::class.java.name))
            .processNameSuffix("advanced")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
            .tag("oneplus-care-advanced-v2")
            .daemon(false)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = if (service != null && service.pingBinder()) IAdvancedMaintenanceService.Stub.asInterface(service) else null
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            refresh()
        }
    }

    fun start() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionResult)
        } catch (_: Throwable) {
        }
        refresh()
    }

    fun refresh() {
        val installed = try {
            context.packageManager.getPackageInfo(SystemIntents.SHIZUKU_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val preV11 = running && runCatching { Shizuku.isPreV11() }.getOrDefault(true)
        val granted = running && !preV11 && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        val denied = running && !preV11 && !granted && runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
        val uid = if (running) runCatching { Shizuku.getUid() }.getOrNull() else null
        _status.value = ShizukuStatus(
            installed = installed,
            running = running,
            permissionGranted = granted,
            permissionDeniedPermanently = denied,
            serviceConnected = remote?.asBinder()?.isBinderAlive == true,
            mode = when (uid) {
                0 -> ShizukuMode.ROOT
                2000 -> ShizukuMode.SHELL
                null -> ShizukuMode.UNKNOWN
                else -> ShizukuMode.OTHER
            },
            apiVersion = if (running) runCatching { Shizuku.getVersion() }.getOrNull() else null,
        )
    }

    /** Returns false when Shizuku is not running or the user previously chose "deny and don't ask". */
    fun requestPermission(): Boolean {
        val s = _status.value
        if (!s.running || s.permissionGranted || s.permissionDeniedPermanently) return s.permissionGranted
        return try {
            Shizuku.requestPermission(REQUEST_CODE)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun bind(): Boolean {
        if (!_status.value.permissionGranted) return false
        return try {
            Shizuku.bindUserService(args, connection)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun unbind() {
        try {
            Shizuku.unbindUserService(args, connection, true)
        } catch (_: Throwable) {
        }
        remote = null
        refresh()
    }

    suspend fun collect(): AdvancedReport? = withContext(Dispatchers.IO) {
        val service = remote ?: return@withContext null
        withTimeoutOrNull(15_000L) {
            try {
                AdvancedReport(
                    identity = service.identity(),
                    properties = AdvancedAllowList.PROPERTIES.sorted().associateWith { service.readProperty(it).trim() },
                    diagnostics = AdvancedDiagnostic.entries.associateWith { service.runDiagnostic(it.name) },
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    companion object {
        const val REQUEST_CODE = 7001
    }
}

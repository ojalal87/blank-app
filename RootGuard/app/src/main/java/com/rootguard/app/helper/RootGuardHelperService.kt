package com.rootguard.app.helper

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import com.rootguard.core.helper.Exec
import com.rootguard.core.helper.ExecResult
import com.rootguard.core.helper.HelperEngine
import com.rootguard.core.helper.HelperJournal
import com.rootguard.core.helper.JournalStore
import com.rootguard.core.helper.SysInfo
import com.rootguard.core.protocol.ErrorCode
import com.rootguard.core.protocol.Response
import com.rootguard.core.protocol.RgProtocol
import com.rootguard.core.protocol.encode
import com.topjohnwu.superuser.ipc.RootService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Runs in the root process started by libsu. Contains no UI, feed, network or LLM code: only the typed
 * [HelperEngine]. Every call is checked against the app's own UID; another app cannot use this binder.
 */
class RootGuardHelperService : RootService() {
    private lateinit var engine: HelperEngine
    private var appUid = -1

    override fun onCreate() {
        super.onCreate()
        appUid = applicationInfo.uid
        engine = HelperEngine(ProcessExec, AndroidSysInfo, FileJournal(File(noBackupFilesDir, "helper_journal.json")), readBootId())
    }

    override fun onBind(intent: Intent): IBinder = object : IRootGuardHelper.Stub() {
        override fun handshake(): String {
            if (!callerOk()) return Response.error(null, ErrorCode.AUTH_FAILED).encode()
            return HelperEngine.handshake(engine.bootId)
        }

        override fun call(requestJson: String?): String {
            if (!callerOk()) return Response.error(null, ErrorCode.AUTH_FAILED).encode()
            if (requestJson == null) return Response.error(null, ErrorCode.MALFORMED).encode()
            return engine.handle(requestJson, SystemClock.elapsedRealtime())
        }
    }

    /** Fail closed when the app UID is unknown. */
    private fun callerOk(): Boolean = appUid > 0 && Binder.getCallingUid() == appUid

    companion object {
        fun readBootId(): String = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrDefault("").ifEmpty { "unknown-boot" }
    }
}

/** ProcessBuilder with an argv list: no shell, bounded output, bounded time. */
object ProcessExec : Exec {
    private const val MAX_OUTPUT = 2 * 1024 * 1024
    private const val TIMEOUT_S = 20L

    override fun run(argv: List<String>, stdin: String?): ExecResult {
        if (stdin != null && stdin.length > RgProtocol.MAX_REQUEST_BYTES * 4) return ExecResult(-1, "stdin too large")
        return try {
            val p = ProcessBuilder(argv).redirectErrorStream(true).start()
            p.outputStream.use { os -> if (stdin != null) os.write(stdin.toByteArray()) }
            val out = ByteArrayOutputStream()
            val reader = Thread {
                runCatching {
                    p.inputStream.use { input ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (out.size() < MAX_OUTPUT) out.write(buf, 0, minOf(n, MAX_OUTPUT - out.size()))
                        }
                    }
                }
            }.apply { start() }
            if (!p.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return ExecResult(-1, "timeout")
            }
            reader.join(2000)
            ExecResult(p.exitValue(), out.toString(Charsets.UTF_8.name()))
        } catch (e: Exception) {
            ExecResult(-1, e.javaClass.simpleName)
        }
    }
}

object AndroidSysInfo : SysInfo {
    override fun selinuxEnforcing(): Boolean? = runCatching { File("/sys/fs/selinux/enforce").readText().trim() == "1" }.getOrNull()
    override fun netInterfaces(): List<String> = File("/sys/class/net").list()?.toList().orEmpty().filter { it.length <= 15 }
    override fun binaryExists(path: String): Boolean = File(path).exists()
}

/** Atomic journal: temp file, fsync, rename. Written before every kernel switch. */
class FileJournal(private val file: File) : JournalStore {
    override fun read(): HelperJournal? = runCatching {
        if (!file.isFile) null else RgProtocol.json.decodeFromString(HelperJournal.serializer(), file.readText())
    }.getOrNull()

    override fun write(j: HelperJournal) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { os ->
            os.write(RgProtocol.json.encodeToString(HelperJournal.serializer(), j).toByteArray())
            os.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IllegalStateException("journal rename failed")
    }
}

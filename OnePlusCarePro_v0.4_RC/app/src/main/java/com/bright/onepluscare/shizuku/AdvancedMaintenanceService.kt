package com.bright.onepluscare.shizuku

import android.content.Context
import android.os.Binder
import android.os.Process
import com.bright.onepluscare.IAdvancedMaintenanceService
import com.bright.onepluscare.core.advanced.AdvancedAllowList
import com.bright.onepluscare.core.advanced.AdvancedDiagnostic
import com.bright.onepluscare.core.advanced.OutputSanitizer
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Shizuku UserService. Runs in a separate process with the shell (uid 2000) or root (uid 0) identity.
 *
 * Trust boundary:
 *  - Only the app that bound it can call it: every call checks Binder.getCallingUid() against the app uid
 *    captured at construction (fail closed if it could not be determined).
 *  - Executes only fixed argv from [AdvancedDiagnostic] / [AdvancedAllowList] via ProcessBuilder (no shell).
 *  - Output is line- and size-capped and stripped of serial/MAC patterns before crossing the Binder.
 *  - No file, package, settings or network mutation is possible through this interface.
 */
class AdvancedMaintenanceService : IAdvancedMaintenanceService.Stub {
    private val allowedCallerUid: Int

    /** Used by Shizuku before v13. Without a Context the caller cannot be verified, so calls are refused. */
    constructor() : super() {
        allowedCallerUid = UNKNOWN_UID
    }

    /** Shizuku v13+ passes the app's package Context. */
    constructor(context: Context) : super() {
        allowedCallerUid = try {
            context.packageManager.getApplicationInfo(context.packageName, 0).uid
        } catch (_: Exception) {
            UNKNOWN_UID
        }
    }

    override fun identity(): String {
        enforceCaller()
        return "uid=${Process.myUid()}"
    }

    override fun readProperty(key: String?): String {
        enforceCaller()
        val argv = key?.let(AdvancedAllowList::propertyArgv) ?: return REFUSED
        return execute(argv, 2_000L, 2)
    }

    override fun runDiagnostic(diagnosticId: String?): String {
        enforceCaller()
        val d = AdvancedDiagnostic.fromWireName(diagnosticId) ?: return REFUSED
        return execute(d.argv, d.timeoutMs, d.maxLines)
    }

    override fun destroy() {
        enforceCaller()
        exitProcess(0)
    }

    private fun enforceCaller() {
        val caller = Binder.getCallingUid()
        if (allowedCallerUid == UNKNOWN_UID || caller != allowedCallerUid) {
            throw SecurityException("caller not allowed")
        }
    }

    private fun execute(argv: List<String>, timeoutMs: Long, maxLines: Int): String {
        return try {
            val process = ProcessBuilder(argv).redirectErrorStream(true).start()
            process.outputStream.close()
            val reader = process.inputStream.bufferedReader()
            val lines = ArrayList<String>()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (lines.size < maxLines && System.nanoTime() < deadline) {
                val line = reader.readLine() ?: break
                lines += line
            }
            if (!process.waitFor(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
            }
            reader.close()
            OutputSanitizer.sanitize(lines.joinToString("\n"), maxLines).ifBlank { NO_OUTPUT }
        } catch (t: Throwable) {
            "unavailable: ${t.javaClass.simpleName}"
        }
    }

    companion object {
        const val REFUSED = "refused"
        const val NO_OUTPUT = "no output"
        private const val UNKNOWN_UID = -1
    }
}

package com.bright.onepluscare.shizuku

import android.content.Context
import android.os.Binder
import android.os.Process
import com.bright.onepluscare.IAdvancedMaintenanceService
import com.bright.onepluscare.core.advanced.AdvancedAllowList
import com.bright.onepluscare.core.advanced.AdvancedDiagnostic
import com.bright.onepluscare.core.advanced.OutputSanitizer
import com.bright.onepluscare.core.tweaks.OpResult
import com.bright.onepluscare.core.tweaks.TweakCommands
import com.bright.onepluscare.core.tweaks.TweakOp
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
 *  - Mutations are possible only through [runOp]: typed TweakOps that are re-validated here by
 *    TweakCommands.argvFor (allow-listed settings and values, app-ops, Doze whitelist, standby buckets,
 *    package disable/uninstall-for-user of non-protected packages, bounded display density).
 *    The app journals the previous state so every change can be undone.
 */
class AdvancedMaintenanceService : IAdvancedMaintenanceService.Stub {
    private val allowedCallerUid: Int
    private val selfPackage: String

    /** Used by Shizuku before v13. Without a Context the caller cannot be verified, so calls are refused. */
    constructor() : super() {
        allowedCallerUid = UNKNOWN_UID
        selfPackage = ""
    }

    /** Shizuku v13+ passes the app's package Context. */
    constructor(context: Context) : super() {
        selfPackage = context.packageName
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

    override fun runOp(opJson: String?): String {
        enforceCaller()
        val op = TweakOp.decode(opJson) ?: return OpResult.encode(OpResult.REFUSED)
        val argv = TweakCommands.argvFor(op, selfPackage) ?: return OpResult.encode(OpResult.REFUSED)
        if (op is TweakOp.AppOpsDump) {
            // The raw dump is large and names every app; only the parsed, typed summary leaves this process.
            val r = run(argv, OP_TIMEOUT_MS, MAX_DUMP_LINES)
            val summary = com.bright.onepluscare.core.privacy.AppOpsHistory.parse(r.output, op.op).take(MAX_HISTORY_ENTRIES)
            return OpResult.encode(r.copy(output = com.bright.onepluscare.core.privacy.AppOpsHistory.encode(summary)))
        }
        return OpResult.encode(run(argv, OP_TIMEOUT_MS, if (op is TweakOp.PackagesList) MAX_LIST_LINES else MAX_OP_LINES))
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

    /** Runs an allow-listed argv and returns the exit code with capped, unsanitized output (no free text crosses here). */
    private fun run(argv: List<String>, timeoutMs: Long, maxLines: Int): OpResult = try {
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        process.outputStream.close()
        val lines = ArrayList<String>()
        process.inputStream.bufferedReader().use { reader ->
            while (lines.size < maxLines) {
                val line = reader.readLine() ?: break
                lines += line.take(300)
            }
        }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        val exit = if (finished) process.exitValue() else -2
        OpResult(ok = finished && exit == 0, exitCode = exit, output = lines.joinToString("\n"))
    } catch (t: Throwable) {
        OpResult(ok = false, exitCode = -3, output = t.javaClass.simpleName)
    }

    companion object {
        private const val OP_TIMEOUT_MS = 15_000L
        private const val MAX_OP_LINES = 200
        private const val MAX_LIST_LINES = 3_000
        private const val MAX_DUMP_LINES = 40_000
        private const val MAX_HISTORY_ENTRIES = 300
        const val REFUSED = "refused"
        const val NO_OUTPUT = "no output"
        private const val UNKNOWN_UID = -1
    }
}

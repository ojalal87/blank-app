package com.bright.onepluscare.data

import android.content.Context
import android.net.Uri
import com.bright.onepluscare.BuildConfig
import com.bright.onepluscare.core.diagnostics.DiagnosticInput
import com.bright.onepluscare.core.diagnostics.DiagnosticOptions
import com.bright.onepluscare.core.diagnostics.DiagnosticReportBuilder
import com.bright.onepluscare.core.health.HealthEvaluator
import com.bright.onepluscare.core.history.HistoryType
import com.bright.onepluscare.shizuku.AdvancedReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Builds and writes the opt-in diagnostic export. It is written only to a destination the user picks
 * through the Storage Access Framework (ACTION_CREATE_DOCUMENT); the app never uploads it.
 * Network probes are NOT re-run during export: the last on-screen result is used if the user opted in.
 */
class DiagnosticsRepository(
    private val context: Context,
    private val device: DeviceRepository,
    private val history: HistoryRepository,
) {
    suspend fun build(
        options: DiagnosticOptions,
        capabilities: Capabilities,
        network: NetworkResult?,
        advanced: AdvancedReport?,
    ): String = withContext(Dispatchers.Default) {
        val facts = device.facts()
        val counts = history.list().groupingBy { it.type.name }.eachCount()
        val advancedText = advanced?.let { r ->
            buildString {
                appendLine(r.identity)
                r.properties.forEach { (k, v) -> appendLine("$k=$v") }
                r.diagnostics.forEach { (d, v) -> appendLine("[${d.name}]").appendLine(v) }
            }
        }
        DiagnosticReportBuilder.build(
            DiagnosticInput(
                generatedAtIso = Instant.now().toString(),
                appVersion = BuildConfig.VERSION_NAME,
                facts = facts,
                health = HealthEvaluator.evaluate(facts),
                network = network?.diagnosis,
                permissions = capabilities.asReportMap(),
                advancedSummary = advancedText,
                historyCounts = counts,
            ),
            options,
        )
    }

    suspend fun write(uri: Uri, content: String): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) } != null
        } catch (_: Exception) {
            false
        }.also { ok -> if (ok) history.add(HistoryType.EXPORT) }
    }
}

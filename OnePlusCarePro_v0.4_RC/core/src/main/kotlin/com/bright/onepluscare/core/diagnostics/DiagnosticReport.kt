package com.bright.onepluscare.core.diagnostics

import com.bright.onepluscare.core.health.HealthReport
import com.bright.onepluscare.core.model.DeviceFacts
import com.bright.onepluscare.core.network.NetworkDiagnosis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** User-selectable sections. Defaults are the minimum useful for support; all extras are opt-in. */
data class DiagnosticOptions(
    val includeDeviceModel: Boolean = true,
    val includeNetworkLayers: Boolean = false,
    val includeAdvanced: Boolean = false,
    val includeHistorySummary: Boolean = false,
)

data class DiagnosticInput(
    val generatedAtIso: String,
    val appVersion: String,
    val facts: DeviceFacts,
    val health: HealthReport,
    val network: NetworkDiagnosis?,
    val permissions: Map<String, Boolean>,
    val advancedSummary: String?,
    val historyCounts: Map<String, Int>,
)

/**
 * Builds the export. By construction it never contains: file names or paths, package names / installed
 * app inventory, IP/MAC/DNS server addresses, Private DNS hostnames, Build.SERIAL, ANDROID_ID, Build
 * fingerprint, or account data. Free-text from Shizuku diagnostics passes through [Redactor] as well.
 */
object DiagnosticReportBuilder {
    const val SCHEMA = "oneplus-care-diagnostic-v2"

    fun build(input: DiagnosticInput, options: DiagnosticOptions): String {
        val f = input.facts
        val root = buildJsonObject {
            put("schema", SCHEMA)
            put("generatedAt", input.generatedAtIso)
            put("appVersion", input.appVersion)
            putJsonObject("device") {
                if (options.includeDeviceModel) {
                    put("manufacturer", f.identity.manufacturer)
                    put("model", f.identity.model)
                }
                put("sdkInt", f.identity.sdkInt)
                put("androidRelease", f.identity.release)
                put("securityPatch", f.identity.securityPatch?.let(::JsonPrimitive) ?: JsonNull)
            }
            putJsonObject("health") {
                put("state", input.health.state.name)
                putJsonArray("factors") {
                    input.health.factors.forEach { add(JsonPrimitive("${it.area}:${it.state}:${it.reason}")) }
                }
            }
            putJsonObject("storage") {
                // Rounded to 0.1 GB so exact byte counts cannot act as a fingerprint.
                put("totalGb", roundGb(f.storage.totalBytes))
                put("availableGb", roundGb(f.storage.availableBytes))
            }
            putJsonObject("battery") {
                put("levelPercent", f.battery.levelPercent.valueOrNull()?.let(::JsonPrimitive) ?: JsonNull)
                put("temperatureC", f.battery.temperatureC.valueOrNull()?.let { JsonPrimitive(Math.round(it * 10f) / 10f) } ?: JsonNull)
                put("chargeState", f.battery.chargeState.name)
                put("health", f.battery.health.name)
                put("cycleCount", f.battery.cycleCount.valueOrNull()?.let(::JsonPrimitive) ?: JsonNull)
            }
            putJsonObject("thermal") {
                put("status", f.thermal.status.valueOrNull()?.let(::JsonPrimitive) ?: JsonNull)
            }
            putJsonObject("network") {
                put("connected", f.link.connected)
                put("validated", f.link.validated)
                put("transports", JsonArray(f.link.transports.map { JsonPrimitive(it.name) }))
                if (options.includeNetworkLayers && input.network != null) {
                    put("verdict", input.network.verdict.name)
                    putJsonArray("layers") {
                        input.network.layers.forEach { add(JsonPrimitive("${it.layer}:${it.status}")) }
                    }
                }
            }
            putJsonObject("permissions") {
                input.permissions.toSortedMap().forEach { (k, v) -> put(k, v) }
            }
            if (options.includeAdvanced && input.advancedSummary != null) {
                put("advanced", Redactor.redact(input.advancedSummary))
            }
            if (options.includeHistorySummary) {
                putJsonObject("historyCounts") { input.historyCounts.toSortedMap().forEach { (k, v) -> put(k, v) } }
            }
            putJsonObject("privacy") {
                put("containsFileNames", false)
                put("containsInstalledAppList", false)
                put("containsNetworkAddresses", false)
                put("containsDeviceIdentifiers", false)
                put("sections", JsonArray(enabledSections(options).map(::JsonPrimitive)))
            }
        }
        return PRETTY.encodeToString(JsonObject.serializer(), root)
    }

    fun enabledSections(o: DiagnosticOptions): List<String> = buildList {
        add("core")
        if (o.includeDeviceModel) add("deviceModel")
        if (o.includeNetworkLayers) add("networkLayers")
        if (o.includeAdvanced) add("advanced")
        if (o.includeHistorySummary) add("historySummary")
    }

    private fun roundGb(bytes: Long): Double = Math.round(bytes / 100_000_000.0) / 10.0

    private val PRETTY = Json { prettyPrint = true }
}

object Redactor {
    private val PATH = Regex("(/storage|/sdcard|/data|/mnt)/[^\\s\"']*")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val IPV4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val IPV6 = Regex("\\b(?:[0-9A-Fa-f]{1,4}:){2,7}[0-9A-Fa-f]{1,4}\\b")
    private val MAC = Regex("\\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\b")
    private val PACKAGE = Regex("\\b(?:[a-z][a-z0-9_]*\\.){2,}[a-z][a-z0-9_]*\\b")
    private val TOKEN = Regex("(?i)\\b(token|secret|password|passwd|auth|bearer|api[_-]?key)\\b\\s*[:=]\\s*\\S+")

    fun redact(text: String): String {
        var s = text
        s = TOKEN.replace(s) { it.groupValues[1] + "=<redacted>" }
        s = EMAIL.replace(s, "<email>")
        s = MAC.replace(s, "<mac>")
        s = IPV6.replace(s, "<ip>")
        s = IPV4.replace(s, "<ip>")
        s = PATH.replace(s) { it.groupValues[1] + "/<path>" }
        s = PACKAGE.replace(s, "<package>")
        return s
    }
}

package com.bright.onepluscare.core.rules

import com.bright.onepluscare.core.apps.AppEvaluator
import com.bright.onepluscare.core.apps.AppFlag
import com.bright.onepluscare.core.apps.AppRecord
import com.bright.onepluscare.core.battery.BatteryInterpreter
import com.bright.onepluscare.core.battery.TemperatureBand
import com.bright.onepluscare.core.battery.ThermalBand
import com.bright.onepluscare.core.duplicates.DuplicateResult
import com.bright.onepluscare.core.health.HealthArea
import com.bright.onepluscare.core.health.HealthEvaluator
import com.bright.onepluscare.core.health.HealthReason
import com.bright.onepluscare.core.health.HealthReport
import com.bright.onepluscare.core.model.BatteryHealth
import com.bright.onepluscare.core.model.ChargeState
import com.bright.onepluscare.core.model.DeviceFacts
import com.bright.onepluscare.core.network.NetworkDiagnosis
import com.bright.onepluscare.core.network.NetworkVerdict
import com.bright.onepluscare.core.storage.CleanupSummary
import com.bright.onepluscare.core.storage.StorageAccessMode
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

data class AdvisorInput(
    val facts: DeviceFacts,
    val health: HealthReport = HealthEvaluator.evaluate(facts),
    val nowMillis: Long = facts.capturedAtMillis,
    val storageMode: StorageAccessMode,
    /** null = the user has not run a scan in this session. */
    val cleanup: CleanupSummary? = null,
    val duplicates: DuplicateResult? = null,
    val trashFileBytes: Long = 0L,
    val trashFileCount: Int = 0,
    val usageAccess: Boolean,
    /** null = App Doctor data not loaded. */
    val apps: List<AppRecord>? = null,
    val usageWindowDays: Int = 30,
    val network: NetworkDiagnosis? = null,
)

fun interface Rule {
    fun evaluate(input: AdvisorInput): Recommendation?
}

class RecommendationEngine(private val rules: List<Rule> = DEFAULT_RULES) {

    fun evaluate(input: AdvisorInput): List<Recommendation> =
        rules.mapNotNull { rule -> rule.evaluate(input) }
            .distinctBy { it.id }
            .sortedWith(
                compareBy<Recommendation> { it.severity.ordinal }
                    .thenByDescending { it.estimatedBenefitBytes ?: 0L }
                    .thenBy { it.id.ordinal },
            )

    /** The dashboard shows at most a few items; the full list lives in Smart Scan. */
    fun top(input: AdvisorInput, limit: Int = 3): List<Recommendation> = evaluate(input).take(limit)

    companion object {
        const val CLEANUP_ATTENTION_BYTES = 1_000_000_000L
        const val CLEANUP_INFO_BYTES = 200_000_000L
        const val DUPLICATE_BYTES = 500_000_000L
        const val TRASH_BYTES = 1_000_000_000L
        const val UNUSED_APPS_MIN = 3
        const val BATTERY_EXEMPT_MIN = 8
        const val PATCH_ATTENTION_DAYS = 180L
        const val PATCH_INFO_DAYS = 90L

        val storage = Rule { i ->
            val f = i.health.factor(HealthArea.STORAGE) ?: return@Rule null
            val id = when (f.reason) {
                HealthReason.STORAGE_CRITICAL -> RecommendationId.STORAGE_CRITICAL
                HealthReason.STORAGE_LOW -> RecommendationId.STORAGE_LOW
                else -> return@Rule null
            }
            val s = i.facts.storage
            Recommendation(
                id = id,
                severity = if (id == RecommendationId.STORAGE_CRITICAL) Severity.CRITICAL else Severity.ATTENTION,
                evidence = listOf(
                    Evidence.Bytes(EvidenceKind.FREE_SPACE, s.availableBytes),
                    Evidence.Percent(EvidenceKind.FREE_PERCENT, s.freeRatio * 100.0),
                ),
                estimatedBenefitBytes = i.cleanup?.totalBytes,
                risk = RiskLevel.LOW,
                reversibility = Reversibility.REVERSIBLE,
                permission = if (i.storageMode == StorageAccessMode.NONE) RequiredPermission.STORAGE else RequiredPermission.NONE,
                action = when {
                    i.storageMode == StorageAccessMode.NONE -> UserAction.GRANT_STORAGE
                    i.cleanup == null -> UserAction.RUN_SMART_SCAN
                    else -> UserAction.OPEN_CLEANER
                },
            )
        }

        val batteryHealth = Rule { i ->
            val h = i.facts.battery.health
            if (h !in setOf(BatteryHealth.OVERHEAT, BatteryHealth.DEAD, BatteryHealth.OVER_VOLTAGE, BatteryHealth.FAILURE)) return@Rule null
            Recommendation(
                RecommendationId.BATTERY_HEALTH_BAD, Severity.CRITICAL,
                listOf(Evidence.Status(EvidenceKind.BATTERY_HEALTH, h.name)),
                null, RiskLevel.NONE, Reversibility.NOT_APPLICABLE, RequiredPermission.NONE, UserAction.OPEN_BATTERY,
            )
        }

        val batteryTemperature = Rule { i ->
            val b = i.facts.battery
            val t = b.temperatureC.valueOrNull() ?: return@Rule null
            val band = BatteryInterpreter.temperatureBand(t)
            val charging = b.chargeState == ChargeState.CHARGING
            val id = when {
                band == TemperatureBand.HOT && charging -> RecommendationId.BATTERY_HOT_CHARGING
                band == TemperatureBand.HOT -> RecommendationId.BATTERY_HOT
                band == TemperatureBand.WARM -> RecommendationId.BATTERY_WARM
                else -> return@Rule null
            }
            Recommendation(
                id = id,
                severity = if (band == TemperatureBand.HOT) Severity.CRITICAL else Severity.ATTENTION,
                evidence = listOf(
                    Evidence.Celsius(EvidenceKind.BATTERY_TEMP, t),
                    Evidence.Status(EvidenceKind.CHARGE_STATE, b.chargeState.name),
                ),
                estimatedBenefitBytes = null,
                risk = RiskLevel.NONE,
                reversibility = Reversibility.NOT_APPLICABLE,
                permission = RequiredPermission.NONE,
                action = UserAction.OPEN_BATTERY,
            )
        }

        val thermal = Rule { i ->
            val status = i.facts.thermal.status.valueOrNull() ?: return@Rule null
            val id = when (BatteryInterpreter.thermalBand(status)) {
                ThermalBand.SEVERE -> RecommendationId.THERMAL_SEVERE
                ThermalBand.MODERATE -> RecommendationId.THERMAL_MODERATE
                else -> return@Rule null
            }
            Recommendation(
                id, if (id == RecommendationId.THERMAL_SEVERE) Severity.CRITICAL else Severity.ATTENTION,
                listOf(Evidence.Count(EvidenceKind.THERMAL_STATUS, status)),
                null, RiskLevel.NONE, Reversibility.NOT_APPLICABLE, RequiredPermission.NONE, UserAction.OPEN_BATTERY,
            )
        }

        val network = Rule { i ->
            val verdict = i.network?.verdict
            val problem = when {
                verdict != null -> verdict !in setOf(NetworkVerdict.HEALTHY, NetworkVerdict.OFFLINE)
                else -> i.facts.link.connected && (!i.facts.link.validated || i.facts.link.captivePortal)
            }
            if (!problem) return@Rule null
            Recommendation(
                RecommendationId.NETWORK_PROBLEM, Severity.ATTENTION,
                listOf(Evidence.Status(EvidenceKind.NETWORK_VERDICT, verdict?.name ?: NetworkVerdict.NOT_VALIDATED.name)),
                null, RiskLevel.NONE, Reversibility.NOT_APPLICABLE, RequiredPermission.NONE, UserAction.OPEN_NETWORK,
            )
        }

        val cleanup = Rule { i ->
            val c = i.cleanup ?: return@Rule null
            val total = c.totalBytes
            if (total < CLEANUP_INFO_BYTES && c.safeBytes < 100_000_000L) return@Rule null
            Recommendation(
                RecommendationId.CLEANUP_AVAILABLE,
                if (total >= CLEANUP_ATTENTION_BYTES) Severity.ATTENTION else Severity.INFO,
                listOf(
                    Evidence.Bytes(EvidenceKind.CANDIDATE_BYTES, total),
                    Evidence.Count(EvidenceKind.CANDIDATE_COUNT, c.candidates.size),
                    Evidence.Bytes(EvidenceKind.SAFE_BYTES, c.safeBytes),
                ),
                total, RiskLevel.LOW, Reversibility.REVERSIBLE, RequiredPermission.NONE, UserAction.OPEN_CLEANER,
            )
        }

        val duplicates = Rule { i ->
            val d = i.duplicates ?: return@Rule null
            if (d.reclaimableBytes < DUPLICATE_BYTES) return@Rule null
            Recommendation(
                RecommendationId.DUPLICATES_AVAILABLE, Severity.INFO,
                listOf(Evidence.Bytes(EvidenceKind.DUPLICATE_BYTES, d.reclaimableBytes), Evidence.Count(EvidenceKind.DUPLICATE_GROUPS, d.groups.size)),
                d.reclaimableBytes, RiskLevel.LOW, Reversibility.REVERSIBLE, RequiredPermission.NONE, UserAction.OPEN_DUPLICATES,
            )
        }

        val trash = Rule { i ->
            if (i.trashFileBytes < TRASH_BYTES) return@Rule null
            Recommendation(
                RecommendationId.TRASH_HOLDING_SPACE, Severity.INFO,
                listOf(Evidence.Bytes(EvidenceKind.TRASH_BYTES, i.trashFileBytes), Evidence.Count(EvidenceKind.TRASH_COUNT, i.trashFileCount)),
                i.trashFileBytes, RiskLevel.MEDIUM, Reversibility.IRREVERSIBLE, RequiredPermission.NONE, UserAction.OPEN_TRASH,
            )
        }

        val storagePermission = Rule { i ->
            if (i.storageMode != StorageAccessMode.NONE) return@Rule null
            Recommendation(
                RecommendationId.STORAGE_PERMISSION_MISSING, Severity.INFO, emptyList(),
                null, RiskLevel.NONE, Reversibility.REVERSIBLE, RequiredPermission.STORAGE, UserAction.GRANT_STORAGE,
            )
        }

        val usageAccess = Rule { i ->
            if (i.usageAccess) return@Rule null
            Recommendation(
                RecommendationId.USAGE_ACCESS_MISSING, Severity.INFO, emptyList(),
                null, RiskLevel.NONE, Reversibility.REVERSIBLE, RequiredPermission.USAGE_ACCESS, UserAction.GRANT_USAGE_ACCESS,
            )
        }

        private fun countFlag(i: AdvisorInput, flag: AppFlag): Int =
            i.apps?.count { flag in AppEvaluator.flags(it, i.nowMillis, i.usageWindowDays) } ?: 0

        val unusedApps = Rule { i ->
            if (!i.usageAccess) return@Rule null
            val n = countFlag(i, AppFlag.UNUSED_LONG)
            if (n < UNUSED_APPS_MIN) return@Rule null
            val bytes = i.apps!!.filter { AppFlag.UNUSED_LONG in AppEvaluator.flags(it, i.nowMillis, i.usageWindowDays) }.sumOf { it.totalBytes ?: 0L }
            Recommendation(
                RecommendationId.UNUSED_APPS, Severity.INFO,
                listOf(Evidence.Count(EvidenceKind.UNUSED_APPS, n)),
                bytes.takeIf { it > 0 }, RiskLevel.MEDIUM, Reversibility.IRREVERSIBLE, RequiredPermission.USAGE_ACCESS, UserAction.OPEN_APP_DOCTOR,
            )
        }

        val sensitiveUnused = Rule { i ->
            if (!i.usageAccess) return@Rule null
            val n = countFlag(i, AppFlag.SENSITIVE_AND_UNUSED)
            if (n == 0) return@Rule null
            Recommendation(
                RecommendationId.SENSITIVE_UNUSED_APPS, Severity.ATTENTION,
                listOf(Evidence.Count(EvidenceKind.SENSITIVE_UNUSED_APPS, n)),
                null, RiskLevel.LOW, Reversibility.REVERSIBLE, RequiredPermission.USAGE_ACCESS, UserAction.OPEN_PRIVACY,
            )
        }

        val batteryExemptions = Rule { i ->
            val n = countFlag(i, AppFlag.BATTERY_EXEMPT)
            if (n < BATTERY_EXEMPT_MIN) return@Rule null
            Recommendation(
                RecommendationId.MANY_BATTERY_EXEMPTIONS, Severity.INFO,
                listOf(Evidence.Count(EvidenceKind.BATTERY_EXEMPT_APPS, n)),
                null, RiskLevel.LOW, Reversibility.REVERSIBLE, RequiredPermission.NONE, UserAction.OPEN_BATTERY,
            )
        }

        val securityPatch = Rule { i ->
            val days = patchAgeDays(i.facts.identity.securityPatch, i.nowMillis) ?: return@Rule null
            if (days < PATCH_INFO_DAYS) return@Rule null
            Recommendation(
                RecommendationId.SECURITY_PATCH_OLD,
                if (days >= PATCH_ATTENTION_DAYS) Severity.ATTENTION else Severity.INFO,
                listOf(Evidence.Days(EvidenceKind.SECURITY_PATCH_AGE_DAYS, days)),
                null, RiskLevel.NONE, Reversibility.NOT_APPLICABLE, RequiredPermission.NONE, UserAction.OPEN_SYSTEM_SETTINGS,
            )
        }

        val lowMemory = Rule { i ->
            if (!i.facts.memory.systemLowMemory) return@Rule null
            Recommendation(
                RecommendationId.SYSTEM_LOW_MEMORY, Severity.INFO,
                listOf(Evidence.Status(EvidenceKind.LOW_MEMORY, "true")),
                null, RiskLevel.NONE, Reversibility.NOT_APPLICABLE, RequiredPermission.NONE, UserAction.OPEN_APP_DOCTOR,
            )
        }

        fun patchAgeDays(patch: String?, nowMillis: Long): Long? {
            if (patch.isNullOrBlank()) return null
            return try {
                val date = LocalDate.parse(patch.trim())
                val now = java.time.Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).toLocalDate()
                ChronoUnit.DAYS.between(date, now).takeIf { it >= 0 }
            } catch (_: DateTimeParseException) {
                null
            }
        }

        val DEFAULT_RULES: List<Rule> = listOf(
            storage, batteryHealth, batteryTemperature, thermal, network, cleanup, duplicates, trash,
            storagePermission, usageAccess, unusedApps, sensitiveUnused, batteryExemptions, securityPatch, lowMemory,
        )
    }
}

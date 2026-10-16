package com.bright.onepluscare.core

import com.bright.onepluscare.core.Fixtures.GB
import com.bright.onepluscare.core.Fixtures.NOW
import com.bright.onepluscare.core.apps.AppRecord
import com.bright.onepluscare.core.apps.SensitivePermission
import com.bright.onepluscare.core.health.HealthArea
import com.bright.onepluscare.core.health.HealthEvaluator
import com.bright.onepluscare.core.health.HealthReason
import com.bright.onepluscare.core.health.HealthState
import com.bright.onepluscare.core.model.BatteryHealth
import com.bright.onepluscare.core.model.ChargeState
import com.bright.onepluscare.core.rules.AdvisorInput
import com.bright.onepluscare.core.rules.Evidence
import com.bright.onepluscare.core.rules.EvidenceKind
import com.bright.onepluscare.core.rules.RecommendationEngine
import com.bright.onepluscare.core.rules.RecommendationId
import com.bright.onepluscare.core.rules.Reversibility
import com.bright.onepluscare.core.rules.Severity
import com.bright.onepluscare.core.rules.UserAction
import com.bright.onepluscare.core.storage.CleanupSummary
import com.bright.onepluscare.core.storage.StorageAccessMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthEvaluatorTest {
    @Test
    fun `healthy device is healthy`() {
        val r = HealthEvaluator.evaluate(Fixtures.facts())
        assertEquals(HealthState.HEALTHY, r.state)
    }

    @Test
    fun `storage below 10 percent is attention and below 5 percent critical`() {
        assertEquals(HealthState.ATTENTION, HealthEvaluator.evaluate(Fixtures.facts(availableBytes = 20 * GB)).factor(HealthArea.STORAGE)!!.state)
        assertEquals(HealthState.CRITICAL, HealthEvaluator.evaluate(Fixtures.facts(availableBytes = 10 * GB)).factor(HealthArea.STORAGE)!!.state)
    }

    @Test
    fun `less than 1 GB free is critical even on a small device`() {
        val r = HealthEvaluator.evaluate(Fixtures.facts(totalBytes = 8 * GB, availableBytes = 900_000_000L))
        assertEquals(HealthReason.STORAGE_CRITICAL, r.factor(HealthArea.STORAGE)!!.reason)
    }

    @Test
    fun `missing temperature is labelled unavailable, not normal`() {
        val r = HealthEvaluator.evaluate(Fixtures.facts(tempC = null, thermal = null))
        assertEquals(HealthReason.BATTERY_TEMP_UNAVAILABLE, r.factor(HealthArea.BATTERY)!!.reason)
        assertEquals(HealthReason.THERMAL_UNAVAILABLE, r.factor(HealthArea.THERMAL)!!.reason)
        assertEquals(HealthState.HEALTHY, r.state)
    }

    @Test
    fun `implausible sensor values are unavailable`() {
        val r = HealthEvaluator.evaluate(Fixtures.facts(tempC = 6553.5f))
        assertEquals(HealthReason.BATTERY_TEMP_UNAVAILABLE, r.factor(HealthArea.BATTERY)!!.reason)
    }

    @Test
    fun `overall state is the worst factor`() {
        val r = HealthEvaluator.evaluate(Fixtures.facts(tempC = 46f, validated = false))
        assertEquals(HealthState.CRITICAL, r.state)
        assertEquals(HealthState.ATTENTION, r.factor(HealthArea.NETWORK)!!.state)
    }

    @Test
    fun `offline is not a device health problem`() {
        val r = HealthEvaluator.evaluate(Fixtures.facts(connected = false, validated = false))
        assertEquals(HealthState.HEALTHY, r.factor(HealthArea.NETWORK)!!.state)
    }

    @Test
    fun `system low memory is attention, never critical`() {
        val r = HealthEvaluator.evaluate(Fixtures.facts(lowMemory = true))
        assertEquals(HealthState.ATTENTION, r.factor(HealthArea.MEMORY)!!.state)
    }
}

class RecommendationEngineTest {
    private val engine = RecommendationEngine()

    private fun input(
        facts: com.bright.onepluscare.core.model.DeviceFacts = Fixtures.facts(),
        mode: StorageAccessMode = StorageAccessMode.FILESYSTEM,
        usage: Boolean = true,
        cleanup: CleanupSummary? = null,
        apps: List<AppRecord>? = null,
        trashBytes: Long = 0,
    ) = AdvisorInput(facts = facts, storageMode = mode, usageAccess = usage, cleanup = cleanup, apps = apps, trashFileBytes = trashBytes)

    @Test
    fun `healthy device with permissions yields no recommendations`() {
        assertTrue(engine.evaluate(input()).isEmpty())
    }

    @Test
    fun `every recommendation carries evidence, risk, reversibility and action`() {
        val recs = engine.evaluate(
            input(
                facts = Fixtures.facts(availableBytes = 5 * GB, tempC = 47f, charge = ChargeState.CHARGING, thermal = 3, validated = false, patch = "2024-01-05"),
                mode = StorageAccessMode.NONE,
                usage = false,
                trashBytes = 3 * GB,
            ),
        )
        assertTrue(recs.size >= 6)
        recs.forEach { r ->
            assertTrue(r.evidence.isNotEmpty() || r.id in setOf(RecommendationId.STORAGE_PERMISSION_MISSING, RecommendationId.USAGE_ACCESS_MISSING), "evidence for ${r.id}")
        }
        // Sorted by severity
        assertEquals(recs.sortedBy { it.severity.ordinal }.map { it.severity }, recs.map { it.severity })
    }

    @Test
    fun `hot while charging is its own critical rule`() {
        val recs = engine.evaluate(input(facts = Fixtures.facts(tempC = 46f, charge = ChargeState.CHARGING)))
        val r = recs.first { it.id == RecommendationId.BATTERY_HOT_CHARGING }
        assertEquals(Severity.CRITICAL, r.severity)
        assertEquals(46f, (r.evidence.first { it.kind == EvidenceKind.BATTERY_TEMP } as Evidence.Celsius).celsius)
    }

    @Test
    fun `low storage without scan suggests running a scan, with permission when missing`() {
        val noPerm = engine.evaluate(input(facts = Fixtures.facts(availableBytes = 20 * GB), mode = StorageAccessMode.NONE)).first { it.id == RecommendationId.STORAGE_LOW }
        assertEquals(UserAction.GRANT_STORAGE, noPerm.action)
        val perm = engine.evaluate(input(facts = Fixtures.facts(availableBytes = 20 * GB))).first { it.id == RecommendationId.STORAGE_LOW }
        assertEquals(UserAction.RUN_SMART_SCAN, perm.action)
        assertNull(perm.estimatedBenefitBytes)
    }

    @Test
    fun `trash holding space is flagged as irreversible to empty`() {
        val r = engine.evaluate(input(trashBytes = 2 * GB)).first { it.id == RecommendationId.TRASH_HOLDING_SPACE }
        assertEquals(Reversibility.IRREVERSIBLE, r.reversibility)
    }

    @Test
    fun `security patch age thresholds`() {
        assertEquals(30L, RecommendationEngine.patchAgeDays("2025-09-09", NOW))
        assertNull(RecommendationEngine.patchAgeDays("garbage", NOW))
        assertNull(RecommendationEngine.patchAgeDays(null, NOW))
        val r = engine.evaluate(input(facts = Fixtures.facts(patch = "2025-01-01"))).first { it.id == RecommendationId.SECURITY_PATCH_OLD }
        assertEquals(Severity.ATTENTION, r.severity)
    }

    @Test
    fun `unused app rules need usage access and enough apps`() {
        val old = NOW - 200L * 86_400_000L
        val apps = (1..4).map {
            AppRecord("p$it", "App $it", false, true, old, old, 10, 10, 1, 0L, old, if (it == 1) setOf(SensitivePermission.LOCATION) else emptySet(), false)
        }
        val recs = engine.evaluate(input(apps = apps))
        assertTrue(recs.any { it.id == RecommendationId.UNUSED_APPS })
        assertTrue(recs.any { it.id == RecommendationId.SENSITIVE_UNUSED_APPS })
        val noUsage = engine.evaluate(input(apps = apps, usage = false))
        assertFalse(noUsage.any { it.id == RecommendationId.UNUSED_APPS })
    }

    @Test
    fun `battery health failure is critical`() {
        val r = engine.evaluate(input(facts = Fixtures.facts(health = BatteryHealth.DEAD))).first()
        assertEquals(RecommendationId.BATTERY_HEALTH_BAD, r.id)
    }

    @Test
    fun `top limits dashboard items`() {
        val many = input(facts = Fixtures.facts(availableBytes = 5 * GB, tempC = 47f, thermal = 4, validated = false), mode = StorageAccessMode.NONE, usage = false)
        assertEquals(3, engine.top(many).size)
    }
}

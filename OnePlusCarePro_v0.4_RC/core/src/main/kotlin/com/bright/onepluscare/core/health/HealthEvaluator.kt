package com.bright.onepluscare.core.health

import com.bright.onepluscare.core.battery.BatteryInterpreter
import com.bright.onepluscare.core.battery.TemperatureBand
import com.bright.onepluscare.core.battery.ThermalBand
import com.bright.onepluscare.core.model.BatteryHealth
import com.bright.onepluscare.core.model.DeviceFacts

/** Overall state shown on the dashboard. Ordinal order is severity order. */
enum class HealthState { HEALTHY, ATTENTION, CRITICAL }

enum class HealthArea { STORAGE, BATTERY, THERMAL, MEMORY, NETWORK }

data class HealthFactor(
    val area: HealthArea,
    val state: HealthState,
    /** Machine-readable reason key, mapped to localized text by the UI. */
    val reason: HealthReason,
)

enum class HealthReason {
    STORAGE_OK, STORAGE_LOW, STORAGE_CRITICAL,
    BATTERY_TEMP_OK, BATTERY_TEMP_WARM, BATTERY_TEMP_HOT, BATTERY_TEMP_UNAVAILABLE, BATTERY_HEALTH_REPORTED_BAD,
    THERMAL_OK, THERMAL_MODERATE, THERMAL_SEVERE, THERMAL_UNAVAILABLE,
    MEMORY_OK, MEMORY_SYSTEM_LOW,
    NETWORK_OK, NETWORK_OFFLINE, NETWORK_NOT_VALIDATED, NETWORK_CAPTIVE_PORTAL,
}

data class HealthReport(val state: HealthState, val factors: List<HealthFactor>) {
    fun factor(area: HealthArea): HealthFactor? = factors.firstOrNull { it.area == area }
}

/**
 * Deterministic, documented thresholds. There is deliberately no 0-100 "score":
 * a weighted number would imply a precision the inputs do not have.
 */
object HealthEvaluator {
    /** Below this free ratio storage needs attention (Android's own low-storage notice is ~10%). */
    const val STORAGE_ATTENTION_RATIO = 0.10
    const val STORAGE_CRITICAL_RATIO = 0.05
    /** Absolute floor: below 1 GB free, OTA updates and camera capture commonly fail. */
    const val STORAGE_CRITICAL_BYTES = 1_000_000_000L

    fun evaluate(facts: DeviceFacts): HealthReport {
        val factors = listOf(
            storage(facts),
            battery(facts),
            thermal(facts),
            memory(facts),
            network(facts),
        )
        val state = factors.maxOf { it.state }
        return HealthReport(state, factors)
    }

    private fun storage(f: DeviceFacts): HealthFactor {
        val s = f.storage
        val state = when {
            s.totalBytes <= 0L -> HealthState.HEALTHY
            s.freeRatio < STORAGE_CRITICAL_RATIO || s.availableBytes < STORAGE_CRITICAL_BYTES -> HealthState.CRITICAL
            s.freeRatio < STORAGE_ATTENTION_RATIO -> HealthState.ATTENTION
            else -> HealthState.HEALTHY
        }
        val reason = when (state) {
            HealthState.CRITICAL -> HealthReason.STORAGE_CRITICAL
            HealthState.ATTENTION -> HealthReason.STORAGE_LOW
            HealthState.HEALTHY -> HealthReason.STORAGE_OK
        }
        return HealthFactor(HealthArea.STORAGE, state, reason)
    }

    private fun battery(f: DeviceFacts): HealthFactor {
        if (f.battery.health in BAD_HEALTH) {
            return HealthFactor(HealthArea.BATTERY, HealthState.CRITICAL, HealthReason.BATTERY_HEALTH_REPORTED_BAD)
        }
        return when (BatteryInterpreter.temperatureBand(f.battery.temperatureC.valueOrNull())) {
            TemperatureBand.HOT -> HealthFactor(HealthArea.BATTERY, HealthState.CRITICAL, HealthReason.BATTERY_TEMP_HOT)
            TemperatureBand.WARM -> HealthFactor(HealthArea.BATTERY, HealthState.ATTENTION, HealthReason.BATTERY_TEMP_WARM)
            TemperatureBand.NORMAL, TemperatureBand.COOL -> HealthFactor(HealthArea.BATTERY, HealthState.HEALTHY, HealthReason.BATTERY_TEMP_OK)
            TemperatureBand.UNAVAILABLE -> HealthFactor(HealthArea.BATTERY, HealthState.HEALTHY, HealthReason.BATTERY_TEMP_UNAVAILABLE)
        }
    }

    private fun thermal(f: DeviceFacts): HealthFactor =
        when (BatteryInterpreter.thermalBand(f.thermal.status.valueOrNull())) {
            ThermalBand.SEVERE -> HealthFactor(HealthArea.THERMAL, HealthState.CRITICAL, HealthReason.THERMAL_SEVERE)
            ThermalBand.MODERATE -> HealthFactor(HealthArea.THERMAL, HealthState.ATTENTION, HealthReason.THERMAL_MODERATE)
            ThermalBand.NORMAL -> HealthFactor(HealthArea.THERMAL, HealthState.HEALTHY, HealthReason.THERMAL_OK)
            ThermalBand.UNAVAILABLE -> HealthFactor(HealthArea.THERMAL, HealthState.HEALTHY, HealthReason.THERMAL_UNAVAILABLE)
        }

    private fun memory(f: DeviceFacts): HealthFactor =
        if (f.memory.systemLowMemory) HealthFactor(HealthArea.MEMORY, HealthState.ATTENTION, HealthReason.MEMORY_SYSTEM_LOW)
        else HealthFactor(HealthArea.MEMORY, HealthState.HEALTHY, HealthReason.MEMORY_OK)

    private fun network(f: DeviceFacts): HealthFactor = when {
        // Being offline is a user choice (airplane mode), not a device health problem.
        !f.link.connected -> HealthFactor(HealthArea.NETWORK, HealthState.HEALTHY, HealthReason.NETWORK_OFFLINE)
        f.link.captivePortal -> HealthFactor(HealthArea.NETWORK, HealthState.ATTENTION, HealthReason.NETWORK_CAPTIVE_PORTAL)
        !f.link.validated -> HealthFactor(HealthArea.NETWORK, HealthState.ATTENTION, HealthReason.NETWORK_NOT_VALIDATED)
        else -> HealthFactor(HealthArea.NETWORK, HealthState.HEALTHY, HealthReason.NETWORK_OK)
    }

    private val BAD_HEALTH = setOf(BatteryHealth.OVERHEAT, BatteryHealth.DEAD, BatteryHealth.OVER_VOLTAGE, BatteryHealth.FAILURE)
}

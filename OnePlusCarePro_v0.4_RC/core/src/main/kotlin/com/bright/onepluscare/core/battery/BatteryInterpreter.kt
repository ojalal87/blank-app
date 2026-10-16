package com.bright.onepluscare.core.battery

import com.bright.onepluscare.core.model.BatteryFacts
import com.bright.onepluscare.core.model.ChargeState
import com.bright.onepluscare.core.model.Reading
import com.bright.onepluscare.core.model.Source

enum class TemperatureBand { COOL, NORMAL, WARM, HOT, UNAVAILABLE }
enum class ThermalBand { NORMAL, MODERATE, SEVERE, UNAVAILABLE }

/** Label for a PowerManager.THERMAL_STATUS_* value. */
enum class ThermalStatusLabel { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN, UNKNOWN }

object BatteryInterpreter {
    /** Lithium-ion cells are typically specified for 0-45 °C charging; sustained >40 °C accelerates ageing. */
    const val WARM_C = 40.0f
    const val HOT_C = 45.0f
    const val COOL_C = 10.0f

    fun temperatureBand(celsius: Float?): TemperatureBand = when {
        celsius == null || celsius.isNaN() -> TemperatureBand.UNAVAILABLE
        // Values outside a physically plausible range are reported by some devices when no sensor exists.
        celsius < -40f || celsius > 100f -> TemperatureBand.UNAVAILABLE
        celsius >= HOT_C -> TemperatureBand.HOT
        celsius >= WARM_C -> TemperatureBand.WARM
        celsius < COOL_C -> TemperatureBand.COOL
        else -> TemperatureBand.NORMAL
    }

    fun thermalBand(status: Int?): ThermalBand = when {
        status == null || status < 0 -> ThermalBand.UNAVAILABLE
        status >= 3 -> ThermalBand.SEVERE
        status == 2 -> ThermalBand.MODERATE
        else -> ThermalBand.NORMAL
    }

    fun thermalLabel(status: Int?): ThermalStatusLabel = when (status) {
        0 -> ThermalStatusLabel.NONE
        1 -> ThermalStatusLabel.LIGHT
        2 -> ThermalStatusLabel.MODERATE
        3 -> ThermalStatusLabel.SEVERE
        4 -> ThermalStatusLabel.CRITICAL
        5 -> ThermalStatusLabel.EMERGENCY
        6 -> ThermalStatusLabel.SHUTDOWN
        else -> ThermalStatusLabel.UNKNOWN
    }

    /**
     * BATTERY_PROPERTY_CURRENT_NOW returns Int.MIN_VALUE or 0 when unsupported; some OEMs report mA
     * instead of µA, and the sign for charging is not consistent. We only accept plausible values and
     * always mark them OEM-dependent.
     */
    fun normalizeCurrentNow(raw: Int?): Reading<Int> {
        if (raw == null || raw == Int.MIN_VALUE || raw == 0) return Reading.Unavailable(com.bright.onepluscare.core.model.UnavailableReason.NOT_REPORTED)
        if (kotlin.math.abs(raw.toLong()) > 20_000_000L) return Reading.Unavailable(com.bright.onepluscare.core.model.UnavailableReason.OEM_RESTRICTED)
        return Reading.Measured(raw, Source.OEM_DEPENDENT)
    }

    /** True when the device is charging while hot: the combination that matters most for battery ageing. */
    fun hotWhileCharging(facts: BatteryFacts): Boolean =
        facts.chargeState == ChargeState.CHARGING &&
            temperatureBand(facts.temperatureC.valueOrNull()).let { it == TemperatureBand.WARM || it == TemperatureBand.HOT }
}

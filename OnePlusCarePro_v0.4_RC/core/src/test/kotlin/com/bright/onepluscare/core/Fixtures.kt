package com.bright.onepluscare.core

import com.bright.onepluscare.core.model.BatteryFacts
import com.bright.onepluscare.core.model.BatteryHealth
import com.bright.onepluscare.core.model.ChargeState
import com.bright.onepluscare.core.model.DeviceFacts
import com.bright.onepluscare.core.model.DeviceIdentity
import com.bright.onepluscare.core.model.LinkFacts
import com.bright.onepluscare.core.model.MemoryFacts
import com.bright.onepluscare.core.model.PlugType
import com.bright.onepluscare.core.model.Reading
import com.bright.onepluscare.core.model.StorageFacts
import com.bright.onepluscare.core.model.ThermalFacts
import com.bright.onepluscare.core.model.Transport
import com.bright.onepluscare.core.model.UnavailableReason

/** Shared sample data; mirrors the app's preview fixtures. */
object Fixtures {
    const val NOW = 1_760_000_000_000L // 2025-10-09
    const val GB = 1_000_000_000L

    fun facts(
        totalBytes: Long = 256 * GB,
        availableBytes: Long = 120 * GB,
        tempC: Float? = 31.5f,
        charge: ChargeState = ChargeState.DISCHARGING,
        health: BatteryHealth = BatteryHealth.GOOD,
        thermal: Int? = 0,
        lowMemory: Boolean = false,
        connected: Boolean = true,
        validated: Boolean = true,
        captive: Boolean = false,
        patch: String? = "2025-09-01",
        manufacturer: String = "OnePlus",
    ) = DeviceFacts(
        capturedAtMillis = NOW,
        identity = DeviceIdentity(manufacturer, manufacturer, "CPH2581", 36, "16", patch),
        storage = StorageFacts(totalBytes, availableBytes),
        memory = MemoryFacts(12 * GB, 5 * GB, lowMemory),
        battery = BatteryFacts(
            levelPercent = Reading.Measured(80),
            temperatureC = Reading.of(tempC, UnavailableReason.NOT_REPORTED),
            chargeState = charge,
            plug = PlugType.NONE,
            health = health,
            voltageMv = Reading.Measured(4100),
            currentNowMicroAmps = Reading.Unavailable(UnavailableReason.NOT_REPORTED),
            cycleCount = Reading.Unavailable(UnavailableReason.API_LEVEL_TOO_LOW),
        ),
        thermal = ThermalFacts(Reading.of(thermal), Reading.Unavailable(UnavailableReason.NOT_REPORTED)),
        link = LinkFacts(connected, if (connected) setOf(Transport.WIFI) else emptySet(), validated, captive, false),
    )
}

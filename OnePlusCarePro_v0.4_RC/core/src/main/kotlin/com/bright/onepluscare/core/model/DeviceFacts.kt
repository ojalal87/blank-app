package com.bright.onepluscare.core.model

/** Platform-free device snapshot. Every field that a device may not report is a [Reading]. */
data class DeviceFacts(
    val capturedAtMillis: Long,
    val identity: DeviceIdentity,
    val storage: StorageFacts,
    val memory: MemoryFacts,
    val battery: BatteryFacts,
    val thermal: ThermalFacts,
    val link: LinkFacts,
)

data class DeviceIdentity(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val sdkInt: Int,
    val release: String,
    /** "YYYY-MM-DD" from Build.VERSION.SECURITY_PATCH, or null when not reported. */
    val securityPatch: String?,
)

data class StorageFacts(
    val totalBytes: Long,
    val availableBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0L)
    val freeRatio: Double get() = if (totalBytes > 0L) availableBytes.toDouble() / totalBytes else 0.0
}

data class MemoryFacts(
    val totalBytes: Long,
    val availableBytes: Long,
    /** ActivityManager.MemoryInfo.lowMemory: the system's own low-memory verdict. */
    val systemLowMemory: Boolean,
)

enum class ChargeState { CHARGING, DISCHARGING, FULL, NOT_CHARGING, UNKNOWN }
enum class PlugType { AC, USB, WIRELESS, DOCK, NONE, UNKNOWN }
enum class BatteryHealth { GOOD, OVERHEAT, DEAD, OVER_VOLTAGE, COLD, FAILURE, UNKNOWN }

data class BatteryFacts(
    val levelPercent: Reading<Int>,
    val temperatureC: Reading<Float>,
    val chargeState: ChargeState,
    val plug: PlugType,
    val health: BatteryHealth,
    val voltageMv: Reading<Int>,
    /** BATTERY_PROPERTY_CURRENT_NOW. Sign and unit conventions differ by OEM, so always OEM_DEPENDENT. */
    val currentNowMicroAmps: Reading<Int>,
    /** EXTRA_CYCLE_COUNT, Android 14+. */
    val cycleCount: Reading<Int>,
)

data class ThermalFacts(
    /** PowerManager.THERMAL_STATUS_* (0..6), API 29+. */
    val status: Reading<Int>,
    /** PowerManager.getThermalHeadroom(10s), API 30+. 1.0 means throttling is expected. */
    val headroom: Reading<Float>,
)

enum class Transport { WIFI, CELLULAR, ETHERNET, VPN, BLUETOOTH, OTHER }

data class LinkFacts(
    val connected: Boolean,
    val transports: Set<Transport>,
    val validated: Boolean,
    val captivePortal: Boolean,
    val metered: Boolean,
)

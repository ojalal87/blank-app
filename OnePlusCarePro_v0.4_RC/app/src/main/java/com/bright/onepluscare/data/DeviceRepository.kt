package com.bright.onepluscare.data

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.storage.StorageManager
import com.bright.onepluscare.core.battery.BatteryInterpreter
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt

/** Reads documented platform APIs only. Must be called off the main thread (StatFs/StorageStats do I/O). */
class DeviceRepository(private val context: Context) {

    suspend fun facts(): DeviceFacts = withContext(Dispatchers.IO) { readFacts() }

    fun readFacts(): DeviceFacts = DeviceFacts(
        capturedAtMillis = System.currentTimeMillis(),
        identity = DeviceIdentity(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE.orEmpty(),
            securityPatch = Build.VERSION.SECURITY_PATCH?.takeIf { it.isNotBlank() },
        ),
        storage = storage(),
        memory = memory(),
        battery = battery(),
        thermal = thermal(),
        link = link(),
    )

    /**
     * StorageStatsManager reports the same totals as system Settings (including the system partition).
     * StatFs on /data is the fallback; v0.3 used StatFs only, which under-reports the total on most devices.
     */
    private fun storage(): StorageFacts {
        val stats = context.getSystemService(StorageStatsManager::class.java)
        if (stats != null) {
            try {
                return StorageFacts(stats.getTotalBytes(StorageManager.UUID_DEFAULT), stats.getFreeBytes(StorageManager.UUID_DEFAULT))
            } catch (_: IOException) {
            } catch (_: SecurityException) {
            }
        }
        val fs = StatFs(Environment.getDataDirectory().absolutePath)
        return StorageFacts(fs.totalBytes, fs.availableBytes)
    }

    private fun memory(): MemoryFacts {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        return MemoryFacts(info.totalMem, info.availMem, info.lowMemory)
    }

    private fun battery(): BatteryFacts {
        // Sticky broadcast: registerReceiver(null, ...) returns the last value without registering anything.
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = context.getSystemService(BatteryManager::class.java)

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent: Reading<Int> = if (level >= 0 && scale > 0) Reading.Measured(((level * 100f) / scale).roundToInt()) else Reading.Unavailable(UnavailableReason.NOT_REPORTED)

        val tempRaw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val temp: Reading<Float> = if (tempRaw == Int.MIN_VALUE) Reading.Unavailable(UnavailableReason.NOT_REPORTED) else {
            val c = tempRaw / 10f
            if (BatteryInterpreter.temperatureBand(c) == com.bright.onepluscare.core.battery.TemperatureBand.UNAVAILABLE) Reading.Unavailable(UnavailableReason.NOT_REPORTED) else Reading.Measured(c)
        }

        val chargeState = when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> ChargeState.CHARGING
            BatteryManager.BATTERY_STATUS_DISCHARGING -> ChargeState.DISCHARGING
            BatteryManager.BATTERY_STATUS_FULL -> ChargeState.FULL
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> ChargeState.NOT_CHARGING
            else -> ChargeState.UNKNOWN
        }
        val plug = when (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
            0 -> PlugType.NONE
            BatteryManager.BATTERY_PLUGGED_AC -> PlugType.AC
            BatteryManager.BATTERY_PLUGGED_USB -> PlugType.USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> PlugType.WIRELESS
            8 /* BATTERY_PLUGGED_DOCK, API 33 */ -> PlugType.DOCK
            else -> PlugType.UNKNOWN
        }
        val health = when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealth.GOOD
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> BatteryHealth.OVERHEAT
            BatteryManager.BATTERY_HEALTH_DEAD -> BatteryHealth.DEAD
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> BatteryHealth.OVER_VOLTAGE
            BatteryManager.BATTERY_HEALTH_COLD -> BatteryHealth.COLD
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> BatteryHealth.FAILURE
            else -> BatteryHealth.UNKNOWN
        }
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val current = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val cycles: Reading<Int> = if (Build.VERSION.SDK_INT >= 34) {
            val c = intent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1) ?: -1
            if (c >= 0) Reading.Measured(c) else Reading.Unavailable(UnavailableReason.NOT_REPORTED)
        } else {
            Reading.Unavailable(UnavailableReason.API_LEVEL_TOO_LOW)
        }

        return BatteryFacts(
            levelPercent = percent,
            temperatureC = temp,
            chargeState = chargeState,
            plug = plug,
            health = health,
            voltageMv = if (voltage > 0) Reading.Measured(voltage) else Reading.Unavailable(UnavailableReason.NOT_REPORTED),
            currentNowMicroAmps = BatteryInterpreter.normalizeCurrentNow(current),
            cycleCount = cycles,
        )
    }

    private fun thermal(): ThermalFacts {
        val pm = context.getSystemService(PowerManager::class.java)
        val status: Reading<Int> = if (Build.VERSION.SDK_INT >= 29 && pm != null) Reading.Measured(pm.currentThermalStatus) else Reading.Unavailable(UnavailableReason.API_LEVEL_TOO_LOW)
        val headroom: Reading<Float> = if (Build.VERSION.SDK_INT >= 30 && pm != null) {
            val h = pm.getThermalHeadroom(10)
            if (h.isNaN()) Reading.Unavailable(UnavailableReason.NOT_REPORTED) else Reading.Measured(h)
        } else {
            Reading.Unavailable(UnavailableReason.API_LEVEL_TOO_LOW)
        }
        return ThermalFacts(status, headroom)
    }

    private fun link(): LinkFacts {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return LinkFacts(false, emptySet(), false, false, false)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return LinkFacts(false, emptySet(), false, false, false)
        return LinkFacts(
            connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            transports = transports(caps),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captivePortal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }

    companion object {
        fun transports(caps: NetworkCapabilities): Set<Transport> = buildSet {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(Transport.WIFI)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(Transport.CELLULAR)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(Transport.ETHERNET)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(Transport.VPN)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add(Transport.BLUETOOTH)
            if (isEmpty()) add(Transport.OTHER)
        }
    }
}

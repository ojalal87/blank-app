package com.bright.onepluscare.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bright.onepluscare.CareApplication
import com.bright.onepluscare.core.battery.BatteryInterpreter
import com.bright.onepluscare.core.battery.ThermalBand
import com.bright.onepluscare.core.health.HealthEvaluator
import com.bright.onepluscare.core.health.HealthState
import com.bright.onepluscare.core.history.HistoryType
import com.bright.onepluscare.core.settings.NotificationLevel
import kotlinx.coroutines.CancellationException

/**
 * Read-only by construction: it only calls DeviceRepository.facts() and StorageRepository.scanCleanup().
 * It never moves, trashes, purges or expires anything (WorkerSafetyTest asserts this against the source).
 */
class MaintenanceScanWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as CareApplication).graph
        return try {
            val facts = graph.device.facts()
            // Do not add load to a device that is already throttling; try again next period.
            if (BatteryInterpreter.thermalBand(facts.thermal.status.valueOrNull()) != ThermalBand.NORMAL &&
                BatteryInterpreter.thermalBand(facts.thermal.status.valueOrNull()) != ThermalBand.UNAVAILABLE
            ) {
                graph.history.add(HistoryType.SCHEDULED_SCAN, detail = "skipped_thermal")
                return Result.success()
            }
            val settings = graph.settings.settings.value
            val health = HealthEvaluator.evaluate(facts)
            val summary = graph.storage.scanCleanup(settings.scanConfig().copy(maxFiles = SCHEDULED_MAX_FILES)) { }
            graph.history.add(HistoryType.SCHEDULED_SCAN, count = summary.candidates.size, bytes = summary.totalBytes, detail = health.state.name.lowercase())

            val notify = when (settings.notificationLevel) {
                NotificationLevel.OFF -> false
                NotificationLevel.CRITICAL_ONLY -> health.state == HealthState.CRITICAL
                NotificationLevel.IMPORTANT -> health.state != HealthState.HEALTHY || summary.totalBytes >= NOTIFY_RECLAIMABLE_BYTES
            }
            if (notify) Notifications.showScanResult(applicationContext, health.state, summary.totalBytes, summary.candidates.size)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val SCHEDULED_MAX_FILES = 15_000
        const val NOTIFY_RECLAIMABLE_BYTES = 1_000_000_000L
    }
}

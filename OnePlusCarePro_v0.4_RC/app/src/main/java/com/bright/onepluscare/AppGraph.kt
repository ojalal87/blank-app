package com.bright.onepluscare

import android.content.Context
import com.bright.onepluscare.core.duplicates.DuplicateResult
import com.bright.onepluscare.core.history.HistoryType
import com.bright.onepluscare.core.rules.RecommendationEngine
import com.bright.onepluscare.core.session.MaintenanceSession
import com.bright.onepluscare.core.storage.CleanupSummary
import com.bright.onepluscare.data.AppInventory
import com.bright.onepluscare.data.AppsRepository
import com.bright.onepluscare.data.Capabilities
import com.bright.onepluscare.data.DeviceRepository
import com.bright.onepluscare.data.DiagnosticsRepository
import com.bright.onepluscare.data.HistoryRepository
import com.bright.onepluscare.data.LegacyMigrator
import com.bright.onepluscare.data.NetworkRepository
import com.bright.onepluscare.data.NetworkResult
import com.bright.onepluscare.data.SettingsRepository
import com.bright.onepluscare.data.StorageRepository
import com.bright.onepluscare.data.SystemIntents
import com.bright.onepluscare.shizuku.ShizukuGateway
import com.bright.onepluscare.work.MaintenanceScheduler
import com.bright.onepluscare.work.Notifications
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Results that several screens (and the dashboard's recommendations) share for the life of the process. */
data class SharedResults(
    val cleanup: CleanupSummary? = null,
    val duplicates: DuplicateResult? = null,
    val apps: AppInventory? = null,
    val network: NetworkResult? = null,
    val session: MaintenanceSession? = null,
)

/** Manual dependency container. One instance per process, owned by [CareApplication]. */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext
    /** Background failures are contained here and never crash the process. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })

    val settings = SettingsRepository(appContext)
    val history = HistoryRepository(appContext)
    val device = DeviceRepository(appContext)
    val storage = StorageRepository(appContext)
    val apps = AppsRepository(appContext)
    val network = NetworkRepository(appContext)
    val intents = SystemIntents(appContext)
    val shizuku = ShizukuGateway(appContext)
    val scheduler = MaintenanceScheduler(appContext)
    val diagnostics = DiagnosticsRepository(appContext, device, history)
    val tweaks = com.bright.onepluscare.data.TweaksRepository(appContext, shizuku, history)
    val security = com.bright.onepluscare.data.SecurityRepository(appContext)
    val firewall = com.bright.onepluscare.firewall.FirewallStore(appContext)
    val engine = RecommendationEngine()

    private val _results = MutableStateFlow(SharedResults())
    val results: StateFlow<SharedResults> = _results.asStateFlow()

    private val _capabilities = MutableStateFlow(Capabilities.detect(appContext, storage, apps))
    val capabilities: StateFlow<Capabilities> = _capabilities.asStateFlow()

    /** Bumped whenever the trash journal changes so the Trash screen and dashboard reload. */
    private val _trashVersion = MutableStateFlow(0)
    val trashVersion: StateFlow<Int> = _trashVersion.asStateFlow()

    private val _startupDone = MutableStateFlow(false)
    val startupDone: StateFlow<Boolean> = _startupDone.asStateFlow()

    private inline fun step(block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    fun updateResults(transform: (SharedResults) -> SharedResults) {
        synchronized(_results) { _results.value = transform(_results.value) }
    }

    fun trashChanged() {
        _trashVersion.value++
    }

    fun refreshCapabilities() {
        scope.launch {
            _capabilities.value = withContext(Dispatchers.IO) { Capabilities.detect(appContext, storage, apps) }
            shizuku.refresh()
        }
    }

    /**
     * Foreground-only startup work: migrate v0.3 data, recover interrupted trash operations, then apply the
     * user's (opt-in) retention. Expiry is the only automatic destructive action and runs only here, never in
     * the background worker.
     */
    fun startup(): Job = scope.launch(Dispatchers.IO) {
        // Each step is independent: one failing (e.g. WorkManager unavailable) must not skip the others.
        step {
            LegacyMigrator(appContext, history, storage).migrateOnce()
            settings.reload()
        }
        step {
            val report = storage.reconcile()
            if (report.changed) {
                history.add(HistoryType.RECOVERY, count = report.completedMoves + report.rolledBackMoves + report.completedRestores + report.rolledBackRestores + report.adoptedOrphans)
            }
        }
        step {
            val expired = storage.expire(settings.settings.value.trashRetentionDays)
            if (expired.purgedIds.isNotEmpty()) history.add(HistoryType.EXPIRY, count = expired.purgedIds.size, bytes = expired.bytesFreed)
        }
        trashChanged()
        step { Notifications.ensureChannels(appContext) }
        step { scheduler.apply(settings.settings.value.dailyScanEnabled) }
        _startupDone.value = true
        step { shizuku.start() }
    }
}

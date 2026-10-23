package com.bright.onepluscare.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bright.onepluscare.CareApplication
import com.bright.onepluscare.R
import com.bright.onepluscare.core.security.Surface
import com.bright.onepluscare.core.security.WatchAlert
import com.bright.onepluscare.core.security.WatchRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Opt-in background security watch state. Own preferences file (excluded from backup). Holds only what is
 * needed to detect changes: the package-change sequence number, who held special access last time, and the
 * keys of alerts already shown (bounded).
 */
class SecurityWatchStore(context: Context) {
    private val prefs = context.getSharedPreferences("care_watch", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply {
            // Re-baseline when turned on again so old changes are not announced as new.
            if (on) remove(KEY_SURFACES).remove(KEY_SEQ)
        }.apply()
        _enabled.value = on
    }

    var sequence: Int?
        get() = if (prefs.contains(KEY_SEQ)) prefs.getInt(KEY_SEQ, 0) else null
        set(v) { prefs.edit().apply { if (v == null) remove(KEY_SEQ) else putInt(KEY_SEQ, v) }.apply() }

    var surfaces: Map<Surface, Set<String>>?
        get() = prefs.getString(KEY_SURFACES, null)?.let { raw ->
            runCatching {
                val o = JSONObject(raw)
                Surface.entries.associateWith { s -> o.optJSONArray(s.name)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty() }
            }.getOrNull()
        }
        set(v) {
            val o = JSONObject()
            v?.forEach { (k, set) -> o.put(k.name, org.json.JSONArray(set.sorted())) }
            prefs.edit().putString(KEY_SURFACES, o.toString()).apply()
        }

    fun announced(): Set<String> = prefs.getStringSet(KEY_ANNOUNCED, emptySet()).orEmpty()

    fun markAnnounced(keys: Collection<String>) {
        val next = (announced() + keys).toList().takeLast(MAX_ANNOUNCED).toSet()
        prefs.edit().putStringSet(KEY_ANNOUNCED, next).apply()
    }

    var bootCount: Int
        get() = prefs.getInt(KEY_BOOT, -2)
        set(v) { prefs.edit().putInt(KEY_BOOT, v).apply() }

    var lastUsageCheckMillis: Long
        get() = prefs.getLong(KEY_USAGE_AT, 0)
        set(v) { prefs.edit().putLong(KEY_USAGE_AT, v).apply() }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SEQ = "pkg_seq"
        private const val KEY_SURFACES = "surfaces"
        private const val KEY_ANNOUNCED = "announced"
        private const val KEY_USAGE_AT = "usage_at"
        private const val KEY_BOOT = "boot_count"
        private const val MAX_ANNOUNCED = 500
    }
}

object SecurityWatchScheduler {
    const val UNIQUE_WORK = "oneplus_care_security_watch"

    fun apply(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(UNIQUE_WORK)
            return
        }
        val req = PeriodicWorkRequestBuilder<SecurityWatchWorker>(1, TimeUnit.HOURS, 20, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .addTag(UNIQUE_WORK)
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

/**
 * Read-only background watch: never changes anything on the device. Checks (1) apps installed or updated
 * since the last run, (2) new holders of accessibility / notification access / device admin, and every
 * six hours (3) unusual background uploads. Each finding is announced once.
 */
class SecurityWatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? CareApplication ?: return Result.success()
        val g = app.graph
        val store = g.securityWatch
        if (!store.enabled.value) return Result.success()
        return try {
            val alerts = ArrayList<WatchAlert>()
            // 1. Changed packages since the stored sequence (API 26+, no broadcast receiver needed).
            val pm = applicationContext.packageManager
            // Sequence numbers restart at every boot: start from 0 after a reboot.
            val boot = runCatching { android.provider.Settings.Global.getInt(applicationContext.contentResolver, android.provider.Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
            val baseline = store.sequence == null
            if (store.bootCount != boot) {
                store.bootCount = boot
                if (!baseline) store.sequence = 0
            }
            val changed = runCatching { pm.getChangedPackages(store.sequence ?: 0) }.getOrNull()
            if (!baseline && changed != null) {
                val installed = changed.packageNames.filter { runCatching { pm.getPackageInfo(it, 0) }.isSuccess }.toSet()
                alerts += WatchRules.riskyChanged(g.security.assess(installed))
            }
            changed?.let { store.sequence = it.sequenceNumber } ?: run { if (store.sequence == null) store.sequence = 0 }
            // 2. Special access grants.
            val now = g.security.surfaces()
            alerts += WatchRules.newSpecialAccess(store.surfaces, now, applicationContext.packageName)
            store.surfaces = now
            val fresh = WatchRules.fresh(alerts, store.announced())
            fresh.forEach { a -> notify(a, g.security.label(a.packageName)) }
            store.markAnnounced(fresh.map { it.key })
            // 3. Unusual uploads, at most every six hours.
            val t = System.currentTimeMillis()
            if (t - store.lastUsageCheckMillis > TimeUnit.HOURS.toMillis(6)) {
                store.lastUsageCheckMillis = t
                val day = LocalDate.now().toString()
                g.dataUsage.unusualNow()?.forEach { (owner, u) ->
                    val key = "UPLOAD:${owner.uid}:$day"
                    if (key !in store.announced() && owner.packages.isNotEmpty()) {
                        Notifications.showSecurityAlert(
                            applicationContext, key.hashCode(),
                            applicationContext.getString(R.string.watch_upload_title, owner.label),
                            applicationContext.getString(R.string.watch_upload_body, android.text.format.Formatter.formatShortFileSize(applicationContext, u.todayBytes)),
                        )
                        store.markAnnounced(listOf(key))
                    }
                }
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    private fun notify(a: WatchAlert, label: String) {
        val c = applicationContext
        val (title, body) = when (a.kind) {
            WatchAlert.Kind.NEW_RISKY_APP -> c.getString(R.string.watch_risky_title, label) to c.getString(R.string.watch_risky_body)
            WatchAlert.Kind.NEW_SPECIAL_ACCESS -> c.getString(
                when (a.surface) {
                    Surface.ACCESSIBILITY -> R.string.watch_a11y_title
                    Surface.NOTIFICATION_LISTENER -> R.string.watch_nls_title
                    Surface.DEVICE_ADMIN, null -> R.string.watch_admin_title
                },
                label,
            ) to c.getString(R.string.watch_access_body)
        }
        Notifications.showSecurityAlert(c, a.key.hashCode(), title, body)
    }

}

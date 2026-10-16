package com.bright.onepluscare.data

import android.content.Context
import com.bright.onepluscare.core.history.HistoryType
import com.bright.onepluscare.core.migration.LegacyMigration

/** Imports v0.3 SharedPreferences (history + Safe Trash metadata) once, then removes them. */
class LegacyMigrator(
    private val context: Context,
    private val history: HistoryRepository,
    private val storage: StorageRepository,
) {
    fun migrateOnce() {
        val flags = context.getSharedPreferences(FLAGS, Context.MODE_PRIVATE)
        if (flags.getBoolean(KEY_DONE, false)) return

        val oldHistory = context.getSharedPreferences("maintenance_history", Context.MODE_PRIVATE)
        val events = LegacyMigration.parseHistory(oldHistory.getString("events_json", null))
        if (events.isNotEmpty()) history.importEvents(events)

        val oldTrash = context.getSharedPreferences("safe_trash", Context.MODE_PRIVATE)
        val dir = storage.trashDir?.absolutePath
        var imported = 0
        if (dir != null) {
            val entries = LegacyMigration.parseTrash(oldTrash.getString("entries_json", null), dir)
            imported = storage.trashStore?.importEntries(entries) ?: 0
        }

        // Only clear old data once it has been copied (or there was nothing to copy).
        oldHistory.edit().clear().commit()
        if (dir != null) oldTrash.edit().clear().commit()
        context.getSharedPreferences("maintenance_schedule", Context.MODE_PRIVATE).let { old ->
            if (old.getBoolean("enabled", false)) {
                context.getSharedPreferences(SettingsRepository.FILE, Context.MODE_PRIVATE).edit().putBoolean("daily_scan", true).commit()
            }
            old.edit().clear().commit()
        }
        if (events.isNotEmpty() || imported > 0) history.add(HistoryType.RECOVERY, count = events.size + imported, detail = "migrated_v03")
        flags.edit().putBoolean(KEY_DONE, true).commit()
    }

    companion object {
        private const val FLAGS = "care_migrations"
        private const val KEY_DONE = "v03_imported"
    }
}

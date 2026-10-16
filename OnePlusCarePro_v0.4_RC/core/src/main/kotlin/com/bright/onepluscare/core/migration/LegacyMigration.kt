package com.bright.onepluscare.core.migration

import com.bright.onepluscare.core.history.HistoryEvent
import com.bright.onepluscare.core.history.HistoryType
import com.bright.onepluscare.core.trash.SafeTrashStore
import com.bright.onepluscare.core.trash.TrashEntry
import com.bright.onepluscare.core.trash.TrashKind
import com.bright.onepluscare.core.trash.TrashState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID

/**
 * One-time import of v0.3 SharedPreferences data ("maintenance_history"/events_json and
 * "safe_trash"/entries_json). Pure functions over the raw JSON strings so they are unit-testable.
 */
object LegacyMigration {
    private val JSON = Json { ignoreUnknownKeys = true }

    fun parseHistory(raw: String?): List<HistoryEvent> {
        val array = parseArray(raw) ?: return emptyList()
        return array.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val type = when (o.str("type")) {
                "scan" -> HistoryType.SCAN
                "duplicate_scan" -> HistoryType.DUPLICATE_SCAN
                "scheduled_scan" -> HistoryType.SCHEDULED_SCAN
                "trash" -> HistoryType.TRASH
                "restore" -> HistoryType.RESTORE
                "purge" -> HistoryType.PURGE
                "maintenance" -> HistoryType.SESSION
                "schedule" -> HistoryType.SCHEDULE
                "delete" -> HistoryType.PURGE
                else -> HistoryType.OTHER
            }
            HistoryEvent(
                id = o.str("id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                timestampMillis = o.long("time") ?: 0L,
                type = type,
                bytes = (o.long("bytes") ?: 0L).coerceAtLeast(0L),
                // v0.3 "detail" could contain an absolute restore path; only the short title is kept.
                legacyText = o.str("title")?.take(80),
            )
        }
    }

    /**
     * v0.3 stored absolute trash paths. Only entries whose trash file sits directly in the (unchanged)
     * Safe Trash directory are imported; anything else is untrusted and skipped. Orphaned files are still
     * recovered later by SafeTrashStore.reconcile().
     */
    fun parseTrash(raw: String?, trashDirAbsolute: String): List<TrashEntry> {
        val array = parseArray(raw) ?: return emptyList()
        val dir = trashDirAbsolute.trimEnd('/')
        return array.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val kind = if (o.str("kind") == "MEDIA") TrashKind.MEDIA else TrashKind.FILE
            val base = TrashEntry(
                id = o.str("id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                kind = kind,
                state = TrashState.IN_TRASH,
                displayName = o.str("name").orEmpty().ifBlank { "file" },
                category = "legacy",
                sizeBytes = (o.long("size") ?: 0L).coerceAtLeast(0L),
                trashedAtMillis = o.long("time") ?: 0L,
            )
            when (kind) {
                TrashKind.MEDIA -> o.str("uri")?.takeIf { it.startsWith("content://media/") }?.let { base.copy(contentUri = it) }
                TrashKind.FILE -> {
                    val trashPath = o.str("trashPath") ?: return@mapNotNull null
                    val original = o.str("originalPath")?.takeIf { it.startsWith("/") } ?: return@mapNotNull null
                    val parent = trashPath.substringBeforeLast('/', "")
                    val name = trashPath.substringAfterLast('/')
                    if (parent != dir || !SafeTrashStore.isSafeTrashName(name)) return@mapNotNull null
                    base.copy(originalPath = original, trashFileName = name)
                }
            }
        }
    }

    private fun parseArray(raw: String?): JsonArray? {
        if (raw.isNullOrBlank()) return null
        return try { JSON.parseToJsonElement(raw) as? JsonArray } catch (_: Exception) { null }
    }

    private fun JsonObject.str(key: String): String? = (this[key])?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    private fun JsonObject.long(key: String): Long? = (this[key])?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
}

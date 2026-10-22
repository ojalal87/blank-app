package com.bright.onepluscare.core.history

import com.bright.onepluscare.core.io.AtomicTextFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
enum class HistoryType {
    @SerialName("scan") SCAN,
    @SerialName("duplicate_scan") DUPLICATE_SCAN,
    @SerialName("scheduled_scan") SCHEDULED_SCAN,
    @SerialName("trash") TRASH,
    @SerialName("system_trash") SYSTEM_TRASH,
    @SerialName("restore") RESTORE,
    @SerialName("purge") PURGE,
    @SerialName("expiry") EXPIRY,
    @SerialName("schedule") SCHEDULE,
    @SerialName("session") SESSION,
    @SerialName("network") NETWORK,
    @SerialName("export") EXPORT,
    @SerialName("recovery") RECOVERY,
    @SerialName("tweak") TWEAK,
    @SerialName("tweak_undo") TWEAK_UNDO,
    @SerialName("debloat") DEBLOAT,
    @SerialName("other") OTHER,
}

/**
 * One maintenance event. Privacy: events carry counts and byte totals only, never file names, paths
 * or package names, so the history can be shown, exported or backed up without leaking content.
 */
@Serializable
data class HistoryEvent(
    val id: String,
    val timestampMillis: Long,
    val type: HistoryType,
    val count: Int = 0,
    val bytes: Long = 0L,
    /** Optional machine-readable detail key (e.g. "filesystem", "mediastore", "on", "off"). */
    val detail: String? = null,
    /** Present only for events migrated from v0.3, which stored free text. */
    val legacyText: String? = null,
)

@Serializable
private data class HistoryFile(val schema: Int = 2, val events: List<HistoryEvent> = emptyList())

data class HistoryFilter(
    val types: Set<HistoryType> = emptySet(),
    val sinceMillis: Long? = null,
    val untilMillis: Long? = null,
) {
    fun matches(e: HistoryEvent): Boolean =
        (types.isEmpty() || e.type in types) &&
            (sinceMillis == null || e.timestampMillis >= sinceMillis) &&
            (untilMillis == null || e.timestampMillis < untilMillis)

    companion object {
        val CLEANING = setOf(HistoryType.TRASH, HistoryType.SYSTEM_TRASH, HistoryType.PURGE, HistoryType.EXPIRY, HistoryType.RESTORE)
        val SCANS = setOf(HistoryType.SCAN, HistoryType.DUPLICATE_SCAN, HistoryType.SCHEDULED_SCAN)
    }
}

class HistoryStore(
    private val file: AtomicTextFile,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEvents: Int = MAX_EVENTS,
) {
    private val lock = Any()
    private var cache: List<HistoryEvent>? = null

    fun add(type: HistoryType, count: Int = 0, bytes: Long = 0L, detail: String? = null): HistoryEvent {
        val event = HistoryEvent(UUID.randomUUID().toString(), clock(), type, count, bytes, detail)
        synchronized(lock) {
            // History is the only store that is capped: losing the oldest log line loses no user data.
            save((listOf(event) + load()).take(maxEvents))
        }
        return event
    }

    fun list(filter: HistoryFilter = HistoryFilter()): List<HistoryEvent> =
        synchronized(lock) { load() }.filter(filter::matches)

    fun importEvents(events: List<HistoryEvent>): Int = synchronized(lock) {
        val current = load()
        val ids = current.mapTo(HashSet()) { it.id }
        val fresh = events.filter { it.id !in ids }
        if (fresh.isNotEmpty()) save((current + fresh).sortedByDescending { it.timestampMillis }.take(maxEvents))
        fresh.size
    }

    fun clear() = synchronized(lock) { save(emptyList()) }

    private fun load(): List<HistoryEvent> {
        cache?.let { return it }
        val raw = file.read()
        val events = if (raw.isNullOrBlank()) emptyList() else try {
            JSON.decodeFromString(HistoryFile.serializer(), raw).events
        } catch (_: Exception) {
            emptyList()
        }
        cache = events
        return events
    }

    private fun save(events: List<HistoryEvent>) {
        file.write(JSON.encodeToString(HistoryFile.serializer(), HistoryFile(events = events)))
        cache = events
    }

    companion object {
        const val MAX_EVENTS = 500
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

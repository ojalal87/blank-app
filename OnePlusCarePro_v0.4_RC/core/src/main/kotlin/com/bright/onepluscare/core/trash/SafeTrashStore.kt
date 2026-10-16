package com.bright.onepluscare.core.trash

import com.bright.onepluscare.core.io.AtomicTextFile
import com.bright.onepluscare.core.storage.PathCheck
import com.bright.onepluscare.core.storage.PathPolicy
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Input for recording an item the user moved to the Android system trash. */
data class MediaTrashItem(val contentUri: String, val displayName: String, val sizeBytes: Long, val category: String)

/**
 * Journaled, reversible Safe Trash for files the app can address by path.
 *
 * Invariants:
 *  - Every mutation is journaled *before* the filesystem change (write-ahead), so [reconcile] can finish
 *    or roll back an interrupted move/restore after a crash.
 *  - Trash file names are generated (`<uuid>.trash`); user-controlled names never become paths.
 *  - Restore never overwrites: the destination is created exclusively (O_EXCL) and then replaced by an
 *    atomic rename of the trash file, so a concurrently created file is never clobbered.
 *  - Every source/destination path passes [PathPolicy]; a tampered journal cannot escape the allowed roots.
 *  - All operations are serialized by one lock: restore, purge and expiry cannot race on the same entry.
 *  - The journal is never truncated: dropping metadata would orphan files (a v0.3 data-loss bug).
 */
class SafeTrashStore(
    private val trashDir: Path,
    private val journalFile: AtomicTextFile,
    private val policy: PathPolicy,
    /** Where adopted orphan files (no journal entry) are restored to. */
    private val orphanRestoreDir: Path?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private var cache: List<TrashEntry>? = null

    fun entries(): List<TrashEntry> = synchronized(lock) { load() }

    fun trashDirectory(): Path = trashDir

    fun moveToTrash(originalPath: String, category: String): TrashOutcome = synchronized(lock) {
        val source = when (val check = policy.checkExistingFile(originalPath)) {
            is PathCheck.Allowed -> check.path
            is PathCheck.Rejected -> return TrashOutcome.Refused(TrashFailure.PATH_REJECTED)
        }
        if (source.startsWith(trashDir.toAbsolutePath().normalize())) return TrashOutcome.Refused(TrashFailure.PATH_REJECTED)
        val size = try {
            Files.readAttributes(source, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size()
        } catch (_: IOException) {
            return TrashOutcome.Refused(TrashFailure.SOURCE_MISSING)
        }
        val id = newId()
        val pending = TrashEntry(
            id = id,
            kind = TrashKind.FILE,
            state = TrashState.PENDING_MOVE,
            displayName = source.fileName.toString(),
            category = category,
            sizeBytes = size,
            trashedAtMillis = clock(),
            originalPath = source.toString(),
            trashFileName = "$id$TRASH_SUFFIX",
        )
        try {
            Files.createDirectories(trashDir)
        } catch (_: IOException) {
            return TrashOutcome.Refused(TrashFailure.MOVE_FAILED)
        }
        save(load() + pending)
        val destination = trashDir.resolve(pending.trashFileName!!)
        try {
            // No REPLACE_EXISTING: destination is a fresh UUID name. Cross-filesystem moves copy then delete,
            // and Files.move removes the partial copy if deleting the source fails.
            Files.move(source, destination)
        } catch (_: Exception) {
            save(load().filterNot { it.id == id })
            return TrashOutcome.Refused(TrashFailure.MOVE_FAILED)
        }
        val done = pending.copy(state = TrashState.IN_TRASH)
        save(load().map { if (it.id == id) done else it })
        TrashOutcome.Moved(done)
    }

    fun restore(entryId: String): RestoreOutcome = synchronized(lock) {
        val entry = load().firstOrNull { it.id == entryId } ?: return RestoreOutcome.Failed(TrashFailure.NOT_FOUND)
        if (entry.kind != TrashKind.FILE || entry.state != TrashState.IN_TRASH) return RestoreOutcome.Failed(TrashFailure.WRONG_KIND)
        val trashFile = trashFileOf(entry) ?: return RestoreOutcome.Failed(TrashFailure.TRASH_FILE_MISSING)
        if (!Files.isRegularFile(trashFile, LinkOption.NOFOLLOW_LINKS)) {
            save(load().filterNot { it.id == entryId })
            return RestoreOutcome.Failed(TrashFailure.TRASH_FILE_MISSING)
        }
        val original = entry.originalPath ?: return RestoreOutcome.Failed(TrashFailure.RESTORE_TARGET_REJECTED)
        val originalCheck = policy.checkRestoreTarget(original)
        val originalPath = (originalCheck as? PathCheck.Allowed)?.path ?: return RestoreOutcome.Failed(TrashFailure.RESTORE_TARGET_REJECTED)
        val parent = originalPath.parent ?: return RestoreOutcome.Failed(TrashFailure.RESTORE_TARGET_REJECTED)
        try {
            Files.createDirectories(parent)
        } catch (_: IOException) {
            return RestoreOutcome.Failed(TrashFailure.RESTORE_FAILED)
        }
        val baseName = RestoreNaming.safeSegment(originalPath.fileName.toString())

        for ((index, candidateName) in RestoreNaming.candidates(baseName).withIndex()) {
            val target = parent.resolve(candidateName)
            // Re-validate after directory creation: catches a parent replaced by a symlink.
            if (policy.checkRestoreTarget(target.toString()) !is PathCheck.Allowed) {
                return RestoreOutcome.Failed(TrashFailure.RESTORE_TARGET_REJECTED)
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue
            save(load().map { if (it.id == entryId) it.copy(state = TrashState.PENDING_RESTORE, restoreTarget = target.toString()) else it })
            try {
                Files.createFile(target) // O_CREAT|O_EXCL: fails if anything (file or symlink) appeared meanwhile.
            } catch (_: FileAlreadyExistsException) {
                save(load().map { if (it.id == entryId) entry else it })
                continue
            } catch (_: IOException) {
                save(load().map { if (it.id == entryId) entry else it })
                return RestoreOutcome.Failed(TrashFailure.RESTORE_FAILED)
            }
            try {
                // Replaces only the empty placeholder we exclusively created above.
                Files.move(trashFile, target, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                deleteIfEmptyPlaceholder(target)
                save(load().map { if (it.id == entryId) entry else it })
                return RestoreOutcome.Failed(TrashFailure.RESTORE_FAILED)
            }
            save(load().filterNot { it.id == entryId })
            return RestoreOutcome.Restored(target.toString(), renamed = index > 0)
        }
        RestoreOutcome.Failed(TrashFailure.NO_FREE_NAME)
    }

    /** Permanently delete FILE entries. [ids] null means every FILE entry currently in trash. */
    fun purge(ids: Set<String>?): PurgeOutcome = synchronized(lock) {
        val targets = load().filter { it.kind == TrashKind.FILE && it.state == TrashState.IN_TRASH && (ids == null || it.id in ids) }
        purgeEntries(targets)
    }

    /** Automatic expiry. Only runs when the user chose a retention period; never touches MEDIA entries. */
    fun expire(retentionDays: Int?): PurgeOutcome = synchronized(lock) {
        val days = RetentionPolicy.sanitize(retentionDays) ?: return PurgeOutcome(emptySet(), 0L, emptySet())
        val now = clock()
        purgeEntries(load().filter { RetentionPolicy.isExpired(it, now, days) })
    }

    fun recordMedia(items: List<MediaTrashItem>): List<TrashEntry> = synchronized(lock) {
        if (items.isEmpty()) return emptyList()
        val now = clock()
        val existingUris = load().mapNotNullTo(HashSet()) { it.contentUri }
        val added = items.filter { it.contentUri !in existingUris }.map {
            TrashEntry(
                id = newId(),
                kind = TrashKind.MEDIA,
                state = TrashState.IN_TRASH,
                displayName = it.displayName,
                category = it.category,
                sizeBytes = it.sizeBytes,
                trashedAtMillis = now,
                contentUri = it.contentUri,
            )
        }
        save(load() + added)
        added
    }

    /** Drop entries without touching files (e.g. media restored through the system dialog). */
    fun forget(ids: Set<String>) = synchronized(lock) {
        if (ids.isEmpty()) return
        save(load().filterNot { it.id in ids && it.kind == TrashKind.MEDIA })
    }

    /** Keep only MEDIA entries whose URIs are still in the system trash. */
    fun retainMedia(stillTrashedUris: Set<String>): Int = synchronized(lock) {
        val current = load()
        val kept = current.filter { it.kind != TrashKind.MEDIA || it.contentUri in stillTrashedUris }
        if (kept.size != current.size) save(kept)
        current.size - kept.size
    }

    fun importEntries(legacy: List<TrashEntry>): Int = synchronized(lock) {
        val current = load()
        val ids = current.mapTo(HashSet()) { it.id }
        val fresh = legacy.filter { it.id !in ids }
        if (fresh.isNotEmpty()) save(current + fresh)
        fresh.size
    }

    /** Complete or roll back interrupted operations and adopt orphaned trash files. Call at startup. */
    fun reconcile(): ReconcileReport = synchronized(lock) {
        var completedMoves = 0
        var rolledBackMoves = 0
        var completedRestores = 0
        var rolledBackRestores = 0
        var missing = 0
        val result = ArrayList<TrashEntry>()

        for (e in load()) {
            if (e.kind == TrashKind.MEDIA) { result += e; continue }
            val trashFile = trashFileOf(e)
            val trashExists = trashFile != null && Files.isRegularFile(trashFile, LinkOption.NOFOLLOW_LINKS)
            when (e.state) {
                TrashState.PENDING_MOVE -> {
                    val originalExists = e.originalPath?.let { Files.exists(Paths.get(it), LinkOption.NOFOLLOW_LINKS) } == true
                    when {
                        trashExists && !originalExists -> { result += e.copy(state = TrashState.IN_TRASH); completedMoves++ }
                        trashExists && originalExists -> {
                            // Interrupted cross-filesystem copy: the original is intact, drop the partial copy.
                            try { Files.deleteIfExists(trashFile!!) } catch (_: IOException) {}
                            rolledBackMoves++
                        }
                        else -> rolledBackMoves++
                    }
                }
                TrashState.PENDING_RESTORE -> {
                    val target = e.restoreTarget?.let { Paths.get(it) }
                    when {
                        trashExists -> {
                            if (target != null) deleteIfEmptyPlaceholder(target)
                            result += e.copy(state = TrashState.IN_TRASH, restoreTarget = null)
                            rolledBackRestores++
                        }
                        target != null && Files.exists(target, LinkOption.NOFOLLOW_LINKS) -> completedRestores++
                        else -> missing++
                    }
                }
                TrashState.IN_TRASH -> if (trashExists) result += e else missing++
            }
        }

        // Orphans: files in the trash directory with no journal entry (e.g. v0.3 journal truncation).
        var adopted = 0
        val known = result.mapNotNullTo(HashSet()) { it.trashFileName }
        try {
            if (Files.isDirectory(trashDir, LinkOption.NOFOLLOW_LINKS)) {
                Files.newDirectoryStream(trashDir).use { stream ->
                    for (file in stream) {
                        val name = file.fileName.toString()
                        if (name in known || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !isSafeTrashName(name)) continue
                        val display = LEGACY_PREFIX.replace(name.removeSuffix(TRASH_SUFFIX), "")
                        val size = try { Files.size(file) } catch (_: IOException) { 0L }
                        result += TrashEntry(
                            id = newId(),
                            kind = TrashKind.FILE,
                            state = TrashState.IN_TRASH,
                            displayName = display,
                            category = ORPHAN_CATEGORY,
                            sizeBytes = size,
                            trashedAtMillis = try { Files.getLastModifiedTime(file).toMillis() } catch (_: IOException) { clock() },
                            originalPath = orphanRestoreDir?.resolve(RestoreNaming.safeSegment(display))?.toString(),
                            trashFileName = name,
                        )
                        adopted++
                    }
                }
            }
        } catch (_: IOException) {
        }

        val report = ReconcileReport(completedMoves, rolledBackMoves, completedRestores, rolledBackRestores, missing, adopted)
        if (report.changed) save(result)
        report
    }

    private fun purgeEntries(targets: List<TrashEntry>): PurgeOutcome {
        val purged = LinkedHashSet<String>()
        val failed = LinkedHashSet<String>()
        var freed = 0L
        for (e in targets) {
            val file = trashFileOf(e)
            if (file == null || !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                purged += e.id // Nothing on disk: drop the stale entry, nothing freed.
                continue
            }
            try {
                val size = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size()
                Files.delete(file)
                freed += size
                purged += e.id
            } catch (_: IOException) {
                failed += e.id
            }
        }
        if (purged.isNotEmpty()) save(load().filterNot { it.id in purged })
        return PurgeOutcome(purged, freed, failed)
    }

    private fun trashFileOf(e: TrashEntry): Path? {
        val name = e.trashFileName ?: return null
        if (!isSafeTrashName(name)) return null
        val file = trashDir.resolve(name).normalize()
        return if (file.parent == trashDir.normalize()) file else null
    }

    private fun deleteIfEmptyPlaceholder(target: Path) {
        try {
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && Files.size(target) == 0L) Files.delete(target)
        } catch (_: IOException) {
        }
    }

    private fun load(): List<TrashEntry> {
        cache?.let { return it }
        val raw = journalFile.read()
        val parsed = if (raw.isNullOrBlank()) emptyList() else try {
            JSON.decodeFromString(TrashJournal.serializer(), raw).entries
        } catch (_: Exception) {
            // Corrupt journal: keep files on disk; reconcile() will adopt them as orphans.
            emptyList()
        }
        cache = parsed
        return parsed
    }

    private fun save(entries: List<TrashEntry>) {
        journalFile.write(JSON.encodeToString(TrashJournal.serializer(), TrashJournal(entries = entries)))
        cache = entries
    }

    companion object {
        const val TRASH_SUFFIX = ".trash"
        const val ORPHAN_CATEGORY = "recovered"
        private val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._ ()_-]{0,250}$")
        /** v0.3 named trash files "<uuid>_<name>". */
        private val LEGACY_PREFIX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}_")

        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun isSafeTrashName(name: String): Boolean = name != "." && name != ".." && SAFE_NAME.matches(name)
    }
}

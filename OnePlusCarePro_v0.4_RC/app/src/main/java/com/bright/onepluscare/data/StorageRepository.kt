package com.bright.onepluscare.data

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import com.bright.onepluscare.core.duplicates.DuplicateConfig
import com.bright.onepluscare.core.duplicates.DuplicateFinder
import com.bright.onepluscare.core.duplicates.DuplicateResult
import com.bright.onepluscare.core.duplicates.DuplicateStage
import com.bright.onepluscare.core.duplicates.HashableFile
import com.bright.onepluscare.core.io.AtomicTextFile
import com.bright.onepluscare.core.storage.CleanupClassifier
import com.bright.onepluscare.core.storage.CleanupSummary
import com.bright.onepluscare.core.storage.FileFacts
import com.bright.onepluscare.core.storage.FileOrigin
import com.bright.onepluscare.core.storage.FileSystemWalker
import com.bright.onepluscare.core.storage.MediaUriPolicy
import com.bright.onepluscare.core.storage.PathPolicy
import com.bright.onepluscare.core.storage.ScanConfig
import com.bright.onepluscare.core.storage.ScanProgress
import com.bright.onepluscare.core.storage.StorageAccessMode
import com.bright.onepluscare.core.storage.StorageAccessResolver
import com.bright.onepluscare.core.trash.MediaTrashItem
import com.bright.onepluscare.core.trash.PurgeOutcome
import com.bright.onepluscare.core.trash.ReconcileReport
import com.bright.onepluscare.core.trash.RestoreOutcome
import com.bright.onepluscare.core.trash.SafeTrashStore
import com.bright.onepluscare.core.trash.TrashEntry
import com.bright.onepluscare.core.trash.TrashOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.file.Path
import java.nio.file.Paths

/** Progress shared by cleaner and duplicate scans. */
data class ScanStep(val phase: Phase, val done: Int, val total: Int?) {
    enum class Phase { LISTING, PARTIAL_HASH, FULL_HASH, VERIFY }
}

data class TrashBatchResult(val movedIds: Set<String>, val movedBytes: Long, val refused: Int)

/**
 * Shared-storage access for the cleaner, duplicate finder and Safe Trash.
 *
 * Access model (see core StorageAccessResolver):
 *  - FILESYSTEM: All Files Access (11+) or legacy write (8-10). Files are moved into Safe Trash.
 *  - MEDIASTORE: media permissions on 11+. Removal goes through MediaStore.createTrashRequest, which shows a
 *    system confirmation dialog; the system purges after ~30 days and the user can restore.
 *  - Android 8-10 without write permission: nothing is offered for removal (v0.3 permanently deleted here).
 */
class StorageRepository(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    /** Null when shared storage is not mounted; Safe Trash is then unavailable. */
    // getExternalFilesDir can throw when no external volume is registered (some OEM boot states, Robolectric).
    val trashDir: File? = runCatching { context.getExternalFilesDir(null) }.getOrNull()?.let { File(it, TRASH_DIR_NAME) }

    private val storageRoot: Path? = try {
        Environment.getExternalStorageDirectory()?.toPath()?.toRealPath()
    } catch (_: Exception) {
        null
    }

    private val policy: PathPolicy? = storageRoot?.let { root ->
        PathPolicy(listOf(root), listOfNotNull(trashDir?.toPath()?.toAbsolutePath()?.normalize()))
    }

    val trashStore: SafeTrashStore? = if (trashDir != null && policy != null && storageRoot != null) {
        SafeTrashStore(
            trashDir = trashDir.toPath().toAbsolutePath().normalize(),
            journalFile = AtomicTextFile(File(context.filesDir, "trash_journal.json").toPath()),
            policy = policy,
            orphanRestoreDir = storageRoot.resolve(Environment.DIRECTORY_DOWNLOADS).resolve("OnePlus Care Recovered"),
        )
    } else {
        null
    }

    fun accessMode(): StorageAccessMode {
        val sdk = Build.VERSION.SDK_INT
        fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val manager = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
        val legacyWrite = Build.VERSION.SDK_INT <= 29 && granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val mediaRead = when {
            Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO) || granted(Manifest.permission.READ_MEDIA_AUDIO)
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val partial = Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val mode = StorageAccessResolver.resolve(sdk, manager, legacyWrite, mediaRead, partial)
        // Filesystem mode is useless without a working Safe Trash.
        return if (mode == StorageAccessMode.FILESYSTEM && trashStore == null) StorageAccessMode.NONE else mode
    }

    /** Runtime permissions to request for the media path on this API level. */
    fun mediaPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        Build.VERSION.SDK_INT >= 30 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    suspend fun scanCleanup(config: ScanConfig, onProgress: (ScanStep) -> Unit): CleanupSummary = withContext(Dispatchers.IO) {
        val mode = accessMode()
        val now = System.currentTimeMillis()
        when (mode) {
            StorageAccessMode.FILESYSTEM -> {
                val walk = walk(config, onProgress)
                val candidates = walk.files.mapNotNull { CleanupClassifier.classify(it, now, config) }.sortedByDescending { it.file.sizeBytes }
                CleanupSummary(candidates, walk.visited, walk.truncated, mode)
            }
            StorageAccessMode.MEDIASTORE, StorageAccessMode.MEDIASTORE_PARTIAL -> {
                val (files, truncated) = queryMediaStore(config.maxFiles, minSize = 1L, onProgress)
                val candidates = files.mapNotNull { CleanupClassifier.classify(it, now, config) }.sortedByDescending { it.file.sizeBytes }
                CleanupSummary(candidates, files.size, truncated, mode)
            }
            StorageAccessMode.NONE -> CleanupSummary.EMPTY
        }
    }

    suspend fun scanDuplicates(config: ScanConfig, dupConfig: DuplicateConfig, onProgress: (ScanStep) -> Unit): Pair<DuplicateResult, StorageAccessMode> = withContext(Dispatchers.IO) {
        val mode = accessMode()
        val hashables: List<HashableFile> = when (mode) {
            StorageAccessMode.FILESYSTEM -> walk(config.copy(), onProgress) { it.sizeBytes >= dupConfig.minSizeBytes }.files.map { f ->
                HashableFile(f, physicalKey(f.absolutePath)) { f.absolutePath?.let { FileInputStream(it) } }
            }
            StorageAccessMode.MEDIASTORE, StorageAccessMode.MEDIASTORE_PARTIAL -> queryMediaStore(config.maxFiles, dupConfig.minSizeBytes, onProgress).first
                .filterNot { config.isExcluded(it.relativePath) }
                .map { f ->
                    HashableFile(f, f.absolutePath) {
                        f.contentUri?.takeIf(MediaUriPolicy::isMediaStoreItem)?.let { resolver.openInputStream(Uri.parse(it)) }
                    }
                }
            StorageAccessMode.NONE -> emptyList()
        }
        val result = DuplicateFinder(dupConfig).find(hashables) { stage, done, total ->
            val phase = when (stage) {
                DuplicateStage.GROUPING -> ScanStep.Phase.LISTING
                DuplicateStage.PARTIAL_HASH -> ScanStep.Phase.PARTIAL_HASH
                DuplicateStage.FULL_HASH -> ScanStep.Phase.FULL_HASH
                DuplicateStage.VERIFY -> ScanStep.Phase.VERIFY
            }
            onProgress(ScanStep(phase, done, total))
        }
        result to mode
    }

    // --- Safe Trash (filesystem) -------------------------------------------------------------------

    suspend fun moveToSafeTrash(files: List<FileFacts>, category: (FileFacts) -> String): TrashBatchResult = withContext(Dispatchers.IO) {
        val store = trashStore ?: return@withContext TrashBatchResult(emptySet(), 0L, files.size)
        val moved = LinkedHashSet<String>()
        var bytes = 0L
        var refused = 0
        val touched = ArrayList<String>()
        for (f in files) {
            currentCoroutineContext().ensureActive()
            val path = f.absolutePath
            if (f.origin != FileOrigin.FILESYSTEM || path == null) { refused++; continue }
            when (val out = store.moveToTrash(path, category(f))) {
                is TrashOutcome.Moved -> { moved += f.id; bytes += out.entry.sizeBytes; touched += path }
                is TrashOutcome.Refused -> refused++
            }
        }
        rescan(touched)
        TrashBatchResult(moved, bytes, refused)
    }

    suspend fun restore(entryId: String): RestoreOutcome = withContext(Dispatchers.IO) {
        val store = trashStore ?: return@withContext RestoreOutcome.Failed(com.bright.onepluscare.core.trash.TrashFailure.NOT_FOUND)
        store.restore(entryId).also { if (it is RestoreOutcome.Restored) rescan(listOf(it.path)) }
    }

    suspend fun purge(ids: Set<String>?): PurgeOutcome = withContext(Dispatchers.IO) {
        trashStore?.purge(ids) ?: PurgeOutcome(emptySet(), 0L, emptySet())
    }

    suspend fun expire(retentionDays: Int?): PurgeOutcome = withContext(Dispatchers.IO) {
        trashStore?.expire(retentionDays) ?: PurgeOutcome(emptySet(), 0L, emptySet())
    }

    fun trashEntries(): List<TrashEntry> = trashStore?.entries().orEmpty()

    suspend fun reconcile(): ReconcileReport = withContext(Dispatchers.IO) {
        val report = trashStore?.reconcile() ?: ReconcileReport()
        reconcileMedia()
        report
    }

    // --- MediaStore system trash (API 30+) ---------------------------------------------------------

    /** Returns null when the platform cannot do a reversible media trash (API < 30) or no valid URIs remain. */
    fun systemTrashRequest(uris: List<String>, trash: Boolean): IntentSender? {
        if (Build.VERSION.SDK_INT < 30) return null
        val valid = uris.filter(MediaUriPolicy::isMediaStoreItem).map(Uri::parse)
        if (valid.isEmpty()) return null
        return try {
            MediaStore.createTrashRequest(resolver, valid, trash).intentSender
        } catch (_: Exception) {
            null
        }
    }

    fun recordSystemTrash(items: List<MediaTrashItem>): List<TrashEntry> =
        trashStore?.recordMedia(items.filter { MediaUriPolicy.isMediaStoreItem(it.contentUri) }).orEmpty()

    fun forgetMedia(ids: Set<String>) {
        trashStore?.forget(ids)
    }

    /** Drop app records for media the system already purged or the user restored elsewhere. */
    private fun reconcileMedia() {
        val store = trashStore ?: return
        if (Build.VERSION.SDK_INT < 30) return
        val media = store.entries().filter { it.contentUri != null }
        if (media.isEmpty()) return
        val still = HashSet<String>()
        for (e in media) {
            val uri = e.contentUri?.takeIf(MediaUriPolicy::isMediaStoreItem) ?: continue
            val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
            try {
                resolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED), args, null)?.use { c ->
                    if (c.moveToFirst() && c.getInt(1) == 1) still += uri
                }
            } catch (_: Exception) {
                // Permission revoked: keep the record rather than guessing.
                still += uri
            }
        }
        store.retainMedia(still)
    }

    // --- internals -------------------------------------------------------------------------------

    private suspend fun walk(config: ScanConfig, onProgress: (ScanStep) -> Unit, accept: (FileFacts) -> Boolean = { true }): FileSystemWalker.Result {
        val root = storageRoot ?: return FileSystemWalker.Result(emptyList(), 0, false, false)
        val p = policy ?: return FileSystemWalker.Result(emptyList(), 0, false, false)
        val ctx = currentCoroutineContext()
        val result = FileSystemWalker(root, p).walk(
            config,
            isActive = { ctx.isActive },
            onProgress = { pr: ScanProgress -> onProgress(ScanStep(ScanStep.Phase.LISTING, pr.filesVisited, null)) },
            accept = accept,
        )
        ctx.ensureActive()
        return result
    }

    private suspend fun queryMediaStore(maxFiles: Int, minSize: Long, onProgress: (ScanStep) -> Unit): Pair<List<FileFacts>, Boolean> {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.Files.FileColumns.MEDIA_TYPE)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.RELATIVE_PATH)
            @Suppress("DEPRECATION") add(MediaStore.MediaColumns.DATA)
        }.toTypedArray()
        val out = ArrayList<FileFacts>()
        var truncated = false
        val ctx = currentCoroutineContext()
        try {
            resolver.query(collection, projection, "${MediaStore.MediaColumns.SIZE} >= ?", arrayOf(minSize.toString()), "${MediaStore.MediaColumns.SIZE} DESC")?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val relCol = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
                @Suppress("DEPRECATION") val dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                while (c.moveToNext()) {
                    if (out.size >= maxFiles) { truncated = true; break }
                    if (out.size % 500 == 0) {
                        if (!ctx.isActive) break
                        onProgress(ScanStep(ScanStep.Phase.LISTING, out.size, c.count))
                    }
                    val id = c.getLong(idCol)
                    val mediaType = c.getInt(typeCol)
                    val itemUri = itemUri(id, mediaType)
                    val data = if (dataCol >= 0) c.getString(dataCol) else null
                    val rel = if (relCol >= 0) c.getString(relCol).orEmpty() else relativeFromData(data)
                    out += FileFacts(
                        id = "media:$id",
                        displayName = c.getString(nameCol) ?: "file",
                        relativePath = rel,
                        sizeBytes = c.getLong(sizeCol),
                        modifiedMillis = c.getLong(dateCol) * 1000L,
                        origin = FileOrigin.MEDIASTORE,
                        // DATA is used only as a physical-identity key for duplicate detection, never for I/O.
                        absolutePath = data,
                        contentUri = itemUri.toString(),
                        mimeType = c.getString(mimeCol),
                        actionable = StorageAccessResolver.supportsSystemTrash(Build.VERSION.SDK_INT) && MediaUriPolicy.isMediaStoreItem(itemUri.toString()),
                    )
                }
            }
        } catch (_: SecurityException) {
            // Permission revoked mid-scan: return what we have.
        }
        ctx.ensureActive()
        return out to truncated
    }

    /** Typed media URIs work with createTrashRequest on every OEM; generic Files URIs do not on some builds. */
    private fun itemUri(id: Long, mediaType: Int): Uri {
        val base = when (mediaType) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }
        return ContentUris.withAppendedId(base, id)
    }

    private fun relativeFromData(data: String?): String {
        val root = storageRoot?.toString() ?: return ""
        if (data == null || !data.startsWith("$root/")) return ""
        return data.removePrefix("$root/").substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
    }

    private fun physicalKey(path: String?): String? = try {
        path?.let { Os.lstat(it) }?.let { "${it.st_dev}:${it.st_ino}" }
    } catch (_: Exception) {
        path?.let { runCatching { Paths.get(it).toRealPath().toString() }.getOrNull() }
    }

    private fun rescan(paths: List<String>) {
        if (paths.isEmpty()) return
        try {
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Same directory as v0.3 so existing Safe Trash files are found and migrated. */
        const val TRASH_DIR_NAME = "safe_trash"
    }
}

package com.bright.onepluscare.core.storage

import java.util.Locale
import java.util.concurrent.TimeUnit

enum class FileOrigin { FILESYSTEM, MEDIASTORE }

/** What the cleaner knows about one file. [relativePath] is relative to the storage root, '/'-separated, ending with '/'. */
data class FileFacts(
    val id: String,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedMillis: Long,
    val origin: FileOrigin,
    /** Absolute path, only for FILESYSTEM origin. */
    val absolutePath: String? = null,
    /** content:// URI string, only for MEDIASTORE origin. */
    val contentUri: String? = null,
    val mimeType: String? = null,
    /** True only when the app verified it can move/trash this item (writable parent, or MediaStore trash support). */
    val actionable: Boolean,
)

enum class CleanupCategory { TEMP_FILE, INSTALLER_APK, OLD_DOWNLOAD, OLD_SCREENSHOT, LARGE_FILE }

/** Smart Scan result grouping from the product brief. */
enum class CleanupGroup { SAFE_TO_CLEAN, REVIEW_RECOMMENDED }

enum class RemovalMethod { SAFE_TRASH, SYSTEM_TRASH }

data class CleanupCandidate(
    val file: FileFacts,
    val category: CleanupCategory,
    val group: CleanupGroup,
    val method: RemovalMethod,
    val ageDays: Long,
) {
    val id: String get() = file.id
}

data class ScanConfig(
    val largeFileMinBytes: Long = 250L * 1_000_000L,
    /** Large files newer than this are not suggested (a fresh 4K video is probably wanted). */
    val largeFileMinAgeDays: Int = 0,
    val oldDownloadDays: Int = 90,
    val oldScreenshotDays: Int = 30,
    val tempFileDays: Int = 7,
    /** Relative path prefixes (e.g. "DCIM/Camera/") the user excluded. Case-insensitive. */
    val exclusions: List<String> = emptyList(),
    val maxFiles: Int = 60_000,
) {
    fun isExcluded(relativePath: String): Boolean {
        val rel = relativePath.lowercase(Locale.ROOT)
        return exclusions.any { prefix ->
            val p = ExclusionRules.normalize(prefix)?.lowercase(Locale.ROOT) ?: return@any false
            rel.startsWith(p)
        }
    }
}

object ExclusionRules {
    /** Normalize a user-entered exclusion to "A/B/" form; null if it is empty or contains traversal. */
    fun normalize(raw: String): String? {
        val parts = raw.trim().replace('\\', '/').split('/').filter { it.isNotBlank() }
        if (parts.isEmpty() || parts.any { it == ".." || it == "." }) return null
        return parts.joinToString("/", postfix = "/")
    }
}

object CleanupClassifier {
    private val TEMP_EXTENSIONS = setOf("tmp", "temp", "part", "crdownload")

    fun classify(file: FileFacts, nowMillis: Long, config: ScanConfig): CleanupCandidate? {
        if (!file.actionable || file.sizeBytes <= 0L) return null
        if (config.isExcluded(file.relativePath)) return null
        val name = file.displayName.lowercase(Locale.ROOT)
        val rel = file.relativePath.lowercase(Locale.ROOT)
        val ext = name.substringAfterLast('.', "")
        val ageDays = TimeUnit.MILLISECONDS.toDays((nowMillis - file.modifiedMillis).coerceAtLeast(0L))
        val method = if (file.origin == FileOrigin.FILESYSTEM) RemovalMethod.SAFE_TRASH else RemovalMethod.SYSTEM_TRASH

        val (category, group) = when {
            ext in TEMP_EXTENSIONS && ageDays >= config.tempFileDays -> CleanupCategory.TEMP_FILE to CleanupGroup.SAFE_TO_CLEAN
            ext == "apk" || ext == "apks" || ext == "xapk" -> CleanupCategory.INSTALLER_APK to CleanupGroup.REVIEW_RECOMMENDED
            isScreenshotDir(rel) && ageDays >= config.oldScreenshotDays -> CleanupCategory.OLD_SCREENSHOT to CleanupGroup.REVIEW_RECOMMENDED
            isDownloadDir(rel) && ageDays >= config.oldDownloadDays -> CleanupCategory.OLD_DOWNLOAD to CleanupGroup.REVIEW_RECOMMENDED
            file.sizeBytes >= config.largeFileMinBytes && ageDays >= config.largeFileMinAgeDays -> CleanupCategory.LARGE_FILE to CleanupGroup.REVIEW_RECOMMENDED
            else -> return null
        }
        return CleanupCandidate(file, category, group, method, ageDays)
    }

    private fun isScreenshotDir(rel: String): Boolean =
        rel.split('/').any { it == "screenshots" || it == "screenrecorder" || it == "screen recordings" }

    private fun isDownloadDir(rel: String): Boolean = rel.startsWith("download/") || rel.startsWith("downloads/")
}

data class CleanupSummary(
    val candidates: List<CleanupCandidate>,
    val scannedFiles: Int,
    val truncated: Boolean,
    val mode: StorageAccessMode,
) {
    val safeBytes: Long get() = candidates.filter { it.group == CleanupGroup.SAFE_TO_CLEAN }.sumOf { it.file.sizeBytes }
    val reviewBytes: Long get() = candidates.filter { it.group == CleanupGroup.REVIEW_RECOMMENDED }.sumOf { it.file.sizeBytes }
    val totalBytes: Long get() = candidates.sumOf { it.file.sizeBytes }

    fun without(ids: Set<String>): CleanupSummary = copy(candidates = candidates.filterNot { it.id in ids })

    companion object {
        val EMPTY = CleanupSummary(emptyList(), 0, false, StorageAccessMode.NONE)
    }
}

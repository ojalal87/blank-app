package com.bright.onepluscare.core.storage

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet
import java.util.Locale

/** Progress of a long-running scan, reported in batches so the UI never blocks on a full tree walk. */
data class ScanProgress(val filesVisited: Int, val currentArea: String?)

/**
 * Bounded walk of shared storage.
 *
 * - Never follows symbolic links (no loops, no escaping the root).
 * - Skips protected directories (Android/data, Android/obb, the Safe Trash itself) and hidden thumbnail caches.
 * - Stops after [ScanConfig.maxFiles] files and reports truncation instead of silently capping.
 * - Polls [isActive] for cooperative cancellation.
 */
class FileSystemWalker(
    private val root: Path,
    private val policy: PathPolicy,
) {
    data class Result(val files: List<FileFacts>, val visited: Int, val truncated: Boolean, val cancelled: Boolean)

    fun walk(
        config: ScanConfig,
        isActive: () -> Boolean = { true },
        onProgress: (ScanProgress) -> Unit = {},
        accept: (FileFacts) -> Boolean = { true },
    ): Result {
        val out = ArrayList<FileFacts>()
        var visited = 0
        var truncated = false
        var cancelled = false
        val rootAbs = root.toAbsolutePath().normalize()

        val visitor = object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!isActive()) { cancelled = true; return FileVisitResult.TERMINATE }
                if (dir == rootAbs) return FileVisitResult.CONTINUE
                val name = dir.fileName?.toString().orEmpty().lowercase(Locale.ROOT)
                if (name == ".thumbnails" || name == ".trash" || name.startsWith(".pending-")) return FileVisitResult.SKIP_SUBTREE
                if (!policy.isScannableDirectory(dir)) return FileVisitResult.SKIP_SUBTREE
                val rel = relativeDir(rootAbs, dir)
                if (config.isExcluded(rel)) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile || attrs.isSymbolicLink) return FileVisitResult.CONTINUE
                visited++
                if (visited % PROGRESS_EVERY == 0) {
                    if (!isActive()) { cancelled = true; return FileVisitResult.TERMINATE }
                    onProgress(ScanProgress(visited, file.parent?.let { relativeDir(rootAbs, it) }))
                }
                if (visited > config.maxFiles) { truncated = true; return FileVisitResult.TERMINATE }
                val parent = file.parent ?: return FileVisitResult.CONTINUE
                val facts = FileFacts(
                    id = file.toString(),
                    displayName = file.fileName.toString(),
                    relativePath = relativeDir(rootAbs, parent),
                    sizeBytes = attrs.size(),
                    modifiedMillis = attrs.lastModifiedTime().toMillis(),
                    origin = FileOrigin.FILESYSTEM,
                    absolutePath = file.toString(),
                    actionable = Files.isWritable(parent),
                )
                if (accept(facts)) out += facts
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        }

        try {
            Files.walkFileTree(rootAbs, EnumSet.noneOf(FileVisitOption::class.java), MAX_DEPTH, visitor)
        } catch (_: IOException) {
            // Root unreadable: return what we have; caller reports limited access.
        } catch (_: SecurityException) {
        }
        onProgress(ScanProgress(visited, null))
        return Result(out, visited.coerceAtMost(config.maxFiles), truncated, cancelled)
    }

    companion object {
        const val PROGRESS_EVERY = 250
        const val MAX_DEPTH = 24

        fun relativeDir(root: Path, dir: Path): String {
            if (dir == root) return ""
            val rel = root.relativize(dir).toString().replace('\\', '/')
            return if (rel.isEmpty()) "" else "$rel/"
        }
    }
}

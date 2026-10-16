package com.bright.onepluscare.core.storage

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

/** Why a path was refused. Each value is a distinct, testable security decision. */
enum class PathRejection {
    NOT_ABSOLUTE,
    TRAVERSAL_SEGMENT,
    OUTSIDE_ALLOWED_ROOT,
    PROTECTED_DIRECTORY,
    SYMBOLIC_LINK,
    SYMLINKED_PARENT,
    NOT_REGULAR_FILE,
    MISSING,
    IO_ERROR,
}

sealed interface PathCheck {
    data class Allowed(val path: Path) : PathCheck
    data class Rejected(val reason: PathRejection) : PathCheck
}

/**
 * Single choke point for every file the app will move, restore or delete.
 *
 * Rules:
 *  - The path must be absolute and contain no "." / ".." segments (checked on the raw string, so a
 *    tampered journal cannot smuggle traversal through normalization).
 *  - After resolving symlinks the parent directory must still be inside one of [allowedRoots], and the
 *    resolved parent must equal the lexical parent (no symlinked directory components).
 *  - The file itself must not be a symbolic link.
 *  - Paths under Android/data, Android/obb (other apps' private external storage) and under any
 *    [extraProtected] directory (e.g. the Safe Trash directory itself) are refused.
 */
class PathPolicy(
    allowedRoots: List<Path>,
    extraProtected: List<Path> = emptyList(),
) {
    private val roots: List<Path> = allowedRoots.map { it.toAbsolutePath().normalize() }
    private val protected: List<Path> = extraProtected.map { it.toAbsolutePath().normalize() }

    /** Validate an existing regular file that is about to be moved or deleted. */
    fun checkExistingFile(raw: String): PathCheck {
        val lexical = lexicalCheck(raw) ?: return PathCheck.Rejected(PathRejection.TRAVERSAL_SEGMENT)
        if (lexical is PathCheck.Rejected) return lexical
        val path = (lexical as PathCheck.Allowed).path
        return try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return PathCheck.Rejected(PathRejection.MISSING)
            if (Files.isSymbolicLink(path)) return PathCheck.Rejected(PathRejection.SYMBOLIC_LINK)
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return PathCheck.Rejected(PathRejection.NOT_REGULAR_FILE)
            parentCheck(path)
        } catch (_: IOException) {
            PathCheck.Rejected(PathRejection.IO_ERROR)
        } catch (_: SecurityException) {
            PathCheck.Rejected(PathRejection.IO_ERROR)
        }
    }

    /** Validate a restore destination. The file may not exist; its parent may need to be created. */
    fun checkRestoreTarget(raw: String): PathCheck {
        val lexical = lexicalCheck(raw) ?: return PathCheck.Rejected(PathRejection.TRAVERSAL_SEGMENT)
        if (lexical is PathCheck.Rejected) return lexical
        val path = (lexical as PathCheck.Allowed).path
        return try {
            if (Files.isSymbolicLink(path)) return PathCheck.Rejected(PathRejection.SYMBOLIC_LINK)
            // Walk up to the deepest existing ancestor and verify it resolves inside a root without links.
            var ancestor: Path? = path.parent
            while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.parent
            if (ancestor == null) return PathCheck.Rejected(PathRejection.OUTSIDE_ALLOWED_ROOT)
            val real = ancestor.toRealPath()
            if (real != ancestor) return PathCheck.Rejected(PathRejection.SYMLINKED_PARENT)
            if (!isUnderRoot(real) && roots.none { it == real }) return PathCheck.Rejected(PathRejection.OUTSIDE_ALLOWED_ROOT)
            PathCheck.Allowed(path)
        } catch (_: IOException) {
            PathCheck.Rejected(PathRejection.IO_ERROR)
        } catch (_: SecurityException) {
            PathCheck.Rejected(PathRejection.IO_ERROR)
        }
    }

    /** Directory filter used by scanners (lexical only; scanners never follow links). */
    fun isScannableDirectory(dir: Path): Boolean {
        val normalized = dir.toAbsolutePath().normalize()
        return !isProtected(normalized)
    }

    private fun lexicalCheck(raw: String): PathCheck? {
        if (raw.isEmpty() || raw.indexOf('\u0000') >= 0) return PathCheck.Rejected(PathRejection.TRAVERSAL_SEGMENT)
        if (!raw.startsWith("/")) return PathCheck.Rejected(PathRejection.NOT_ABSOLUTE)
        val segments = raw.split('/')
        if (segments.any { it == ".." || it == "." }) return null
        val path = try { Paths.get(raw).normalize() } catch (_: Exception) { return null }
        if (!isUnderRoot(path)) return PathCheck.Rejected(PathRejection.OUTSIDE_ALLOWED_ROOT)
        if (isProtected(path)) return PathCheck.Rejected(PathRejection.PROTECTED_DIRECTORY)
        return PathCheck.Allowed(path)
    }

    private fun parentCheck(path: Path): PathCheck {
        val parent = path.parent ?: return PathCheck.Rejected(PathRejection.OUTSIDE_ALLOWED_ROOT)
        val realParent = parent.toRealPath()
        if (realParent != parent) return PathCheck.Rejected(PathRejection.SYMLINKED_PARENT)
        if (!isUnderRoot(realParent) && roots.none { it == realParent }) return PathCheck.Rejected(PathRejection.OUTSIDE_ALLOWED_ROOT)
        return PathCheck.Allowed(path)
    }

    private fun isUnderRoot(path: Path): Boolean = roots.any { root -> path != root && path.startsWith(root) }

    private fun isProtected(path: Path): Boolean {
        if (protected.any { path.startsWith(it) }) return true
        for (root in roots) {
            if (!path.startsWith(root)) continue
            val rel = root.relativize(path)
            if (rel.nameCount >= 2 && rel.getName(0).toString().lowercase(Locale.ROOT) == "android") {
                val second = rel.getName(1).toString().lowercase(Locale.ROOT)
                if (second == "data" || second == "obb") return true
            }
        }
        return false
    }
}

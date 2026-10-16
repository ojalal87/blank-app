package com.bright.onepluscare.core.io

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Crash-safe small-file persistence: write to a sibling temp file, fsync, then atomically rename over the
 * target. A crash leaves either the old or the new content, never a torn file. Replaces SharedPreferences
 * `apply()`, which is asynchronous and can lose the last write if the process dies.
 */
class AtomicTextFile(private val target: Path) {
    private val temp: Path = target.resolveSibling(target.fileName.toString() + ".tmp")
    private val backup: Path = target.resolveSibling(target.fileName.toString() + ".bak")

    @Synchronized
    fun read(): String? {
        for (candidate in listOf(target, backup)) {
            try {
                if (Files.isRegularFile(candidate)) return String(Files.readAllBytes(candidate), Charsets.UTF_8)
            } catch (_: IOException) {
                // fall through to backup
            }
        }
        return null
    }

    @Synchronized
    fun write(content: String) {
        Files.createDirectories(target.parent)
        val bytes = content.toByteArray(Charsets.UTF_8)
        FileChannel.open(
            temp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { ch ->
            val buf = ByteBuffer.wrap(bytes)
            while (buf.hasRemaining()) ch.write(buf)
            ch.force(true)
        }
        if (Files.exists(target)) {
            try {
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: IOException) {
                // Backup is best-effort.
            }
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized
    fun delete() {
        Files.deleteIfExists(target)
        Files.deleteIfExists(backup)
        Files.deleteIfExists(temp)
    }
}

package com.bright.onepluscare.core.duplicates

import com.bright.onepluscare.core.storage.FileFacts
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.security.MessageDigest

/** A file that can be hashed. [physicalKey] identifies the underlying inode/real path when known. */
class HashableFile(
    val facts: FileFacts,
    val physicalKey: String?,
    val open: () -> InputStream?,
)

data class DuplicateConfig(
    val minSizeBytes: Long = 1_000_000L,
    /** Upper bound on bytes read for full hashing, to keep battery/thermal impact bounded. */
    val maxHashBytes: Long = 8L * 1_000_000_000L,
    val maxHashFiles: Int = 4_000,
    /** Re-read every copy and compare bytes with the keeper before reporting it as a duplicate. */
    val verifyBytes: Boolean = true,
)

data class DuplicateGroup(
    val sha256: String,
    val sizeBytes: Long,
    /** Keeper first, then the other copies. Always >= 2 entries. */
    val files: List<FileFacts>,
) {
    val keeper: FileFacts get() = files.first()
    val copies: List<FileFacts> get() = files.drop(1)
    val reclaimableBytes: Long get() = sizeBytes * (files.size - 1)
}

data class DuplicateResult(
    val groups: List<DuplicateGroup>,
    val consideredFiles: Int,
    val hashedFiles: Int,
    val hashedBytes: Long,
    /** True when [DuplicateConfig] budgets stopped hashing early. */
    val budgetExhausted: Boolean,
    /** Same-size, same-hash files whose bytes differed on verification (should be 0 in practice). */
    val collisionsRejected: Int,
) {
    val reclaimableBytes: Long get() = groups.sumOf { it.reclaimableBytes }
    val duplicateCopies: Int get() = groups.sumOf { it.files.size - 1 }

    fun without(ids: Set<String>): DuplicateResult = copy(
        groups = groups.mapNotNull { g ->
            val remaining = g.files.filterNot { it.id in ids }
            if (remaining.size < 2) null else g.copy(files = remaining)
        },
    )

    companion object {
        val EMPTY = DuplicateResult(emptyList(), 0, 0, 0L, false, 0)
    }
}

enum class DuplicateStage { GROUPING, PARTIAL_HASH, FULL_HASH, VERIFY }

class DuplicateFinder(private val config: DuplicateConfig = DuplicateConfig()) {

    suspend fun find(
        input: List<HashableFile>,
        onProgress: (DuplicateStage, Int, Int) -> Unit = { _, _, _ -> },
    ): DuplicateResult {
        onProgress(DuplicateStage.GROUPING, 0, input.size)
        val bySize = input.asSequence()
            .filter { it.facts.sizeBytes >= config.minSizeBytes }
            .groupBy { it.facts.sizeBytes }
            .values
            .map(::distinctPhysical)
            .filter { it.size > 1 }

        // Stage 1: cheap partial fingerprint (head + tail) to discard most same-size non-duplicates.
        val partialGroups = ArrayList<List<HashableFile>>()
        val totalPartial = bySize.sumOf { it.size }
        var done = 0
        for (group in bySize) {
            currentCoroutineContext().ensureActive()
            val keyed = HashMap<String, MutableList<HashableFile>>()
            for (f in group) {
                val key = partialHash(f)
                done++
                if (done % 25 == 0) onProgress(DuplicateStage.PARTIAL_HASH, done, totalPartial)
                if (key != null) keyed.getOrPut(key) { ArrayList() }.add(f)
            }
            keyed.values.filter { it.size > 1 }.forEach(partialGroups::add)
        }

        // Stage 2: full SHA-256, bounded by byte and file budgets. Largest groups first: most reclaimable.
        partialGroups.sortByDescending { it.first().facts.sizeBytes * (it.size - 1) }
        val fullGroups = ArrayList<Pair<String, List<HashableFile>>>()
        var hashedFiles = 0
        var hashedBytes = 0L
        var exhausted = false
        val totalFull = partialGroups.sumOf { it.size }
        outer@ for (group in partialGroups) {
            val keyed = HashMap<String, MutableList<HashableFile>>()
            for (f in group) {
                currentCoroutineContext().ensureActive()
                if (hashedFiles >= config.maxHashFiles || hashedBytes + f.facts.sizeBytes > config.maxHashBytes) {
                    exhausted = true
                    break@outer
                }
                val digest = fullHash(f)
                hashedFiles++
                hashedBytes += f.facts.sizeBytes
                onProgress(DuplicateStage.FULL_HASH, hashedFiles, totalFull)
                if (digest != null) keyed.getOrPut(digest) { ArrayList() }.add(f)
            }
            keyed.filterValues { it.size > 1 }.forEach { (k, v) -> fullGroups += k to v }
        }

        // Stage 3: defensive byte verification against the keeper; also picks the keeper deterministically.
        var collisions = 0
        val result = ArrayList<DuplicateGroup>()
        for ((index, entry) in fullGroups.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress(DuplicateStage.VERIFY, index + 1, fullGroups.size)
            val (digest, members) = entry
            val ordered = members.sortedWith(KEEPER_ORDER)
            val keeper = ordered.first()
            val confirmed = ArrayList<HashableFile>().apply { add(keeper) }
            for (other in ordered.drop(1)) {
                if (!config.verifyBytes || sameBytes(keeper, other)) confirmed += other else collisions++
            }
            if (confirmed.size > 1) {
                result += DuplicateGroup(digest, keeper.facts.sizeBytes, confirmed.map { it.facts })
            }
        }

        return DuplicateResult(
            groups = result.sortedByDescending { it.reclaimableBytes },
            consideredFiles = input.size,
            hashedFiles = hashedFiles,
            hashedBytes = hashedBytes,
            budgetExhausted = exhausted,
            collisionsRejected = collisions,
        )
    }

    /** Two paths to the same inode (bind mounts, hard links, /sdcard aliases) are one file, not duplicates. */
    private fun distinctPhysical(group: List<HashableFile>): List<HashableFile> {
        val seen = HashSet<String>()
        return group.filter { f -> f.physicalKey == null || seen.add(f.physicalKey) }
    }

    private fun partialHash(f: HashableFile): String? = try {
        f.open()?.use { stream ->
            val md = MessageDigest.getInstance("SHA-256")
            val size = f.facts.sizeBytes
            val head = ByteArray(PARTIAL_CHUNK)
            val headRead = readFully(stream, head)
            md.update(head, 0, headRead)
            if (size > 2L * PARTIAL_CHUNK) {
                skipFully(stream, size - headRead - PARTIAL_CHUNK)
                val tail = ByteArray(PARTIAL_CHUNK)
                md.update(tail, 0, readFully(stream, tail))
            }
            hex(md.digest())
        }
    } catch (_: Exception) {
        null
    }

    private fun fullHash(f: HashableFile): String? = try {
        f.open()?.use { sha256(it) }
    } catch (_: Exception) {
        null
    }

    private fun sameBytes(a: HashableFile, b: HashableFile): Boolean = try {
        val sa = a.open() ?: return false
        sa.use { ia ->
            val sb = b.open() ?: return false
            sb.use { ib -> streamsEqual(ia, ib) }
        }
    } catch (_: Exception) {
        false
    }

    companion object {
        const val PARTIAL_CHUNK = 64 * 1024
        private const val BUFFER = 128 * 1024

        /** Oldest copy is kept; ties broken by shallower/shorter path, then id, so the choice is stable. */
        val KEEPER_ORDER: Comparator<HashableFile> = compareBy<HashableFile>(
            { it.facts.modifiedMillis },
            { it.facts.relativePath.count { c -> c == '/' } },
            { it.facts.relativePath.length + it.facts.displayName.length },
            { it.facts.id },
        )

        fun sha256(input: InputStream): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) md.update(buf, 0, n)
            }
            return hex(md.digest())
        }

        fun streamsEqual(a: InputStream, b: InputStream): Boolean {
            val ba = ByteArray(BUFFER)
            val bb = ByteArray(BUFFER)
            while (true) {
                val na = readFully(a, ba)
                val nb = readFully(b, bb)
                if (na != nb) return false
                if (na == 0) return true
                for (i in 0 until na) if (ba[i] != bb[i]) return false
                if (na < BUFFER) return true
            }
        }

        private fun readFully(input: InputStream, buf: ByteArray): Int {
            var total = 0
            while (total < buf.size) {
                val n = input.read(buf, total, buf.size - total)
                if (n < 0) break
                total += n
            }
            return total
        }

        private fun skipFully(input: InputStream, count: Long) {
            var remaining = count
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped <= 0) {
                    if (input.read() < 0) return
                    remaining--
                } else {
                    remaining -= skipped
                }
            }
        }

        private fun hex(bytes: ByteArray): String {
            val chars = "0123456789abcdef"
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xff
                sb.append(chars[v ushr 4]).append(chars[v and 0x0f])
            }
            return sb.toString()
        }
    }
}

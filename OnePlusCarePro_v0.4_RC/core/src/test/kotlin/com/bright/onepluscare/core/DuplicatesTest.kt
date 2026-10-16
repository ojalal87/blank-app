package com.bright.onepluscare.core

import com.bright.onepluscare.core.duplicates.DuplicateConfig
import com.bright.onepluscare.core.duplicates.DuplicateFinder
import com.bright.onepluscare.core.duplicates.HashableFile
import com.bright.onepluscare.core.duplicates.SelectionGuard
import com.bright.onepluscare.core.storage.FileFacts
import com.bright.onepluscare.core.storage.FileOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DuplicateFinderTest {
    private fun file(id: String, bytes: ByteArray, modified: Long = 0, rel: String = "DCIM/", physical: String? = id, lie: (() -> InputStream)? = null) =
        HashableFile(
            FileFacts(id, "$id.jpg", rel, bytes.size.toLong(), modified, FileOrigin.FILESYSTEM, absolutePath = "/r/$rel$id.jpg", actionable = true),
            physical,
        ) { lie?.invoke() ?: ByteArrayInputStream(bytes) }

    private val cfg = DuplicateConfig(minSizeBytes = 1)
    private val payload = Random(7).nextBytes(300_000)

    @Test
    fun `identical content is grouped and the oldest copy is the keeper`() = runTest {
        val a = file("a", payload, modified = 30)
        val b = file("b", payload.copyOf(), modified = 10)
        val c = file("c", payload.copyOf(), modified = 20)
        val result = DuplicateFinder(cfg).find(listOf(a, b, c))
        assertEquals(1, result.groups.size)
        val g = result.groups.single()
        assertEquals("b", g.keeper.id)
        assertEquals(2L * payload.size, g.reclaimableBytes)
    }

    @Test
    fun `same size but different content is not grouped`() = runTest {
        val other = payload.copyOf().also { it[150_000] = (it[150_000] + 1).toByte() }
        val result = DuplicateFinder(cfg).find(listOf(file("a", payload), file("b", other)))
        assertTrue(result.groups.isEmpty())
    }

    @Test
    fun `two paths to the same physical file are not duplicates`() = runTest {
        val result = DuplicateFinder(cfg).find(listOf(file("a", payload, physical = "dev1:ino9"), file("b", payload.copyOf(), physical = "dev1:ino9")))
        assertTrue(result.groups.isEmpty())
    }

    @Test
    fun `byte verification rejects a copy whose stream differs on re-read (collision defence)`() = runTest {
        var reads = 0
        val tricky = file("b", payload.copyOf()) {
            reads++
            // First two reads (partial + full hash) look identical; the verification read differs.
            if (reads <= 2) ByteArrayInputStream(payload) else ByteArrayInputStream(ByteArray(payload.size))
        }
        val result = DuplicateFinder(cfg).find(listOf(file("a", payload, modified = 1), tricky))
        assertTrue(result.groups.isEmpty())
        assertEquals(1, result.collisionsRejected)
    }

    @Test
    fun `budget limits stop hashing and are reported`() = runTest {
        val files = (1..6).map { file("f$it", payload.copyOf()) }
        val result = DuplicateFinder(cfg.copy(maxHashFiles = 3)).find(files)
        assertTrue(result.budgetExhausted)
        assertEquals(3, result.hashedFiles)
    }

    @Test
    fun `files below the minimum size are ignored`() = runTest {
        val result = DuplicateFinder(DuplicateConfig(minSizeBytes = 1_000_000)).find(listOf(file("a", payload), file("b", payload.copyOf())))
        assertTrue(result.groups.isEmpty())
    }

    @Test
    fun `cancellation propagates`() = runTest {
        val job = Job().also { it.cancel() }
        assertFailsWith<CancellationException> {
            withContext(job) { DuplicateFinder(cfg).find(listOf(file("a", payload), file("b", payload.copyOf()))) }
        }
    }

    @Test
    fun `small files compare fully, including empty tails`() = runTest {
        val small = byteArrayOf(1, 2, 3)
        val result = DuplicateFinder(cfg).find(listOf(file("a", small), file("b", small.copyOf())))
        assertEquals(1, result.groups.size)
    }

    @Test
    fun `selection guard never allows every copy of a group`() = runTest {
        val result = DuplicateFinder(cfg).find(listOf(file("a", payload, modified = 1), file("b", payload.copyOf(), modified = 2)))
        val g = result.groups.single()
        val all = g.files.map { it.id }.toSet()
        val sanitized = SelectionGuard.sanitize(result.groups, all)
        assertEquals(setOf("b"), sanitized.ids)
        assertEquals(setOf("a"), sanitized.protectedIds)
        assertEquals(setOf("b"), SelectionGuard.suggest(result.groups))
        // Selecting only the keeper is allowed (user prefers to keep the other copy).
        assertEquals(setOf("a"), SelectionGuard.sanitize(result.groups, setOf("a")).ids)
        // Unknown ids are dropped.
        assertTrue(SelectionGuard.sanitize(result.groups, setOf("zzz")).ids.isEmpty())
    }

    @Test
    fun `result without removes trashed ids and drops groups that fall below two`() = runTest {
        val r = DuplicateFinder(cfg).find(listOf(file("a", payload, modified = 1), file("b", payload.copyOf(), modified = 2), file("c", payload.copyOf(), modified = 3)))
        assertEquals(1, r.without(setOf("c")).groups.single().copies.size)
        assertTrue(r.without(setOf("b", "c")).groups.isEmpty())
    }
}

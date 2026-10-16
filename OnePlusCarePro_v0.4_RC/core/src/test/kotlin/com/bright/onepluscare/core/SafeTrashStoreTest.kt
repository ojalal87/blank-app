package com.bright.onepluscare.core

import com.bright.onepluscare.core.io.AtomicTextFile
import com.bright.onepluscare.core.storage.PathPolicy
import com.bright.onepluscare.core.trash.MediaTrashItem
import com.bright.onepluscare.core.trash.RestoreNaming
import com.bright.onepluscare.core.trash.RestoreOutcome
import com.bright.onepluscare.core.trash.RetentionPolicy
import com.bright.onepluscare.core.trash.SafeTrashStore
import com.bright.onepluscare.core.trash.TrashEntry
import com.bright.onepluscare.core.trash.TrashFailure
import com.bright.onepluscare.core.trash.TrashJournal
import com.bright.onepluscare.core.trash.TrashKind
import com.bright.onepluscare.core.trash.TrashOutcome
import com.bright.onepluscare.core.trash.TrashState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafeTrashStoreTest {
    @TempDir lateinit var tmp: Path

    private var now = Fixtures.NOW
    private lateinit var root: Path
    private lateinit var trashDir: Path
    private lateinit var journalPath: Path

    private fun store(): SafeTrashStore {
        root = Files.createDirectories(tmp.resolve("emulated/0")).toRealPath()
        trashDir = root.resolve("Android/data/com.bright.onepluscare/files/safe_trash")
        journalPath = tmp.resolve("internal/trash_journal.json")
        val policy = PathPolicy(listOf(root), listOf(trashDir))
        return SafeTrashStore(trashDir, AtomicTextFile(journalPath), policy, root.resolve("Download/Recovered"), clock = { now })
    }

    private fun write(rel: String, content: String): Path {
        val p = root.resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, content.toByteArray())
        return p
    }

    @Test
    fun `move then restore returns the exact bytes to the original path`() {
        val s = store()
        val f = write("Download/report.pdf", "hello")
        val moved = s.moveToTrash(f.toString(), "old_download") as TrashOutcome.Moved
        assertFalse(Files.exists(f))
        assertEquals(TrashState.IN_TRASH, moved.entry.state)
        assertEquals(5L, moved.entry.sizeBytes)
        val restored = s.restore(moved.entry.id) as RestoreOutcome.Restored
        assertEquals(f.toString(), restored.path)
        assertFalse(restored.renamed)
        assertContentEquals("hello".toByteArray(), Files.readAllBytes(f))
        assertTrue(s.entries().isEmpty())
    }

    @Test
    fun `restore never overwrites an existing file`() {
        val s = store()
        val f = write("Download/a.txt", "original")
        val id = (s.moveToTrash(f.toString(), "x") as TrashOutcome.Moved).entry.id
        write("Download/a.txt", "newer file the user created")
        val r = s.restore(id) as RestoreOutcome.Restored
        assertTrue(r.renamed)
        assertEquals("a (restored).txt", Path.of(r.path).fileName.toString())
        assertEquals("newer file the user created", String(Files.readAllBytes(f)))
        assertEquals("original", String(Files.readAllBytes(Path.of(r.path))))
    }

    @Test
    fun `restore recreates a deleted parent directory`() {
        val s = store()
        val f = write("Pictures/Trip/p.jpg", "jpg")
        val id = (s.moveToTrash(f.toString(), "x") as TrashOutcome.Moved).entry.id
        Files.delete(f.parent)
        assertIs<RestoreOutcome.Restored>(s.restore(id))
        assertTrue(Files.exists(f))
    }

    @Test
    fun `tampered journal cannot restore outside the root or delete outside the trash dir`() {
        val s = store()
        val victim = Files.write(tmp.resolve("victim.txt"), "keep".toByteArray())
        Files.createDirectories(trashDir)
        Files.write(trashDir.resolve("good.trash"), "payload".toByteArray())
        val evil = listOf(
            TrashEntry("1", TrashKind.FILE, TrashState.IN_TRASH, "x", "c", 7, now, originalPath = tmp.resolve("evil.txt").toString(), trashFileName = "good.trash"),
            TrashEntry("2", TrashKind.FILE, TrashState.IN_TRASH, "x", "c", 4, now, originalPath = "$root/a", trashFileName = "../../../../../../victim.txt"),
            TrashEntry("3", TrashKind.FILE, TrashState.IN_TRASH, "x", "c", 4, now, originalPath = "$root/Download/../../escape", trashFileName = "good.trash"),
        )
        Files.createDirectories(journalPath.parent)
        Files.write(journalPath, SafeTrashStore.JSON.encodeToString(TrashJournal.serializer(), TrashJournal(entries = evil)).toByteArray())
        val fresh = store()
        assertEquals(TrashFailure.RESTORE_TARGET_REJECTED, (fresh.restore("1") as RestoreOutcome.Failed).reason)
        assertEquals(TrashFailure.TRASH_FILE_MISSING, (fresh.restore("2") as RestoreOutcome.Failed).reason)
        assertEquals(TrashFailure.RESTORE_TARGET_REJECTED, (fresh.restore("3") as RestoreOutcome.Failed).reason)
        fresh.purge(null)
        assertTrue(Files.exists(victim), "purge must not follow a traversal trash name")
        assertFalse(Files.exists(tmp.resolve("evil.txt")))
    }

    @Test
    fun `move refuses protected paths, symlinks and missing files`() {
        val s = store()
        val other = write("Android/data/com.other/files/db", "x")
        assertEquals(TrashFailure.PATH_REJECTED, (s.moveToTrash(other.toString(), "c") as TrashOutcome.Refused).reason)
        val target = write("Music/t.mp3", "m")
        val link = Files.createSymbolicLink(root.resolve("Music/link.mp3"), target)
        assertEquals(TrashFailure.PATH_REJECTED, (s.moveToTrash(link.toString(), "c") as TrashOutcome.Refused).reason)
        assertEquals(TrashFailure.PATH_REJECTED, (s.moveToTrash("$root/none", "c") as TrashOutcome.Refused).reason)
        assertTrue(Files.exists(target))
    }

    @Test
    fun `purge deletes only trash files and measures freed bytes`() {
        val s = store()
        val a = s.moveToTrash(write("Download/a", "12345").toString(), "c") as TrashOutcome.Moved
        val b = s.moveToTrash(write("Download/b", "123").toString(), "c") as TrashOutcome.Moved
        val out = s.purge(setOf(a.entry.id))
        assertEquals(5L, out.bytesFreed)
        assertEquals(setOf(a.entry.id), out.purgedIds)
        assertEquals(listOf(b.entry.id), s.entries().map { it.id })
        assertEquals(3L, s.purge(null).bytesFreed)
    }

    @Test
    fun `expiry is off by default and respects the chosen retention`() {
        val s = store()
        val e = (s.moveToTrash(write("Download/a", "x").toString(), "c") as TrashOutcome.Moved).entry
        now += 31L * 86_400_000L
        assertTrue(s.expire(null).purgedIds.isEmpty())
        assertTrue(s.expire(45).purgedIds.isEmpty(), "45 is not an allowed option and must be ignored")
        assertTrue(s.expire(60).purgedIds.isEmpty())
        assertEquals(setOf(e.id), s.expire(30).purgedIds)
    }

    @Test
    fun `clock going backwards never expires items`() {
        val e = TrashEntry("x", TrashKind.FILE, TrashState.IN_TRASH, "n", "c", 1, trashedAtMillis = 2_000)
        assertFalse(RetentionPolicy.isExpired(e, 1_000, 7))
    }

    @Test
    fun `media entries are tracked, de-duplicated and never purged by the app`() {
        val s = store()
        s.recordMedia(listOf(MediaTrashItem("content://media/external/images/media/5", "p.jpg", 100, "dup")))
        s.recordMedia(listOf(MediaTrashItem("content://media/external/images/media/5", "p.jpg", 100, "dup")))
        assertEquals(1, s.entries().size)
        assertEquals(0L, s.purge(null).bytesFreed)
        assertEquals(1, s.entries().size)
        assertEquals(1, s.retainMedia(emptySet()))
        assertTrue(s.entries().isEmpty())
    }

    @Test
    fun `reconcile completes a move interrupted after the file was moved`() {
        val s = store()
        val f = write("Download/a", "data")
        Files.createDirectories(trashDir)
        Files.move(f, trashDir.resolve("id1.trash"))
        seedJournal(TrashEntry("id1", TrashKind.FILE, TrashState.PENDING_MOVE, "a", "c", 4, now, originalPath = f.toString(), trashFileName = "id1.trash"))
        val report = store().reconcile()
        assertEquals(1, report.completedMoves)
        assertEquals(TrashState.IN_TRASH, store().entries().single().state)
    }

    @Test
    fun `reconcile rolls back a partial cross-device copy and keeps the original`() {
        store()
        val f = write("Download/a", "data")
        Files.createDirectories(trashDir)
        Files.write(trashDir.resolve("id1.trash"), "da".toByteArray())
        seedJournal(TrashEntry("id1", TrashKind.FILE, TrashState.PENDING_MOVE, "a", "c", 4, now, originalPath = f.toString(), trashFileName = "id1.trash"))
        val s = store()
        assertEquals(1, s.reconcile().rolledBackMoves)
        assertTrue(Files.exists(f))
        assertFalse(Files.exists(trashDir.resolve("id1.trash")))
        assertTrue(s.entries().isEmpty())
    }

    @Test
    fun `reconcile rolls back an interrupted restore and removes the empty placeholder`() {
        store()
        Files.createDirectories(trashDir)
        Files.write(trashDir.resolve("id1.trash"), "data".toByteArray())
        val target = write("Download/a", "")
        seedJournal(TrashEntry("id1", TrashKind.FILE, TrashState.PENDING_RESTORE, "a", "c", 4, now, originalPath = target.toString(), trashFileName = "id1.trash", restoreTarget = target.toString()))
        val s = store()
        assertEquals(1, s.reconcile().rolledBackRestores)
        assertFalse(Files.exists(target))
        assertEquals(TrashState.IN_TRASH, s.entries().single().state)
        assertNull(s.entries().single().restoreTarget)
    }

    @Test
    fun `reconcile adopts orphaned trash files, including v0_3 names`() {
        store()
        Files.createDirectories(trashDir)
        Files.write(trashDir.resolve("0f8fad5b-d9cb-469f-a165-70867728950e_holiday.jpg"), "x".toByteArray())
        val s = store()
        assertEquals(1, s.reconcile().adoptedOrphans)
        val e = s.entries().single()
        assertEquals("holiday.jpg", e.displayName)
        assertEquals(SafeTrashStore.ORPHAN_CATEGORY, e.category)
        val restored = s.restore(e.id) as RestoreOutcome.Restored
        assertTrue(restored.path.endsWith("Download/Recovered/holiday.jpg"))
    }

    @Test
    fun `corrupt journal does not lose files`() {
        store()
        Files.createDirectories(trashDir)
        Files.write(trashDir.resolve("abc.trash"), "x".toByteArray())
        Files.createDirectories(journalPath.parent)
        Files.write(journalPath, "{not json".toByteArray())
        val s = store()
        assertEquals(1, s.reconcile().adoptedOrphans)
    }

    @Test
    fun `journal survives reopening and is never truncated`() {
        val s = store()
        repeat(320) { i -> s.moveToTrash(write("Download/f$i", "x").toString(), "c") }
        assertEquals(320, store().entries().size)
    }

    @Test
    fun `restore names`() {
        assertEquals(listOf("a.jpg", "a (restored).jpg", "a (restored 2).jpg"), RestoreNaming.candidates("a.jpg", 2).toList())
        assertEquals(listOf(".nomedia", ".nomedia (restored)"), RestoreNaming.candidates(".nomedia", 1).toList())
        assertEquals("restored_file", RestoreNaming.safeSegment(".."))
        assertEquals("a_b", RestoreNaming.safeSegment("a/b"))
    }

    private fun seedJournal(vararg entries: TrashEntry) {
        Files.createDirectories(journalPath.parent)
        AtomicTextFile(journalPath).write(SafeTrashStore.JSON.encodeToString(TrashJournal.serializer(), TrashJournal(entries = entries.toList())))
    }
}

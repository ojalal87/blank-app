package com.bright.onepluscare.core

import com.bright.onepluscare.core.Fixtures.NOW
import com.bright.onepluscare.core.storage.CleanupCategory
import com.bright.onepluscare.core.storage.CleanupClassifier
import com.bright.onepluscare.core.storage.CleanupGroup
import com.bright.onepluscare.core.storage.ExclusionRules
import com.bright.onepluscare.core.storage.FileFacts
import com.bright.onepluscare.core.storage.FileOrigin
import com.bright.onepluscare.core.storage.FileSystemWalker
import com.bright.onepluscare.core.storage.PathCheck
import com.bright.onepluscare.core.storage.PathPolicy
import com.bright.onepluscare.core.storage.PathRejection
import com.bright.onepluscare.core.storage.RemovalMethod
import com.bright.onepluscare.core.storage.ScanConfig
import com.bright.onepluscare.core.storage.StorageAccessMode
import com.bright.onepluscare.core.storage.StorageAccessResolver
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathPolicyTest {
    @TempDir lateinit var tmp: Path

    private fun setup(): Pair<Path, PathPolicy> {
        val root = Files.createDirectories(tmp.resolve("emulated/0")).toRealPath()
        return root to PathPolicy(listOf(root), listOf(root.resolve("Android/data/app/files/safe_trash")))
    }

    private fun reason(c: PathCheck) = (c as PathCheck.Rejected).reason

    @Test
    fun `accepts a regular file inside the root`() {
        val (root, policy) = setup()
        val f = Files.createDirectories(root.resolve("Download")).resolve("a.bin").also { Files.write(it, byteArrayOf(1)) }
        assertIs<PathCheck.Allowed>(policy.checkExistingFile(f.toString()))
    }

    @Test
    fun `rejects traversal, relative paths and NUL bytes`() {
        val (root, policy) = setup()
        assertEquals(PathRejection.TRAVERSAL_SEGMENT, reason(policy.checkExistingFile("$root/Download/../../../etc/passwd")))
        assertEquals(PathRejection.TRAVERSAL_SEGMENT, reason(policy.checkExistingFile("$root/./a")))
        assertEquals(PathRejection.NOT_ABSOLUTE, reason(policy.checkExistingFile("Download/a")))
        assertEquals(PathRejection.TRAVERSAL_SEGMENT, reason(policy.checkExistingFile("$root/a\u0000b")))
        assertEquals(PathRejection.TRAVERSAL_SEGMENT, reason(policy.checkRestoreTarget("$root/x/../../../../tmp/evil")))
    }

    @Test
    fun `rejects files outside the root and the root itself`() {
        val (root, policy) = setup()
        val outside = Files.write(tmp.resolve("outside.txt"), byteArrayOf(1))
        assertEquals(PathRejection.OUTSIDE_ALLOWED_ROOT, reason(policy.checkExistingFile(outside.toString())))
        assertEquals(PathRejection.OUTSIDE_ALLOWED_ROOT, reason(policy.checkRestoreTarget(root.toString())))
        assertEquals(PathRejection.OUTSIDE_ALLOWED_ROOT, reason(policy.checkRestoreTarget("${root}evil/x")))
    }

    @Test
    fun `rejects Android data, obb and the trash directory`() {
        val (root, policy) = setup()
        val data = Files.createDirectories(root.resolve("Android/data/com.other/files")).resolve("db").also { Files.write(it, byteArrayOf(1)) }
        val obb = Files.createDirectories(root.resolve("android/OBB/game")).resolve("main.obb").also { Files.write(it, byteArrayOf(1)) }
        assertEquals(PathRejection.PROTECTED_DIRECTORY, reason(policy.checkExistingFile(data.toString())))
        assertEquals(PathRejection.PROTECTED_DIRECTORY, reason(policy.checkExistingFile(obb.toString())))
        assertEquals(PathRejection.PROTECTED_DIRECTORY, reason(policy.checkRestoreTarget("$root/Android/data/app/files/safe_trash/x")))
    }

    @Test
    fun `rejects a symlinked file and a symlinked parent directory`() {
        val (root, policy) = setup()
        val outsideDir = Files.createDirectories(tmp.resolve("secret"))
        val secret = Files.write(outsideDir.resolve("s.txt"), byteArrayOf(1))
        val link = Files.createSymbolicLink(root.resolve("link.txt"), secret)
        assertEquals(PathRejection.SYMBOLIC_LINK, reason(policy.checkExistingFile(link.toString())))
        val dirLink = Files.createSymbolicLink(root.resolve("dirlink"), outsideDir)
        assertEquals(PathRejection.SYMLINKED_PARENT, reason(policy.checkExistingFile("$dirLink/s.txt")))
        assertEquals(PathRejection.SYMLINKED_PARENT, reason(policy.checkRestoreTarget("$dirLink/new.txt")))
    }

    @Test
    fun `restore target may live in a directory that does not exist yet`() {
        val (root, policy) = setup()
        assertIs<PathCheck.Allowed>(policy.checkRestoreTarget("$root/Pictures/New/deep/x.jpg"))
    }

    @Test
    fun `directories and missing files`() {
        val (root, policy) = setup()
        Files.createDirectories(root.resolve("DCIM"))
        assertEquals(PathRejection.NOT_REGULAR_FILE, reason(policy.checkExistingFile("$root/DCIM")))
        assertEquals(PathRejection.MISSING, reason(policy.checkExistingFile("$root/nope")))
    }
}

class FileSystemWalkerTest {
    @TempDir lateinit var tmp: Path

    @Test
    fun `walk skips protected dirs, does not follow symlink loops and reports truncation`() {
        val root = Files.createDirectories(tmp.resolve("root")).toRealPath()
        val policy = PathPolicy(listOf(root))
        Files.createDirectories(root.resolve("Download"))
        Files.createDirectories(root.resolve("Android/data/x"))
        Files.createDirectories(root.resolve("DCIM/.thumbnails"))
        Files.write(root.resolve("Download/a.apk"), ByteArray(10))
        Files.write(root.resolve("Android/data/x/private.db"), ByteArray(10))
        Files.write(root.resolve("DCIM/.thumbnails/t.jpg"), ByteArray(10))
        Files.createSymbolicLink(root.resolve("Download/loop"), root)
        val walker = FileSystemWalker(root, policy)
        val result = walker.walk(ScanConfig())
        assertEquals(listOf("Download/a.apk"), result.files.map { it.relativePath + it.displayName })
        assertFalse(result.truncated)

        repeat(5) { Files.write(root.resolve("Download/f$it.bin"), ByteArray(1)) }
        val limited = walker.walk(ScanConfig(maxFiles = 3))
        assertTrue(limited.truncated)
        assertEquals(3, limited.files.size)
    }

    @Test
    fun `cancellation stops the walk`() {
        val root = Files.createDirectories(tmp.resolve("r2")).toRealPath()
        repeat(600) { Files.write(root.resolve("f$it"), ByteArray(1)) }
        val result = FileSystemWalker(root, PathPolicy(listOf(root))).walk(ScanConfig(), isActive = { false })
        assertTrue(result.cancelled)
    }

    @Test
    fun `excluded directories are not walked`() {
        val root = Files.createDirectories(tmp.resolve("r3")).toRealPath()
        Files.createDirectories(root.resolve("DCIM/Camera"))
        Files.write(root.resolve("DCIM/Camera/v.mp4"), ByteArray(1))
        val result = FileSystemWalker(root, PathPolicy(listOf(root))).walk(ScanConfig(exclusions = listOf("dcim/camera")))
        assertTrue(result.files.isEmpty())
    }
}

class CleanupClassifierTest {
    private val day = 86_400_000L

    private fun file(name: String, rel: String, size: Long = 1_000, ageDays: Long = 0, actionable: Boolean = true, origin: FileOrigin = FileOrigin.FILESYSTEM) =
        FileFacts("id-$rel$name", name, rel, size, NOW - ageDays * day, origin, absolutePath = "/r/$rel$name", actionable = actionable)

    @Test
    fun `temp files are safe to clean only after the age threshold`() {
        assertEquals(CleanupGroup.SAFE_TO_CLEAN, CleanupClassifier.classify(file("x.tmp", "Download/", ageDays = 8), NOW, ScanConfig())!!.group)
        assertNull(CleanupClassifier.classify(file("x.tmp", "Download/", ageDays = 2), NOW, ScanConfig()))
    }

    @Test
    fun `apks, old downloads, screenshots and large files are review only`() {
        val cfg = ScanConfig()
        assertEquals(CleanupCategory.INSTALLER_APK, CleanupClassifier.classify(file("a.APK", "Download/"), NOW, cfg)!!.category)
        assertEquals(CleanupCategory.OLD_DOWNLOAD, CleanupClassifier.classify(file("a.pdf", "Download/", ageDays = 120), NOW, cfg)!!.category)
        assertEquals(CleanupCategory.OLD_SCREENSHOT, CleanupClassifier.classify(file("s.png", "Pictures/Screenshots/", ageDays = 40), NOW, cfg)!!.category)
        val large = CleanupClassifier.classify(file("v.mp4", "DCIM/Camera/", size = 300_000_000), NOW, cfg)!!
        assertEquals(CleanupCategory.LARGE_FILE, large.category)
        assertEquals(CleanupGroup.REVIEW_RECOMMENDED, large.group)
    }

    @Test
    fun `download heuristic matches the top-level folder only`() {
        assertNull(CleanupClassifier.classify(file("a.pdf", "Documents/downloaded-notes/", ageDays = 400), NOW, ScanConfig()))
    }

    @Test
    fun `large-file age filter`() {
        val cfg = ScanConfig(largeFileMinAgeDays = 90)
        assertNull(CleanupClassifier.classify(file("v.mp4", "DCIM/", size = 300_000_000, ageDays = 10), NOW, cfg))
        assertTrue(CleanupClassifier.classify(file("v.mp4", "DCIM/", size = 300_000_000, ageDays = 100), NOW, cfg) != null)
    }

    @Test
    fun `non-actionable or excluded files are never reported`() {
        assertNull(CleanupClassifier.classify(file("a.apk", "Download/", actionable = false), NOW, ScanConfig()))
        assertNull(CleanupClassifier.classify(file("a.apk", "Download/"), NOW, ScanConfig(exclusions = listOf("Download"))))
    }

    @Test
    fun `media store items use system trash`() {
        val c = CleanupClassifier.classify(file("a.apk", "Download/", origin = FileOrigin.MEDIASTORE), NOW, ScanConfig())!!
        assertEquals(RemovalMethod.SYSTEM_TRASH, c.method)
    }

    @Test
    fun `exclusion normalization rejects traversal`() {
        assertEquals("DCIM/Camera/", ExclusionRules.normalize(" /DCIM//Camera/ "))
        assertNull(ExclusionRules.normalize("../Android"))
        assertNull(ExclusionRules.normalize("  "))
    }

    @Test
    fun `storage access resolution across API levels`() {
        assertEquals(StorageAccessMode.FILESYSTEM, StorageAccessResolver.resolve(34, true, false, false, false))
        assertEquals(StorageAccessMode.MEDIASTORE, StorageAccessResolver.resolve(33, false, false, true, false))
        assertEquals(StorageAccessMode.MEDIASTORE_PARTIAL, StorageAccessResolver.resolve(34, false, false, false, true))
        assertEquals(StorageAccessMode.FILESYSTEM, StorageAccessResolver.resolve(29, false, true, true, false))
        // Android 8-10 without write permission: no media removal path exists without permanent delete.
        assertEquals(StorageAccessMode.NONE, StorageAccessResolver.resolve(28, false, false, true, false))
        assertFalse(StorageAccessResolver.supportsSystemTrash(29))
    }

    @Suppress("unused")
    private fun touch(p: Path, millis: Long) = Files.setLastModifiedTime(p, FileTime.fromMillis(millis))
}

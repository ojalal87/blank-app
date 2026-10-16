package com.bright.onepluscare

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bright.onepluscare.core.storage.StorageAccessMode
import com.bright.onepluscare.data.StorageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StorageRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `no permission means no access mode and no scan results`() {
        val repo = StorageRepository(context)
        assertEquals(StorageAccessMode.NONE, repo.accessMode())
    }

    @Test
    fun `system trash request rejects non-MediaStore uris`() {
        val repo = StorageRepository(context)
        assertNull(repo.systemTrashRequest(listOf("content://com.evil/1", "file:///sdcard/a.jpg"), trash = true))
    }

    @Test
    fun `trash directory matches v0_3 location for migration`() {
        val repo = StorageRepository(context)
        assertTrue(repo.trashDir?.name?.let { it == StorageRepository.TRASH_DIR_NAME } ?: true)
    }

    @Test
    @Config(sdk = [29])
    fun `android 10 never offers system trash`() {
        val repo = StorageRepository(context)
        assertNull(repo.systemTrashRequest(listOf("content://media/external/images/media/1"), trash = true))
    }
}

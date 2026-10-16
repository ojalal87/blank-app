package com.bright.onepluscare

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bright.onepluscare.core.storage.MediaUriPolicy
import com.bright.onepluscare.data.StorageRepository
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device test (Android 11+). Creates a media item owned by this app, then checks that a reversible
 * trash request can be built for it. The system dialog itself is user-driven and covered by TEST_PLAN.md.
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreTrashInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var created: android.net.Uri? = null

    @Test
    fun trashRequestIsBuiltForOwnMediaItem() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "care-test-${System.nanoTime()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CareTest/")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull(uri)
        created = uri
        context.contentResolver.openOutputStream(uri!!)!!.use { it.write(ByteArray(1024)) }
        assertTrue(MediaUriPolicy.isMediaStoreItem(uri.toString()))
        assertNotNull(StorageRepository(context).systemTrashRequest(listOf(uri.toString()), trash = true))
    }

    @After
    fun cleanUp() {
        created?.let { context.contentResolver.delete(it, null, null) }
    }
}

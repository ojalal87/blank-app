package com.bright.onepluscare

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.bright.onepluscare.core.history.HistoryType
import com.bright.onepluscare.work.MaintenanceScanWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** WorkManager test: the scheduled worker succeeds without permissions and never touches files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CareApplication::class)
class WorkerSafetyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `worker runs read-only and records a history event`() = runBlocking {
        val app = context as CareApplication
        app.graph.startup().join()
        val trashDir = app.graph.storage.trashDir
        val before = trashDir?.listFiles()?.size ?: 0
        val sentinel = File(context.getExternalFilesDir(null), "sentinel.tmp").apply { parentFile?.mkdirs(); writeText("x") }

        val worker = TestListenableWorkerBuilder<MaintenanceScanWorker>(context).build()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(sentinel.exists())
        assertEquals(before, trashDir?.listFiles()?.size ?: 0)
        assertTrue(app.graph.history.list().any { it.type == HistoryType.SCHEDULED_SCAN })
    }
}

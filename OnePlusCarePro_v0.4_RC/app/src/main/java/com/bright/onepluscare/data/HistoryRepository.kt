package com.bright.onepluscare.data

import android.content.Context
import com.bright.onepluscare.core.history.HistoryEvent
import com.bright.onepluscare.core.history.HistoryFilter
import com.bright.onepluscare.core.history.HistoryStore
import com.bright.onepluscare.core.history.HistoryType
import com.bright.onepluscare.core.io.AtomicTextFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Process-wide history with a change counter so screens refresh after the worker or another screen writes. */
class HistoryRepository(context: Context) {
    val store = HistoryStore(AtomicTextFile(File(context.filesDir, "history.json").toPath()))
    private val version = MutableStateFlow(0)
    val changes: StateFlow<Int> = version.asStateFlow()

    fun add(type: HistoryType, count: Int = 0, bytes: Long = 0L, detail: String? = null): HistoryEvent =
        store.add(type, count, bytes, detail).also { version.value++ }

    fun list(filter: HistoryFilter = HistoryFilter()): List<HistoryEvent> = store.list(filter)

    fun clear() {
        store.clear()
        version.value++
    }

    fun importEvents(events: List<HistoryEvent>): Int = store.importEvents(events).also { if (it > 0) version.value++ }

    fun notifyExternalChange() {
        version.value++
    }
}

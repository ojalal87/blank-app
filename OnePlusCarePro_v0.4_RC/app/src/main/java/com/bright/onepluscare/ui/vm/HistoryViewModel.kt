package com.bright.onepluscare.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bright.onepluscare.AppGraph
import com.bright.onepluscare.core.history.HistoryEvent
import com.bright.onepluscare.core.history.HistoryFilter
import com.bright.onepluscare.core.history.HistoryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

enum class HistoryRange(val days: Int?) { ALL(null), WEEK(7), MONTH(30) }
enum class HistoryGroup(val types: Set<HistoryType>) {
    ALL(emptySet()),
    CLEANING(HistoryFilter.CLEANING),
    SCANS(HistoryFilter.SCANS),
    OTHER(setOf(HistoryType.SCHEDULE, HistoryType.SESSION, HistoryType.NETWORK, HistoryType.EXPORT, HistoryType.RECOVERY, HistoryType.OTHER)),
}

data class HistoryUiState(
    val events: List<HistoryEvent> = emptyList(),
    val group: HistoryGroup = HistoryGroup.ALL,
    val range: HistoryRange = HistoryRange.ALL,
    val freedBytes: Long = 0L,
)

class HistoryViewModel(private val g: AppGraph) : ViewModel() {
    private val group = MutableStateFlow(HistoryGroup.ALL)
    private val range = MutableStateFlow(HistoryRange.ALL)

    val state: StateFlow<HistoryUiState> = combine(group, range, g.history.changes) { gr, ra, _ ->
        val since = ra.days?.let { System.currentTimeMillis() - it * 86_400_000L }
        val events = g.history.list(HistoryFilter(types = gr.types, sinceMillis = since))
        HistoryUiState(
            events = events,
            group = gr,
            range = ra,
            // Only permanent deletions free space; trash moves do not.
            freedBytes = events.filter { it.type == HistoryType.PURGE || it.type == HistoryType.EXPIRY }.sumOf { it.bytes },
        )
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun setGroup(value: HistoryGroup) { group.value = value }
    fun setRange(value: HistoryRange) { range.value = value }
    fun clear() = g.history.clear()
}

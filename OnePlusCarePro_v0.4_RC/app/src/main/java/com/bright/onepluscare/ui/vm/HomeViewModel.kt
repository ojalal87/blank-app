package com.bright.onepluscare.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bright.onepluscare.AppGraph
import com.bright.onepluscare.core.health.HealthEvaluator
import com.bright.onepluscare.core.health.HealthReport
import com.bright.onepluscare.core.history.HistoryEvent
import com.bright.onepluscare.core.history.HistoryType
import com.bright.onepluscare.core.model.DeviceFacts
import com.bright.onepluscare.core.rules.AdvisorInput
import com.bright.onepluscare.core.rules.Recommendation
import com.bright.onepluscare.core.trash.TrashKind
import com.bright.onepluscare.data.Capabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class HomeState(
    val loading: Boolean = true,
    val facts: DeviceFacts? = null,
    val health: HealthReport? = null,
    val top: List<Recommendation> = emptyList(),
    val all: List<Recommendation> = emptyList(),
    val trashFileBytes: Long = 0L,
    val trashCount: Int = 0,
    val lastMaintenance: HistoryEvent? = null,
    val capabilities: Capabilities? = null,
)

/**
 * Dashboard + Battery & Thermal. Device facts refresh every 5 s only while a screen is collecting
 * (WhileSubscribed), so nothing polls in the background.
 */
class HomeViewModel(private val g: AppGraph) : ViewModel() {
    private val ticker = flow {
        while (true) {
            emit(g.device.facts())
            delay(REFRESH_MS)
        }
    }

    val state: StateFlow<HomeState> = combine(ticker, g.results, g.capabilities, g.trashVersion, g.history.changes) { facts, results, caps, _, _ ->
        val health = HealthEvaluator.evaluate(facts)
        val fileTrash = g.storage.trashEntries().filter { it.kind == TrashKind.FILE }
        val input = AdvisorInput(
            facts = facts,
            health = health,
            storageMode = caps.storageMode,
            cleanup = results.cleanup,
            duplicates = results.duplicates,
            trashFileBytes = fileTrash.sumOf { it.sizeBytes },
            trashFileCount = fileTrash.size,
            usageAccess = caps.usageAccess,
            apps = results.apps?.apps,
            usageWindowDays = results.apps?.windowDays ?: 30,
            network = results.network?.diagnosis,
        )
        val all = g.engine.evaluate(input)
        HomeState(
            loading = false,
            facts = facts,
            health = health,
            top = all.take(3),
            all = all,
            trashFileBytes = input.trashFileBytes,
            trashCount = fileTrash.size,
            lastMaintenance = g.history.list().firstOrNull { it.type in MAINTENANCE_TYPES },
            capabilities = caps,
        )
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    companion object {
        const val REFRESH_MS = 5_000L
        private val MAINTENANCE_TYPES = setOf(HistoryType.SCAN, HistoryType.TRASH, HistoryType.SYSTEM_TRASH, HistoryType.PURGE, HistoryType.SESSION, HistoryType.SCHEDULED_SCAN)
    }
}

package com.bright.onepluscare.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bright.onepluscare.AppGraph
import com.bright.onepluscare.core.apps.AppEvaluator
import com.bright.onepluscare.core.apps.AppQuery
import com.bright.onepluscare.core.apps.AppRecord
import com.bright.onepluscare.data.AppInventory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppsUiState(
    val loading: Boolean = false,
    val progress: Pair<Int, Int>? = null,
    val inventory: AppInventory? = null,
    val query: AppQuery = AppQuery(),
    val visible: List<AppRecord> = emptyList(),
    val usageAccess: Boolean = false,
    val error: Boolean = false,
)

class AppsViewModel(private val g: AppGraph) : ViewModel() {
    private val query = MutableStateFlow(AppQuery())
    private val loading = MutableStateFlow<Pair<Int, Int>?>(null)
    private val error = MutableStateFlow(false)
    private var job: Job? = null

    val state: StateFlow<AppsUiState> = combine(g.results.map { it.apps }, query, loading, g.capabilities, error) { inv, q, progress, caps, err ->
        val now = System.currentTimeMillis()
        AppsUiState(
            loading = progress != null,
            progress = progress,
            inventory = inv,
            query = q,
            visible = inv?.let { AppEvaluator.query(it.apps, q, now, it.windowDays) }.orEmpty(),
            usageAccess = caps.usageAccess,
            error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppsUiState())

    fun loadIfNeeded() {
        val inv = g.results.value.apps
        if (inv == null || inv.usageAccess != g.capabilities.value.usageAccess) reload()
    }

    fun reload() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            error.value = false
            loading.value = 0 to 0
            try {
                val inv = g.apps.load { done, total -> loading.value = done to total }
                g.updateResults { it.copy(apps = inv) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                error.value = true
            } finally {
                loading.value = null
            }
        }
    }

    fun setQuery(transform: (AppQuery) -> AppQuery) = query.update(transform)

    fun openDetails(packageName: String) = g.intents.openAppDetails(packageName)
}

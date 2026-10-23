package com.bright.onepluscare.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bright.onepluscare.AppGraph
import com.bright.onepluscare.core.hardening.HardeningCheck
import com.bright.onepluscare.core.hardening.HardeningEvaluator
import com.bright.onepluscare.core.hardening.HardeningFacts
import com.bright.onepluscare.data.SystemIntents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HardeningUiState(val loading: Boolean = true, val facts: HardeningFacts? = null, val checks: List<HardeningCheck> = emptyList())

class HardeningViewModel(private val g: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(HardeningUiState())
    val state: StateFlow<HardeningUiState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val f = g.hardening.facts(g.shizuku.status.value.running)
            _state.value = HardeningUiState(false, f, HardeningEvaluator.evaluate(f))
        }
    }

    fun open(t: SystemIntents.Target) = g.intents.open(t)
}

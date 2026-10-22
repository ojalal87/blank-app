package com.rootguard.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rootguard.app.data.ControllerState
import com.rootguard.app.data.Draft
import com.rootguard.app.data.FirewallController
import com.rootguard.app.data.PrincipalView
import com.rootguard.core.model.Mode
import com.rootguard.core.model.NetworkScope
import com.rootguard.core.policy.Preview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Wizard states from handoff docs/05_WIZARD_MODES.md (subset implemented in milestone 1). */
enum class Screen { LOADING, WELCOME, CONSENT, PROBE, GOAL, APPS, PREVIEW, RESULT, DASHBOARD, CAPABILITIES, AUDIT }

data class UiLocal(
    val stack: List<Screen> = listOf(Screen.LOADING),
    val probing: Boolean = false,
    val preview: Preview? = null,
    val query: String = "",
    val showSystem: Boolean = false,
    val loadingApps: Boolean = false,
    val applyOk: Boolean? = null,
    val confirmRecover: Boolean = false,
    val wizard: Boolean = true,
) {
    val screen: Screen get() = stack.last()
}

class MainViewModel(val controller: FirewallController) : ViewModel() {
    private val _ui = MutableStateFlow(UiLocal())
    val ui: StateFlow<UiLocal> = _ui.asStateFlow()
    val state: StateFlow<ControllerState> = controller.state
    val draft: StateFlow<Draft> = controller.draft
    val principals: StateFlow<List<PrincipalView>> = controller.principals

    init {
        viewModelScope.launch {
            controller.load()
            if (controller.state.value.consented && controller.state.value.appliedRevision != null) {
                _ui.update { it.copy(stack = listOf(Screen.DASHBOARD), wizard = false) }
                controller.reconcile()
            } else {
                _ui.update { it.copy(stack = listOf(if (controller.state.value.consented) Screen.PROBE else Screen.WELCOME)) }
            }
        }
    }

    fun go(s: Screen) = _ui.update { it.copy(stack = it.stack + s) }
    fun back() = _ui.update { if (it.stack.size > 1) it.copy(stack = it.stack.dropLast(1)) else it }
    private fun reset(s: Screen) = _ui.update { it.copy(stack = listOf(s)) }

    fun acceptConsent() = viewModelScope.launch {
        controller.recordConsent()
        go(Screen.PROBE)
    }

    fun probe() = viewModelScope.launch {
        _ui.update { it.copy(probing = true) }
        controller.probe()
        _ui.update { it.copy(probing = false) }
    }

    fun setMode(m: Mode) {
        controller.setMode(m)
        viewModelScope.launch { controller.saveDraft("GOAL") }
    }

    fun openApps() {
        go(Screen.APPS)
        viewModelScope.launch {
            _ui.update { it.copy(loadingApps = true) }
            controller.refreshInventory()
            _ui.update { it.copy(loadingApps = false) }
        }
    }

    fun setAllowed(p: String, n: NetworkScope, allowed: Boolean) {
        controller.setAllowed(p, n, allowed)
        viewModelScope.launch { controller.saveDraft("IMPORTANT_APPS") }
    }

    fun setQuery(q: String) = _ui.update { it.copy(query = q.take(64)) }
    fun setShowSystem(b: Boolean) = _ui.update { it.copy(showSystem = b) }

    fun openPreview() {
        _ui.update { it.copy(preview = controller.preview()) }
        go(Screen.PREVIEW)
    }

    fun apply() = viewModelScope.launch {
        _ui.update { it.copy(applyOk = null) }
        go(Screen.RESULT)
        val r = controller.apply()
        _ui.update { it.copy(applyOk = r?.phase == com.rootguard.core.model.Phase.VERIFIED) }
    }

    fun finish() {
        _ui.update { it.copy(wizard = false) }
        reset(Screen.DASHBOARD)
    }

    fun editRules() = openApps()

    fun recheck() = viewModelScope.launch { controller.reconcile() }
    fun reapply() = viewModelScope.launch { controller.reapplyLastGood() }
    fun askRecover(show: Boolean) = _ui.update { it.copy(confirmRecover = show) }
    fun recover() = viewModelScope.launch {
        _ui.update { it.copy(confirmRecover = false) }
        controller.recover()
        if (_ui.value.screen != Screen.DASHBOARD) reset(Screen.DASHBOARD)
    }
}

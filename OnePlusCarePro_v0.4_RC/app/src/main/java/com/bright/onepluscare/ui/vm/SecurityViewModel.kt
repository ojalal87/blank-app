package com.bright.onepluscare.ui.vm

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bright.onepluscare.AppGraph
import com.bright.onepluscare.R
import com.bright.onepluscare.core.security.AppAssessment
import com.bright.onepluscare.core.security.FirewallRules
import com.bright.onepluscare.core.security.ScanSummary
import com.bright.onepluscare.data.IocInfo
import com.bright.onepluscare.data.IocUpdateResult
import com.bright.onepluscare.data.NetApp
import com.bright.onepluscare.firewall.FirewallStore
import com.bright.onepluscare.firewall.FirewallVpnService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class SecurityLocal(
    val scanning: Boolean = false,
    val progress: Float? = null,
    val summary: ScanSummary? = null,
    val selected: String? = null,
    val ioc: IocInfo? = null,
    val iocUpdating: Boolean = false,
    val netApps: List<NetApp>? = null,
    val showSystemApps: Boolean = false,
    val query: String = "",
    /** VPN consent intent waiting to be launched by [com.bright.onepluscare.ui.nav.ActivityResultHost]. */
    val pendingConsent: Intent? = null,
    val consentLaunched: Boolean = false,
    val message: String? = null,
)

data class SecurityUiState(val local: SecurityLocal = SecurityLocal(), val blocked: Set<String> = emptySet(), val firewallOn: Boolean = false) {
    val selectedAssessment: AppAssessment? get() = local.summary?.assessments?.firstOrNull { it.facts.packageName == local.selected }
    val visibleNetApps: List<NetApp>
        get() {
            val q = local.query.trim().lowercase(Locale.ROOT)
            return local.netApps.orEmpty().filter { a ->
                (local.showSystemApps || !a.isSystem || a.packageName in blocked) &&
                    (q.isEmpty() || a.label.lowercase(Locale.ROOT).contains(q) || a.packageName.lowercase(Locale.ROOT).contains(q))
            }.sortedByDescending { it.packageName in blocked }
        }
}

class SecurityViewModel(private val g: AppGraph) : ViewModel() {
    private val local = MutableStateFlow(SecurityLocal())
    private var scanJob: Job? = null

    val state: StateFlow<SecurityUiState> = combine(local, g.firewall.blocked, FirewallStore.running) { l, b, r ->
        SecurityUiState(l, b, r)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecurityUiState())

    fun loadIocInfo() {
        if (local.value.ioc != null) return
        viewModelScope.launch { local.update { it.copy(ioc = g.security.ioc().second) } }
    }

    fun scan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            local.update { it.copy(scanning = true, progress = 0f) }
            try {
                val s = g.security.scan { done, total -> local.update { it.copy(progress = if (total > 0) done.toFloat() / total else null) } }
                local.update { it.copy(summary = s, ioc = it.ioc ?: g.security.ioc().second) }
            } finally {
                local.update { it.copy(scanning = false, progress = null) }
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
    }

    fun select(pkg: String) = local.update { it.copy(selected = pkg) }

    fun updateIoc() {
        if (local.value.iocUpdating) return
        viewModelScope.launch {
            local.update { it.copy(iocUpdating = true) }
            val r = g.security.updateIoc()
            val info = g.security.ioc().second
            local.update {
                it.copy(
                    iocUpdating = false, ioc = info,
                    message = when (r) {
                        IocUpdateResult.UPDATED -> str(R.string.sec_ioc_updated, info.families)
                        IocUpdateResult.NETWORK_ERROR -> str(R.string.sec_ioc_network_error)
                        IocUpdateResult.INVALID -> str(R.string.sec_ioc_invalid)
                    },
                )
            }
            if (r == IocUpdateResult.UPDATED && local.value.summary != null) scan()
        }
    }

    fun openApp(pkg: String) = g.intents.openAppDetails(pkg)
    fun open(target: com.bright.onepluscare.data.SystemIntents.Target) = g.intents.open(target)

    // --- firewall ---

    fun loadNetApps() {
        if (local.value.netApps != null) return
        viewModelScope.launch { local.update { it.copy(netApps = g.security.internetApps()) } }
    }

    fun setQuery(q: String) = local.update { it.copy(query = q.take(64)) }
    fun setShowSystem(on: Boolean) = local.update { it.copy(showSystemApps = on) }

    fun setBlocked(pkg: String, blocked: Boolean) {
        g.firewall.set(pkg, blocked)
        if (FirewallStore.running.value) {
            if (FirewallRules.canStart(g.firewall.current())) runService { FirewallVpnService.reload(g.appContext) } else runService { FirewallVpnService.stop(g.appContext) }
        }
    }

    fun setFirewall(on: Boolean) {
        if (!on) {
            runService { FirewallVpnService.stop(g.appContext) }
            return
        }
        if (!FirewallRules.canStart(g.firewall.current())) {
            local.update { it.copy(message = str(R.string.fw_need_apps)) }
            return
        }
        val consent = runCatching { FirewallVpnService.consentIntent(g.appContext) }.getOrNull()
        if (consent != null) local.update { it.copy(pendingConsent = consent, consentLaunched = false) } else startFirewall()
    }

    fun consentLaunched() = local.update { it.copy(consentLaunched = true) }

    fun onConsentResult(granted: Boolean) {
        local.update { it.copy(pendingConsent = null, consentLaunched = false) }
        if (granted) startFirewall() else local.update { it.copy(message = str(R.string.fw_consent_denied)) }
    }

    private fun startFirewall() {
        runService { FirewallVpnService.start(g.appContext) }
    }

    /** Block from a scan result: adds the app and, if the firewall is off, explains how to turn it on. */
    fun blockFromScan(pkg: String) {
        setBlocked(pkg, true)
        local.update { it.copy(message = str(if (FirewallStore.running.value) R.string.fw_blocked_now else R.string.fw_blocked_turn_on)) }
    }

    fun messageShown() = local.update { it.copy(message = null) }

    private fun runService(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            local.update { it.copy(message = str(R.string.fw_start_failed)) }
        }
    }

    private fun str(id: Int, vararg args: Any) = g.appContext.getString(id, *args)
}

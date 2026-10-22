package com.bright.onepluscare.ui.vm

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bright.onepluscare.AppGraph
import com.bright.onepluscare.R
import com.bright.onepluscare.core.security.AppAssessment
import com.bright.onepluscare.core.security.FirewallPolicy
import com.bright.onepluscare.core.security.NetKind
import com.bright.onepluscare.firewall.FirewallStatus
import com.bright.onepluscare.firewall.AdBlockStatus
import com.bright.onepluscare.firewall.AdBlockStore
import com.bright.onepluscare.firewall.DnsFilterVpnService
import com.bright.onepluscare.firewall.ListInfo
import com.bright.onepluscare.firewall.ListUpdateResult
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
    /** Which VPN the pending consent is for. */
    val consentFor: VpnKind = VpnKind.FIREWALL,
    val listUpdating: Boolean = false,
    val privateDnsStrict: Boolean = false,
    val consentLaunched: Boolean = false,
    val message: String? = null,
)

enum class VpnKind { FIREWALL, ADBLOCK }

data class SecurityUiState(
    val local: SecurityLocal = SecurityLocal(),
    val policy: FirewallPolicy = FirewallPolicy.EMPTY,
    val status: FirewallStatus = FirewallStatus(),
    val ad: AdBlockStatus = AdBlockStatus(),
    val listInfo: ListInfo? = null,
    val exceptions: Set<String> = emptySet(),
) {
    val firewallOn: Boolean get() = status.running
    /** Apps with any block (Wi-Fi, mobile or both). */
    val blocked: Set<String> get() = policy.allApps
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

    val state: StateFlow<SecurityUiState> = combine(
        combine(local, g.firewall.policy, FirewallStore.status, ::Triple),
        combine(AdBlockStore.status, g.adBlock.info, g.adBlock.exceptions, ::Triple),
    ) { (l, p, st), (ad, info, ex) ->
        SecurityUiState(l, p, st, ad, info, ex)
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

    fun setBlocked(pkg: String, kind: NetKind, blocked: Boolean) {
        g.firewall.set(pkg, kind, blocked)
        afterPolicyChange()
    }

    private fun afterPolicyChange() {
        if (FirewallStore.status.value.running) {
            if (!g.firewall.current().isEmpty) runService { FirewallVpnService.reload(g.appContext) } else runService { FirewallVpnService.stop(g.appContext) }
        }
    }

    fun setFirewall(on: Boolean) {
        if (!on) {
            runService { FirewallVpnService.stop(g.appContext) }
            return
        }
        if (g.firewall.current().isEmpty) {
            local.update { it.copy(message = str(R.string.fw_need_apps)) }
            return
        }
        val consent = runCatching { FirewallVpnService.consentIntent(g.appContext) }.getOrNull()
        if (consent != null) local.update { it.copy(pendingConsent = consent, consentLaunched = false, consentFor = VpnKind.FIREWALL) } else startFirewall()
    }

    fun consentLaunched() = local.update { it.copy(consentLaunched = true) }

    fun onConsentResult(granted: Boolean) {
        local.update { it.copy(pendingConsent = null, consentLaunched = false) }
        val kind = local.value.consentFor
        if (granted) {
            if (kind == VpnKind.FIREWALL) startFirewall() else startAdBlock()
        } else {
            local.update { it.copy(message = str(R.string.fw_consent_denied)) }
        }
    }

    private fun startFirewall() {
        // One VPN at a time: the ad blocker stops while the firewall runs.
        if (AdBlockStore.status.value.running) runService { DnsFilterVpnService.stop(g.appContext) }
        runService { FirewallVpnService.start(g.appContext) }
    }

    // --- ad blocking ---

    fun loadAdBlock() {
        viewModelScope.launch {
            g.adBlock.loadInfo()
            val cm = g.appContext.getSystemService(android.net.ConnectivityManager::class.java)
            val strict = runCatching { cm?.activeNetwork?.let { cm.getLinkProperties(it) }?.privateDnsServerName != null }.getOrDefault(false)
            local.update { it.copy(privateDnsStrict = strict) }
        }
    }

    fun setAdBlock(on: Boolean) {
        if (!on) {
            runService { DnsFilterVpnService.stop(g.appContext) }
            return
        }
        if ((g.adBlock.info.value?.entries ?: 0) == 0) {
            local.update { it.copy(message = str(R.string.ab_need_list)) }
            return
        }
        val consent = runCatching { FirewallVpnService.consentIntent(g.appContext) }.getOrNull()
        if (consent != null) local.update { it.copy(pendingConsent = consent, consentLaunched = false, consentFor = VpnKind.ADBLOCK) } else startAdBlock()
    }

    private fun startAdBlock() {
        if (FirewallStore.status.value.running) runService { FirewallVpnService.stop(g.appContext) }
        runService { DnsFilterVpnService.start(g.appContext) }
    }

    fun updateList() {
        if (local.value.listUpdating) return
        viewModelScope.launch {
            local.update { it.copy(listUpdating = true) }
            val r = g.adBlock.update()
            local.update {
                it.copy(
                    listUpdating = false,
                    message = when (r) {
                        ListUpdateResult.UPDATED -> str(R.string.ab_list_updated, g.adBlock.info.value?.entries ?: 0)
                        ListUpdateResult.NETWORK_ERROR -> str(R.string.sec_ioc_network_error)
                        ListUpdateResult.INVALID -> str(R.string.ab_list_invalid)
                    },
                )
            }
            if (r == ListUpdateResult.UPDATED && AdBlockStore.status.value.running) runService { DnsFilterVpnService.reload(g.appContext) }
        }
    }

    fun allowHost(host: String) {
        g.adBlock.addException(host)
        if (AdBlockStore.status.value.running) runService { DnsFilterVpnService.reload(g.appContext) }
    }

    fun removeException(host: String) {
        g.adBlock.removeException(host)
        if (AdBlockStore.status.value.running) runService { DnsFilterVpnService.reload(g.appContext) }
    }

    /** Block from a scan result: adds the app and, if the firewall is off, explains how to turn it on. */
    fun blockFromScan(pkg: String) {
        g.firewall.setEverywhere(pkg, true)
        afterPolicyChange()
        local.update { it.copy(message = str(if (FirewallStore.status.value.running) R.string.fw_blocked_now else R.string.fw_blocked_turn_on)) }
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

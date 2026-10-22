package com.rootguard.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rootguard.app.R
import com.rootguard.app.data.EDITABLE_NETWORKS
import com.rootguard.core.model.CapabilityId
import com.rootguard.core.model.EnforcementHealth
import com.rootguard.core.model.Mode
import com.rootguard.core.model.NetworkScope
import com.rootguard.core.model.Support
import com.rootguard.core.model.Attribution
import java.text.DateFormat
import java.util.Date

@Composable
fun RootGuardRoot(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    BackHandler(enabled = ui.stack.size > 1) { vm.back() }
    when (ui.screen) {
        Screen.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        Screen.WELCOME -> WelcomeScreen(vm)
        Screen.CONSENT -> ConsentScreen(vm)
        Screen.PROBE -> ProbeScreen(vm)
        Screen.GOAL -> GoalScreen(vm)
        Screen.APPS -> AppsScreen(vm)
        Screen.PREVIEW -> PreviewScreen(vm)
        Screen.RESULT -> ResultScreen(vm)
        Screen.DASHBOARD -> DashboardScreen(vm)
        Screen.CAPABILITIES -> CapabilitiesScreen(vm)
        Screen.AUDIT -> AuditScreen(vm)
    }
    if (ui.confirmRecover) {
        AlertDialog(
            onDismissRequest = { vm.askRecover(false) },
            title = { Text(stringResource(R.string.recover_title)) },
            text = { Text(stringResource(R.string.recover_body)) },
            confirmButton = { TextButton(onClick = vm::recover) { Text(stringResource(R.string.recover_confirm), color = Bad) } },
            dismissButton = { TextButton(onClick = { vm.askRecover(false) }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

// --- building blocks ----------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(title: String, vm: MainViewModel, step: Int? = null, bottom: (@Composable () -> Unit)? = null, content: LazyListScope.() -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (step != null && ui.wizard) Text(stringResource(R.string.wizard_step, step, 6), style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    if (ui.stack.size > 1) IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
        bottomBar = { if (bottom != null) Box(Modifier.fillMaxWidth().padding(16.dp)) { bottom() } },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun Section(title: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Body(text: String, muted: Boolean = false) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)

@Composable
private fun Pill(text: String, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

private fun Support.color() = when (this) {
    Support.SUPPORTED -> Ok
    Support.UNVERIFIED -> Warn
    Support.UNSUPPORTED, Support.DENIED -> Bad
}

private fun EnforcementHealth.color() = when (this) {
    EnforcementHealth.ACTIVE -> Ok
    EnforcementHealth.DEGRADED -> Bad
    EnforcementHealth.OFF -> Warn
    EnforcementHealth.UNKNOWN -> Neutral
}

@Composable
private fun Primary(text: String, onClick: () -> Unit, enabled: Boolean = true) =
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) { Text(text) }

@Composable
private fun Secondary(text: String, onClick: () -> Unit, enabled: Boolean = true, color: Color? = null) =
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
        Text(text, color = color ?: MaterialTheme.colorScheme.primary)
    }

// --- wizard -------------------------------------------------------------------------------------------

@Composable
private fun WelcomeScreen(vm: MainViewModel) = Page(stringResource(R.string.app_name), vm, bottom = { Primary(stringResource(R.string.action_continue), { vm.go(Screen.CONSENT) }) }) {
    item { Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineSmall) }
    item { Body(stringResource(R.string.welcome_body)) }
    item { Section(stringResource(R.string.welcome_does_title)) { Body(stringResource(R.string.welcome_does)) } }
    item { Section(stringResource(R.string.welcome_not_title)) { Body(stringResource(R.string.welcome_not)) } }
}

@Composable
private fun ConsentScreen(vm: MainViewModel) {
    var agreed by remember { mutableStateOf(false) }
    Page(stringResource(R.string.consent_title), vm, step = 1, bottom = { Primary(stringResource(R.string.consent_accept), vm::acceptConsent, enabled = agreed) }) {
        item { Section(stringResource(R.string.consent_helper_title)) { Body(stringResource(R.string.consent_helper_body)) } }
        item { Section(stringResource(R.string.consent_never_title)) { Body(stringResource(R.string.consent_never_body)) } }
        item { Section(stringResource(R.string.consent_data_title)) { Body(stringResource(R.string.consent_data_body)) } }
        item {
            Row(Modifier.fillMaxWidth().selectable(agreed, role = Role.Checkbox) { agreed = !agreed }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(agreed, onCheckedChange = { agreed = it })
                Body(stringResource(R.string.consent_checkbox))
            }
        }
    }
}

@Composable
private fun ProbeScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val s by vm.state.collectAsStateWithLifecycle()
    val probe = s.probe
    val canContinue = probe != null && probe.has(CapabilityId.ROOT) && probe.has(CapabilityId.OWNER_MATCH_V4)
    Page(stringResource(R.string.probe_title), vm, step = 2, bottom = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canContinue) Primary(stringResource(R.string.action_continue), { vm.go(Screen.GOAL) })
            Secondary(stringResource(if (probe == null) R.string.probe_run else R.string.probe_rerun), vm::probe, enabled = !ui.probing)
        }
    }) {
        item { Body(stringResource(R.string.probe_body)) }
        if (ui.probing) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Body(stringResource(R.string.probe_running)) } }
        if (!ui.probing && probe == null && s.lastError != null) {
            item { Section(stringResource(R.string.probe_no_root_title)) { Body(stringResource(Labels.error(s.lastError))); Body(stringResource(R.string.probe_no_root_body), muted = true) } }
        }
        if (probe != null) {
            if (!canContinue) item { Section(stringResource(R.string.probe_unsupported_title)) { Body(stringResource(R.string.probe_unsupported_body)) } }
            capabilityItems(probe)
        }
    }
}

private fun LazyListScope.capabilityItems(probe: com.rootguard.core.model.ProbeResult) {
    items(probe.capabilities, key = { it.id.name }) { c ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Labels.capability(c.id)), style = MaterialTheme.typography.titleSmall)
                Text(c.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
            }
            Pill(stringResource(Labels.support(c.support)), c.support.color())
        }
    }
}

@Composable
private fun GoalScreen(vm: MainViewModel) {
    val d by vm.draft.collectAsStateWithLifecycle()
    Page(stringResource(R.string.goal_title), vm, step = 3, bottom = { Primary(stringResource(R.string.action_continue), vm::openApps) }) {
        item { Body(stringResource(R.string.goal_body)) }
        val modes = listOf(
            Triple(Mode.BALANCED, R.string.mode_balanced, R.string.mode_balanced_body),
            Triple(Mode.STRICT, R.string.mode_strict, R.string.mode_strict_body),
            Triple(Mode.DATA_SAVER, R.string.mode_data_saver, R.string.mode_data_saver_body),
        )
        items(modes, key = { it.first.name }) { (m, title, body) ->
            Section {
                Row(Modifier.fillMaxWidth().selectable(d.mode == m, role = Role.RadioButton) { vm.setMode(m) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = d.mode == m, onClick = { vm.setMode(m) })
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                        Body(stringResource(body), muted = true)
                    }
                }
            }
        }
        item { Section(stringResource(R.string.mode_later_title)) { Body(stringResource(R.string.mode_later_body), muted = true) } }
    }
}

@Composable
private fun AppsScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val d by vm.draft.collectAsStateWithLifecycle()
    val all by vm.principals.collectAsStateWithLifecycle()
    val q = ui.query.trim().lowercase()
    val list = all.filter { p ->
        (ui.showSystem || !p.isSystem) && p.usesInternet &&
            (q.isEmpty() || p.label.lowercase().contains(q) || p.packages.any { it.packageName.contains(q) })
    }
    Page(stringResource(R.string.apps_title), vm, step = 4, bottom = { Primary(stringResource(R.string.apps_review), vm::openPreview, enabled = !ui.loadingApps) }) {
        item { Body(stringResource(if (d.mode == Mode.STRICT) R.string.apps_body_strict else R.string.apps_body)) }
        item {
            OutlinedTextField(ui.query, vm::setQuery, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.apps_search)) })
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Body(stringResource(R.string.apps_show_system))
                Spacer(Modifier.weight(1f))
                Switch(ui.showSystem, vm::setShowSystem)
            }
        }
        if (ui.loadingApps) item { CircularProgressIndicator() }
        items(list, key = { it.principalId }) { p ->
            Section {
                Text(p.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "UID ${p.uid} · " + p.packages.joinToString { it.packageName },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (p.attribution == Attribution.SHARED) Pill(stringResource(R.string.apps_shared, p.packages.size), Warn)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EDITABLE_NETWORKS.forEach { n ->
                        val allowed = d.allowed(p.principalId, n)
                        FilterChip(
                            selected = allowed,
                            onClick = { vm.setAllowed(p.principalId, n, !allowed) },
                            label = { Text(stringResource(netLabel(n)) + " · " + stringResource(if (allowed) R.string.allowed else R.string.blocked)) },
                        )
                    }
                }
            }
        }
    }
}

private fun netLabel(n: NetworkScope) = when (n) {
    NetworkScope.WIFI -> R.string.net_wifi
    NetworkScope.MOBILE -> R.string.net_mobile
    NetworkScope.VPN -> R.string.net_vpn
    else -> R.string.net_any
}

@Composable
private fun PreviewScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val p = ui.preview
    val plan = p?.plan
    Page(stringResource(R.string.preview_title), vm, step = 5, bottom = { Primary(stringResource(R.string.preview_apply), vm::apply, enabled = plan != null) }) {
        if (p == null) {
            item { Body(stringResource(R.string.preview_no_probe)) }
            return@Page
        }
        item {
            Section(stringResource(R.string.preview_summary)) {
                if (plan != null) {
                    plan.chains.forEach { c -> Body(stringResource(R.string.preview_chain, if (c.family == 4) "IPv4" else "IPv6", c.rules.size)) }
                    Body(stringResource(R.string.preview_hash, plan.canonicalHash.take(16)), muted = true)
                } else {
                    Body(stringResource(R.string.preview_blocked))
                }
            }
        }
        if (p.unsupported.isNotEmpty()) {
            item { Text(stringResource(R.string.preview_unsupported), style = MaterialTheme.typography.titleMedium, color = Bad) }
            items(p.unsupported) { n -> Section { Body(stringResource(Labels.note(n.note))); n.detail?.let { Body(it, muted = true) } } }
        }
        item { Text(stringResource(R.string.preview_warnings), style = MaterialTheme.typography.titleMedium) }
        items(p.warnings) { n -> Section { Body(stringResource(Labels.note(n.note))); n.detail?.let { Body(it, muted = true) } } }
        item { Section(stringResource(R.string.preview_failure_title)) { Body(stringResource(R.string.preview_failure_body)) } }
    }
}

@Composable
private fun ResultScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val s by vm.state.collectAsStateWithLifecycle()
    Page(stringResource(R.string.result_title), vm, step = 6, bottom = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.applyOk == true) Primary(stringResource(R.string.result_done), vm::finish)
            if (ui.applyOk == false) {
                Primary(stringResource(R.string.result_back_to_rules), vm::back)
                Secondary(stringResource(R.string.dash_recover), { vm.askRecover(true) }, color = Bad)
            }
        }
    }) {
        when (ui.applyOk) {
            null -> item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Body(stringResource(R.string.result_applying)) } }
            true -> item { Section(stringResource(R.string.result_ok_title)) { Body(stringResource(R.string.result_ok_body)) } }
            false -> item {
                Section(stringResource(R.string.result_fail_title)) {
                    Body(stringResource(Labels.error(s.lastError)))
                    Body(stringResource(if (s.lastReceipt?.compensated == true) R.string.result_compensated else R.string.result_check_state), muted = true)
                }
            }
        }
        if (ui.applyOk != null) item { HealthCard(vm) }
    }
}

// --- dashboard ----------------------------------------------------------------------------------------

@Composable
private fun HealthCard(vm: MainViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    Section(stringResource(R.string.dash_enforcement)) {
        Pill(stringResource(Labels.health(s.health)), s.health.color())
        Body(stringResource(Labels.reason(s.reason)), muted = true)
        s.appliedRevision?.let { Body(stringResource(R.string.dash_revision, it.removePrefix("rev-").take(8)), muted = true) }
        s.lastHealth?.families?.forEach { f ->
            Body(stringResource(R.string.dash_family, if (f.family == 4) "IPv4" else "IPv6", f.ruleCount, f.hookCount), muted = true)
        }
    }
}

@Composable
private fun DashboardScreen(vm: MainViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    Page(stringResource(R.string.app_name), vm) {
        item { HealthCard(vm) }
        item {
            Section(stringResource(R.string.dash_coverage)) {
                val p = s.probe
                if (p == null) Body(stringResource(R.string.dash_not_probed), muted = true)
                else listOf(CapabilityId.OWNER_MATCH_V4, CapabilityId.OWNER_MATCH_V6, CapabilityId.WIFI_INTERFACES, CapabilityId.MOBILE_INTERFACES, CapabilityId.VPN_PATH, CapabilityId.TETHER_FORWARD, CapabilityId.DOMAIN_RULES, CapabilityId.FLOW_METADATA).forEach { id ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Labels.capability(id)), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Pill(stringResource(Labels.support(p.support(id))), p.support(id).color())
                    }
                }
                Body(stringResource(R.string.dash_monitoring_note), muted = true)
            }
        }
        item { Primary(stringResource(R.string.dash_edit_rules), vm::editRules, enabled = !s.busy) }
        if (s.health == EnforcementHealth.OFF && s.appliedRevision != null) {
            item { Secondary(stringResource(R.string.dash_reapply), vm::reapply, enabled = !s.busy) }
        }
        item { Secondary(stringResource(R.string.dash_recheck), vm::recheck, enabled = !s.busy) }
        item { Secondary(stringResource(R.string.dash_capabilities), { vm.go(Screen.CAPABILITIES) }) }
        item { Secondary(stringResource(R.string.dash_audit), { vm.go(Screen.AUDIT) }) }
        item { Secondary(stringResource(R.string.dash_recover), { vm.askRecover(true) }, color = Bad) }
        item { Body(stringResource(R.string.dash_reboot_note), muted = true) }
        item { Body(stringResource(R.string.dash_limits), muted = true) }
    }
}

@Composable
private fun CapabilitiesScreen(vm: MainViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    Page(stringResource(R.string.dash_capabilities), vm) {
        val p = s.probe
        if (p == null) item { Body(stringResource(R.string.dash_not_probed)) } else {
            item { Body(stringResource(R.string.cap_boot, p.bootId.take(8), p.probeVersion), muted = true) }
            capabilityItems(p)
        }
    }
}

@Composable
private fun AuditScreen(vm: MainViewModel) {
    val events by vm.controller.audit.collectAsState(initial = emptyList())
    val ctx = LocalContext.current
    val fmt = remember(ctx) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
    Page(stringResource(R.string.dash_audit), vm) {
        if (events.isEmpty()) item { Body(stringResource(R.string.audit_empty), muted = true) }
        items(events, key = { it.auditId }) { e ->
            Section {
                Text(e.eventType, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                Body(fmt.format(Date(e.timeMs)) + " · " + e.actor, muted = true)
                Text(e.detailsJson.take(300), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

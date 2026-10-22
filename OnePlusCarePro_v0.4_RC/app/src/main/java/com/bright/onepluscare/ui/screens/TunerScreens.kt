package com.bright.onepluscare.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.AppBlocking
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bright.onepluscare.R
import com.bright.onepluscare.core.tweaks.ChangeStatus
import com.bright.onepluscare.core.tweaks.DebloatKind
import com.bright.onepluscare.core.tweaks.DebloatRisk
import com.bright.onepluscare.core.tweaks.PackageState
import com.bright.onepluscare.core.tweaks.SettingsAllowList
import com.bright.onepluscare.core.tweaks.SystemPackage
import com.bright.onepluscare.core.tweaks.TweakChange
import com.bright.onepluscare.core.tweaks.TweakKind
import com.bright.onepluscare.core.tweaks.TweakPlanner
import com.bright.onepluscare.data.SystemIntents
import com.bright.onepluscare.shizuku.ShizukuMode
import com.bright.onepluscare.ui.Fmt
import com.bright.onepluscare.ui.components.CareCard
import com.bright.onepluscare.ui.components.ConfirmDialog
import com.bright.onepluscare.ui.components.EmptyState
import com.bright.onepluscare.ui.components.NavRow
import com.bright.onepluscare.ui.components.NoticeCard
import com.bright.onepluscare.ui.components.PrimaryAction
import com.bright.onepluscare.ui.components.ProgressBlock
import com.bright.onepluscare.ui.components.ScreenScaffold
import com.bright.onepluscare.ui.components.SecondaryAction
import com.bright.onepluscare.ui.components.SectionHeader
import com.bright.onepluscare.ui.components.StatusPill
import com.bright.onepluscare.ui.components.Tone
import com.bright.onepluscare.ui.nav.Dest
import com.bright.onepluscare.ui.nav.Navigator
import com.bright.onepluscare.ui.vm.DebloatFilter
import com.bright.onepluscare.ui.vm.LocalAppGraph
import com.bright.onepluscare.ui.vm.TunerConfirm
import com.bright.onepluscare.ui.vm.TunerUiState
import com.bright.onepluscare.ui.vm.TunerViewModel
import com.bright.onepluscare.ui.vm.careViewModel

// --- shared ---------------------------------------------------------------------------------------------

/** Shown instead of tuner content until Shizuku is running and this app is allowed. */
private fun LazyListScope.shizukuGate(s: TunerUiState, nav: Navigator, vm: TunerViewModel): Boolean {
    if (s.ready) return true
    item {
        val body = when {
            !s.shizuku.installed -> R.string.tuner_gate_not_installed
            !s.shizuku.running -> R.string.tuner_gate_not_running
            else -> R.string.tuner_gate_no_permission
        }
        NoticeCard(
            Tone.INFO, stringResource(R.string.tuner_gate_title), stringResource(body),
            action = {
                Row {
                    TextButton(onClick = { nav.go(Dest.SHIZUKU_SETUP) }) { Text(stringResource(R.string.shz_open_guide)) }
                    if (s.shizuku.running && !s.shizuku.permissionGranted && !s.shizuku.permissionDeniedPermanently) {
                        TextButton(onClick = { vm.requestShizukuPermission() }) { Text(stringResource(R.string.adv_request_permission)) }
                    }
                }
            },
        )
    }
    return false
}

@Composable
private fun TunerConfirmHost(s: TunerUiState, vm: TunerViewModel) {
    when (val c = s.local.confirm) {
        null -> Unit
        is TunerConfirm.Density -> ConfirmDialog(
            title = stringResource(R.string.tuner_density_confirm_title),
            body = stringResource(R.string.tuner_density_confirm_body, c.dpi),
            confirmLabel = stringResource(R.string.action_apply),
            irreversible = false,
            onConfirm = vm::confirm,
            onDismiss = vm::dismissConfirm,
        )
        is TunerConfirm.Debloat -> ConfirmDialog(
            title = stringResource(if (c.uninstall) R.string.debloat_confirm_uninstall_title else R.string.debloat_confirm_disable_title, c.label),
            body = listOfNotNull(
                stringResource(if (c.uninstall) R.string.debloat_confirm_uninstall_body else R.string.debloat_confirm_disable_body),
                when (c.risk) {
                    DebloatRisk.CAUTION -> stringResource(R.string.debloat_risk_caution_body)
                    DebloatRisk.UNKNOWN -> stringResource(R.string.debloat_risk_unknown_body)
                    else -> null
                },
            ).joinToString("\n\n"),
            confirmLabel = stringResource(if (c.uninstall) R.string.debloat_action_uninstall else R.string.debloat_action_disable),
            irreversible = false,
            onConfirm = vm::confirm,
            onDismiss = vm::dismissConfirm,
        )
        TunerConfirm.RevertAll -> ConfirmDialog(
            title = stringResource(R.string.tuner_revert_all_title),
            body = stringResource(R.string.tuner_revert_all_body, s.active.size),
            confirmLabel = stringResource(R.string.tuner_revert_all),
            irreversible = false,
            onConfirm = vm::confirm,
            onDismiss = vm::dismissConfirm,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun SwitchLine(title: String, body: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { role = Role.Switch }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

// --- hub ------------------------------------------------------------------------------------------------

@Composable
fun TunerScreen(nav: Navigator, padding: PaddingValues) {
    val vm: TunerViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    ScreenScaffold(title = stringResource(R.string.screen_tuner), outerPadding = padding, onBack = nav.back, message = s.local.message, onMessageShown = vm::messageShown) {
        item {
            CareCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.tuner_intro_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.tuner_intro_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.ready) {
                        StatusPill(
                            if (s.shizuku.mode == ShizukuMode.ROOT) Tone.ATTENTION else Tone.HEALTHY,
                            stringResource(if (s.shizuku.mode == ShizukuMode.ROOT) R.string.tuner_ready_root else R.string.tuner_ready),
                        )
                    }
                }
            }
        }
        shizukuGate(s, nav, vm)
        item { SectionHeader(stringResource(R.string.tuner_sections)) }
        item { NavRow(Icons.Outlined.BatteryAlert, stringResource(R.string.screen_tuner_fixes), stringResource(R.string.tuner_fixes_subtitle), { nav.go(Dest.TUNER_FIXES) }) }
        item { NavRow(Icons.Outlined.AppBlocking, stringResource(R.string.screen_debloat), stringResource(R.string.debloat_subtitle), { nav.go(Dest.TUNER_DEBLOAT) }) }
        item { NavRow(Icons.Outlined.Animation, stringResource(R.string.screen_tuner_display), stringResource(R.string.tuner_display_subtitle), { nav.go(Dest.TUNER_DISPLAY) }) }
        item {
            NavRow(
                Icons.Outlined.History, stringResource(R.string.screen_tuner_changes),
                stringResource(R.string.tuner_changes_subtitle, s.active.size), { nav.go(Dest.TUNER_CHANGES) },
            )
        }
        item { Text(stringResource(R.string.tuner_safety_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

// --- Shizuku setup guide --------------------------------------------------------------------------------

@Composable
fun ShizukuSetupScreen(nav: Navigator, padding: PaddingValues) {
    val vm: TunerViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val graph = LocalAppGraph.current
    LaunchedEffect(Unit) { vm.refresh() }
    val steps = listOf(
        R.string.shz_step1_title to R.string.shz_step1_body,
        R.string.shz_step2_title to R.string.shz_step2_body,
        R.string.shz_step3_title to R.string.shz_step3_body,
        R.string.shz_step4_title to R.string.shz_step4_body,
        R.string.shz_step5_title to R.string.shz_step5_body,
        R.string.shz_step6_title to R.string.shz_step6_body,
    )
    ScreenScaffold(title = stringResource(R.string.screen_shizuku_setup), outerPadding = padding, onBack = nav.back) {
        item { Text(stringResource(R.string.shz_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            CareCard {
                Column {
                    SetupStatus(stringResource(R.string.adv_installed), s.shizuku.installed)
                    SetupStatus(stringResource(R.string.adv_running), s.shizuku.running)
                    SetupStatus(stringResource(R.string.adv_permission), s.shizuku.permissionGranted)
                }
            }
        }
        steps.forEachIndexed { i, (t, b) ->
            item(key = "step$i") {
                CareCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.shz_step_label, i + 1, stringResource(t)), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(b), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        when (i) {
                            0 -> if (!s.shizuku.installed) TextButton(onClick = { graph.intents.open(SystemIntents.Target.SHIZUKU_STORE) }) { Text(stringResource(R.string.shz_get_shizuku)) }
                            1, 2 -> TextButton(onClick = { graph.intents.open(SystemIntents.Target.DEVELOPER) }) { Text(stringResource(R.string.shz_open_developer)) }
                            3 -> if (s.shizuku.installed) TextButton(onClick = { graph.intents.open(SystemIntents.Target.SHIZUKU_APP) }) { Text(stringResource(R.string.adv_open_shizuku)) }
                            4 -> if (s.shizuku.running && !s.shizuku.permissionGranted) TextButton(onClick = { vm.requestShizukuPermission() }) { Text(stringResource(R.string.adv_request_permission)) }
                            else -> Unit
                        }
                    }
                }
            }
        }
        item { NoticeCard(Tone.INFO, stringResource(R.string.shz_reboot_title), stringResource(R.string.shz_reboot_body)) }
        if (s.ready) item { PrimaryAction(stringResource(R.string.shz_done), onClick = { nav.go(Dest.TUNER) }) }
    }
}

@Composable
private fun SetupStatus(label: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        StatusPill(if (ok) Tone.HEALTHY else Tone.NEUTRAL, stringResource(if (ok) R.string.yes else R.string.no))
    }
}

// --- OxygenOS fixes -------------------------------------------------------------------------------------

private val DNS_PRESETS = listOf(
    R.string.dns_automatic to null,
    R.string.dns_adguard to "dns.adguard-dns.com",
    R.string.dns_cloudflare to "one.one.one.one",
    R.string.dns_google to "dns.google",
    R.string.dns_quad9 to "dns.quad9.net",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunerFixesScreen(nav: Navigator, padding: PaddingValues) {
    val vm: TunerViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val graph = LocalAppGraph.current
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(s.ready) { if (s.ready) { vm.refresh(); vm.loadApps() } }

    ScreenScaffold(title = stringResource(R.string.screen_tuner_fixes), outerPadding = padding, onBack = nav.back, message = s.local.message, onMessageShown = vm::messageShown) {
        if (!shizukuGate(s, nav, vm)) return@ScreenScaffold
        if (s.local.busy) item { ProgressBlock(stringResource(R.string.working), null) }

        item { SectionHeader(stringResource(R.string.fix_background_title)) }
        item { Text(stringResource(R.string.fix_background_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it.take(64) }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.apps_search)) }, modifier = Modifier.fillMaxWidth(),
            )
        }
        val apps = s.local.apps
        if (apps == null) {
            item { ProgressBlock(stringResource(R.string.privacy_loading_apps), null) }
        } else {
            val fixed = s.backgroundFixed
            val q = query.trim().lowercase()
            val list = apps.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.contains(q) }
                .sortedWith(compareByDescending<com.bright.onepluscare.core.apps.AppRecord> { it.packageName in fixed })
            items(list, key = { "bg-" + it.packageName }) { app ->
                CareCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                    Column {
                        SwitchLine(app.label, app.packageName, checked = app.packageName in fixed, enabled = !s.local.busy) { vm.toggleBackground(app.packageName) }
                        if (app.packageName in fixed) {
                            TextButton(onClick = { graph.intents.openAppDetails(app.packageName) }) { Text(stringResource(R.string.fix_background_oem_step)) }
                        }
                    }
                }
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= 34) {
            item { SectionHeader(stringResource(R.string.fix_phantom_title)) }
            item {
                CareCard {
                    SwitchLine(
                        stringResource(R.string.fix_phantom_switch), stringResource(R.string.fix_phantom_body),
                        checked = s.local.snapshot?.phantomMonitor == "false", enabled = !s.local.busy && s.local.snapshot != null,
                    ) { vm.setPhantom(it) }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.fix_dns_title)) }
        item { Text(stringResource(R.string.fix_dns_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            val snap = s.local.snapshot
            val currentHost = if (snap?.privateDnsMode == "hostname") snap.privateDnsHost else null
            ChipRow {
                DNS_PRESETS.forEach { (label, host) ->
                    FilterChip(
                        selected = snap != null && currentHost == host && (host != null || snap.privateDnsMode != "off"),
                        onClick = { vm.setPrivateDns(host) },
                        enabled = !s.local.busy && snap != null,
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        }
    }
    TunerConfirmHost(s, vm)
}

// --- Debloat --------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebloatScreen(nav: Navigator, padding: PaddingValues) {
    val vm: TunerViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.ready) { if (s.ready && s.local.packages == null) vm.loadPackages() }

    ScreenScaffold(title = stringResource(R.string.screen_debloat), outerPadding = padding, onBack = nav.back, message = s.local.message, onMessageShown = vm::messageShown) {
        if (!shizukuGate(s, nav, vm)) return@ScreenScaffold
        item { NoticeCard(Tone.INFO, stringResource(R.string.debloat_how_title), stringResource(R.string.debloat_how_body)) }
        item {
            OutlinedTextField(
                value = s.local.query, onValueChange = vm::setQuery, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.debloat_search)) }, modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            ChipRow {
                listOfNotNull(
                    DebloatFilter.RECOMMENDED, DebloatFilter.CAUTION, DebloatFilter.CHANGED,
                    DebloatFilter.ALL.takeIf { s.local.expert },
                ).forEach { f ->
                    FilterChip(selected = s.local.filter == f, onClick = { vm.setFilter(f) }, label = { Text(stringResource(filterLabel(f))) })
                }
            }
        }
        item {
            CareCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                SwitchLine(stringResource(R.string.debloat_expert), stringResource(R.string.debloat_expert_body), s.local.expert) { vm.setExpert(it) }
            }
        }
        if (s.local.packagesLoading || s.local.busy) item { ProgressBlock(stringResource(R.string.working), null) }
        val visible = s.visiblePackages
        if (s.local.packages != null && visible.isEmpty()) {
            item { CareCard { EmptyState(Icons.Outlined.AppBlocking, stringResource(R.string.debloat_empty_title), stringResource(R.string.debloat_empty_body)) } }
        }
        items(visible, key = { "p-" + it.info.pkg }) { p -> PackageRow(p, s.local.busy, vm) }
    }
    TunerConfirmHost(s, vm)
}

private fun filterLabel(f: DebloatFilter) = when (f) {
    DebloatFilter.RECOMMENDED -> R.string.debloat_filter_recommended
    DebloatFilter.CAUTION -> R.string.debloat_filter_caution
    DebloatFilter.CHANGED -> R.string.debloat_filter_changed
    DebloatFilter.ALL -> R.string.debloat_filter_all
}

private fun kindLabel(k: DebloatKind) = when (k) {
    DebloatKind.PARTNER_STUB -> R.string.dk_partner_stub
    DebloatKind.PARTNER_APP -> R.string.dk_partner_app
    DebloatKind.GOOGLE_OPTIONAL -> R.string.dk_google
    DebloatKind.ONEPLUS_OPTIONAL -> R.string.dk_oneplus
    DebloatKind.SYSTEM_COMPONENT -> R.string.dk_system
    DebloatKind.OTHER -> R.string.dk_other
}

@Composable
private fun PackageRow(p: SystemPackage, busy: Boolean, vm: TunerViewModel) {
    CareCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.info.label ?: p.info.pkg, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.info.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(kindLabel(p.info.kind)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(
                    when (p.info.risk) { DebloatRisk.RECOMMENDED -> Tone.HEALTHY; DebloatRisk.CAUTION -> Tone.ATTENTION; else -> Tone.CRITICAL },
                    stringResource(when (p.info.risk) { DebloatRisk.RECOMMENDED -> R.string.debloat_risk_recommended; DebloatRisk.CAUTION -> R.string.debloat_risk_caution; else -> R.string.debloat_risk_unknown }),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    if (p.state == PackageState.ENABLED) Tone.NEUTRAL else Tone.INFO,
                    stringResource(when (p.state) { PackageState.ENABLED -> R.string.pkg_enabled; PackageState.DISABLED -> R.string.pkg_disabled; PackageState.UNINSTALLED_FOR_USER -> R.string.pkg_removed }),
                )
                Spacer(Modifier.weight(1f))
                if (p.state == PackageState.ENABLED) {
                    TextButton(onClick = { vm.requestDebloat(p, uninstall = false) }, enabled = !busy) { Text(stringResource(R.string.debloat_action_disable)) }
                    TextButton(onClick = { vm.requestDebloat(p, uninstall = true) }, enabled = !busy) { Text(stringResource(R.string.debloat_action_uninstall)) }
                } else {
                    TextButton(onClick = { vm.restore(p) }, enabled = !busy) { Text(stringResource(R.string.action_restore)) }
                }
            }
        }
    }
}

// --- Display & animations -------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunerDisplayScreen(nav: Navigator, padding: PaddingValues) {
    val vm: TunerViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.ready) { if (s.ready) vm.refresh() }

    ScreenScaffold(title = stringResource(R.string.screen_tuner_display), outerPadding = padding, onBack = nav.back, message = s.local.message, onMessageShown = vm::messageShown) {
        if (!shizukuGate(s, nav, vm)) return@ScreenScaffold
        val snap = s.local.snapshot
        if (snap == null) {
            item { ProgressBlock(stringResource(R.string.working), null) }
            return@ScreenScaffold
        }
        if (s.local.busy) item { ProgressBlock(stringResource(R.string.working), null) }

        item { SectionHeader(stringResource(R.string.disp_animation_title)) }
        item { Text(stringResource(R.string.disp_animation_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            val current = snap.animationScale ?: "1.0"
            ChipRow {
                SettingsAllowList.ANIMATION_SCALES.forEach { v ->
                    FilterChip(
                        selected = current.toFloatOrNull() == v.toFloat(),
                        onClick = { vm.setAnimation(v) }, enabled = !s.local.busy,
                        label = { Text(if (v == "0.0") stringResource(R.string.disp_animation_off) else stringResource(R.string.disp_times, Fmt.number(v.toFloat(), if (v == "0.75" || v == "1.25") 2 else 1))) },
                    )
                }
            }
        }

        val rates = s.refreshRates.filter { it >= 60f }
        if (rates.size > 1) {
            item { SectionHeader(stringResource(R.string.disp_refresh_title)) }
            item { Text(stringResource(R.string.disp_refresh_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                val active = s.active.firstOrNull { it.kind == TweakKind.REFRESH_RATE }
                ChipRow {
                    FilterChip(selected = active == null, onClick = { active?.let(vm::revert) }, enabled = !s.local.busy, label = { Text(stringResource(R.string.disp_refresh_default)) })
                    rates.forEach { hz ->
                        FilterChip(
                            selected = active?.target == TweakPlanner.formatRate(hz),
                            onClick = { vm.setRefreshRate(hz) }, enabled = !s.local.busy,
                            label = { Text(stringResource(R.string.disp_hz, Fmt.number(hz))) },
                        )
                    }
                }
            }
        }

        val physical = snap.density.physical
        if (physical != null) {
            item { SectionHeader(stringResource(R.string.disp_density_title)) }
            item { Text(stringResource(R.string.disp_density_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                val range = TweakPlanner.densityRange(physical)
                val options = (-3..3).map { physical + it * physical / 12 }.filter { it in range }.distinct()
                val current = snap.density.effective
                ChipRow {
                    options.forEach { dpi ->
                        FilterChip(
                            selected = current == dpi,
                            onClick = { vm.requestDensity(dpi) }, enabled = !s.local.busy && current != dpi,
                            label = {
                                Text(
                                    when {
                                        dpi == physical -> stringResource(R.string.disp_density_default, dpi)
                                        dpi < physical -> stringResource(R.string.disp_density_more, dpi)
                                        else -> stringResource(R.string.disp_density_larger, dpi)
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
        item { Text(stringResource(R.string.disp_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    TunerConfirmHost(s, vm)
}

// --- Changes & undo -------------------------------------------------------------------------------------

@Composable
fun changeTitle(c: TweakChange): String = when (c.kind) {
    TweakKind.BACKGROUND_FIX -> stringResource(R.string.chg_background, c.target)
    TweakKind.DEBLOAT_DISABLE -> stringResource(R.string.chg_disable, c.target)
    TweakKind.DEBLOAT_UNINSTALL -> stringResource(R.string.chg_uninstall, c.target)
    TweakKind.PACKAGE_RESTORE -> stringResource(R.string.chg_restore, c.target)
    TweakKind.ANIMATION_SCALE -> stringResource(R.string.chg_animation, c.target)
    TweakKind.DISPLAY_DENSITY -> stringResource(R.string.chg_density, c.target)
    TweakKind.REFRESH_RATE -> stringResource(R.string.chg_refresh, c.target)
    TweakKind.PRIVATE_DNS -> stringResource(R.string.chg_dns, c.target)
    TweakKind.PHANTOM_PROCESS -> stringResource(if (c.target == "false") R.string.chg_phantom_off else R.string.chg_phantom_on)
}

@Composable
fun TunerChangesScreen(nav: Navigator, padding: PaddingValues) {
    val vm: TunerViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    ScreenScaffold(title = stringResource(R.string.screen_tuner_changes), outerPadding = padding, onBack = nav.back, message = s.local.message, onMessageShown = vm::messageShown) {
        item { Text(stringResource(R.string.chg_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (!s.ready && s.active.isNotEmpty()) item { NoticeCard(Tone.ATTENTION, stringResource(R.string.tuner_gate_title), stringResource(R.string.chg_need_shizuku)) }
        if (s.local.busy) item { ProgressBlock(stringResource(R.string.working), null) }
        if (s.active.isEmpty()) {
            item { CareCard { EmptyState(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.chg_empty_title), stringResource(R.string.chg_empty_body)) } }
        } else {
            item { SecondaryAction(stringResource(R.string.tuner_revert_all), onClick = vm::requestRevertAll, enabled = s.ready && !s.local.busy, icon = Icons.AutoMirrored.Outlined.Undo, modifier = Modifier.fillMaxWidth()) }
            items(s.active, key = { it.id }) { c ->
                CareCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(changeTitle(c), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(Fmt.dateTime(context, c.createdAtMillis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (c.undo.isNotEmpty()) TextButton(onClick = { vm.revert(c) }, enabled = s.ready && !s.local.busy) { Text(stringResource(R.string.chg_undo)) }
                    }
                }
            }
        }
        val past = s.changes.filter { it.status == ChangeStatus.REVERTED || it.status == ChangeStatus.FAILED }.sortedByDescending { it.createdAtMillis }
        if (past.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.chg_past)) }
            items(past.take(30), key = { "past-" + it.id }) { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(changeTitle(c), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    StatusPill(if (c.status == ChangeStatus.FAILED) Tone.CRITICAL else Tone.NEUTRAL, stringResource(if (c.status == ChangeStatus.FAILED) R.string.chg_failed else R.string.chg_reverted))
                }
            }
            item { TextButton(onClick = vm::clearFinished) { Text(stringResource(R.string.chg_clear_past)) } }
        }
    }
    TunerConfirmHost(s, vm)
}

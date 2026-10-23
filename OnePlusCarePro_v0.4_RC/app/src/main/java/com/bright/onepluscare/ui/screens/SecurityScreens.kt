package com.bright.onepluscare.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bright.onepluscare.R
import com.bright.onepluscare.core.security.AppAssessment
import com.bright.onepluscare.core.security.FindingId
import com.bright.onepluscare.core.security.NetKind
import com.bright.onepluscare.core.security.ThreatLevel
import com.bright.onepluscare.data.IocSource
import com.bright.onepluscare.data.SystemIntents
import com.bright.onepluscare.ui.Fmt
import com.bright.onepluscare.ui.components.CareCard
import com.bright.onepluscare.ui.components.EmptyState
import com.bright.onepluscare.ui.components.InfoRow
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
import com.bright.onepluscare.ui.vm.SecurityViewModel
import com.bright.onepluscare.ui.vm.careViewModel

private fun ThreatLevel.tone(): Tone = when (this) {
    ThreatLevel.NONE -> Tone.HEALTHY
    ThreatLevel.LOW -> Tone.INFO
    ThreatLevel.MEDIUM -> Tone.ATTENTION
    ThreatLevel.HIGH, ThreatLevel.CRITICAL -> Tone.CRITICAL
}

private fun ThreatLevel.label(): Int = when (this) {
    ThreatLevel.NONE -> R.string.sec_level_none
    ThreatLevel.LOW -> R.string.sec_level_low
    ThreatLevel.MEDIUM -> R.string.sec_level_medium
    ThreatLevel.HIGH -> R.string.sec_level_high
    ThreatLevel.CRITICAL -> R.string.sec_level_critical
}

private fun FindingId.title(): Int = when (this) {
    FindingId.KNOWN_STALKERWARE -> R.string.sec_f_stalkerware
    FindingId.BANKING_TROJAN_PATTERN -> R.string.sec_f_banking
    FindingId.HIDDEN_WITH_SURVEILLANCE -> R.string.sec_f_hidden
    FindingId.ACCESSIBILITY_CONTROL -> R.string.sec_f_a11y
    FindingId.READS_NOTIFICATIONS -> R.string.sec_f_notifications
    FindingId.DEVICE_ADMIN -> R.string.sec_f_admin
    FindingId.SIDELOADED_SMS_ACCESS -> R.string.sec_f_sms
    FindingId.BACKGROUND_SURVEILLANCE -> R.string.sec_f_surveillance
    FindingId.LEGACY_PERMISSION_MODEL -> R.string.sec_f_legacy
    FindingId.SIDELOADED -> R.string.sec_f_sideloaded
    FindingId.CAN_INSTALL_APPS -> R.string.sec_f_installs
    FindingId.DEBUGGABLE_BUILD -> R.string.sec_f_debuggable
}

private fun FindingId.body(): Int = when (this) {
    FindingId.KNOWN_STALKERWARE -> R.string.sec_f_stalkerware_body
    FindingId.BANKING_TROJAN_PATTERN -> R.string.sec_f_banking_body
    FindingId.HIDDEN_WITH_SURVEILLANCE -> R.string.sec_f_hidden_body
    FindingId.ACCESSIBILITY_CONTROL -> R.string.sec_f_a11y_body
    FindingId.READS_NOTIFICATIONS -> R.string.sec_f_notifications_body
    FindingId.DEVICE_ADMIN -> R.string.sec_f_admin_body
    FindingId.SIDELOADED_SMS_ACCESS -> R.string.sec_f_sms_body
    FindingId.BACKGROUND_SURVEILLANCE -> R.string.sec_f_surveillance_body
    FindingId.LEGACY_PERMISSION_MODEL -> R.string.sec_f_legacy_body
    FindingId.SIDELOADED -> R.string.sec_f_sideloaded_body
    FindingId.CAN_INSTALL_APPS -> R.string.sec_f_installs_body
    FindingId.DEBUGGABLE_BUILD -> R.string.sec_f_debuggable_body
}

@Composable
private fun AppRow(a: AppAssessment, onClick: () -> Unit) {
    NavRow(
        if (a.level >= ThreatLevel.HIGH) Icons.Outlined.GppMaybe else Icons.Outlined.Security,
        a.facts.label,
        a.findings.firstOrNull()?.let { stringResource(it.id.title()) } ?: a.facts.packageName,
        onClick,
        trailing = { StatusPill(a.level.tone(), stringResource(a.level.label())) },
    )
}

@Composable
fun SecurityScreen(nav: Navigator, padding: PaddingValues) {
    val vm: SecurityViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.loadIocInfo() }
    val summary = s.local.summary

    ScreenScaffold(title = stringResource(R.string.screen_security), outerPadding = padding, onBack = nav.back, message = s.local.message, onMessageShown = vm::messageShown) {
        item { NoticeCard(Tone.INFO, stringResource(R.string.sec_intro_title), stringResource(R.string.sec_intro_body)) }
        item {
            if (s.local.scanning) {
                ProgressBlock(stringResource(R.string.sec_scanning), s.local.progress, onCancel = vm::cancelScan)
            } else {
                PrimaryAction(stringResource(if (summary == null) R.string.sec_scan else R.string.sec_rescan), vm::scan, icon = Icons.Outlined.Search)
            }
        }
        if (summary != null) {
            item {
                CareCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.sec_result), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            StatusPill(summary.worst.tone(), stringResource(summary.worst.label()))
                        }
                        Text(stringResource(R.string.sec_result_body, summary.scannedApps, summary.flagged.size, summary.iocFamilies), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (summary.flagged.isEmpty()) {
                item { EmptyState(Icons.Outlined.GppGood, stringResource(R.string.sec_clean_title), stringResource(R.string.sec_clean_body)) }
            } else {
                item { SectionHeader(stringResource(R.string.sec_flagged)) }
                items(summary.flagged, key = { "f-" + it.facts.packageName }) { a ->
                    AppRow(a) { vm.select(a.facts.packageName); nav.go(Dest.SECURITY_APP) }
                }
            }

            item { SectionHeader(stringResource(R.string.sec_capabilities)) }
            item { Text(stringResource(R.string.sec_capabilities_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                CapabilityCard(Icons.Outlined.Accessibility, stringResource(R.string.sec_cap_a11y), summary.accessibilityApps, { vm.open(SystemIntents.Target.ACCESSIBILITY) }) {
                    vm.select(it); nav.go(Dest.SECURITY_APP)
                }
            }
            item {
                CapabilityCard(Icons.Outlined.NotificationsActive, stringResource(R.string.sec_cap_notifications), summary.notificationListenerApps, { vm.open(SystemIntents.Target.NOTIFICATION_LISTENERS) }) {
                    vm.select(it); nav.go(Dest.SECURITY_APP)
                }
            }
            item {
                CapabilityCard(Icons.Outlined.AdminPanelSettings, stringResource(R.string.sec_cap_admin), summary.deviceAdminApps, { vm.open(SystemIntents.Target.SECURITY) }) {
                    vm.select(it); nav.go(Dest.SECURITY_APP)
                }
            }
        }

        item { SectionHeader(stringResource(R.string.sec_protection)) }
        item { NavRow(Icons.Outlined.GppMaybe, stringResource(R.string.screen_malware), stringResource(R.string.mw_subtitle), { nav.go(Dest.MALWARE_SCAN) }) }
        item {
            CareCard {
                Column {
                    FwSwitch(stringResource(R.string.watch_title), stringResource(R.string.watch_body), s.watchOn) { vm.setWatch(it) }
                    if (s.watchOn && !vm.canNotify()) {
                        Text(stringResource(R.string.watch_needs_notifications), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { nav.go(Dest.PERMISSIONS) }) { Text(stringResource(R.string.screen_permissions)) }
                    }
                }
            }
        }
        item {
            NavRow(Icons.Outlined.Block, stringResource(R.string.screen_firewall),
                fwStatusText(s), { nav.go(Dest.FIREWALL) },
                trailing = if (s.firewallOn) ({ StatusPill(Tone.HEALTHY, stringResource(R.string.on)) }) else null)
        }
        item {
            val ioc = s.local.ioc
            CareCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.sec_ioc_title), style = MaterialTheme.typography.titleSmall)
                    if (ioc != null) {
                        InfoRow(stringResource(R.string.sec_ioc_families), Fmt.number(ioc.families))
                        InfoRow(stringResource(R.string.sec_ioc_indicators), Fmt.number(ioc.packages + ioc.certificates))
                        InfoRow(
                            stringResource(R.string.sec_ioc_source),
                            if (ioc.source == IocSource.BUNDLED) stringResource(R.string.sec_ioc_bundled)
                            else ioc.updatedAtMillis?.let { Fmt.date(context, it) } ?: stringResource(R.string.sec_ioc_downloaded),
                        )
                    }
                    Text(stringResource(R.string.sec_ioc_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SecondaryAction(stringResource(if (s.local.iocUpdating) R.string.sec_ioc_updating else R.string.sec_ioc_update), vm::updateIoc, enabled = !s.local.iocUpdating, icon = Icons.Outlined.CloudDownload)
                }
            }
        }
        item {
            NavRow(Icons.Outlined.Block, stringResource(R.string.screen_adblock),
                if (s.ad.running) stringResource(R.string.ab_status_on, s.ad.blocked, s.ad.queries) else stringResource(R.string.ab_subtitle), { nav.go(Dest.ADBLOCK) },
                trailing = if (s.ad.running) ({ StatusPill(Tone.HEALTHY, stringResource(R.string.on)) }) else null)
        }
        item {
            NavRow(Icons.Outlined.Shield, stringResource(R.string.sec_play_protect), stringResource(R.string.sec_play_protect_body), { vm.open(SystemIntents.Target.SECURITY) })
        }
        item { Text(stringResource(R.string.sec_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun CapabilityCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, apps: List<AppAssessment>, onSettings: () -> Unit, onApp: (String) -> Unit) {
    CareCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(icon, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                StatusPill(if (apps.isEmpty()) Tone.HEALTHY else Tone.NEUTRAL, stringResource(R.string.privacy_app_count, apps.size))
            }
            if (apps.isEmpty()) {
                Text(stringResource(R.string.sec_cap_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            apps.forEach { a -> TextButton(onClick = { onApp(a.facts.packageName) }) { Text(a.facts.label, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
            TextButton(onClick = onSettings) { Text(stringResource(R.string.action_open_settings)) }
        }
    }
}

@Composable
fun SecurityAppScreen(nav: Navigator, padding: PaddingValues) {
    val vm: SecurityViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val a = s.selectedAssessment
    ScreenScaffold(title = a?.facts?.label ?: stringResource(R.string.screen_security), outerPadding = padding, onBack = nav.back, message = s.local.message, onMessageShown = vm::messageShown) {
        if (a == null) {
            item { EmptyState(Icons.Outlined.Security, stringResource(R.string.sec_no_app), stringResource(R.string.sec_no_app_body)) }
            return@ScreenScaffold
        }
        item {
            CareCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.facts.packageName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        StatusPill(a.level.tone(), stringResource(a.level.label()))
                    }
                    InfoRow(stringResource(R.string.sec_installer), a.facts.installer ?: stringResource(R.string.sec_installer_unknown))
                    a.facts.firstInstallMillis?.let { InfoRow(stringResource(R.string.sec_installed_on), Fmt.date(context, it)) }
                    InfoRow(stringResource(R.string.sec_launcher), stringResource(if (a.facts.hasLauncherIcon) R.string.sec_launcher_yes else R.string.sec_launcher_no))
                }
            }
        }
        if (a.level >= ThreatLevel.HIGH) {
            item { NoticeCard(Tone.CRITICAL, stringResource(R.string.sec_what_now), stringResource(R.string.sec_what_now_body)) }
        }
        item { SectionHeader(stringResource(R.string.sec_findings)) }
        if (a.findings.isEmpty()) item { Text(stringResource(R.string.sec_no_findings), style = MaterialTheme.typography.bodyMedium) }
        items(a.findings, key = { it.id.name }) { f ->
            CareCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(f.id.title()), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        StatusPill(f.level.tone(), stringResource(f.level.label()))
                    }
                    Text(stringResource(f.id.body()), style = MaterialTheme.typography.bodyMedium)
                    if (f.id == FindingId.KNOWN_STALKERWARE && f.detail != null) Text(stringResource(R.string.sec_family, f.detail!!), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { SectionHeader(stringResource(R.string.sec_actions)) }
        item { PrimaryAction(stringResource(R.string.sec_open_app_info), { vm.openApp(a.facts.packageName) }) }
        item { Text(stringResource(R.string.sec_open_app_info_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (a.facts.accessibilityServiceEnabled) item { SecondaryAction(stringResource(R.string.sec_turn_off_a11y), { vm.open(SystemIntents.Target.ACCESSIBILITY) }, Modifier.fillMaxWidth()) }
        if (a.facts.notificationListenerEnabled) item { SecondaryAction(stringResource(R.string.sec_turn_off_notifications), { vm.open(SystemIntents.Target.NOTIFICATION_LISTENERS) }, Modifier.fillMaxWidth()) }
        if (a.facts.deviceAdminActive) item { SecondaryAction(stringResource(R.string.sec_turn_off_admin), { vm.open(SystemIntents.Target.SECURITY) }, Modifier.fillMaxWidth()) }
        if (a.facts.packageName !in s.blocked) {
            item { SecondaryAction(stringResource(R.string.sec_block_internet), { vm.blockFromScan(a.facts.packageName) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Block) }
        }
        if (a.level >= ThreatLevel.HIGH) {
            item { Text(stringResource(R.string.sec_stalkerware_safety), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun FirewallScreen(nav: Navigator, padding: PaddingValues) {
    val vm: SecurityViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadNetApps() }

    ScreenScaffold(title = stringResource(R.string.screen_firewall), outerPadding = padding, onBack = nav.back, message = s.local.message, onMessageShown = vm::messageShown) {
        item { NoticeCard(Tone.INFO, stringResource(R.string.fw_intro_title), stringResource(R.string.fw_intro_body)) }
        item {
            CareCard {
                Column {
                    FwSwitch(stringResource(R.string.fw_enable), fwStatusText(s), s.firewallOn) { vm.setFirewall(it) }
                    Text(stringResource(R.string.fw_vpn_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { vm.open(SystemIntents.Target.VPN) }) { Text(stringResource(R.string.fw_always_on)) }
                }
            }
        }
        item { SectionHeader(stringResource(R.string.fw_apps)) }
        item {
            OutlinedTextField(
                value = s.local.query, onValueChange = vm::setQuery, singleLine = true,
                label = { Text(stringResource(R.string.fw_search)) }, modifier = Modifier.fillMaxWidth(),
            )
        }
        item { FwSwitch(stringResource(R.string.fw_show_system), null, s.local.showSystemApps) { vm.setShowSystem(it) } }
        val apps = s.local.netApps
        if (apps == null) {
            item { ProgressBlock(stringResource(R.string.fw_loading), null) }
        } else {
            val visible = s.visibleNetApps
            if (visible.isEmpty()) item { Text(stringResource(R.string.fw_no_apps), style = MaterialTheme.typography.bodyMedium) }
            items(visible, key = { "n-" + it.packageName }) { app ->
                FwAppRow(app.label, app.packageName, app.packageName in s.policy.blockWifi, app.packageName in s.policy.blockMobile) { kind, blocked -> vm.setBlocked(app.packageName, kind, blocked) }
            }
        }
        item { Text(stringResource(R.string.fw_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun fwStatusText(s: com.bright.onepluscare.ui.vm.SecurityUiState): String {
    val st = s.status
    if (!st.running) return stringResource(R.string.fw_status_off, s.blocked.size)
    val net = stringResource(
        when (st.network) {
            NetKind.WIFI -> R.string.fw_net_wifi
            NetKind.MOBILE -> R.string.fw_net_mobile
            NetKind.OTHER -> R.string.fw_net_other
            NetKind.NONE -> R.string.fw_net_none
        },
    )
    return if (st.selfOnly) stringResource(R.string.fw_status_idle, net) else stringResource(R.string.fw_status_on_net, net, st.blockingNow)
}

@Composable
private fun FwAppRow(label: String, pkg: String, wifiBlocked: Boolean, mobileBlocked: Boolean, onChange: (NetKind, Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple(NetKind.WIFI, R.string.fw_net_wifi, wifiBlocked), Triple(NetKind.MOBILE, R.string.fw_net_mobile, mobileBlocked)).forEach { (kind, name, blocked) ->
                val netName = stringResource(name)
                FilterChip(
                    selected = blocked,
                    onClick = { onChange(kind, !blocked) },
                    label = { Text(stringResource(if (blocked) R.string.fw_chip_blocked else R.string.fw_chip_allowed, netName)) },
                    leadingIcon = if (blocked) ({ androidx.compose.material3.Icon(Icons.Outlined.Block, contentDescription = null) }) else null,
                )
            }
        }
    }
}

@Composable
private fun FwSwitch(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { role = Role.Switch }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun AdBlockScreen(nav: Navigator, padding: PaddingValues) {
    val vm: SecurityViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.loadAdBlock() }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    ScreenScaffold(title = stringResource(R.string.screen_adblock), outerPadding = padding, onBack = nav.back, message = s.local.message, onMessageShown = vm::messageShown) {
        item { NoticeCard(Tone.INFO, stringResource(R.string.ab_intro_title), stringResource(R.string.ab_intro_body)) }
        if (s.local.privateDnsStrict) {
            item { NoticeCard(Tone.ATTENTION, stringResource(R.string.ab_private_dns_title), stringResource(R.string.ab_private_dns_body)) }
        }
        item {
            CareCard {
                Column {
                    FwSwitch(
                        stringResource(R.string.ab_enable),
                        if (s.ad.running) stringResource(R.string.ab_status_on, s.ad.blocked, s.ad.queries) else stringResource(R.string.ab_status_off),
                        s.ad.running,
                    ) { vm.setAdBlock(it) }
                    if (s.firewallOn) Text(stringResource(R.string.ab_replaces_firewall), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.ad.noUpstream) Text(stringResource(R.string.ab_no_upstream), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            CareCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.ab_list_title), style = MaterialTheme.typography.titleSmall)
                    val info = s.listInfo
                    InfoRow(stringResource(R.string.ab_list_entries), info?.entries?.takeIf { it > 0 }?.let { Fmt.number(it) } ?: stringResource(R.string.ab_list_none))
                    info?.updatedAtMillis?.let { InfoRow(stringResource(R.string.sec_ioc_source), Fmt.date(context, it)) }
                    Text(stringResource(R.string.ab_list_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SecondaryAction(stringResource(if (s.local.listUpdating) R.string.sec_ioc_updating else R.string.ab_list_update), vm::updateList, enabled = !s.local.listUpdating, icon = Icons.Outlined.CloudDownload)
                }
            }
        }
        if (s.ad.topBlocked.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.ab_top_blocked)) }
            items(s.ad.topBlocked, key = { "b-" + it.first }) { (host, count) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(host, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.ab_times, count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { vm.allowHost(host) }) { Text(stringResource(R.string.ab_allow)) }
                }
            }
            item { Text(stringResource(R.string.ab_top_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (s.exceptions.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.ab_exceptions)) }
            items(s.exceptions.toList(), key = { "e-$it" }) { host ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(host, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { vm.removeException(host) }) { Text(stringResource(R.string.ab_remove)) }
                }
            }
        }
        item { SectionHeader(stringResource(R.string.ab_alt_title)) }
        item {
            CareCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.ab_alt_body), style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(PRIVATE_DNS_ADBLOCK, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(PRIVATE_DNS_ADBLOCK)) }) { Text(stringResource(R.string.ab_copy)) }
                    }
                    TextButton(onClick = { vm.open(SystemIntents.Target.PRIVATE_DNS_NETWORK) }) { Text(stringResource(R.string.action_open_settings)) }
                    Text(stringResource(R.string.ab_alt_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Text(stringResource(R.string.ab_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private const val PRIVATE_DNS_ADBLOCK = "dns.adguard-dns.com"

private fun com.bright.onepluscare.core.security.ScanVerdict.tone() = when (this) {
    com.bright.onepluscare.core.security.ScanVerdict.KNOWN_MALICIOUS -> Tone.CRITICAL
    com.bright.onepluscare.core.security.ScanVerdict.SUSPICIOUS -> Tone.ATTENTION
    com.bright.onepluscare.core.security.ScanVerdict.NO_KNOWN_MATCH -> Tone.HEALTHY
    com.bright.onepluscare.core.security.ScanVerdict.INCONCLUSIVE -> Tone.NEUTRAL
}

private fun com.bright.onepluscare.core.security.ScanVerdict.label() = when (this) {
    com.bright.onepluscare.core.security.ScanVerdict.KNOWN_MALICIOUS -> R.string.mw_v_malicious
    com.bright.onepluscare.core.security.ScanVerdict.SUSPICIOUS -> R.string.mw_v_suspicious
    com.bright.onepluscare.core.security.ScanVerdict.NO_KNOWN_MATCH -> R.string.mw_v_no_match
    com.bright.onepluscare.core.security.ScanVerdict.INCONCLUSIVE -> R.string.mw_v_inconclusive
}

private fun com.bright.onepluscare.core.security.HashSource.label() = when (this) {
    com.bright.onepluscare.core.security.HashSource.ECHAP_STALKERWARE -> R.string.mw_src_echap
    com.bright.onepluscare.core.security.HashSource.MALWAREBAZAAR -> R.string.mw_src_bazaar
}

@Composable
fun MalwareScanScreen(nav: Navigator, padding: PaddingValues) {
    val vm: com.bright.onepluscare.ui.vm.MalwareScanViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.load() }
    var keyInput by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }

    ScreenScaffold(title = stringResource(R.string.screen_malware), outerPadding = padding, onBack = nav.back, message = s.message, onMessageShown = vm::messageShown) {
        item { NoticeCard(Tone.INFO, stringResource(R.string.mw_intro_title), stringResource(R.string.mw_intro_body)) }

        // 1. Installed apps
        item { SectionHeader(stringResource(R.string.mw_installed)) }
        item { FwSwitch(stringResource(R.string.mw_include_system), stringResource(R.string.mw_include_system_body), s.includeSystem) { vm.setIncludeSystem(it) } }
        item {
            if (s.scanning) ProgressBlock(stringResource(R.string.mw_scanning), s.progress, onCancel = vm::cancelScan)
            else PrimaryAction(stringResource(R.string.mw_scan_installed), vm::scanInstalled, icon = Icons.Outlined.Search)
        }
        s.installed?.let { r ->
            item {
                CareCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.sec_result), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            StatusPill(if (r.matches.isEmpty()) Tone.HEALTHY else Tone.CRITICAL, stringResource(if (r.matches.isEmpty()) R.string.mw_v_no_match else R.string.mw_v_malicious))
                        }
                        Text(stringResource(R.string.mw_installed_result, r.scannedApps, Fmt.bytes(context, r.bytesHashed), r.matches.size), style = MaterialTheme.typography.bodyMedium)
                        if (r.unreadable > 0) Text(stringResource(R.string.mw_unreadable, r.unreadable), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(r.matches, key = { "m-" + it.packageName }) { m ->
                NoticeCard(
                    Tone.CRITICAL, m.label, stringResource(R.string.mw_match_body, m.entry.family, stringResource(m.entry.source.label())),
                    action = { TextButton(onClick = { vm.openApp(m.packageName) }) { Text(stringResource(R.string.sec_open_app_info)) } },
                )
            }
        }

        // 2. APK file before installing
        item { SectionHeader(stringResource(R.string.mw_apk)) }
        item { Text(stringResource(R.string.mw_apk_body), style = MaterialTheme.typography.bodyMedium) }
        item {
            if (s.checkingApk) ProgressBlock(stringResource(R.string.mw_checking_apk), null)
            else SecondaryAction(stringResource(R.string.mw_pick_apk), vm::pickApk, Modifier.fillMaxWidth(), icon = Icons.Outlined.Search)
        }
        if (s.apkFailed) item { NoticeCard(Tone.ATTENTION, stringResource(R.string.mw_v_inconclusive), stringResource(R.string.mw_apk_failed)) }
        s.apk?.let { a ->
            item {
                CareCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.label ?: a.fileName ?: "APK", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            StatusPill(a.report.verdict.tone(), stringResource(a.report.verdict.label()))
                        }
                        a.packageName?.let { InfoRow(stringResource(R.string.mw_package), it) }
                        a.versionName?.let { InfoRow(stringResource(R.string.mw_version), it) }
                        InfoRow(stringResource(R.string.mw_size), Fmt.bytes(context, a.sizeBytes))
                        a.report.sha256?.let { InfoRow("SHA-256", it.take(16) + "…") }
                        a.report.hashMatch?.let { Text(stringResource(R.string.mw_match_body, it.family, stringResource(it.source.label())), style = MaterialTheme.typography.bodyMedium) }
                        a.report.iocFamily?.let { Text(stringResource(R.string.sec_family, it), style = MaterialTheme.typography.bodyMedium) }
                        Text(
                            stringResource(
                                when (a.report.verdict) {
                                    com.bright.onepluscare.core.security.ScanVerdict.KNOWN_MALICIOUS -> R.string.mw_apk_malicious_advice
                                    com.bright.onepluscare.core.security.ScanVerdict.SUSPICIOUS -> R.string.mw_apk_suspicious_advice
                                    com.bright.onepluscare.core.security.ScanVerdict.NO_KNOWN_MATCH -> R.string.mw_apk_nomatch_advice
                                    com.bright.onepluscare.core.security.ScanVerdict.INCONCLUSIVE -> R.string.mw_apk_failed
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            val findings = a.report.assessment?.findings.orEmpty()
            if (findings.isNotEmpty()) {
                item { Text(stringResource(R.string.mw_apk_could), style = MaterialTheme.typography.titleSmall) }
                items(findings, key = { "f-" + it.id.name }) { f ->
                    CareCard {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(f.id.title()), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                StatusPill(f.level.tone(), stringResource(f.level.label()))
                            }
                            Text(stringResource(f.id.body()), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // 3. Database
        item { SectionHeader(stringResource(R.string.mw_db)) }
        item {
            CareCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.info?.let { i ->
                        InfoRow(stringResource(R.string.mw_src_echap), Fmt.number(i.echap))
                        InfoRow(stringResource(R.string.mw_src_bazaar), Fmt.number(i.bazaar))
                        InfoRow(stringResource(R.string.sec_ioc_source), i.updatedAtMillis?.let { Fmt.date(context, it) } ?: stringResource(R.string.sec_ioc_bundled))
                    }
                    Text(stringResource(R.string.mw_db_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SecondaryAction(stringResource(if (s.updating) R.string.sec_ioc_updating else R.string.mw_update_echap), vm::updateEchap, enabled = !s.updating, icon = Icons.Outlined.CloudDownload)
                }
            }
        }
        item {
            CareCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.mw_bazaar_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.mw_bazaar_body), style = MaterialTheme.typography.bodySmall)
                    if (s.hasKey) {
                        Text(stringResource(R.string.mw_key_present), style = MaterialTheme.typography.bodyMedium)
                        Row {
                            SecondaryAction(stringResource(if (s.updating) R.string.sec_ioc_updating else R.string.mw_update_bazaar), vm::updateBazaar, enabled = !s.updating, icon = Icons.Outlined.CloudDownload)
                            TextButton(onClick = vm::clearKey) { Text(stringResource(R.string.ab_remove)) }
                        }
                    } else {
                        androidx.compose.material3.OutlinedTextField(
                            value = keyInput, onValueChange = { keyInput = it.take(128) }, singleLine = true,
                            label = { Text(stringResource(R.string.mw_key_label)) }, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        )
                        Row {
                            TextButton(onClick = { vm.saveKey(keyInput); keyInput = "" }, enabled = keyInput.isNotBlank()) { Text(stringResource(R.string.mw_key_save)) }
                            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                            TextButton(onClick = { runCatching { uriHandler.openUri(com.bright.onepluscare.data.MalwareScanRepository.BAZAAR_KEY_URL) } }) { Text(stringResource(R.string.mw_key_get)) }
                        }
                    }
                }
            }
        }
        item { Text(stringResource(R.string.mw_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

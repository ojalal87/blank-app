package com.bright.onepluscare.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
        item {
            NavRow(Icons.Outlined.Block, stringResource(R.string.screen_firewall),
                stringResource(if (s.firewallOn) R.string.fw_status_on else R.string.fw_subtitle, s.blocked.size), { nav.go(Dest.FIREWALL) },
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
                    FwSwitch(stringResource(R.string.fw_enable), stringResource(if (s.firewallOn) R.string.fw_status_on else R.string.fw_status_off, s.blocked.size), s.firewallOn) { vm.setFirewall(it) }
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
                FwSwitch(app.label, app.packageName, app.packageName in s.blocked) { vm.setBlocked(app.packageName, it) }
            }
        }
        item { Text(stringResource(R.string.fw_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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

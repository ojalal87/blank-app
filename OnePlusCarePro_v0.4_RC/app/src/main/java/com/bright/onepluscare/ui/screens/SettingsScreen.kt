package com.bright.onepluscare.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bright.onepluscare.BuildConfig
import com.bright.onepluscare.R
import com.bright.onepluscare.core.settings.AppSettings
import com.bright.onepluscare.core.settings.NotificationLevel
import com.bright.onepluscare.data.SystemIntents
import com.bright.onepluscare.ui.components.CareCard
import com.bright.onepluscare.ui.components.InfoRow
import com.bright.onepluscare.ui.components.NavRow
import com.bright.onepluscare.ui.components.PrimaryAction
import com.bright.onepluscare.ui.components.ScreenScaffold
import com.bright.onepluscare.ui.components.SectionHeader
import com.bright.onepluscare.ui.nav.Dest
import com.bright.onepluscare.ui.nav.Navigator
import com.bright.onepluscare.ui.vm.SettingsViewModel
import com.bright.onepluscare.ui.vm.careViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: Navigator, padding: PaddingValues) {
    val vm: SettingsViewModel = careViewModel()
    val s by vm.settings.collectAsStateWithLifecycle()
    val caps by vm.capabilities.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshCapabilities() }
    var exclusionDraft by rememberSaveable { mutableStateOf("") }
    var exclusionError by rememberSaveable { mutableStateOf(false) }

    ScreenScaffold(
        title = stringResource(R.string.screen_settings),
        outerPadding = padding,
        onBack = nav.back,
        message = export.message,
        onMessageShown = vm::exportMessageShown,
    ) {
        item { SectionHeader(stringResource(R.string.set_schedule)) }
        item {
            CareCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwitchRow(stringResource(R.string.set_daily_scan), stringResource(R.string.set_daily_scan_body), s.dailyScanEnabled) { on ->
                        vm.setDailyScan(on)
                        if (on && Build.VERSION.SDK_INT >= 33 && !caps.notificationsAllowed && s.notificationLevel != NotificationLevel.OFF) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    Text(stringResource(R.string.set_notify_level), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NotificationLevel.entries.forEach { level ->
                            FilterChip(
                                selected = s.notificationLevel == level,
                                onClick = { vm.setNotificationLevel(level) },
                                label = {
                                    Text(stringResource(when (level) {
                                        NotificationLevel.OFF -> R.string.notify_off
                                        NotificationLevel.CRITICAL_ONLY -> R.string.notify_critical
                                        NotificationLevel.IMPORTANT -> R.string.notify_important
                                    }))
                                },
                            )
                        }
                    }
                    Text(stringResource(R.string.set_schedule_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item { SectionHeader(stringResource(R.string.set_cleaner)) }
        item {
            CareCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.set_large_size), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppSettings.LARGE_FILE_MB_OPTIONS.forEach { mb ->
                            FilterChip(selected = s.largeFileMinMb == mb, onClick = { vm.setLargeFileMb(mb) }, label = { Text(stringResource(R.string.unit_mb_short, mb)) })
                        }
                    }
                    Text(stringResource(R.string.set_large_age), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppSettings.LARGE_FILE_AGE_OPTIONS.forEach { d ->
                            FilterChip(
                                selected = s.largeFileMinAgeDays == d,
                                onClick = { vm.setLargeFileAge(d) },
                                label = { Text(if (d == 0) stringResource(R.string.age_any) else stringResource(R.string.age_older_than, d)) },
                            )
                        }
                    }
                }
            }
        }
        item {
            CareCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.set_exclusions), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.set_exclusions_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    s.exclusions.forEach { ex ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ex, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.removeExclusion(ex) }) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_remove)) }
                        }
                    }
                    OutlinedTextField(
                        value = exclusionDraft,
                        onValueChange = { exclusionDraft = it.take(120); exclusionError = false },
                        label = { Text(stringResource(R.string.set_exclusion_hint)) },
                        isError = exclusionError,
                        supportingText = if (exclusionError) {
                            { Text(stringResource(R.string.set_exclusion_invalid)) }
                        } else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = {
                        if (vm.addExclusion(exclusionDraft)) exclusionDraft = "" else exclusionError = true
                    }) { Text(stringResource(R.string.action_add)) }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.set_trash)) }
        item {
            NavRow(
                Icons.Outlined.DeleteOutline,
                stringResource(R.string.trash_retention),
                if (s.trashRetentionDays == null) stringResource(R.string.retention_never) else stringResource(R.string.retention_days, s.trashRetentionDays ?: 0),
                { nav.go(Dest.TRASH) },
            )
        }

        item { SectionHeader(stringResource(R.string.set_diagnostics)) }
        item {
            CareCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.set_diag_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SwitchRow(stringResource(R.string.set_diag_model), null, s.diagnostics.includeDeviceModel) { v -> vm.setDiagnostics { it.copy(includeDeviceModel = v) } }
                    SwitchRow(stringResource(R.string.set_diag_network), stringResource(R.string.set_diag_network_body), s.diagnostics.includeNetworkLayers) { v -> vm.setDiagnostics { it.copy(includeNetworkLayers = v) } }
                    SwitchRow(stringResource(R.string.set_diag_history), null, s.diagnostics.includeHistorySummary) { v -> vm.setDiagnostics { it.copy(includeHistorySummary = v) } }
                    SwitchRow(stringResource(R.string.set_diag_advanced), stringResource(R.string.set_diag_advanced_body), s.diagnostics.includeAdvanced) { v -> vm.setDiagnostics { it.copy(includeAdvanced = v) } }
                    Text(stringResource(R.string.set_diag_never), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PrimaryAction(stringResource(R.string.action_export), onClick = vm::prepareExport, enabled = !export.building, icon = Icons.Outlined.FileUpload)
                }
            }
        }

        item { SectionHeader(stringResource(R.string.set_general)) }
        item { NavRow(Icons.Outlined.Language, stringResource(R.string.set_language), stringResource(R.string.set_language_body), { vm.open(SystemIntents.Target.APP_LANGUAGE) }) }
        item {
            CareCard {
                Column {
                    InfoRow(stringResource(R.string.set_version), BuildConfig.VERSION_NAME)
                    Text(stringResource(R.string.set_about), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics { role = Role.Switch },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

package com.bright.onepluscare.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bright.onepluscare.R
import com.bright.onepluscare.data.SystemIntents
import com.bright.onepluscare.ui.Labels
import com.bright.onepluscare.ui.components.CareCard
import com.bright.onepluscare.ui.components.InfoRow
import com.bright.onepluscare.ui.components.NavRow
import com.bright.onepluscare.ui.components.NoticeCard
import com.bright.onepluscare.ui.components.ScreenScaffold
import com.bright.onepluscare.ui.components.SectionHeader
import com.bright.onepluscare.ui.components.StatusPill
import com.bright.onepluscare.ui.components.Tone
import com.bright.onepluscare.ui.daysText
import com.bright.onepluscare.ui.nav.Dest
import com.bright.onepluscare.ui.nav.Navigator
import com.bright.onepluscare.ui.vm.AppsViewModel
import com.bright.onepluscare.ui.vm.PrivacyViewModel
import com.bright.onepluscare.ui.vm.careViewModel

@Composable
fun PrivacyScreen(nav: Navigator, padding: PaddingValues) {
    val vm: PrivacyViewModel = careViewModel()
    val apps: AppsViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { apps.loadIfNeeded() }

    ScreenScaffold(title = stringResource(R.string.screen_privacy), outerPadding = padding, onBack = nav.back) {
        item { SectionHeader(stringResource(R.string.privacy_device)) }
        item {
            val sec = s.security
            CareCard {
                Column {
                    InfoRow(stringResource(R.string.privacy_screen_lock), sec?.screenLockSecure?.let { stringResource(if (it) R.string.privacy_lock_on else R.string.privacy_lock_off) })
                    InfoRow(
                        stringResource(R.string.privacy_patch),
                        sec?.securityPatch,
                        note = sec?.patchAgeDays?.let { stringResource(R.string.privacy_patch_age, daysText(it)) },
                    )
                    InfoRow(stringResource(R.string.privacy_dev_options), sec?.developerOptions?.let { stringResource(if (it) R.string.on else R.string.off) })
                    InfoRow(stringResource(R.string.privacy_usb_debugging), sec?.usbDebugging?.let { stringResource(if (it) R.string.on else R.string.off) }, note = stringResource(R.string.privacy_usb_note))
                }
            }
        }
        if (s.security?.screenLockSecure == false) {
            item {
                NoticeCard(Tone.ATTENTION, stringResource(R.string.privacy_lock_off), stringResource(R.string.privacy_lock_advice),
                    action = { TextButton(onClick = { vm.open(SystemIntents.Target.SECURITY) }) { Text(stringResource(R.string.action_open_settings)) } })
            }
        }
        item { NavRow(Icons.Outlined.Security, stringResource(R.string.privacy_open_privacy), stringResource(R.string.privacy_open_privacy_body), { vm.open(SystemIntents.Target.PRIVACY) }) }

        item { SectionHeader(stringResource(R.string.privacy_permissions)) }
        if (!s.appsLoaded) {
            item { Text(stringResource(R.string.privacy_loading_apps), style = MaterialTheme.typography.bodyMedium) }
        } else if (s.byPermission.isEmpty()) {
            item { CareCard { Text(stringResource(R.string.privacy_no_sensitive), style = MaterialTheme.typography.bodyMedium) } }
        }
        if (s.unusedWithSensitive.isNotEmpty()) {
            item { NoticeCard(Tone.ATTENTION, stringResource(R.string.rec_sensitive_unused_title), stringResource(R.string.privacy_unused_body, s.unusedWithSensitive.size)) }
            items(s.unusedWithSensitive, key = { "u-" + it.packageName }) { app ->
                NavRow(Icons.Outlined.Lock, app.label, app.sensitivePermissions.map { stringResource(Labels.sensitive(it)) }.joinToString(" · "), { vm.openApp(app.packageName) })
            }
        } else if (!s.usageAccess && s.appsLoaded) {
            item {
                NoticeCard(Tone.INFO, stringResource(R.string.apps_usage_title), stringResource(R.string.privacy_usage_needed),
                    action = { TextButton(onClick = { nav.go(Dest.PERMISSIONS) }) { Text(stringResource(R.string.action_grant_usage)) } })
            }
        }
        s.byPermission.forEach { (perm, list) ->
            item(key = "p-$perm") {
                CareCard {
                    Column {
                        androidx.compose.foundation.layout.Row {
                            Text(stringResource(Labels.sensitive(perm)), style = MaterialTheme.typography.titleSmall, modifier = androidx.compose.ui.Modifier.weight(1f))
                            StatusPill(Tone.NEUTRAL, stringResource(R.string.privacy_app_count, list.size))
                        }
                        list.take(MAX_LISTED).forEach { app ->
                            TextButton(onClick = { vm.openApp(app.packageName) }) { Text(app.label) }
                        }
                        if (list.size > MAX_LISTED) Text(stringResource(R.string.privacy_and_more, list.size - MAX_LISTED), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { Text(stringResource(R.string.privacy_scope_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { NavRow(Icons.Outlined.PrivacyTip, stringResource(R.string.screen_permissions), stringResource(R.string.permissions_subtitle), { nav.go(Dest.PERMISSIONS) }) }
    }
}

private const val MAX_LISTED = 8

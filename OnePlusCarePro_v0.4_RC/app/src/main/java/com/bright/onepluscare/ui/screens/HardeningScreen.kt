package com.bright.onepluscare.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bright.onepluscare.R
import com.bright.onepluscare.core.hardening.CheckId
import com.bright.onepluscare.core.hardening.CheckState
import com.bright.onepluscare.core.hardening.HardeningCheck
import com.bright.onepluscare.core.hardening.WifiSecurity
import com.bright.onepluscare.data.SystemIntents
import com.bright.onepluscare.ui.components.CareCard
import com.bright.onepluscare.ui.components.ProgressBlock
import com.bright.onepluscare.ui.components.ScreenScaffold
import com.bright.onepluscare.ui.components.StatusPill
import com.bright.onepluscare.ui.components.Tone
import com.bright.onepluscare.ui.nav.Navigator
import com.bright.onepluscare.ui.vm.HardeningViewModel
import com.bright.onepluscare.ui.vm.careViewModel

private fun CheckState.tone() = when (this) {
    CheckState.OK -> Tone.HEALTHY
    CheckState.ATTENTION -> Tone.ATTENTION
    CheckState.CRITICAL -> Tone.CRITICAL
    CheckState.UNKNOWN -> Tone.NEUTRAL
}

private fun CheckState.label() = when (this) {
    CheckState.OK -> R.string.hd_state_ok
    CheckState.ATTENTION -> R.string.hd_state_attention
    CheckState.CRITICAL -> R.string.hd_state_critical
    CheckState.UNKNOWN -> R.string.hd_state_unknown
}

private fun CheckId.title() = when (this) {
    CheckId.SCREEN_LOCK -> R.string.hd_lock
    CheckId.PATCH_AGE -> R.string.hd_patch
    CheckId.STORAGE_ENCRYPTION -> R.string.hd_encryption
    CheckId.USB_DEBUGGING -> R.string.hd_adb
    CheckId.DEVELOPER_OPTIONS -> R.string.hd_dev
    CheckId.LOCK_SCREEN_CONTENT -> R.string.hd_lockscreen
    CheckId.INSTALL_SOURCES -> R.string.hd_installers
    CheckId.WIFI_SECURITY -> R.string.hd_wifi
    CheckId.PRIVATE_DNS -> R.string.hd_dns
}

private fun CheckId.target() = when (this) {
    CheckId.SCREEN_LOCK, CheckId.STORAGE_ENCRYPTION -> SystemIntents.Target.SECURITY
    CheckId.PATCH_AGE -> SystemIntents.Target.DEVICE_INFO
    CheckId.USB_DEBUGGING, CheckId.DEVELOPER_OPTIONS -> SystemIntents.Target.DEVELOPER
    CheckId.LOCK_SCREEN_CONTENT -> SystemIntents.Target.SETTINGS
    CheckId.INSTALL_SOURCES -> SystemIntents.Target.UNKNOWN_SOURCES
    CheckId.WIFI_SECURITY -> SystemIntents.Target.WIFI
    CheckId.PRIVATE_DNS -> SystemIntents.Target.PRIVATE_DNS_NETWORK
}

@Composable
private fun body(c: HardeningCheck, installers: List<String>): String = when (c.id) {
    CheckId.SCREEN_LOCK -> stringResource(if (c.state == CheckState.OK) R.string.hd_lock_ok else R.string.hd_lock_bad)
    CheckId.PATCH_AGE -> c.detail?.let { stringResource(R.string.hd_patch_body, it.toInt()) } ?: stringResource(R.string.hd_unknown_body)
    CheckId.STORAGE_ENCRYPTION -> stringResource(if (c.state == CheckState.OK) R.string.hd_encryption_ok else if (c.state == CheckState.UNKNOWN) R.string.hd_unknown_body else R.string.hd_encryption_bad)
    CheckId.USB_DEBUGGING -> stringResource(
        when {
            c.state == CheckState.OK -> R.string.hd_adb_ok
            c.detail == "shizuku" -> R.string.hd_adb_shizuku
            c.state == CheckState.UNKNOWN -> R.string.hd_unknown_body
            else -> R.string.hd_adb_bad
        },
    )
    CheckId.DEVELOPER_OPTIONS -> stringResource(if (c.state == CheckState.UNKNOWN) R.string.hd_unknown_body else R.string.hd_dev_body)
    CheckId.LOCK_SCREEN_CONTENT -> stringResource(if (c.state == CheckState.OK) R.string.hd_lockscreen_ok else if (c.state == CheckState.UNKNOWN) R.string.hd_lockscreen_unknown else R.string.hd_lockscreen_bad)
    CheckId.INSTALL_SOURCES -> if (installers.isEmpty()) stringResource(R.string.hd_installers_ok) else stringResource(R.string.hd_installers_body, installers.size, installers.take(6).joinToString(", "))
    CheckId.WIFI_SECURITY -> stringResource(
        when (c.detail) {
            WifiSecurity.OPEN.name -> R.string.hd_wifi_open
            WifiSecurity.WEP.name -> R.string.hd_wifi_wep
            WifiSecurity.ENHANCED_OPEN.name -> R.string.hd_wifi_owe
            WifiSecurity.SECURED.name -> R.string.hd_wifi_ok
            WifiSecurity.NOT_ON_WIFI.name -> R.string.hd_wifi_none
            else -> R.string.hd_unknown_body
        },
    )
    CheckId.PRIVATE_DNS -> stringResource(if (c.state == CheckState.OK) R.string.hd_dns_ok else if (c.state == CheckState.UNKNOWN) R.string.hd_unknown_body else R.string.hd_dns_off)
}

@Composable
fun HardeningScreen(nav: Navigator, padding: PaddingValues) {
    val vm: HardeningViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    ScreenScaffold(title = stringResource(R.string.screen_hardening), outerPadding = padding, onBack = nav.back) {
        item { Text(stringResource(R.string.hd_intro), style = MaterialTheme.typography.bodyMedium) }
        if (s.loading && s.facts == null) item { ProgressBlock(stringResource(R.string.hd_loading), null) }
        items(s.checks, key = { it.id.name }) { c ->
            CareCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(c.id.title()), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        StatusPill(c.state.tone(), stringResource(c.state.label()))
                    }
                    Text(body(c, s.facts?.installerApps.orEmpty()), style = MaterialTheme.typography.bodyMedium)
                    if (c.state != CheckState.OK) TextButton(onClick = { vm.open(c.id.target()) }) { Text(stringResource(R.string.action_open_settings)) }
                }
            }
        }
        item { TextButton(onClick = vm::load) { Text(stringResource(R.string.hd_recheck)) } }
    }
}

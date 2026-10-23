package com.bright.onepluscare.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bright.onepluscare.R
import com.bright.onepluscare.core.privacy.AccessState
import com.bright.onepluscare.core.privacy.OpAccess
import com.bright.onepluscare.ui.components.CareCard
import com.bright.onepluscare.ui.components.EmptyState
import com.bright.onepluscare.ui.components.NoticeCard
import com.bright.onepluscare.ui.components.ProgressBlock
import com.bright.onepluscare.ui.components.ScreenScaffold
import com.bright.onepluscare.ui.components.StatusPill
import com.bright.onepluscare.ui.components.Tone
import com.bright.onepluscare.ui.nav.Dest
import com.bright.onepluscare.ui.nav.Navigator
import com.bright.onepluscare.ui.vm.AccessWindow
import com.bright.onepluscare.ui.vm.PrivacyAccessViewModel
import com.bright.onepluscare.ui.vm.careViewModel

@Composable
private fun agoText(ms: Long): String = when {
    ms < 60_000 -> stringResource(R.string.pa_just_now)
    ms < 3_600_000 -> stringResource(R.string.pa_minutes_ago, (ms / 60_000).toInt())
    ms < 86_400_000 -> stringResource(R.string.pa_hours_ago, (ms / 3_600_000).toInt())
    else -> stringResource(R.string.pa_days_ago, (ms / 86_400_000).toInt())
}

@Composable
private fun AccessLine(label: Int, a: OpAccess?) {
    if (a == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(agoText(a.agoMs), style = MaterialTheme.typography.bodySmall)
        if (a.state == AccessState.BACKGROUND || a.anyBackground) {
            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
            StatusPill(Tone.ATTENTION, stringResource(R.string.pa_background))
        }
    }
}

@Composable
fun PrivacyAccessScreen(nav: Navigator, padding: PaddingValues) {
    val vm: PrivacyAccessViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.ready) { if (s.ready && s.local.items == null) vm.load() }

    ScreenScaffold(title = stringResource(R.string.screen_privacy_access), outerPadding = padding, onBack = nav.back) {
        item { NoticeCard(Tone.INFO, stringResource(R.string.pa_intro_title), stringResource(R.string.pa_intro_body)) }
        if (!s.ready) {
            item {
                NoticeCard(
                    Tone.INFO, stringResource(R.string.tuner_gate_title), stringResource(R.string.pa_needs_shizuku),
                    action = {
                        Row {
                            TextButton(onClick = { nav.go(Dest.SHIZUKU_SETUP) }) { Text(stringResource(R.string.shz_open_guide)) }
                            if (s.shizuku.running && !s.shizuku.permissionGranted) TextButton(onClick = vm::requestShizukuPermission) { Text(stringResource(R.string.adv_request_permission)) }
                        }
                    },
                )
            }
            item { TextButton(onClick = vm::openPrivacyDashboard) { Text(stringResource(R.string.pa_open_dashboard)) } }
            return@ScreenScaffold
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(AccessWindow.DAY to R.string.pa_24h, AccessWindow.WEEK to R.string.du_week, AccessWindow.ALL to R.string.pa_all).forEach { (w, l) ->
                    FilterChip(selected = s.local.window == w, onClick = { vm.setWindow(w) }, label = { Text(stringResource(l)) })
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pa_background_only), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(s.local.backgroundOnly, vm::setBackgroundOnly)
            }
        }
        if (s.local.loading) item { ProgressBlock(stringResource(R.string.pa_loading), null) }
        if (s.local.unavailable) item { NoticeCard(Tone.ATTENTION, stringResource(R.string.pa_failed_title), stringResource(R.string.tuner_msg_unavailable)) }
        val list = s.visible
        if (s.local.items != null && list.isEmpty()) item { EmptyState(Icons.Outlined.Videocam, stringResource(R.string.pa_empty_title), stringResource(R.string.pa_empty_body)) }
        items(list, key = { it.packageName }) { a ->
            CareCard(onClick = { vm.openApp(a.packageName) }) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(a.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    AccessLine(R.string.pa_camera, a.camera)
                    AccessLine(R.string.pa_microphone, a.microphone)
                    AccessLine(R.string.pa_location, a.location)
                }
            }
        }
        item { Text(stringResource(R.string.pa_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { TextButton(onClick = vm::load, enabled = !s.local.loading) { Text(stringResource(R.string.pa_refresh)) } }
    }
}

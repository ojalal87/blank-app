package com.bright.onepluscare.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bright.onepluscare.R
import com.bright.onepluscare.shizuku.ShizukuMode
import com.bright.onepluscare.ui.components.CareCard
import com.bright.onepluscare.ui.components.EmptyState
import com.bright.onepluscare.ui.components.InfoRow
import com.bright.onepluscare.ui.components.NoticeCard
import com.bright.onepluscare.ui.components.PrimaryAction
import com.bright.onepluscare.ui.components.ProgressBlock
import com.bright.onepluscare.ui.components.ScreenScaffold
import com.bright.onepluscare.ui.components.SecondaryAction
import com.bright.onepluscare.ui.components.SectionHeader
import com.bright.onepluscare.ui.components.Tone
import com.bright.onepluscare.ui.nav.Navigator
import com.bright.onepluscare.ui.vm.AdvancedViewModel
import com.bright.onepluscare.ui.vm.careViewModel

@Composable
fun AdvancedScreen(nav: Navigator, padding: PaddingValues) {
    val vm: AdvancedViewModel = careViewModel()
    val st by vm.status.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    ScreenScaffold(title = stringResource(R.string.screen_advanced), outerPadding = padding, onBack = nav.back) {
        item { NoticeCard(Tone.INFO, stringResource(R.string.adv_scope_title), stringResource(R.string.adv_scope_body)) }
        item {
            CareCard {
                Column {
                    InfoRow(stringResource(R.string.adv_installed), stringResource(if (st.installed) R.string.yes else R.string.no))
                    InfoRow(stringResource(R.string.adv_running), stringResource(if (st.running) R.string.yes else R.string.no))
                    InfoRow(stringResource(R.string.adv_permission), stringResource(if (st.permissionGranted) R.string.perm_granted else R.string.perm_not_granted))
                    InfoRow(
                        stringResource(R.string.adv_mode),
                        when (st.mode) {
                            ShizukuMode.ROOT -> stringResource(R.string.adv_mode_root)
                            ShizukuMode.SHELL -> stringResource(R.string.adv_mode_shell)
                            ShizukuMode.OTHER -> stringResource(R.string.adv_mode_other)
                            ShizukuMode.UNKNOWN -> null
                        },
                    )
                }
            }
        }
        if (st.mode == ShizukuMode.ROOT) {
            item { NoticeCard(Tone.ATTENTION, stringResource(R.string.adv_root_title), stringResource(R.string.adv_root_body)) }
        }
        when {
            !st.installed -> item {
                CareCard { EmptyState(Icons.Outlined.AdminPanelSettings, stringResource(R.string.adv_not_installed_title), stringResource(R.string.adv_not_installed_body)) }
            }
            !st.running -> item {
                NoticeCard(Tone.NEUTRAL, stringResource(R.string.adv_not_running_title), stringResource(R.string.adv_not_running_body),
                    action = { TextButton(onClick = { vm.openShizuku() }) { Text(stringResource(R.string.adv_open_shizuku)) } })
            }
            !st.permissionGranted -> item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (st.permissionDeniedPermanently) {
                        NoticeCard(Tone.ATTENTION, stringResource(R.string.adv_denied_title), stringResource(R.string.adv_denied_body))
                    } else {
                        PrimaryAction(stringResource(R.string.adv_request_permission), onClick = { vm.requestPermission() })
                    }
                }
            }
            else -> item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryAction(stringResource(R.string.adv_collect), onClick = vm::collect, enabled = !busy, icon = Icons.Outlined.PlayArrow)
                    if (st.serviceConnected) SecondaryAction(stringResource(R.string.adv_stop), onClick = vm::stopService, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (busy) item { ProgressBlock(stringResource(R.string.working), null) }
        report?.let { r ->
            item { SectionHeader(stringResource(R.string.adv_properties)) }
            item {
                CareCard {
                    Column {
                        InfoRow(stringResource(R.string.adv_identity), r.identity)
                        r.properties.forEach { (k, v) -> InfoRow(k, v.ifBlank { null }) }
                    }
                }
            }
            r.diagnostics.forEach { (d, text) ->
                item(key = "d-${d.name}") {
                    CareCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(d.name.lowercase(), style = MaterialTheme.typography.titleSmall)
                            Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        }
                    }
                }
            }
        }
        item { Text(stringResource(R.string.adv_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

package com.bright.onepluscare.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bright.onepluscare.R
import com.bright.onepluscare.data.SystemIntents
import com.bright.onepluscare.ui.Fmt
import com.bright.onepluscare.ui.Labels
import com.bright.onepluscare.ui.bytesText
import com.bright.onepluscare.ui.components.CareCard
import com.bright.onepluscare.ui.components.InfoRow
import com.bright.onepluscare.ui.components.NavRow
import com.bright.onepluscare.ui.components.NoticeCard
import com.bright.onepluscare.ui.components.PrimaryAction
import com.bright.onepluscare.ui.components.ScreenScaffold
import com.bright.onepluscare.ui.components.SecondaryAction
import com.bright.onepluscare.ui.components.SectionHeader
import com.bright.onepluscare.ui.components.Tone
import com.bright.onepluscare.ui.nav.Navigator
import com.bright.onepluscare.ui.vm.RepairViewModel
import com.bright.onepluscare.ui.vm.careViewModel

@Composable
fun RepairScreen(nav: Navigator, padding: PaddingValues) {
    val vm: RepairViewModel = careViewModel()
    val session by vm.session.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val context = LocalContext.current

    ScreenScaffold(title = stringResource(R.string.screen_repair), outerPadding = padding, onBack = nav.back) {
        item { SectionHeader(stringResource(R.string.repair_before_after)) }
        item {
            CareCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.repair_session_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val sNow = session
                    if (sNow == null) {
                        PrimaryAction(stringResource(R.string.action_start_session), onClick = vm::start, icon = Icons.Outlined.PlayArrow)
                    } else {
                        InfoRow(stringResource(R.string.repair_started), Fmt.relativeTime(sNow.startedAtMillis))
                        InfoRow(stringResource(R.string.repair_free_before), bytesText(sNow.freeBytesBefore))
                        InfoRow(stringResource(R.string.repair_pending_trash), bytesText(sNow.trashedBytes))
                        InfoRow(stringResource(R.string.repair_purged), bytesText(sNow.purgedBytes))
                        SecondaryAction(stringResource(R.string.action_finish_session), onClick = vm::finish, icon = Icons.Outlined.Stop)
                    }
                }
            }
        }
        result?.let { r ->
            item {
                CareCard {
                    Column {
                        Text(stringResource(R.string.repair_result), style = MaterialTheme.typography.titleSmall)
                        InfoRow(stringResource(R.string.repair_attributed), bytesText(r.attributedFreedBytes), note = stringResource(R.string.repair_attributed_note))
                        InfoRow(stringResource(R.string.repair_net_change), Fmt.signedBytes(context, r.netFreeChangeBytes), note = stringResource(R.string.repair_net_note))
                        InfoRow(stringResource(R.string.repair_pending_trash), bytesText(r.pendingInTrashBytes), note = stringResource(R.string.repair_pending_note))
                    }
                }
            }
            if (r.externalActivityLikely) {
                item { NoticeCard(Tone.INFO, stringResource(R.string.repair_external_title), stringResource(R.string.repair_external_body)) }
            }
        }

        item { SectionHeader(stringResource(R.string.repair_system_tools)) }
        item { NavRow(Icons.Outlined.Storage, stringResource(R.string.repair_storage_title), stringResource(R.string.repair_storage_body), { vm.open(SystemIntents.Target.STORAGE) }) }
        item { NavRow(Icons.Outlined.Wifi, stringResource(R.string.repair_internet_title), stringResource(R.string.repair_internet_body), { vm.open(SystemIntents.Target.INTERNET_PANEL) }) }
        item { NavRow(Icons.Outlined.BatterySaver, stringResource(R.string.repair_battery_saver_title), stringResource(R.string.repair_battery_saver_body), { vm.open(SystemIntents.Target.BATTERY_SAVER) }) }
        item { NavRow(Icons.Outlined.Notifications, stringResource(R.string.repair_notifications_title), stringResource(R.string.repair_notifications_body), { vm.open(SystemIntents.Target.APP_NOTIFICATIONS) }) }
        items(vm.oem.guidance, key = { "g-" + it.name }) { g ->
            NavRow(guidanceIcon(g), stringResource(Labels.guidanceTitle(g)), stringResource(Labels.guidanceBody(g)), { vm.openGuidance(g) })
        }
        item { Text(stringResource(R.string.battery_no_placebo), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

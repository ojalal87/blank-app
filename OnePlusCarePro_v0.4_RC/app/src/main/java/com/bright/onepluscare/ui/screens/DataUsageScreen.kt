package com.bright.onepluscare.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bright.onepluscare.R
import com.bright.onepluscare.core.security.NetKind
import com.bright.onepluscare.core.usage.UnusualReason
import com.bright.onepluscare.data.SpecialUid
import com.bright.onepluscare.data.UsageOwner
import com.bright.onepluscare.data.UsagePeriod
import com.bright.onepluscare.data.UsageRow
import com.bright.onepluscare.ui.bytesText
import com.bright.onepluscare.ui.components.CareCard
import com.bright.onepluscare.ui.components.EmptyState
import com.bright.onepluscare.ui.components.InfoRow
import com.bright.onepluscare.ui.components.NoticeCard
import com.bright.onepluscare.ui.components.ProgressBlock
import com.bright.onepluscare.ui.components.ScreenScaffold
import com.bright.onepluscare.ui.components.SectionHeader
import com.bright.onepluscare.ui.components.Tone
import com.bright.onepluscare.ui.components.UsageBar
import com.bright.onepluscare.ui.nav.Dest
import com.bright.onepluscare.ui.nav.Navigator
import com.bright.onepluscare.ui.vm.DataUsageViewModel
import com.bright.onepluscare.ui.vm.careViewModel

@Composable
private fun ownerLabel(o: UsageOwner): String = when (o.special) {
    SpecialUid.REMOVED_APPS -> stringResource(R.string.du_removed_apps)
    SpecialUid.TETHERING -> stringResource(R.string.du_tethering)
    SpecialUid.SYSTEM -> stringResource(R.string.du_system)
    null -> o.label
}

@Composable
fun DataUsageScreen(nav: Navigator, padding: PaddingValues) {
    val vm: DataUsageViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf<Int?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(s.usageAccess) { if (s.usageAccess && s.local.report == null) vm.load() }
    val blockedMsg = stringResource(R.string.du_added_to_firewall)

    ScreenScaffold(title = stringResource(R.string.screen_data_usage), outerPadding = padding, onBack = nav.back, message = message, onMessageShown = { message = null }) {
        if (!s.usageAccess) {
            item {
                NoticeCard(Tone.INFO, stringResource(R.string.apps_usage_title), stringResource(R.string.du_needs_usage),
                    action = { TextButton(onClick = { nav.go(Dest.PERMISSIONS) }) { Text(stringResource(R.string.action_grant_usage)) } })
            }
            return@ScreenScaffold
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(UsagePeriod.TODAY to R.string.du_today, UsagePeriod.WEEK to R.string.du_week, UsagePeriod.MONTH to R.string.du_month).forEach { (p, label) ->
                    FilterChip(selected = s.local.period == p, onClick = { vm.load(p) }, label = { Text(stringResource(label)) })
                }
            }
        }
        val r = s.local.report
        if (s.local.loading) item { ProgressBlock(stringResource(R.string.du_loading), null) }
        if (s.local.failed) item { NoticeCard(Tone.ATTENTION, stringResource(R.string.du_failed_title), stringResource(R.string.du_failed_body)) }
        if (r == null) return@ScreenScaffold
        item {
            CareCard {
                Column {
                    InfoRow(stringResource(R.string.fw_net_wifi), bytesText(r.totalWifi))
                    InfoRow(stringResource(R.string.fw_net_mobile), if (r.mobileUnavailable) null else bytesText(r.totalMobile))
                    Text(stringResource(R.string.du_source_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (r.unusual.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.du_unusual)) }
            items(r.unusual, key = { "x-" + it.second.uid }) { (owner, u) ->
                NoticeCard(
                    Tone.ATTENTION, ownerLabel(owner),
                    when (u.reason) {
                        UnusualReason.BACKGROUND_UPLOAD_SPIKE -> stringResource(R.string.du_spike, bytesText(u.todayBytes), bytesText(u.typicalBytes ?: 0))
                        UnusualReason.NEW_BACKGROUND_UPLOADER -> stringResource(R.string.du_new_uploader, bytesText(u.todayBytes))
                    },
                    action = owner.packages.firstOrNull()?.let { pkg -> { TextButton(onClick = { vm.openApp(pkg) }) { Text(stringResource(R.string.sec_open_app_info)) } } },
                )
            }
            item { Text(stringResource(R.string.du_unusual_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(stringResource(R.string.du_apps), Modifier.weight(1f))
                Text(stringResource(R.string.fw_show_system), style = MaterialTheme.typography.bodySmall)
                Switch(s.local.showSystem, vm::setShowSystem)
            }
        }
        val rows = r.rows.filter { s.local.showSystem || !it.owner.isSystem }
        if (rows.isEmpty()) item { EmptyState(Icons.Outlined.DataUsage, stringResource(R.string.du_empty_title), stringResource(R.string.du_empty_body)) }
        val max = rows.maxOfOrNull { it.usage.total }?.coerceAtLeast(1) ?: 1
        items(rows, key = { "u-" + it.owner.uid }) { row ->
            UsageRowCard(row, max, expanded == row.owner.uid, { expanded = if (expanded == row.owner.uid) null else row.owner.uid },
                onAppInfo = vm::openApp,
                onBlock = { pkg, kind -> vm.block(pkg, kind); message = blockedMsg })
        }
    }
}

@Composable
private fun UsageRowCard(row: UsageRow, max: Long, open: Boolean, onToggle: () -> Unit, onAppInfo: (String) -> Unit, onBlock: (String, NetKind) -> Unit) {
    val u = row.usage
    CareCard(onClick = onToggle) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ownerLabel(row.owner), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(bytesText(u.total), style = MaterialTheme.typography.titleSmall)
            }
            UsageBar((u.total.toDouble() / max).toFloat(), Tone.INFO)
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.du_split, bytesText(u.wifi), bytesText(u.mobile), bytesText(u.background)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val pkg = row.owner.packages.firstOrNull()
            if (open && pkg != null) {
                Text(stringResource(R.string.du_upload, bytesText(u.wifiTx + u.mobileTx), bytesText(u.backgroundTx)), style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { onAppInfo(pkg) }) { Text(stringResource(R.string.du_app_info)) }
                    TextButton(onClick = { onBlock(pkg, NetKind.MOBILE) }) { Text(stringResource(R.string.du_block_mobile)) }
                    TextButton(onClick = { onBlock(pkg, NetKind.WIFI) }) { Text(stringResource(R.string.du_block_wifi)) }
                }
            }
        }
    }
}

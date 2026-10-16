package com.bright.onepluscare.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bright.onepluscare.R
import com.bright.onepluscare.core.history.HistoryEvent
import com.bright.onepluscare.ui.Fmt
import com.bright.onepluscare.ui.Labels
import com.bright.onepluscare.ui.bytesText
import com.bright.onepluscare.ui.components.CareCard
import com.bright.onepluscare.ui.components.ConfirmDialog
import com.bright.onepluscare.ui.components.EmptyState
import com.bright.onepluscare.ui.components.ScreenScaffold
import com.bright.onepluscare.ui.nav.Navigator
import com.bright.onepluscare.ui.vm.HistoryGroup
import com.bright.onepluscare.ui.vm.HistoryRange
import com.bright.onepluscare.ui.vm.HistoryViewModel
import com.bright.onepluscare.ui.vm.careViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(nav: Navigator, padding: PaddingValues) {
    val vm: HistoryViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = stringResource(R.string.screen_history),
        outerPadding = padding,
        onBack = nav.back,
        actions = { IconButton(onClick = { confirmClear = true }) { Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.action_clear_history)) } },
    ) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryGroup.entries.forEach { gr ->
                    FilterChip(selected = s.group == gr, onClick = { vm.setGroup(gr) }, label = { Text(stringResource(groupLabel(gr))) })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryRange.entries.forEach { r ->
                    FilterChip(selected = s.range == r, onClick = { vm.setRange(r) }, label = { Text(stringResource(rangeLabel(r))) })
                }
            }
        }
        item {
            CareCard {
                Column {
                    Text(bytesText(s.freedBytes), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.history_freed_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (s.events.isEmpty()) {
            item { CareCard { EmptyState(Icons.Outlined.History, stringResource(R.string.history_empty_title), stringResource(R.string.history_empty_body)) } }
        }
        items(s.events, key = { it.id }) { e -> HistoryRow(e) }
        item { Text(stringResource(R.string.history_privacy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.action_clear_history),
            body = stringResource(R.string.confirm_clear_history),
            confirmLabel = stringResource(R.string.action_clear),
            irreversible = true,
            onConfirm = { confirmClear = false; vm.clear() },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun HistoryRow(e: HistoryEvent) {
    val context = LocalContext.current
    CareCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Labels.history(e.type)), style = MaterialTheme.typography.titleSmall)
                val parts = buildList {
                    add(Fmt.dateTime(context, e.timestampMillis))
                    if (e.count > 0) add(context.getString(R.string.history_items, e.count))
                    e.legacyText?.let { add(it) }
                }
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (e.bytes > 0) Text(bytesText(e.bytes), style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun groupLabel(g: HistoryGroup) = when (g) {
    HistoryGroup.ALL -> R.string.filter_all
    HistoryGroup.CLEANING -> R.string.filter_cleaning
    HistoryGroup.SCANS -> R.string.filter_scans
    HistoryGroup.OTHER -> R.string.filter_other
}

private fun rangeLabel(r: HistoryRange) = when (r) {
    HistoryRange.ALL -> R.string.range_all
    HistoryRange.WEEK -> R.string.range_week
    HistoryRange.MONTH -> R.string.range_month
}

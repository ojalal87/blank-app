package com.bright.onepluscare.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bright.onepluscare.R
import com.bright.onepluscare.core.trash.RetentionPolicy
import com.bright.onepluscare.core.trash.TrashEntry
import com.bright.onepluscare.ui.Fmt
import com.bright.onepluscare.ui.Labels
import com.bright.onepluscare.ui.bytesText
import com.bright.onepluscare.ui.components.CareCard
import com.bright.onepluscare.ui.components.ConfirmDialog
import com.bright.onepluscare.ui.components.EmptyState
import com.bright.onepluscare.ui.components.NoticeCard
import com.bright.onepluscare.ui.components.ProgressBlock
import com.bright.onepluscare.ui.components.ScreenScaffold
import com.bright.onepluscare.ui.components.SectionHeader
import com.bright.onepluscare.ui.components.Tone
import com.bright.onepluscare.ui.components.color
import com.bright.onepluscare.ui.nav.Navigator
import com.bright.onepluscare.ui.vm.TrashViewModel
import com.bright.onepluscare.ui.vm.careViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(nav: Navigator, padding: PaddingValues) {
    val vm: TrashViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()

    ScreenScaffold(title = stringResource(R.string.screen_trash), outerPadding = padding, onBack = nav.back, message = s.message, onMessageShown = vm::messageShown) {
        if (!s.safeTrashAvailable) {
            item { NoticeCard(Tone.ATTENTION, stringResource(R.string.trash_unavailable_title), stringResource(R.string.trash_unavailable_body)) }
        }
        item {
            CareCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(bytesText(s.fileBytes), style = MaterialTheme.typography.displaySmall)
                    Text(stringResource(R.string.trash_summary, s.files.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.trash_space_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.trash_uninstall_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (s.busy) item { ProgressBlock(stringResource(R.string.working), null) }

        item { SectionHeader(stringResource(R.string.trash_retention)) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RetentionPolicy.OPTIONS.forEach { days ->
                        FilterChip(
                            selected = s.retentionDays == days,
                            onClick = { vm.setRetention(days) },
                            label = { Text(if (days == null) stringResource(R.string.retention_never) else stringResource(R.string.retention_days, days)) },
                        )
                    }
                }
                Text(stringResource(R.string.trash_retention_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item { SectionHeader(stringResource(R.string.trash_safe_files)) }
        if (s.files.isEmpty()) {
            item { CareCard { EmptyState(Icons.Outlined.DeleteOutline, stringResource(R.string.trash_empty_title), stringResource(R.string.trash_empty_body)) } }
        } else {
            items(s.files, key = { it.id }) { e -> TrashRow(e, onRestore = { vm.restore(e) }, onDelete = { vm.requestPurge(setOf(e.id)) }) }
            item {
                OutlinedButton(onClick = { vm.requestPurge(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_empty_trash), color = Tone.CRITICAL.color())
                }
            }
        }

        if (s.media.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.trash_system_media)) }
            item { Text(stringResource(R.string.trash_system_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(s.media, key = { it.id }) { e -> TrashRow(e, onRestore = { vm.restore(e) }, onDelete = null) }
        }
    }

    s.purge?.let { p ->
        ConfirmDialog(
            title = stringResource(R.string.confirm_purge_title),
            body = stringResource(R.string.confirm_purge_body, p.count, bytesText(p.bytes)),
            confirmLabel = stringResource(R.string.action_delete_permanently),
            irreversible = true,
            onConfirm = vm::confirmPurge,
            onDismiss = vm::dismissPurge,
        )
    }
}

@Composable
private fun TrashRow(e: TrashEntry, onRestore: () -> Unit, onDelete: (() -> Unit)?) {
    val context = LocalContext.current
    CareCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(stringResource(Labels.trashCategory(e.category)), Fmt.dateTime(context, e.trashedAtMillis)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(bytesText(e.sizeBytes), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
            }
            Row {
                TextButton(onClick = onRestore) { Text(stringResource(R.string.action_restore)) }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        androidx.compose.material3.Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = Tone.CRITICAL.color())
                        Text(stringResource(R.string.action_delete_permanently), color = Tone.CRITICAL.color())
                    }
                }
            }
        }
    }
}

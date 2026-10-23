package com.bright.onepluscare.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TaskAlt
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bright.onepluscare.R
import com.bright.onepluscare.ui.Fmt
import com.bright.onepluscare.ui.components.CareCard
import com.bright.onepluscare.ui.components.EmptyState
import com.bright.onepluscare.ui.components.InfoRow
import com.bright.onepluscare.ui.components.NoticeCard
import com.bright.onepluscare.ui.components.PrimaryAction
import com.bright.onepluscare.ui.components.ProgressBlock
import com.bright.onepluscare.ui.components.ScreenScaffold
import com.bright.onepluscare.ui.components.SecondaryAction
import com.bright.onepluscare.ui.components.SectionHeader
import com.bright.onepluscare.ui.components.StatusPill
import com.bright.onepluscare.ui.components.Tone
import com.bright.onepluscare.ui.nav.Navigator
import com.bright.onepluscare.ui.vm.TrackersViewModel
import com.bright.onepluscare.ui.vm.careViewModel

@Composable
fun TrackersScreen(nav: Navigator, padding: PaddingValues) {
    val vm: TrackersViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.load() }

    ScreenScaffold(title = stringResource(R.string.screen_trackers), outerPadding = padding, onBack = nav.back, message = s.message, onMessageShown = vm::messageShown) {
        item { NoticeCard(Tone.INFO, stringResource(R.string.tr_intro_title), stringResource(R.string.tr_intro_body)) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mw_include_system), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(s.includeSystem, vm::setIncludeSystem)
            }
        }
        item {
            if (s.scanning) ProgressBlock(stringResource(R.string.tr_scanning), s.progress, onCancel = vm::cancel)
            else PrimaryAction(stringResource(R.string.tr_scan), vm::scan, icon = Icons.Outlined.Search)
        }
        val r = s.report
        if (r != null) {
            val withTrackers = r.apps.filter { it.trackers.isNotEmpty() }
            item {
                CareCard {
                    Column {
                        Text(stringResource(R.string.tr_result, r.scanned, withTrackers.size, withTrackers.sumOf { it.trackers.size }), style = MaterialTheme.typography.bodyMedium)
                        if (r.unreadable > 0) Text(stringResource(R.string.mw_unreadable, r.unreadable), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (withTrackers.isEmpty()) item { EmptyState(Icons.Outlined.TaskAlt, stringResource(R.string.tr_none_title), stringResource(R.string.tr_none_body)) }
            item { SectionHeader(stringResource(R.string.tr_apps)) }
            items(withTrackers, key = { it.packageName }) { a ->
                CareCard(onClick = { open = if (open == a.packageName) null else a.packageName }) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            StatusPill(if (a.trackers.size >= 5) Tone.ATTENTION else Tone.NEUTRAL, stringResource(R.string.tr_count, a.trackers.size))
                        }
                        val names = a.trackers.joinToString(" · ") { it.name }
                        Text(names, style = MaterialTheme.typography.bodySmall, maxLines = if (open == a.packageName) 20 else 2, overflow = TextOverflow.Ellipsis)
                        if (open == a.packageName) {
                            a.trackers.groupBy { it.categories.firstOrNull() ?: "-" }.forEach { (cat, list) ->
                                Text("$cat: " + list.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { vm.openApp(a.packageName) }) { Text(stringResource(R.string.du_app_info)) }
                        }
                    }
                }
            }
        }
        item { SectionHeader(stringResource(R.string.tr_db)) }
        item {
            CareCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow(stringResource(R.string.tr_signatures), Fmt.number(s.signatures))
                    InfoRow(stringResource(R.string.sec_ioc_source), s.updatedAtMillis?.let { Fmt.date(context, it) } ?: stringResource(R.string.sec_ioc_bundled))
                    Text(stringResource(R.string.tr_db_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SecondaryAction(stringResource(if (s.updating) R.string.sec_ioc_updating else R.string.tr_update), vm::update, enabled = !s.updating, icon = Icons.Outlined.CloudDownload)
                }
            }
        }
        item { Text(stringResource(R.string.tr_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

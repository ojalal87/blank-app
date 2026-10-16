package com.bright.onepluscare.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bright.onepluscare.R
import com.bright.onepluscare.core.apps.AppEvaluator
import com.bright.onepluscare.core.apps.AppRecord
import com.bright.onepluscare.core.apps.AppSort
import com.bright.onepluscare.ui.Labels
import com.bright.onepluscare.ui.bytesText
import com.bright.onepluscare.ui.components.CareCard
import com.bright.onepluscare.ui.components.EmptyState
import com.bright.onepluscare.ui.components.InfoRow
import com.bright.onepluscare.ui.components.NavRow
import com.bright.onepluscare.ui.components.NoticeCard
import com.bright.onepluscare.ui.components.ProgressBlock
import com.bright.onepluscare.ui.components.ScreenScaffold
import com.bright.onepluscare.ui.components.StatusPill
import com.bright.onepluscare.ui.components.Tone
import com.bright.onepluscare.ui.daysText
import com.bright.onepluscare.ui.durationText
import com.bright.onepluscare.ui.nav.Dest
import com.bright.onepluscare.ui.nav.Navigator
import com.bright.onepluscare.ui.vm.AppsViewModel
import com.bright.onepluscare.ui.vm.careViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppDoctorScreen(nav: Navigator, padding: PaddingValues) {
    val vm: AppsViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadIfNeeded() }

    ScreenScaffold(
        title = stringResource(R.string.screen_app_doctor),
        outerPadding = padding,
        actions = { IconButton(onClick = vm::reload) { Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh)) } },
    ) {
        if (!s.usageAccess) {
            item {
                NoticeCard(
                    Tone.INFO, stringResource(R.string.apps_usage_title), stringResource(R.string.apps_usage_body),
                    action = { TextButton(onClick = { nav.go(Dest.PERMISSIONS) }) { Text(stringResource(R.string.action_grant_usage)) } },
                )
            }
        }
        item { NavRow(Icons.Outlined.PrivacyTip, stringResource(R.string.screen_privacy), stringResource(R.string.privacy_subtitle), { nav.go(Dest.PRIVACY) }) }
        item {
            OutlinedTextField(
                value = s.query.text,
                onValueChange = { t -> vm.setQuery { it.copy(text = t.take(64)) } },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.apps_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSort.entries.forEach { sort ->
                    FilterChip(
                        selected = s.query.sort == sort,
                        onClick = { vm.setQuery { it.copy(sort = sort) } },
                        label = { Text(stringResource(sortLabel(sort))) },
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.query.includeSystem, onClick = { vm.setQuery { it.copy(includeSystem = !it.includeSystem) } }, label = { Text(stringResource(R.string.apps_include_system)) })
                FilterChip(selected = s.query.onlyFlagged, onClick = { vm.setQuery { it.copy(onlyFlagged = !it.onlyFlagged) } }, label = { Text(stringResource(R.string.apps_only_flagged)) })
            }
        }
        val progress = s.progress
        if (s.loading && progress != null) {
            item { ProgressBlock(stringResource(R.string.apps_loading, progress.first, progress.second), if (progress.second > 0) progress.first.toFloat() / progress.second else null) }
        } else if (s.error) {
            item { NoticeCard(Tone.CRITICAL, stringResource(R.string.error_title), stringResource(R.string.apps_error), action = { TextButton(onClick = vm::reload) { Text(stringResource(R.string.action_retry)) } }) }
        } else if (s.inventory != null && s.visible.isEmpty()) {
            item { CareCard { EmptyState(Icons.Outlined.Apps, stringResource(R.string.apps_empty_title), stringResource(R.string.apps_empty_body)) } }
        }
        val window = s.inventory?.windowDays ?: 30
        items(s.visible, key = { it.packageName }) { app -> AppCard(app, window, onOpen = { vm.openDetails(app.packageName) }) }
        if (s.inventory != null) {
            item { Text(stringResource(R.string.apps_metrics_note, window), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun sortLabel(s: AppSort): Int = when (s) {
    AppSort.NAME -> R.string.sort_name
    AppSort.TOTAL_SIZE -> R.string.sort_size
    AppSort.CACHE -> R.string.sort_cache
    AppSort.LAST_USED -> R.string.sort_last_used
    AppSort.FOREGROUND -> R.string.sort_usage
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppCard(app: AppRecord, windowDays: Int, onOpen: () -> Unit) {
    val now = System.currentTimeMillis()
    val flags = AppEvaluator.flags(app, now, windowDays)
    CareCard(onClick = onOpen) {
        Column(Modifier.fillMaxWidth()) {
            Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (flags.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    flags.forEach { StatusPill(Tone.ATTENTION, stringResource(Labels.appFlag(it))) }
                }
            }
            // Each metric is distinct and labelled; null renders "Not available", never 0.
            InfoRow(stringResource(R.string.app_package_size), app.packageBytes?.let { bytesText(it) })
            InfoRow(stringResource(R.string.app_data_size), app.dataBytes?.let { bytesText(it) }, note = stringResource(R.string.app_data_includes_cache))
            InfoRow(stringResource(R.string.app_cache_size), app.cacheBytes?.let { bytesText(it) })
            InfoRow(stringResource(R.string.app_usage_time, windowDays), app.foregroundMillis?.let { durationText(it) })
            InfoRow(stringResource(R.string.app_last_used), AppEvaluator.daysSince(app.lastUsedMillis, now)?.let { if (it == 0L) stringResource(R.string.today) else daysText(it) })
            Text(stringResource(R.string.app_open_settings), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}


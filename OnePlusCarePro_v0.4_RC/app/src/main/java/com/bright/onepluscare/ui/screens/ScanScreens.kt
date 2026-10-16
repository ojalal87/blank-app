package com.bright.onepluscare.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bright.onepluscare.R
import com.bright.onepluscare.core.health.HealthState
import com.bright.onepluscare.core.rules.RecommendationId
import com.bright.onepluscare.core.storage.CleanupCandidate
import com.bright.onepluscare.core.storage.CleanupGroup
import com.bright.onepluscare.core.storage.StorageAccessMode
import com.bright.onepluscare.data.ScanStep
import com.bright.onepluscare.ui.Fmt
import com.bright.onepluscare.ui.Labels
import com.bright.onepluscare.ui.bytesText
import com.bright.onepluscare.ui.components.CareCard
import com.bright.onepluscare.ui.components.ConfirmDialog
import com.bright.onepluscare.ui.components.EmptyState
import com.bright.onepluscare.ui.components.NoticeCard
import com.bright.onepluscare.ui.components.PrimaryAction
import com.bright.onepluscare.ui.components.ProgressBlock
import com.bright.onepluscare.ui.components.ScreenScaffold
import com.bright.onepluscare.ui.components.SecondaryAction
import com.bright.onepluscare.ui.components.SectionHeader
import com.bright.onepluscare.ui.components.StatusPill
import com.bright.onepluscare.ui.components.Tone
import com.bright.onepluscare.ui.components.healthLabel
import com.bright.onepluscare.ui.components.tone
import com.bright.onepluscare.ui.daysText
import com.bright.onepluscare.ui.nav.Dest
import com.bright.onepluscare.ui.nav.Navigator
import com.bright.onepluscare.ui.vm.LocalAppGraph
import com.bright.onepluscare.ui.vm.Running
import com.bright.onepluscare.ui.vm.ScanViewModel
import com.bright.onepluscare.ui.vm.SmartStage
import com.bright.onepluscare.ui.vm.TrashConfirmation
import com.bright.onepluscare.ui.vm.TrashSource
import com.bright.onepluscare.ui.vm.careViewModel

private val STORAGE_RECS = setOf(RecommendationId.CLEANUP_AVAILABLE, RecommendationId.DUPLICATES_AVAILABLE, RecommendationId.STORAGE_LOW, RecommendationId.STORAGE_CRITICAL)

@Composable
fun runningLabel(r: Running): String {
    val stage = r.stage?.let {
        stringResource(
            when (it) {
                SmartStage.HEALTH -> R.string.stage_health
                SmartStage.STORAGE -> R.string.stage_storage
                SmartStage.DUPLICATES -> R.string.stage_duplicates
                SmartStage.APPS -> R.string.stage_apps
            },
        )
    }
    val step = r.step?.let { st ->
        val phase = stringResource(
            when (st.phase) {
                ScanStep.Phase.LISTING -> R.string.phase_listing
                ScanStep.Phase.PARTIAL_HASH -> R.string.phase_partial_hash
                ScanStep.Phase.FULL_HASH -> R.string.phase_full_hash
                ScanStep.Phase.VERIFY -> R.string.phase_verify
            },
        )
        if (st.total != null && st.total > 0) stringResource(R.string.phase_count_total, phase, Fmt.number(st.done), Fmt.number(st.total))
        else stringResource(R.string.phase_count, phase, Fmt.number(st.done))
    }
    return listOfNotNull(stage, step).joinToString(" — ").ifEmpty { stringResource(R.string.scan_starting) }
}

private fun Running.fraction(): Float? {
    val st = step ?: return null
    val total = st.total ?: return null
    return if (total > 0) st.done.toFloat() / total else null
}

@Composable
fun AccessModeNotice(mode: StorageAccessMode, nav: Navigator) {
    when (mode) {
        StorageAccessMode.NONE -> NoticeCard(
            Tone.ATTENTION, stringResource(R.string.access_none_title), stringResource(R.string.access_none_body),
            action = { TextButton(onClick = { nav.go(Dest.PERMISSIONS) }) { Text(stringResource(R.string.action_grant_storage)) } },
        )
        StorageAccessMode.MEDIASTORE, StorageAccessMode.MEDIASTORE_PARTIAL -> NoticeCard(
            Tone.INFO, stringResource(Labels.storageMode(mode)), stringResource(R.string.access_media_body),
            action = { TextButton(onClick = { nav.go(Dest.PERMISSIONS) }) { Text(stringResource(R.string.action_more_access)) } },
        )
        StorageAccessMode.FILESYSTEM -> Unit
    }
}

@Composable
fun TrashConfirmDialog(c: TrashConfirmation, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val counts = stringResource(R.string.confirm_trash_body, c.files.size, bytesText(c.bytes))
    val where = stringResource(if (c.systemTrash) R.string.confirm_trash_system else R.string.confirm_trash_safe)
    val keepers = if (c.protectedKeepers > 0) stringResource(R.string.confirm_keepers_protected, c.protectedKeepers) else null
    val body = listOfNotNull(counts, where, keepers).joinToString("\n\n")
    ConfirmDialog(
        title = stringResource(R.string.confirm_trash_title),
        body = body,
        confirmLabel = stringResource(R.string.action_move_to_trash),
        irreversible = false,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

// --- Smart Scan -------------------------------------------------------------------------------------------

@Composable
fun SmartScanScreen(nav: Navigator, padding: PaddingValues) {
    val vm: ScanViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val graph = LocalAppGraph.current

    ScreenScaffold(title = stringResource(R.string.screen_smart_scan), outerPadding = padding, message = s.message, onMessageShown = vm::messageShown) {
        val running = s.running
        if (running != null) {
            item { ProgressBlock(runningLabel(running), running.fraction(), onCancel = vm::cancel) }
        } else if (!s.smartDone) {
            item {
                CareCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.smart_intro_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.smart_intro_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PrimaryAction(stringResource(R.string.action_start_scan), onClick = vm::startSmartScan, icon = Icons.Outlined.PlayArrow)
                    }
                }
            }
            item { AccessModeNotice(s.mode, nav) }
        }

        if (s.smartDone && running == null) {
            val cleanup = s.cleanup
            val dups = s.duplicates
            // Safe to clean
            item { SectionHeader(stringResource(R.string.group_safe)) }
            item {
                val safe = cleanup?.candidates?.filter { it.group == CleanupGroup.SAFE_TO_CLEAN }.orEmpty()
                GroupSummaryCard(
                    tone = Tone.HEALTHY,
                    title = if (safe.isEmpty()) stringResource(R.string.group_safe_empty) else stringResource(R.string.group_items_bytes, safe.size, bytesText(safe.sumOf { it.file.sizeBytes })),
                    body = stringResource(R.string.group_safe_body),
                    actionLabel = if (safe.isEmpty()) null else stringResource(R.string.action_review_files),
                    onAction = { nav.go(Dest.CLEANER) },
                )
            }
            // Review recommended
            item { SectionHeader(stringResource(R.string.group_review)) }
            item {
                val review = cleanup?.candidates?.filter { it.group == CleanupGroup.REVIEW_RECOMMENDED }.orEmpty()
                GroupSummaryCard(
                    tone = Tone.ATTENTION,
                    title = if (review.isEmpty()) stringResource(R.string.group_review_empty) else stringResource(R.string.group_items_bytes, review.size, bytesText(review.sumOf { it.file.sizeBytes })),
                    body = stringResource(R.string.group_review_body),
                    actionLabel = if (review.isEmpty()) null else stringResource(R.string.action_review_files),
                    onAction = { nav.go(Dest.CLEANER) },
                )
            }
            item {
                GroupSummaryCard(
                    tone = Tone.ATTENTION,
                    title = if (dups == null || dups.groups.isEmpty()) stringResource(R.string.dup_none_found) else stringResource(R.string.dup_summary, dups.groups.size, bytesText(dups.reclaimableBytes)),
                    body = if (dups?.budgetExhausted == true) stringResource(R.string.dup_budget_note) else stringResource(R.string.dup_keep_one_note),
                    actionLabel = if (dups == null || dups.groups.isEmpty()) null else stringResource(R.string.action_review_duplicates),
                    onAction = { nav.go(Dest.DUPLICATES) },
                )
            }
            // Diagnostic findings (non-storage rules)
            val diagnostic = s.findings.filter { it.id !in STORAGE_RECS }
            item { SectionHeader(stringResource(R.string.group_diagnostic)) }
            if (diagnostic.isEmpty()) {
                item { CareCard { Text(stringResource(R.string.group_diagnostic_empty), style = MaterialTheme.typography.bodyMedium) } }
            } else {
                items(diagnostic, key = { "f-" + it.id.name }) { RecommendationCard(it, onAction = { a -> handleAction(a, nav, graph.intents) }) }
            }
            // No action needed
            val healthy = s.health?.factors?.filter { it.state == HealthState.HEALTHY }.orEmpty()
            if (healthy.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.group_no_action)) }
                item {
                    CareCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            healthy.forEach { f ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusPill(Tone.HEALTHY, healthLabel(HealthState.HEALTHY))
                                    Spacer(Modifier.width(10.dp))
                                    Text(stringResource(Labels.healthReason(f.reason)), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
            item { SecondaryAction(stringResource(R.string.action_scan_again), onClick = vm::startSmartScan, icon = Icons.Outlined.Refresh, modifier = Modifier.fillMaxWidth()) }
        }

        item { SectionHeader(stringResource(R.string.tools_cleaning)) }
        item {
            com.bright.onepluscare.ui.components.NavRow(Icons.Outlined.CleaningServices, stringResource(R.string.screen_cleaner), stringResource(R.string.cleaner_subtitle), { nav.go(Dest.CLEANER) })
        }
        item {
            com.bright.onepluscare.ui.components.NavRow(Icons.Outlined.ContentCopy, stringResource(R.string.screen_duplicates), stringResource(R.string.dup_subtitle), { nav.go(Dest.DUPLICATES) })
        }
        item {
            com.bright.onepluscare.ui.components.NavRow(Icons.Outlined.DeleteOutline, stringResource(R.string.screen_trash), stringResource(R.string.trash_subtitle), { nav.go(Dest.TRASH) })
        }
    }
}

@Composable
private fun GroupSummaryCard(tone: Tone, title: String, body: String, actionLabel: String?, onAction: () -> Unit) {
    val action: (@Composable () -> Unit)? = if (actionLabel == null) null else {
        { TextButton(onClick = onAction) { Text(actionLabel) } }
    }
    NoticeCard(tone, title, body, action = action)
}

// --- Cleaner ----------------------------------------------------------------------------------------------

@Composable
fun CleanerScreen(nav: Navigator, padding: PaddingValues) {
    val vm: ScanViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()

    ScreenScaffold(
        title = stringResource(R.string.screen_cleaner),
        outerPadding = padding,
        onBack = nav.back,
        message = s.message,
        onMessageShown = vm::messageShown,
    ) {
        item { AccessModeNotice(s.mode, nav) }
        val running = s.running
        if (running != null) {
            item { ProgressBlock(runningLabel(running), running.fraction(), onCancel = vm::cancel) }
            return@ScreenScaffold
        }
        val cleanup = s.cleanup
        if (cleanup == null) {
            item {
                CareCard {
                    EmptyState(
                        Icons.Outlined.CleaningServices, stringResource(R.string.cleaner_empty_title), stringResource(R.string.cleaner_empty_body),
                        action = { PrimaryAction(stringResource(R.string.action_start_scan), onClick = vm::startCleanupScan, enabled = s.mode != StorageAccessMode.NONE) },
                    )
                }
            }
            return@ScreenScaffold
        }

        item {
            CareCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(bytesText(cleanup.totalBytes), style = MaterialTheme.typography.displaySmall)
                    Text(stringResource(R.string.cleaner_summary, cleanup.candidates.size, Fmt.number(cleanup.scannedFiles)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (cleanup.truncated) Text(stringResource(R.string.cleaner_truncated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.cleaner_not_freed_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (cleanup.candidates.isEmpty()) {
            item { CareCard { EmptyState(Icons.Outlined.TaskAlt, stringResource(R.string.cleaner_nothing_title), stringResource(R.string.cleaner_nothing_body)) } }
        }
        candidateGroup(this, CleanupGroup.SAFE_TO_CLEAN, cleanup.candidates, s.selectedCleanup, vm)
        candidateGroup(this, CleanupGroup.REVIEW_RECOMMENDED, cleanup.candidates, s.selectedCleanup, vm)

        item {
            val selected = cleanup.candidates.filter { it.id in s.selectedCleanup }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryAction(
                    if (selected.isEmpty()) stringResource(R.string.action_select_items) else stringResource(R.string.action_move_n_to_trash, selected.size, bytesText(selected.sumOf { it.file.sizeBytes })),
                    onClick = { vm.requestTrash(TrashSource.CLEANUP) },
                    enabled = selected.isNotEmpty(),
                    icon = Icons.Outlined.DeleteOutline,
                )
                SecondaryAction(stringResource(R.string.action_scan_again), onClick = vm::startCleanupScan, icon = Icons.Outlined.Refresh, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    s.confirmation?.takeIf { it.source == TrashSource.CLEANUP }?.let { c ->
        TrashConfirmDialog(c, onConfirm = vm::confirmTrash, onDismiss = vm::dismissConfirmation)
    }
}

private fun candidateGroup(scope: LazyListScope, group: CleanupGroup, all: List<CleanupCandidate>, selected: Set<String>, vm: ScanViewModel) {
    val list = all.filter { it.group == group }
    if (list.isEmpty()) return
    scope.item(key = "h-$group") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(stringResource(if (group == CleanupGroup.SAFE_TO_CLEAN) R.string.group_safe else R.string.group_review), Modifier.weight(1f))
            val allSelected = list.all { it.id in selected }
            TextButton(onClick = { vm.selectCleanupGroup(group, !allSelected) }) {
                Text(stringResource(if (allSelected) R.string.action_deselect_all else R.string.action_select_all))
            }
        }
    }
    scope.items(list, key = { it.id }) { c -> CandidateRow(c, c.id in selected) { vm.toggleCleanup(c.id) } }
}

@Composable
private fun CandidateRow(c: CleanupCandidate, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Checkbox, onClick = onToggle).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(c.file.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(stringResource(Labels.category(c.category)), c.file.relativePath.ifEmpty { "/" }, daysText(c.ageDays)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(bytesText(c.file.sizeBytes), style = MaterialTheme.typography.labelLarge)
    }
}

// --- Duplicates -------------------------------------------------------------------------------------------

@Composable
fun DuplicatesScreen(nav: Navigator, padding: PaddingValues) {
    val vm: ScanViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()

    ScreenScaffold(title = stringResource(R.string.screen_duplicates), outerPadding = padding, onBack = nav.back, message = s.message, onMessageShown = vm::messageShown) {
        item { AccessModeNotice(s.mode, nav) }
        val running = s.running
        if (running != null) {
            item { ProgressBlock(runningLabel(running), running.fraction(), onCancel = vm::cancel) }
            return@ScreenScaffold
        }
        val d = s.duplicates
        if (d == null) {
            item {
                CareCard {
                    EmptyState(
                        Icons.Outlined.ContentCopy, stringResource(R.string.dup_empty_title), stringResource(R.string.dup_empty_body),
                        action = { PrimaryAction(stringResource(R.string.action_start_scan), onClick = vm::startDuplicateScan, enabled = s.mode != StorageAccessMode.NONE) },
                    )
                }
            }
            return@ScreenScaffold
        }
        item {
            CareCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(bytesText(d.reclaimableBytes), style = MaterialTheme.typography.displaySmall)
                    Text(stringResource(R.string.dup_stats, d.groups.size, d.duplicateCopies, Fmt.number(d.hashedFiles)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.dup_method_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (d.budgetExhausted) Text(stringResource(R.string.dup_budget_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (d.groups.isEmpty()) {
            item { CareCard { EmptyState(Icons.Outlined.TaskAlt, stringResource(R.string.dup_none_found), stringResource(R.string.dup_none_body)) } }
        } else {
            item {
                Row {
                    TextButton(onClick = vm::suggestDuplicateSelection) { Text(stringResource(R.string.action_select_extra_copies)) }
                    TextButton(onClick = vm::clearDuplicateSelection) { Text(stringResource(R.string.action_clear_selection)) }
                }
            }
        }
        d.groups.forEachIndexed { index, group ->
            item(key = "g-${group.sha256}-${group.keeper.id}") {
                SectionHeader(stringResource(R.string.dup_group_title, index + 1, bytesText(group.sizeBytes), group.files.size))
            }
            items(group.files, key = { "d-${it.id}" }) { f ->
                val isKeeper = f.id == group.keeper.id
                val checked = f.id in s.selectedDuplicates
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Checkbox) { vm.toggleDuplicate(f.id) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = { vm.toggleDuplicate(f.id) })
                    Column(Modifier.weight(1f)) {
                        Text(f.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(f.relativePath.ifEmpty { "/" }, Fmt.date(LocalContext.current, f.modifiedMillis)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (isKeeper) StatusPill(Tone.HEALTHY, stringResource(R.string.dup_keeper))
                }
            }
        }
        if (d.groups.isNotEmpty()) {
            item {
                val count = s.selectedDuplicates.size
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NoticeCard(Tone.INFO, stringResource(R.string.dup_guard_title), stringResource(R.string.dup_keep_one_note))
                    PrimaryAction(
                        if (count == 0) stringResource(R.string.action_select_items) else stringResource(R.string.action_move_selected_to_trash, count),
                        onClick = { vm.requestTrash(TrashSource.DUPLICATES) },
                        enabled = count > 0,
                        icon = Icons.Outlined.DeleteOutline,
                    )
                    SecondaryAction(stringResource(R.string.action_scan_again), onClick = vm::startDuplicateScan, icon = Icons.Outlined.Refresh, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    s.confirmation?.takeIf { it.source == TrashSource.DUPLICATES }?.let { c ->
        TrashConfirmDialog(c, onConfirm = vm::confirmTrash, onDismiss = vm::dismissConfirmation)
    }
}


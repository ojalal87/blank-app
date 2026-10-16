package com.bright.onepluscare.ui.vm

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bright.onepluscare.AppGraph
import com.bright.onepluscare.R
import com.bright.onepluscare.core.duplicates.DuplicateConfig
import com.bright.onepluscare.core.duplicates.DuplicateResult
import com.bright.onepluscare.core.duplicates.SelectionGuard
import com.bright.onepluscare.core.health.HealthEvaluator
import com.bright.onepluscare.core.health.HealthReport
import com.bright.onepluscare.core.history.HistoryType
import com.bright.onepluscare.core.rules.AdvisorInput
import com.bright.onepluscare.core.rules.Recommendation
import com.bright.onepluscare.core.storage.CleanupGroup
import com.bright.onepluscare.core.storage.CleanupSummary
import com.bright.onepluscare.core.storage.FileFacts
import com.bright.onepluscare.core.storage.FileOrigin
import com.bright.onepluscare.core.storage.StorageAccessMode
import com.bright.onepluscare.core.trash.MediaTrashItem
import com.bright.onepluscare.core.trash.TrashKind
import com.bright.onepluscare.data.ScanStep
import com.bright.onepluscare.ui.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ScanKind { SMART, CLEANUP, DUPLICATES }
enum class SmartStage { HEALTH, STORAGE, DUPLICATES, APPS }
enum class TrashSource { CLEANUP, DUPLICATES }

data class Running(val kind: ScanKind, val stage: SmartStage?, val step: ScanStep?)

/** Shown before anything is moved. Counts reflect the sanitized selection, not the raw checkbox state. */
data class TrashConfirmation(
    val source: TrashSource,
    val files: List<FileFacts>,
    val categories: Map<String, String>,
    val protectedKeepers: Int,
) {
    val bytes: Long get() = files.sumOf { it.sizeBytes }
    val systemTrash: Boolean get() = files.any { it.origin == FileOrigin.MEDIASTORE }
}

/** [launched] prevents re-showing the system dialog after a configuration change. */
data class PendingSystemTrash(val sender: IntentSender, val confirmation: TrashConfirmation, val launched: Boolean = false)

data class ScanUiState(
    val running: Running? = null,
    val mode: StorageAccessMode = StorageAccessMode.NONE,
    val health: HealthReport? = null,
    val cleanup: CleanupSummary? = null,
    val duplicates: DuplicateResult? = null,
    val findings: List<Recommendation> = emptyList(),
    val smartDone: Boolean = false,
    val selectedCleanup: Set<String> = emptySet(),
    val selectedDuplicates: Set<String> = emptySet(),
    val confirmation: TrashConfirmation? = null,
    val pendingSystemTrash: PendingSystemTrash? = null,
    val message: String? = null,
)

class ScanViewModel(private val g: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(
        ScanUiState(cleanup = g.results.value.cleanup, duplicates = g.results.value.duplicates, mode = g.capabilities.value.storageMode),
    )
    val state: StateFlow<ScanUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun startSmartScan() = launchScan(ScanKind.SMART) {
        val facts = g.device.facts()
        val health = HealthEvaluator.evaluate(facts)
        _state.update { it.copy(health = health, running = Running(ScanKind.SMART, SmartStage.STORAGE, null)) }

        val settings = g.settings.settings.value
        val cleanup = g.storage.scanCleanup(settings.scanConfig()) { step -> progress(ScanKind.SMART, SmartStage.STORAGE, step) }
        publishCleanup(cleanup)

        _state.update { it.copy(running = Running(ScanKind.SMART, SmartStage.DUPLICATES, null)) }
        val (dups, _) = g.storage.scanDuplicates(settings.scanConfig(), SMART_DUP_CONFIG) { step -> progress(ScanKind.SMART, SmartStage.DUPLICATES, step) }
        publishDuplicates(dups)

        val caps = g.capabilities.value
        var apps = g.results.value.apps
        if (caps.usageAccess) {
            _state.update { it.copy(running = Running(ScanKind.SMART, SmartStage.APPS, null)) }
            apps = g.apps.load()
            g.updateResults { it.copy(apps = apps) }
        }

        val fileTrash = g.storage.trashEntries().filter { it.kind == TrashKind.FILE }
        val findings = g.engine.evaluate(
            AdvisorInput(
                facts = facts,
                health = health,
                storageMode = cleanup.mode,
                cleanup = cleanup,
                duplicates = dups,
                trashFileBytes = fileTrash.sumOf { it.sizeBytes },
                trashFileCount = fileTrash.size,
                usageAccess = caps.usageAccess,
                apps = apps?.apps,
                usageWindowDays = apps?.windowDays ?: 30,
                network = g.results.value.network?.diagnosis,
            ),
        )
        _state.update { it.copy(findings = findings, smartDone = true) }
    }

    fun startCleanupScan() = launchScan(ScanKind.CLEANUP) {
        val cleanup = g.storage.scanCleanup(g.settings.settings.value.scanConfig()) { step -> progress(ScanKind.CLEANUP, null, step) }
        publishCleanup(cleanup)
    }

    fun startDuplicateScan() = launchScan(ScanKind.DUPLICATES) {
        val (dups, _) = g.storage.scanDuplicates(g.settings.settings.value.scanConfig(), DuplicateConfig()) { step -> progress(ScanKind.DUPLICATES, null, step) }
        publishDuplicates(dups)
    }

    fun cancel() {
        job?.cancel()
    }

    fun toggleCleanup(id: String) = _state.update { s ->
        s.copy(selectedCleanup = if (id in s.selectedCleanup) s.selectedCleanup - id else s.selectedCleanup + id)
    }

    fun selectCleanupGroup(group: CleanupGroup, select: Boolean) = _state.update { s ->
        val ids = s.cleanup?.candidates?.filter { it.group == group }?.map { it.id }.orEmpty()
        s.copy(selectedCleanup = if (select) s.selectedCleanup + ids else s.selectedCleanup - ids.toSet())
    }

    fun toggleDuplicate(id: String) = _state.update { s ->
        s.copy(selectedDuplicates = if (id in s.selectedDuplicates) s.selectedDuplicates - id else s.selectedDuplicates + id)
    }

    fun suggestDuplicateSelection() = _state.update { s ->
        s.copy(selectedDuplicates = s.duplicates?.let { SelectionGuard.suggest(it.groups) }.orEmpty())
    }

    fun clearDuplicateSelection() = _state.update { it.copy(selectedDuplicates = emptySet()) }

    /** Step 1 of removal: build a sanitized confirmation. Nothing is moved yet. */
    fun requestTrash(source: TrashSource) = _state.update { s ->
        when (source) {
            TrashSource.CLEANUP -> {
                val picked = s.cleanup?.candidates?.filter { it.id in s.selectedCleanup }.orEmpty()
                if (picked.isEmpty()) s else s.copy(
                    confirmation = TrashConfirmation(source, picked.map { it.file }, picked.associate { it.id to it.category.name }, 0),
                )
            }
            TrashSource.DUPLICATES -> {
                val groups = s.duplicates?.groups.orEmpty()
                val sanitized = SelectionGuard.sanitize(groups, s.selectedDuplicates)
                val files = groups.flatMap { it.files }.filter { it.id in sanitized.ids }
                if (files.isEmpty()) s.copy(message = if (sanitized.protectedIds.isNotEmpty()) str(R.string.msg_keep_one) else null)
                else s.copy(confirmation = TrashConfirmation(source, files, files.associate { it.id to "DUPLICATE" }, sanitized.protectedIds.size))
            }
        }
    }

    fun dismissConfirmation() = _state.update { it.copy(confirmation = null) }

    /** Step 2: user confirmed. Filesystem items go to Safe Trash now; media items need the system dialog. */
    fun confirmTrash() {
        val c = _state.value.confirmation ?: return
        _state.update { it.copy(confirmation = null) }
        viewModelScope.launch {
            val fsFiles = c.files.filter { it.origin == FileOrigin.FILESYSTEM }
            val media = c.files.filter { it.origin == FileOrigin.MEDIASTORE }
            if (fsFiles.isNotEmpty()) {
                val r = g.storage.moveToSafeTrash(fsFiles) { c.categories[it.id] ?: "other" }
                removeFromResults(r.movedIds)
                if (r.movedIds.isNotEmpty()) {
                    g.history.add(HistoryType.TRASH, count = r.movedIds.size, bytes = r.movedBytes)
                    g.updateResults { it.copy(session = it.session?.recordTrash(r.movedBytes, r.movedIds.size)) }
                    g.trashChanged()
                }
                val msg = if (r.refused == 0) str(R.string.msg_moved_safe_trash, r.movedIds.size, Fmt.bytes(g.appContext, r.movedBytes))
                else str(R.string.msg_moved_with_refused, r.movedIds.size, r.refused)
                _state.update { it.copy(message = msg) }
            }
            if (media.isNotEmpty()) {
                val sender = g.storage.systemTrashRequest(media.mapNotNull { it.contentUri }, trash = true)
                if (sender == null) _state.update { it.copy(message = str(R.string.msg_system_trash_unavailable)) }
                else _state.update { it.copy(pendingSystemTrash = PendingSystemTrash(sender, c.copy(files = media))) }
            }
        }
    }

    /** The UI launched the IntentSender; clear it so a recomposition never launches it twice. */
    fun systemTrashLaunched() = _state.update { it.copy(pendingSystemTrash = it.pendingSystemTrash?.copy(launched = true)) }

    fun onSystemTrashResult(confirmation: TrashConfirmation, ok: Boolean) {
        _state.update { it.copy(pendingSystemTrash = null) }
        if (!ok) {
            _state.update { it.copy(message = str(R.string.msg_system_trash_cancelled)) }
            return
        }
        viewModelScope.launch {
            g.storage.recordSystemTrash(
                confirmation.files.mapNotNull { f -> f.contentUri?.let { MediaTrashItem(it, f.displayName, f.sizeBytes, confirmation.categories[f.id] ?: "other") } },
            )
            val ids = confirmation.files.map { it.id }.toSet()
            removeFromResults(ids)
            g.history.add(HistoryType.SYSTEM_TRASH, count = ids.size, bytes = confirmation.bytes)
            g.updateResults { it.copy(session = it.session?.recordTrash(confirmation.bytes, ids.size)) }
            g.trashChanged()
            _state.update { it.copy(message = str(R.string.msg_moved_system_trash, ids.size)) }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun launchScan(kind: ScanKind, block: suspend () -> Unit) {
        if (_state.value.running != null) return
        job = viewModelScope.launch {
            g.refreshCapabilities()
            _state.update {
                it.copy(
                    running = Running(kind, if (kind == ScanKind.SMART) SmartStage.HEALTH else null, null),
                    mode = g.storage.accessMode(),
                    smartDone = if (kind == ScanKind.SMART) false else it.smartDone,
                )
            }
            try {
                block()
            } catch (e: CancellationException) {
                _state.update { it.copy(message = str(R.string.msg_scan_cancelled)) }
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(message = str(R.string.msg_scan_failed)) }
            } finally {
                _state.update { it.copy(running = null) }
            }
        }
    }

    private fun progress(kind: ScanKind, stage: SmartStage?, step: ScanStep) {
        _state.update { it.copy(running = Running(kind, stage, step)) }
    }

    private fun publishCleanup(cleanup: CleanupSummary) {
        g.updateResults { it.copy(cleanup = cleanup) }
        g.history.add(HistoryType.SCAN, count = cleanup.candidates.size, bytes = cleanup.totalBytes, detail = cleanup.mode.name.lowercase())
        _state.update { s -> s.copy(cleanup = cleanup, mode = cleanup.mode, selectedCleanup = s.selectedCleanup.filterTo(HashSet()) { id -> cleanup.candidates.any { it.id == id } }) }
    }

    private fun publishDuplicates(d: DuplicateResult) {
        g.updateResults { it.copy(duplicates = d) }
        g.history.add(HistoryType.DUPLICATE_SCAN, count = d.groups.size, bytes = d.reclaimableBytes)
        _state.update { it.copy(duplicates = d, selectedDuplicates = emptySet()) }
    }

    private fun removeFromResults(ids: Set<String>) {
        if (ids.isEmpty()) return
        // A file can appear in both result sets; remove it from both.
        _state.update { s ->
            s.copy(
                cleanup = s.cleanup?.without(ids),
                duplicates = s.duplicates?.without(ids),
                selectedCleanup = s.selectedCleanup - ids,
                selectedDuplicates = s.selectedDuplicates - ids,
            )
        }
        val s = _state.value
        g.updateResults { it.copy(cleanup = s.cleanup, duplicates = s.duplicates) }
    }

    private fun str(id: Int, vararg args: Any): String = g.appContext.getString(id, *args)

    companion object {
        /** Smart Scan uses a smaller hashing budget than the dedicated Duplicate Cleaner. */
        val SMART_DUP_CONFIG = DuplicateConfig(maxHashBytes = 2L * 1_000_000_000L, maxHashFiles = 1_500)
    }
}

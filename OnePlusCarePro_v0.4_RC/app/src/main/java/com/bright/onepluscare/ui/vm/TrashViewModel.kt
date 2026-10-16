package com.bright.onepluscare.ui.vm

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bright.onepluscare.AppGraph
import com.bright.onepluscare.R
import com.bright.onepluscare.core.history.HistoryType
import com.bright.onepluscare.core.trash.RestoreOutcome
import com.bright.onepluscare.core.trash.TrashEntry
import com.bright.onepluscare.core.trash.TrashFailure
import com.bright.onepluscare.core.trash.TrashKind
import com.bright.onepluscare.core.trash.TrashState
import com.bright.onepluscare.ui.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PendingMediaRestore(val sender: IntentSender, val entryId: String, val launched: Boolean = false)

/** Purge confirmation: [ids] null = all Safe Trash files. */
data class PurgeConfirmation(val ids: Set<String>?, val count: Int, val bytes: Long)

data class TrashUiState(
    val files: List<TrashEntry> = emptyList(),
    val media: List<TrashEntry> = emptyList(),
    val retentionDays: Int? = null,
    val safeTrashAvailable: Boolean = true,
    val busy: Boolean = false,
    val purge: PurgeConfirmation? = null,
    val pendingRestore: PendingMediaRestore? = null,
    val message: String? = null,
) {
    val fileBytes: Long get() = files.sumOf { it.sizeBytes }
}

class TrashViewModel(private val g: AppGraph) : ViewModel() {
    private val ui = MutableStateFlow(TrashUiState())

    val state: StateFlow<TrashUiState> = combine(ui, g.trashVersion, g.settings.settings, g.capabilities) { u, _, s, caps ->
        val entries = g.storage.trashEntries().filter { it.state == TrashState.IN_TRASH }.sortedByDescending { it.trashedAtMillis }
        u.copy(
            files = entries.filter { it.kind == TrashKind.FILE },
            media = entries.filter { it.kind == TrashKind.MEDIA },
            retentionDays = s.trashRetentionDays,
            safeTrashAvailable = caps.safeTrashAvailable,
        )
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrashUiState())

    fun restore(entry: TrashEntry) {
        if (entry.kind == TrashKind.MEDIA) {
            val uri = entry.contentUri ?: return
            val sender = g.storage.systemTrashRequest(listOf(uri), trash = false)
            if (sender == null) ui.update { it.copy(message = str(R.string.msg_system_trash_unavailable)) }
            else ui.update { it.copy(pendingRestore = PendingMediaRestore(sender, entry.id)) }
            return
        }
        viewModelScope.launch {
            ui.update { it.copy(busy = true) }
            val msg = when (val r = g.storage.restore(entry.id)) {
                is RestoreOutcome.Restored -> {
                    g.history.add(HistoryType.RESTORE, count = 1, bytes = entry.sizeBytes)
                    str(if (r.renamed) R.string.msg_restored_renamed else R.string.msg_restored)
                }
                is RestoreOutcome.Failed -> str(
                    when (r.reason) {
                        TrashFailure.TRASH_FILE_MISSING -> R.string.msg_restore_missing
                        TrashFailure.RESTORE_TARGET_REJECTED -> R.string.msg_restore_rejected
                        else -> R.string.msg_restore_failed
                    },
                )
            }
            g.trashChanged()
            ui.update { it.copy(busy = false, message = msg) }
        }
    }

    fun restoreLaunched() = ui.update { it.copy(pendingRestore = it.pendingRestore?.copy(launched = true)) }

    fun onMediaRestoreResult(entryId: String, ok: Boolean) {
        ui.update { it.copy(pendingRestore = null) }
        if (!ok) return
        g.storage.forgetMedia(setOf(entryId))
        g.history.add(HistoryType.RESTORE, count = 1)
        g.trashChanged()
        ui.update { it.copy(message = str(R.string.msg_restored)) }
    }

    fun requestPurge(ids: Set<String>?) {
        val files = state.value.files.filter { ids == null || it.id in ids }
        if (files.isEmpty()) return
        ui.update { it.copy(purge = PurgeConfirmation(ids, files.size, files.sumOf { f -> f.sizeBytes })) }
    }

    fun dismissPurge() = ui.update { it.copy(purge = null) }

    fun confirmPurge() {
        val p = ui.value.purge ?: return
        ui.update { it.copy(purge = null, busy = true) }
        viewModelScope.launch {
            val out = g.storage.purge(p.ids)
            if (out.purgedIds.isNotEmpty()) {
                g.history.add(HistoryType.PURGE, count = out.purgedIds.size, bytes = out.bytesFreed)
                g.updateResults { it.copy(session = it.session?.recordPurge(out.bytesFreed)) }
            }
            g.trashChanged()
            ui.update {
                it.copy(
                    busy = false,
                    message = if (out.failedIds.isEmpty()) str(R.string.msg_purged, Fmt.bytes(g.appContext, out.bytesFreed))
                    else str(R.string.msg_purged_partial, out.failedIds.size),
                )
            }
        }
    }

    fun setRetention(days: Int?) = g.settings.update { it.copy(trashRetentionDays = days) }

    fun messageShown() = ui.update { it.copy(message = null) }

    private fun str(id: Int, vararg args: Any): String = g.appContext.getString(id, *args)

}

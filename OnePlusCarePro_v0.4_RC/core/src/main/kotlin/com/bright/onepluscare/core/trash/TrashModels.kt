package com.bright.onepluscare.core.trash

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TrashKind {
    /** Moved by this app into its Safe Trash directory; restorable by this app. */
    @SerialName("file") FILE,

    /** Trashed through the Android MediaStore system trash (API 30+); the system purges it after ~30 days. */
    @SerialName("media") MEDIA,
}

@Serializable
enum class TrashState {
    /** Journaled before the move. If seen at startup, the move may or may not have happened. */
    @SerialName("pending_move") PENDING_MOVE,
    @SerialName("in_trash") IN_TRASH,
    /** Journaled before restore with the exact target path. */
    @SerialName("pending_restore") PENDING_RESTORE,
}

@Serializable
data class TrashEntry(
    val id: String,
    val kind: TrashKind,
    val state: TrashState,
    val displayName: String,
    val category: String,
    val sizeBytes: Long,
    val trashedAtMillis: Long,
    /** FILE only: where it came from and where it will be restored. */
    val originalPath: String? = null,
    /** FILE only: a generated file name inside the trash directory. Never user-controlled. */
    val trashFileName: String? = null,
    /** FILE only, PENDING_RESTORE: the exact destination chosen (and pre-created) for restore. */
    val restoreTarget: String? = null,
    /** MEDIA only. */
    val contentUri: String? = null,
)

@Serializable
data class TrashJournal(
    val schema: Int = SCHEMA,
    val entries: List<TrashEntry> = emptyList(),
) {
    companion object {
        const val SCHEMA = 2
    }
}

sealed interface TrashOutcome {
    data class Moved(val entry: TrashEntry) : TrashOutcome
    data class Refused(val reason: TrashFailure) : TrashOutcome
}

enum class TrashFailure {
    PATH_REJECTED,
    SOURCE_MISSING,
    MOVE_FAILED,
    NOT_FOUND,
    WRONG_KIND,
    TRASH_FILE_MISSING,
    RESTORE_TARGET_REJECTED,
    NO_FREE_NAME,
    RESTORE_FAILED,
    DELETE_FAILED,
}

sealed interface RestoreOutcome {
    /** [renamed] is true when the original name was taken and a "(restored)" name was used instead. */
    data class Restored(val path: String, val renamed: Boolean) : RestoreOutcome
    data class Failed(val reason: TrashFailure) : RestoreOutcome
}

data class PurgeOutcome(
    val purgedIds: Set<String>,
    /** Sum of on-disk sizes of files actually deleted, measured just before deletion. */
    val bytesFreed: Long,
    val failedIds: Set<String>,
)

data class ReconcileReport(
    val completedMoves: Int = 0,
    val rolledBackMoves: Int = 0,
    val completedRestores: Int = 0,
    val rolledBackRestores: Int = 0,
    val missingTrashFiles: Int = 0,
    val adoptedOrphans: Int = 0,
) {
    val changed: Boolean get() = completedMoves + rolledBackMoves + completedRestores + rolledBackRestores + missingTrashFiles + adoptedOrphans > 0
}

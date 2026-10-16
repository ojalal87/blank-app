package com.bright.onepluscare.core.storage

/** How the cleaner can reach shared storage on this device, given the granted permissions. */
enum class StorageAccessMode {
    /** Android 11+ with All Files Access, or Android 8-10 with legacy WRITE_EXTERNAL_STORAGE. Uses Safe Trash. */
    FILESYSTEM,

    /** Android 11+ with media permissions only. Removal goes through the system MediaStore trash dialog. */
    MEDIASTORE,

    /** Android 14+ "selected photos" partial access. Results are limited to user-picked items. */
    MEDIASTORE_PARTIAL,

    /** No usable permission. */
    NONE,
}

object StorageAccessResolver {
    fun resolve(
        sdkInt: Int,
        isExternalStorageManager: Boolean,
        legacyWriteGranted: Boolean,
        mediaReadGranted: Boolean,
        partialMediaGranted: Boolean,
    ): StorageAccessMode = when {
        sdkInt >= 30 && isExternalStorageManager -> StorageAccessMode.FILESYSTEM
        sdkInt in 26..29 && legacyWriteGranted -> StorageAccessMode.FILESYSTEM
        sdkInt >= 30 && mediaReadGranted -> StorageAccessMode.MEDIASTORE
        sdkInt >= 34 && partialMediaGranted -> StorageAccessMode.MEDIASTORE_PARTIAL
        else -> StorageAccessMode.NONE
    }

    /** MediaStore.createTrashRequest exists on API 30+ only. Below that we never offer media removal. */
    fun supportsSystemTrash(sdkInt: Int): Boolean = sdkInt >= 30
}

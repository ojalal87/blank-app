package com.bright.onepluscare.core.session

/**
 * Before/After that only reports what can be attributed. Free space changes for many reasons (other apps,
 * the camera, system caches), so the attributed number is the sum of bytes this app *measured* while
 * permanently deleting files during the session; the net change is shown separately and labelled as such.
 * Moving files into Safe Trash or system trash frees nothing and is reported as "pending".
 */
data class MaintenanceSession(
    val startedAtMillis: Long,
    val freeBytesBefore: Long,
    val purgedBytes: Long = 0L,
    val trashedBytes: Long = 0L,
    val trashedCount: Int = 0,
) {
    fun recordPurge(bytes: Long) = copy(purgedBytes = purgedBytes + bytes.coerceAtLeast(0L))
    fun recordTrash(bytes: Long, count: Int) = copy(trashedBytes = trashedBytes + bytes.coerceAtLeast(0L), trashedCount = trashedCount + count)

    fun finish(freeBytesAfter: Long, endedAtMillis: Long) = SessionResult(
        durationMillis = (endedAtMillis - startedAtMillis).coerceAtLeast(0L),
        attributedFreedBytes = purgedBytes,
        netFreeChangeBytes = freeBytesAfter - freeBytesBefore,
        pendingInTrashBytes = trashedBytes,
        trashedCount = trashedCount,
    )
}

data class SessionResult(
    val durationMillis: Long,
    val attributedFreedBytes: Long,
    val netFreeChangeBytes: Long,
    val pendingInTrashBytes: Long,
    val trashedCount: Int,
) {
    /** Net change differs from attributed change by more than 5% (or 50 MB): something else used/freed space. */
    val externalActivityLikely: Boolean
        get() {
            val diff = kotlin.math.abs(netFreeChangeBytes - attributedFreedBytes)
            return diff > maxOf(50_000_000L, attributedFreedBytes / 20)
        }
}

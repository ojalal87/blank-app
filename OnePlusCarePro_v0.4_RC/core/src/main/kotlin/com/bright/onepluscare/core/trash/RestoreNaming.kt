package com.bright.onepluscare.core.trash

object RestoreNaming {
    /**
     * Candidate names for restoring [fileName] when the original is taken:
     * "photo.jpg" -> "photo (restored).jpg", "photo (restored 2).jpg", ...
     * Hidden files and names without extension are handled; the extension is preserved so the
     * file keeps opening with the right app.
     */
    fun candidates(fileName: String, max: Int = 50): Sequence<String> = sequence {
        yield(fileName)
        val dot = fileName.lastIndexOf('.')
        val (base, ext) = if (dot > 0) fileName.substring(0, dot) to fileName.substring(dot) else fileName to ""
        yield("$base (restored)$ext")
        for (i in 2..max) yield("$base (restored $i)$ext")
    }

    /** Make a display name safe to use as a single path segment. */
    fun safeSegment(name: String): String {
        val cleaned = name.replace('/', '_').replace('\u0000', '_').trim()
        return when {
            cleaned.isEmpty() || cleaned == "." || cleaned == ".." -> "restored_file"
            cleaned.length > 200 -> cleaned.takeLast(200)
            else -> cleaned
        }
    }
}

object RetentionPolicy {
    /** Allowed user choices for automatic Safe Trash expiry. null = never expire (default). */
    val OPTIONS: List<Int?> = listOf(null, 7, 14, 30, 60, 90)

    fun sanitize(days: Int?): Int? = if (days in OPTIONS) days else null

    fun isExpired(entry: TrashEntry, nowMillis: Long, retentionDays: Int?): Boolean {
        if (retentionDays == null) return false
        if (entry.kind != TrashKind.FILE || entry.state != TrashState.IN_TRASH) return false
        // A clock moved backwards must never make items expire early.
        val age = nowMillis - entry.trashedAtMillis
        return age >= retentionDays * 86_400_000L
    }
}

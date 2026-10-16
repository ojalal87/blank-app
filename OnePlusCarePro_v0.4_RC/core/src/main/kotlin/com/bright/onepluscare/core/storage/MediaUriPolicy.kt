package com.bright.onepluscare.core.storage

/**
 * Only MediaStore item URIs (content://media/<volume>/<collection...>/<numeric id>) may be passed to
 * MediaStore.createTrashRequest or opened for hashing. Rejects other authorities, user-info, query
 * strings, fragments and encoded traversal so a tampered journal cannot point the app at another provider.
 */
object MediaUriPolicy {
    private val PATTERN = Regex("^content://media/[a-z0-9_-]{1,64}(/[a-z_]{1,32}){1,4}/[0-9]{1,19}$")

    fun isMediaStoreItem(uri: String?): Boolean = uri != null && uri.length < 256 && PATTERN.matches(uri)
}

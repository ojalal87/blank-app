package com.bright.onepluscare.core.duplicates

/**
 * Enforces "never remove every copy" in the domain layer, independent of what the UI allows.
 * Any selection that would empty a group has that group's keeper (or, if the keeper is not selected,
 * nothing) removed from the selection.
 */
object SelectionGuard {
    data class Sanitized(val ids: Set<String>, val protectedIds: Set<String>)

    fun sanitize(groups: List<DuplicateGroup>, selected: Set<String>): Sanitized {
        val allowed = LinkedHashSet<String>()
        val protectedIds = LinkedHashSet<String>()
        for (group in groups) {
            val ids = group.files.map { it.id }
            val chosen = ids.filter { it in selected }
            if (chosen.isEmpty()) continue
            if (chosen.size >= ids.size) {
                // Everything selected: keep the keeper.
                protectedIds += group.keeper.id
                allowed += chosen.filterNot { it == group.keeper.id }
            } else {
                allowed += chosen
            }
        }
        return Sanitized(allowed, protectedIds)
    }

    /** Default suggestion: every copy except the keeper. */
    fun suggest(groups: List<DuplicateGroup>): Set<String> =
        groups.flatMapTo(LinkedHashSet()) { g -> g.copies.map { it.id } }
}

package com.bright.onepluscare.core.security

/** Special access that lets an app watch or control the phone. */
enum class Surface { ACCESSIBILITY, NOTIFICATION_LISTENER, DEVICE_ADMIN }

data class WatchAlert(val kind: Kind, val packageName: String, val surface: Surface? = null, val level: ThreatLevel? = null) {
    enum class Kind { NEW_RISKY_APP, NEW_SPECIAL_ACCESS }
    /** Stable key so the same finding is announced once, not every hour. */
    val key: String get() = listOf(kind.name, packageName, surface?.name ?: "", level?.name ?: "").joinToString(":")
}

/**
 * Pure decisions for the background security watch: what changed since the last check and which changes
 * deserve a notification. The first run only records a baseline (nothing to compare with yet).
 */
object WatchRules {
    fun newSpecialAccess(previous: Map<Surface, Set<String>>?, current: Map<Surface, Set<String>>, selfPackage: String): List<WatchAlert> {
        if (previous == null) return emptyList()
        return current.flatMap { (surface, pkgs) ->
            (pkgs - previous[surface].orEmpty() - selfPackage).sorted().map { WatchAlert(WatchAlert.Kind.NEW_SPECIAL_ACCESS, it, surface = surface) }
        }
    }

    /** Only HIGH/CRITICAL findings interrupt the user; lower ones stay in the Security screen. */
    fun riskyChanged(assessments: List<AppAssessment>): List<WatchAlert> =
        assessments.filter { it.level >= ThreatLevel.HIGH }.map { WatchAlert(WatchAlert.Kind.NEW_RISKY_APP, it.facts.packageName, level = it.level) }

    /** Drops alerts already announced; [announced] is bounded by the caller. */
    fun fresh(alerts: List<WatchAlert>, announced: Set<String>): List<WatchAlert> = alerts.filter { it.key !in announced }.distinctBy { it.key }
}

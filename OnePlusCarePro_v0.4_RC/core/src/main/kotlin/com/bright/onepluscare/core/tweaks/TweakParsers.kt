package com.bright.onepluscare.core.tweaks

/** Parsers for the plain-text output of the allow-listed commands. All tolerate OEM noise. */
object TweakParsers {
    /** `settings get` prints "null" for unset keys. */
    fun settingValue(out: String): String? = out.trim().lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    /** `cmd appops get pkg OP` → "OP: allow; time=…" or "No operations." (= default). */
    fun appOpMode(out: String, op: String): String {
        val m = Regex("\\b${Regex.escape(op)}: ([a-z_]+)").find(out) ?: return "default"
        val mode = m.groupValues[1]
        // Modes outside the allow-list (e.g. "foreground") cannot be restored exactly; fall back to the default.
        return if (mode in AppOpsAllowList.MODES) mode else "default"
    }

    /** `cmd deviceidle whitelist` → lines "system-excidle,pkg,uid", "system,pkg,uid", "user,pkg,uid". */
    data class IdleWhitelist(val user: Set<String>, val system: Set<String>) {
        fun contains(pkg: String) = pkg in user || pkg in system
    }

    fun idleWhitelist(out: String): IdleWhitelist {
        val user = HashSet<String>()
        val system = HashSet<String>()
        out.lineSequence().forEach { line ->
            val parts = line.trim().split(',')
            if (parts.size < 2) return@forEach
            when {
                parts[0] == "user" -> user += parts[1]
                parts[0].startsWith("system") -> system += parts[1]
            }
        }
        return IdleWhitelist(user, system)
    }

    fun standbyBucket(out: String): Int? = out.trim().lineSequence().firstOrNull()?.trim()?.toIntOrNull()

    fun packageList(out: String): Set<String> = out.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("package:") }
        .map { it.removePrefix("package:").substringBefore(' ') }
        .filter(TweakCommands::isValidPackage)
        .toSet()

    data class Density(val physical: Int?, val override: Int?) {
        val effective: Int? get() = override ?: physical
    }

    fun density(out: String): Density {
        fun grab(label: String) = Regex("$label density: (\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
        return Density(grab("Physical"), grab("Override"))
    }
}

package com.bright.onepluscare.core.advanced

/**
 * The complete, closed set of things the Shizuku UserService may execute. The AIDL interface only
 * accepts an enum *name*; the argv is fixed here and never built from caller input, so there is no
 * argument or shell injection surface. No shell (`sh -c`) is ever used.
 */
enum class AdvancedDiagnostic(
    val argv: List<String>,
    val timeoutMs: Long,
    val maxLines: Int,
) {
    BATTERY(listOf("/system/bin/dumpsys", "battery"), 3_000L, 40),
    THERMAL(listOf("/system/bin/dumpsys", "thermalservice"), 3_000L, 80),
    DISK(listOf("/system/bin/df", "-h", "/data"), 2_000L, 12),
    ;

    companion object {
        /** Exact, case-sensitive match only. Anything else is refused. */
        fun fromWireName(name: String?): AdvancedDiagnostic? = entries.firstOrNull { it.name == name }
    }
}

object AdvancedAllowList {
    /** Read-only build properties. None of them is a stable per-device identifier (no serial, no IMEI). */
    val PROPERTIES: Set<String> = setOf(
        "ro.product.manufacturer",
        "ro.product.brand",
        "ro.product.model",
        "ro.build.version.release",
        "ro.build.version.sdk",
        "ro.build.version.security_patch",
        "ro.build.version.oplusrom",
        "ro.build.display.id",
        "ro.boot.verifiedbootstate",
        "ro.boot.flash.locked",
    )

    const val GETPROP = "/system/bin/getprop"

    fun isAllowedProperty(key: String?): Boolean = key != null && key in PROPERTIES

    fun propertyArgv(key: String): List<String>? = if (isAllowedProperty(key)) listOf(GETPROP, key) else null

    /** Hard caps applied to any service output before it crosses the Binder boundary. */
    const val MAX_OUTPUT_CHARS = 16_000
}

object OutputSanitizer {
    private val SERIAL = Regex("(?i)\\b(serial(no)?|imei|meid|iccid|android_id)\\s*[:=]\\s*\\S+")
    private val MAC = Regex("\\b([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\b")

    fun sanitize(raw: String, maxLines: Int): String {
        val lines = raw.lineSequence().take(maxLines).map { line ->
            MAC.replace(SERIAL.replace(line) { m -> m.groupValues[1] + ": <redacted>" }, "<mac>")
        }
        return lines.joinToString("\n").take(AdvancedAllowList.MAX_OUTPUT_CHARS)
    }
}

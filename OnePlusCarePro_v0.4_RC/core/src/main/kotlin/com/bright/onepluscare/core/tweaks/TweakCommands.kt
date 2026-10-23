package com.bright.onepluscare.core.tweaks

/**
 * Maps a validated [TweakOp] to a fixed argv. This runs inside the privileged Shizuku process and is the
 * enforcement point: it returns null (refuse) for any op whose fields are not allow-listed. argv is passed
 * to ProcessBuilder directly; no shell ever parses it.
 */
object TweakCommands {
    private const val CMD = "/system/bin/cmd"
    private const val SETTINGS = "/system/bin/settings"
    private const val PM = "/system/bin/pm"
    private const val WM = "/system/bin/wm"
    private const val DUMPSYS = "/system/bin/dumpsys"

    const val MIN_DPI = 120
    const val MAX_DPI = 960

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    fun isValidPackage(pkg: String): Boolean = pkg.length <= 200 && PACKAGE.matches(pkg)

    /**
     * @param selfPackage this app's package (never modifiable).
     */
    fun argvFor(op: TweakOp, selfPackage: String): List<String>? = when (op) {
        is TweakOp.SettingGet -> SettingsAllowList.find(op.ns, op.key)?.let { listOf(SETTINGS, "get", op.ns.cli, op.key) }
        is TweakOp.SettingPut -> SettingsAllowList.find(op.ns, op.key)
            ?.takeIf { it.rule.accepts(op.value) }
            ?.let { listOf(SETTINGS, "put", op.ns.cli, op.key, op.value) }
        is TweakOp.SettingDelete -> SettingsAllowList.find(op.ns, op.key)?.let { listOf(SETTINGS, "delete", op.ns.cli, op.key) }

        is TweakOp.AppOpGet -> if (pkgOk(op.pkg, selfPackage, allowSelf = true) && op.op in AppOpsAllowList.OPS) listOf(CMD, "appops", "get", op.pkg, op.op) else null
        is TweakOp.AppOpSet -> if (pkgOk(op.pkg, selfPackage) && op.op in AppOpsAllowList.OPS && op.mode in AppOpsAllowList.MODES) {
            listOf(CMD, "appops", "set", op.pkg, op.op, op.mode)
        } else null

        TweakOp.IdleWhitelistList -> listOf(CMD, "deviceidle", "whitelist")
        is TweakOp.IdleWhitelistAdd -> if (pkgOk(op.pkg, selfPackage, allowSelf = true)) listOf(CMD, "deviceidle", "whitelist", "+${op.pkg}") else null
        is TweakOp.IdleWhitelistRemove -> if (pkgOk(op.pkg, selfPackage, allowSelf = true)) listOf(CMD, "deviceidle", "whitelist", "-${op.pkg}") else null

        is TweakOp.StandbyGet -> if (pkgOk(op.pkg, selfPackage, allowSelf = true)) listOf(CMD, "activity", "get-standby-bucket", op.pkg) else null
        is TweakOp.StandbySet -> if (pkgOk(op.pkg, selfPackage) && op.bucket in StandbyBuckets.SETTABLE) listOf(CMD, "activity", "set-standby-bucket", op.pkg, op.bucket) else null

        is TweakOp.PackagesList -> when (op.filter) {
            PackageListFilter.SYSTEM -> listOf(PM, "list", "packages", "-s")
            PackageListFilter.DISABLED -> listOf(PM, "list", "packages", "-d")
            PackageListFilter.SYSTEM_INCLUDING_UNINSTALLED -> listOf(PM, "list", "packages", "-s", "-u")
        }
        // Removal is refused for protected packages here, in the privileged process, regardless of the UI.
        is TweakOp.PackageDisable -> if (removable(op.pkg, selfPackage)) listOf(PM, "disable-user", "--user", "0", op.pkg) else null
        is TweakOp.PackageUninstallUser -> if (removable(op.pkg, selfPackage)) listOf(PM, "uninstall", "-k", "--user", "0", op.pkg) else null
        is TweakOp.PackageEnable -> if (isValidPackage(op.pkg)) listOf(PM, "enable", "--user", "0", op.pkg) else null
        is TweakOp.PackageInstallExisting -> if (isValidPackage(op.pkg)) listOf(CMD, "package", "install-existing", "--user", "0", op.pkg) else null

        TweakOp.DensityGet -> listOf(WM, "density")
        is TweakOp.AppOpsDump -> listOf(DUMPSYS, "appops", "--op", op.op.opName)
        is TweakOp.DensitySet -> if (op.dpi in MIN_DPI..MAX_DPI) listOf(WM, "density", op.dpi.toString()) else null
        TweakOp.DensityReset -> listOf(WM, "density", "reset")
    }

    private fun pkgOk(pkg: String, self: String, allowSelf: Boolean = false): Boolean =
        isValidPackage(pkg) && (allowSelf || pkg != self) && pkg != SHIZUKU_PACKAGE

    private fun removable(pkg: String, self: String): Boolean =
        pkgOk(pkg, self) && DebloatClassifier.classify(pkg).risk != DebloatRisk.PROTECTED

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
}

/** Result of running one op in the service, returned as JSON over Binder. */
@kotlinx.serialization.Serializable
data class OpResult(val ok: Boolean, val exitCode: Int, val output: String, val refused: Boolean = false) {
    companion object {
        val REFUSED = OpResult(ok = false, exitCode = -1, output = "", refused = true)
        private val JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        fun encode(r: OpResult) = JSON.encodeToString(serializer(), r)
        fun decode(raw: String?): OpResult = raw?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: REFUSED
    }
}

/**
 * Some commands exit 0 while printing an error (pm, cmd). Treat known error markers as failure.
 */
object OutputChecks {
    private val ERROR_MARKERS = listOf("Exception", "Error:", "Failure", "Unknown command", "not found", "Security exception", "Bad argument")

    fun looksFailed(op: TweakOp, output: String): Boolean {
        if (op.readOnly) return false
        if (op is TweakOp.PackageUninstallUser) return !output.contains("Success")
        return ERROR_MARKERS.any { output.contains(it, ignoreCase = false) }
    }
}

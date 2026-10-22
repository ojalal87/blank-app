package com.bright.onepluscare.core.tweaks

enum class DebloatRisk {
    /** Never removable: boot, telephony, security, emergency alerts, app installation, keyboards, launchers. */
    PROTECTED,

    /** Curated: commonly preinstalled and safe to disable; nothing in the system depends on it. */
    RECOMMENDED,

    /** Curated: removable, but you lose a feature people often rely on (mail, maps, photos backup…). */
    CAUTION,

    /** Not in the curated list. Shown only in expert mode. */
    UNKNOWN,
}

enum class DebloatKind { PARTNER_STUB, PARTNER_APP, GOOGLE_OPTIONAL, ONEPLUS_OPTIONAL, SYSTEM_COMPONENT, OTHER }

data class DebloatInfo(val pkg: String, val risk: DebloatRisk, val kind: DebloatKind, val label: String?)

object DebloatClassifier {
    private val PROTECTED_EXACT = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.phone", "com.android.shell",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.bluetooth", "com.android.nfc", "com.android.server.telecom", "com.android.keychain",
        "com.android.externalstorage", "com.android.documentsui", "com.google.android.documentsui", "com.android.mtp",
        "com.android.webview", "com.google.android.webview", "com.google.android.trichromelibrary",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.android.emergency",
        "com.android.se", "com.android.location.fused", "com.android.sharedstoragebackup", "com.android.wallpaperbackup",
        "com.android.dynsystem", "com.android.certinstaller", "com.android.localtransport", "com.android.proxyhandler",
        "com.android.storagemanager", "com.android.htmlviewer", "com.android.inputdevices", "com.android.wifi.resources",
        "com.google.android.ext.services", "com.android.ext.services", "com.google.android.ext.shared",
        "com.google.android.modulemetadata", "com.android.modulemetadata", "com.google.android.apps.restore",
        TweakCommands.SHIZUKU_PACKAGE,
    )

    private val PROTECTED_PREFIXES = listOf(
        "com.android.providers.", "com.android.networkstack", "com.google.android.networkstack",
        "com.android.cellbroadcast", "com.google.android.cellbroadcast", "com.android.ims", "com.android.inputmethod",
        "com.google.android.inputmethod", "com.android.server.", "com.android.adservices", "com.google.android.adservices",
        "com.android.permission", "com.google.android.permission", "com.android.connectivity", "com.google.android.connectivity",
        "com.android.wifi", "com.google.android.wifi", "com.android.tethering", "com.google.android.tethering",
        "com.android.media", "com.google.android.media", "com.android.rkpdapp", "com.google.android.rkpdapp",
        "com.android.ondevicepersonalization", "com.google.android.ondevicepersonalization",
        "com.qualcomm.qti.", "com.qti.", "vendor.qti.", "org.codeaurora.ims",
    )

    /** Substrings that indicate a launcher, keyboard or telephony component on OEM builds. */
    private val PROTECTED_MARKERS = listOf(".launcher", "systemui", "telephony", ".ims", "keyboard", "inputmethod", ".modem", "radio", "emergency")

    private data class Curated(val risk: DebloatRisk, val kind: DebloatKind, val label: String)

    /** Only package names verified to be standalone apps. Anything else is UNKNOWN by design. */
    private val CURATED: Map<String, Curated> = mapOf(
        "com.facebook.appmanager" to Curated(DebloatRisk.RECOMMENDED, DebloatKind.PARTNER_STUB, "Facebook App Manager"),
        "com.facebook.services" to Curated(DebloatRisk.RECOMMENDED, DebloatKind.PARTNER_STUB, "Facebook Services"),
        "com.facebook.system" to Curated(DebloatRisk.RECOMMENDED, DebloatKind.PARTNER_STUB, "Facebook App Installer"),
        "com.netflix.partner.activation" to Curated(DebloatRisk.RECOMMENDED, DebloatKind.PARTNER_STUB, "Netflix partner activation"),
        "com.netflix.mediaclient" to Curated(DebloatRisk.RECOMMENDED, DebloatKind.PARTNER_APP, "Netflix"),
        "com.facebook.katana" to Curated(DebloatRisk.RECOMMENDED, DebloatKind.PARTNER_APP, "Facebook"),
        "com.instagram.android" to Curated(DebloatRisk.RECOMMENDED, DebloatKind.PARTNER_APP, "Instagram"),
        "com.linkedin.android" to Curated(DebloatRisk.RECOMMENDED, DebloatKind.PARTNER_APP, "LinkedIn"),
        "com.microsoft.skydrive" to Curated(DebloatRisk.RECOMMENDED, DebloatKind.PARTNER_APP, "OneDrive"),
        "com.amazon.mShop.android.shopping" to Curated(DebloatRisk.RECOMMENDED, DebloatKind.PARTNER_APP, "Amazon Shopping"),
        "com.amazon.appmanager" to Curated(DebloatRisk.RECOMMENDED, DebloatKind.PARTNER_STUB, "Amazon App Manager"),
        "com.oneplus.brickmode" to Curated(DebloatRisk.RECOMMENDED, DebloatKind.ONEPLUS_OPTIONAL, "Zen Mode"),
        "com.google.android.apps.youtube.music" to Curated(DebloatRisk.RECOMMENDED, DebloatKind.GOOGLE_OPTIONAL, "YouTube Music"),
        "com.google.android.videos" to Curated(DebloatRisk.RECOMMENDED, DebloatKind.GOOGLE_OPTIONAL, "Google TV"),
        "com.google.android.apps.podcasts" to Curated(DebloatRisk.RECOMMENDED, DebloatKind.GOOGLE_OPTIONAL, "Google Podcasts"),
        "com.google.android.apps.magazines" to Curated(DebloatRisk.RECOMMENDED, DebloatKind.GOOGLE_OPTIONAL, "Google News"),
        "com.google.android.apps.subscriptions.red" to Curated(DebloatRisk.RECOMMENDED, DebloatKind.GOOGLE_OPTIONAL, "Google One"),
        "com.google.android.apps.tachyon" to Curated(DebloatRisk.RECOMMENDED, DebloatKind.GOOGLE_OPTIONAL, "Google Meet"),
        "com.google.android.keep" to Curated(DebloatRisk.RECOMMENDED, DebloatKind.GOOGLE_OPTIONAL, "Google Keep"),
        "com.google.android.feedback" to Curated(DebloatRisk.RECOMMENDED, DebloatKind.GOOGLE_OPTIONAL, "Market Feedback Agent"),
        "com.google.android.apps.docs" to Curated(DebloatRisk.CAUTION, DebloatKind.GOOGLE_OPTIONAL, "Google Drive"),
        "com.google.android.youtube" to Curated(DebloatRisk.CAUTION, DebloatKind.GOOGLE_OPTIONAL, "YouTube"),
        "com.google.android.apps.photos" to Curated(DebloatRisk.CAUTION, DebloatKind.GOOGLE_OPTIONAL, "Google Photos"),
        "com.google.android.apps.maps" to Curated(DebloatRisk.CAUTION, DebloatKind.GOOGLE_OPTIONAL, "Google Maps"),
        "com.google.android.gm" to Curated(DebloatRisk.CAUTION, DebloatKind.GOOGLE_OPTIONAL, "Gmail"),
        "com.google.android.apps.wellbeing" to Curated(DebloatRisk.CAUTION, DebloatKind.GOOGLE_OPTIONAL, "Digital Wellbeing"),
        "com.google.android.googlequicksearchbox" to Curated(DebloatRisk.CAUTION, DebloatKind.GOOGLE_OPTIONAL, "Google app"),
        "com.google.ar.core" to Curated(DebloatRisk.CAUTION, DebloatKind.GOOGLE_OPTIONAL, "Google Play Services for AR"),
        "com.android.chrome" to Curated(DebloatRisk.CAUTION, DebloatKind.GOOGLE_OPTIONAL, "Chrome"),
    )

    fun classify(pkg: String): DebloatInfo {
        CURATED[pkg]?.let { return DebloatInfo(pkg, it.risk, it.kind, it.label) }
        val lower = pkg.lowercase()
        val protected = pkg in PROTECTED_EXACT ||
            PROTECTED_PREFIXES.any { pkg.startsWith(it) } ||
            PROTECTED_MARKERS.any { lower.contains(it) }
        if (protected) return DebloatInfo(pkg, DebloatRisk.PROTECTED, DebloatKind.SYSTEM_COMPONENT, null)
        val kind = when {
            pkg.startsWith("com.oneplus.") || pkg.startsWith("com.oplus.") || pkg.startsWith("com.coloros.") || pkg.startsWith("com.heytap.") -> DebloatKind.ONEPLUS_OPTIONAL
            pkg.startsWith("com.google.") -> DebloatKind.GOOGLE_OPTIONAL
            pkg.startsWith("com.android.") -> DebloatKind.SYSTEM_COMPONENT
            else -> DebloatKind.OTHER
        }
        return DebloatInfo(pkg, DebloatRisk.UNKNOWN, kind, null)
    }

    /** Extra protection known only at runtime (the current launcher and keyboard). */
    fun classify(pkg: String, dynamicProtected: Set<String>): DebloatInfo =
        if (pkg in dynamicProtected) DebloatInfo(pkg, DebloatRisk.PROTECTED, DebloatKind.SYSTEM_COMPONENT, null) else classify(pkg)
}

enum class PackageState { ENABLED, DISABLED, UNINSTALLED_FOR_USER }

data class SystemPackage(val info: DebloatInfo, val state: PackageState)

object PackageInventory {
    /**
     * Combines `pm list packages -s` (installed system packages, including disabled), `-d` (disabled) and
     * `-s -u` (system packages including those uninstalled for this user).
     */
    fun build(systemOut: String, disabledOut: String, systemAllOut: String, dynamicProtected: Set<String>): List<SystemPackage> {
        val installed = TweakParsers.packageList(systemOut)
        val disabled = TweakParsers.packageList(disabledOut)
        val uninstalled = TweakParsers.packageList(systemAllOut) - installed
        val result = ArrayList<SystemPackage>()
        installed.forEach { p ->
            result += SystemPackage(DebloatClassifier.classify(p, dynamicProtected), if (p in disabled) PackageState.DISABLED else PackageState.ENABLED)
        }
        uninstalled.forEach { p -> result += SystemPackage(DebloatClassifier.classify(p, dynamicProtected), PackageState.UNINSTALLED_FOR_USER) }
        return result.sortedWith(compareBy<SystemPackage>({ it.info.risk.ordinal }, { (it.info.label ?: it.info.pkg).lowercase() }))
    }
}

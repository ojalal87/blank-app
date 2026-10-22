package com.bright.onepluscare.core.security

/** Everything the scanner knows about one installed app. All of it comes from public Android APIs. */
data class AppSecurityFacts(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    /** InstallSourceInfo / getInstallerPackageName. null = unknown (often adb or a file manager). */
    val installer: String?,
    val grantedPermissions: Set<String>,
    val requestedPermissions: Set<String>,
    val hasLauncherIcon: Boolean,
    val targetSdk: Int,
    val debuggable: Boolean,
    val certSha1s: List<String>,
    val accessibilityServiceEnabled: Boolean,
    val notificationListenerEnabled: Boolean,
    val deviceAdminActive: Boolean,
    val isDefaultSms: Boolean,
    val isVpnApp: Boolean,
    val firstInstallMillis: Long?,
)

enum class ThreatLevel { NONE, LOW, MEDIUM, HIGH, CRITICAL }

enum class FindingId {
    KNOWN_STALKERWARE,
    BANKING_TROJAN_PATTERN,
    HIDDEN_WITH_SURVEILLANCE,
    ACCESSIBILITY_CONTROL,
    READS_NOTIFICATIONS,
    DEVICE_ADMIN,
    SIDELOADED_SMS_ACCESS,
    BACKGROUND_SURVEILLANCE,
    LEGACY_PERMISSION_MODEL,
    SIDELOADED,
    CAN_INSTALL_APPS,
    DEBUGGABLE_BUILD,
}

data class SecurityFinding(val id: FindingId, val level: ThreatLevel, val detail: String? = null)

data class AppAssessment(val facts: AppSecurityFacts, val level: ThreatLevel, val findings: List<SecurityFinding>, val stalkerwareFamily: String?)

data class ScanSummary(
    val scannedApps: Int,
    val assessments: List<AppAssessment>,
    val iocFamilies: Int,
    val accessibilityApps: List<AppAssessment>,
    val notificationListenerApps: List<AppAssessment>,
    val deviceAdminApps: List<AppAssessment>,
) {
    val flagged: List<AppAssessment> get() = assessments.filter { it.level >= ThreatLevel.MEDIUM }
    val worst: ThreatLevel get() = assessments.maxOfOrNull { it.level } ?: ThreatLevel.NONE
}

/**
 * Explainable heuristic scanner. It does NOT claim to detect all malware (no app can inspect other apps'
 * code or data on Android); it highlights capabilities that malware and stalkerware need, plus exact
 * matches against a public stalkerware indicator list. Google Play Protect remains the platform scanner.
 */
object AppRiskScanner {
    val TRUSTED_INSTALLERS = setOf(
        "com.android.vending", "com.google.android.feedback",
        "com.heytap.market", "com.oppo.market", "com.oneplus.market",
        "com.sec.android.app.samsungapps", "com.huawei.appmarket", "com.xiaomi.market", "com.xiaomi.mipicks",
        "com.amazon.venezia", "org.fdroid.fdroid", "com.aurora.store",
    )

    private const val P = "android.permission."
    private val SMS = setOf("${P}READ_SMS", "${P}RECEIVE_SMS", "${P}SEND_SMS")
    private val SURVEILLANCE = setOf("${P}RECORD_AUDIO", "${P}ACCESS_BACKGROUND_LOCATION", "${P}READ_CALL_LOG", "${P}READ_SMS", "${P}CAMERA", "${P}READ_CONTACTS")

    fun assess(f: AppSecurityFacts, ioc: IocDatabase, selfPackage: String): AppAssessment {
        val out = ArrayList<SecurityFinding>()
        val sideloaded = !f.isSystem && (f.installer == null || f.installer !in TRUSTED_INSTALLERS)
        val overlay = "${P}SYSTEM_ALERT_WINDOW" in f.requestedPermissions
        val hasSms = f.grantedPermissions.any { it in SMS } || f.isDefaultSms
        val surveillance = f.grantedPermissions.count { it in SURVEILLANCE }

        val family = if (f.isSystem) null else ioc.match(f.packageName, f.certSha1s)?.name
        if (family != null) out += SecurityFinding(FindingId.KNOWN_STALKERWARE, ThreatLevel.CRITICAL, family)

        if (!f.isSystem) {
            if (f.accessibilityServiceEnabled && hasSms && overlay) out += SecurityFinding(FindingId.BANKING_TROJAN_PATTERN, ThreatLevel.HIGH)
            if (!f.hasLauncherIcon && (surveillance >= 2 || f.accessibilityServiceEnabled || f.notificationListenerEnabled || f.deviceAdminActive)) {
                out += SecurityFinding(FindingId.HIDDEN_WITH_SURVEILLANCE, ThreatLevel.HIGH)
            }
            if (f.accessibilityServiceEnabled) out += SecurityFinding(FindingId.ACCESSIBILITY_CONTROL, if (sideloaded) ThreatLevel.MEDIUM else ThreatLevel.LOW)
            if (f.notificationListenerEnabled) out += SecurityFinding(FindingId.READS_NOTIFICATIONS, if (sideloaded) ThreatLevel.MEDIUM else ThreatLevel.LOW)
            if (f.deviceAdminActive) out += SecurityFinding(FindingId.DEVICE_ADMIN, if (sideloaded) ThreatLevel.MEDIUM else ThreatLevel.LOW)
            if (sideloaded && hasSms && !f.isDefaultSms) out += SecurityFinding(FindingId.SIDELOADED_SMS_ACCESS, ThreatLevel.MEDIUM)
            if (sideloaded && surveillance >= 3) out += SecurityFinding(FindingId.BACKGROUND_SURVEILLANCE, ThreatLevel.MEDIUM)
            if (f.targetSdk in 1..22) out += SecurityFinding(FindingId.LEGACY_PERMISSION_MODEL, ThreatLevel.MEDIUM)
            if (sideloaded) out += SecurityFinding(FindingId.SIDELOADED, ThreatLevel.LOW, f.installer)
            if (sideloaded && "${P}REQUEST_INSTALL_PACKAGES" in f.requestedPermissions) out += SecurityFinding(FindingId.CAN_INSTALL_APPS, ThreatLevel.LOW)
            if (f.debuggable && f.packageName != selfPackage) out += SecurityFinding(FindingId.DEBUGGABLE_BUILD, ThreatLevel.LOW)
        }
        val level = out.maxOfOrNull { it.level } ?: ThreatLevel.NONE
        return AppAssessment(f, level, out.sortedByDescending { it.level }, family)
    }

    fun scan(apps: List<AppSecurityFacts>, ioc: IocDatabase, selfPackage: String): ScanSummary {
        val assessed = apps.filter { it.packageName != selfPackage }.map { assess(it, ioc, selfPackage) }
            .sortedWith(compareByDescending<AppAssessment> { it.level }.thenBy { it.facts.label.lowercase() })
        return ScanSummary(
            scannedApps = assessed.size,
            assessments = assessed,
            iocFamilies = ioc.entries.size,
            accessibilityApps = assessed.filter { it.facts.accessibilityServiceEnabled },
            notificationListenerApps = assessed.filter { it.facts.notificationListenerEnabled },
            deviceAdminApps = assessed.filter { it.facts.deviceAdminActive },
        )
    }
}

/** Per-app internet blocking list for the local-VPN firewall. Pure so it can be validated and tested. */
object FirewallRules {
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /**
     * The VPN routes ONLY the listed apps into a tunnel that drops everything. An empty list must never
     * start the VPN, because VpnService treats "no allowed apps" as "all apps" — that would cut off the phone.
     */
    fun sanitize(blocked: Collection<String>, selfPackage: String): Set<String> =
        blocked.filter { PACKAGE.matches(it) && it != selfPackage && it.length <= 200 }.toSortedSet()

    fun canStart(blocked: Set<String>): Boolean = blocked.isNotEmpty()
}

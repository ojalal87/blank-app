package com.bright.onepluscare.data

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import com.bright.onepluscare.core.io.AtomicTextFile
import com.bright.onepluscare.core.security.AppRiskScanner
import com.bright.onepluscare.core.security.AppSecurityFacts
import com.bright.onepluscare.core.security.IocDatabase
import com.bright.onepluscare.core.security.ScanSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

enum class IocSource { BUNDLED, DOWNLOADED }

data class IocInfo(val families: Int, val packages: Int, val certificates: Int, val source: IocSource, val updatedAtMillis: Long?)

enum class IocUpdateResult { UPDATED, NETWORK_ERROR, INVALID }

/** An app that can be blocked by the firewall: user-installed or updated, and holding INTERNET. */
data class NetApp(val packageName: String, val label: String, val isSystem: Boolean)

/**
 * Collects public package facts for [AppRiskScanner] and manages the stalkerware indicator list. The list
 * ships in the APK; it is downloaded again only when the user taps "Update" (no background network use).
 */
class SecurityRepository(private val context: Context) {
    private val pm: PackageManager = context.packageManager
    private val iocFile = File(context.filesDir, "stalkerware_ioc.yaml")
    @Volatile private var cached: Pair<IocDatabase, IocInfo>? = null

    suspend fun ioc(): Pair<IocDatabase, IocInfo> = withContext(Dispatchers.IO) {
        cached ?: loadIoc().also { cached = it }
    }

    private fun loadIoc(): Pair<IocDatabase, IocInfo> {
        val downloaded = runCatching { AtomicTextFile(iocFile.toPath()).read() }.getOrNull()
            ?.let { IocDatabase.parse(it) }
            ?.takeIf { it.entries.size >= IocDatabase.MIN_ENTRIES_FOR_UPDATE }
        if (downloaded != null) return downloaded to info(downloaded, IocSource.DOWNLOADED, iocFile.lastModified())
        val bundled = runCatching { context.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            ?.let { IocDatabase.parse(it) } ?: IocDatabase.EMPTY
        return bundled to info(bundled, IocSource.BUNDLED, null)
    }

    private fun info(db: IocDatabase, src: IocSource, at: Long?) = IocInfo(db.entries.size, db.packageCount, db.certificateCount, src, at)

    /** User-initiated only. HTTPS with system CAs (network_security_config), size-capped, validated before it replaces anything. */
    suspend fun updateIoc(): IocUpdateResult = withContext(Dispatchers.IO) {
        val text = try {
            val net = com.bright.onepluscare.firewall.AppNetwork.forOwnTraffic(context)
            val conn = (net?.openConnection(URL(IOC_URL)) ?: URL(IOC_URL).openConnection()) as HttpsURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "text/plain")
            try {
                if (conn.responseCode != 200) return@withContext IocUpdateResult.NETWORK_ERROR
                conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > IocDatabase.MAX_BYTES) return@withContext IocUpdateResult.INVALID
                    }
                    out.toByteArray().toString(Charsets.UTF_8)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext IocUpdateResult.NETWORK_ERROR
        }
        val db = IocDatabase.parse(text)
        if (db.entries.size < IocDatabase.MIN_ENTRIES_FOR_UPDATE) return@withContext IocUpdateResult.INVALID
        AtomicTextFile(iocFile.toPath()).write(text)
        cached = db to info(db, IocSource.DOWNLOADED, System.currentTimeMillis())
        IocUpdateResult.UPDATED
    }

    suspend fun scan(onProgress: (Int, Int) -> Unit = { _, _ -> }): ScanSummary = withContext(Dispatchers.IO) {
        val (db, _) = ioc()
        AppRiskScanner.scan(collectFacts(onProgress), db, context.packageName)
    }

    @Suppress("DEPRECATION")
    private suspend fun collectFacts(onProgress: (Int, Int) -> Unit): List<AppSecurityFacts> {
        val flags = PackageManager.GET_PERMISSIONS or if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val packages: List<PackageInfo> = runCatching { pm.getInstalledPackages(flags) }.getOrDefault(emptyList())
        val launchable = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).mapTo(HashSet()) { it.activityInfo.packageName }
        }.getOrDefault(emptySet())
        val a11y = enabledComponents(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val listeners = enabledComponents("enabled_notification_listeners")
        val admins = runCatching {
            context.getSystemService(DevicePolicyManager::class.java)?.activeAdmins.orEmpty().mapTo(HashSet()) { it.packageName }
        }.getOrDefault(emptySet())
        val sms = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
        val vpnApps = runCatching {
            pm.queryIntentServices(Intent("android.net.VpnService"), 0).mapTo(HashSet()) { it.serviceInfo.packageName }
        }.getOrDefault(emptySet())

        val out = ArrayList<AppSecurityFacts>(packages.size)
        packages.forEachIndexed { i, p ->
            currentCoroutineContext().ensureActive()
            if (i % 20 == 0) onProgress(i, packages.size)
            val ai = p.applicationInfo ?: return@forEachIndexed
            val requested = p.requestedPermissions?.toSet().orEmpty()
            val granted = p.requestedPermissions?.filterIndexed { idx, _ ->
                (p.requestedPermissionsFlags?.getOrNull(idx) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            }?.toSet().orEmpty()
            out += AppSecurityFacts(
                packageName = p.packageName,
                label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(p.packageName),
                isSystem = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                installer = installer(p.packageName),
                grantedPermissions = granted,
                requestedPermissions = requested,
                hasLauncherIcon = p.packageName in launchable,
                targetSdk = ai.targetSdkVersion,
                debuggable = ai.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                certSha1s = certSha1s(p),
                accessibilityServiceEnabled = p.packageName in a11y,
                notificationListenerEnabled = p.packageName in listeners,
                deviceAdminActive = p.packageName in admins,
                isDefaultSms = p.packageName == sms,
                isVpnApp = p.packageName in vpnApps,
                firstInstallMillis = p.firstInstallTime.takeIf { it > 0 },
            )
        }
        onProgress(packages.size, packages.size)
        return out
    }

    /** Apps the firewall can block, sorted by label. */
    suspend fun internetApps(): List<NetApp> = withContext(Dispatchers.IO) {
        val packages = runCatching { pm.getInstalledPackages(PackageManager.GET_PERMISSIONS) }.getOrDefault(emptyList())
        packages.mapNotNull { p ->
            val ai = p.applicationInfo ?: return@mapNotNull null
            if (p.packageName == context.packageName) return@mapNotNull null
            if (p.requestedPermissions?.contains(android.Manifest.permission.INTERNET) != true) return@mapNotNull null
            val system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
            NetApp(p.packageName, runCatching { ai.loadLabel(pm).toString() }.getOrDefault(p.packageName), system)
        }.sortedBy { it.label.lowercase() }
    }

    private fun enabledComponents(key: String): Set<String> = runCatching {
        Settings.Secure.getString(context.contentResolver, key).orEmpty()
            .split(':').mapNotNull { it.substringBefore('/').trim().takeIf { s -> s.isNotEmpty() } }.toSet()
    }.getOrDefault(emptySet())

    @Suppress("DEPRECATION")
    private fun installer(pkg: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName else pm.getInstallerPackageName(pkg)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun certSha1s(p: PackageInfo): List<String> = runCatching {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = p.signingInfo ?: return@runCatching emptyList<String>()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            p.signatures
        }
        sigs.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString("") { b -> "%02X".format(b) }
        }
    }.getOrDefault(emptyList())

    companion object {
        const val ASSET = "stalkerware_ioc.yaml"
        const val IOC_URL = "https://raw.githubusercontent.com/AssoEchap/stalkerware-indicators/master/ioc.yaml"
    }
}

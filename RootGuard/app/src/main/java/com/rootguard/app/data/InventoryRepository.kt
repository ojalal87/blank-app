package com.rootguard.app.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserManager
import androidx.room.withTransaction
import com.rootguard.app.data.db.AppInstallationEntity
import com.rootguard.app.data.db.AppVersionEntity
import com.rootguard.app.data.db.ControlDatabase
import com.rootguard.app.data.db.PrincipalEntity
import com.rootguard.app.data.db.PrincipalMemberEntity
import com.rootguard.core.model.Attribution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

/** What PackageManager reports for one installed package. Pure data so reconciliation is testable. */
data class InstalledPackage(
    val packageName: String,
    val label: String,
    val uid: Int,
    val firstInstallMs: Long,
    val versionCode: Long,
    val signerLineageSha256: String,
    val signerSha256: String,
    val isSystem: Boolean,
    val hasInternet: Boolean,
    val sharedUserId: String?,
)

/** A principal (UID assignment) with its current member packages, for the UI and the compiler. */
data class PrincipalView(
    val principalId: String,
    val uid: Int,
    val attribution: Attribution,
    val packages: List<InstalledPackage>,
) {
    val label: String get() = packages.minByOrNull { it.label.lowercase() }?.label ?: "UID $uid"
    val isSystem: Boolean get() = packages.all { it.isSystem }
    val usesInternet: Boolean get() = packages.any { it.hasInternet }
}

/**
 * Keeps app_installation / principal / principal_member in step with PackageManager.
 * - A package reinstall is a new installation generation (new app_id).
 * - A UID whose previous members are all gone is a NEW principal: rules never carry over to a reused UID.
 * - Several packages on one UID make the principal SHARED; the UI warns that rules affect all of them.
 */
class InventoryRepository(private val context: Context, private val db: ControlDatabase) {
    private val pm: PackageManager = context.packageManager

    val userSerial: Long by lazy { context.getSystemService(UserManager::class.java)?.getSerialNumberForUser(Process.myUserHandle()) ?: 0L }
    val androidUserId: Int get() = Process.myUid() / 100000

    @Volatile var lastPackages: Map<String, InstalledPackage> = emptyMap()
        private set

    suspend fun sync(nowMs: Long = System.currentTimeMillis()): List<PrincipalView> = withContext(Dispatchers.IO) {
        val installed = readPackages()
        lastPackages = installed.associateBy { it.packageName }
        reconcile(installed, nowMs)
    }

    suspend fun reconcile(installed: List<InstalledPackage>, nowMs: Long): List<PrincipalView> = db.withTransaction {
        val dao = db.inventory()
        val user = userSerial
        val generation = { p: InstalledPackage -> "${p.firstInstallMs}:${p.signerLineageSha256.take(16)}" }

        // 1. installations
        val active = dao.activeInstallations(user).associateBy { it.packageName }
        val byPkg = installed.associateBy { it.packageName }
        for ((pkg, inst) in active) {
            val now = byPkg[pkg]
            if (now == null || generation(now) != inst.installGeneration) {
                dao.markRemoved(inst.appId, nowMs.coerceAtLeast(inst.installedMs))
                dao.closeMembership(inst.appId, nowMs)
            }
        }
        val appIds = HashMap<String, String>()
        for (p in installed) {
            val existing = active[p.packageName]?.takeIf { it.installGeneration == generation(p) }
            val id = existing?.appId ?: UUID.randomUUID().toString().also {
                dao.insertInstallation(AppInstallationEntity(it, user, p.packageName, generation(p), p.signerLineageSha256, p.firstInstallMs.coerceAtMost(nowMs), null))
            }
            appIds[p.packageName] = id
            dao.insertVersion(AppVersionEntity(id, p.versionCode, nowMs, p.signerSha256, "[]"))
        }

        // 2. principals
        val membersByPrincipal = dao.activeMembers().groupBy { it.principalId }
        val principals = dao.activePrincipals(user)
        val byUid = installed.groupBy { it.uid }
        val keep = HashMap<Int, PrincipalEntity>()
        for (pr in principals) {
            val currentApps = byUid[pr.androidUid].orEmpty().mapNotNull { appIds[it.packageName] }.toSet()
            val memberApps = membersByPrincipal[pr.principalId].orEmpty().map { it.appId }.toSet()
            if (currentApps.isEmpty() || memberApps.intersect(currentApps).isEmpty()) {
                dao.closeMembers(pr.principalId, nowMs)
                dao.closePrincipal(pr.principalId, nowMs.coerceAtLeast(pr.validFromMs))
            } else {
                keep[pr.androidUid] = pr
            }
        }
        val activeMemberApps = dao.activeMembers().groupBy { it.principalId }
        for ((uid, pkgs) in byUid) {
            val attribution = if (pkgs.size > 1 || pkgs.any { it.sharedUserId != null }) Attribution.SHARED else Attribution.EXACT
            val pr = keep[uid] ?: PrincipalEntity(UUID.randomUUID().toString(), user, uid, nowMs, null, attribution).also { dao.insertPrincipal(it) }
            if (pr.attribution != attribution) dao.setAttribution(pr.principalId, attribution)
            val have = activeMemberApps[pr.principalId].orEmpty().map { it.appId }.toSet()
            for (p in pkgs) {
                val appId = appIds.getValue(p.packageName)
                if (appId !in have) dao.insertMember(PrincipalMemberEntity(pr.principalId, appId, nowMs, null))
            }
        }
        views(installed)
    }

    private suspend fun views(installed: List<InstalledPackage>): List<PrincipalView> {
        val dao = db.inventory()
        val inst = dao.activeInstallations(userSerial).associateBy { it.appId }
        val byPkg = installed.associateBy { it.packageName }
        val members = dao.activeMembers().groupBy { it.principalId }
        return dao.activePrincipals(userSerial).map { pr ->
            PrincipalView(pr.principalId, pr.androidUid, pr.attribution, members[pr.principalId].orEmpty().mapNotNull { m -> inst[m.appId]?.let { byPkg[it.packageName] } })
        }.filter { it.packages.isNotEmpty() }.sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
    }

    @Suppress("DEPRECATION")
    private fun readPackages(): List<InstalledPackage> {
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES
        val list: List<PackageInfo> = runCatching { pm.getInstalledPackages(flags) }.getOrDefault(emptyList())
        return list.mapNotNull { p ->
            val ai = p.applicationInfo ?: return@mapNotNull null
            val certs = runCatching {
                val si = p.signingInfo
                when {
                    si == null -> emptyList()
                    si.hasMultipleSigners() -> si.apkContentsSigners.toList()
                    else -> si.signingCertificateHistory.toList()
                }
            }.getOrDefault(emptyList()).map { sha256(it.toByteArray()) }
            InstalledPackage(
                packageName = p.packageName,
                label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(p.packageName),
                uid = ai.uid,
                firstInstallMs = p.firstInstallTime,
                versionCode = p.longVersionCode,
                signerLineageSha256 = sha256(certs.joinToString(",").toByteArray()),
                signerSha256 = certs.lastOrNull() ?: "",
                isSystem = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                hasInternet = p.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true,
                sharedUserId = p.sharedUserId,
            )
        }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}

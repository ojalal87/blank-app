package com.bright.onepluscare.data

import android.content.Context
import com.bright.onepluscare.core.privacy.AppOpsHistory
import com.bright.onepluscare.core.privacy.OpAccess
import com.bright.onepluscare.core.privacy.PrivacyOp
import com.bright.onepluscare.core.tweaks.TweakOp
import com.bright.onepluscare.shizuku.ShizukuGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Last recorded camera / microphone / location use per app. Location = the more recent of fine and coarse. */
data class AccessSummary(
    val packageName: String,
    val label: String,
    val camera: OpAccess?,
    val microphone: OpAccess?,
    val location: OpAccess?,
) {
    val mostRecentAgoMs: Long get() = listOfNotNull(camera, microphone, location).minOf { it.agoMs }
    val anyBackground: Boolean get() = listOfNotNull(camera, microphone, location).any { it.anyBackground }
}

/**
 * Reads Android's own app-ops record through Shizuku (`dumpsys appops --op`, parsed inside the Shizuku
 * process). Read-only; nothing is stored. Without Shizuku the screen points to Android's privacy dashboard.
 */
class PrivacyAccessRepository(private val context: Context, private val shizuku: ShizukuGateway) {
    suspend fun load(): List<AccessSummary>? = withContext(Dispatchers.IO) {
        val byOp = HashMap<PrivacyOp, List<OpAccess>>()
        for (op in PrivacyOp.entries) {
            val r = shizuku.runOp(TweakOp.AppOpsDump(op))
            if (!r.ok && r.exitCode <= -10) return@withContext null // Shizuku unavailable
            byOp[op] = AppOpsHistory.decode(r.output).orEmpty()
        }
        val pkgs = byOp.values.flatten().map { it.packageName }.toSet() - context.packageName
        val pm = context.packageManager
        pkgs.map { pkg ->
            fun of(op: PrivacyOp) = byOp[op].orEmpty().firstOrNull { it.packageName == pkg }
            val fine = of(PrivacyOp.FINE_LOCATION)
            val coarse = of(PrivacyOp.COARSE_LOCATION)
            val loc = listOfNotNull(fine, coarse).minByOrNull { it.agoMs }?.let { best ->
                best.copy(anyBackground = (fine?.anyBackground == true) || (coarse?.anyBackground == true))
            }
            val label = runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault(pkg)
            AccessSummary(pkg, label, of(PrivacyOp.CAMERA), of(PrivacyOp.MICROPHONE), loc)
        }.sortedBy { it.mostRecentAgoMs }
    }
}

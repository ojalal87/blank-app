package com.bright.onepluscare.ui.vm

import android.app.KeyguardManager
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bright.onepluscare.AppGraph
import com.bright.onepluscare.core.apps.AppEvaluator
import com.bright.onepluscare.core.apps.AppFlag
import com.bright.onepluscare.core.apps.AppRecord
import com.bright.onepluscare.core.apps.SensitivePermission
import com.bright.onepluscare.core.rules.RecommendationEngine
import com.bright.onepluscare.data.SystemIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class DeviceSecurity(
    val screenLockSecure: Boolean?,
    val developerOptions: Boolean?,
    val usbDebugging: Boolean?,
    val securityPatch: String?,
    val patchAgeDays: Long?,
)

data class PrivacyUiState(
    val security: DeviceSecurity? = null,
    val usageAccess: Boolean = false,
    val appsLoaded: Boolean = false,
    /** Apps holding each sensitive permission (granted), user apps only. */
    val byPermission: Map<SensitivePermission, List<AppRecord>> = emptyMap(),
    val unusedWithSensitive: List<AppRecord> = emptyList(),
)

/** Read-only privacy review. Settings.Global values are public, read-only and need no permission. */
class PrivacyViewModel(private val g: AppGraph) : ViewModel() {
    private val security = flow {
        val ctx = g.appContext
        val km = ctx.getSystemService(KeyguardManager::class.java)
        val patch = android.os.Build.VERSION.SECURITY_PATCH?.takeIf { it.isNotBlank() }
        emit(
            DeviceSecurity(
                screenLockSecure = km?.isDeviceSecure,
                developerOptions = runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }.getOrNull(),
                usbDebugging = runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrNull(),
                securityPatch = patch,
                patchAgeDays = RecommendationEngine.patchAgeDays(patch, System.currentTimeMillis()),
            ),
        )
    }

    val state: StateFlow<PrivacyUiState> = combine(security, g.results, g.capabilities) { sec, results, caps ->
        val apps = results.apps?.apps.orEmpty().filter { !it.isSystem }
        val now = System.currentTimeMillis()
        PrivacyUiState(
            security = sec,
            usageAccess = caps.usageAccess,
            appsLoaded = results.apps != null,
            byPermission = SensitivePermission.entries.associateWith { p -> apps.filter { p in it.sensitivePermissions }.sortedBy { it.label } }.filterValues { it.isNotEmpty() },
            unusedWithSensitive = results.apps?.let { inv -> apps.filter { AppFlag.SENSITIVE_AND_UNUSED in AppEvaluator.flags(it, now, inv.windowDays) } }.orEmpty(),
        )
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrivacyUiState())

    fun open(target: SystemIntents.Target) = g.intents.open(target)
    fun openApp(pkg: String) = g.intents.openAppDetails(pkg)
}

package com.bright.onepluscare.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bright.onepluscare.R
import com.bright.onepluscare.core.storage.StorageAccessMode
import com.bright.onepluscare.data.Capabilities
import com.bright.onepluscare.data.SystemIntents
import com.bright.onepluscare.ui.Labels
import com.bright.onepluscare.ui.components.CareCard
import com.bright.onepluscare.ui.components.ScreenScaffold
import com.bright.onepluscare.ui.components.SectionHeader
import com.bright.onepluscare.ui.components.StatusPill
import com.bright.onepluscare.ui.components.Tone
import com.bright.onepluscare.ui.nav.Navigator
import com.bright.onepluscare.ui.vm.LocalAppGraph
import com.bright.onepluscare.ui.vm.SettingsViewModel
import com.bright.onepluscare.ui.vm.careViewModel

/** Callbacks for granting each optional capability. Shared by onboarding and the Permission Center. */
class PermissionActions(
    val requestMedia: () -> Unit,
    val openAllFiles: () -> Unit,
    val openUsageAccess: () -> Unit,
    val requestNotifications: () -> Unit,
)

@Composable
fun rememberPermissionActions(): PermissionActions {
    val vm: SettingsViewModel = careViewModel()
    val graph = LocalAppGraph.current
    val media = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshCapabilities() }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshCapabilities() }
    return PermissionActions(
        requestMedia = { media.launch(graph.storage.mediaPermissions()) },
        openAllFiles = { vm.open(SystemIntents.Target.APP_ALL_FILES_ACCESS) },
        openUsageAccess = { vm.open(SystemIntents.Target.USAGE_ACCESS) },
        requestNotifications = {
            if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.open(SystemIntents.Target.APP_NOTIFICATIONS)
        },
    )
}

@Composable
fun PermissionCenterScreen(nav: Navigator, padding: PaddingValues) {
    val vm: SettingsViewModel = careViewModel()
    val caps by vm.capabilities.collectAsStateWithLifecycle()
    val actions = rememberPermissionActions()
    ScreenScaffold(title = stringResource(R.string.screen_permissions), outerPadding = padding, onBack = nav.back) {
        permissionItems(caps, actions)
        item { Text(stringResource(R.string.permissions_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

fun LazyListScope.permissionItems(caps: Capabilities, actions: PermissionActions) {
    item { SectionHeader(stringResource(R.string.perm_section_storage)) }
    item {
        PermissionCard(
            title = stringResource(R.string.perm_media_title),
            body = stringResource(R.string.perm_media_body),
            granted = caps.storageMode != StorageAccessMode.NONE,
            status = stringResource(Labels.storageMode(caps.storageMode)),
            actionLabel = stringResource(R.string.action_grant),
            onAction = actions.requestMedia,
        )
    }
    if (caps.sdkInt >= 30) {
        item {
            PermissionCard(
                title = stringResource(R.string.perm_all_files_title),
                body = stringResource(R.string.perm_all_files_body),
                granted = caps.storageMode == StorageAccessMode.FILESYSTEM,
                status = null,
                actionLabel = stringResource(R.string.action_open_settings),
                onAction = actions.openAllFiles,
            )
        }
    }
    item { SectionHeader(stringResource(R.string.perm_section_apps)) }
    item {
        PermissionCard(
            title = stringResource(R.string.perm_usage_title),
            body = stringResource(R.string.perm_usage_body),
            granted = caps.usageAccess,
            status = null,
            actionLabel = stringResource(R.string.action_open_settings),
            onAction = actions.openUsageAccess,
        )
    }
    item { SectionHeader(stringResource(R.string.perm_section_notifications)) }
    item {
        PermissionCard(
            title = stringResource(R.string.perm_notifications_title),
            body = stringResource(R.string.perm_notifications_body),
            granted = caps.notificationsAllowed,
            status = null,
            actionLabel = stringResource(R.string.action_grant),
            onAction = actions.requestNotifications,
        )
    }
}

@Composable
private fun PermissionCard(title: String, body: String, granted: Boolean, status: String?, actionLabel: String, onAction: () -> Unit) {
    CareCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                StatusPill(if (granted) Tone.HEALTHY else Tone.NEUTRAL, stringResource(if (granted) R.string.perm_granted else R.string.perm_not_granted))
            }
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status != null) Text(status, style = MaterialTheme.typography.labelMedium)
            if (!granted) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

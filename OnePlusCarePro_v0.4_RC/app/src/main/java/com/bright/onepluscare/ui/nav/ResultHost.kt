package com.bright.onepluscare.ui.nav

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bright.onepluscare.ui.vm.ScanViewModel
import com.bright.onepluscare.ui.vm.SecurityViewModel
import com.bright.onepluscare.ui.vm.MalwareScanViewModel
import com.bright.onepluscare.ui.vm.SettingsViewModel
import com.bright.onepluscare.ui.vm.TrashViewModel
import com.bright.onepluscare.ui.vm.careViewModel

/**
 * The single place where system dialogs are launched (MediaStore trash/restore confirmation, the
 * export file picker and the VPN consent for the firewall). Composed exactly once, so transitions between screens can never launch a dialog
 * twice, and each pending request is marked as launched so rotation does not re-open it.
 */
@Composable
fun ActivityResultHost() {
    val scanVm: ScanViewModel = careViewModel()
    val trashVm: TrashViewModel = careViewModel()
    val settingsVm: SettingsViewModel = careViewModel()

    val scan by scanVm.state.collectAsStateWithLifecycle()
    val trash by trashVm.state.collectAsStateWithLifecycle()
    val export by settingsVm.export.collectAsStateWithLifecycle()

    val pendingTrash by rememberUpdatedState(scan.pendingSystemTrash)
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        pendingTrash?.let { scanVm.onSystemTrashResult(it.confirmation, result.resultCode == Activity.RESULT_OK) }
    }
    LaunchedEffect(scan.pendingSystemTrash) {
        val p = scan.pendingSystemTrash ?: return@LaunchedEffect
        if (!p.launched) {
            scanVm.systemTrashLaunched()
            trashLauncher.launch(IntentSenderRequest.Builder(p.sender).build())
        }
    }

    val pendingRestore by rememberUpdatedState(trash.pendingRestore)
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        pendingRestore?.let { trashVm.onMediaRestoreResult(it.entryId, result.resultCode == Activity.RESULT_OK) }
    }
    LaunchedEffect(trash.pendingRestore) {
        val p = trash.pendingRestore ?: return@LaunchedEffect
        if (!p.launched) {
            trashVm.restoreLaunched()
            restoreLauncher.launch(IntentSenderRequest.Builder(p.sender).build())
        }
    }

    val securityVm: SecurityViewModel = careViewModel()
    val security by securityVm.state.collectAsStateWithLifecycle()
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        securityVm.onConsentResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(security.local.pendingConsent, security.local.consentLaunched) {
        val consent = security.local.pendingConsent ?: return@LaunchedEffect
        if (!security.local.consentLaunched) {
            securityVm.consentLaunched()
            consentLauncher.launch(consent)
        }
    }

    val malwareVm: MalwareScanViewModel = careViewModel()
    val malware by malwareVm.state.collectAsStateWithLifecycle()
    val apkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> malwareVm.onApkPicked(uri) }
    LaunchedEffect(malware.pickRequested) {
        if (malware.pickRequested) {
            malwareVm.pickLaunched()
            apkLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        settingsVm.exportDestinationChosen(uri)
    }
    LaunchedEffect(export.pendingContent, export.launched) {
        if (export.pendingContent != null && !export.launched) {
            settingsVm.exportLaunched()
            exportLauncher.launch("oneplus-care-diagnostic.json")
        }
    }
}

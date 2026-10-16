package com.bright.onepluscare.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bright.onepluscare.R
import com.bright.onepluscare.ui.components.CareCard
import com.bright.onepluscare.ui.components.PrimaryAction
import com.bright.onepluscare.ui.vm.SettingsViewModel
import com.bright.onepluscare.ui.vm.careViewModel

/** Two steps: principles, then optional capabilities with first-run detection. Everything can be skipped. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val vm: SettingsViewModel = careViewModel()
    val caps by vm.capabilities.collectAsStateWithLifecycle()
    val actions = rememberPermissionActions()

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            LazyColumn(
                Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (step == 0) {
                    item {
                        Text(stringResource(R.string.onb_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    }
                    item { Text(stringResource(R.string.onb_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { Principle(Icons.Outlined.Visibility, stringResource(R.string.onb_p1_title), stringResource(R.string.onb_p1_body)) }
                    item { Principle(Icons.Outlined.Restore, stringResource(R.string.onb_p2_title), stringResource(R.string.onb_p2_body)) }
                    item { Principle(Icons.Outlined.Shield, stringResource(R.string.onb_p3_title), stringResource(R.string.onb_p3_body)) }
                } else {
                    item { Text(stringResource(R.string.onb_caps_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
                    item { Text(stringResource(R.string.onb_caps_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    permissionItems(caps, actions)
                }
            }
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryAction(
                    stringResource(if (step == 0) R.string.action_continue else R.string.action_finish),
                    onClick = { if (step == 0) step = 1 else onDone() },
                )
                if (step == 1) {
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { step = 0 }) { Text(stringResource(R.string.action_back)) }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDone) { Text(stringResource(R.string.action_skip)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Principle(icon: ImageVector, title: String, body: String) {
    CareCard {
        Row {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

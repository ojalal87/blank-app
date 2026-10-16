package com.bright.onepluscare.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bright.onepluscare.R
import com.bright.onepluscare.core.health.HealthArea
import com.bright.onepluscare.core.health.HealthState
import com.bright.onepluscare.core.model.ChargeState
import com.bright.onepluscare.core.rules.UserAction
import com.bright.onepluscare.data.SystemIntents
import com.bright.onepluscare.ui.Fmt
import com.bright.onepluscare.ui.Labels
import com.bright.onepluscare.ui.bytesText
import com.bright.onepluscare.ui.celsiusText
import com.bright.onepluscare.ui.components.CareCard
import com.bright.onepluscare.ui.components.EmptyState
import com.bright.onepluscare.ui.components.MetricTile
import com.bright.onepluscare.ui.components.PrimaryAction
import com.bright.onepluscare.ui.components.ProgressBlock
import com.bright.onepluscare.ui.components.RingGauge
import com.bright.onepluscare.ui.components.ScreenScaffold
import com.bright.onepluscare.ui.components.SectionHeader
import com.bright.onepluscare.ui.components.StatusPill
import com.bright.onepluscare.ui.components.Tone
import com.bright.onepluscare.ui.components.healthLabel
import com.bright.onepluscare.ui.components.tone
import com.bright.onepluscare.ui.nav.Dest
import com.bright.onepluscare.ui.nav.Navigator
import com.bright.onepluscare.ui.vm.HomeViewModel
import com.bright.onepluscare.ui.vm.LocalAppGraph
import com.bright.onepluscare.ui.vm.careViewModel

/** Maps a recommendation's action to navigation or a documented system screen. */
fun handleAction(action: UserAction, nav: Navigator, intents: SystemIntents) {
    when (action) {
        UserAction.RUN_SMART_SCAN -> nav.go(Dest.SCAN)
        UserAction.OPEN_CLEANER -> nav.go(Dest.CLEANER)
        UserAction.OPEN_DUPLICATES -> nav.go(Dest.DUPLICATES)
        UserAction.OPEN_TRASH -> nav.go(Dest.TRASH)
        UserAction.OPEN_APP_DOCTOR -> nav.go(Dest.APPS)
        UserAction.OPEN_PRIVACY -> nav.go(Dest.PRIVACY)
        UserAction.OPEN_BATTERY -> nav.go(Dest.BATTERY)
        UserAction.OPEN_NETWORK -> nav.go(Dest.NETWORK)
        UserAction.GRANT_STORAGE, UserAction.GRANT_USAGE_ACCESS -> nav.go(Dest.PERMISSIONS)
        UserAction.OPEN_SYSTEM_SETTINGS -> intents.open(SystemIntents.Target.DEVICE_INFO)
    }
}

@Composable
fun HomeScreen(nav: Navigator, padding: PaddingValues) {
    val vm: HomeViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val graph = LocalAppGraph.current
    val context = LocalContext.current

    ScreenScaffold(
        title = stringResource(R.string.app_name),
        outerPadding = padding,
        actions = {
            IconButton(onClick = { nav.go(Dest.SETTINGS) }) { Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.screen_settings)) }
        },
    ) {
        val facts = s.facts
        val health = s.health
        if (s.loading || facts == null || health == null) {
            item { ProgressBlock(stringResource(R.string.home_loading), null) }
            return@ScreenScaffold
        }

        // 1. Overall state: one glance, with the reason in words.
        item {
            CareCard {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val tone = health.state.tone()
                    val stateText = healthLabel(health.state)
                    RingGauge(
                        fraction = when (health.state) { HealthState.HEALTHY -> 1f; HealthState.ATTENTION -> 0.6f; HealthState.CRITICAL -> 0.25f },
                        tone = tone,
                        modifier = Modifier.semantics { contentDescription = stateText },
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stateText, style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.home_device_state), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val worst = health.factors.filter { it.state == health.state && it.state != HealthState.HEALTHY }
                    Text(
                        if (worst.isEmpty()) stringResource(R.string.home_all_good) else worst.joinToString(" · ") { context.getString(Labels.healthReason(it.reason)) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrimaryAction(stringResource(R.string.action_run_smart_scan), onClick = { nav.go(Dest.SCAN) }, icon = Icons.Outlined.PlayArrow)
                }
            }
        }

        // 2. Three key areas only (storage, battery/thermal, network). Everything else lives one level down.
        item {
            val storage = facts.storage
            val stFactor = health.factor(HealthArea.STORAGE)
            val batFactor = health.factor(HealthArea.BATTERY)
            val thFactor = health.factor(HealthArea.THERMAL)
            val netFactor = health.factor(HealthArea.NETWORK)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricTile(
                        label = stringResource(R.string.metric_free_storage),
                        value = bytesText(storage.availableBytes),
                        supporting = stringResource(R.string.metric_of_total, bytesText(storage.totalBytes)),
                        tone = stFactor?.state?.tone(),
                        icon = Icons.Outlined.Storage,
                        modifier = Modifier.weight(1f),
                        onClick = { nav.go(Dest.CLEANER) },
                    )
                    val temp = facts.battery.temperatureC.valueOrNull()
                    val worstBattery = listOfNotNull(batFactor, thFactor).maxByOrNull { it.state }
                    MetricTile(
                        label = stringResource(R.string.metric_battery),
                        value = facts.battery.levelPercent.valueOrNull()?.let { Fmt.percent(it.toDouble()) } ?: stringResource(R.string.not_available),
                        supporting = listOfNotNull(
                            temp?.let { celsiusText(it) },
                            if (facts.battery.chargeState == ChargeState.CHARGING) stringResource(R.string.cs_charging) else null,
                        ).joinToString(" · ").ifEmpty { stringResource(R.string.not_available) },
                        tone = worstBattery?.state?.tone(),
                        icon = Icons.Outlined.BatteryStd,
                        modifier = Modifier.weight(1f),
                        onClick = { nav.go(Dest.BATTERY) },
                    )
                }
                MetricTile(
                    label = stringResource(R.string.metric_network),
                    value = stringResource(netFactor?.reason?.let(Labels::healthReason) ?: R.string.not_available),
                    tone = netFactor?.state?.tone(),
                    icon = Icons.Outlined.Wifi,
                    onClick = { nav.go(Dest.NETWORK) },
                )
            }
        }

        // 3. Top recommendations (max 3).
        item { SectionHeader(stringResource(R.string.home_recommended)) }
        if (s.top.isEmpty()) {
            item {
                CareCard {
                    EmptyState(Icons.Outlined.TaskAlt, stringResource(R.string.home_no_actions_title), stringResource(R.string.home_no_actions_body))
                }
            }
        } else {
            items(s.top, key = { it.id.name }) { rec -> RecommendationCard(rec, onAction = { handleAction(it, nav, graph.intents) }) }
            if (s.all.size > s.top.size) {
                item {
                    com.bright.onepluscare.ui.components.NavRow(
                        icon = Icons.Outlined.TaskAlt,
                        title = stringResource(R.string.home_more_findings, s.all.size - s.top.size),
                        subtitle = null,
                        onClick = { nav.go(Dest.SCAN) },
                    )
                }
            }
        }

        // 4. Last maintenance result.
        item { SectionHeader(stringResource(R.string.home_last_maintenance)) }
        item {
            val last = s.lastMaintenance
            CareCard(onClick = { nav.go(Dest.HISTORY) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        if (last == null) {
                            Text(stringResource(R.string.home_no_history), style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text(stringResource(Labels.history(last.type)), style = MaterialTheme.typography.titleSmall)
                            Text(Fmt.relativeTime(last.timestampMillis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (s.trashCount > 0) StatusPill(Tone.NEUTRAL, stringResource(R.string.home_in_trash, bytesText(s.trashFileBytes)))
                }
            }
        }
    }
}


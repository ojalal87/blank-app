package com.bright.onepluscare.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bright.onepluscare.R
import com.bright.onepluscare.core.battery.BatteryInterpreter
import com.bright.onepluscare.core.model.Reading
import com.bright.onepluscare.core.model.Source
import com.bright.onepluscare.core.oem.GuidanceItem
import com.bright.onepluscare.core.oem.OemFamily
import com.bright.onepluscare.ui.Fmt
import com.bright.onepluscare.ui.Labels
import com.bright.onepluscare.ui.celsiusText
import com.bright.onepluscare.ui.components.CareCard
import com.bright.onepluscare.ui.components.InfoRow
import com.bright.onepluscare.ui.components.NavRow
import com.bright.onepluscare.ui.components.NoticeCard
import com.bright.onepluscare.ui.components.ProgressBlock
import com.bright.onepluscare.ui.components.ScreenScaffold
import com.bright.onepluscare.ui.components.SectionHeader
import com.bright.onepluscare.ui.components.Tone
import com.bright.onepluscare.ui.nav.Navigator
import com.bright.onepluscare.ui.vm.HomeViewModel
import com.bright.onepluscare.ui.vm.LocalAppGraph
import com.bright.onepluscare.ui.vm.careViewModel

fun guidanceIcon(g: GuidanceItem) = when (g) {
    GuidanceItem.REVIEW_BATTERY_OPTIMIZATION_EXEMPTIONS -> Icons.Outlined.Tune
    GuidanceItem.REVIEW_BATTERY_USAGE -> Icons.Outlined.BatteryChargingFull
    GuidanceItem.PER_APP_BACKGROUND_ACTIVITY -> Icons.Outlined.Tune
    GuidanceItem.OPTIMIZED_CHARGING_INFO -> Icons.Outlined.Bolt
    GuidanceItem.SCHEDULED_SCAN_RELIABILITY -> Icons.Outlined.Schedule
    GuidanceItem.STORAGE_SETTINGS -> Icons.Outlined.Storage
}

@Composable
fun BatteryScreen(nav: Navigator, padding: PaddingValues) {
    val vm: HomeViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val graph = LocalAppGraph.current

    ScreenScaffold(title = stringResource(R.string.screen_battery), outerPadding = padding, onBack = nav.back) {
        val f = s.facts
        if (f == null) {
            item { ProgressBlock(stringResource(R.string.home_loading), null) }
            return@ScreenScaffold
        }
        val b = f.battery
        val temp = b.temperatureC.valueOrNull()
        val band = BatteryInterpreter.temperatureBand(temp)
        item {
            CareCard {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(b.levelPercent.valueOrNull()?.let { Fmt.percent(it.toDouble()) } ?: stringResource(R.string.not_available), style = MaterialTheme.typography.displayMedium)
                    Text(stringResource(Labels.charge(b.chargeState)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (BatteryInterpreter.hotWhileCharging(b)) {
            item { NoticeCard(Tone.CRITICAL, stringResource(R.string.rec_battery_hot_charging_title), stringResource(R.string.rec_battery_hot_charging_reason)) }
        }
        item { SectionHeader(stringResource(R.string.battery_measured)) }
        item {
            CareCard {
                Column {
                    InfoRow(stringResource(R.string.battery_temperature), temp?.let { celsiusText(it) }, note = stringResource(tempBandLabel(band)))
                    InfoRow(stringResource(R.string.battery_health), stringResource(Labels.batteryHealth(b.health)), note = stringResource(R.string.battery_health_note))
                    InfoRow(stringResource(R.string.battery_power_source), stringResource(Labels.plug(b.plug)))
                    InfoRow(stringResource(R.string.battery_voltage), b.voltageMv.valueOrNull()?.let { stringResource(R.string.unit_mv, Fmt.number(it)) })
                    InfoRow(
                        stringResource(R.string.battery_current),
                        b.currentNowMicroAmps.valueOrNull()?.let { stringResource(R.string.unit_ma, Fmt.number(it / 1000.0, 0)) },
                        note = stringResource(if ((b.currentNowMicroAmps as? Reading.Measured)?.source == Source.OEM_DEPENDENT) R.string.battery_oem_dependent else R.string.battery_not_reported),
                    )
                    InfoRow(
                        stringResource(R.string.battery_cycles),
                        b.cycleCount.valueOrNull()?.let { Fmt.number(it) },
                        note = if (f.identity.sdkInt < 34) stringResource(R.string.battery_cycles_api) else null,
                    )
                }
            }
        }
        item { SectionHeader(stringResource(R.string.thermal_title)) }
        item {
            CareCard {
                Column {
                    val status = f.thermal.status.valueOrNull()
                    InfoRow(stringResource(R.string.thermal_status), if (status == null) null else stringResource(Labels.thermal(BatteryInterpreter.thermalLabel(status))), note = stringResource(R.string.thermal_status_note))
                    InfoRow(
                        stringResource(R.string.thermal_headroom),
                        f.thermal.headroom.valueOrNull()?.let { Fmt.percent(it * 100.0) },
                        note = stringResource(R.string.thermal_headroom_note),
                    )
                }
            }
        }
        item {
            Text(stringResource(R.string.battery_no_placebo), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val oem = s.capabilities?.oem
        if (oem != null) {
            item {
                SectionHeader(
                    stringResource(
                        when (oem.family) {
                            OemFamily.ONEPLUS -> R.string.oem_oneplus_guidance
                            OemFamily.OPPO_FAMILY -> R.string.oem_coloros_guidance
                            else -> R.string.oem_generic_guidance
                        },
                    ),
                )
            }
            items(oem.guidance, key = { it.name }) { g ->
                NavRow(guidanceIcon(g), stringResource(Labels.guidanceTitle(g)), stringResource(Labels.guidanceBody(g)), { graph.intents.openGuidance(g) })
            }
        }
    }
}

private fun tempBandLabel(b: com.bright.onepluscare.core.battery.TemperatureBand): Int = when (b) {
    com.bright.onepluscare.core.battery.TemperatureBand.COOL -> R.string.temp_cool
    com.bright.onepluscare.core.battery.TemperatureBand.NORMAL -> R.string.temp_normal
    com.bright.onepluscare.core.battery.TemperatureBand.WARM -> R.string.temp_warm
    com.bright.onepluscare.core.battery.TemperatureBand.HOT -> R.string.temp_hot
    com.bright.onepluscare.core.battery.TemperatureBand.UNAVAILABLE -> R.string.temp_unavailable
}

package com.bright.onepluscare.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bright.onepluscare.R
import com.bright.onepluscare.core.network.LayerStatus
import com.bright.onepluscare.core.network.NetworkVerdict
import com.bright.onepluscare.core.network.PrivateDnsMode
import com.bright.onepluscare.data.SystemIntents
import com.bright.onepluscare.ui.Fmt
import com.bright.onepluscare.ui.Labels
import com.bright.onepluscare.ui.components.CareCard
import com.bright.onepluscare.ui.components.EmptyState
import com.bright.onepluscare.ui.components.InfoRow
import com.bright.onepluscare.ui.components.NavRow
import com.bright.onepluscare.ui.components.PrimaryAction
import com.bright.onepluscare.ui.components.ProgressBlock
import com.bright.onepluscare.ui.components.ScreenScaffold
import com.bright.onepluscare.ui.components.SectionHeader
import com.bright.onepluscare.ui.components.StatusPill
import com.bright.onepluscare.ui.components.Tone
import com.bright.onepluscare.ui.nav.Navigator
import com.bright.onepluscare.ui.vm.NetworkViewModel
import com.bright.onepluscare.ui.vm.careViewModel

private fun LayerStatus.tone() = when (this) {
    LayerStatus.OK -> Tone.HEALTHY
    LayerStatus.PROBLEM -> Tone.CRITICAL
    LayerStatus.UNKNOWN -> Tone.NEUTRAL
    LayerStatus.NOT_APPLICABLE -> Tone.NEUTRAL
}

@Composable
fun NetworkScreen(nav: Navigator, padding: PaddingValues) {
    val vm: NetworkViewModel = careViewModel()
    val s by vm.state.collectAsStateWithLifecycle()

    ScreenScaffold(title = stringResource(R.string.screen_network), outerPadding = padding, onBack = nav.back) {
        item {
            CareCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.network_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PrimaryAction(stringResource(if (s.result == null) R.string.action_run_check else R.string.action_run_again), onClick = vm::run, enabled = !s.running, icon = Icons.Outlined.PlayArrow)
                    Text(stringResource(R.string.network_privacy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (s.running) item { ProgressBlock(stringResource(R.string.network_running), null) }
        val r = s.result
        if (r == null && !s.running) {
            item { CareCard { EmptyState(Icons.Outlined.NetworkCheck, stringResource(R.string.network_empty_title), stringResource(R.string.network_empty_body)) } }
        }
        if (r != null) {
            val v = r.diagnosis.verdict
            item {
                CareCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusPill(
                            when (v) {
                                NetworkVerdict.HEALTHY -> Tone.HEALTHY
                                NetworkVerdict.OFFLINE, NetworkVerdict.SLOW, NetworkVerdict.NOT_VALIDATED -> Tone.ATTENTION
                                else -> Tone.CRITICAL
                            },
                            stringResource(Labels.verdict(v)),
                        )
                        Text(stringResource(Labels.verdictAdvice(v)), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.network_measured_at, Fmt.relativeTime(r.measuredAtMillis)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.network_layers)) }
            item {
                CareCard {
                    Column {
                        r.diagnosis.layers.forEach { layer ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(Labels.layer(layer.layer)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                layer.millis?.let {
                                    Text(stringResource(R.string.unit_ms, Fmt.number(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(8.dp))
                                }
                                StatusPill(layer.status.tone(), stringResource(Labels.layerStatus(layer.status)))
                            }
                        }
                    }
                }
            }
            item {
                CareCard {
                    Column {
                        InfoRow(stringResource(R.string.network_transport), r.snapshot.transports.map { stringResource(Labels.transport(it)) }.joinToString(" + ").ifEmpty { null })
                        InfoRow(stringResource(R.string.network_metered), stringResource(if (r.snapshot.metered) R.string.yes else R.string.no))
                        InfoRow(
                            stringResource(R.string.network_private_dns),
                            stringResource(
                                when (r.snapshot.privateDns) {
                                    PrivateDnsMode.OFF -> R.string.pdns_off
                                    PrivateDnsMode.OPPORTUNISTIC -> R.string.pdns_auto
                                    PrivateDnsMode.STRICT -> R.string.pdns_strict
                                    PrivateDnsMode.UNKNOWN -> R.string.not_available
                                },
                            ),
                        )
                        InfoRow(stringResource(R.string.network_dns_servers), Fmt.number(r.snapshot.dnsServerCount), note = stringResource(R.string.network_addresses_hidden))
                    }
                }
            }
        }
        item { SectionHeader(stringResource(R.string.network_fix)) }
        item { NavRow(Icons.Outlined.Wifi, stringResource(R.string.repair_internet_title), stringResource(R.string.repair_internet_body), { vm.open(SystemIntents.Target.INTERNET_PANEL) }) }
        item { NavRow(Icons.Outlined.Dns, stringResource(R.string.repair_private_dns_title), stringResource(R.string.repair_private_dns_body), { vm.open(SystemIntents.Target.PRIVATE_DNS_NETWORK) }) }
    }
}

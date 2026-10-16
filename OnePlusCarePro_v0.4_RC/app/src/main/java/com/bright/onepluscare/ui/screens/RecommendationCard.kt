package com.bright.onepluscare.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bright.onepluscare.R
import com.bright.onepluscare.core.rules.Recommendation
import com.bright.onepluscare.core.rules.UserAction
import com.bright.onepluscare.ui.Labels
import com.bright.onepluscare.ui.bytesText
import com.bright.onepluscare.ui.components.CareCard
import com.bright.onepluscare.ui.components.InfoRow
import com.bright.onepluscare.ui.components.color
import com.bright.onepluscare.ui.components.icon
import com.bright.onepluscare.ui.components.tone
import com.bright.onepluscare.ui.evidenceValue

/**
 * Explainable recommendation: title + reason always visible; evidence, expected benefit, risk,
 * reversibility and required permission one tap away. Labelled "rule-based", never "AI".
 */
@Composable
fun RecommendationCard(rec: Recommendation, onAction: (UserAction) -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(rec.id) { mutableStateOf(false) }
    val tone = rec.severity.tone()
    CareCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(tone.icon(), contentDescription = null, tint = tone.color(), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Labels.recTitle(rec.id)), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(Labels.recReason(rec.id)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = stringResource(if (expanded) R.string.action_hide_details else R.string.action_show_details),
                    )
                }
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    rec.evidence.forEach { e -> InfoRow(stringResource(Labels.evidence(e.kind)), evidenceValue(e)) }
                    InfoRow(stringResource(R.string.rec_field_benefit), rec.estimatedBenefitBytes?.let { stringResource(R.string.rec_up_to, bytesText(it)) } ?: stringResource(Labels.recBenefit(rec.id)))
                    if (rec.estimatedBenefitBytes != null) {
                        Text(stringResource(Labels.recBenefit(rec.id)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    InfoRow(stringResource(R.string.rec_field_risk), stringResource(Labels.risk(rec.risk)))
                    InfoRow(stringResource(R.string.rec_field_reversible), stringResource(Labels.reversibility(rec.reversibility)))
                    InfoRow(stringResource(R.string.rec_field_permission), stringResource(Labels.permission(rec.permission)))
                    Text(stringResource(R.string.rec_rule_based_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onAction(rec.action) }) { Text(stringResource(Labels.action(rec.action))) }
            }
        }
    }
}

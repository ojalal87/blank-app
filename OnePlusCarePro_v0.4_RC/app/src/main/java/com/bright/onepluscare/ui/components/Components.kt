package com.bright.onepluscare.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bright.onepluscare.R
import com.bright.onepluscare.core.health.HealthState
import com.bright.onepluscare.core.rules.Severity
import com.bright.onepluscare.ui.theme.LocalStatusColors

enum class Tone { HEALTHY, ATTENTION, CRITICAL, INFO, NEUTRAL }

fun HealthState.tone(): Tone = when (this) {
    HealthState.HEALTHY -> Tone.HEALTHY
    HealthState.ATTENTION -> Tone.ATTENTION
    HealthState.CRITICAL -> Tone.CRITICAL
}

fun Severity.tone(): Tone = when (this) {
    Severity.CRITICAL -> Tone.CRITICAL
    Severity.ATTENTION -> Tone.ATTENTION
    Severity.INFO -> Tone.INFO
}

@Composable
fun Tone.color(): Color {
    val s = LocalStatusColors.current
    return when (this) {
        Tone.HEALTHY -> s.healthy
        Tone.ATTENTION -> s.attention
        Tone.CRITICAL -> s.critical
        Tone.INFO -> s.info
        Tone.NEUTRAL -> s.neutral
    }
}

fun Tone.icon(): ImageVector = when (this) {
    Tone.HEALTHY -> Icons.Outlined.CheckCircle
    Tone.ATTENTION -> Icons.Outlined.WarningAmber
    Tone.CRITICAL -> Icons.Outlined.ErrorOutline
    Tone.INFO, Tone.NEUTRAL -> Icons.Outlined.Info
}

@Composable
fun healthLabel(state: HealthState): String = stringResource(
    when (state) {
        HealthState.HEALTHY -> R.string.state_healthy
        HealthState.ATTENTION -> R.string.state_attention
        HealthState.CRITICAL -> R.string.state_critical
    },
)

/** Icon + text status pill. Color is never the only signal. */
@Composable
fun StatusPill(tone: Tone, label: String, modifier: Modifier = Modifier) {
    val c = tone.color()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(c.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(tone.icon(), contentDescription = null, tint = c, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = c)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
fun CareCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val shape = RoundedCornerShape(20.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, colors = colors) {
            Box(Modifier.padding(contentPadding)) { content() }
        }
    } else {
        Card(modifier = modifier.fillMaxWidth(), shape = shape, colors = colors) {
            Box(Modifier.padding(contentPadding)) { content() }
        }
    }
}

/** Ring gauge. [fraction] in 0..1. The center content carries the accessible text. */
@Composable
fun RingGauge(
    fraction: Float,
    tone: Tone,
    modifier: Modifier = Modifier,
    diameter: Int = 168,
    stroke: Int = 14,
    center: @Composable () -> Unit,
) {
    val animated by animateFloatAsState(targetValue = fraction.coerceIn(0f, 1f), animationSpec = tween(700), label = "ring")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val color = tone.color()
    Box(modifier.size(diameter.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter.dp)) {
            val w = stroke.dp.toPx()
            drawArc(track, startAngle = 135f, sweepAngle = 270f, useCenter = false, style = Stroke(w, cap = StrokeCap.Round))
            drawArc(color, startAngle = 135f, sweepAngle = 270f * animated, useCenter = false, style = Stroke(w, cap = StrokeCap.Round))
        }
        center()
    }
}

@Composable
fun UsageBar(fraction: Float, tone: Tone, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        color = tone.color(),
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    tone: Tone? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    CareCard(modifier = modifier, onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (supporting != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (tone != null) {
                        Icon(tone.icon(), contentDescription = null, tint = tone.color(), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(supporting, style = MaterialTheme.typography.bodySmall, color = tone?.color() ?: MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
        }
    }
}

/** label: value row. Pass [value] = null to render the localized "Not available". */
@Composable
fun InfoRow(label: String, value: String?, modifier: Modifier = Modifier, note: String? = null) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            value ?: stringResource(R.string.not_available),
            style = MaterialTheme.typography.bodyMedium,
            color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Navigation row used by hub screens. Minimum 56dp target. */
@Composable
fun NavRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing() else Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

@Composable
fun SecondaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text)
    }
}

/**
 * Confirmation for any destructive or system-visible action. Irreversible actions get the critical color,
 * an explicit consequence line and a haptic tick on confirm.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    irreversible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val danger = Tone.CRITICAL.color()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (irreversible) Icons.Outlined.ErrorOutline else Icons.Outlined.Info, contentDescription = null, tint = if (irreversible) danger else MaterialTheme.colorScheme.onSurface) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body)
                if (irreversible) Text(stringResource(R.string.confirm_cannot_undo), color = danger, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (irreversible) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onConfirm()
            }) { Text(confirmLabel, color = if (irreversible) danger else MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) action()
    }
}

/** Determinate when [fraction] is known, otherwise indeterminate. Announces [label] to TalkBack. */
@Composable
fun ProgressBlock(label: String, fraction: Float?, modifier: Modifier = Modifier, onCancel: (() -> Unit)? = null) {
    CareCard(modifier.animateContentSize()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.semantics { contentDescription = label }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (onCancel != null) TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
            }
        }
    }
}

@Composable
fun NoticeCard(tone: Tone, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = tone.color()
    CareCard(modifier) {
        Row {
            Icon(tone.icon(), contentDescription = null, tint = c, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (action != null) {
                    Spacer(Modifier.height(4.dp))
                    action()
                }
            }
        }
    }
}

@Composable
fun dangerButtonColors() = ButtonDefaults.buttonColors(containerColor = Tone.CRITICAL.color(), contentColor = Color.Black)

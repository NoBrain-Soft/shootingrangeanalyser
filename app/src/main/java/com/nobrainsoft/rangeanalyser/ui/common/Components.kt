package com.nobrainsoft.rangeanalyser.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nobrainsoft.rangeanalyser.core.coach.Tip
import com.nobrainsoft.rangeanalyser.core.coach.TipConfidence
import com.nobrainsoft.rangeanalyser.core.coach.TipSeverity
import com.nobrainsoft.rangeanalyser.ui.theme.Dimens
import com.nobrainsoft.rangeanalyser.ui.theme.LocalRangeMode
import com.nobrainsoft.rangeanalyser.ui.theme.ScoreColors

/** One measured number, big enough to read at arm's length in daylight. */
@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    emphasis: Boolean = false,
) {
    Card(
        modifier = modifier.heightIn(min = Dimens.touchTarget),
        shape = RoundedCornerShape(Dimens.cardCorner),
        colors = CardDefaults.cardColors(
            containerColor = if (emphasis) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(Dimens.itemSpacing)) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = if (emphasis) {
                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (emphasis) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            secondary?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (emphasis) {
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * A coaching tip with the numbers that produced it.
 *
 * The evidence is always shown, never behind a disclosure. A tip that asserts without showing its
 * working cannot be argued with, and shooting has enough confident folklore already.
 */
@Composable
fun TipCard(tip: Tip, modifier: Modifier = Modifier) {
    val accent = when (tip.severity) {
        TipSeverity.IMPORTANT -> MaterialTheme.colorScheme.primary
        TipSeverity.SUGGESTION -> ScoreColors.warning
        TipSeverity.INFO -> MaterialTheme.colorScheme.secondary
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.cardCorner),
    ) {
        Column(Modifier.padding(Dimens.gutter)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(accent, CircleShape),
                )
                Text(
                    text = tip.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }

            Text(
                text = tip.body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (tip.evidence.isNotEmpty()) {
                Column(Modifier.padding(top = 12.dp)) {
                    tip.evidence.forEach { evidence ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = evidence.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = evidence.value,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }

            if (tip.confidence == TipConfidence.LOW) {
                Text(
                    text = "Commonly believed rather than measured - worth testing, not trusting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

/** How sure the app is about something, stated rather than hidden. */
@Composable
fun ConfidenceBadge(confidence: Double, modifier: Modifier = Modifier) {
    val (label, colour) = when {
        confidence >= 0.8 -> "Confident" to ScoreColors.hit
        confidence >= 0.6 -> "Probable" to ScoreColors.warning
        else -> "Check this" to ScoreColors.miss
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = colour.copy(alpha = 0.18f),
    ) {
        Text(
            text = "$label  ${(confidence * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = colour,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * A number the user changes with their thumbs rather than the keyboard.
 *
 * Typing outdoors is miserable, and most of these values move in known steps anyway.
 */
@Composable
fun StepperField(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    step: Double = 1.0,
    range: ClosedFloatingPointRange<Double> = 0.0..1000.0,
    suffix: String = "",
    supporting: String? = null,
    format: (Double) -> String = { if (it % 1.0 == 0.0) it.toInt().toString() else String.format("%.2f", it) },
) {
    val buttonSize = if (LocalRangeMode.current) Dimens.touchTargetRange else Dimens.touchTarget

    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        supporting?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = { onValueChange((value - step).coerceIn(range.start, range.endInclusive)) },
                modifier = Modifier.size(buttonSize),
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Decrease $label")
            }
            Text(
                text = "${format(value)}$suffix",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(
                onClick = { onValueChange((value + step).coerceIn(range.start, range.endInclusive)) },
                modifier = Modifier.size(buttonSize),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Increase $label")
            }
        }
    }
}

/**
 * A step-by-step flow with its progress visible.
 *
 * Used by the calibration wizard. The back button is always present and always in the same place:
 * a wizard that can strand you is worse than a form.
 */
@Composable
fun WizardScaffold(
    title: String,
    stepIndex: Int,
    stepCount: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.touchTarget)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(Dimens.touchTarget)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Step ${stepIndex + 1} of $stepCount",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = { (stepIndex + 1f) / stepCount },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        bottomBar = bottomBar,
    ) { padding ->
        content(Modifier.padding(padding))
    }
}

/** A tappable row of choices, sized for gloves. */
@Composable
fun ChoiceRow(
    label: String,
    description: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.touchTarget)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Dimens.cardCorner),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            Modifier.padding(Dimens.gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        CircleShape,
                    )
                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
            Column(Modifier.padding(start = Dimens.itemSpacing)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Warns about something the user should know before trusting a number. */
@Composable
fun CautionBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.cardCorner),
        color = ScoreColors.warning.copy(alpha = 0.18f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(Dimens.gutter),
        )
    }
}

/** Fixed-height spacer used to keep bottom-anchored actions clear of content. */
@Composable
fun BottomActionSpacer() {
    Box(Modifier.fillMaxWidth().height(Dimens.touchTargetRange + Dimens.gutterWide))
}

/** Horizontal spacer, for rows of tiles. */
@Composable
fun TileGap() {
    Box(Modifier.width(Dimens.itemSpacing))
}

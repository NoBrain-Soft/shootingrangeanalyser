package com.nobrainsoft.rangeanalyser.ui.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nobrainsoft.rangeanalyser.appContainer
import com.nobrainsoft.rangeanalyser.core.analysis.AnalysedSession
import com.nobrainsoft.rangeanalyser.core.coach.CoachingReport
import com.nobrainsoft.rangeanalyser.core.stats.SightCorrection
import com.nobrainsoft.rangeanalyser.core.stats.HorizontalDirection
import com.nobrainsoft.rangeanalyser.core.stats.VerticalDirection
import com.nobrainsoft.rangeanalyser.export.Exporter
import com.nobrainsoft.rangeanalyser.ui.common.MetricTile
import com.nobrainsoft.rangeanalyser.ui.common.ShotLayer
import com.nobrainsoft.rangeanalyser.ui.common.TargetView
import com.nobrainsoft.rangeanalyser.ui.common.TipCard
import com.nobrainsoft.rangeanalyser.ui.rangeViewModel
import com.nobrainsoft.rangeanalyser.ui.theme.Dimens
import com.nobrainsoft.rangeanalyser.ui.theme.ScoreColors
import java.util.Locale

@Composable
fun ResultsScreen(sessionId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel = rangeViewModel(key = "results-$sessionId") {
        ResultsViewModel(it.repository, sessionId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    val analysed = state.analysed
    val exporter = remember(context) {
        Exporter(context, context.appContainer.files.exportsDirectory)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(analysed?.session?.name ?: "Results") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (analysed != null) {
                        IconButton(onClick = {
                            val file = exporter.toCsv(analysed)
                            context.startActivity(exporter.shareIntent(file, "text/csv"))
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (analysed == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        ResultsContent(
            analysed = analysed,
            coaching = state.coaching,
            correction = state.correction,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@Composable
private fun ResultsContent(
    analysed: AnalysedSession,
    coaching: CoachingReport?,
    correction: SightCorrection?,
    modifier: Modifier = Modifier,
) {
    val stats = analysed.stats

    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.gutter),
        verticalArrangement = Arrangement.spacedBy(Dimens.itemSpacing),
    ) {
        item {
            Card {
                Box(Modifier.fillMaxWidth().height(340.dp)) {
                    TargetView(
                        spec = analysed.target
                            ?: com.nobrainsoft.rangeanalyser.core.target.TargetLibrary.BLANK_A4,
                        layers = listOf(
                            ShotLayer(
                                label = analysed.session.name,
                                shots = analysed.session.shots,
                                colour = ScoreColors.hit,
                                showCentroid = true,
                            ),
                        ),
                        pointOfAim = analysed.session.pointOfAim,
                        extremeSpreadPair = stats.extremeSpreadPair?.let { (first, second) ->
                            val positions = analysed.positions
                            positions.getOrNull(first)?.let { a ->
                                positions.getOrNull(second)?.let { b -> a to b }
                            }
                        },
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.itemSpacing)) {
                MetricTile(
                    label = "Mean radius",
                    value = String.format(Locale.ROOT, "%.1f mm", stats.meanRadiusMm),
                    secondary = String.format(Locale.ROOT, "%.2f MOA", stats.meanRadiusMoa()),
                    emphasis = true,
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "Group",
                    value = String.format(Locale.ROOT, "%.1f mm", stats.extremeSpreadMm),
                    secondary = String.format(Locale.ROOT, "%.2f MOA", stats.extremeSpreadMoa()),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.itemSpacing)) {
                MetricTile(
                    label = "Shots",
                    value = stats.shotCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "CEP 50%",
                    value = String.format(Locale.ROOT, "%.1f mm", stats.cep50Mm),
                    secondary = "half the shots land inside",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // The interval, not just the number. A group is a sample, and the honest headline is the
        // range it could plausibly have been.
        stats.extremeSpreadCi?.let { interval ->
            item {
                Card {
                    Column(Modifier.padding(Dimens.gutter)) {
                        Text(
                            "What this group really tells you",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            String.format(
                                Locale.ROOT,
                                "Shooting this same gun and load again, a %d-shot group would " +
                                    "plausibly measure anywhere from %.1f to %.1f mm. The single " +
                                    "number above is one sample from that range.",
                                stats.shotCount,
                                interval.lower,
                                interval.upper,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }

        analysed.score?.let { score ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.itemSpacing)) {
                    MetricTile(
                        label = "Score",
                        value = score.totalDecimalScore
                            ?.let { String.format(Locale.ROOT, "%.1f", it) }
                            ?: score.totalRingScore.toString(),
                        secondary = score.maxPossibleRingScore?.let { "of $it" },
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = if (score.innerRingCount > 0) "Inner" else "Misses",
                        value = if (score.innerRingCount > 0) {
                            score.innerRingCount.toString()
                        } else {
                            score.missCount.toString()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        correction?.let { item { SightCard(it) } }

        coaching?.let { report ->
            item {
                Text(
                    "What the numbers suggest",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = Dimens.itemSpacing),
                )
            }
            items(report.tips, key = { it.id }) { TipCard(it) }
            item {
                Text(
                    report.disclaimer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Dimens.itemSpacing),
                )
            }
        }
    }
}

@Composable
private fun SightCard(correction: SightCorrection) {
    val vertical = when (correction.verticalDirection) {
        VerticalDirection.UP -> "${correction.verticalClickCount} up"
        VerticalDirection.DOWN -> "${correction.verticalClickCount} down"
        VerticalDirection.NONE -> null
    }
    val horizontal = when (correction.horizontalDirection) {
        HorizontalDirection.LEFT -> "${correction.horizontalClickCount} left"
        HorizontalDirection.RIGHT -> "${correction.horizontalClickCount} right"
        HorizontalDirection.NONE -> null
    }

    Card {
        Column(Modifier.padding(Dimens.gutter)) {
            Text(
                "Sight adjustment",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                listOfNotNull(vertical, horizontal).joinToString(", ").ifBlank { "None needed" },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
            // Stated as impact movement, because sight markings disagree and holes do not.
            Text(
                "That is how far the point of impact should move. If the group goes the wrong way " +
                    "after the first adjustment, turn the other way - the number of clicks is " +
                    "right either way.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (correction.residualMm.radius > 0.5) {
                Text(
                    String.format(
                        Locale.ROOT,
                        "Clicks are discrete, so about %.1f mm will remain.",
                        correction.residualMm.radius,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

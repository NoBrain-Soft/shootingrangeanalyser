package com.nobrainsoft.rangeanalyser.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nobrainsoft.rangeanalyser.core.analysis.AnalysedSession
import com.nobrainsoft.rangeanalyser.core.stats.ComparisonResult
import com.nobrainsoft.rangeanalyser.core.stats.ComparisonVerdict
import com.nobrainsoft.rangeanalyser.core.stats.ResolutionAdvice
import com.nobrainsoft.rangeanalyser.core.stats.TrendDirection
import com.nobrainsoft.rangeanalyser.core.stats.TrendMetric
import com.nobrainsoft.rangeanalyser.core.target.TargetLibrary
import com.nobrainsoft.rangeanalyser.ui.common.CautionBanner
import com.nobrainsoft.rangeanalyser.ui.common.MetricTile
import com.nobrainsoft.rangeanalyser.ui.common.SeriesColours
import com.nobrainsoft.rangeanalyser.ui.common.ShotLayer
import com.nobrainsoft.rangeanalyser.ui.common.TargetView
import com.nobrainsoft.rangeanalyser.ui.common.TrendChart
import com.nobrainsoft.rangeanalyser.ui.common.millimetreLabel
import com.nobrainsoft.rangeanalyser.ui.rangeViewModel
import com.nobrainsoft.rangeanalyser.ui.theme.Dimens
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onCompare: () -> Unit) {
    val viewModel = rangeViewModel(key = "history") { HistoryViewModel(it.repository) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved targets") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            if (state.canCompare) {
                Button(
                    onClick = onCompare,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Dimens.gutter)
                        .heightIn(min = Dimens.touchTargetRange),
                ) {
                    Text("Compare these two")
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.gutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.itemSpacing),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.filter.favouritesOnly,
                        onClick = viewModel::toggleFavouritesOnly,
                        label = { Text("Favourites") },
                    )
                    if (state.selectedIds.isNotEmpty()) {
                        FilterChip(
                            selected = true,
                            onClick = viewModel::clearSelection,
                            label = { Text("${state.selectedIds.size} selected - clear") },
                        )
                    }
                }
            }

            if (state.sessions.isEmpty()) {
                item {
                    Text(
                        "Nothing saved yet. Analyse a target and it will appear here, ready to " +
                            "compare against the next one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(state.sessions, key = { it.session.id }) { session ->
                SessionRow(
                    session = session,
                    selected = session.session.id in state.selectedIds,
                    onOpen = { onOpen(session.session.id) },
                    onToggleSelect = { viewModel.toggleSelected(session.session.id) },
                    onToggleFavourite = {
                        viewModel.setFavourite(session.session.id, !session.session.isFavourite)
                    },
                )
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: AnalysedSession,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(Dimens.cardCorner),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(Dimens.gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clickable(onClick = onToggleSelect),
            ) {
                TargetView(
                    spec = session.target ?: TargetLibrary.BLANK_A4,
                    layers = listOf(
                        ShotLayer(
                            label = session.session.name,
                            shots = session.session.shots,
                            colour = SeriesColours.blue,
                            numbered = false,
                        ),
                    ),
                    showRings = false,
                )
            }

            Column(Modifier.weight(1f).padding(start = Dimens.itemSpacing)) {
                Text(session.session.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(
                        dateFormat.format(Date(session.session.startedAtEpochMs)),
                        session.firearm?.name,
                        session.ammo?.displayName,
                        "${session.shotCount} shots",
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    millimetreLabel(session.stats.meanRadiusMm),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "mm MR",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(onClick = onToggleFavourite) {
                Icon(
                    if (session.session.isFavourite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "Favourite",
                )
            }
        }
    }
}

/**
 * Two targets side by side, and a straight answer about whether the difference is real.
 */
@Composable
fun CompareScreen(onBack: () -> Unit) {
    val viewModel = rangeViewModel(key = "history") { HistoryViewModel(it.repository) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val chosen = state.selected

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compare") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (chosen.size < 2) {
            Text(
                "Pick two saved targets to compare.",
                modifier = Modifier.padding(padding).padding(Dimens.gutter),
            )
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.gutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.itemSpacing),
        ) {
            item {
                Card {
                    Box(Modifier.fillMaxWidth().height(340.dp)) {
                        TargetView(
                            spec = chosen[0].target ?: TargetLibrary.BLANK_A4,
                            layers = chosen.mapIndexed { index, session ->
                                ShotLayer(
                                    label = session.session.name,
                                    shots = session.session.shots,
                                    colour = SeriesColours.at(index),
                                    numbered = false,
                                    showCentroid = true,
                                    // The older group is hollow, so the two never merge visually.
                                    outlined = index > 0,
                                )
                            },
                        )
                    }
                }
            }

            // Identity is never carried by colour alone: each series is named beside its swatch.
            item {
                Column {
                    chosen.forEachIndexed { index, session ->
                        Row(
                            Modifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .background(SeriesColours.at(index), CircleShape),
                            )
                            Text(
                                session.session.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }

            state.comparison?.let { comparison -> item { VerdictCard(comparison) } }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.itemSpacing)) {
                    MetricTile(
                        label = chosen[0].session.name.take(14),
                        value = "${millimetreLabel(chosen[0].stats.meanRadiusMm)} mm",
                        secondary = "${chosen[0].shotCount} shots",
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = chosen[1].session.name.take(14),
                        value = "${millimetreLabel(chosen[1].stats.meanRadiusMm)} mm",
                        secondary = "${chosen[1].shotCount} shots",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The headline answer.
 *
 * This card is the reason the comparison feature is worth having: it will happily say that a
 * visibly better group is not, in fact, evidence of anything.
 */
@Composable
private fun VerdictCard(comparison: ComparisonResult) {
    val headline = when (comparison.verdict) {
        ComparisonVerdict.A_TIGHTER -> "${comparison.aLabel} is genuinely tighter"
        ComparisonVerdict.B_TIGHTER -> "${comparison.bLabel} is genuinely tighter"
        ComparisonVerdict.INDISTINGUISHABLE -> "Too close to call"
        ComparisonVerdict.INSUFFICIENT_DATA -> "Not enough shots to compare"
    }

    val detail = buildString {
        append(
            String.format(
                Locale.ROOT,
                "Mean radius %.1f mm against %.1f mm, a %.0f%% difference. ",
                comparison.aMeanRadiusMm,
                comparison.bMeanRadiusMm,
                kotlin.math.abs(comparison.meanRadiusPercentChange),
            ),
        )
        when (comparison.verdict) {
            ComparisonVerdict.INDISTINGUISHABLE -> append(
                "With these shot counts, groups this different turn up by chance often enough " +
                    "that the difference means nothing. ",
            )

            ComparisonVerdict.INSUFFICIENT_DATA -> append("Shoot more before drawing conclusions. ")
            else -> append("That difference is larger than chance comfortably explains. ")
        }
        when (comparison.advice) {
            ResolutionAdvice.SHOOT_MORE -> comparison.shotsPerGroupToDecide?.let {
                append("About $it shots in each group would settle it.")
            }

            ResolutionAdvice.DIFFERENCE_TOO_SMALL_TO_MATTER -> append(
                "No practical amount of shooting would separate these two - they shoot the same.",
            )

            else -> Unit
        }
    }

    Card {
        Column(Modifier.padding(Dimens.gutter)) {
            Text(headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (comparison.centroidShiftMm > 5.0) {
                Text(
                    String.format(
                        Locale.ROOT,
                        "The point of impact also moved %.0f mm between them - that is a zero " +
                            "change, separate from how tight the groups are.",
                        comparison.centroidShiftMm,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
fun ProgressScreen(onBack: () -> Unit) {
    val viewModel = rangeViewModel(key = "history") { HistoryViewModel(it.repository) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Progress") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.gutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.itemSpacing),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        TrendMetric.MEAN_RADIUS_MM to "Mean radius",
                        TrendMetric.EXTREME_SPREAD_MM to "Group size",
                        TrendMetric.AVERAGE_SCORE_PER_SHOT to "Score",
                    ).forEach { (metric, label) ->
                        FilterChip(
                            selected = state.trendMetric == metric,
                            onClick = { viewModel.setMetric(metric) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            val trend = state.trend
            if (trend == null) {
                item {
                    Text(
                        "Three sessions with the same setup are needed before a trend means " +
                            "anything. Keep shooting - the app will start reporting it then.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item {
                    Card {
                        Column(Modifier.padding(Dimens.gutter)) {
                            // The chart plots one measure; the title names it, so no legend.
                            Text(
                                when (state.trendMetric) {
                                    TrendMetric.MEAN_RADIUS_MM -> "Mean radius, mm"
                                    TrendMetric.EXTREME_SPREAD_MM -> "Group size, mm"
                                    TrendMetric.AVERAGE_SCORE_PER_SHOT -> "Average score per shot"
                                    else -> "Trend"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Box(Modifier.fillMaxWidth().height(220.dp)) {
                                TrendChart(
                                    trend = trend,
                                    valueLabel = ::millimetreLabel,
                                    lowerIsBetter = state.trendMetric.lowerIsBetter,
                                )
                            }
                        }
                    }
                }

                item {
                    when (trend.direction) {
                        TrendDirection.IMPROVING -> Card {
                            Text(
                                "You are improving, and the trend holds up rather than being one " +
                                    "good day.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(Dimens.gutter),
                            )
                        }

                        TrendDirection.WORSENING -> CautionBanner(
                            "Groups have been opening up across these sessions. Worth checking " +
                                "mounting screws and whether the ammunition is the same lot.",
                        )

                        TrendDirection.NO_CHANGE_DETECTED -> Card {
                            Text(
                                "Your groups move around, but not by more than normal " +
                                    "session-to-session variation. That is not the same as no " +
                                    "progress - there is not yet enough data to see it.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(Dimens.gutter),
                            )
                        }
                    }
                }
            }

            state.ranking?.takeIf { it.entries.size >= 2 }?.let { ranking ->
                item {
                    Text(
                        "Loads",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = Dimens.itemSpacing),
                    )
                }
                items(ranking.entries, key = { it.ammoId ?: it.ammoName }) { load ->
                    Card {
                        Column(Modifier.padding(Dimens.gutter)) {
                            Text(load.ammoName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                String.format(
                                    Locale.ROOT,
                                    "%.2f MOA mean radius over %d session%s",
                                    load.meanRadiusMoa,
                                    load.sessionCount,
                                    if (load.sessionCount == 1) "" else "s",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (!ranking.topTwoSeparable) {
                    item {
                        CautionBanner(
                            "The top two loads are not separable on this much data - whichever is " +
                                "ahead is ahead by luck, not by performance.",
                        )
                    }
                }
            }
        }
    }
}

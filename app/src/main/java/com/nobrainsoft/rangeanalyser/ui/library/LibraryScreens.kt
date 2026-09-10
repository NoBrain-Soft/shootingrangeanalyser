// TopAppBar is still @ExperimentalMaterial3Api in Material3 1.3. Opted in at file level
// rather than per function: every top-level screen here has an app bar.
@file:OptIn(ExperimentalMaterial3Api::class)

package com.nobrainsoft.rangeanalyser.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nobrainsoft.rangeanalyser.core.model.Calibers
import com.nobrainsoft.rangeanalyser.core.stats.LoadPerformance
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import com.nobrainsoft.rangeanalyser.ui.common.enumLabel
import com.nobrainsoft.rangeanalyser.ui.rangeViewModel
import com.nobrainsoft.rangeanalyser.ui.theme.Dimens
import java.util.Locale

@Composable
fun FirearmLibraryScreen(onBack: () -> Unit, onEdit: (String?) -> Unit) {
    val viewModel = rangeViewModel(key = "library") { LibraryViewModel(it.repository) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LibraryScaffold(
        title = "Firearms",
        addLabel = "Add firearm",
        onBack = onBack,
        onAdd = { onEdit(null) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.gutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.itemSpacing),
        ) {
            items(state.firearms, key = { it.id }) { firearm ->
                LibraryRow(
                    title = firearm.name.ifBlank { "Unnamed" },
                    subtitle = listOfNotNull(
                        enumLabel(firearm.type.name),
                        Calibers.find(firearm.caliberId)?.displayName,
                        firearm.sight.clickValue?.let {
                            "${it.amount} ${enumLabel(it.unit.name)} per click"
                        },
                    ).joinToString("  ·  "),
                    onClick = { onEdit(firearm.id) },
                )
            }
        }
    }
}

@Composable
fun AmmoLibraryScreen(onBack: () -> Unit, onEdit: (String?) -> Unit) {
    val viewModel = rangeViewModel(key = "library") { LibraryViewModel(it.repository) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LibraryScaffold(
        title = "Ammunition",
        addLabel = "Add load",
        onBack = onBack,
        onAdd = { onEdit(null) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.gutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.itemSpacing),
        ) {
            items(state.ammo, key = { it.id }) { ammo ->
                LibraryRow(
                    title = ammo.displayName,
                    subtitle = listOfNotNull(
                        Calibers.find(ammo.caliberId)?.displayName,
                        ammo.lot.takeIf { it.isNotBlank() }?.let { "lot $it" },
                    ).joinToString("  ·  "),
                    // How a load has actually shot belongs next to the load, not buried in a
                    // separate report - it is the whole reason for recording lots.
                    trailing = state.loadPerformance[ammo.id]?.let { performanceLine(it) },
                    onClick = { onEdit(ammo.id) },
                )
            }
        }
    }
}

@Composable
fun TargetLibraryScreen(
    onBack: () -> Unit,
    onEditCustom: (TargetSpec) -> Unit,
    onNewTarget: () -> Unit,
) {
    val viewModel = rangeViewModel(key = "library") { LibraryViewModel(it.repository) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LibraryScaffold(
        title = "Targets",
        addLabel = "Add target",
        onBack = onBack,
        onAdd = onNewTarget,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.gutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.itemSpacing),
        ) {
            items(state.targets, key = { it.id }) { spec ->
                LibraryRow(
                    title = spec.name,
                    subtitle = buildString {
                        append(enumLabel(spec.discipline.name))
                        spec.defaultDistanceM?.let {
                            append("  ·  ${it.toInt()} m")
                        }
                        if (!spec.dimensionsVerified) {
                            append("  ·  dimensions approximate")
                        }
                    },
                    // Where the dimensions came from is a sentence, not a value, so it goes under
                    // the row rather than beside it. Put in `trailing` it took whatever width it
                    // wanted and squeezed the name into a column one character wide.
                    footnote = spec.source.takeIf { it.isNotBlank() },
                    // A built-in face has published dimensions and is not the user's to change;
                    // opening it as a copy would quietly detach them from the rulebook.
                    onClick = { if (!spec.isBuiltIn) onEditCustom(spec) },
                )
            }
        }
    }
}

@Composable
private fun LibraryScaffold(
    title: String,
    addLabel: String,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(addLabel) },
            )
        },
        content = content,
    )
}

/**
 * One entry in a library list.
 *
 * [trailing] is for a short measured value shown beside the name; it is width-capped, because an
 * unbounded one takes as much of the row as it likes and leaves the name wrapping a letter at a
 * time. Anything longer than a couple of words belongs in [footnote], under the row.
 */
@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: String? = null,
    footnote: String? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.touchTarget)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Dimens.cardCorner),
    ) {
        Column(Modifier.fillMaxWidth().padding(Dimens.gutter)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                trailing?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .widthIn(max = 120.dp)
                            .padding(start = 8.dp),
                    )
                }
            }
            footnote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * A load's record, with its uncertainty attached.
 *
 * The interval is shown rather than the bare average because a load tried once at one range trip
 * has not been measured so much as sampled.
 */
private fun performanceLine(performance: LoadPerformance): String {
    val mean = String.format(Locale.ROOT, "%.2f", performance.meanRadiusMoa)
    val interval = performance.meanRadiusMoaCi?.let {
        String.format(Locale.ROOT, " (%.2f-%.2f)", it.lower, it.upper)
    }.orEmpty()
    val sessions = if (performance.isSingleSession) {
        "1 session"
    } else {
        "${performance.sessionCount} sessions"
    }
    return "$mean$interval MOA\n$sessions"
}

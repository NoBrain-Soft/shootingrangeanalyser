// TopAppBar is still @ExperimentalMaterial3Api in Material3 1.3. Opted in at file level
// rather than per function: every top-level screen here has an app bar.
@file:OptIn(ExperimentalMaterial3Api::class)

package com.nobrainsoft.rangeanalyser.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nobrainsoft.rangeanalyser.core.model.Profile
import com.nobrainsoft.rangeanalyser.ui.common.CautionBanner
import com.nobrainsoft.rangeanalyser.ui.rangeViewModel
import com.nobrainsoft.rangeanalyser.ui.theme.Dimens

/**
 * The start of everything.
 *
 * A row of profile cards, and under it what was shot recently. Tapping a profile offers live or
 * photo straight away, so getting from a cold start to watching a target is three taps: open the
 * app, tap the profile, tap Live. That target - and not a tidier information architecture - is what
 * this screen is arranged around, because it is used standing at a firing point between strings.
 */
@Composable
fun HomeScreen(
    onStartLive: (Profile) -> Unit,
    onStartPhoto: (Profile) -> Unit,
    onEditProfile: (String) -> Unit,
    onNewProfile: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenProgress: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel = rangeViewModel { HomeViewModel(it.repository, it.openCvAvailable) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Range Analyser") },
                actions = {
                    IconButton(onClick = onOpenProgress, modifier = Modifier.width(Dimens.touchTarget)) {
                        Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = "Progress")
                    }
                    IconButton(onClick = onOpenHistory, modifier = Modifier.width(Dimens.touchTarget)) {
                        Icon(Icons.Default.History, contentDescription = "History")
                    }
                    IconButton(onClick = onOpenSettings, modifier = Modifier.width(Dimens.touchTarget)) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = Dimens.gutter,
                vertical = Dimens.itemSpacing,
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.itemSpacing),
        ) {
            if (!state.openCvAvailable) {
                item {
                    CautionBanner(
                        "Image analysis is unavailable on this device - the OpenCV libraries did " +
                            "not load. Everything else still works, and shots can be placed by hand.",
                    )
                }
            }

            item {
                Text(
                    "Ready to shoot",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (state.profiles.isEmpty()) {
                item { EmptyProfilesCard(onNewProfile) }
            } else {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Dimens.itemSpacing)) {
                        items(state.profiles, key = { it.profile.id }) { summary ->
                            ProfileCard(
                                summary = summary,
                                onLive = {
                                    viewModel.markUsed(summary.profile)
                                    onStartLive(summary.profile)
                                },
                                onPhoto = {
                                    viewModel.markUsed(summary.profile)
                                    onStartPhoto(summary.profile)
                                },
                                onEdit = { onEditProfile(summary.profile.id) },
                            )
                        }
                        item {
                            AddProfileCard(onNewProfile)
                        }
                    }
                }
            }

            if (state.recent.isNotEmpty()) {
                item {
                    Text(
                        "Recent sessions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = Dimens.itemSpacing),
                    )
                }
                items(state.recent, key = { it.id }) { session ->
                    RecentSessionRow(session, onClick = { onOpenSession(session.id) })
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    summary: ProfileSummary,
    onLive: () -> Unit,
    onPhoto: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier.width(260.dp),
        shape = RoundedCornerShape(Dimens.cardCorner),
    ) {
        Column(Modifier.padding(Dimens.gutter)) {
            // The edit affordance used to be nothing but a tap on this text, which nobody found -
            // reported as "I cannot edit my setups". It is a button now.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary.profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).clickable(onClick = onEdit),
                )
                IconButton(onClick = onEdit, modifier = Modifier.size(Dimens.touchTarget)) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit ${summary.profile.name}")
                }
            }
            Text(
                summary.firearmName ?: "No firearm set",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                buildString {
                    append(summary.ammoName ?: "Load not recorded")
                    append("  ·  ")
                    append("${summary.profile.distanceM.toInt()} m")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                summary.targetName ?: "Target not set",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (summary.profile.calibration != null) {
                Text(
                    "Calibration saved",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.itemSpacing),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onLive,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Dimens.touchTarget),
                ) {
                    Icon(Icons.Default.Videocam, contentDescription = null)
                    Text("Live", modifier = Modifier.padding(start = 6.dp))
                }
                FilledTonalButton(
                    onClick = onPhoto,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Dimens.touchTarget),
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Text("Photo", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun AddProfileCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(160.dp)
            .heightIn(min = 180.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(Dimens.cardCorner),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(Dimens.gutter),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("New setup", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun EmptyProfilesCard(onNewProfile: () -> Unit) {
    Card(shape = RoundedCornerShape(Dimens.cardCorner)) {
        Column(Modifier.padding(Dimens.gutterWide)) {
            Text("Set up your first gun", style = MaterialTheme.typography.titleMedium)
            Text(
                "A setup remembers your gun, load, target and distance - and its calibration - so " +
                    "starting a session at the range takes one tap instead of six fields.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(
                onClick = onNewProfile,
                modifier = Modifier
                    .padding(top = Dimens.itemSpacing)
                    .heightIn(min = Dimens.touchTarget),
            ) {
                Text("Create a setup")
            }
        }
    }
}

@Composable
private fun RecentSessionRow(session: RecentSession, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.touchTarget)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Dimens.cardCorner),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(Dimens.gutter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(session.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    session.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(session.headline, style = MaterialTheme.typography.titleMedium)
                Text(
                    session.headlineLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

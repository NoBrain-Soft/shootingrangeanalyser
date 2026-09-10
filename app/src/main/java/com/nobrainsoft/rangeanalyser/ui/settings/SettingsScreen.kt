// TopAppBar is still @ExperimentalMaterial3Api in Material3 1.3. Opted in at file level
// rather than per function: every top-level screen here has an app bar.
@file:OptIn(ExperimentalMaterial3Api::class)

package com.nobrainsoft.rangeanalyser.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.clickable
import androidx.compose.runtime.rememberCoroutineScope
import com.nobrainsoft.rangeanalyser.appContainer
import com.nobrainsoft.rangeanalyser.core.geometry.AngularUnit
import com.nobrainsoft.rangeanalyser.core.geometry.LengthUnit
import com.nobrainsoft.rangeanalyser.data.AppSettings
import com.nobrainsoft.rangeanalyser.data.SpeechVerbosity
import com.nobrainsoft.rangeanalyser.ui.common.ChipPicker
import com.nobrainsoft.rangeanalyser.ui.common.PickerField
import com.nobrainsoft.rangeanalyser.ui.common.enumLabel
import com.nobrainsoft.rangeanalyser.ui.theme.AppTheme
import com.nobrainsoft.rangeanalyser.ui.theme.Dimens
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenFirearms: () -> Unit,
    onOpenAmmo: () -> Unit,
    onOpenTargets: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.appContainer
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(AppSettings())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Dimens.gutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.itemSpacing),
        ) {
            Text("Display", style = MaterialTheme.typography.titleMedium)

            ChipPicker(
                label = "Theme",
                selected = settings.theme,
                options = AppTheme.entries,
                optionLabel = { enumLabel(it.name) },
                onSelect = { scope.launch { container.settings.setTheme(it) } },
            )
            Text(
                "Range mode is built for direct sunlight: pure black and white, larger type and " +
                    "bigger buttons. It looks blunt indoors, which is the trade.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text("Units", style = MaterialTheme.typography.titleMedium)

            ChipPicker(
                label = "Dispersion",
                selected = settings.units.angular,
                options = AngularUnit.entries,
                optionLabel = { it.name },
                onSelect = {
                    scope.launch {
                        container.settings.setUnits(settings.units.copy(angular = it))
                    }
                },
            )

            PickerField(
                label = "Lengths",
                selected = settings.units.length,
                options = LengthUnit.entries,
                optionLabel = { enumLabel(it.name) },
                onSelect = {
                    scope.launch {
                        container.settings.setUnits(settings.units.copy(length = it))
                    }
                },
            )

            HorizontalDivider()
            Text("Live mode", style = MaterialTheme.typography.titleMedium)

            PickerField(
                label = "Spoken shot calls",
                selected = settings.speech,
                options = SpeechVerbosity.entries,
                optionLabel = {
                    when (it) {
                        SpeechVerbosity.OFF -> "Silent"
                        SpeechVerbosity.SCORE_ONLY -> "Score only"
                        SpeechVerbosity.FULL -> "Shot number, score and direction"
                    }
                },
                onSelect = { scope.launch { container.settings.setSpeech(it) } },
            )
            Text(
                "Calls are always shown on screen as well - with ear protection on you may hear " +
                    "none of it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ListItem(
                headlineContent = { Text("Keep the screen on while watching") },
                trailingContent = {
                    Switch(
                        checked = settings.keepScreenOnWhileWatching,
                        onCheckedChange = {
                            scope.launch { container.settings.setKeepScreenOn(it) }
                        },
                    )
                },
            )

            HorizontalDivider()
            Text("Libraries", style = MaterialTheme.typography.titleMedium)

            ListItem(
                headlineContent = { Text("Firearms") },
                modifier = Modifier.clickable(onClick = onOpenFirearms).fillMaxWidth(),
            )
            ListItem(
                headlineContent = { Text("Ammunition") },
                modifier = Modifier.clickable(onClick = onOpenAmmo).fillMaxWidth(),
            )
            ListItem(
                headlineContent = { Text("Targets") },
                modifier = Modifier.clickable(onClick = onOpenTargets).fillMaxWidth(),
            )

            HorizontalDivider()
            Text(
                "This app is a training aid. It is not a competition scoring system, and its " +
                    "measurements depend on how well the target was photographed and calibrated.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

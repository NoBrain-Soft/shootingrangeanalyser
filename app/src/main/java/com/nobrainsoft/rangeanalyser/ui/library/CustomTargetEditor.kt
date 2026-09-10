// TopAppBar is still @ExperimentalMaterial3Api in Material3 1.3. Opted in at file level
// rather than per function: every top-level screen here has an app bar.
@file:OptIn(ExperimentalMaterial3Api::class)

package com.nobrainsoft.rangeanalyser.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nobrainsoft.rangeanalyser.camera.PhotoLoader
import com.nobrainsoft.rangeanalyser.core.target.CustomTargets
import com.nobrainsoft.rangeanalyser.core.target.Discipline
import com.nobrainsoft.rangeanalyser.ui.common.CautionBanner
import com.nobrainsoft.rangeanalyser.ui.common.MarkableImage
import com.nobrainsoft.rangeanalyser.ui.common.PickerField
import com.nobrainsoft.rangeanalyser.ui.common.StepperField
import com.nobrainsoft.rangeanalyser.ui.common.TargetView
import com.nobrainsoft.rangeanalyser.ui.common.LabeledTextField
import com.nobrainsoft.rangeanalyser.ui.common.enumLabel
import com.nobrainsoft.rangeanalyser.ui.rangeViewModel
import com.nobrainsoft.rangeanalyser.ui.theme.Dimens
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Builds a target face the app does not already know.
 *
 * Ranges print their own. Until this existed, a shooter whose club uses its own A3 face had no
 * usable option at all - every built-in target would have scored their shots against the wrong
 * geometry, which is worse than not scoring them.
 *
 * Two ways in, because both are genuinely quicker depending on the target: type the numbers if the
 * face is evenly spaced and you know its outer diameter, or photograph it and tap the rings if you
 * do not. Photographing needs no ruler and no published dimensions - only something of known size
 * in the frame, which the paper itself supplies.
 */
@Composable
fun CustomTargetEditorScreen(targetId: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val viewModel = rangeViewModel(key = "custom-target-${targetId.orEmpty()}") {
        CustomTargetViewModel(it.repository)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(targetId) { viewModel.load(targetId) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        loadPhoto(uri, scope, viewModel, context)
    }

    if (state.measuring) {
        MeasureStepScreen(
            state = state,
            viewModel = viewModel,
            onPickPhoto = {
                runCatching { pickImage.launch("image/*") }
                    .onFailure { viewModel.failed("No app offered a picture to open.") }
            },
            onBack = viewModel::cancelMeasuring,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (targetId == null) "New target" else "Edit target") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.editingExistingId != null) {
                        IconButton(onClick = { viewModel.delete(onDone) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.padding(Dimens.gutter)) {
                state.problem?.let {
                    Text(
                        it.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Button(
                    onClick = { viewModel.save(onDone) },
                    enabled = state.canSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.touchTargetRange),
                ) {
                    Text("Save target")
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.gutter, vertical = Dimens.itemSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimens.itemSpacing),
        ) {
            state.failure?.let { CautionBanner(it) }

            LabeledTextField(
                label = "Name",
                value = state.draft.name,
                onValueChange = { name -> viewModel.update { it.copy(name = name) } },
                supporting = "\"Club 25 m face\"",
            )

            PickerField(
                label = "Discipline",
                selected = state.draft.discipline,
                options = Discipline.entries,
                optionLabel = { enumLabel(it.name) },
                onSelect = { discipline -> viewModel.update { it.copy(discipline = discipline) } },
            )

            Text("Paper size", style = MaterialTheme.typography.titleSmall)
            Text(
                "Only used to sanity-check your measurements and to give the calibrator something " +
                    "of known size to find. A preset is a shortcut, not a limit - anything can be " +
                    "typed underneath.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CustomTargets.Sheet.common.take(4).forEach { sheet ->
                    FilterChip(
                        selected = state.draft.sheetWidthMm == sheet.widthMm &&
                            state.draft.sheetHeightMm == sheet.heightMm,
                        onClick = { viewModel.setSheet(sheet) },
                        label = { Text(sheet.label) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            StepperField(
                label = "Paper width (mm)",
                value = state.draft.sheetWidthMm ?: CustomTargets.Sheet.A4.widthMm,
                step = 1.0,
                range = 20.0..2000.0,
                onValueChange = { width -> viewModel.update { it.copy(sheetWidthMm = width) } },
            )
            StepperField(
                label = "Paper height (mm)",
                value = state.draft.sheetHeightMm ?: CustomTargets.Sheet.A4.heightMm,
                step = 1.0,
                range = 20.0..2000.0,
                onValueChange = { height -> viewModel.update { it.copy(sheetHeightMm = height) } },
            )

            StepperField(
                label = "Usual distance (m)",
                value = state.draft.defaultDistanceM ?: 25.0,
                step = 5.0,
                range = 1.0..1000.0,
                onValueChange = { metres -> viewModel.update { it.copy(defaultDistanceM = metres) } },
            )

            // --- Rings ---------------------------------------------------------------------------

            Text(
                "Scoring rings",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = Dimens.itemSpacing),
            )

            if (state.draft.ringDiametersMm.isEmpty()) {
                Text(
                    "Nothing measured yet. Photograph the target and tap its rings, or enter an " +
                        "evenly spaced face below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                RingList(
                    diameters = state.draft.ringDiametersMm,
                    highestValue = state.draft.highestRingValue,
                    onRemove = viewModel::removeRing,
                )
            }

            Button(
                onClick = viewModel::beginMeasuring,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.touchTargetRange),
            ) {
                Text("Measure from a photo")
            }

            EvenRingEntry(onApply = viewModel::setEvenRings)

            StepperField(
                label = "Highest ring scores",
                value = state.draft.highestRingValue.toDouble(),
                step = 1.0,
                range = 1.0..20.0,
                onValueChange = { value ->
                    viewModel.update { it.copy(highestRingValue = value.toInt()) }
                },
                supporting = "Ten on most faces. Each larger ring counts down from here.",
            )

            // --- Preview -------------------------------------------------------------------------

            state.preview?.let { spec ->
                Text(
                    "How it will be scored",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = Dimens.itemSpacing),
                )
                Text(
                    "If this does not look like the target in front of you, the measurements are " +
                        "wrong - and that is far easier to see here than to catch in a number.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card {
                    Box(Modifier.fillMaxWidth().height(300.dp)) {
                        TargetView(spec = spec, layers = emptyList())
                    }
                }
            }

            Spacer(Modifier.height(Dimens.sectionSpacing))
        }
    }
}

/**
 * The measuring flow: set the scale, mark the centre, tap each ring.
 *
 * One instruction on screen at a time, and nothing advances by itself. This is done standing up,
 * often in bright sun, holding a target in the other hand - so every mark can be moved by tapping
 * near it, the picture zooms, and the step only ends when the shooter says it does.
 */
@Composable
private fun MeasureStepScreen(
    state: CustomTargetState,
    viewModel: CustomTargetViewModel,
    onPickPhoto: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stepTitle(state.step)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.photo != null) {
                        IconButton(onClick = viewModel::rotate) {
                            Icon(Icons.Default.Refresh, contentDescription = "Rotate the photo")
                        }
                    }
                    if (state.marks.isNotEmpty()) {
                        IconButton(onClick = viewModel::undoMark) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.padding(Dimens.gutter)) {
                if (state.step == MeasureStep.SCALE) {
                    Text(
                        "How far apart are those two marks in real life?",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CustomTargets.Sheet.common.take(4).forEach { sheet ->
                            FilterChip(
                                selected = state.referenceLengthMm == sheet.widthMm,
                                onClick = {
                                    viewModel.setReferenceLength(sheet.widthMm)
                                    viewModel.setSheet(sheet)
                                },
                                label = { Text(sheet.label) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    StepperField(
                        label = "Distance (mm)",
                        value = state.referenceLengthMm,
                        step = 1.0,
                        range = 5.0..2000.0,
                        onValueChange = viewModel::setReferenceLength,
                        supporting = "The paper's width, or anything else you can measure",
                    )
                }

                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.itemSpacing),
                ) {
                    if (state.step != MeasureStep.NEED_PHOTO) {
                        OutlinedButton(
                            onClick = viewModel::previousStep,
                            modifier = Modifier.heightIn(min = Dimens.touchTargetRange),
                        ) {
                            Text("Back")
                        }
                    }
                    Button(
                        onClick = viewModel::nextStep,
                        enabled = state.stepComplete,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Dimens.touchTargetRange),
                    ) {
                        Text(
                            when (state.step) {
                                MeasureStep.RINGS ->
                                    "Done - ${state.draft.ringDiametersMm.size} rings"
                                else -> "Next"
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Dimens.gutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.itemSpacing),
        ) {
            state.failure?.let { CautionBanner(it) }

            Text(stepInstruction(state.step), style = MaterialTheme.typography.bodyLarge)

            if (state.step != MeasureStep.NEED_PHOTO && state.photo != null) {
                Text(
                    "Pinch to zoom, drag to move. Tap a mark to pick it up and put it somewhere " +
                        "else.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.step == MeasureStep.RINGS && state.draft.ringDiametersMm.isNotEmpty()) {
                Text(
                    state.draft.ringDiametersMm.sorted().joinToString("  ·  ") {
                        String.format(Locale.ROOT, "%.0f mm", it)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            val photo = state.photo
            if (photo == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (state.busy) {
                        CircularProgressIndicator()
                    } else {
                        Button(
                            onClick = onPickPhoto,
                            modifier = Modifier.heightIn(min = Dimens.touchTargetRange),
                        ) {
                            Text("Choose a photo of the target")
                        }
                    }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    MarkableImage(
                        image = photo,
                        marks = state.marks,
                        onMark = viewModel::addMark,
                        onMoveMark = viewModel::moveMark,
                        maximumMarks = state.marksWanted,
                        connectMarks = state.step == MeasureStep.SCALE,
                        markLabels = when (state.step) {
                            MeasureStep.SCALE -> listOf("one edge", "the other")
                            MeasureStep.CENTRE -> listOf("centre")
                            else -> emptyList()
                        },
                    )
                }
            }
        }
    }
}

private fun stepTitle(step: MeasureStep): String = when (step) {
    MeasureStep.NEED_PHOTO -> "Photograph the target"
    MeasureStep.SCALE -> "Set the scale"
    MeasureStep.CENTRE -> "Mark the centre"
    MeasureStep.RINGS -> "Mark the rings"
}

private fun stepInstruction(step: MeasureStep): String = when (step) {
    MeasureStep.NEED_PHOTO ->
        "Square on, with the whole sheet in the frame."
    MeasureStep.SCALE ->
        "Put a mark on each edge of the paper, then say how far apart they really are."
    MeasureStep.CENTRE ->
        "Put a mark on the exact centre of the target."
    MeasureStep.RINGS ->
        "Tap the outer edge of each scoring ring. Order does not matter."
}

@Composable
private fun RingList(diameters: List<Double>, highestValue: Int, onRemove: (Double) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        diameters.sorted().forEachIndexed { index, diameter ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = Dimens.touchTarget),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Ring ${highestValue - index}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    String.format(Locale.ROOT, "%.1f mm", diameter),
                    style = MaterialTheme.typography.bodyLarge,
                )
                IconButton(onClick = { onRemove(diameter) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove ring")
                }
            }
        }
    }
}

/** The quick path for a face that is evenly spaced and whose outer diameter is known. */
@Composable
private fun EvenRingEntry(onApply: (Double, Int) -> Unit) {
    var outerDiameter by remember { mutableStateOf(200.0) }
    var ringCount by remember { mutableStateOf(10) }

    Card {
        Column(
            Modifier.padding(Dimens.gutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.itemSpacing),
        ) {
            Text("Or enter an evenly spaced face", style = MaterialTheme.typography.titleSmall)
            StepperField(
                label = "Outer ring diameter (mm)",
                value = outerDiameter,
                step = 5.0,
                range = 5.0..1000.0,
                onValueChange = { outerDiameter = it },
            )
            StepperField(
                label = "Number of rings",
                value = ringCount.toDouble(),
                step = 1.0,
                range = 1.0..20.0,
                onValueChange = { ringCount = it.toInt() },
            )
            OutlinedButton(
                onClick = { onApply(outerDiameter, ringCount) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.touchTarget),
            ) {
                Text("Replace rings with these")
            }
        }
    }
}

private fun loadPhoto(
    uri: Uri?,
    scope: kotlinx.coroutines.CoroutineScope,
    viewModel: CustomTargetViewModel,
    context: android.content.Context,
) {
    if (uri == null) return
    viewModel.setBusy(true)
    scope.launch {
        PhotoLoader.loadGreyscale(context, uri)
            .onSuccess { viewModel.setPhoto(it) }
            .onFailure { viewModel.failed(PhotoLoader.reasonFor(it)) }
    }
}

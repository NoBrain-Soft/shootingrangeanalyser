package com.nobrainsoft.rangeanalyser.ui.calibrate

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nobrainsoft.rangeanalyser.core.model.CalibrationMethod
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import com.nobrainsoft.rangeanalyser.ui.common.CautionBanner
import com.nobrainsoft.rangeanalyser.ui.common.ChoiceRow
import com.nobrainsoft.rangeanalyser.ui.common.ConfidenceBadge
import com.nobrainsoft.rangeanalyser.ui.common.MarkableImage
import com.nobrainsoft.rangeanalyser.ui.common.StepperField
import com.nobrainsoft.rangeanalyser.ui.common.WizardScaffold
import com.nobrainsoft.rangeanalyser.ui.theme.Dimens
import com.nobrainsoft.rangeanalyser.vision.calibration.CalibrationAttempt
import com.nobrainsoft.rangeanalyser.vision.calibration.CalibrationEvidence
import com.nobrainsoft.rangeanalyser.vision.calibration.Calibrator

/**
 * Establishes scale, in as few taps as the target allows.
 *
 * The flow is deliberately optimistic: try to measure the target's own printing, show what was
 * found, and let the user accept it. Only when that fails does the wizard ask them to do anything.
 */
@Composable
fun CalibrationWizardScreen(
    viewModel: CalibrationViewModel,
    spec: TargetSpec,
    onCancel: () -> Unit,
    onCalibrated: (CalibrationAttempt) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val stepIndex = when (state.step) {
        WizardStep.AUTOMATIC -> 0
        WizardStep.CHOOSE_METHOD -> 1
        WizardStep.MARK_REFERENCE, WizardStep.MARK_CORNERS, WizardStep.ENTER_DISTANCE -> 2
        WizardStep.VERIFY -> 3
    }

    WizardScaffold(
        title = "Set the scale",
        stepIndex = stepIndex,
        stepCount = 4,
        onBack = {
            when (state.step) {
                WizardStep.AUTOMATIC -> onCancel()
                WizardStep.CHOOSE_METHOD -> viewModel.tryAutomatic()
                WizardStep.VERIFY -> viewModel.goToMethods()
                else -> viewModel.goToMethods()
            }
        },
        bottomBar = {
            WizardActions(state, viewModel, onCalibrated)
        },
    ) { modifier ->
        Column(
            modifier
                .fillMaxSize()
                .padding(horizontal = Dimens.gutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.itemSpacing),
        ) {
            when (state.step) {
                WizardStep.AUTOMATIC -> AutomaticStep(state, spec)
                WizardStep.CHOOSE_METHOD -> MethodStep(spec, viewModel)
                WizardStep.MARK_REFERENCE -> ReferenceStep(state, viewModel)
                WizardStep.MARK_CORNERS -> CornersStep(state, viewModel, spec)
                WizardStep.ENTER_DISTANCE -> DistanceStep(state, viewModel)
                WizardStep.VERIFY -> VerifyStep(state)
            }
        }
    }
}

@Composable
private fun ColumnScope.AutomaticStep(state: CalibrationState, spec: TargetSpec) {
    if (state.busy) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val attempt = state.attempt
    val preview = state.preview

    Text(
        if (attempt != null) "Found the target" else "Could not measure this target",
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        if (attempt != null) {
            "${attempt.describeEvidence()}. Check the outline sits on the target, then carry on."
        } else {
            "Nothing on this face could be measured automatically. Choose another way to set the " +
                "scale - it only takes a moment, and the result is just as good."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    attempt?.let { ConfidenceBadge(it.confidence) }

    if (preview != null) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MarkableImage(
                image = preview,
                marks = emptyList(),
                onMark = {},
                maximumMarks = 0,
                overlay = { scale, origin ->
                    // Draw what the detector latched onto, so the user can see whether it picked
                    // the aiming mark or something else in the picture entirely.
                    when (val found = attempt?.evidence) {
                        is CalibrationEvidence.Ellipse -> {
                            val centre = androidx.compose.ui.geometry.Offset(
                                origin.x + found.fitted.center.x.toFloat() * scale,
                                origin.y + found.fitted.center.y.toFloat() * scale,
                            )
                            drawCircle(
                                color = Color(0xFFFF6B35),
                                radius = (found.fitted.size.width / 2).toFloat() * scale,
                                center = centre,
                                style = Stroke(width = 4f),
                            )
                        }

                        is CalibrationEvidence.Quad -> {
                            val points = found.corners.map {
                                androidx.compose.ui.geometry.Offset(
                                    origin.x + it.x.toFloat() * scale,
                                    origin.y + it.y.toFloat() * scale,
                                )
                            }
                            points.indices.forEach { index ->
                                drawLine(
                                    Color(0xFFFF6B35),
                                    points[index],
                                    points[(index + 1) % points.size],
                                    strokeWidth = 4f,
                                )
                            }
                        }

                        else -> Unit
                    }
                },
            )
        }
    }

    if (attempt != null && attempt.confidence < Calibrator.LOW_CONFIDENCE) {
        CautionBanner(
            "This measurement is not a confident one - the target is small in frame or seen at a " +
                "steep angle. Moving closer or squaring up would improve it.",
        )
    }
}

@Composable
private fun ColumnScope.MethodStep(spec: TargetSpec, viewModel: CalibrationViewModel) {
    Text("How should we measure it?", style = MaterialTheme.typography.titleMedium)

    val methods = buildList {
        if (spec.blackDiameterMm != null) {
            add(
                CalibrationMethod.RING_GEOMETRY to
                    ("Use the printed target" to "Best when the aiming mark is clearly visible"),
            )
        }
        if (spec.sheetWidthMm != null) {
            add(
                CalibrationMethod.PAPER_QUAD to
                    ("Trace the paper edges" to "Handles a photo taken from an angle"),
            )
        }
        add(
            CalibrationMethod.MANUAL_REFERENCE to
                ("Measure a known distance" to "Works on anything - mark two points and say how far apart they are"),
        )
        add(
            CalibrationMethod.OPTICAL_DISTANCE to
                ("I know the distance to the target" to "Uses the camera lens; needs the target square on"),
        )
    }

    methods.forEach { (method, text) ->
        ChoiceRow(
            label = text.first,
            description = text.second,
            selected = false,
            onClick = { viewModel.chooseMethod(method) },
        )
    }
}

@Composable
private fun ColumnScope.ReferenceStep(state: CalibrationState, viewModel: CalibrationViewModel) {
    Text("Mark a known distance", style = MaterialTheme.typography.titleMedium)
    Text(
        "Tap the two ends of something you can measure - the edges of the paper, a ruler laid on " +
            "the target, anything of a known size.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    state.preview?.let { preview ->
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MarkableImage(
                image = preview,
                marks = state.marks,
                markLabels = listOf("start", "end"),
                maximumMarks = 2,
                onMark = viewModel::addMark,
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        viewModel.referencePresets.take(3).forEach { preset ->
            OutlinedButton(
                onClick = { viewModel.setReferenceLength(preset.millimetres) },
                modifier = Modifier.weight(1f),
            ) {
                Text(preset.label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    StepperField(
        label = "Real distance between the marks",
        value = state.referenceLengthMm,
        step = 1.0,
        range = 1.0..5000.0,
        suffix = " mm",
        onValueChange = viewModel::setReferenceLength,
    )

    if (state.marks.isNotEmpty()) {
        TextButton(onClick = viewModel::undoMark) { Text("Undo last mark") }
    }
}

@Composable
private fun ColumnScope.CornersStep(
    state: CalibrationState,
    viewModel: CalibrationViewModel,
    spec: TargetSpec,
) {
    Text("Tap the four corners", style = MaterialTheme.typography.titleMedium)
    Text(
        "Go round the sheet in order. Four corners of a known size is enough to undo the camera " +
            "angle completely, so this is the best option for a photo taken from the side.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    state.preview?.let { preview ->
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MarkableImage(
                image = preview,
                marks = state.marks,
                markLabels = listOf("1", "2", "3", "4"),
                maximumMarks = 4,
                onMark = viewModel::addMark,
            )
        }
    }

    Text(
        "Sheet: ${spec.sheetWidthMm?.toInt() ?: 210} x ${spec.sheetHeightMm?.toInt() ?: 297} mm",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (state.marks.isNotEmpty()) {
        TextButton(onClick = viewModel::undoMark) { Text("Undo last corner") }
    }
}

@Composable
private fun ColumnScope.DistanceStep(state: CalibrationState, viewModel: CalibrationViewModel) {
    Text("How far away is the target?", style = MaterialTheme.typography.titleMedium)
    Text(
        "The app works out the scale from the lens and the distance. It assumes the target is " +
            "square on to the camera, so it is best on a marked range where you know the distance.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    StepperField(
        label = "Distance to target",
        value = state.targetDistanceM,
        step = 5.0,
        range = 1.0..1000.0,
        suffix = " m",
        onValueChange = viewModel::setTargetDistance,
    )

    state.preview?.let {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ColumnScope.VerifyStep(state: CalibrationState) {
    Text("Does this line up?", style = MaterialTheme.typography.titleMedium)
    Text(
        "The rings the app expects are drawn over your photo. If they sit on the printed rings, " +
            "the scale is right. If they float somewhere else, go back and try another method.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    state.disagreement?.let { disagreement ->
        CautionBanner(
            "Two ways of measuring this target disagree by " +
                "${disagreement.percent.toInt()}%. The more reliable one is being used, but it is " +
                "worth checking this overlay carefully before trusting the numbers.",
        )
    }

    val verification = state.verification
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (verification == null) {
            CircularProgressIndicator()
        } else {
            Image(
                bitmap = verification,
                contentDescription = "Expected rings drawn over the photograph",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    state.attempt?.let {
        Text(
            "One pixel is ${"%.3f".format(it.millimetresPerPixel)} mm" +
                if (it.correctsPerspective) ", camera angle corrected" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WizardActions(
    state: CalibrationState,
    viewModel: CalibrationViewModel,
    onCalibrated: (CalibrationAttempt) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(Dimens.gutter),
        horizontalArrangement = Arrangement.spacedBy(Dimens.itemSpacing),
    ) {
        when (state.step) {
            WizardStep.AUTOMATIC -> {
                OutlinedButton(
                    onClick = viewModel::goToMethods,
                    modifier = Modifier.weight(1f).heightIn(min = Dimens.touchTargetRange),
                ) {
                    Text(if (state.attempt == null) "Choose a method" else "Not right")
                }
                if (state.attempt != null) {
                    Button(
                        onClick = viewModel::acceptAutomatic,
                        modifier = Modifier.weight(1f).heightIn(min = Dimens.touchTargetRange),
                    ) {
                        Text("Looks good")
                    }
                }
            }

            WizardStep.MARK_REFERENCE -> Button(
                onClick = viewModel::applyMarks,
                enabled = state.marks.size >= 2,
                modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.touchTargetRange),
            ) {
                Text(if (state.marks.size < 2) "Mark both ends" else "Use this measurement")
            }

            WizardStep.MARK_CORNERS -> Button(
                onClick = viewModel::applyMarks,
                enabled = state.marks.size >= 4,
                modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.touchTargetRange),
            ) {
                Text("Corners marked (${state.marks.size}/4)")
            }

            WizardStep.ENTER_DISTANCE -> Button(
                onClick = viewModel::applyMarks,
                modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.touchTargetRange),
            ) {
                Text("Use this distance")
            }

            WizardStep.VERIFY -> {
                OutlinedButton(
                    onClick = viewModel::goToMethods,
                    modifier = Modifier.weight(1f).heightIn(min = Dimens.touchTargetRange),
                ) {
                    Text("Try again")
                }
                Button(
                    onClick = { state.attempt?.let(onCalibrated) },
                    enabled = state.canFinish,
                    modifier = Modifier.weight(1f).heightIn(min = Dimens.touchTargetRange),
                ) {
                    Text("Use this")
                }
            }

            WizardStep.CHOOSE_METHOD -> Unit
        }
    }
}

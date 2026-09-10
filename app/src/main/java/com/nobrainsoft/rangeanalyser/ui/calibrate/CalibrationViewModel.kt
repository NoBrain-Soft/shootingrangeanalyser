package com.nobrainsoft.rangeanalyser.ui.calibrate

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nobrainsoft.rangeanalyser.camera.ImageBridge
import com.nobrainsoft.rangeanalyser.core.model.Caliber
import com.nobrainsoft.rangeanalyser.core.model.CalibrationMethod
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import com.nobrainsoft.rangeanalyser.ui.common.ImageMark
import com.nobrainsoft.rangeanalyser.vision.calibration.CalibrationAttempt
import com.nobrainsoft.rangeanalyser.vision.calibration.CalibrationDisagreement
import com.nobrainsoft.rangeanalyser.vision.calibration.CalibrationEvidence
import com.nobrainsoft.rangeanalyser.vision.calibration.Calibrator
import com.nobrainsoft.rangeanalyser.vision.calibration.Rectifier
import com.nobrainsoft.rangeanalyser.vision.detect.ExpectedTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import org.opencv.core.Point

/** Where the wizard is. */
enum class WizardStep {
    /** Try the target's own printing first; most of the time this is the only step. */
    AUTOMATIC,
    CHOOSE_METHOD,
    MARK_REFERENCE,
    MARK_CORNERS,
    ENTER_DISTANCE,

    /** Draw the expected rings back over the photo, so the user can see it is right. */
    VERIFY,
}

/** Paper sizes people actually have, so the reference step is usually a tap rather than a measure. */
data class ReferencePreset(val label: String, val millimetres: Double)

data class CalibrationState(
    val step: WizardStep = WizardStep.AUTOMATIC,
    val busy: Boolean = false,
    val preview: ImageBitmap? = null,
    val attempt: CalibrationAttempt? = null,
    val alternatives: List<CalibrationAttempt> = emptyList(),
    val disagreement: CalibrationDisagreement? = null,
    val automaticFailed: Boolean = false,
    val marks: List<ImageMark> = emptyList(),
    val referenceLengthMm: Double = 210.0,
    val targetDistanceM: Double = 25.0,
    val focalLengthMm: Double = 26.0,
    val pixelPitchMm: Double = 0.0014,
    val zoomRatio: Double = 1.0,
    /** The rectified view with the expected rings drawn on, for the verify step. */
    val verification: ImageBitmap? = null,
) {
    val canFinish: Boolean get() = attempt != null
}

/**
 * Drives the calibration wizard.
 *
 * The order of the steps is the point. Automatic detection runs first and, when it works, the whole
 * job is one tap with no numbers typed. Only when it fails does the user see a method chooser - and
 * the last step is always visual: the app draws where it thinks the rings are, and the user says
 * whether they line up. A confidence percentage cannot be checked by eye; a ring that sits on the
 * printed ring can.
 */
class CalibrationViewModel : ViewModel() {

    private val _state = MutableStateFlow(CalibrationState())
    val state: StateFlow<CalibrationState> = _state.asStateFlow()

    /** The photograph being calibrated. Owned here and released when the wizard closes. */
    private var image: Mat? = null
    private var spec: TargetSpec? = null
    private var caliber: Caliber? = null

    val referencePresets = listOf(
        ReferencePreset("A4 width", 210.0),
        ReferencePreset("A4 height", 297.0),
        ReferencePreset("Letter width", 215.9),
        ReferencePreset("Letter height", 279.4),
        ReferencePreset("A3 width", 297.0),
        ReferencePreset("30 cm rule", 300.0),
    )

    fun begin(greyscale: Mat, spec: TargetSpec, caliber: Caliber?) {
        image?.release()
        image = greyscale.clone()
        this.spec = spec
        this.caliber = caliber

        _state.value = CalibrationState(
            preview = ImageBridge.toImageBitmap(greyscale),
            targetDistanceM = spec.defaultDistanceM ?: 25.0,
            referenceLengthMm = spec.sheetWidthMm ?: 210.0,
        )
        tryAutomatic()
    }

    fun tryAutomatic() {
        val source = image ?: return
        val face = spec ?: return

        viewModelScope.launch {
            _state.update { it.copy(busy = true) }

            val attempts = withContext(Dispatchers.Default) {
                buildList {
                    Calibrator.fromRingGeometry(source, face)?.let { add(it) }
                    Calibrator.fromPaperQuad(source, face)?.let { add(it) }
                }
            }
            val outcome = Calibrator.reconcile(attempts)

            _state.update {
                it.copy(
                    busy = false,
                    attempt = outcome.best,
                    alternatives = outcome.alternatives,
                    disagreement = outcome.disagreement,
                    automaticFailed = outcome.best == null,
                    step = if (outcome.best == null) WizardStep.CHOOSE_METHOD else WizardStep.AUTOMATIC,
                )
            }
            if (outcome.best != null) buildVerification()
        }
    }

    fun chooseMethod(method: CalibrationMethod) {
        _state.update {
            it.copy(
                marks = emptyList(),
                step = when (method) {
                    CalibrationMethod.RING_GEOMETRY -> WizardStep.AUTOMATIC
                    CalibrationMethod.PAPER_QUAD -> WizardStep.MARK_CORNERS
                    CalibrationMethod.MANUAL_REFERENCE -> WizardStep.MARK_REFERENCE
                    CalibrationMethod.BULLET_CALIBER -> WizardStep.MARK_REFERENCE
                    CalibrationMethod.OPTICAL_DISTANCE -> WizardStep.ENTER_DISTANCE
                },
            )
        }
        if (method == CalibrationMethod.RING_GEOMETRY) tryAutomatic()
    }

    fun goToMethods() {
        _state.update { it.copy(step = WizardStep.CHOOSE_METHOD, marks = emptyList()) }
    }

    fun addMark(mark: ImageMark) {
        _state.update { it.copy(marks = it.marks + mark) }
    }

    fun undoMark() {
        _state.update { it.copy(marks = it.marks.dropLast(1)) }
    }

    fun setReferenceLength(millimetres: Double) {
        _state.update { it.copy(referenceLengthMm = millimetres) }
    }

    fun setTargetDistance(metres: Double) {
        _state.update { it.copy(targetDistanceM = metres) }
    }

    fun setOptics(focalLengthMm: Double, pixelPitchMm: Double, zoomRatio: Double) {
        _state.update {
            it.copy(
                focalLengthMm = focalLengthMm,
                pixelPitchMm = pixelPitchMm,
                zoomRatio = zoomRatio,
            )
        }
    }

    /** Turns the marks the user placed into a calibration. */
    fun applyMarks() {
        val current = _state.value
        val source = image ?: return
        val centre = Point(source.cols() / 2.0, source.rows() / 2.0)

        val attempt = when (current.step) {
            WizardStep.MARK_REFERENCE -> {
                if (current.marks.size < 2) return
                Calibrator.fromReferenceLength(
                    from = current.marks[0].toPoint(),
                    to = current.marks[1].toPoint(),
                    realDistanceMm = current.referenceLengthMm,
                    // Prefer a detected aiming mark for the origin; fall back to the frame centre.
                    targetCentre = detectedCentre() ?: centre,
                )
            }

            WizardStep.MARK_CORNERS -> {
                if (current.marks.size < 4) return
                val sheet = spec
                Calibrator.fromCorners(
                    corners = current.marks.map { it.toPoint() },
                    widthMm = sheet?.sheetWidthMm ?: current.referenceLengthMm,
                    heightMm = sheet?.sheetHeightMm ?: current.referenceLengthMm,
                )
            }

            WizardStep.ENTER_DISTANCE -> Calibrator.fromOptics(
                distanceM = current.targetDistanceM,
                focalLengthMm = current.focalLengthMm,
                pixelPitchMm = current.pixelPitchMm,
                zoomRatio = current.zoomRatio,
                targetCentre = detectedCentre() ?: centre,
            )

            else -> null
        } ?: return

        // Cross-check against whatever else can be measured, and surface a disagreement rather
        // than letting one method quietly win.
        val others = buildList {
            spec?.let { face -> Calibrator.fromRingGeometry(source, face)?.let { add(it) } }
        }
        val outcome = Calibrator.reconcile(listOf(attempt) + others)

        _state.update {
            it.copy(
                attempt = attempt,
                alternatives = outcome.all.filter { candidate -> candidate !== attempt },
                disagreement = outcome.disagreement,
                step = WizardStep.VERIFY,
            )
        }
        buildVerification()
    }

    fun acceptAutomatic() {
        _state.update { it.copy(step = WizardStep.VERIFY) }
        buildVerification()
    }

    /**
     * Renders the target as the app believes it to be, over the rectified photograph.
     *
     * This is the whole verification step. If the drawn rings sit on the printed rings, the
     * calibration is right; if they float somewhere else, it is wrong - and that is visible in a
     * glance, without the user having to interpret a number.
     */
    private fun buildVerification() {
        val source = image ?: return
        val face = spec ?: return
        val transform = _state.value.attempt?.transform ?: return

        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                val rectified = Rectifier.rectify(source, transform, face) ?: return@withContext null
                val levels = ExpectedTarget.estimateLevels(rectified, face)
                val expected = ExpectedTarget.render(rectified, face, levels)

                // Half the real picture, half the model: misalignment shows up as a doubled ring.
                val blended = Mat()
                org.opencv.core.Core.addWeighted(rectified.image, 0.6, expected, 0.4, 0.0, blended)
                expected.release()

                val result = ImageBridge.toImageBitmap(blended)
                blended.release()
                rectified.release()
                result
            }
            _state.update { it.copy(verification = bitmap) }
        }
    }

    private fun detectedCentre(): Point? {
        val source = image ?: return null
        return Calibrator.findTargetCentre(source)
    }

    override fun onCleared() {
        image?.release()
        image = null
        super.onCleared()
    }

    private fun ImageMark.toPoint() = Point(x, y)

    private fun MutableStateFlow<CalibrationState>.update(
        transform: (CalibrationState) -> CalibrationState,
    ) {
        value = transform(value)
    }
}

/** Where a calibration attempt found its reference, for drawing over the preview. */
fun CalibrationAttempt.describeEvidence(): String = when (val found = evidence) {
    is CalibrationEvidence.Ellipse ->
        "Measured the ${found.knownDiameterMm.toInt()} mm aiming mark"

    is CalibrationEvidence.Quad ->
        "Measured the sheet, ${found.widthMm.toInt()} x ${found.heightMm.toInt()} mm"

    is CalibrationEvidence.Reference ->
        "Using your ${found.realDistanceMm.toInt()} mm reference"

    is CalibrationEvidence.Holes ->
        "Estimated from ${found.diameterPx.size} bullet holes"

    null -> "Using the distance and lens"
}

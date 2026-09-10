package com.nobrainsoft.rangeanalyser.ui.library

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nobrainsoft.rangeanalyser.camera.ImageBridge
import com.nobrainsoft.rangeanalyser.core.target.CustomTargets
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import com.nobrainsoft.rangeanalyser.data.RangeRepository
import com.nobrainsoft.rangeanalyser.ui.common.ImageMark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.opencv.core.Core
import org.opencv.core.Mat
import java.util.UUID

/** Which part of measuring the shooter is on. */
enum class MeasureStep {
    /** Nothing to measure from yet. */
    NEED_PHOTO,

    /** Two marks a known distance apart, to learn the scale. */
    SCALE,

    /** One mark on the middle of the target. */
    CENTRE,

    /** One mark per scoring ring. */
    RINGS,
}

data class CustomTargetState(
    val draft: CustomTargets.Draft = CustomTargets.Draft(),
    val measuring: Boolean = false,
    val step: MeasureStep = MeasureStep.NEED_PHOTO,
    val photo: ImageBitmap? = null,
    /** Marks belonging to the step on screen. */
    val marks: List<ImageMark> = emptyList(),
    val referenceLengthMm: Double = CustomTargets.Sheet.A4.widthMm,
    val busy: Boolean = false,
    val failure: String? = null,
    val editingExistingId: String? = null,
) {
    val problem: CustomTargets.Problem? get() = CustomTargets.problemWith(draft)

    val canSave: Boolean get() = problem == null

    /** Marks the current step still wants before it can be finished. */
    val marksWanted: Int
        get() = when (step) {
            MeasureStep.NEED_PHOTO -> 0
            MeasureStep.SCALE -> 2
            MeasureStep.CENTRE -> 1
            MeasureStep.RINGS -> Int.MAX_VALUE
        }

    val stepComplete: Boolean
        get() = when (step) {
            MeasureStep.NEED_PHOTO -> photo != null
            MeasureStep.SCALE -> marks.size >= 2
            MeasureStep.CENTRE -> marks.size >= 1
            MeasureStep.RINGS -> marks.isNotEmpty()
        }

    /** Built only when valid, so the preview can never show a face that would not save. */
    val preview: TargetSpec?
        get() = if (canSave) {
            CustomTargets.build(editingExistingId ?: "preview", draft).getOrNull()
        } else {
            null
        }
}

/**
 * Backs the custom target editor.
 *
 * Marks are kept per step and nothing advances on its own. An earlier version committed each step as
 * soon as it had enough taps, which made the second edge of the paper impossible to place well and
 * the first impossible to correct at all - the step was gone before you could look at it. Every step
 * now waits for the shooter to say it is right.
 */
class CustomTargetViewModel(private val repository: RangeRepository) : ViewModel() {

    private val _state = MutableStateFlow(CustomTargetState())
    val state: StateFlow<CustomTargetState> = _state.asStateFlow()

    /** The photograph being measured. Owned here and released with the view model. */
    private var image: Mat? = null

    private var scaleMarks: List<ImageMark> = emptyList()
    private var centreMark: ImageMark? = null
    private var ringMarks: List<ImageMark> = emptyList()

    fun load(targetId: String?) {
        if (targetId == null) return
        viewModelScope.launch {
            val existing = repository.findTarget(targetId) ?: return@launch
            val rings = existing.rings ?: return@launch
            _state.value = _state.value.copy(
                editingExistingId = existing.id,
                draft = CustomTargets.Draft(
                    name = existing.name,
                    discipline = existing.discipline,
                    sheetWidthMm = existing.sheetWidthMm,
                    sheetHeightMm = existing.sheetHeightMm,
                    ringDiametersMm = rings.rings.map { it.diameterMm },
                    highestRingValue = rings.highestValue,
                    blackDiameterMm = existing.blackDiameterMm,
                    innerRingDiameterMm = rings.innerRingDiameterMm,
                    defaultDistanceM = existing.defaultDistanceM,
                ),
            )
        }
    }

    fun update(transform: (CustomTargets.Draft) -> CustomTargets.Draft) {
        _state.value = _state.value.copy(draft = transform(_state.value.draft), failure = null)
    }

    fun setSheet(sheet: CustomTargets.Sheet) = update {
        it.copy(sheetWidthMm = sheet.widthMm, sheetHeightMm = sheet.heightMm)
    }

    fun setReferenceLength(millimetres: Double) {
        _state.value = _state.value.copy(referenceLengthMm = millimetres)
        if (_state.value.step == MeasureStep.RINGS) recomputeRings()
    }

    fun setEvenRings(outerDiameterMm: Double, ringCount: Int) = update {
        it.copy(ringDiametersMm = CustomTargets.evenlySpaced(outerDiameterMm, ringCount))
    }

    fun removeRing(diameterMm: Double) = update {
        it.copy(ringDiametersMm = it.ringDiametersMm.filterNot { existing -> existing == diameterMm })
    }

    // --- Measuring from a photograph --------------------------------------------------------------

    fun beginMeasuring() {
        _state.value = _state.value.copy(
            measuring = true,
            step = if (image == null) MeasureStep.NEED_PHOTO else MeasureStep.SCALE,
            marks = scaleMarks,
        )
    }

    fun cancelMeasuring() {
        _state.value = _state.value.copy(measuring = false)
    }

    fun setBusy(busy: Boolean) {
        _state.value = _state.value.copy(busy = busy, failure = null)
    }

    fun failed(message: String) {
        _state.value = _state.value.copy(busy = false, failure = message)
    }

    /** Takes ownership of a greyscale photograph of the target. */
    fun setPhoto(greyscale: Mat) {
        image?.release()
        image = greyscale
        scaleMarks = emptyList()
        centreMark = null
        ringMarks = emptyList()
        _state.value = _state.value.copy(
            photo = ImageBridge.toImageBitmap(greyscale),
            step = MeasureStep.SCALE,
            marks = emptyList(),
            busy = false,
            failure = null,
        )
    }

    /**
     * Turns the photograph a quarter turn.
     *
     * Phone cameras are inconsistent about which way up they record a picture, and a target lying
     * on its side is hard to tap accurately. The marks are rotated with the image rather than
     * discarded, so this can be used after measuring has begun.
     */
    fun rotate() {
        val source = image ?: return
        val height = source.rows()

        val rotated = Mat()
        Core.rotate(source, rotated, Core.ROTATE_90_CLOCKWISE)
        source.release()
        image = rotated

        // A quarter turn clockwise sends (x, y) to (height - 1 - y, x).
        fun turn(mark: ImageMark) = ImageMark(x = (height - 1) - mark.y, y = mark.x)
        scaleMarks = scaleMarks.map(::turn)
        centreMark = centreMark?.let(::turn)
        ringMarks = ringMarks.map(::turn)

        _state.value = _state.value.copy(
            photo = ImageBridge.toImageBitmap(rotated),
            marks = marksFor(_state.value.step),
        )
    }

    /** Places another mark, if the step still wants one. */
    fun addMark(mark: ImageMark) {
        val current = _state.value
        if (current.marks.size >= current.marksWanted) return
        setMarks(current.step, current.marks + mark)
    }

    /** Moves an existing mark, which is how a misplaced one gets corrected. */
    fun moveMark(index: Int, mark: ImageMark) {
        val current = _state.value
        if (index !in current.marks.indices) return
        setMarks(current.step, current.marks.toMutableList().also { it[index] = mark })
    }

    fun undoMark() {
        val current = _state.value
        setMarks(current.step, current.marks.dropLast(1))
    }

    /**
     * Accepts the current step and moves on.
     *
     * The scale step is the only one that can be refused: two marks in the same place carry no
     * measurement, and every ring diameter after it would inherit the nonsense.
     */
    fun nextStep() {
        val current = _state.value
        when (current.step) {
            MeasureStep.NEED_PHOTO -> if (image != null) {
                _state.value = current.copy(step = MeasureStep.SCALE, marks = scaleMarks)
            }

            MeasureStep.SCALE -> {
                if (millimetresPerPixel() == null) {
                    _state.value = current.copy(
                        failure = "Those two marks are almost on top of each other. Put one on " +
                            "each edge of the paper.",
                    )
                    return
                }
                _state.value = current.copy(
                    step = MeasureStep.CENTRE,
                    marks = listOfNotNull(centreMark),
                    failure = null,
                )
            }

            MeasureStep.CENTRE -> {
                _state.value = current.copy(step = MeasureStep.RINGS, marks = ringMarks)
                recomputeRings()
            }

            MeasureStep.RINGS -> _state.value = current.copy(measuring = false)
        }
    }

    fun previousStep() {
        val current = _state.value
        val back = when (current.step) {
            MeasureStep.NEED_PHOTO -> null
            MeasureStep.SCALE -> MeasureStep.NEED_PHOTO
            MeasureStep.CENTRE -> MeasureStep.SCALE
            MeasureStep.RINGS -> MeasureStep.CENTRE
        } ?: return
        _state.value = current.copy(step = back, marks = marksFor(back), failure = null)
    }

    fun restartMeasuring() {
        scaleMarks = emptyList()
        centreMark = null
        ringMarks = emptyList()
        _state.value = _state.value.copy(
            step = if (image == null) MeasureStep.NEED_PHOTO else MeasureStep.SCALE,
            marks = emptyList(),
            draft = _state.value.draft.copy(ringDiametersMm = emptyList()),
            failure = null,
        )
    }

    fun save(onSaved: () -> Unit) {
        val current = _state.value
        val id = current.editingExistingId ?: "custom-${UUID.randomUUID()}"
        CustomTargets.build(id, current.draft)
            .onSuccess { spec ->
                viewModelScope.launch {
                    repository.saveCustomTarget(spec, System.currentTimeMillis())
                    onSaved()
                }
            }
            .onFailure { failed(it.message ?: "That target could not be saved.") }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = _state.value.editingExistingId ?: return
        viewModelScope.launch {
            repository.findTarget(id)?.let { repository.deleteCustomTarget(it) }
            onDeleted()
        }
    }

    override fun onCleared() {
        image?.release()
        image = null
        super.onCleared()
    }

    // --- Internals --------------------------------------------------------------------------------

    private fun marksFor(step: MeasureStep): List<ImageMark> = when (step) {
        MeasureStep.NEED_PHOTO -> emptyList()
        MeasureStep.SCALE -> scaleMarks
        MeasureStep.CENTRE -> listOfNotNull(centreMark)
        MeasureStep.RINGS -> ringMarks
    }

    private fun setMarks(step: MeasureStep, marks: List<ImageMark>) {
        when (step) {
            MeasureStep.NEED_PHOTO -> return
            MeasureStep.SCALE -> scaleMarks = marks
            MeasureStep.CENTRE -> centreMark = marks.firstOrNull()
            MeasureStep.RINGS -> ringMarks = marks
        }
        _state.value = _state.value.copy(marks = marks, failure = null)
        if (step == MeasureStep.RINGS) recomputeRings()
    }

    private fun millimetresPerPixel(): Double? {
        if (scaleMarks.size < 2) return null
        return CustomTargets.scaleFrom(
            scaleMarks[0].x, scaleMarks[0].y,
            scaleMarks[1].x, scaleMarks[1].y,
            _state.value.referenceLengthMm,
        )
    }

    /**
     * Ring diameters are derived from the marks rather than accumulated as they are placed.
     *
     * That is what makes a ring correctable: moving its mark re-measures it, and nothing has to
     * remember which tap produced which number.
     */
    private fun recomputeRings() {
        val scale = millimetresPerPixel() ?: return
        val centre = centreMark ?: return
        val diameters = ringMarks.map {
            CustomTargets.diameterFromTap(centre.x, centre.y, it.x, it.y, scale)
        }
        _state.value = _state.value.copy(
            draft = _state.value.draft.copy(ringDiametersMm = diameters),
        )
    }
}

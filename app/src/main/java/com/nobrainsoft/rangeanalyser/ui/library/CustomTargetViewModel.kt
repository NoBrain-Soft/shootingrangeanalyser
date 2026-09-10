package com.nobrainsoft.rangeanalyser.ui.library

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nobrainsoft.rangeanalyser.camera.ImageBridge
import com.nobrainsoft.rangeanalyser.core.target.CustomTargets
import com.nobrainsoft.rangeanalyser.core.target.Discipline
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import com.nobrainsoft.rangeanalyser.data.RangeRepository
import com.nobrainsoft.rangeanalyser.ui.common.ImageMark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.opencv.core.Mat
import java.util.UUID

/** Which part of measuring the shooter is on. */
enum class MeasureStep {
    /** Nothing to measure from yet. */
    NEED_PHOTO,

    /** Two taps a known distance apart, to learn the scale. */
    SCALE,

    /** One tap on the middle of the target. */
    CENTRE,

    /** One tap per scoring ring. */
    RINGS,
}

data class CustomTargetState(
    val draft: CustomTargets.Draft = CustomTargets.Draft(),
    val measuring: Boolean = false,
    val step: MeasureStep = MeasureStep.NEED_PHOTO,
    val photo: ImageBitmap? = null,
    val marks: List<ImageMark> = emptyList(),
    val referenceLengthMm: Double = CustomTargets.Sheet.A3.widthMm,
    val busy: Boolean = false,
    val failure: String? = null,
    val editingExistingId: String? = null,
) {
    val problem: CustomTargets.Problem? get() = CustomTargets.problemWith(draft)

    val canSave: Boolean get() = problem == null

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
 * Measuring is kept as a list of taps rather than as running totals, so every step can be undone.
 * Getting a ring wrong three taps ago should cost one undo, not a restart - people are doing this
 * standing at a range with the target in one hand.
 */
class CustomTargetViewModel(private val repository: RangeRepository) : ViewModel() {

    private val _state = MutableStateFlow(CustomTargetState())
    val state: StateFlow<CustomTargetState> = _state.asStateFlow()

    /** The photograph being measured. Owned here and released with the view model. */
    private var image: Mat? = null

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
            marks = emptyList(),
        )
    }

    fun cancelMeasuring() {
        _state.value = _state.value.copy(measuring = false, marks = emptyList())
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
        _state.value = _state.value.copy(
            photo = ImageBridge.toImageBitmap(greyscale),
            step = MeasureStep.SCALE,
            marks = emptyList(),
            busy = false,
            failure = null,
        )
    }

    /**
     * Records a tap, and advances when the current step has what it needs.
     *
     * The ring step never completes on its own: only the shooter knows how many rings their target
     * has, so it keeps accepting taps until they say they are done.
     */
    fun addMark(mark: ImageMark) {
        val current = _state.value
        val marks = current.marks + mark

        when (current.step) {
            MeasureStep.NEED_PHOTO -> return

            MeasureStep.SCALE -> {
                if (marks.size < 2) {
                    _state.value = current.copy(marks = marks)
                    return
                }
                val scale = CustomTargets.scaleFrom(
                    marks[0].x, marks[0].y, marks[1].x, marks[1].y, current.referenceLengthMm,
                )
                if (scale == null) {
                    _state.value = current.copy(
                        marks = emptyList(),
                        failure = "Those two taps were almost on top of each other. Tap the two " +
                            "opposite edges of the sheet.",
                    )
                    return
                }
                millimetresPerPixel = scale
                _state.value = current.copy(step = MeasureStep.CENTRE, marks = emptyList(), failure = null)
            }

            MeasureStep.CENTRE -> {
                centre = mark
                _state.value = current.copy(step = MeasureStep.RINGS, marks = emptyList())
            }

            MeasureStep.RINGS -> {
                val origin = centre ?: return
                val scale = millimetresPerPixel ?: return
                val diameter = CustomTargets.diameterFromTap(
                    origin.x, origin.y, mark.x, mark.y, scale,
                )
                _state.value = current.copy(
                    marks = marks,
                    draft = current.draft.copy(
                        ringDiametersMm = current.draft.ringDiametersMm + diameter,
                    ),
                )
            }
        }
    }

    fun undoMark() {
        val current = _state.value
        if (current.step == MeasureStep.RINGS && current.marks.isNotEmpty()) {
            _state.value = current.copy(
                marks = current.marks.dropLast(1),
                draft = current.draft.copy(
                    ringDiametersMm = current.draft.ringDiametersMm.dropLast(1),
                ),
            )
            return
        }
        _state.value = current.copy(marks = current.marks.dropLast(1))
    }

    /** Leaves the measuring flow, keeping whatever rings were measured. */
    fun finishMeasuring() {
        _state.value = _state.value.copy(measuring = false, marks = emptyList())
    }

    fun restartMeasuring() {
        centre = null
        millimetresPerPixel = null
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

    private var centre: ImageMark? = null
    private var millimetresPerPixel: Double? = null
}

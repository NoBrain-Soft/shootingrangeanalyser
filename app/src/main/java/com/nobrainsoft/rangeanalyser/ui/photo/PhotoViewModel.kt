package com.nobrainsoft.rangeanalyser.ui.photo

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nobrainsoft.rangeanalyser.camera.ImageBridge
import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.model.Caliber
import com.nobrainsoft.rangeanalyser.core.model.Profile
import com.nobrainsoft.rangeanalyser.core.model.SavedCalibration
import com.nobrainsoft.rangeanalyser.core.model.Session
import com.nobrainsoft.rangeanalyser.core.model.SessionMode
import com.nobrainsoft.rangeanalyser.core.model.Shot
import com.nobrainsoft.rangeanalyser.core.model.ShotSource
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import com.nobrainsoft.rangeanalyser.data.RangeRepository
import com.nobrainsoft.rangeanalyser.vision.calibration.CalibrationAttempt
import com.nobrainsoft.rangeanalyser.vision.calibration.Rectifier
import com.nobrainsoft.rangeanalyser.vision.detect.DetectionResult
import com.nobrainsoft.rangeanalyser.vision.detect.HoleDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import java.util.UUID

enum class PhotoStage { PICK, CALIBRATE, DETECTING, REVIEW }

data class PhotoState(
    val stage: PhotoStage = PhotoStage.PICK,
    val shots: List<Shot> = emptyList(),
    val selectedShotId: String? = null,
    /** Set when the user has picked a shot and the next tap will move it. */
    val movingShotId: String? = null,
    val detection: DetectionResult? = null,
    val rectified: ImageBitmap? = null,
    /**
     * Where the rectified photograph's centre is, and its scale, so the review screen can put
     * detections back onto the picture and turn a tap on it into millimetres.
     */
    val rectifiedPixelsPerMm: Double = 0.0,
    val rectifiedCentreX: Double = 0.0,
    val rectifiedCentreY: Double = 0.0,
    val busy: Boolean = false,
    val message: String? = null,
    val canUndo: Boolean = false,
    /** Set when the shooter asked to calibrate again, overriding a saved calibration. */
    val forceWizard: Boolean = false,
) {
    /**
     * True when the photograph does not look like the selected face.
     *
     * Reported from a range: a calibration that locked onto a table edge produced a rectified image
     * with the target off in a corner, and five confident "holes" on a printed warning label. The
     * detector cannot tell that from a real result; this can.
     */
    val calibrationSuspect: Boolean get() = detection?.targetLooksWrong == true

    /** True when the face records no aiming mark, so the calibration cannot be checked at all. */
    val cannotVerify: Boolean
        get() = detection != null && detection.targetAgreement == null
}

/**
 * Photo analysis, from picking an image to a reviewed set of shots.
 *
 * The review stage is not optional and cannot be skipped. No detector is perfect, and every number
 * the app later reports - score, group size, coaching - is built on these positions. Presenting them
 * as final without a human glance would be the app's most damaging possible lie.
 */
class PhotoViewModel(
    private val repository: RangeRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PhotoState())
    val state: StateFlow<PhotoState> = _state.asStateFlow()

    private var source: Mat? = null
    private var profile: Profile? = null
    private var spec: TargetSpec? = null
    private var caliber: Caliber? = null
    private var calibration: CalibrationAttempt? = null

    /** Edit history for undo. Bounded, because nobody needs to undo a hundred taps. */
    private val history = ArrayDeque<List<Shot>>()

    fun prepare(profile: Profile, spec: TargetSpec, caliber: Caliber?) {
        this.profile = profile
        this.spec = spec
        this.caliber = caliber
    }

    /** Takes ownership of the greyscale image and moves to calibration. */
    fun setImage(greyscale: Mat) {
        source?.release()
        source = greyscale
        _state.value = _state.value.copy(stage = PhotoStage.CALIBRATE, message = null)
    }

    fun currentImage(): Mat? = source

    /**
     * Accepts a calibration and runs detection.
     *
     * A profile with a saved calibration skips straight here on the next session, which is the
     * whole point of saving one.
     */
    fun onCalibrated(attempt: CalibrationAttempt, saveToProfile: Boolean) {
        calibration = attempt
        if (saveToProfile) {
            profile?.let { current ->
                viewModelScope.launch {
                    repository.saveProfile(
                        current.copy(calibration = attempt.toSaved(System.currentTimeMillis())),
                    )
                }
            }
        }
        detect()
    }

    /** Drops the calibration and returns to the wizard, whatever the profile has saved. */
    fun recalibrate() {
        calibration = null
        _state.value = _state.value.copy(
            stage = PhotoStage.CALIBRATE,
            forceWizard = true,
            detection = null,
            rectified = null,
            shots = emptyList(),
            message = null,
        )
    }

    fun useSavedCalibration(saved: SavedCalibration): Boolean {
        if (_state.value.forceWizard) return false
        val homography = saved.homography ?: return false
        calibration = CalibrationAttempt(
            method = saved.method,
            transform = com.nobrainsoft.rangeanalyser.vision.geometry.Transform2d.of(homography),
            millimetresPerPixel = saved.mmPerPixel,
            confidence = saved.confidence,
            correctsPerspective = saved.correctsPerspective,
        )
        detect()
        return true
    }

    private fun detect() {
        val image = source ?: return
        val face = spec ?: return
        val transform = calibration?.transform ?: return

        viewModelScope.launch {
            _state.value = _state.value.copy(stage = PhotoStage.DETECTING, busy = true)

            val outcome = withContext(Dispatchers.Default) {
                val rectified = Rectifier.rectify(image, transform, face)
                    ?: return@withContext null
                val result = HoleDetector.detect(rectified, face, caliber)
                val preview = ImageBridge.toImageBitmap(rectified.image)
                val geometry = Triple(
                    rectified.pixelsPerMm,
                    rectified.centre.x,
                    rectified.centre.y,
                )
                rectified.release()
                Triple(result, preview, geometry)
            }

            if (outcome == null) {
                _state.value = _state.value.copy(
                    stage = PhotoStage.CALIBRATE,
                    busy = false,
                    message = "Could not straighten the target with that calibration. Try again.",
                )
                return@launch
            }

            val (result, preview, geometry) = outcome
            val (pixelsPerMm, centreX, centreY) = geometry
            // Ordering by position rather than by confidence: on a photograph there is no firing
            // order to preserve, and a stable left-to-right numbering is easier to check against
            // the paper in front of you.
            val shots = result.holes
                .sortedWith(compareBy({ -it.positionMm.y }, { it.positionMm.x }))
                .mapIndexed { index, hole ->
                    Shot(
                        id = UUID.randomUUID().toString(),
                        position = hole.positionMm,
                        orderIndex = index,
                        diameterMm = hole.diameterMm,
                        confidence = hole.confidence,
                        source = ShotSource.AUTO,
                    )
                }

            _state.value = _state.value.copy(
                stage = PhotoStage.REVIEW,
                busy = false,
                detection = result,
                rectified = preview,
                rectifiedPixelsPerMm = pixelsPerMm,
                rectifiedCentreX = centreX,
                rectifiedCentreY = centreY,
                shots = shots,
                message = summarise(result, shots.size),
            )
        }
    }

    private fun summarise(result: DetectionResult, count: Int): String = buildString {
        if (result.targetLooksWrong) {
            append(
                "This photograph does not look like the target that was selected, so these " +
                    "positions are measured against geometry that is not in the picture. " +
                    "Calibrate again before trusting anything here. ",
            )
        }
        append("Found $count hole${if (count == 1) "" else "s"}.")
        val doubtful = result.uncertain.size
        if (doubtful > 0) append(" $doubtful need a look.")
        if (!result.calibreKnown) {
            append(" Setting a calibre on the firearm would make this more reliable.")
        }
        val clustered = result.holes.count { it.isEstimatedFromCluster }
        if (clustered > 0) {
            append(" $clustered came from overlapping holes and are estimates - check them.")
        }
    }

    // --- Review editing --------------------------------------------------------------------------

    fun selectShot(shotId: String?) {
        _state.value = _state.value.copy(selectedShotId = shotId, movingShotId = null)
    }

    fun beginMove(shotId: String) {
        _state.value = _state.value.copy(selectedShotId = shotId, movingShotId = shotId)
    }

    /**
     * Handles a tap on bare target: either finishes a move, or adds a missed shot.
     */
    fun tapTarget(position: PointMm) {
        val current = _state.value
        val moving = current.movingShotId

        if (moving != null) {
            push(current.shots)
            _state.value = current.copy(
                shots = current.shots.map {
                    if (it.id == moving) {
                        // A moved detection is no longer purely automatic, and the record says so.
                        it.copy(position = position, source = ShotSource.AUTO_EDITED, confidence = 1.0)
                    } else {
                        it
                    }
                },
                movingShotId = null,
                canUndo = true,
            )
            return
        }

        push(current.shots)
        _state.value = current.copy(
            shots = current.shots + Shot(
                id = UUID.randomUUID().toString(),
                position = position,
                orderIndex = current.shots.size,
                source = ShotSource.MANUAL,
            ),
            canUndo = true,
        )
    }

    fun deleteSelected() {
        val current = _state.value
        val id = current.selectedShotId ?: return
        push(current.shots)
        _state.value = current.copy(
            shots = current.shots.filterNot { it.id == id }
                .mapIndexed { index, shot -> shot.copy(orderIndex = index) },
            selectedShotId = null,
            movingShotId = null,
            canUndo = true,
        )
    }

    fun undo() {
        val previous = history.removeLastOrNull() ?: return
        _state.value = _state.value.copy(
            shots = previous,
            selectedShotId = null,
            movingShotId = null,
            canUndo = history.isNotEmpty(),
        )
    }

    private fun push(shots: List<Shot>) {
        history.addLast(shots)
        if (history.size > UNDO_DEPTH) history.removeFirst()
    }

    fun save(name: String, onSaved: (String) -> Unit) {
        val profile = profile ?: return
        val face = spec ?: return

        val session = Session(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { profile.name },
            profileId = profile.id,
            firearmId = profile.firearmId,
            ammoId = profile.ammoId,
            targetSpecId = face.id,
            distanceM = profile.distanceM,
            position = profile.position,
            support = profile.support,
            handedness = profile.handedness,
            mode = SessionMode.PHOTO,
            startedAtEpochMs = System.currentTimeMillis(),
            shots = _state.value.shots,
        )

        viewModelScope.launch {
            repository.saveSession(session)
            onSaved(session.id)
        }
    }

    override fun onCleared() {
        source?.release()
        source = null
        super.onCleared()
    }

    private companion object {
        const val UNDO_DEPTH = 30
    }
}

package com.nobrainsoft.rangeanalyser.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.model.Caliber
import com.nobrainsoft.rangeanalyser.core.model.Profile
import com.nobrainsoft.rangeanalyser.core.model.Session
import com.nobrainsoft.rangeanalyser.core.model.SessionMode
import com.nobrainsoft.rangeanalyser.core.model.Shot
import com.nobrainsoft.rangeanalyser.core.model.ShotSource
import com.nobrainsoft.rangeanalyser.core.scoring.Scorer
import com.nobrainsoft.rangeanalyser.core.stats.GroupStats
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import com.nobrainsoft.rangeanalyser.data.RangeRepository
import com.nobrainsoft.rangeanalyser.data.SpeechVerbosity
import com.nobrainsoft.rangeanalyser.vision.calibration.Calibrator
import com.nobrainsoft.rangeanalyser.vision.geometry.Transform2d
import com.nobrainsoft.rangeanalyser.vision.live.FrameOutcome
import com.nobrainsoft.rangeanalyser.vision.live.LiveFeasibility
import com.nobrainsoft.rangeanalyser.vision.live.ShotWatcher
import com.nobrainsoft.rangeanalyser.vision.live.SkipReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.opencv.core.Mat
import org.opencv.core.Point
import java.util.UUID

data class LiveState(
    val armed: Boolean = false,
    val shots: List<Shot> = emptyList(),
    val lastCall: String = "",
    val status: String = "Point the camera at the target and zoom in.",
    val feasibility: LiveFeasibility.Assessment? = null,
    /** What the camera reports it actually reached, for the label and for feasibility. */
    val zoomRatio: Float = 1f,
    val maximumZoom: Float = 1f,
    /**
     * The zoom control's own position, from none to as much as this camera has.
     *
     * Driving the camera by ratio needs its maximum, which is not known until it is bound and came
     * back as 1.0 on the reported device - pinning the slider at one end and making it do nothing.
     * Linear zoom is always valid, whatever the camera turns out to be capable of.
     */
    val linearZoom: Float = 0f,
    val stats: GroupStats? = null,
    val totalScore: String = "",
    val calibrated: Boolean = false,
    val skipped: SkipReason? = null,
    val targetChanged: Boolean = false,
    /** The face being scored against, which can be swapped without leaving the session. */
    val spec: TargetSpec? = null,
    /**
     * Where each shot currently sits in the analyser's own frame, so the preview can mark them.
     *
     * Kept in frame pixels rather than millimetres because that is what can be mapped onto what the
     * shooter is looking at, and it already carries the drift correction - so the markers stay on
     * their holes when the phone is nudged.
     */
    val shotsInFrame: List<FramePoint> = emptyList(),
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val frameRotationDegrees: Int = 0,
) {
    val shotCount: Int get() = shots.count { !it.excluded }
}

/** A point in the analyser's frame, with the shot number to label it. */
data class FramePoint(val x: Double, val y: Double, val number: Int)

/**
 * Runs a live session.
 *
 * Camera frames arrive on a background thread and are handed straight to the watcher; only
 * confirmed shots come back to the UI. That keeps per-frame work off the main thread and means the
 * screen only recomposes when something actually happened, which on a quiet firing point is rarely.
 */
class LiveViewModel(
    private val repository: RangeRepository,
    private val announcer: SpeechAnnouncer,
) : ViewModel() {

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private var watcher: ShotWatcher? = null
    private var profile: Profile? = null
    private var spec: TargetSpec? = null
    private var caliber: Caliber? = null
    private var scorer: Scorer? = null
    private var speech: SpeechVerbosity = SpeechVerbosity.FULL
    private var startedAtMs: Long = 0L
    private var pendingArm = false
    private var imageToTargetMm: Transform2d? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var frameRotation = 0

    fun prepare(
        profile: Profile,
        spec: TargetSpec,
        caliber: Caliber?,
        speech: SpeechVerbosity,
    ) {
        this.profile = profile
        this.spec = spec
        this.caliber = caliber
        this.speech = speech
        this.scorer = Scorer(spec, caliber)
        _state.value = _state.value.copy(spec = spec)

        // A saved calibration means the shooter can arm immediately; otherwise the first frame is
        // used to work out the scale.
        val saved = profile.calibration
        if (saved?.homography != null) {
            imageToTargetMm = Transform2d.of(saved.homography!!)
            watcher = ShotWatcher(imageToTargetMm!!, caliber)
            _state.value = _state.value.copy(
                calibrated = true,
                status = "Calibration ready. Frame the target and start watching.",
            )
        }
        updateFeasibility()
    }

    fun setZoom(ratio: Float, maximum: Float) {
        _state.value = _state.value.copy(zoomRatio = ratio, maximumZoom = maximum)
        updateFeasibility()
    }

    /** Moves the zoom control. The camera is driven from this, and reports back what it managed. */
    fun setLinearZoom(fraction: Float) {
        _state.value = _state.value.copy(linearZoom = fraction.coerceIn(0f, 1f))
    }

    /**
     * Asks the watcher to take the next frame as its reference.
     *
     * Deferred to the camera thread rather than done here, because the reference has to be an
     * actual frame and the UI thread does not have one.
     */
    fun arm() {
        pendingArm = true
        startedAtMs = System.currentTimeMillis()
        _state.value = _state.value.copy(status = "Reading the target...", targetChanged = false)
    }

    fun disarm() {
        watcher?.disarm()
        _state.value = _state.value.copy(
            armed = false,
            status = "Stopped. Save the string or carry on watching.",
        )
    }

    /** Called on the camera thread for every frame. The [Mat] must not be kept. */
    fun onFrame(luma: Mat, timestampMs: Long, rotationDegrees: Int) {
        val current = watcher ?: calibrateFrom(luma) ?: return
        frameWidth = luma.cols()
        frameHeight = luma.rows()
        frameRotation = rotationDegrees

        if (pendingArm) {
            pendingArm = false
            current.arm(luma)
            _state.value = _state.value.copy(
                armed = true,
                status = "Watching. Take your time.",
            )
            return
        }
        if (!current.isArmed) return

        when (val outcome = current.onFrame(luma, timestampMs - startedAtMs)) {
            is FrameOutcome.Shots -> record(outcome)
            is FrameOutcome.Skipped -> _state.value = _state.value.copy(skipped = outcome.reason)
            FrameOutcome.TargetChanged -> _state.value = _state.value.copy(
                targetChanged = true,
                status = "That looks like a different target.",
            )

            FrameOutcome.Watching -> if (_state.value.skipped != null) {
                _state.value = _state.value.copy(skipped = null)
            }
        }

        refreshOverlay(current)
    }

    /**
     * Recomputes where each shot appears in the frame the shooter is looking at.
     *
     * Positions are stored in target millimetres, which do not move; the phone does. Mapping back
     * through the calibration and adding the watcher's own alignment shift puts each marker over
     * the hole it belongs to rather than over where the target used to be.
     */
    private fun refreshOverlay(watcher: ShotWatcher) {
        val toImage = imageToTargetMm?.inverse() ?: return
        val drift = watcher.alignmentShift
        val marks = _state.value.shots
            .filterNot { it.excluded }
            .mapIndexed { index, shot ->
                val point = toImage.toImagePoint(shot.position)
                FramePoint(point.x + drift.x, point.y + drift.y, index + 1)
            }

        val current = _state.value
        if (current.shotsInFrame == marks &&
            current.frameWidth == frameWidth &&
            current.frameRotationDegrees == frameRotation
        ) {
            return
        }
        _state.value = current.copy(
            shotsInFrame = marks,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            frameRotationDegrees = frameRotation,
        )
    }

    private fun record(outcome: FrameOutcome.Shots) {
        val existing = _state.value.shots
        val added = outcome.shots.mapIndexed { index, live ->
            Shot(
                id = UUID.randomUUID().toString(),
                position = live.positionMm,
                orderIndex = existing.size + index,
                timestampMs = live.timestampMs,
                diameterMm = live.diameterMm,
                confidence = live.confidence,
                source = ShotSource.AUTO,
            )
        }
        val shots = existing + added

        val last = added.lastOrNull()
        val call = last?.let {
            announcer.announce(
                shotNumber = shots.indexOf(it) + 1,
                score = scorer?.score(it.position),
                position = it.position,
                verbosity = speech,
            )
        } ?: _state.value.lastCall

        _state.value = _state.value.copy(
            shots = shots,
            lastCall = call,
            skipped = null,
            stats = statsFor(shots),
            totalScore = totalFor(shots),
            status = "Watching. ${shots.size} on the target.",
        )
    }

    /** Adds a shot the detector missed, placed by hand on the overlay. */
    fun addManualShot(position: PointMm) {
        val shots = _state.value.shots + Shot(
            id = UUID.randomUUID().toString(),
            position = position,
            orderIndex = _state.value.shots.size,
            source = ShotSource.MANUAL,
        )
        _state.value = _state.value.copy(
            shots = shots,
            stats = statsFor(shots),
            totalScore = totalFor(shots),
        )
    }

    /** Marks a call as not a shot, without deleting the record of it. */
    fun toggleExcluded(shotId: String) {
        val shots = _state.value.shots.map {
            if (it.id == shotId) it.copy(excluded = !it.excluded) else it
        }
        _state.value = _state.value.copy(
            shots = shots,
            stats = statsFor(shots),
            totalScore = totalFor(shots),
        )
    }

    /** Every target face on the phone, so one can be swapped in without leaving the firing point. */
    val targets: StateFlow<List<TargetSpec>> = repository.observeTargets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The face currently being scored against. */
    val currentSpec: TargetSpec? get() = spec

    /**
     * Swaps the target face mid-session.
     *
     * Walking back to the car to edit a setup because the club put up a different face is the sort
     * of thing that makes an app get deleted. The calibration goes with it - the new face is a
     * different size, so the old scale is not merely stale but wrong.
     */
    fun changeTarget(replacement: TargetSpec) {
        spec = replacement
        scorer = Scorer(replacement, caliber)
        recalibrate()
        _state.value = _state.value.copy(
            spec = replacement,
            status = "Now scoring against ${replacement.name}. Reading the target...",
        )
    }

    /**
     * Throws away the calibration and measures again from the next frame.
     *
     * Wanted whenever the camera has been moved, the zoom changed, or the first automatic attempt
     * simply locked onto the wrong thing.
     */
    fun recalibrate() {
        watcher?.reset()
        watcher = null
        imageToTargetMm = null
        pendingArm = false
        _state.value = _state.value.copy(
            armed = false,
            calibrated = false,
            shots = emptyList(),
            stats = null,
            totalScore = "",
            lastCall = "",
            targetChanged = false,
            status = "Measuring the target again...",
        )
    }

    /** Starts a fresh string on a new target, keeping the calibration. */
    fun newTarget() {
        watcher?.reset()
        pendingArm = true
        _state.value = _state.value.copy(
            shots = emptyList(),
            stats = null,
            totalScore = "",
            targetChanged = false,
            lastCall = "",
            status = "New target. Reading it now...",
        )
    }

    fun save(onSaved: (String) -> Unit) {
        val profile = profile ?: return
        val spec = spec ?: return
        val session = Session(
            id = UUID.randomUUID().toString(),
            name = "${profile.name} live",
            profileId = profile.id,
            firearmId = profile.firearmId,
            ammoId = profile.ammoId,
            targetSpecId = spec.id,
            distanceM = profile.distanceM,
            position = profile.position,
            support = profile.support,
            handedness = profile.handedness,
            mode = SessionMode.LIVE,
            startedAtEpochMs = startedAtMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
            shots = _state.value.shots,
        )
        viewModelScope.launch {
            repository.saveSession(session)
            onSaved(session.id)
        }
    }

    /**
     * Works out the scale from the first frame when the profile has no saved calibration.
     *
     * Live mode cannot stop and run a wizard - the shooter is on the firing point - so it takes the
     * best automatic measurement available and says in the status line what it used.
     */
    private fun calibrateFrom(luma: Mat): ShotWatcher? {
        val face = spec ?: return null
        val attempt = Calibrator.fromRingGeometry(luma, face)
            ?: Calibrator.fromPaperQuad(luma, face)
            ?: profile?.let { fromOptics(luma, it) }
            ?: run {
                _state.value = _state.value.copy(
                    status = "Cannot measure this target from here. Use photo mode, or calibrate " +
                        "the setup first.",
                )
                return null
            }

        imageToTargetMm = attempt.transform
        val created = ShotWatcher(attempt.transform, caliber)
        watcher = created
        _state.value = _state.value.copy(
            calibrated = true,
            status = "Measured the target. Ready to watch.",
        )
        updateFeasibility()
        return created
    }

    private fun fromOptics(luma: Mat, profile: Profile) = Calibrator.fromOptics(
        distanceM = profile.distanceM,
        focalLengthMm = DEFAULT_FOCAL_LENGTH_MM,
        pixelPitchMm = DEFAULT_PIXEL_PITCH_MM,
        zoomRatio = _state.value.zoomRatio.toDouble(),
        targetCentre = Point(luma.cols() / 2.0, luma.rows() / 2.0),
    )

    private fun updateFeasibility() {
        val caliber = caliber ?: return
        val profile = profile ?: return
        val current = _state.value

        // Prefer the real measured scale once calibrated; fall back to lens arithmetic before that.
        val assessment = watcher
            ?.takeIf { current.calibrated }
            ?.let {
                LiveFeasibility.assessFromScale(
                    caliber = caliber,
                    millimetresPerPixel = caliber.bulletDiameterMm / it.expectedHoleDiameterPx,
                )
            }
            ?: LiveFeasibility.assess(
                caliber = caliber,
                distanceM = profile.distanceM,
                focalLengthMm = DEFAULT_FOCAL_LENGTH_MM,
                pixelPitchMm = DEFAULT_PIXEL_PITCH_MM,
                zoomRatio = current.zoomRatio.toDouble(),
                maximumZoomRatio = current.maximumZoom.toDouble(),
            )

        _state.value = current.copy(feasibility = assessment)
    }

    private fun statsFor(shots: List<Shot>): GroupStats? {
        val profile = profile ?: return null
        return GroupStats.of(shots, profile.distanceM, caliber)
    }

    private fun totalFor(shots: List<Shot>): String {
        val summary = scorer?.summarise(shots) ?: return ""
        return summary.totalDecimalScore
            ?.let { String.format(java.util.Locale.ROOT, "%.1f", it) }
            ?: summary.totalRingScore.toString()
    }

    override fun onCleared() {
        watcher?.reset()
        announcer.release()
        super.onCleared()
    }

    private companion object {
        /** Reasonable defaults for a modern phone's main camera, used only before calibration. */
        const val DEFAULT_FOCAL_LENGTH_MM = 26.0
        const val DEFAULT_PIXEL_PITCH_MM = 0.0014
    }
}

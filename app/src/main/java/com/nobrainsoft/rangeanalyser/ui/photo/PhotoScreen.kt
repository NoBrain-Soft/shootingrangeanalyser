// TopAppBar is still @ExperimentalMaterial3Api in Material3 1.3. Opted in at file level
// rather than per function: every top-level screen here has an app bar.
@file:OptIn(ExperimentalMaterial3Api::class)

package com.nobrainsoft.rangeanalyser.ui.photo

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nobrainsoft.rangeanalyser.appContainer
import com.nobrainsoft.rangeanalyser.camera.PhotoLoader
import com.nobrainsoft.rangeanalyser.core.model.Caliber
import com.nobrainsoft.rangeanalyser.core.model.Profile
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import com.nobrainsoft.rangeanalyser.ui.calibrate.CalibrationViewModel
import com.nobrainsoft.rangeanalyser.ui.calibrate.CalibrationWizardScreen
import com.nobrainsoft.rangeanalyser.ui.common.CautionBanner
import com.nobrainsoft.rangeanalyser.ui.common.ImageMark
import com.nobrainsoft.rangeanalyser.ui.common.MarkableImage
import com.nobrainsoft.rangeanalyser.ui.common.ShotLayer
import com.nobrainsoft.rangeanalyser.ui.common.TargetTap
import com.nobrainsoft.rangeanalyser.ui.common.TargetView
import com.nobrainsoft.rangeanalyser.ui.rangeViewModel
import com.nobrainsoft.rangeanalyser.ui.theme.Dimens
import com.nobrainsoft.rangeanalyser.ui.theme.ScoreColors
import java.io.File
import kotlinx.coroutines.launch

/**
 * Photo analysis, end to end.
 *
 * The system camera is used for capture rather than an in-app one: it focuses better, exposes
 * better, and lets the shooter retake the picture until it is square and sharp - all of which
 * matter far more to the result than a bespoke shutter button would.
 */
@Composable
fun PhotoScreen(
    profile: Profile,
    spec: TargetSpec,
    caliber: Caliber?,
    onFinished: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel = rangeViewModel { PhotoViewModel(it.repository) }
    val calibrationViewModel = rangeViewModel(key = "calibration") { CalibrationViewModel() }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(profile, spec, caliber) { viewModel.prepare(profile, spec, caliber) }

    when (state.stage) {
        PhotoStage.PICK -> PickStep(
            context = context,
            onImage = { viewModel.setImage(it) },
            onBack = onBack,
        )

        PhotoStage.CALIBRATE -> {
            val image = viewModel.currentImage()
            LaunchedEffect(image) {
                // A profile that already carries a calibration goes straight to detection - the
                // whole reason for saving one is not to do this twice.
                val reused = profile.calibration?.let { viewModel.useSavedCalibration(it) } ?: false
                if (!reused && image != null) {
                    calibrationViewModel.begin(image, spec, caliber)
                }
            }
            if (image != null && (profile.calibration == null || state.forceWizard)) {
                CalibrationWizardScreen(
                    viewModel = calibrationViewModel,
                    spec = spec,
                    onCancel = onBack,
                    onCalibrated = { viewModel.onCalibrated(it, saveToProfile = true) },
                )
            } else {
                Busy("Reusing this setup's saved calibration...")
            }
        }

        PhotoStage.DETECTING -> Busy("Looking for holes...")

        PhotoStage.REVIEW -> ReviewStep(
            state = state,
            spec = spec,
            viewModel = viewModel,
            onFinished = onFinished,
            onBack = onBack,
        )
    }
}

@Composable
private fun PickStep(context: Context, onImage: (org.opencv.core.Mat) -> Unit, onBack: () -> Unit) {
    var pendingCapture by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    /**
     * Reads the picked photograph.
     *
     * Off the main thread, because decoding a 50-megapixel phone photograph and converting it for
     * OpenCV takes long enough to freeze the UI. Wrapped, because every step of it can fail for
     * reasons outside the app's control - a URI another app has already revoked, a format that
     * cannot be decoded, a picture too large for the heap - and none of those are worth a crash.
     */
    fun load(uri: Uri?) {
        if (uri == null) return
        if (!context.appContainer.openCvAvailable) {
            failure = "Image analysis is unavailable on this device - the OpenCV libraries did " +
                "not load, so a photograph cannot be measured."
            return
        }
        busy = true
        failure = null
        scope.launch {
            val outcome = PhotoLoader.loadGreyscale(context, uri)
            busy = false
            outcome
                .onSuccess { onImage(it) }
                .onFailure { failure = PhotoLoader.reasonFor(it) }
        }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { load(it) }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success -> if (success) load(pendingCapture) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Analyse a target") }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Dimens.gutterWide),
            verticalArrangement = Arrangement.spacedBy(Dimens.itemSpacing),
        ) {
            Text("Photograph the target", style = MaterialTheme.typography.titleMedium)
            Text(
                "Square on and filling the frame gives the best result. A slight angle is fine - " +
                    "the app measures it out - but a very steep one costs accuracy.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            failure?.let { CautionBanner(it) }

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text("Reading the photograph...", modifier = Modifier.padding(start = 12.dp))
                }
            }

            Button(
                enabled = !busy,
                onClick = {
                    // Every part of this can fail on a device without a camera app, or where the
                    // provider is misconfigured; neither is worth taking the app down for.
                    runCatching {
                        val file = File(context.appContainer.files.targetsDirectory, "capture.jpg")
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file,
                        )
                        pendingCapture = uri
                        takePicture.launch(uri)
                    }.onFailure {
                        Log.w("RangeAnalyser", "could not start the camera", it)
                        failure = "No camera app would open. Choose an existing photo instead."
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.touchTargetRange),
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Text("Take a photo", modifier = Modifier.padding(start = 8.dp))
            }

            OutlinedButton(
                enabled = !busy,
                onClick = {
                    runCatching { pickImage.launch("image/*") }.onFailure {
                        Log.w("RangeAnalyser", "no gallery app", it)
                        failure = "No app on this phone offered a picture to open."
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.touchTargetRange),
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Text("Choose an existing photo", modifier = Modifier.padding(start = 8.dp))
            }

            TextButton(onClick = onBack) { Text("Cancel") }
        }
    }
}

@Composable
private fun ReviewStep(
    state: PhotoState,
    spec: TargetSpec,
    viewModel: PhotoViewModel,
    onFinished: (String) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Check the holes") }) },
        bottomBar = {
            Column(Modifier.padding(Dimens.gutter)) {
                if (saving) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name this session") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.itemSpacing),
                ) {
                    if (state.canUndo) {
                        OutlinedButton(
                            onClick = viewModel::undo,
                            modifier = Modifier.heightIn(min = Dimens.touchTargetRange),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                        }
                    }
                    if (state.selectedShotId != null) {
                        OutlinedButton(
                            onClick = viewModel::deleteSelected,
                            modifier = Modifier.heightIn(min = Dimens.touchTargetRange),
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete shot")
                        }
                        OutlinedButton(
                            onClick = { viewModel.beginMove(state.selectedShotId!!) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = Dimens.touchTargetRange),
                        ) {
                            Text("Move")
                        }
                    }
                    Button(
                        onClick = {
                            if (saving) viewModel.save(name, onFinished) else saving = true
                        },
                        enabled = state.shots.isNotEmpty(),
                        modifier = Modifier
                            .weight(1.2f)
                            .heightIn(min = Dimens.touchTargetRange),
                    ) {
                        Text(if (saving) "Save" else "Done")
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
            if (state.calibrationSuspect) {
                CautionBanner(
                    "The rings the app expects are not where they are in this photograph, so the " +
                        "calibration found something other than your target. Everything below is " +
                        "measured against the wrong geometry.",
                )
                Button(
                    onClick = viewModel::recalibrate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.touchTargetRange),
                ) {
                    Text("Calibrate again")
                }
            } else if (state.cannotVerify) {
                CautionBanner(
                    "This target records no aiming mark, so the app could not measure it from the " +
                        "target's own printing and cannot check the calibration either. Adding the " +
                        "black's diameter to the target makes both possible.",
                )
            }

            state.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.detection?.uncertain?.isNotEmpty() == true) {
                CautionBanner(
                    "Holes ringed in amber are ones the detector was unsure about. Everything on " +
                        "the results screen is built from these positions, so it is worth a look.",
                )
            }

            Text(
                when {
                    state.movingShotId != null -> "Tap where the shot should be."
                    state.selectedShotId != null -> "Move or delete it, or tap elsewhere to add one."
                    else -> "Tap a hole to correct it, or tap bare paper to add one the app missed."
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            // The photograph is the default view, and the reason is the reported failure: eleven
            // holes, one found. Against a drawn diagram there is no way to tell a detector that
            // missed the group from a calibration that put the target somewhere else entirely.
            // Against the picture it is obvious at a glance.
            var showPhoto by remember { mutableStateOf(true) }
            val photo = state.rectified

            if (photo != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = showPhoto,
                        onClick = { showPhoto = true },
                        label = { Text("Photo") },
                    )
                    FilterChip(
                        selected = !showPhoto,
                        onClick = { showPhoto = false },
                        label = { Text("Target") },
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (photo != null && showPhoto && state.rectifiedPixelsPerMm > 0.0) {
                    val toMark = { shot: com.nobrainsoft.rangeanalyser.core.model.Shot ->
                        ImageMark(
                            x = state.rectifiedCentreX + shot.position.x * state.rectifiedPixelsPerMm,
                            y = state.rectifiedCentreY - shot.position.y * state.rectifiedPixelsPerMm,
                        )
                    }
                    val toMm = { mark: ImageMark ->
                        com.nobrainsoft.rangeanalyser.core.geometry.PointMm(
                            x = (mark.x - state.rectifiedCentreX) / state.rectifiedPixelsPerMm,
                            y = (state.rectifiedCentreY - mark.y) / state.rectifiedPixelsPerMm,
                        )
                    }
                    MarkableImage(
                        image = photo,
                        marks = state.shots.map(toMark),
                        onMark = { viewModel.tapTarget(toMm(it)) },
                        onMoveMark = { index, mark ->
                            state.shots.getOrNull(index)?.let { shot ->
                                viewModel.beginMove(shot.id)
                                viewModel.tapTarget(toMm(mark))
                            }
                        },
                    )
                } else {
                    TargetView(
                        spec = spec,
                        layers = listOf(
                            ShotLayer(
                                label = "Detected",
                                shots = state.shots,
                                colour = ScoreColors.hit,
                            ),
                        ),
                        selectedShotId = state.selectedShotId,
                        onTap = { tap ->
                            when (tap) {
                                is TargetTap.OnShot -> viewModel.selectShot(tap.shotId)
                                is TargetTap.OnTarget -> viewModel.tapTarget(tap.positionMm)
                            }
                        },
                    )
                }
            }

            Text(
                "${state.shots.size} shots",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun Busy(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Dimens.itemSpacing),
            )
        }
    }
}


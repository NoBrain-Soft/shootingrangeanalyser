package com.nobrainsoft.rangeanalyser.ui.live

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nobrainsoft.rangeanalyser.camera.LumaAnalyzer
import com.nobrainsoft.rangeanalyser.core.model.Caliber
import com.nobrainsoft.rangeanalyser.core.model.Profile
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import com.nobrainsoft.rangeanalyser.data.SpeechVerbosity
import com.nobrainsoft.rangeanalyser.ui.common.CautionBanner
import com.nobrainsoft.rangeanalyser.ui.common.ShotLayer
import com.nobrainsoft.rangeanalyser.ui.common.TargetView
import com.nobrainsoft.rangeanalyser.ui.rangeViewModel
import com.nobrainsoft.rangeanalyser.ui.theme.Dimens
import com.nobrainsoft.rangeanalyser.ui.theme.ScoreColors
import com.nobrainsoft.rangeanalyser.vision.live.LiveFeasibility
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Watches a target and calls the shots.
 *
 * The camera fills the screen because framing is the thing the shooter is doing, but the group
 * itself is shown on a clean target diagram in the corner rather than as markers over the zoomed
 * preview. Trying to read a group off a shaky, heavily zoomed camera image is exactly the problem
 * this app exists to remove.
 */
@Composable
fun LiveScreen(
    profile: Profile,
    spec: TargetSpec,
    /** From the profile's firearm. Drives detection kernel sizes and the feasibility warning. */
    caliber: Caliber?,
    speech: SpeechVerbosity,
    onFinished: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val viewModel = rangeViewModel {
        LiveViewModel(it.repository, SpeechAnnouncer(context))
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(profile, spec, caliber) {
        viewModel.prepare(profile, spec, caliber, speech)
    }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) requestPermission.launch(Manifest.permission.CAMERA)
    }

    if (!granted) {
        CameraRationale(onRequest = { requestPermission.launch(Manifest.permission.CAMERA) }, onBack = onBack)
        return
    }

    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                val previewView = PreviewView(viewContext).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val providerFuture = ProcessCameraProvider.getInstance(viewContext)
                providerFuture.addListener({
                    val provider = providerFuture.get()

                    val preview = Preview.Builder().build().also {
                        // The setter, not the synthetic property: CameraX 1.4 has no matching
                        // getter, so `it.surfaceProvider = ...` does not resolve.
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        // Dropping frames is right here: the watcher needs the newest picture of
                        // the target, never a backlog of old ones.
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(executor, LumaAnalyzer(viewModel::onFrame)) }

                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    previewView.tag = camera
                    camera.cameraInfo.zoomState.value?.let {
                        viewModel.setZoom(it.zoomRatio, it.maxZoomRatio)
                    }
                }, ContextCompat.getMainExecutor(viewContext))
                previewView
            },
            update = { previewView ->
                (previewView.tag as? androidx.camera.core.Camera)
                    ?.cameraControl
                    ?.setZoomRatio(state.zoomRatio)
            },
        )

        StatusPanel(
            state = state,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(Dimens.gutter),
        )

        if (state.shots.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(Dimens.gutter)
                    .size(150.dp),
                shape = RoundedCornerShape(Dimens.cardCorner),
                color = Color.Black.copy(alpha = 0.55f),
            ) {
                TargetView(
                    spec = spec,
                    layers = listOf(
                        ShotLayer(
                            label = "This string",
                            shots = state.shots,
                            colour = ScoreColors.hit,
                        ),
                    ),
                )
            }
        }

        Controls(
            state = state,
            viewModel = viewModel,
            onFinished = onFinished,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(Dimens.gutter),
        )
    }
}

@Composable
private fun StatusPanel(state: LiveState, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.cardCorner),
        color = Color.Black.copy(alpha = 0.6f),
    ) {
        Column(Modifier.padding(Dimens.gutter)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${state.shotCount} shots",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
                if (state.totalScore.isNotBlank()) {
                    Text(
                        state.totalScore,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            state.stats?.let {
                Text(
                    String.format(
                        Locale.ROOT,
                        "group %.1f mm · mean radius %.1f mm",
                        it.extremeSpreadMm,
                        it.meanRadiusMm,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }

            // Everything spoken is also written down: the shooter is wearing ear protection.
            if (state.lastCall.isNotBlank()) {
                Text(
                    state.lastCall,
                    style = MaterialTheme.typography.headlineSmall,
                    color = ScoreColors.hit,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Text(
                state.status,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.padding(top = 4.dp),
            )

            state.skipped?.let {
                Text(
                    "Frame skipped - something moved across the target.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ScoreColors.warning,
                )
            }
        }
    }
}

@Composable
private fun Controls(
    state: LiveState,
    viewModel: LiveViewModel,
    onFinished: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.itemSpacing)) {
        // The honest warning: at some distances and calibres this simply cannot work, and saying so
        // beforehand is worth more than a string of missed shots.
        state.feasibility?.let { assessment ->
            when (assessment.verdict) {
                LiveFeasibility.Verdict.UNRELIABLE -> CautionBanner(
                    "A hole would be about ${assessment.holeDiameterPx.toInt()} pixels across at " +
                        "this distance and zoom - too small to track. " +
                        (
                            assessment.suggestedZoomRatio
                                ?.let { "Zoom to about ${"%.1f".format(it)}x." }
                                ?: "Move closer, or use photo mode instead."
                            ),
                )

                LiveFeasibility.Verdict.MARGINAL -> CautionBanner(
                    "Holes are small in frame - expect some misses. Zooming in would help.",
                )

                LiveFeasibility.Verdict.GOOD -> Unit
            }
        }

        if (state.targetChanged) {
            CautionBanner("The view changed completely. Start a new target?")
        }

        if (state.maximumZoom > 1f) {
            Column {
                Text(
                    "Zoom ${"%.1f".format(state.zoomRatio)}x",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
                Slider(
                    value = state.zoomRatio,
                    onValueChange = { viewModel.setZoom(it, state.maximumZoom) },
                    valueRange = 1f..state.maximumZoom,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.itemSpacing)) {
            if (state.shots.isNotEmpty()) {
                OutlinedButton(
                    onClick = { viewModel.save(onFinished) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Dimens.touchTargetRange),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) {
                    Text("Finish")
                }
            }

            if (state.targetChanged || state.shots.isNotEmpty()) {
                OutlinedButton(
                    onClick = viewModel::newTarget,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Dimens.touchTargetRange),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) {
                    Text("New target")
                }
            }

            Button(
                onClick = { if (state.armed) viewModel.disarm() else viewModel.arm() },
                modifier = Modifier
                    .weight(1.4f)
                    .heightIn(min = Dimens.touchTargetRange),
            ) {
                Text(if (state.armed) "Stop watching" else "Start watching")
            }
        }
    }
}

@Composable
private fun CameraRationale(onRequest: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(Dimens.gutterWide),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("The camera is how this works", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Live mode watches your target through the camera and tells you where each shot went. " +
                "Nothing is uploaded anywhere - the pictures stay on your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Dimens.itemSpacing),
        )
        Button(
            onClick = onRequest,
            modifier = Modifier
                .padding(top = Dimens.sectionSpacing)
                .fillMaxWidth()
                .heightIn(min = Dimens.touchTargetRange),
        ) {
            Text("Allow camera access")
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .padding(top = Dimens.itemSpacing)
                .fillMaxWidth()
                .heightIn(min = Dimens.touchTarget),
        ) {
            Text("Not now")
        }
    }
}

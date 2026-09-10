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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.max
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
    onAnalysePhoto: () -> Unit,
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

    // The camera, held in composition rather than in the view's tag: zoom has to be applied from a
    // effect that knows when it changed, and fishing it back out of a View was how it got lost.
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }

    // Linear zoom rather than a ratio, because the camera's maximum is not known until it is bound
    // and was reported as 1.0 on the reported device - which pinned the slider and made it do
    // nothing. A 0-to-1 control is always valid, and the ratio shown is read back from the camera.
    LaunchedEffect(camera, state.linearZoom) {
        val bound = camera ?: return@LaunchedEffect
        runCatching {
            bound.cameraControl.setLinearZoom(state.linearZoom)
            bound.cameraInfo.zoomState.value?.let {
                viewModel.setZoom(it.zoomRatio, it.maxZoomRatio)
            }
        }
    }

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
                    camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }, ContextCompat.getMainExecutor(viewContext))
                previewView
            },
        )

        // Markers on the picture the shooter is actually looking at. The corner diagram is still
        // there for the group's shape, but reading a hit off it means looking away from the target.
        ShotOverlay(state, Modifier.fillMaxSize())

        StatusPanel(
            state = state,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(Dimens.gutter),
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(Dimens.gutter),
            horizontalAlignment = Alignment.Start,
        ) {
            if (state.shots.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .padding(bottom = Dimens.itemSpacing)
                        .size(150.dp),
                    shape = RoundedCornerShape(Dimens.cardCorner),
                    color = Color.Black.copy(alpha = 0.55f),
                ) {
                    TargetView(
                        spec = state.spec ?: spec,
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
                onAnalysePhoto = onAnalysePhoto,
            )
        }
    }
}

/**
 * Draws each called shot over the camera preview.
 *
 * The analyser sees the sensor's own orientation and the preview shows it upright and centre-
 * cropped to fill the screen, so both have to be undone to put a marker where the hole is. Shot
 * positions arrive already corrected for the phone's drift, so they stay on their holes when the
 * camera is nudged rather than sliding off.
 */
@Composable
private fun ShotOverlay(state: LiveState, modifier: Modifier = Modifier) {
    if (state.shotsInFrame.isEmpty() || state.frameWidth <= 0 || state.frameHeight <= 0) return
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier) {
        // The frame as the preview shows it: rotated upright, then scaled to cover the view.
        val quarterTurn = state.frameRotationDegrees == 90 || state.frameRotationDegrees == 270
        val uprightWidth = if (quarterTurn) state.frameHeight else state.frameWidth
        val uprightHeight = if (quarterTurn) state.frameWidth else state.frameHeight
        if (uprightWidth <= 0 || uprightHeight <= 0) return@Canvas

        val scale = max(size.width / uprightWidth, size.height / uprightHeight)
        val originX = (size.width - uprightWidth * scale) / 2f
        val originY = (size.height - uprightHeight * scale) / 2f

        for (mark in state.shotsInFrame) {
            val (ux, uy) = when (state.frameRotationDegrees) {
                90 -> (state.frameHeight - mark.y) to mark.x
                180 -> (state.frameWidth - mark.x) to (state.frameHeight - mark.y)
                270 -> mark.y to (state.frameWidth - mark.x)
                else -> mark.x to mark.y
            }
            val at = Offset(originX + ux.toFloat() * scale, originY + uy.toFloat() * scale)
            if (at.x < 0f || at.y < 0f || at.x > size.width || at.y > size.height) continue

            drawCircle(Color.Black.copy(alpha = 0.55f), radius = 22f, center = at)
            drawCircle(ScoreColors.hit, radius = 18f, center = at, style = Stroke(width = 4f))

            val layout = textMeasurer.measure(
                mark.number.toString(),
                TextStyle(fontSize = 13.sp, color = Color.White),
            )
            drawText(
                layout,
                topLeft = Offset(
                    at.x - layout.size.width / 2f,
                    at.y - layout.size.height / 2f,
                ),
            )
        }
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
    onAnalysePhoto: () -> Unit,
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

        Column {
            Text(
                "Zoom ${String.format(Locale.ROOT, "%.1f", state.zoomRatio)}x",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
            Slider(
                value = state.linearZoom,
                onValueChange = viewModel::setLinearZoom,
                valueRange = 0f..1f,
            )
        }

        // Changing target or re-measuring used to mean walking off the firing point to edit a
        // setup. Both belong here: the club puts up a different face, or the phone gets nudged.
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.itemSpacing)) {
            val targets by viewModel.targets.collectAsStateWithLifecycle()
            var picking by remember { mutableStateOf(false) }

            OutlinedButton(
                onClick = { picking = true },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Dimens.touchTarget),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) {
                Text(state.spec?.name ?: "Target", maxLines = 1)
            }

            OutlinedButton(
                onClick = viewModel::recalibrate,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Dimens.touchTarget),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) {
                Text("Re-measure")
            }

            if (picking) {
                AlertDialog(
                    onDismissRequest = { picking = false },
                    title = { Text("Which target is up?") },
                    text = {
                        LazyColumn {
                            items(targets) { candidate ->
                                TextButton(
                                    onClick = {
                                        viewModel.changeTarget(candidate)
                                        picking = false
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = Dimens.touchTarget),
                                ) {
                                    Text(candidate.name, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { picking = false }) { Text("Cancel") }
                    },
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

            // Live tracking calls a shot within a millimetre or two; a photograph of the same face
            // measures it far better, and only a photograph can be reviewed hole by hole. Saving
            // the string first means the two can be compared afterwards rather than one replacing
            // the other.
            if (state.shots.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        viewModel.save { onAnalysePhoto() }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Dimens.touchTargetRange),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) {
                    Text("Save + photo")
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

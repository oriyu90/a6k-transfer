package jp.yuki.a6000transfer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import jp.yuki.a6000transfer.R
import jp.yuki.a6000transfer.sony.CameraCredentialReader
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun CameraScanScreen(
    onCredentials: (CameraCredentialReader.Reading) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var allowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = it
    }
    LaunchedEffect(Unit) {
        if (!allowed) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (allowed) {
        CameraSession(onCredentials = onCredentials, onBack = onBack)
    } else {
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.scan_permission_hint))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text(stringResource(R.string.scan_grant_permission))
            }
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                })
            }) { Text(stringResource(R.string.scan_open_settings)) }
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
private fun CameraSession(
    onCredentials: (CameraCredentialReader.Reading) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            clipChildren = true
            clipToPadding = true
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var reading by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    val active = remember(lifecycleOwner, previewView) { AtomicBoolean(true) }

    DisposableEffect(lifecycleOwner, previewView) {
        val future = try {
            ProcessCameraProvider.getInstance(context)
        } catch (_: Exception) {
            errorRes = R.string.scan_camera_failed
            null
        }
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var capture: ImageCapture? = null
        future?.addListener({
            if (active.get()) {
                try {
                    val readyProvider = future.get()
                    val readyPreview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val readyCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build()
                    val readyCamera = readyProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, readyPreview, readyCapture,
                    )
                    provider = readyProvider
                    preview = readyPreview
                    capture = readyCapture
                    camera = readyCamera
                    imageCapture = readyCapture
                    zoomRatio = readyCamera.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                    torchOn = readyCamera.cameraInfo.torchState.value == TorchState.ON
                } catch (_: Exception) {
                    errorRes = R.string.scan_camera_failed
                }
            }
        }, executor)
        onDispose {
            active.set(false)
            provider?.let { ready ->
                val owned = listOfNotNull(preview, capture)
                if (owned.isNotEmpty()) ready.unbind(*owned.toTypedArray())
            }
            camera = null
            imageCapture = null
        }
    }

    fun scan() {
        val capture = imageCapture ?: return
        if (reading) return
        reading = true
        errorRes = null
        fun finish(result: CameraCredentialReader.Reading?, failure: Int = R.string.scan_not_found) {
            if (!active.get()) return
            reading = false
            if (result == null) errorRes = failure else onCredentials(result)
        }
        lateinit var captureAttempt: (Int, CameraCredentialReader.Reading?) -> Unit
        captureAttempt = { attempt, prior ->
            if (active.get()) {
                try {
                    capture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                    capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                            CameraOcrProcessor.read(image, executor) { result ->
                                if (active.get()) {
                                    val merged = CameraCredentialReader.combine(prior, result)
                                    if (result?.passwordConfirmed == true) {
                                        finish(result)
                                    } else if (attempt < 2) {
                                        handler.postDelayed({ captureAttempt(2, merged) }, 300)
                                    } else {
                                        finish(merged)
                                    }
                                }
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            if (!active.get()) return
                            if (attempt < 2) handler.postDelayed({ captureAttempt(2, prior) }, 300)
                            else finish(prior, R.string.scan_capture_failed)
                        }
                    })
                } catch (_: Exception) {
                    if (attempt < 2) handler.postDelayed({ captureAttempt(2, prior) }, 300)
                    else finish(prior, R.string.scan_capture_failed)
                }
            }
        }
        captureAttempt(1, null)
    }

    val zoomState = camera?.cameraInfo?.zoomState?.value
    val minZoom = zoomState?.minZoomRatio ?: 1f
    val maxZoom = zoomState?.maxZoomRatio ?: 1f
    val hasTorch = camera?.cameraInfo?.hasFlashUnit() == true
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val previewHeight = minOf(300.dp, maxHeight * 0.43f)
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.scan_hint), style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.fillMaxWidth().height(previewHeight).clipToBounds()) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize().pointerInput(camera) {
                        detectTapGestures { position ->
                            try {
                                camera?.cameraControl?.startFocusAndMetering(
                                    FocusMeteringAction.Builder(
                                        previewView.meteringPointFactory.createPoint(position.x, position.y),
                                    ).setAutoCancelDuration(3, TimeUnit.SECONDS).build(),
                                )
                            } catch (_: Exception) {
                                errorRes = R.string.scan_camera_failed
                            }
                        }
                    },
                )
                Box(
                    Modifier.fillMaxWidth(0.88f).fillMaxHeight(0.62f)
                        .align(Alignment.Center)
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                )
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            val next = !torchOn
                            val result = camera?.cameraControl?.enableTorch(next)
                            result?.addListener({
                                if (active.get()) {
                                    torchOn = try {
                                        result.get()
                                        next
                                    } catch (_: Exception) {
                                        errorRes = R.string.scan_camera_failed
                                        !next
                                    }
                                }
                            }, executor)
                        },
                        enabled = hasTorch && !reading,
                    ) { Text(stringResource(if (torchOn) R.string.scan_torch_off else R.string.scan_torch_on)) }
                    OutlinedButton(onClick = onBack) { Text(stringResource(R.string.cancel)) }
                }
                if (maxZoom > minZoom) {
                    Text(stringResource(R.string.scan_zoom, String.format(Locale.ROOT, "%.1f", zoomRatio)))
                    Slider(
                        value = zoomRatio.coerceIn(minZoom, maxZoom),
                        onValueChange = { value ->
                            zoomRatio = value
                            camera?.cameraControl?.setZoomRatio(value)
                        },
                        valueRange = minZoom..maxZoom,
                        enabled = !reading,
                    )
                }
                Button(onClick = { scan() }, enabled = imageCapture != null && !reading) {
                    if (reading) {
                        CircularProgressIndicator(Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(if (reading) R.string.scan_reading else R.string.scan_capture))
                }
                errorRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

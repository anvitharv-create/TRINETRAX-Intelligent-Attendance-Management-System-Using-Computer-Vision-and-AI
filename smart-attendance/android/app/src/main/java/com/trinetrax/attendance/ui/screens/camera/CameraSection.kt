package com.trinetrax.attendance.ui.screens.camera

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.trinetrax.attendance.ui.components.ErrorState
import com.trinetrax.attendance.ui.components.LoadingView
import com.trinetrax.attendance.ui.theme.MutedText
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** JPEG-encoded base64 (no data-URL prefix) at a size the recognition model handles well. */
fun bitmapToBase64(bitmap: Bitmap, maxDimension: Int = 1280, quality: Int = 85): String {
    val scaled = if (maxOf(bitmap.width, bitmap.height) > maxDimension) {
        val ratio = maxOf(bitmap.width, bitmap.height).toFloat() / maxDimension
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width / ratio).toInt().coerceAtLeast(1),
            (bitmap.height / ratio).toInt().coerceAtLeast(1),
            true
        )
    } else {
        bitmap
    }
    val stream = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
    return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
}

private suspend fun Context.cameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            { continuation.resume(future.get()) },
            ContextCompat.getMainExecutor(this)
        )
    }

/**
 * Live CameraX preview with capture, retry and camera switching.
 * Resources are unbound when the composable leaves composition.
 */
@Composable
fun CameraSection(
    onImageCaptured: (Bitmap) -> Unit,
    modifier: Modifier = Modifier,
    facingFront: Boolean = true
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember { mutableStateOf<Boolean?>(null) }
    var lensFront by remember { mutableStateOf(facingFront) }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            hasPermission = true
        } else {
            hasPermission = null
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    when (hasPermission) {
        null -> LoadingView("Requesting camera permission…")
        false -> Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Camera permission is required", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "TrinetraX uses the camera only to capture the image that is sent to the attendance server for face recognition.",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedText
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant permission")
            }
        }
        true -> {
            val previewView = remember {
                PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            }
            val imageCapture = remember {
                ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
            }

            LaunchedEffect(lensFront) {
                runCatching {
                    val provider = context.cameraProvider()
                    provider.unbindAll()
                    val selector = CameraSelector.Builder()
                        .requireLensFacing(
                            if (lensFront) CameraSelector.LENS_FACING_FRONT
                            else CameraSelector.LENS_FACING_BACK
                        )
                        .build()
                    val preview = Preview.Builder().build().apply {
                        setSurfaceProvider(previewView.surfaceProvider)
                    }
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                }.onFailure {
                    errorText = "Could not start the camera: ${it.message}"
                }
            }

            DisposableEffect(Unit) {
                onDispose {
                    runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
                }
            }

            Box(modifier = modifier.fillMaxSize()) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

                if (errorText != null) {
                    ErrorState(errorText!!, modifier = Modifier.align(Alignment.Center))
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                ) {
                    IconButton(
                        onClick = { lensFront = !lensFront },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape)
                    ) {
                        Icon(Icons.Filled.Cameraswitch, contentDescription = "Switch camera", tint = Color.White)
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (lensFront) "Front camera" else "Rear camera",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            errorText = null
                            imageCapture.takePicture(
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        var bitmap = image.toBitmap()
                                        val rotation = image.imageInfo.rotationDegrees
                                        if (rotation != 0) {
                                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                                            bitmap = Bitmap.createBitmap(
                                                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                                            )
                                        }
                                        image.close()
                                        busy = false
                                        onImageCaptured(bitmap)
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        busy = false
                                        errorText = "Capture failed: ${exception.message}"
                                    }
                                }
                            )
                        },
                        shape = CircleShape
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                        Spacer(Modifier.height(0.dp))
                        Text(if (busy) " Capturing…" else " Capture photo", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

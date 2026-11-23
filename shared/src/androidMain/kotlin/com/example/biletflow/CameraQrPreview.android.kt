package com.example.biletflow.presentation.organizer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
actual fun CameraQrPreview(modifier: Modifier, onFrame: (List<String>) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onFrame)
    val preferences = remember { context.getSharedPreferences("camera_permission", Context.MODE_PRIVATE) }
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var permission by remember { mutableStateOf(granted()) }
    var status by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        permission = allowed
        status = if (allowed) 0 else if (context.activity()?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA) } == true) 1 else 2
    }
    val request: () -> Unit = {
        if (granted()) { permission = true; status = 0; retry++ }
        else { preferences.edit().putBoolean("asked", true).apply(); launcher.launch(Manifest.permission.CAMERA) }
    }
    LaunchedEffect(Unit) {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) status = 3
        else if (!permission) {
            val activity = context.activity()
            if (preferences.getBoolean("asked", false)) status = if (activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)) 1 else 2
            else request()
        }
    }
    Box(modifier) {
        if (permission && status == 0) {
            val view = remember { PreviewView(context) }
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            DisposableEffect(owner, view, retry) {
                val alive = AtomicBoolean(true)
                val executor = Executors.newSingleThreadExecutor()
                val future = ProcessCameraProvider.getInstance(context)
                val preview = Preview.Builder().build().apply { setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                var provider: ProcessCameraProvider? = null
                val main = ContextCompat.getMainExecutor(context)
                val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")) }
                var lastFrame = 0L
                analysis.setAnalyzer(executor) { image ->
                    try {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (alive.get() && now - lastFrame >= 150) {
                            lastFrame = now
                            val plane = image.planes[0]
                            val buffer = plane.buffer
                            val base = buffer.position()
                            val pixels = ByteArray(image.width * image.height)
                            for (y in 0 until image.height) for (x in 0 until image.width)
                                pixels[y * image.width + x] = buffer.get(base + y * plane.rowStride + x * plane.pixelStride)
                            val source = PlanarYUVLuminanceSource(pixels, image.width, image.height, 0, 0, image.width, image.height, false)
                            val codes = try { listOf(reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text) }
                                catch (_: ReaderException) { emptyList() } finally { reader.reset() }
                            main.execute { if (alive.get()) callback(codes) }
                        }
                    } catch (_: Exception) {
                        main.execute { if (alive.get()) status = 4 }
                    } finally { image.close() }
                }
                future.addListener({
                    if (alive.get()) try {
                        val camera = future.get(); provider = camera
                        val selector = when {
                            camera.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                            camera.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                            else -> null
                        }
                        if (selector == null) status = 3
                        else camera.bindToLifecycle(owner, selector, preview, analysis)
                    } catch (_: Exception) { if (alive.get()) status = 4 }
                }, main)
                onDispose {
                    alive.set(false); analysis.clearAnalyzer(); provider?.unbind(preview, analysis); executor.shutdown()
                }
            }
        }
        if (status != 0) Surface { CameraStatusPanel(status, request) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } }
    }
}

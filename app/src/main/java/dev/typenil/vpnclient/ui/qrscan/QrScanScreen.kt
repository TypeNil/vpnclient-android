package dev.typenil.vpnclient.ui.qrscan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.typenil.vpnclient.core.common.log.SecureLog
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val TAG = "QrScan"

/** Result key the scan destination writes into the previous back-stack
 *  entry's savedStateHandle before popping — see VpnApp. */
internal const val QR_RESULT_KEY = "qrResult"

@Composable
fun QrScanScreen(
    onBack: () -> Unit,
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QrScanViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // "Don't ask again" (or the second denial on Android 11+) makes the
    // permission request a silent no-op — the only way back is app settings.
    var permissionPermanentlyDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
        if (!granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.CAMERA,
            )
        ) {
            permissionPermanentlyDenied = true
        }
    }
    LaunchedEffect(Unit) {
        if (!cameraGranted && !permissionPermanentlyDenied) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Each rejection bumps the nonce so the notice re-arms per payload;
    // analysis keeps running underneath.
    var rejectNonce by remember { mutableIntStateOf(0) }
    var showReject by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    LaunchedEffect(rejectNonce) {
        if (rejectNonce > 0) {
            showReject = true
            delay(2_500)
            showReject = false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Scan QR code", style = MaterialTheme.typography.titleLarge)
        }

        when {
            !cameraGranted -> PermissionRequest(
                permanentlyDenied = permissionPermanentlyDenied,
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = {
                    activity?.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        },
                    )
                },
                onBack = onBack,
            )
            cameraFailed -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Camera unavailable",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { cameraFailed = false }) { Text("Retry") }
            }
            else -> Box(Modifier.fillMaxSize()) {
                CameraPreview(
                    onQr = { raw ->
                        when (val outcome = viewModel.onBarcode(raw)) {
                            is ScanOutcome.Found -> onResult(outcome.url)
                            ScanOutcome.Rejected -> rejectNonce++
                            ScanOutcome.Ignored -> Unit
                        }
                    },
                    onCameraError = { cameraFailed = true },
                    modifier = Modifier.fillMaxSize(),
                )
                ViewfinderOverlay()
                if (showReject) {
                    Text(
                        text = "Not a subscription link",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .padding(24.dp),
                    )
                }
            }
        }
    }
}

/**
 * Scanning affordance over the live preview: a rounded target frame with a
 * sweeping line so it's obvious the camera is actively decoding, plus the
 * aim hint below it.
 */
@Composable
private fun ViewfinderOverlay() {
    val sweep by rememberInfiniteTransition(label = "qrSweep").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sweep",
    )
    val sweepRange = with(LocalDensity.current) { 228.dp.toPx() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(240.dp)
                .border(
                    width = 2.dp,
                    color = Color.White.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(20.dp),
                ),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .align(Alignment.TopCenter)
                    // Lambda offset reads `sweep` in the layout phase, so the
                    // animation doesn't recompose the overlay every frame.
                    .offset { IntOffset(0, (sweepRange * sweep).roundToInt()) }
                    .height(2.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.primary,
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
        }
        Text(
            text = "Point the camera at a QR code",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PermissionRequest(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Camera access is needed to scan a subscription QR code",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(16.dp))
        if (permanentlyDenied) {
            Button(onClick = onOpenSettings) { Text("Open settings") }
        } else {
            Button(onClick = onRequest) { Text("Allow camera") }
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun CameraPreview(
    onQr: (String) -> Unit,
    onCameraError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // The view is created by AndroidView, then the effect binds against it —
    // keyed on both so a recreated view (config change) rebinds cleanly.
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    AndroidView(factory = { PreviewView(it).also { view -> previewView = view } }, modifier = modifier)

    DisposableEffect(lifecycleOwner, previewView) {
        val view = previewView ?: return@DisposableEffect onDispose {}
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
        val analysisExecutor = Executors.newSingleThreadExecutor()
        var cameraProvider: ProcessCameraProvider? = null
        var disposed = false
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                // The provider future can resolve after dispose — binding to
                // a dead lifecycle/executor would leak a zombie camera.
                if (disposed) return@addListener
                cameraProvider = runCatching { providerFuture.get() }.getOrElse {
                    SecureLog.e(TAG, "camera provider unavailable", it)
                    onCameraError()
                    return@addListener
                }
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(view.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    analyze(scanner, proxy, onQr)
                }
                try {
                    cameraProvider?.unbindAll()
                    cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (e: Exception) {
                    SecureLog.e(TAG, "camera bind failed", e)
                    onCameraError()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            disposed = true
            cameraProvider?.unbindAll()
            analysisExecutor.shutdown()
            scanner.close()
        }
    }
}

/**
 * Decode one frame. ML Kit delivers its Task listeners on the main thread,
 * so [onQr] — and any navigation it triggers — is main-thread safe. The raw
 * payload may be a secret URL: it is passed through, never logged.
 */
@OptIn(ExperimentalGetImage::class)
private fun analyze(scanner: BarcodeScanner, proxy: ImageProxy, onQr: (String) -> Unit) {
    val image = proxy.image
    if (image == null) {
        proxy.close()
        return
    }
    try {
        scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
            .addOnSuccessListener { barcodes ->
                barcodes.firstNotNullOfOrNull { it.rawValue }?.let(onQr)
            }
            .addOnFailureListener {
                SecureLog.w(TAG, "barcode analysis failed", it)
            }
            .addOnCompleteListener { proxy.close() }
    } catch (e: Exception) {
        // e.g. the scanner was closed while a frame was in flight — drop it.
        SecureLog.w(TAG, "barcode analysis rejected", e)
        proxy.close()
    }
}

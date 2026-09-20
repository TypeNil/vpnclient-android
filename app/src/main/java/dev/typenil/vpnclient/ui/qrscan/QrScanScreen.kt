package dev.typenil.vpnclient.ui.qrscan

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { cameraGranted = it }
    LaunchedEffect(Unit) {
        if (!cameraGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
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
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onBack = onBack,
            )
            cameraFailed -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Camera unavailable",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                if (showReject) {
                    Text(
                        text = "Not a subscription link",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit, onBack: () -> Unit) {
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
        Button(onClick = onRequest) { Text("Allow camera") }
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
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
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

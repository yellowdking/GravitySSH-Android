package com.gravityssh.app.ui.sync

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.gravityssh.app.ui.theme.AgDanger
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrCameraPreview(onScanned: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScanned by rememberUpdatedState(onScanned)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val delivered = remember { AtomicBoolean(false) }
    val disposed = remember { AtomicBoolean(false) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            runCatching { cameraProvider?.unbindAll() }
            cameraProvider = null
            executor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val mainExecutor = ContextCompat.getMainExecutor(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    if (disposed.get()) return@addListener
                    runCatching {
                        val provider = providerFuture.get()
                        if (disposed.get()) {
                            provider.unbindAll()
                            return@addListener
                        }
                        cameraProvider = provider
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        val reader = QRCodeReader()
                        var yuvBuf = ByteArray(0)
                        analysis.setAnalyzer(executor) { proxy ->
                            try {
                                if (!disposed.get() && !delivered.get()) {
                                    val plane = proxy.planes.firstOrNull()
                                    if (plane != null) {
                                        val buf = plane.buffer
                                        val len = buf.remaining()
                                        if (yuvBuf.size < len) yuvBuf = ByteArray(len)
                                        buf.get(yuvBuf, 0, len)
                                        decodeQr(proxy, plane.rowStride, yuvBuf, reader)?.let { text ->
                                            if (delivered.compareAndSet(false, true)) {
                                                mainExecutor.execute {
                                                    if (!disposed.get()) currentOnScanned(text)
                                                }
                                            }
                                        }
                                    }
                                }
                            } finally {
                                proxy.close()
                            }
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                        cameraError = null
                    }.onFailure { err ->
                        if (!disposed.get()) {
                            cameraError = "相机初始化失败: ${err.message ?: "请检查相机权限或设备状态"}"
                        }
                    }
                }, mainExecutor)
                previewView
            }
        )

        cameraError?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = AgDanger,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

private fun decodeQr(
    proxy: ImageProxy,
    rowStride: Int,
    data: ByteArray,
    reader: QRCodeReader
): String? {
    return runCatching {
        val source = PlanarYUVLuminanceSource(
            data, rowStride, proxy.height, 0, 0, proxy.width, proxy.height, false
        )
        reader.decode(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull().also { reader.reset() }
}

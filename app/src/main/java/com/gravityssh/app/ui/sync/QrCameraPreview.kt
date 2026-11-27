package com.gravityssh.app.ui.sync

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
import java.nio.ByteBuffer
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
                                    val width = proxy.width
                                    val height = proxy.height
                                    if (plane != null && width > 0 && height > 0) {
                                        val needed = width * height
                                        if (yuvBuf.size < needed) yuvBuf = ByteArray(needed)
                                        copyYPlaneTightly(
                                            buf = plane.buffer,
                                            width = width,
                                            height = height,
                                            rowStride = plane.rowStride,
                                            pixelStride = plane.pixelStride,
                                            dst = yuvBuf
                                        )
                                        decodeQr(width, height, yuvBuf, reader)?.let { text ->
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

private fun copyYPlaneTightly(
    buf: ByteBuffer,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
    dst: ByteArray
) {
    val basePos = buf.position()
    val limit = buf.limit()
    val needed = width * height
    if (pixelStride == 1 && rowStride == width) {
        val toRead = minOf(needed, (limit - basePos).coerceAtLeast(0))
        if (toRead > 0) buf.get(dst, 0, toRead)
        if (toRead < needed) dst.fill(0, toRead, needed)
        return
    }
    for (r in 0 until height) {
        val rowStart = basePos + r * rowStride
        val dstOff = r * width
        if (rowStart >= limit) {
            dst.fill(0, dstOff, needed)
            break
        }
        if (pixelStride == 1) {
            buf.position(rowStart)
            val toRead = minOf(width, limit - rowStart)
            buf.get(dst, dstOff, toRead)
            if (toRead < width) dst.fill(0, dstOff + toRead, dstOff + width)
        } else {
            for (c in 0 until width) {
                val idx = rowStart + c * pixelStride
                dst[dstOff + c] = if (idx < limit) buf.get(idx) else 0
            }
        }
    }
}

private fun decodeQr(
    width: Int,
    height: Int,
    data: ByteArray,
    reader: QRCodeReader
): String? {
    return runCatching {
        val source = PlanarYUVLuminanceSource(
            data, width, height, 0, 0, width, height, false
        )
        reader.decode(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull().also { reader.reset() }
}

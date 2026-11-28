package com.gravityssh.app.ui.sync

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodeHelper {
    private const val DENSE_URI_THRESHOLD = 110
    private const val QR_FG_ARGB = 0xFF0B0D12.toInt()

    fun encodeMatrix(content: String, size: Int = 1): BitMatrix? {
        if (content.isBlank()) return null
        return runCatching {
            val ecLevel = if (content.length > DENSE_URI_THRESHOLD) {
                ErrorCorrectionLevel.L
            } else {
                ErrorCorrectionLevel.M
            }
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ecLevel,
                EncodeHintType.MARGIN to 1
            )
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        }.getOrNull()
    }

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
        val safeSize = sizePx.coerceIn(128, 2048)
        val matrix = encodeMatrix(content, safeSize) ?: return null
        return runCatching {
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val rowOff = y * w
                for (x in 0 until w) {
                    pixels[rowOff + x] = if (matrix.get(x, y)) QR_FG_ARGB else AndroidColor.WHITE
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        }.getOrNull()
    }
}

/**
 * Pure Compose Canvas QR code renderer — crisp at any screen density without bitmap blur.
 * Defaults to 216.dp with adaptive ErrorCorrectionLevel.L for dense synctool://pair URIs.
 */
@Composable
fun QrCodeCanvas(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 216.dp
) {
    val matrix = remember(content) { QrCodeHelper.encodeMatrix(content) }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(10.dp)
    ) {
        if (matrix != null) {
            Canvas(modifier = Modifier.size(size - 20.dp)) {
                val cols = matrix.width
                val rows = matrix.height
                if (cols <= 0 || rows <= 0) return@Canvas
                val cellW = this.size.width / cols
                val cellH = this.size.height / rows
                for (y in 0 until rows) {
                    for (x in 0 until cols) {
                        if (matrix.get(x, y)) {
                            drawRect(
                                color = Color(0xFF0B0D12),
                                topLeft = Offset(x * cellW, y * cellH),
                                size = Size(cellW + 0.5f, cellH + 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}

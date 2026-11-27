package com.gravityssh.app.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object SyncCryptoPrimitives {
    fun signAppGate(
        appGateKey: String,
        method: String,
        path: String,
        timestampSec: Long,
        rawBody: ByteArray
    ): String {
        val bodyHash = sha256Hex(rawBody)
        val msg = "$method\n$path\n$timestampSec\n$bodyHash"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(appGateKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return bytesToHex(mac.doFinal(msg.toByteArray(Charsets.UTF_8)))
    }

    fun sha256Hex(data: ByteArray): String =
        bytesToHex(MessageDigest.getInstance("SHA-256").digest(data))

    fun bytesToHex(bytes: ByteArray): String {
        val hex = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hex[i * 2] = digits[v ushr 4]
            hex[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(hex)
    }

    fun gzipCompress(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(data) }
        return baos.toByteArray()
    }

    const val MAX_BUNDLE_PLAIN_BYTES = 16 * 1024 * 1024
    const val MAX_PAIR_REQUEST_PLAIN_BYTES = 64 * 1024

    fun gzipDecompress(
        data: ByteArray,
        maxBytes: Int = MAX_BUNDLE_PLAIN_BYTES,
        limitErrorMsg: String = "同步数据包超过 16 MiB 上限"
    ): ByteArray =
        GZIPInputStream(ByteArrayInputStream(data)).use { gis ->
            readStreamCapped(gis, maxBytes, limitErrorMsg)
        }

    fun readStreamCapped(input: InputStream, maxBytes: Int, limitErrorMsg: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) {
                throw IOException(limitErrorMsg)
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

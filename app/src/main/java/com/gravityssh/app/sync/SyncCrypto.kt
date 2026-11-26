package com.gravityssh.app.sync

import com.gravityssh.app.model.SyncBundle
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class EncryptedDropBlob(
    val channelId: String,
    val saltB64: String,
    val nonceB64: String,
    val ciphertextB64: String
)

object SyncCrypto {
    const val CROCKFORD_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    fun generateSyncCode(): String {
        val chars = CharArray(8) { CROCKFORD_ALPHABET[random.nextInt(CROCKFORD_ALPHABET.length)] }
        return formatCode(String(chars))
    }

    fun canonicalizeCode(input: String): String {
        var raw = input.trim()
        val idx = raw.indexOf("code=", ignoreCase = true)
        if (idx >= 0) raw = raw.substring(idx + 5).substringBefore('&')
        val cleaned = raw.uppercase().replace("-", "").replace(" ", "").replace("\n", "").replace("\r", "")
        require(cleaned.length == 8 && cleaned.all { it in CROCKFORD_ALPHABET }) {
            "同步码格式无效（应为 8 位 XXXX-XXXX）"
        }
        return cleaned
    }

    fun formatCode(canonicalOrFormatted: String): String {
        val c = canonicalizeCode(canonicalOrFormatted)
        return "${c.substring(0, 4)}-${c.substring(4, 8)}"
    }

    fun buildQrUri(code: String): String = "${SyncConfig.QR_SCHEME_PREFIX}${formatCode(code)}"

    fun deriveChannelId(code: String): String {
        val canonical = canonicalizeCode(code)
        return sha256Hex("${SyncConfig.CHANNEL_PREFIX}$canonical".toByteArray(Charsets.UTF_8))
    }

    fun deriveAesKey(canonicalCode: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(canonicalCode.toCharArray(), salt, SyncConfig.PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return key
    }

    fun encryptBundle(
        bundle: SyncBundle,
        code: String,
        fixedSalt: ByteArray? = null,
        fixedNonce: ByteArray? = null
    ): EncryptedDropBlob {
        val canonical = canonicalizeCode(code)
        val channelId = deriveChannelId(canonical)
        val salt = fixedSalt?.copyOf() ?: ByteArray(16).also { random.nextBytes(it) }
        val nonce = fixedNonce?.copyOf() ?: ByteArray(12).also { random.nextBytes(it) }
        val compressed = SyncCryptoPrimitives.gzipCompress(json.encodeToString(bundle).toByteArray(Charsets.UTF_8))
        val key = deriveAesKey(canonical, salt)
        val ct = SyncCryptoPrimitives.aesGcmEncrypt(key, nonce, compressed, channelId.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return EncryptedDropBlob(channelId, b64.encodeToString(salt), b64.encodeToString(nonce), b64.encodeToString(ct))
    }

    fun decryptBundle(saltB64: String, nonceB64: String, ciphertextB64: String, code: String): SyncBundle {
        val rawJson = decryptRawJson(saltB64, nonceB64, ciphertextB64, code)
        return json.decodeFromString(SyncBundle.serializer(), rawJson)
    }

    fun decryptRawJson(saltB64: String, nonceB64: String, ciphertextB64: String, code: String): String {
        val canonical = canonicalizeCode(code)
        val channelId = deriveChannelId(canonical)
        val b64 = Base64.getDecoder()
        val key = deriveAesKey(canonical, b64.decode(saltB64))
        val compressed = SyncCryptoPrimitives.aesGcmDecrypt(
            key, b64.decode(nonceB64), b64.decode(ciphertextB64), channelId.toByteArray(Charsets.UTF_8)
        )
        return SyncCryptoPrimitives.gzipDecompress(compressed).toString(Charsets.UTF_8)
    }

    fun signAppGate(appGateKey: String, method: String, path: String, timestampSec: Long, rawBody: ByteArray): String =
        SyncCryptoPrimitives.signAppGate(appGateKey, method, path, timestampSec, rawBody)

    fun sha256Hex(data: ByteArray): String = SyncCryptoPrimitives.sha256Hex(data)

    fun bytesToHex(bytes: ByteArray): String = SyncCryptoPrimitives.bytesToHex(bytes)
}

package com.gravityssh.app.sync

import com.gravityssh.app.model.AppJson
import com.gravityssh.app.model.SyncBundle
import com.gravityssh.app.vault.CryptoUtils
import kotlinx.serialization.encodeToString
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException

data class EncryptedDropBlob(
    val channelId: String,
    val saltB64: String,
    val nonceB64: String,
    val ciphertextB64: String
)

object SyncCrypto {
    const val CROCKFORD_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val MIN_CT_LEN = 16
    private val random = SecureRandom()
    private val json = AppJson.json

    fun generateSyncCode(): String {
        val chars = CharArray(8) { CROCKFORD_ALPHABET[random.nextInt(CROCKFORD_ALPHABET.length)] }
        return formatCode(String(chars))
    }

    fun canonicalizeCode(input: String): String {
        var raw = input.trim()
        val idx = raw.indexOf("code=", ignoreCase = true)
        if (idx >= 0) raw = raw.substring(idx + 5).substringBefore('&')
        val cleaned = raw.uppercase()
            .replace("-", "")
            .replace(" ", "")
            .replace("\t", "")
            .replace("\n", "")
            .replace("\r", "")
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

    fun deriveAesKey(canonicalCode: String, salt: ByteArray): ByteArray =
        CryptoUtils.pbkdf2Sha256(canonicalCode, salt, SyncConfig.PBKDF2_ITERATIONS, 256)

    fun encryptBundle(
        bundle: SyncBundle,
        code: String,
        fixedSalt: ByteArray? = null,
        fixedNonce: ByteArray? = null
    ): EncryptedDropBlob {
        val canonical = canonicalizeCode(code)
        val channelId = deriveChannelId(canonical)
        val salt = fixedSalt?.copyOf() ?: ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val nonce = fixedNonce?.copyOf() ?: ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        require(salt.size == SALT_LEN) { "同步加密盐值长度无效" }
        require(nonce.size == NONCE_LEN) { "同步加密随机数长度无效" }
        val compressed = SyncCryptoPrimitives.gzipCompress(json.encodeToString(bundle).toByteArray(Charsets.UTF_8))
        val key = deriveAesKey(canonical, salt)
        val ct = try {
            CryptoUtils.aesGcmEncrypt(key, nonce, compressed, channelId.toByteArray(Charsets.UTF_8))
        } finally {
            CryptoUtils.zero(key)
        }
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
        val salt = decodeB64(b64, saltB64, "同步加密盐值格式无效")
        val nonce = decodeB64(b64, nonceB64, "同步加密随机数格式无效")
        val ct = decodeB64(b64, ciphertextB64, "同步密文格式无效")
        require(salt.size == SALT_LEN) { "同步加密盐值长度无效" }
        require(nonce.size == NONCE_LEN) { "同步加密随机数长度无效" }
        require(ct.size >= MIN_CT_LEN) { "同步密文数据不完整" }

        val key = deriveAesKey(canonical, salt)
        val compressed = try {
            CryptoUtils.aesGcmDecrypt(key, nonce, ct, channelId.toByteArray(Charsets.UTF_8))
        } catch (e: AEADBadTagException) {
            throw SecurityException("同步码错误或数据已被篡改，解密失败", e)
        } finally {
            CryptoUtils.zero(key)
        }
        return SyncCryptoPrimitives.gzipDecompress(compressed).toString(Charsets.UTF_8)
    }

    private fun decodeB64(decoder: Base64.Decoder, value: String, errorMsg: String): ByteArray =
        try {
            decoder.decode(value)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(errorMsg, e)
        }

    fun signAppGate(appGateKey: String, method: String, path: String, timestampSec: Long, rawBody: ByteArray): String =
        SyncCryptoPrimitives.signAppGate(appGateKey, method, path, timestampSec, rawBody)

    fun sha256Hex(data: ByteArray): String = SyncCryptoPrimitives.sha256Hex(data)

    fun bytesToHex(bytes: ByteArray): String = SyncCryptoPrimitives.bytesToHex(bytes)
}

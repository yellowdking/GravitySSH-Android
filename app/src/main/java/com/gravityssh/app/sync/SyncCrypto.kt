package com.gravityssh.app.sync

import com.gravityssh.app.model.AppJson
import com.gravityssh.app.model.PairRequestPayload
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
        return SyncCryptoPrimitives.sha256Hex("${SyncConfig.CHANNEL_PREFIX}$canonical".toByteArray(Charsets.UTF_8))
    }

    fun generateHexToken32(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return SyncCryptoPrimitives.bytesToHex(bytes)
    }

    fun deriveDualSecret(sendCode: String, recvCode: String): String =
        "${canonicalizeCode(sendCode)}:${canonicalizeCode(recvCode)}"

    fun deriveDualAad(channelId: String, requestId: String, canonicalRecv: String): String {
        require(channelId.isNotBlank()) { "通道 ID 不能为空" }
        require(requestId.isNotBlank()) { "请求 ID 不能为空" }
        require(canonicalRecv.isNotBlank()) { "接收确认码不能为空" }
        return "$channelId:$requestId:$canonicalRecv"
    }

    fun deriveAesKey(canonicalCode: String, salt: ByteArray): ByteArray =
        CryptoUtils.pbkdf2Sha256(canonicalCode, salt, SyncConfig.PBKDF2_ITERATIONS, 256)

    fun encryptPairRequest(
        sendCode: String,
        info: PairRequestPayload,
        fixedSalt: ByteArray? = null,
        fixedNonce: ByteArray? = null
    ): EncryptedDropBlob {
        val canonicalSend = canonicalizeCode(sendCode)
        val channelId = deriveChannelId(canonicalSend)
        return encryptRawJsonWithSecret(
            rawJson = json.encodeToString(info),
            secret = canonicalSend,
            aad = channelId,
            channelId = channelId,
            fixedSalt = fixedSalt,
            fixedNonce = fixedNonce
        )
    }

    fun decryptPairRequest(
        sendCode: String,
        saltB64: String,
        nonceB64: String,
        ctB64: String
    ): PairRequestPayload {
        val canonicalSend = canonicalizeCode(sendCode)
        val channelId = deriveChannelId(canonicalSend)
        val rawJson = decryptRawJsonWithSecret(
            saltB64 = saltB64,
            nonceB64 = nonceB64,
            ciphertextB64 = ctB64,
            secret = canonicalSend,
            aad = channelId,
            maxPlainBytes = SyncCryptoPrimitives.MAX_PAIR_REQUEST_PLAIN_BYTES,
            limitErrorMsg = "配对验证请求超过 64 KiB 上限"
        )
        return json.decodeFromString(PairRequestPayload.serializer(), rawJson)
    }

    fun encryptBundleDual(
        bundle: SyncBundle,
        sendCode: String,
        recvCode: String,
        requestId: String,
        fixedSalt: ByteArray? = null,
        fixedNonce: ByteArray? = null
    ): EncryptedDropBlob {
        val canonicalSend = canonicalizeCode(sendCode)
        val canonicalRecv = canonicalizeCode(recvCode)
        val channelId = deriveChannelId(canonicalSend)
        val dualSecret = deriveDualSecret(canonicalSend, canonicalRecv)
        val dualAad = deriveDualAad(channelId, requestId, canonicalRecv)
        return encryptRawJsonWithSecret(
            rawJson = json.encodeToString(bundle),
            secret = dualSecret,
            aad = dualAad,
            channelId = channelId,
            fixedSalt = fixedSalt,
            fixedNonce = fixedNonce
        )
    }

    fun decryptBundleDual(
        saltB64: String,
        nonceB64: String,
        ctB64: String,
        sendCode: String,
        recvCode: String,
        requestId: String
    ): SyncBundle {
        val canonicalSend = canonicalizeCode(sendCode)
        val canonicalRecv = canonicalizeCode(recvCode)
        val channelId = deriveChannelId(canonicalSend)
        val dualSecret = deriveDualSecret(canonicalSend, canonicalRecv)
        val dualAad = deriveDualAad(channelId, requestId, canonicalRecv)
        val rawJson = decryptRawJsonWithSecret(saltB64, nonceB64, ctB64, dualSecret, dualAad)
        return json.decodeFromString(SyncBundle.serializer(), rawJson)
    }

    private fun encryptRawJsonWithSecret(
        rawJson: String,
        secret: String,
        aad: String,
        channelId: String,
        fixedSalt: ByteArray? = null,
        fixedNonce: ByteArray? = null
    ): EncryptedDropBlob {
        val salt = fixedSalt?.copyOf() ?: ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val nonce = fixedNonce?.copyOf() ?: ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        require(salt.size == SALT_LEN) { "同步加密盐值长度无效" }
        require(nonce.size == NONCE_LEN) { "同步加密随机数长度无效" }
        val compressed = SyncCryptoPrimitives.gzipCompress(rawJson.toByteArray(Charsets.UTF_8))
        val key = deriveAesKey(secret, salt)
        val ct = try {
            CryptoUtils.aesGcmEncrypt(key, nonce, compressed, aad.toByteArray(Charsets.UTF_8))
        } finally {
            CryptoUtils.zero(key)
        }
        val b64 = Base64.getEncoder()
        return EncryptedDropBlob(channelId, b64.encodeToString(salt), b64.encodeToString(nonce), b64.encodeToString(ct))
    }

    private fun decryptRawJsonWithSecret(
        saltB64: String,
        nonceB64: String,
        ciphertextB64: String,
        secret: String,
        aad: String,
        maxPlainBytes: Int = SyncCryptoPrimitives.MAX_BUNDLE_PLAIN_BYTES,
        limitErrorMsg: String = "同步数据包超过 16 MiB 上限"
    ): String {
        val b64 = Base64.getDecoder()
        val salt = decodeB64(b64, saltB64, "同步加密盐值格式无效")
        val nonce = decodeB64(b64, nonceB64, "同步加密随机数格式无效")
        val ct = decodeB64(b64, ciphertextB64, "同步密文格式无效")
        require(salt.size == SALT_LEN) { "同步加密盐值长度无效" }
        require(nonce.size == NONCE_LEN) { "同步加密随机数长度无效" }
        require(ct.size >= MIN_CT_LEN) { "同步密文数据不完整" }

        val key = deriveAesKey(secret, salt)
        val compressed = try {
            CryptoUtils.aesGcmDecrypt(key, nonce, ct, aad.toByteArray(Charsets.UTF_8))
        } catch (e: AEADBadTagException) {
            throw SecurityException("同步码错误或数据已被篡改，解密失败", e)
        } finally {
            CryptoUtils.zero(key)
        }
        return SyncCryptoPrimitives.gzipDecompress(compressed, maxPlainBytes, limitErrorMsg)
            .toString(Charsets.UTF_8)
    }

    private fun decodeB64(decoder: Base64.Decoder, value: String, errorMsg: String): ByteArray =
        try {
            decoder.decode(value)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(errorMsg, e)
        }

    fun signAppGate(appGateKey: String, method: String, path: String, timestampSec: Long, rawBody: ByteArray): String =
        SyncCryptoPrimitives.signAppGate(appGateKey, method, path, timestampSec, rawBody)

    fun bytesToHex(bytes: ByteArray): String = SyncCryptoPrimitives.bytesToHex(bytes)
}

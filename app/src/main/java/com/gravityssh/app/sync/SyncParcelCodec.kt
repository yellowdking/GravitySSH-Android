package com.gravityssh.app.sync

import com.gravityssh.app.model.AppJson
import com.gravityssh.app.model.SyncBundle
import com.gravityssh.app.model.SyncInboxItemUi
import com.yellowdking.synctool.client.InboxPreview
import com.yellowdking.synctool.crypto.KeysAndAuth
import com.yellowdking.synctool.envelope.BlobInput
import com.yellowdking.synctool.envelope.ErrorCode
import com.yellowdking.synctool.envelope.ParcelInput
import com.yellowdking.synctool.envelope.ReceivedParcel
import com.yellowdking.synctool.envelope.SyncError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@Serializable
data class VaultParcelSummaryPayload(
    @SerialName("bundleSchemaVersion") val bundleSchemaVersion: Int = 1,
    @SerialName("exportedAt") val exportedAt: String = "",
    @SerialName("sourcePlatform") val sourcePlatform: String = "android",
    @SerialName("sourceDevice") val sourceDevice: String = "",
    @SerialName("serverCount") val serverCount: Int = 0,
    @SerialName("keyCount") val keyCount: Int = 0,
    @SerialName("shortcutCount") val shortcutCount: Int = 0,
    @SerialName("templateCount") val templateCount: Int = 0
)

/**
 * Canonical Always-2-Part `gravityssh.vault.v1` parcel encoder/decoder with 100% cross-platform
 * parity against Windows `GravitySSH v1.5.0`:
 * - `part_id = 0` (`Manifest.inlinePayload`): `< 512 B` counts summary JSON (NEVER contains SSH private keys or passwords).
 * - `part_id = 1` (`BlobInput`): gzipped UTF-8 `SyncBundle` JSON (`role = "vault_bundle"`, `name = "vault_bundle.json.gz"`,
 *   `mime = "application/gzip"`, `encoding = "gzip"`), zero plaintext temp files on sender.
 * - Receiver accepts `role in {"vault_bundle", "vault_dump"}` (16 MiB gunzip cap) + empty-blob `inlinePayload` fallback.
 */
object SyncParcelCodec {

    private const val ROLE_VAULT_BUNDLE = "vault_bundle"
    private const val ROLE_VAULT_DUMP = "vault_dump"
    private const val BLOB_FILE_NAME = "vault_bundle.json.gz"
    private const val MIME_GZIP = "application/gzip"
    private const val ENCODING_GZIP = "gzip"
    private const val ENCODING_IDENTITY = "identity"

    private val ACCEPTED_ROLES = setOf(ROLE_VAULT_BUNDLE, ROLE_VAULT_DUMP)

    fun encodeVaultParcel(bundle: SyncBundle): ParcelInput {
        val exportedAt = bundle.exportedAt.ifBlank { Instant.now().toString() }
        val sourcePlatform = bundle.sourcePlatform.ifBlank { "android" }
        val sourceDevice = bundle.sourceDevice.ifBlank { "Android" }.take(64)
        val normalizedBundle = bundle.copy(
            exportedAt = exportedAt,
            sourcePlatform = sourcePlatform,
            sourceDevice = sourceDevice
        )

        val summary = VaultParcelSummaryPayload(
            bundleSchemaVersion = normalizedBundle.schemaVersion,
            exportedAt = exportedAt,
            sourcePlatform = sourcePlatform,
            sourceDevice = sourceDevice,
            serverCount = normalizedBundle.servers.size,
            keyCount = normalizedBundle.sshKeys.size,
            shortcutCount = normalizedBundle.shortcuts.size,
            templateCount = normalizedBundle.templates.size
        )
        val inlineObj = AppJson.json.encodeToJsonElement(VaultParcelSummaryPayload.serializer(), summary).jsonObject
        val inlineUtf8 = AppJson.json.encodeToString(JsonObject.serializer(), inlineObj)
            .toByteArray(StandardCharsets.UTF_8)
        if (inlineUtf8.size >= SyncConfig.MAX_INLINE_SUMMARY_BYTES) {
            throw SyncError(
                code = ErrorCode.ERR_MANIFEST_INVALID,
                rawMessage = "inline summary payload exceeds ${SyncConfig.MAX_INLINE_SUMMARY_BYTES} bytes (${inlineUtf8.size})"
            )
        }

        val rawJsonBytes = AppJson.json.encodeToString(SyncBundle.serializer(), normalizedBundle)
            .toByteArray(StandardCharsets.UTF_8)
        val gzippedBytes = try {
            gzipCompress(rawJsonBytes)
        } finally {
            KeysAndAuth.zeroBytes(rawJsonBytes)
        }

        val blobInput = BlobInput.fromBytes(
            bytes = gzippedBytes,
            name = BLOB_FILE_NAME,
            role = ROLE_VAULT_BUNDLE,
            mime = MIME_GZIP,
            encoding = ENCODING_GZIP
        )

        return ParcelInput(
            appId = SyncConfig.APP_ID,
            schema = SyncConfig.PARCEL_SCHEMA,
            inlinePayload = inlineObj,
            blobs = listOf(blobInput)
        )
    }

    fun decodeVaultParcel(parcel: ReceivedParcel): SyncBundle {
        val manifest = parcel.manifest
        if (manifest.appId != SyncConfig.APP_ID) {
            throw SyncError(
                code = ErrorCode.ERR_MANIFEST_INVALID,
                rawMessage = "unexpected parcel app_id: ${manifest.appId}"
            )
        }
        if (manifest.schema != SyncConfig.PARCEL_SCHEMA) {
            throw SyncError(
                code = ErrorCode.ERR_UNSUPPORTED_VERSION,
                rawMessage = "unsupported parcel schema: ${manifest.schema}"
            )
        }

        val bundleBlob = parcel.blobs.firstOrNull { it.descriptor.role in ACCEPTED_ROLES }
        val decoded: SyncBundle = try {
            when {
                bundleBlob != null -> {
                    val enc = bundleBlob.descriptor.encoding?.trim()?.lowercase().orEmpty()
                    val plainBytes = bundleBlob.openStream().use { fileStream ->
                        when (enc) {
                            ENCODING_GZIP -> GZIPInputStream(fileStream).use { gz ->
                                readStreamCapped(gz, SyncConfig.MAX_BUNDLE_GUNZIP_BYTES)
                            }
                            "", ENCODING_IDENTITY -> readStreamCapped(fileStream, SyncConfig.MAX_BUNDLE_GUNZIP_BYTES)
                            else -> throw SyncError(
                                code = ErrorCode.ERR_MANIFEST_INVALID,
                                rawMessage = "unsupported vault_bundle encoding: $enc"
                            )
                        }
                    }
                    try {
                        val utf8 = String(plainBytes, StandardCharsets.UTF_8)
                        AppJson.json.decodeFromString(SyncBundle.serializer(), utf8)
                    } catch (e: Exception) {
                        throw SyncError(
                            code = ErrorCode.ERR_MANIFEST_INVALID,
                            rawMessage = "failed to decode SyncBundle JSON",
                            rawCause = e
                        )
                    } finally {
                        KeysAndAuth.zeroBytes(plainBytes)
                    }
                }
                parcel.blobs.isEmpty() && manifest.inlinePayload != null -> {
                    val inlineObj = manifest.inlinePayload ?: throw SyncError(
                        code = ErrorCode.ERR_MANIFEST_INVALID,
                        rawMessage = "parcel missing inline payload"
                    )
                    val hasBundleShape = "servers" in inlineObj ||
                        "sshKeys" in inlineObj ||
                        "shortcuts" in inlineObj ||
                        "templates" in inlineObj ||
                        "schemaVersion" in inlineObj
                    if (!hasBundleShape) {
                        throw SyncError(
                            code = ErrorCode.ERR_MANIFEST_INVALID,
                            rawMessage = "parcel has neither vault_bundle blob nor inline SyncBundle"
                        )
                    }
                    try {
                        AppJson.json.decodeFromJsonElement(SyncBundle.serializer(), inlineObj)
                    } catch (e: Exception) {
                        throw SyncError(
                            code = ErrorCode.ERR_MANIFEST_INVALID,
                            rawMessage = "failed to decode inline SyncBundle fallback",
                            rawCause = e
                        )
                    }
                }
                else -> throw SyncError(
                    code = ErrorCode.ERR_MANIFEST_INVALID,
                    rawMessage = "missing vault_bundle attachment in parcel"
                )
            }
        } finally {
            for (b in parcel.blobs) {
                runCatching { b.safeFile.delete() }
            }
        }

        return decoded.copy(
            sourcePlatform = decoded.sourcePlatform.ifBlank { manifest.sender.platform.orEmpty().ifBlank { "unknown" } },
            sourceDevice = decoded.sourceDevice.ifBlank { manifest.sender.label },
            exportedAt = decoded.exportedAt.ifBlank {
                if (manifest.createdAt > 0L) Instant.ofEpochSecond(manifest.createdAt).toString() else ""
            }
        )
    }

    fun toInboxItemUi(preview: InboxPreview): SyncInboxItemUi {
        val m = preview.manifest
        if (m.schema != SyncConfig.PARCEL_SCHEMA) {
            return SyncInboxItemUi(
                itemId = preview.itemId,
                msgId = m.msgId,
                senderDeviceId = m.sender.deviceId,
                senderLabel = m.sender.label,
                senderPlatform = m.sender.platform.orEmpty(),
                createdAt = preview.createdAt,
                expiresAt = preview.expiresAt,
                totalBytes = preview.totalBytes,
                serverCount = 0,
                keyCount = 0,
                shortcutCount = 0,
                templateCount = 0,
                pairingBroken = false,
                errorMessage = "不支持的同步包裹版本 (${m.schema})"
            )
        }

        val summary = m.inlinePayload?.let { obj ->
            runCatching {
                AppJson.json.decodeFromJsonElement(VaultParcelSummaryPayload.serializer(), obj)
            }.getOrNull()
        }

        return SyncInboxItemUi(
            itemId = preview.itemId,
            msgId = m.msgId,
            senderDeviceId = m.sender.deviceId,
            senderLabel = m.sender.label.ifBlank { summary?.sourceDevice.orEmpty() },
            senderPlatform = summary?.sourcePlatform?.ifBlank { null }
                ?: m.sender.platform.orEmpty(),
            createdAt = preview.createdAt,
            expiresAt = preview.expiresAt,
            totalBytes = preview.totalBytes,
            serverCount = summary?.serverCount ?: 0,
            keyCount = summary?.keyCount ?: 0,
            shortcutCount = summary?.shortcutCount ?: 0,
            templateCount = summary?.templateCount ?: 0,
            pairingBroken = false,
            errorMessage = ""
        )
    }

    internal fun gzipCompress(raw: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(minOf(raw.size, 65536))
        GZIPOutputStream(bos).use { gz ->
            gz.write(raw)
        }
        return bos.toByteArray()
    }

    internal fun readStreamCapped(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            if (r > 0) {
                total += r.toLong()
                if (total > maxBytes.toLong()) {
                    throw SyncError(
                        code = ErrorCode.ERR_MANIFEST_INVALID,
                        rawMessage = "uncompressed SyncBundle exceeds ${maxBytes / (1024 * 1024)} MiB safety limit",
                        rawCause = IOException("SyncBundle gunzip limit exceeded")
                    )
                }
                out.write(buf, 0, r)
            }
        }
        return out.toByteArray()
    }
}

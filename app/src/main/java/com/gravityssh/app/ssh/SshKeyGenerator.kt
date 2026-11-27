package com.gravityssh.app.ssh

import com.gravityssh.app.model.SSHKeyRecord
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import java.io.StringWriter
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

object SshKeyGenerator {

    fun generateEd25519(name: String, comment: String = "gravityssh-android"): SSHKeyRecord {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair = gen.generateKeyPair()

        val pubBlob = OpenSSHPublicKeyUtil.encodePublicKey(pair.public)
        val privBytes = OpenSSHPrivateKeyUtil.encodePrivateKey(pair.private)
        val pem = toPem("OPENSSH PRIVATE KEY", privBytes)
        val pubB64 = Base64.getEncoder().encodeToString(pubBlob)
        val suffix = if (comment.isNotBlank()) " ${comment.trim()}" else ""

        return SSHKeyRecord(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Ed25519 Key" },
            keyType = "ed25519",
            fingerprint = sha256Fingerprint(pubBlob),
            publicKey = "ssh-ed25519 $pubB64$suffix",
            privateKeyPem = pem,
            comment = comment.trim(),
            createdAt = Instant.now().toString()
        )
    }

    fun generateRsa4096(name: String, comment: String = "gravityssh-android"): SSHKeyRecord {
        val gen = RSAKeyPairGenerator()
        gen.init(RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), SecureRandom(), 4096, 80))
        val pair = gen.generateKeyPair()

        val pubBlob = OpenSSHPublicKeyUtil.encodePublicKey(pair.public)
        val privBytes = OpenSSHPrivateKeyUtil.encodePrivateKey(pair.private)
        val pem = toPem("OPENSSH PRIVATE KEY", privBytes)
        val pubB64 = Base64.getEncoder().encodeToString(pubBlob)
        val suffix = if (comment.isNotBlank()) " ${comment.trim()}" else ""

        return SSHKeyRecord(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "RSA-4096 Key" },
            keyType = "rsa",
            fingerprint = sha256Fingerprint(pubBlob),
            publicKey = "ssh-rsa $pubB64$suffix",
            privateKeyPem = pem,
            comment = comment.trim(),
            createdAt = Instant.now().toString()
        )
    }

    fun importKey(
        name: String,
        privateKeyPem: String,
        passphrase: String = "",
        comment: String = ""
    ): SSHKeyRecord {
        val trimmedPem = privateKeyPem.trim()
        require(trimmedPem.isNotEmpty()) { "私钥内容不能为空" }
        val finder = if (passphrase.isNotEmpty()) PasswordUtils.createOneOff(passphrase.toCharArray()) else null
        val pubKey: PublicKey = SSHClient().use { ssh ->
            val provider = ssh.loadKeys(trimmedPem, null, finder)
            provider.public
        }
        val pubBlob = Buffer.PlainBuffer().putPublicKey(pubKey).compactData
        val algoName = runCatching { Buffer.PlainBuffer(pubBlob).readString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: sshAlgoName(pubKey.algorithm)
        val pubB64 = Base64.getEncoder().encodeToString(pubBlob)
        val suffix = if (comment.isNotBlank()) " ${comment.trim()}" else ""

        return SSHKeyRecord(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Imported Key" },
            keyType = normalizeKeyType(algoName),
            fingerprint = sha256Fingerprint(pubBlob),
            publicKey = "$algoName $pubB64$suffix",
            privateKeyPem = trimmedPem,
            passphrase = passphrase,
            comment = comment.trim(),
            createdAt = Instant.now().toString()
        )
    }

    fun sha256Fingerprint(publicKeyWireBlob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyWireBlob)
        val b64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return "SHA256:$b64"
    }

    private fun toPem(type: String, content: ByteArray): String {
        val sw = StringWriter()
        PemWriter(sw).use { it.writeObject(PemObject(type, content)) }
        return sw.toString().trim()
    }

    private fun normalizeKeyType(algo: String): String = when {
        algo.contains("Ed", ignoreCase = true) -> "ed25519"
        algo.contains("RSA", ignoreCase = true) -> "rsa"
        algo.contains("EC", ignoreCase = true) -> "ecdsa"
        else -> algo.lowercase()
    }

    private fun sshAlgoName(algo: String): String = when {
        algo.contains("Ed", ignoreCase = true) -> "ssh-ed25519"
        algo.contains("RSA", ignoreCase = true) -> "ssh-rsa"
        algo.contains("EC", ignoreCase = true) -> "ecdsa-sha2-nistp256"
        else -> algo.lowercase()
    }
}

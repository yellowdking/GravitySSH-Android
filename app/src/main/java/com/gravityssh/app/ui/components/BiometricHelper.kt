package com.gravityssh.app.ui.components

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.gravityssh.app.vault.AndroidKeystoreMasterKeyProvider
import com.gravityssh.app.vault.MasterKeyProvider

object BiometricHelper {
    private const val STRONG_ONLY = BiometricManager.Authenticators.BIOMETRIC_STRONG
    private const val API30_AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    private val CRYPTO_CHALLENGE = "gravityssh-biometric-auth-v1".toByteArray(Charsets.UTF_8)

    fun isDeviceSecure(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isDeviceSecure == true
    }

    fun canStrongBiometric(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(STRONG_ONLY) == BiometricManager.BIOMETRIC_SUCCESS

    fun canAuthenticate(context: Context): Boolean {
        val bm = BiometricManager.from(context)
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                bm.canAuthenticate(API30_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                bm.canAuthenticate(STRONG_ONLY) == BiometricManager.BIOMETRIC_SUCCESS || isDeviceSecure(context)
            else ->
                bm.canAuthenticate(STRONG_ONLY) == BiometricManager.BIOMETRIC_SUCCESS
        }
    }

    fun authenticate(
        context: Context,
        title: String = "解锁 GravitySSH 保险库",
        subtitle: String = "请验证强生物识别或设备锁屏凭据",
        negativeButtonText: String = "使用密码",
        cryptoProvider: MasterKeyProvider? = null,
        allowDeviceCredentialFallback: Boolean = true,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val activity = context.findFragmentActivity()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            onError("当前界面不支持生物识别")
            return
        }
        val provider = cryptoProvider ?: AndroidKeystoreMasterKeyProvider()
        val executor = ContextCompat.getMainExecutor(activity)

        fun launchPlainDeviceCredentialFallback() {
            try {
                val fallbackCallback = object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                            errorCode != BiometricPrompt.ERROR_USER_CANCELED
                        ) {
                            onError(errString.toString())
                        }
                    }
                }
                val fallbackPrompt = BiometricPrompt(activity, executor, fallbackCallback)
                val fbBuilder = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fbBuilder.setAllowedAuthenticators(API30_AUTHENTICATORS)
                } else {
                    @Suppress("DEPRECATION")
                    fbBuilder.setDeviceCredentialAllowed(true)
                }
                fallbackPrompt.authenticate(fbBuilder.build())
            } catch (e: Exception) {
                onError(e.message ?: "无法启动锁屏凭据验证")
            }
        }

        fun makeCryptoCallback(
            requireCrypto: Boolean,
            fallbackOnNegativeButton: Boolean
        ): BiometricPrompt.AuthenticationCallback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (requireCrypto) {
                    val authCipher = result.cryptoObject?.cipher
                    if (authCipher == null) {
                        onError("硬件密钥认证对象缺失，请重试")
                        return
                    }
                    val verified = runCatching { authCipher.doFinal(CRYPTO_CHALLENGE) }.isSuccess
                    if (!verified) {
                        onError("硬件密钥认证校验失败，请重试")
                        return
                    }
                }
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON && fallbackOnNegativeButton) {
                    launchPlainDeviceCredentialFallback()
                    return
                }
                if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    errorCode != BiometricPrompt.ERROR_USER_CANCELED
                ) {
                    onError(errString.toString())
                }
            }
        }

        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    val promptInfo = BiometricPrompt.PromptInfo.Builder()
                        .setTitle(title)
                        .setSubtitle(subtitle)
                        .setAllowedAuthenticators(API30_AUTHENTICATORS)
                        .build()
                    val cipher = provider.createBiometricAuthCipher()
                    if (cipher != null) {
                        val cryptoPrompt = BiometricPrompt(
                            activity,
                            executor,
                            makeCryptoCallback(requireCrypto = true, fallbackOnNegativeButton = false)
                        )
                        val launched = runCatching {
                            cryptoPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
                            true
                        }.getOrElse {
                            provider.resetBiometricAuthKey()
                            val retryCipher = provider.createBiometricAuthCipher()
                            if (retryCipher != null) {
                                runCatching {
                                    cryptoPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(retryCipher))
                                    true
                                }.getOrDefault(false)
                            } else {
                                false
                            }
                        }
                        if (!launched) {
                            if (allowDeviceCredentialFallback) {
                                launchPlainDeviceCredentialFallback()
                            } else {
                                onError("硬件生物识别密钥认证不可用")
                            }
                        }
                    } else if (allowDeviceCredentialFallback) {
                        launchPlainDeviceCredentialFallback()
                    } else {
                        onError("当前设备硬件密钥认证不可用")
                    }
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                    // Prior to API 30, androidx.biometric rejects CryptoObject + DEVICE_CREDENTIAL together.
                    // Use BIOMETRIC_STRONG + CryptoObject when enrolled, with negative button / fallback to DEVICE_CREDENTIAL.
                    val strongAvailable = canStrongBiometric(context)
                    val deviceSecure = isDeviceSecure(context)
                    val cipher = if (strongAvailable) provider.createBiometricAuthCipher() else null
                    if (strongAvailable && cipher != null) {
                        val offerCredFallback = allowDeviceCredentialFallback && deviceSecure
                        val strongInfo = BiometricPrompt.PromptInfo.Builder()
                            .setTitle(title)
                            .setSubtitle(subtitle)
                            .setAllowedAuthenticators(STRONG_ONLY)
                            .setNegativeButtonText(if (offerCredFallback) "使用锁屏密码" else negativeButtonText)
                            .build()
                        val cryptoPrompt = BiometricPrompt(
                            activity,
                            executor,
                            makeCryptoCallback(requireCrypto = true, fallbackOnNegativeButton = offerCredFallback)
                        )
                        val launched = runCatching {
                            cryptoPrompt.authenticate(strongInfo, BiometricPrompt.CryptoObject(cipher))
                            true
                        }.getOrDefault(false)
                        if (!launched) {
                            if (offerCredFallback) {
                                launchPlainDeviceCredentialFallback()
                            } else {
                                onError("无法启动强生物识别验证")
                            }
                        }
                    } else if (allowDeviceCredentialFallback && deviceSecure) {
                        launchPlainDeviceCredentialFallback()
                    } else {
                        onError("设备未开启强生物识别或锁屏凭据")
                    }
                }

                else -> {
                    val strongInfo = BiometricPrompt.PromptInfo.Builder()
                        .setTitle(title)
                        .setSubtitle(subtitle)
                        .setAllowedAuthenticators(STRONG_ONLY)
                        .setNegativeButtonText(negativeButtonText)
                        .build()
                    val cipher = provider.createBiometricAuthCipher()
                    val prompt = BiometricPrompt(
                        activity,
                        executor,
                        makeCryptoCallback(requireCrypto = cipher != null, fallbackOnNegativeButton = false)
                    )
                    if (cipher != null) {
                        prompt.authenticate(strongInfo, BiometricPrompt.CryptoObject(cipher))
                    } else {
                        prompt.authenticate(strongInfo)
                    }
                }
            }
        } catch (e: Exception) {
            onError(e.message ?: "无法启动生物识别验证")
        }
    }

    private fun Context.findFragmentActivity(): FragmentActivity? {
        val visited = HashSet<Context>()
        var cur: Context? = this
        while (cur is ContextWrapper && visited.add(cur)) {
            if (cur is FragmentActivity) return cur
            cur = cur.baseContext
        }
        return cur as? FragmentActivity
    }
}

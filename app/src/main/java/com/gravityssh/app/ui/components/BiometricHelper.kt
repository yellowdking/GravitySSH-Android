package com.gravityssh.app.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricHelper {
    private const val API30_AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    private const val API28_AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    private const val LEGACY_AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK

    fun canAuthenticate(context: Context): Boolean {
        val bm = BiometricManager.from(context)
        val authenticators = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> API30_AUTHENTICATORS
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> API28_AUTHENTICATORS
            else -> LEGACY_AUTHENTICATORS
        }
        return bm.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticate(
        context: Context,
        title: String = "解锁 GravitySSH 保险库",
        subtitle: String = "请验证指纹、人脸或设备锁屏凭据",
        negativeButtonText: String = "使用密码",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val activity = context.findFragmentActivity()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            onError("当前界面不支持生物识别")
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
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
        try {
            val prompt = BiometricPrompt(activity, executor, callback)
            val builder = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    // DEVICE_CREDENTIAL requires omitting setNegativeButtonText on API 30+
                    builder.setAllowedAuthenticators(API30_AUTHENTICATORS)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                    @Suppress("DEPRECATION")
                    builder.setDeviceCredentialAllowed(true)
                }
                else -> {
                    builder.setAllowedAuthenticators(LEGACY_AUTHENTICATORS)
                        .setNegativeButtonText(negativeButtonText)
                }
            }
            prompt.authenticate(builder.build())
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

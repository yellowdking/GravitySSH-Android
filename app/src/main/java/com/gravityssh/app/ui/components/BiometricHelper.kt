package com.gravityssh.app.ui.components

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricHelper {
    fun canAuthenticate(context: Context): Boolean {
        val bm = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        return bm.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticate(
        context: Context,
        title: String = "解锁 GravitySSH 保险库",
        subtitle: String = "请验证指纹或人脸",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val activity = context.findFragmentActivity()
        if (activity == null) {
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
        val prompt = BiometricPrompt(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("使用密码")
            .build()
        prompt.authenticate(info)
    }

    private fun Context.findFragmentActivity(): FragmentActivity? {
        var cur: Context? = this
        while (cur is ContextWrapper) {
            if (cur is FragmentActivity) return cur
            cur = cur.baseContext
        }
        return cur as? FragmentActivity
    }
}

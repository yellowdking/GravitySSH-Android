package com.gravityssh.app.ui.lock

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.gravityssh.app.model.VaultLockState
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.components.BiometricHelper
import com.gravityssh.app.ui.theme.*

@Composable
fun VaultLockScreen(
    lockState: VaultLockState,
    onUnlockPassword: (String) -> Result<Unit>,
    onUnlockBiometric: () -> Result<Unit>
) {
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val canBiometric = remember(context) { BiometricHelper.canAuthenticate(context) }

    fun submitPassword() {
        if (password.isBlank()) errorMsg = "请输入主密码"
        else onUnlockPassword(password).onFailure { errorMsg = it.message ?: "主密码错误" }
    }

    fun triggerBiometric() {
        BiometricHelper.authenticate(
            context = context,
            negativeButtonText = if (lockState.hasPassword) "使用密码" else "取消",
            onSuccess = { onUnlockBiometric().onFailure { errorMsg = it.message ?: "生物识别解锁失败" } },
            onError = { errorMsg = it }
        )
    }

    LaunchedEffect(lockState.biometricEnabled, lockState.hasPassword) {
        if (lockState.biometricEnabled && !lockState.hasPassword && canBiometric) triggerBiometric()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(AgBg).imePadding().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        AgCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = AgElevated,
                    border = BorderStroke(1.dp, AgBorder),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Lock, null, tint = AgAccent, modifier = Modifier.size(28.dp))
                    }
                }
                Text("GravitySSH 保险库已锁定", style = MaterialTheme.typography.titleLarge, color = AgText)
                Text(
                    if (lockState.hasPassword) "请输入主密码以解密本地主机与 SSH 密钥" else "请验证指纹或人脸以解锁加密保险库",
                    style = MaterialTheme.typography.bodySmall,
                    color = AgMuted
                )

                if (lockState.hasPassword) {
                    AgTextField(
                        value = password,
                        onValueChange = { password = it; errorMsg = null },
                        label = "主密码",
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitPassword() }),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, tint = AgMuted)
                            }
                        }
                    )
                    Button(
                        onClick = ::submitPassword,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AgAccent)
                    ) { Text("解锁保险库", color = Color.White) }
                }

                if (lockState.biometricEnabled && canBiometric) {
                    OutlinedButton(
                        onClick = { triggerBiometric() },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, AgCyan.copy(alpha = 0.6f))
                    ) {
                        Icon(Icons.Rounded.Fingerprint, null, tint = AgCyan)
                        Spacer(Modifier.width(8.dp))
                        Text("使用指纹 / 生物识别解锁", color = AgCyan)
                    }
                }

                val displayedError = errorMsg ?: lockState.startupError
                if (!displayedError.isNullOrBlank()) {
                    Text(displayedError, style = MaterialTheme.typography.bodySmall, color = AgDanger)
                }
            }
        }
    }
}

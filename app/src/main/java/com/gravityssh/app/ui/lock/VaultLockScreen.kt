package com.gravityssh.app.ui.lock

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gravityssh.app.model.VaultLockState
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.components.AgConfirmDialog
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.components.AgPasswordField
import com.gravityssh.app.ui.components.AgStatusBanner
import com.gravityssh.app.ui.components.BiometricHelper
import com.gravityssh.app.ui.theme.*

@Composable
fun VaultLockScreen(
    lockState: VaultLockState,
    onUnlockPassword: (String, (Result<Unit>) -> Unit) -> Unit,
    onUnlockBiometric: ((Result<Unit>) -> Unit) -> Unit,
    onResetCorruptedVault: ((Result<Unit>) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var canBiometric by remember(context) { mutableStateOf(BiometricHelper.canAuthenticate(context)) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canBiometric = BiometricHelper.canAuthenticate(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val biometricUnavailable = lockState.biometricEnabled && !lockState.hasPassword && !canBiometric
    val hasStartupOrKeyError = !lockState.startupError.isNullOrBlank()
    val showPrimaryReset = hasStartupOrKeyError || biometricUnavailable
    val biometricUnavailableHint = if (biometricUnavailable) {
        "设备当前未开启生物识别或锁屏凭据，无法完成解锁验证。请在系统设置中重新启用指纹/锁屏密码，或备份并重置保险库。"
    } else null

    fun submitPassword() {
        if (busy) return
        if (password.isEmpty()) {
            errorMsg = "请输入主密码"
            return
        }
        busy = true
        errorMsg = null
        onUnlockPassword(password) { res ->
            busy = false
            res.onFailure { errorMsg = it.message ?: "主密码错误" }
        }
    }

    fun triggerBiometric() {
        if (busy) return
        BiometricHelper.authenticate(
            context = context,
            negativeButtonText = if (lockState.hasPassword) "使用密码" else "取消",
            onSuccess = {
                busy = true
                errorMsg = null
                onUnlockBiometric { res ->
                    busy = false
                    res.onFailure { errorMsg = it.message ?: "生物识别解锁失败" }
                }
            },
            onError = { errorMsg = it }
        )
    }

    LaunchedEffect(lockState.biometricEnabled, lockState.hasPassword, canBiometric, hasStartupOrKeyError) {
        if (lockState.biometricEnabled && !lockState.hasPassword && canBiometric && !hasStartupOrKeyError) {
            triggerBiometric()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AgBg)
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
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
                    when {
                        lockState.hasPassword -> "请输入主密码以解密本地主机与 SSH 密钥"
                        biometricUnavailable -> "生物识别与设备锁屏凭据不可用"
                        lockState.biometricEnabled -> "请验证指纹、人脸或设备锁屏凭据以解锁加密保险库"
                        else -> "本地保险库未能自动解锁，请查看下方提示"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AgMuted
                )

                if (lockState.hasPassword) {
                    AgPasswordField(
                        value = password,
                        onValueChange = { password = it; errorMsg = null },
                        label = "主密码",
                        enabled = !busy,
                        imeAction = ImeAction.Done,
                        onDone = ::submitPassword
                    )
                    Button(
                        onClick = ::submitPassword,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AgAccent)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (busy) "正在解密保险库..." else "解锁保险库", color = Color.White)
                    }
                }

                if (lockState.biometricEnabled && !lockState.hasPassword && canBiometric) {
                    OutlinedButton(
                        onClick = { triggerBiometric() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, AgCyan.copy(alpha = 0.6f))
                    ) {
                        Icon(AgIcons.Fingerprint, null, tint = AgCyan)
                        Spacer(Modifier.width(8.dp))
                        Text("使用指纹 / 锁屏凭据解锁", color = AgCyan)
                    }
                }

                val displayedError = lockState.startupError ?: errorMsg ?: biometricUnavailableHint
                if (!displayedError.isNullOrBlank()) {
                    AgStatusBanner(message = displayedError, color = AgDanger)
                }

                if (showPrimaryReset) {
                    OutlinedButton(
                        onClick = { confirmReset = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, AgDanger)
                    ) {
                        Text("重置保险库（备份后重建）", color = AgDanger)
                    }
                } else if (lockState.hasPassword || lockState.biometricEnabled) {
                    TextButton(
                        onClick = { confirmReset = true },
                        enabled = !busy
                    ) {
                        Text(
                            if (lockState.hasPassword) "忘记主密码或密钥失效？重置保险库" else "无法解锁？重置保险库",
                            style = MaterialTheme.typography.bodySmall,
                            color = AgMuted
                        )
                    }
                }
            }
        }
        }
    }

    if (confirmReset) {
        AgConfirmDialog(
            title = "重置保险库",
            message = "系统将先备份当前加密保险库文件，再创建全新的空保险库。所有本地主机与密钥需重新添加或从其他设备同步，此操作不可撤销，确定继续？",
            confirmLabel = "备份并重置",
            dangerous = true,
            onConfirm = {
                confirmReset = false
                busy = true
                errorMsg = null
                onResetCorruptedVault { res ->
                    busy = false
                    res.onFailure { errorMsg = it.message ?: "重置保险库失败" }
                }
            },
            onDismiss = { confirmReset = false }
        )
    }
}

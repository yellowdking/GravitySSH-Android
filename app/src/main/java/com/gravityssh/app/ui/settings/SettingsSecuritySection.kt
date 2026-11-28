package com.gravityssh.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.model.VaultLockState
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.components.AgConfirmDialog
import com.gravityssh.app.ui.components.AgFilterChip
import com.gravityssh.app.ui.components.AgStatusBanner
import com.gravityssh.app.ui.components.AgSwitchRow
import com.gravityssh.app.ui.components.BiometricHelper
import com.gravityssh.app.ui.setBiometricLockEnabled
import com.gravityssh.app.ui.setMasterPassword
import com.gravityssh.app.ui.theme.*
import com.gravityssh.app.ui.updateSettings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private data class SecurityVaultSlice(
    val lockState: VaultLockState,
    val biometricLockEnabled: Boolean,
    val autoLockMinutes: Int
)

private val AUTO_LOCK_OPTIONS = listOf(
    0 to "关闭",
    1 to "1分钟",
    5 to "5分钟",
    15 to "15分钟",
    30 to "30分钟"
)

@Composable
fun SettingsSecuritySection(viewModel: AppViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val securitySlice by remember(viewModel) {
        viewModel.vaultState
            .map {
                SecurityVaultSlice(
                    lockState = it.lockState,
                    biometricLockEnabled = it.data.settings.biometricLockEnabled,
                    autoLockMinutes = it.data.settings.autoLockMinutes
                )
            }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = viewModel.vaultState.value.let {
            SecurityVaultSlice(
                lockState = it.lockState,
                biometricLockEnabled = it.data.settings.biometricLockEnabled,
                autoLockMinutes = it.data.settings.autoLockMinutes
            )
        }
    )
    val knownHosts by viewModel.knownHosts.collectAsStateWithLifecycle()
    val knownHostsError by viewModel.knownHostsError.collectAsStateWithLifecycle()
    val lockState = securitySlice.lockState
    val biometricLockEnabled = securitySlice.biometricLockEnabled
    val autoLockMinutes = securitySlice.autoLockMinutes
    val lockActive = lockState.hasPassword || lockState.biometricEnabled
    var canBiometric by remember(context) { mutableStateOf(BiometricHelper.canAuthenticate(context)) }
    var biometricBusy by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canBiometric = BiometricHelper.canAuthenticate(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showPwDialog by remember { mutableStateOf(false) }
    var confirmClearKnown by remember { mutableStateOf(false) }

    AgCard {
        Text("保险库安全与锁屏", style = MaterialTheme.typography.titleMedium, color = AgText)
        Spacer(Modifier.height(8.dp))
        AgSwitchRow(
            label = "生物识别指纹解锁",
            checked = !lockState.hasPassword && biometricLockEnabled,
            enabled = !biometricBusy && !lockState.hasPassword && (canBiometric || biometricLockEnabled)
        ) { enabled ->
            if (biometricBusy) return@AgSwitchRow
            if (enabled) {
                if (!canBiometric) {
                    viewModel.showToast("设备未开启强生物识别或锁屏密码")
                    return@AgSwitchRow
                }
                BiometricHelper.authenticate(
                    context = context,
                    title = "验证身份以开启解锁保护",
                    subtitle = "请验证强生物识别或设备锁屏凭据",
                    negativeButtonText = "取消",
                    cryptoProvider = viewModel.container.vaultRepository.masterKeyProvider,
                    allowDeviceCredentialFallback = true,
                    onSuccess = {
                        biometricBusy = true
                        viewModel.setBiometricLockEnabled(true) { res ->
                            biometricBusy = false
                            res.onSuccess { viewModel.showToast("已开启生物识别锁") }
                                .onFailure { viewModel.showToast(it.message ?: "开启生物识别锁失败") }
                        }
                    },
                    onError = { err -> viewModel.showToast("身份验证失败: $err") }
                )
            } else {
                biometricBusy = true
                viewModel.setBiometricLockEnabled(false) { res ->
                    biometricBusy = false
                    res.onSuccess { viewModel.showToast("已关闭生物识别锁") }
                        .onFailure { viewModel.showToast(it.message ?: "关闭生物识别锁失败") }
                }
            }
        }
        if (lockState.hasPassword) {
            Text(
                "已设置主密码时需使用主密码解锁",
                style = MaterialTheme.typography.bodySmall,
                color = AgMuted
            )
        }
        Spacer(Modifier.height(10.dp))
        val autoLockLabel = AUTO_LOCK_OPTIONS.firstOrNull { it.first == autoLockMinutes }?.second
            ?: "${autoLockMinutes}分钟"
        Text(
            "后台自动锁定：$autoLockLabel",
            style = MaterialTheme.typography.labelMedium,
            color = if (lockActive) AgText else AgMuted
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AUTO_LOCK_OPTIONS.forEach { (minutes, label) ->
                AgFilterChip(
                    selected = autoLockMinutes == minutes,
                    onClick = {
                        if (lockActive && autoLockMinutes != minutes) {
                            viewModel.updateSettings { it.copy(autoLockMinutes = minutes) }
                        }
                    },
                    label = label,
                    enabled = lockActive,
                    selectedColor = AgCyan
                )
            }
        }
        if (!lockActive) {
            Text(
                "开启主密码或生物识别锁后可配置后台自动锁定",
                style = MaterialTheme.typography.bodySmall,
                color = AgMuted
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { showPwDialog = true },
                border = BorderStroke(1.dp, AgCyan),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) { Text(if (lockState.hasPassword) "修改/移除主密码" else "设置主密码", color = AgCyan) }
            if (lockActive) {
                OutlinedButton(
                    onClick = { viewModel.lockVault() },
                    border = BorderStroke(1.dp, AgAccent),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(0.7f)
                ) { Text("立即锁定", color = AgAccent) }
            }
        }
    }

    AgCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("SSH 主机指纹缓存 (known_hosts)", style = MaterialTheme.typography.titleMedium, color = AgText)
                Text("已信任 ${knownHosts.size} 台主机指纹", style = MaterialTheme.typography.bodySmall, color = AgMuted)
            }
            if (knownHosts.isNotEmpty()) {
                TextButton(onClick = { confirmClearKnown = true }) { Text("全部清除", color = AgDanger) }
            }
        }
        knownHostsError?.let { err ->
            Spacer(Modifier.height(8.dp))
            AgStatusBanner(message = err, color = AgWarning)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { viewModel.dismissKnownHostsError() }) {
                    Text("知道了", color = AgMuted, fontSize = 12.sp)
                }
            }
        }
        knownHosts.forEach { entry ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.hostPort, fontFamily = MonoFontFamily, fontSize = 12.sp, color = AgText)
                    Text("${entry.algorithm} · ${entry.fingerprint}", fontFamily = MonoFontFamily, fontSize = 10.sp, color = AgMuted, maxLines = 1)
                }
                IconButton(onClick = { viewModel.clearKnownHostByKey(entry.hostPort) }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.Delete, "清除", tint = AgDanger, modifier = Modifier.size(16.dp))
                }
            }
        }
    }

    if (showPwDialog) {
        MasterPasswordDialog(
            hasExisting = lockState.hasPassword,
            onSave = { cur, next, done ->
                viewModel.setMasterPassword(cur, next) { res ->
                    res.onSuccess { showPwDialog = false }
                    done(res)
                }
            },
            onDismiss = { showPwDialog = false }
        )
    }

    if (confirmClearKnown) {
        AgConfirmDialog(
            title = "清除全部 SSH 缓存",
            message = "清除后下次连接各主机将重新记录其公钥指纹。确定继续？",
            confirmLabel = "全部清除",
            dangerous = true,
            onConfirm = { viewModel.clearAllKnownHosts(); confirmClearKnown = false },
            onDismiss = { confirmClearKnown = false }
        )
    }
}

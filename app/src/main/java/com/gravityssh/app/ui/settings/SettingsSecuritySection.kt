package com.gravityssh.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.components.AgConfirmDialog
import com.gravityssh.app.ui.components.AgSwitchRow
import com.gravityssh.app.ui.components.BiometricHelper
import com.gravityssh.app.ui.setMasterPassword
import com.gravityssh.app.ui.theme.*
import com.gravityssh.app.ui.updateSettings

@Composable
fun SettingsSecuritySection(viewModel: AppViewModel) {
    val context = LocalContext.current
    val vaultState by viewModel.vaultState.collectAsStateWithLifecycle()
    val knownHosts by viewModel.knownHosts.collectAsStateWithLifecycle()
    val lockState = vaultState.lockState
    val settings = vaultState.data.settings
    val canBiometric = remember(context) { BiometricHelper.canAuthenticate(context) }

    var showPwDialog by remember { mutableStateOf(false) }
    var confirmClearKnown by remember { mutableStateOf(false) }

    AgCard {
        Text("保险库安全与锁屏", style = MaterialTheme.typography.titleMedium, color = AgText)
        Spacer(Modifier.height(6.dp))
        Text(
            if (lockState.hasPassword) "已启用主密码双层加密保护" else "当前使用 Android Keystore 硬件密钥自动保护",
            style = MaterialTheme.typography.bodySmall,
            color = AgMuted
        )
        Spacer(Modifier.height(8.dp))
        AgSwitchRow(
            label = "生物识别指纹解锁",
            checked = !lockState.hasPassword && settings.biometricLockEnabled,
            enabled = canBiometric && !lockState.hasPassword
        ) { enabled ->
            viewModel.updateSettings { it.copy(biometricLockEnabled = enabled) }
            viewModel.showToast(if (enabled) "已开启生物识别锁" else "已关闭生物识别锁")
        }
        if (lockState.hasPassword) {
            Text(
                "已设置主密码时需使用主密码解锁",
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
            if (lockState.hasPassword || lockState.biometricEnabled) {
                OutlinedButton(
                    onClick = { viewModel.container.vaultRepository.lockNow() },
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

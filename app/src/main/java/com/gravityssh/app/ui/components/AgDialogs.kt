package com.gravityssh.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gravityssh.app.model.resolveSshPortCommand
import com.gravityssh.app.terminal.PasteGuard
import com.gravityssh.app.terminal.PasteWarningKind
import com.gravityssh.app.ui.theme.AgAccent
import com.gravityssh.app.ui.theme.AgBg
import com.gravityssh.app.ui.theme.AgBorder
import com.gravityssh.app.ui.theme.AgDanger
import com.gravityssh.app.ui.theme.AgElevated
import com.gravityssh.app.ui.theme.AgMuted
import com.gravityssh.app.ui.theme.AgPanel
import com.gravityssh.app.ui.theme.AgText
import com.gravityssh.app.ui.theme.AgWarning
import com.gravityssh.app.ui.theme.MonoFontFamily

@Composable
fun AgEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = AgElevated,
            border = BorderStroke(1.dp, AgBorder),
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = AgAccent, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = AgText)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AgMuted)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = AgAccent),
                shape = RoundedCornerShape(10.dp)
            ) { Text(actionLabel, color = Color.White) }
        }
    }
}

@Composable
fun AgConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "确认",
    dangerous: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AgPanel,
        title = { Text(title, color = AgText) },
        text = { Text(message, color = AgMuted, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = if (dangerous) AgDanger else AgAccent)
            ) { Text(confirmLabel, color = Color.White) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = AgMuted) }
        }
    )
}

@Composable
fun AgCommandConfirmDialog(
    title: String,
    command: String,
    safetyNote: String = "",
    requiresSshPort: Boolean = false,
    onConfirm: (resolvedCommand: String) -> Unit,
    onDismiss: () -> Unit
) {
    var portText by remember(command) { mutableStateOf("") }
    var portError by remember(command) { mutableStateOf<String?>(null) }
    val parsedPort = portText.trim().toIntOrNull()
    val previewCommand = remember(command, requiresSshPort, parsedPort) {
        if (requiresSshPort && parsedPort != null) resolveSshPortCommand(command, parsedPort) else command
    }
    val highRiskWarnings = remember(previewCommand) {
        PasteGuard.inspect(previewCommand, bracketedPasteActive = true).warnings.filter {
            it.kind == PasteWarningKind.DANGER || it.kind == PasteWarningKind.PIPE_SHELL
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AgPanel,
        title = { Text(title, color = AgText) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (safetyNote.isNotBlank()) {
                    AgStatusBanner(message = safetyNote, color = AgWarning)
                }
                highRiskWarnings.forEach { warn ->
                    AgStatusBanner(message = "${warn.label}：${warn.detail}", color = AgDanger)
                }
                if (requiresSshPort) {
                    AgTextField(
                        value = portText,
                        onValueChange = { raw ->
                            portText = raw.filter(Char::isDigit).take(5)
                            portError = null
                        },
                        label = "新的 SSH 端口 (1024-65535)",
                        placeholder = "例如 2222",
                        mono = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    portError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AgDanger) }
                }
                Text("待执行命令预览：", style = MaterialTheme.typography.labelSmall, color = AgMuted)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AgBg, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        text = previewCommand.take(1500),
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        color = AgText
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (requiresSshPort) {
                        val port = portText.trim().toIntOrNull()
                        if (port == null || port !in 1024..65535) {
                            portError = "请输入 1024–65535 之间的有效端口号"
                            return@Button
                        }
                        onConfirm(resolveSshPortCommand(command, port))
                    } else {
                        onConfirm(command)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (highRiskWarnings.isNotEmpty() || requiresSshPort) AgDanger else AgAccent
                )
            ) {
                Text("确认执行", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = AgMuted) }
        }
    )
}

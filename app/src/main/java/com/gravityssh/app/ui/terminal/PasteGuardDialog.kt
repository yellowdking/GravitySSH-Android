package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gravityssh.app.terminal.PasteGuard
import com.gravityssh.app.terminal.PasteInspection
import com.gravityssh.app.terminal.PasteWarningKind
import com.gravityssh.app.ui.components.AgStatusBanner
import com.gravityssh.app.ui.theme.AgBg
import com.gravityssh.app.ui.theme.AgCyan
import com.gravityssh.app.ui.theme.AgDanger
import com.gravityssh.app.ui.theme.AgMuted
import com.gravityssh.app.ui.theme.AgPanel
import com.gravityssh.app.ui.theme.AgText
import com.gravityssh.app.ui.theme.AgWarning
import com.gravityssh.app.ui.theme.MonoFontFamily

@Composable
fun PasteGuardDialog(
    rawText: String,
    inspection: PasteInspection,
    bracketedPaste: Boolean = false,
    onConfirmPaste: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var currentText by remember(rawText) { mutableStateOf(rawText) }
    var currentInspection by remember(rawText, inspection) { mutableStateOf(inspection) }
    val purifiedText = remember(currentText) { PasteGuard.stripHiddenChars(currentText) }
    val purifiedInspection = remember(purifiedText, bracketedPaste, currentInspection.hasHiddenChars) {
        if (currentInspection.hasHiddenChars) PasteGuard.inspect(purifiedText, bracketedPaste) else null
    }
    val purifiedStillRisky = purifiedInspection?.needsConfirm == true
    val displayedWarnings = remember(currentInspection, purifiedInspection) {
        if (purifiedInspection == null) {
            currentInspection.warnings
        } else {
            val seen = HashSet<Pair<PasteWarningKind, String>>()
            buildList {
                currentInspection.warnings.forEach { if (seen.add(it.kind to it.detail)) add(it) }
                purifiedInspection.warnings.forEach { if (seen.add(it.kind to it.detail)) add(it) }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AgPanel,
        icon = { Icon(Icons.Rounded.Warning, contentDescription = null, tint = AgWarning) },
        title = { Text("安全粘贴拦截提醒", color = AgText) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                displayedWarnings.forEach { warn ->
                    val tint = when (warn.kind) {
                        PasteWarningKind.DANGER, PasteWarningKind.HIDDEN_CHARS -> AgDanger
                        else -> AgWarning
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = tint.copy(alpha = 0.13f),
                        border = BorderStroke(1.dp, tint.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(9.dp)) {
                            Text(warn.label, style = MaterialTheme.typography.labelMedium, color = tint)
                            Text(warn.detail, style = MaterialTheme.typography.bodySmall, color = AgText)
                        }
                    }
                }
                if (currentInspection.hasHiddenChars && purifiedStillRisky) {
                    AgStatusBanner(
                        message = "移除隐藏字符后仍包含风险命令，请先点击「仅净化预览」核对真实内容后再确认粘贴。",
                        color = AgDanger
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text("待粘贴内容预览：", style = MaterialTheme.typography.labelSmall, color = AgMuted)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AgBg, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        text = currentText.take(1200),
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        color = AgText
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (currentInspection.hasHiddenChars && purifiedInspection != null) {
                    OutlinedButton(
                        onClick = {
                            if (purifiedStillRisky) {
                                // Never paste unchecked dangerous purified text directly: update preview and require explicit confirm
                                currentText = purifiedText
                                currentInspection = purifiedInspection
                            } else {
                                onConfirmPaste(purifiedText)
                            }
                        },
                        border = BorderStroke(1.dp, AgCyan)
                    ) {
                        Text(if (purifiedStillRisky) "仅净化预览" else "净化后粘贴", color = AgCyan)
                    }
                }
                Button(
                    onClick = { onConfirmPaste(currentText) },
                    colors = ButtonDefaults.buttonColors(containerColor = AgDanger)
                ) {
                    Text("确认粘贴", color = Color.White)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = AgMuted)
            }
        }
    )
}

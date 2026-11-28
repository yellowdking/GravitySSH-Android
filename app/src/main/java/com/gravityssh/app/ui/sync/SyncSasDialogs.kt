package com.gravityssh.app.ui.sync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gravityssh.app.model.SyncFlowStage
import com.gravityssh.app.model.SyncUiState
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.cancelSyncFlow
import com.gravityssh.app.ui.components.AgBadge
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.components.AgStatusBanner
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.confirmSyncResponderSas
import com.gravityssh.app.ui.submitSyncSasInput
import com.gravityssh.app.ui.theme.*

@Composable
fun SyncSasDialogs(
    state: SyncUiState,
    viewModel: AppViewModel
) {
    when (state.flowStage) {
        SyncFlowStage.AWAITING_SAS_INPUT -> {
            InitiatorSasInputDialog(
                attemptsRemaining = state.sasAttemptsRemaining,
                errorMessage = state.errorMessage,
                onSubmit = { digits -> viewModel.submitSyncSasInput(digits) },
                onCancel = { viewModel.cancelSyncFlow() }
            )
        }
        SyncFlowStage.AWAITING_SAS_CONFIRM -> {
            ResponderSasConfirmDialog(
                sasCode = state.sasCode,
                onApprove = { viewModel.confirmSyncResponderSas(true) },
                onReject = { viewModel.confirmSyncResponderSas(false) }
            )
        }
        else -> Unit
    }
}

@Composable
private fun InitiatorSasInputDialog(
    attemptsRemaining: Int,
    errorMessage: String?,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var digits by remember(attemptsRemaining) { mutableStateOf("") }
    var submitted by remember(attemptsRemaining, errorMessage) { mutableStateOf(false) }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(18.dp))
                .background(AgPanel)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(AgIcons.Shield, null, tint = AgCyan, modifier = Modifier.size(20.dp))
                    Text("输入 6 位安全码", style = MaterialTheme.typography.titleLarge, color = AgText)
                }
                AgBadge(
                    text = "剩余 $attemptsRemaining 次",
                    color = if (attemptsRemaining <= 1) AgDanger else AgWarning
                )
            }

            AgStatusBanner(
                message = "防社工攻击提示：请当面或语音核对接收端屏幕上显示的 6 位安全码，严禁通过发送 8 位房间码的同一聊天窗口索要安全码！",
                color = AgWarning,
                icon = Icons.Rounded.Warning
            )

            errorMessage?.takeIf { it.isNotBlank() }?.let { err ->
                AgStatusBanner(message = err, color = AgDanger)
            }

            AgTextField(
                value = digits,
                onValueChange = { raw ->
                    digits = raw.filter(Char::isDigit).take(6)
                },
                label = "接收端显示的 6 位数字安全码",
                placeholder = "例如 042819",
                mono = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (digits.length == 6 && !submitted) {
                            submitted = true
                            onSubmit(digits)
                        }
                    }
                )
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onCancel,
                    border = BorderStroke(1.dp, AgDanger),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Text("取消并销毁", color = AgDanger)
                }
                Button(
                    onClick = {
                        if (!submitted) {
                            submitted = true
                            onSubmit(digits)
                        }
                    },
                    enabled = digits.length == 6 && !submitted,
                    colors = ButtonDefaults.buttonColors(containerColor = AgAccent),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1.2f).height(44.dp)
                ) {
                    Text(if (submitted) "核验中..." else "验证并发送", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ResponderSasConfirmDialog(
    sasCode: String,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val formattedSas = remember(sasCode) {
        val clean = sasCode.filter(Char::isDigit)
        if (clean.length == 6) "${clean.substring(0, 3)}-${clean.substring(3, 6)}" else sasCode.ifBlank { "--- ---" }
    }
    var responded by remember(sasCode) { mutableStateOf(false) }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(18.dp))
                .background(AgPanel)
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(AgIcons.VerifiedUser, null, tint = AgWarning, modifier = Modifier.size(22.dp))
                Text("核对 6 位安全码", style = MaterialTheme.typography.titleLarge, color = AgText)
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AgInputBg,
                border = BorderStroke(1.5.dp, AgCyan),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(vertical = 16.dp, horizontal = 12.dp)
                ) {
                    Text(
                        text = formattedSas,
                        fontFamily = MonoFontFamily,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = AgCyan,
                        letterSpacing = 4.sp
                    )
                }
            }

            Text(
                text = "请与发送端当面或语音核对 6 位安全码是否完全一致",
                style = MaterialTheme.typography.bodyMedium,
                color = AgText,
                fontWeight = FontWeight.Medium
            )

            AgStatusBanner(
                message = "请将上方 6 位数字口头告知发送端输入，并双向确认完全一致后再点击允许。若不一致请立即点击拒绝以阻断中间人攻击！",
                color = AgWarning,
                icon = Icons.Rounded.Warning
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        if (!responded) {
                            responded = true
                            onReject()
                        }
                    },
                    enabled = !responded,
                    border = BorderStroke(1.dp, AgDanger),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(46.dp)
                ) {
                    Text("不一致，拒绝", color = AgDanger, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = {
                        if (!responded) {
                            responded = true
                            onApprove()
                        }
                    },
                    enabled = !responded,
                    colors = ButtonDefaults.buttonColors(containerColor = AgSuccess),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1.15f).height(46.dp)
                ) {
                    Text("一致，允许", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

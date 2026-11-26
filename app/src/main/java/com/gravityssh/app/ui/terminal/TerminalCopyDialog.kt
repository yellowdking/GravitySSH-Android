package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.gravityssh.app.ui.theme.*

@Composable
internal fun TerminalCopyDialog(
    pressedLine: String,
    screenText: String,
    onCopyText: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        containerColor = AgPanel,
        shape = RoundedCornerShape(16.dp),
        title = { Text("长按复制", color = AgText, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onCopyText(pressedLine, "已复制当前行") },
                        enabled = pressedLine.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AgAccent,
                            contentColor = Color.White,
                            disabledContainerColor = AgElevated,
                            disabledContentColor = AgSubtle
                        )
                    ) { Text("复制该行", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    OutlinedButton(
                        onClick = { onCopyText(screenText, "已复制终端整屏") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, AgBorderLight),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = AgElevated, contentColor = AgText)
                    ) { Text("复制整屏", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Spacer(Modifier.height(10.dp))
                Text("或长按下方文本自由划选复制：", color = AgMuted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 300.dp),
                    color = AgInputBg,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, AgBorder)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                text = screenText.ifBlank { "(当前屏幕为空)" },
                                fontFamily = MonoFontFamily,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = AgText
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = AgMuted) } }
    )
}

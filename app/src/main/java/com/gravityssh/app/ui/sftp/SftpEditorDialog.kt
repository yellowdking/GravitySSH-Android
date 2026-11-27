package com.gravityssh.app.ui.sftp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gravityssh.app.model.RemoteFileContent
import com.gravityssh.app.ui.components.AgConfirmDialog
import com.gravityssh.app.ui.theme.AgAccent
import com.gravityssh.app.ui.theme.AgBg
import com.gravityssh.app.ui.theme.AgBorder
import com.gravityssh.app.ui.theme.AgInputBg
import com.gravityssh.app.ui.theme.AgMuted
import com.gravityssh.app.ui.theme.AgSurface
import com.gravityssh.app.ui.theme.AgText
import com.gravityssh.app.ui.theme.MonoFontFamily
import kotlinx.coroutines.launch

@Composable
fun SftpEditorDialog(
    file: RemoteFileContent,
    onSave: suspend (String) -> Unit,
    onDismiss: () -> Unit
) {
    var content by remember(file.path) { mutableStateOf(file.content) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val hasChanges = content != file.content
    val scope = rememberCoroutineScope()

    fun requestClose() {
        if (saving) return
        if (hasChanges) confirmDiscard = true else onDismiss()
    }

    Dialog(
        onDismissRequest = ::requestClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BackHandler(enabled = !saving && hasChanges) {
            confirmDiscard = true
        }
        Surface(modifier = Modifier.fillMaxSize().imePadding(), color = AgBg) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AgSurface)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.path.substringAfterLast('/') + if (hasChanges) " *" else "",
                            style = MaterialTheme.typography.titleMedium,
                            color = AgText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = file.path,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = MonoFontFamily,
                            color = AgMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = ::requestClose, enabled = !saving) {
                            Text("关闭", color = AgMuted)
                        }
                        Button(
                            onClick = {
                                saving = true
                                scope.launch {
                                    onSave(content)
                                    saving = false
                                }
                            },
                            enabled = !saving,
                            colors = ButtonDefaults.buttonColors(containerColor = AgAccent),
                            shape = RoundedCornerShape(9.dp)
                        ) {
                            if (saving) {
                                CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(if (saving) "保存中" else "保存", color = Color.White)
                        }
                    }
                }

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    textStyle = TextStyle(fontFamily = MonoFontFamily, fontSize = 12.sp, lineHeight = 18.sp, color = AgText),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AgBorder,
                        unfocusedBorderColor = AgBorder,
                        focusedContainerColor = AgInputBg,
                        unfocusedContainerColor = AgInputBg,
                        cursorColor = AgAccent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(10.dp)
                )
            }
        }
    }

    if (confirmDiscard) {
        AgConfirmDialog(
            title = "放弃未保存修改",
            message = "当前文件有未保存的更改，确定放弃并关闭编辑器？",
            confirmLabel = "放弃修改",
            dangerous = true,
            onConfirm = { confirmDiscard = false; onDismiss() },
            onDismiss = { confirmDiscard = false }
        )
    }
}

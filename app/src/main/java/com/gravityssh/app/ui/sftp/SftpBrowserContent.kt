package com.gravityssh.app.ui.sftp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gravityssh.app.model.SFTPFileEntry
import com.gravityssh.app.model.TransferProgress
import com.gravityssh.app.ui.theme.*

@Composable
internal fun SftpBrowserContent(
    serverName: String,
    currentPath: String,
    showHidden: Boolean,
    loading: Boolean,
    errorMsg: String?,
    transfer: TransferProgress?,
    entries: List<SFTPFileEntry>,
    onUp: () -> Unit,
    onHome: () -> Unit,
    onRefresh: () -> Unit,
    onToggleHidden: () -> Unit,
    onMkdir: () -> Unit,
    onUpload: () -> Unit,
    onTapEntry: (SFTPFileEntry) -> Unit,
    onMenuEntry: (SFTPFileEntry) -> Unit,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(AgSurface).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                IconButton(onClick = onUp) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "上级", tint = AgText) }
                Column(modifier = Modifier.weight(1f)) {
                    Text("SFTP · $serverName", style = MaterialTheme.typography.titleSmall, color = AgText, maxLines = 1)
                    Text(currentPath, fontFamily = MonoFontFamily, fontSize = 11.sp, color = AgCyan, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = onDismiss) { Text("关闭", color = AgMuted) }
        }

        Row(
            modifier = Modifier.fillMaxWidth().background(AgPanel).padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(onClick = onHome) { Icon(Icons.Rounded.Home, "主目录", tint = AgMuted) }
            IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "刷新", tint = AgMuted) }
            IconButton(onClick = onToggleHidden) {
                Icon(if (showHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, "隐藏文件", tint = if (showHidden) AgCyan else AgMuted)
            }
            IconButton(onClick = onMkdir) { Icon(Icons.Rounded.CreateNewFolder, "新建目录", tint = AgAccent) }
            IconButton(onClick = onUpload) { Icon(Icons.Rounded.UploadFile, "上传", tint = AgCyan) }
        }

        transfer?.let { t ->
            Column(modifier = Modifier.fillMaxWidth().background(AgSurface).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("${if (t.direction == "upload") "上传" else "下载"} ${t.fileName} (${t.percent}%)", fontSize = 11.sp, color = AgCyan)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { t.percent / 100f }, modifier = Modifier.fillMaxWidth(), color = AgAccent)
            }
        }
        HorizontalDivider(color = AgBorder)

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AgAccent) }
            errorMsg != null -> Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                Text(errorMsg, color = AgDanger, style = MaterialTheme.typography.bodyMedium)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(entries, key = { it.path }) { entry ->
                    SftpRow(entry, { onTapEntry(entry) }, { onMenuEntry(entry) })
                }
            }
        }
    }
}

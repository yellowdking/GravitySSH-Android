package com.gravityssh.app.ui.sftp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gravityssh.app.model.SFTPFileEntry
import com.gravityssh.app.model.TransferProgress
import com.gravityssh.app.ui.components.AgEmptyState
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.theme.*

@Composable
internal fun SftpBrowserContent(
    serverName: String,
    currentPath: String,
    canNavigateUp: Boolean,
    showHidden: Boolean,
    loading: Boolean,
    errorMsg: String?,
    transfer: TransferProgress?,
    entries: List<SFTPFileEntry>,
    listState: LazyListState,
    onUp: () -> Unit,
    onHome: () -> Unit,
    onRefresh: () -> Unit,
    onToggleHidden: () -> Unit,
    onMkdir: () -> Unit,
    onUpload: () -> Unit,
    onCancelTransfer: () -> Unit,
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
                IconButton(onClick = onUp, enabled = canNavigateUp) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        "上级",
                        tint = if (canNavigateUp) AgText else AgMuted.copy(alpha = 0.35f)
                    )
                }
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
                Icon(
                    if (showHidden) AgIcons.Visibility else AgIcons.VisibilityOff,
                    "隐藏文件",
                    tint = if (showHidden) AgCyan else AgMuted
                )
            }
            IconButton(onClick = onMkdir) { Icon(AgIcons.CreateNewFolder, "新建目录", tint = AgAccent) }
            IconButton(onClick = onUpload) { Icon(AgIcons.UploadFile, "上传", tint = AgCyan) }
        }

        transfer?.let { t ->
            Column(modifier = Modifier.fillMaxWidth().background(AgSurface).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${if (t.direction == "upload") "上传" else "下载"} ${t.fileName} (${t.percent}%)",
                        fontSize = 11.sp,
                        color = AgCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = onCancelTransfer,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text("取消", fontSize = 11.sp, color = AgDanger)
                    }
                }
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
            entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AgEmptyState(
                    icon = AgIcons.FolderOpen,
                    title = "目录为空",
                    subtitle = if (showHidden) "当前目录下没有任何文件或文件夹" else "当前目录暂无可见项，可点击上方眼睛图标显示隐藏文件"
                )
            }
            else -> LazyColumn(
                state = listState,
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

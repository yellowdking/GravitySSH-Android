package com.gravityssh.app.ui.sync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gravityssh.app.model.SyncInboxItemUi
import com.gravityssh.app.ui.components.AgBadge
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.components.AgStatusBanner
import com.gravityssh.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SyncInboxSectionCard(
    items: List<SyncInboxItemUi>,
    isRefreshing: Boolean,
    busy: Boolean,
    pendingInboxItemId: String?,
    bytesDone: Long,
    bytesTotal: Long,
    onRefresh: () -> Unit,
    onMerge: (String) -> Unit,
    onRequestOverwrite: (String) -> Unit,
    onDismiss: (String) -> Unit
) {
    AgCard(borderColor = if (items.isNotEmpty()) AgCyan.copy(alpha = 0.7f) else AgBorder) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(AgIcons.Download, null, tint = AgCyan, modifier = Modifier.size(18.dp))
                Text("待收配置", style = MaterialTheme.typography.titleMedium, color = AgText)
                if (items.isNotEmpty()) {
                    AgBadge(text = "${items.size}", color = AgCyan)
                }
            }
            IconButton(onClick = onRefresh, enabled = !isRefreshing && !busy, modifier = Modifier.size(30.dp)) {
                if (isRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = AgCyan)
                } else {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = AgCyan, modifier = Modifier.size(18.dp))
                }
            }
        }

        if (items.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "暂无待收配置",
                style = MaterialTheme.typography.bodySmall,
                color = AgMuted
            )
        } else {
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.forEach { item ->
                    val isItemBusy = busy && pendingInboxItemId == item.itemId
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = AgElevated,
                        border = BorderStroke(1.dp, if (item.pairingBroken) AgDanger else AgBorderLight),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = item.senderLabel.ifBlank { item.senderDeviceId.take(8) },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = AgText
                                    )
                                    if (item.senderPlatform.isNotBlank()) {
                                        AgBadge(text = item.senderPlatform, color = AgAccentLight)
                                    }
                                }
                                Text(
                                    text = "${formatEpochSecShort(item.createdAt)} · ${formatSyncBytes(item.totalBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AgMuted
                                )
                            }

                            if (item.pairingBroken) {
                                AgStatusBanner(
                                    message = item.errorMessage.ifBlank {
                                        "配对状态已失效，请删除并重新绑定设备"
                                    },
                                    color = AgDanger,
                                    icon = Icons.Rounded.Warning
                                )
                                OutlinedButton(
                                    onClick = { onDismiss(item.itemId) },
                                    enabled = !busy,
                                    border = BorderStroke(1.dp, AgDanger),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth().height(36.dp)
                                ) {
                                    Text("删除", color = AgDanger, fontSize = 12.sp)
                                }
                            } else {
                                Text(
                                    text = "${item.serverCount} 主机 · ${item.keyCount} 密钥 · ${item.shortcutCount} 快捷命令 · ${item.templateCount} 模板",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AgMuted
                                )
                                if (isItemBusy) {
                                    SyncTransferProgressRow(
                                        bytesDone = bytesDone,
                                        bytesTotal = bytesTotal,
                                        label = "正在导入..."
                                    )
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Button(
                                        onClick = { onMerge(item.itemId) },
                                        enabled = !busy,
                                        colors = ButtonDefaults.buttonColors(containerColor = AgAccent),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.weight(1.2f).height(36.dp)
                                    ) {
                                        if (isItemBusy) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                                            Spacer(Modifier.width(5.dp))
                                        }
                                        Text("合并导入", color = Color.White, fontSize = 12.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { onRequestOverwrite(item.itemId) },
                                        enabled = !busy,
                                        border = BorderStroke(1.dp, AgWarning),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.weight(1.1f).height(36.dp)
                                    ) {
                                        Text("覆盖导入", color = AgWarning, fontSize = 12.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { onDismiss(item.itemId) },
                                        enabled = !busy,
                                        border = BorderStroke(1.dp, AgDanger.copy(alpha = 0.8f)),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.weight(0.8f).height(36.dp)
                                    ) {
                                        Text("删除", color = AgDanger, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun formatSyncRoomCode(raw: String): String {
    val clean = raw.trim().replace("-", "").replace(" ", "").uppercase(Locale.US)
    return if (clean.length == 8) "${clean.substring(0, 4)}-${clean.substring(4, 8)}" else raw.trim()
}

internal fun formatSyncBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.2f MiB", bytes / (1024.0 * 1024.0))
}

internal fun formatEpochSecShort(epochSec: Long): String {
    if (epochSec <= 0L) return "--"
    val ms = if (epochSec > 10_000_000_000L) epochSec else epochSec * 1000L
    return runCatching {
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ms))
    }.getOrDefault("--")
}

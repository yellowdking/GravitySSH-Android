package com.gravityssh.app.ui.servers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gravityssh.app.model.JumpMode
import com.gravityssh.app.model.ProxyType
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.ui.components.AgBadge
import com.gravityssh.app.ui.theme.*

@Composable
fun ServerCardItem(
    server: ServerConfig,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val accent = parseHexColor(server.color)
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onConnect),
        shape = RoundedCornerShape(14.dp),
        color = AgPanel,
        border = BorderStroke(1.dp, AgBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(4.dp).height(42.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(server.name.ifBlank { server.host }, style = MaterialTheme.typography.titleMedium, color = AgText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${server.username}@${server.host}:${server.port}", style = MaterialTheme.typography.bodySmall, fontFamily = MonoFontFamily, color = AgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AgBadge(server.authType.uppercase(), AgAccent)
                    if (server.proxy.type != ProxyType.NONE) AgBadge(server.proxy.type.uppercase(), AgCyan)
                    if (server.jumpHost.mode != JumpMode.NONE) AgBadge("JUMP", AgViolet)
                }
            }
            IconButton(onClick = onConnect) { Icon(Icons.Rounded.PlayArrow, "连接", tint = AgCyan, modifier = Modifier.size(22.dp)) }
            IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, "编辑", tint = AgMuted, modifier = Modifier.size(18.dp)) }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "删除", tint = AgDanger.copy(alpha = 0.85f), modifier = Modifier.size(18.dp)) }
        }
    }
}

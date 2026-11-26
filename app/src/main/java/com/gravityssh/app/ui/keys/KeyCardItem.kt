package com.gravityssh.app.ui.keys

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.ui.components.AgBadge
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.theme.*

@Composable
fun KeyCardItem(
    key: SSHKeyRecord,
    onCopy: () -> Unit,
    onDeploy: () -> Unit,
    onDelete: () -> Unit
) {
    AgCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Text(key.name, style = MaterialTheme.typography.titleMedium, color = AgText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                AgBadge(key.keyType.uppercase(), AgAccent)
            }
            Row {
                IconButton(onClick = onCopy, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.ContentCopy, "复制公钥", tint = AgCyan, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDeploy, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.RocketLaunch, "部署到主机", tint = AgAccent, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.Delete, "删除", tint = AgDanger.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(key.fingerprint.ifBlank { "无指纹" }, fontFamily = MonoFontFamily, fontSize = 11.sp, color = AgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (key.comment.isNotBlank()) {
            Text(key.comment, style = MaterialTheme.typography.labelSmall, color = AgMuted)
        }
    }
}

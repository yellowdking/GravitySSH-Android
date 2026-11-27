package com.gravityssh.app.ui.sftp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gravityssh.app.model.SFTPFileEntry
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.theme.*
import java.util.Locale

@Composable
internal fun SftpRow(entry: SFTPFileEntry, onTap: () -> Unit, onMenu: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = AgPanel,
        border = BorderStroke(1.dp, AgBorder),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap)
    ) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (entry.isDir) AgIcons.Folder else AgIcons.InsertDriveFile,
                null,
                tint = if (entry.isDir) AgWarning else AgCyan,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium, color = AgText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${entry.mode}  ${if (entry.isDir) "目录" else formatBytes(entry.size)}", fontFamily = MonoFontFamily, fontSize = 11.sp, color = AgMuted)
            }
            IconButton(onClick = onMenu, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.MoreVert, "操作", tint = AgMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun formatBytes(size: Long): String = when {
    size >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", size / 1048576.0)
    size >= 1024 -> String.format(Locale.US, "%.1f KB", size / 1024.0)
    else -> "$size B"
}

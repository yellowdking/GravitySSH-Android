package com.gravityssh.app.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gravityssh.app.model.BuiltinTemplate
import com.gravityssh.app.model.CommandRecord
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.ui.components.AgBadge
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.theme.*

@Composable
internal fun ShortcutItemCard(
    sc: CommandShortcut,
    onTogglePin: () -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    AgCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(sc.name, style = MaterialTheme.typography.titleSmall, color = AgText)
                    AgBadge(sc.category, AgAccent)
                }
                Spacer(Modifier.height(4.dp))
                Text(sc.command, fontFamily = MonoFontFamily, fontSize = 11.sp, color = AgMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onTogglePin, modifier = Modifier.size(32.dp)) {
                Icon(AgIcons.PushPin, "置顶", tint = if (sc.pinned) AgWarning else AgMuted, modifier = Modifier.size(17.dp))
            }
            IconButton(onClick = onRun, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.PlayArrow, "执行", tint = AgCyan, modifier = Modifier.size(20.dp)) }
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Edit, "编辑", tint = AgMuted, modifier = Modifier.size(17.dp)) }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Delete, "删除", tint = AgDanger, modifier = Modifier.size(17.dp)) }
        }
    }
}

@Composable
internal fun TemplateItemCard(
    tpl: BuiltinTemplate,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    AgCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tpl.name, style = MaterialTheme.typography.titleSmall, color = AgText)
                    AgBadge(tpl.category, AgCyan)
                }
                if (tpl.description.isNotBlank()) Text(tpl.description, style = MaterialTheme.typography.bodySmall, color = AgMuted)
                if (tpl.safetyNote.isNotBlank()) Text("⚠ ${tpl.safetyNote}", style = MaterialTheme.typography.labelSmall, color = AgWarning)
            }
            IconButton(onClick = onRun, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.PlayArrow, "执行", tint = AgCyan, modifier = Modifier.size(20.dp)) }
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Edit, "编辑", tint = AgMuted, modifier = Modifier.size(17.dp)) }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Delete, "删除", tint = AgDanger, modifier = Modifier.size(17.dp)) }
        }
    }
}

@Composable
internal fun HistoryItemCard(rec: CommandRecord, onStar: () -> Unit, onRun: () -> Unit) {
    AgCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rec.command, fontFamily = MonoFontFamily, fontSize = 12.sp, color = AgText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(rec.serverName, style = MaterialTheme.typography.labelSmall, color = AgMuted)
            }
            IconButton(onClick = onStar, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Star, "收藏", tint = AgWarning, modifier = Modifier.size(18.dp)) }
            IconButton(onClick = onRun, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.PlayArrow, "执行", tint = AgCyan, modifier = Modifier.size(20.dp)) }
        }
    }
}

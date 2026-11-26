package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gravityssh.app.model.CompletionItem
import com.gravityssh.app.ui.theme.AgAccent
import com.gravityssh.app.ui.theme.AgBorder
import com.gravityssh.app.ui.theme.AgCyan
import com.gravityssh.app.ui.theme.AgElevated
import com.gravityssh.app.ui.theme.AgMuted
import com.gravityssh.app.ui.theme.AgPanel
import com.gravityssh.app.ui.theme.AgText
import com.gravityssh.app.ui.theme.AgViolet
import com.gravityssh.app.ui.theme.MonoFontFamily

@Composable
fun AutocompleteBar(
    suggestions: List<CompletionItem>,
    onPick: (CompletionItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (suggestions.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AgPanel)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        suggestions.forEach { item ->
            val tagColor = when (item.source) {
                "shortcut" -> AgAccent
                "history" -> AgCyan
                else -> AgViolet
            }
            Surface(
                onClick = { onPick(item) },
                shape = RoundedCornerShape(8.dp),
                color = AgElevated,
                border = BorderStroke(1.dp, AgBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.command,
                        color = AgText,
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.description.isNotBlank()) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = item.description,
                            color = tagColor.copy(alpha = 0.9f),
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

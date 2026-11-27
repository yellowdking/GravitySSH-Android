package com.gravityssh.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.terminal.TouchCursorMode
import com.gravityssh.app.terminal.TouchSensitivity
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.components.AgFilterChip
import com.gravityssh.app.ui.components.AgSwitchRow
import com.gravityssh.app.ui.theme.*
import com.gravityssh.app.ui.updateSettings

@Composable
internal fun SettingsTouchCursorCard(viewModel: AppViewModel) {
    val vaultState by viewModel.vaultState.collectAsStateWithLifecycle()
    val settings = vaultState.data.settings
    val curMode = TouchCursorMode.fromId(settings.touchCursorMode)
    val curSens = TouchSensitivity.fromId(settings.touchCursorSensitivity)

    AgCard {
        Text("触控光标与 Nano 编辑器手势", style = MaterialTheme.typography.titleMedium, color = AgText)
        Spacer(Modifier.height(4.dp))
        Text(
            "慢滑精准逐字微调、快滑智能加速跳转；支持点击定位光标与快速滑行减速。",
            style = MaterialTheme.typography.bodySmall,
            color = AgMuted
        )
        Spacer(Modifier.height(10.dp))

        Text("触控模式", style = MaterialTheme.typography.labelMedium, color = AgMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TouchCursorMode.entries.forEach { m ->
                AgFilterChip(
                    selected = curMode == m,
                    onClick = { viewModel.updateSettings { it.copy(touchCursorMode = m.id) } },
                    label = m.label,
                    selectedColor = AgAccent
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("滑动加速灵敏度", style = MaterialTheme.typography.labelMedium, color = AgMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TouchSensitivity.entries.forEach { s ->
                AgFilterChip(
                    selected = curSens == s,
                    onClick = { viewModel.updateSettings { it.copy(touchCursorSensitivity = s.id) } },
                    label = s.label,
                    selectedColor = AgCyan
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        AgSwitchRow("快速滑动惯性滑行减速", settings.touchCursorFling) { v ->
            viewModel.updateSettings { it.copy(touchCursorFling = v) }
        }
    }
}

package com.gravityssh.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private data class TouchCursorSettingsSlice(
    val mode: String,
    val sensitivity: String,
    val fling: Boolean
)

@Composable
internal fun SettingsTouchCursorCard(viewModel: AppViewModel) {
    val slice by remember(viewModel) {
        viewModel.vaultState
            .map {
                TouchCursorSettingsSlice(
                    mode = it.data.settings.touchCursorMode,
                    sensitivity = it.data.settings.touchCursorSensitivity,
                    fling = it.data.settings.touchCursorFling
                )
            }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = viewModel.vaultState.value.data.settings.let {
            TouchCursorSettingsSlice(it.touchCursorMode, it.touchCursorSensitivity, it.touchCursorFling)
        }
    )
    val curMode = TouchCursorMode.fromId(slice.mode)
    val curSens = TouchSensitivity.fromId(slice.sensitivity)

    AgCard {
        Text("触控光标与 Nano 编辑器手势", style = MaterialTheme.typography.titleMedium, color = AgText)
        Spacer(Modifier.height(8.dp))

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
        AgSwitchRow("快速滑动惯性滑行减速", slice.fling) { v ->
            viewModel.updateSettings { it.copy(touchCursorFling = v) }
        }
    }
}

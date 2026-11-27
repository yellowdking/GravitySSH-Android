package com.gravityssh.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.sync.SyncRelayCard
import com.gravityssh.app.ui.theme.*
import com.gravityssh.app.ui.updateSettings
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: AppViewModel, onOpenSync: () -> Unit) {
    val vaultState by viewModel.vaultState.collectAsStateWithLifecycle()
    val settings = vaultState.data.settings
    var fontSizeSlider by remember(settings.terminalFontSize) {
        mutableFloatStateOf(settings.terminalFontSize.toFloat())
    }

    Scaffold(
        containerColor = AgBg,
        topBar = {
            TopAppBar(
                title = { Text("设置与安全", style = MaterialTheme.typography.titleLarge, color = AgText) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AgSurface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AgCard(borderColor = AgCyan.copy(alpha = 0.5f), modifier = Modifier.clickable(onClick = onOpenSync)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.QrCode2, null, tint = AgCyan, modifier = Modifier.size(28.dp))
                        Column {
                            Text("多端同步", style = MaterialTheme.typography.titleMedium, color = AgText)
                            Text("一次性同步码 / 二维码端到端加密迁移", style = MaterialTheme.typography.bodySmall, color = AgMuted)
                        }
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = AgMuted)
                }
            }

            AgCard {
                Text("终端体验与智能辅助", style = MaterialTheme.typography.titleMedium, color = AgText)
                Spacer(Modifier.height(6.dp))
                ToggleRow("实时命令自动补全", settings.autocompleteEnabled) { v -> viewModel.updateSettings { it.copy(autocompleteEnabled = v) } }
                ToggleRow("终端顶部主机监控条 (CPU/RAM/RTT)", settings.monitorBarEnabled) { v -> viewModel.updateSettings { it.copy(monitorBarEnabled = v) } }
                ToggleRow("安全粘贴拦截 (高危命令/隐藏字符)", settings.pasteGuardEnabled) { v -> viewModel.updateSettings { it.copy(pasteGuardEnabled = v) } }
                ToggleRow("终端关键字语义高亮 (Error/Warn/IP)", settings.keywordHighlightEnabled) { v -> viewModel.updateSettings { it.copy(keywordHighlightEnabled = v) } }
                ToggleRow("记录命令执行历史", settings.recordCommands) { v -> viewModel.updateSettings { it.copy(recordCommands = v) } }
                ToggleRow("启动时自动检查更新", settings.checkUpdatesOnStartup) { v -> viewModel.updateSettings { it.copy(checkUpdatesOnStartup = v) } }
                Spacer(Modifier.height(8.dp))
                Text("终端字号：${fontSizeSlider.roundToInt()} sp", style = MaterialTheme.typography.bodyMedium, color = AgText)
                Slider(
                    value = fontSizeSlider,
                    onValueChange = { fontSizeSlider = it },
                    onValueChangeFinished = {
                        val target = fontSizeSlider.roundToInt().coerceIn(10, 22)
                        if (target != settings.terminalFontSize) {
                            viewModel.updateSettings { it.copy(terminalFontSize = target) }
                        }
                    },
                    valueRange = 10f..22f,
                    steps = 11,
                    colors = SliderDefaults.colors(thumbColor = AgAccent, activeTrackColor = AgAccent)
                )
            }

            SyncRelayCard(viewModel = viewModel)

            SettingsTouchCursorCard(viewModel = viewModel)
            SettingsSecuritySection(viewModel = viewModel)
            SettingsUpdateCard(viewModel = viewModel)
            Spacer(Modifier.height(20.dp))
        }
    }
}

package com.gravityssh.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.model.SyncRelayStatusUi
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.components.AgBadge
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.components.AgSwitchRow
import com.gravityssh.app.ui.theme.*
import com.gravityssh.app.ui.updateSettings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

private data class SettingsNavCounts(
    val keyCount: Int,
    val shortcutCount: Int,
    val templateCount: Int,
    val historyCount: Int
)

private data class SettingsSyncOverview(
    val relay: SyncRelayStatusUi,
    val pairedCount: Int,
    val inboxCount: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    onOpenSync: () -> Unit,
    onOpenKeys: () -> Unit,
    onOpenTools: () -> Unit
) {
    val vm = viewModel
    val settings by remember(vm) {
        vm.vaultState.map { it.data.settings }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = vm.vaultState.value.data.settings)
    val navCounts by remember(vm) {
        vm.vaultState
            .map {
                SettingsNavCounts(
                    keyCount = it.data.sshKeys.size,
                    shortcutCount = it.data.shortcuts.size,
                    templateCount = it.data.templates.size,
                    historyCount = it.data.history.size
                )
            }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = vm.vaultState.value.data.let {
            SettingsNavCounts(it.sshKeys.size, it.shortcuts.size, it.templates.size, it.history.size)
        }
    )
    val syncOverview by remember(vm) {
        vm.syncUiState
            .map {
                SettingsSyncOverview(
                    relay = it.relay,
                    pairedCount = it.pairedDevices.size,
                    inboxCount = it.inboxItems.size
                )
            }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = vm.syncUiState.value.let {
            SettingsSyncOverview(
                relay = it.relay,
                pairedCount = it.pairedDevices.size,
                inboxCount = it.inboxItems.size
            )
        }
    )

    var fontSizeSlider by remember(settings.terminalFontSize) {
        mutableFloatStateOf(settings.terminalFontSize.toFloat())
    }

    val relaySubtitle = remember(syncOverview) {
        val relayLabel = if (syncOverview.relay.mode == "custom") {
            val safeRedacted = syncOverview.relay.redactedUri
                .ifBlank { syncOverview.relay.endpoint }
                .substringBefore('#')
                .ifBlank { "自定义中继" }
            "自定义中继: $safeRedacted"
        } else {
            "官方默认中继 (TLS 1.3 + SPKI Pin)"
        }
        if (syncOverview.pairedCount > 0) {
            "$relayLabel · 已配对 ${syncOverview.pairedCount} 台"
        } else {
            relayLabel
        }
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsNavCard(
                icon = AgIcons.QrCode2,
                tint = AgCyan,
                borderColor = AgCyan.copy(alpha = 0.5f),
                title = "多端同步与中继",
                subtitle = relaySubtitle,
                badgeText = if (syncOverview.inboxCount > 0) "待收 ${syncOverview.inboxCount}" else null,
                onClick = onOpenSync
            )
            SettingsNavCard(
                icon = AgIcons.Key,
                tint = AgAccent,
                title = "密钥",
                subtitle = "${navCounts.keyCount} 把 SSH 密钥 · 硬件级加密存储与主机部署",
                onClick = onOpenKeys
            )
            SettingsNavCard(
                icon = AgIcons.Bolt,
                tint = AgCyan,
                title = "快捷",
                subtitle = "${navCounts.shortcutCount} 快捷命令 · ${navCounts.templateCount} 模板 · ${navCounts.historyCount} 历史",
                onClick = onOpenTools
            )

            AgCard {
                Text("终端体验与智能辅助", style = MaterialTheme.typography.titleMedium, color = AgText)
                Spacer(Modifier.height(6.dp))
                AgSwitchRow("实时命令自动补全", settings.autocompleteEnabled) { v -> vm.updateSettings { it.copy(autocompleteEnabled = v) } }
                AgSwitchRow("终端顶部主机监控条 (CPU/RAM/RTT)", settings.monitorBarEnabled) { v -> vm.updateSettings { it.copy(monitorBarEnabled = v) } }
                AgSwitchRow("安全粘贴拦截 (高危命令/隐藏字符)", settings.pasteGuardEnabled) { v -> vm.updateSettings { it.copy(pasteGuardEnabled = v) } }
                AgSwitchRow("终端关键字语义高亮 (Error/Warn/IP)", settings.keywordHighlightEnabled) { v -> vm.updateSettings { it.copy(keywordHighlightEnabled = v) } }
                AgSwitchRow("记录命令执行历史", settings.recordCommands) { v -> vm.updateSettings { it.copy(recordCommands = v) } }
                AgSwitchRow("启动时自动检查更新", settings.checkUpdatesOnStartup) { v -> vm.updateSettings { it.copy(checkUpdatesOnStartup = v) } }
                Spacer(Modifier.height(8.dp))
                Text("终端字号：${fontSizeSlider.roundToInt()} sp", style = MaterialTheme.typography.bodyMedium, color = AgText)
                Slider(
                    value = fontSizeSlider,
                    onValueChange = { fontSizeSlider = it },
                    onValueChangeFinished = {
                        val target = fontSizeSlider.roundToInt().coerceIn(10, 22)
                        if (target != settings.terminalFontSize) {
                            vm.updateSettings { it.copy(terminalFontSize = target) }
                        }
                    },
                    valueRange = 10f..22f,
                    steps = 11,
                    colors = SliderDefaults.colors(thumbColor = AgAccent, activeTrackColor = AgAccent)
                )
            }

            SettingsTouchCursorCard(viewModel = vm)
            SettingsSecuritySection(viewModel = vm)
            SettingsUpdateCard(viewModel = vm)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SettingsNavCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    borderColor: Color = AgBorder,
    badgeText: String? = null,
    onClick: () -> Unit
) {
    AgCard(borderColor = borderColor, modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(title, style = MaterialTheme.typography.titleMedium, color = AgText)
                        if (!badgeText.isNullOrBlank()) {
                            AgBadge(text = badgeText, color = AgCyan)
                        }
                    }
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AgMuted)
                }
            }
            Icon(AgIcons.ChevronRight, null, tint = AgMuted)
        }
    }
}

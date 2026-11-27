package com.gravityssh.app.ui.sync

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.cleanupAbandonedSyncOnLeave
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }

    DisposableEffect(context) {
        onDispose {
            val changingConfig = context.findSyncActivity()?.isChangingConfigurations == true
            if (!changingConfig) {
                viewModel.cleanupAbandonedSyncOnLeave()
            }
        }
    }

    Scaffold(
        containerColor = AgBg,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = AgText)
                    }
                },
                title = { Text("多端同步", style = MaterialTheme.typography.titleLarge, color = AgText) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AgSurface)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            TabRow(selectedTabIndex = tabIndex, containerColor = AgSurface, contentColor = AgAccent) {
                Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = {
                    Text("发送到其他设备", color = if (tabIndex == 0) AgText else AgMuted)
                })
                Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = {
                    Text("从其他设备接收", color = if (tabIndex == 1) AgText else AgMuted)
                })
            }

            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SyncRelayCard(viewModel = viewModel)

                AgCard(borderColor = AgCyan.copy(alpha = 0.35f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(AgIcons.Shield, contentDescription = null, tint = AgCyan, modifier = Modifier.size(18.dp))
                        Text("端到端零知识加密 · 阅后即焚", style = MaterialTheme.typography.titleSmall, color = AgCyan)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "使用 PBKDF2-SHA256 (200,000次) + AES-256-GCM 本地加密，中继服务器仅内存暂存密文，5 分钟过期或读取一次即销毁。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AgMuted
                    )
                }

                if (tabIndex == 0) SyncSendCard(viewModel = viewModel)
                else SyncReceiveCard(viewModel = viewModel)
            }
        }
    }
}

private tailrec fun Context.findSyncActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findSyncActivity()
    else -> null
}

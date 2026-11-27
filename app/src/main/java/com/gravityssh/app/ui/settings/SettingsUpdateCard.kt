package com.gravityssh.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.BuildConfig
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.theme.*

@Composable
internal fun SettingsUpdateCard(viewModel: AppViewModel) {
    val context = LocalContext.current
    val updateResult by viewModel.updateResult.collectAsStateWithLifecycle()
    val checkingUpdate by viewModel.checkingUpdate.collectAsStateWithLifecycle()

    AgCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("版本与更新", style = MaterialTheme.typography.titleMedium, color = AgText)
                Text("GravitySSH-Android v${BuildConfig.APP_VERSION}", style = MaterialTheme.typography.bodySmall, color = AgMuted)
            }
            Button(
                onClick = { viewModel.checkUpdates(silent = false) },
                enabled = !checkingUpdate,
                colors = ButtonDefaults.buttonColors(containerColor = AgAccent),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (checkingUpdate) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(6.dp))
                } else {
                    Icon(AgIcons.SystemUpdate, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text("检查更新", color = Color.White)
            }
        }
        updateResult?.let { u ->
            Spacer(Modifier.height(8.dp))
            Text(
                if (u.hasUpdate) "发现新版本 v${u.latestVersion}！" else "当前已是最新版本",
                style = MaterialTheme.typography.bodySmall,
                color = if (u.hasUpdate) AgSuccess else AgMuted
            )
            if (u.hasUpdate && u.downloadUrl.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.downloadUrl))) } },
                    border = BorderStroke(1.dp, AgSuccess)
                ) { Text("下载 v${u.latestVersion} APK", color = AgSuccess) }
            }
        }
    }
}

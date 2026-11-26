package com.gravityssh.app.ui.sync

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.gravityssh.app.ui.theme.*

@Composable
fun QrScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier.fillMaxWidth(0.92f).clip(RoundedCornerShape(18.dp)).background(AgPanel).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("扫描同步二维码", style = MaterialTheme.typography.titleLarge, color = AgText)
                TextButton(onClick = onDismiss) { Text("关闭", color = AgMuted) }
            }
            Spacer(Modifier.height(10.dp))
            if (hasPermission) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(14.dp)).background(AgBg),
                    contentAlignment = Alignment.Center
                ) {
                    QrCameraPreview(onScanned = onResult)
                    Box(modifier = Modifier.size(190.dp).border(2.dp, AgCyan, RoundedCornerShape(14.dp)))
                }
                Spacer(Modifier.height(10.dp))
                Text("将电脑端 GravitySSH 显示的二维码对准取景框", style = MaterialTheme.typography.bodySmall, color = AgMuted)
            } else {
                Text(
                    "需要相机权限才能扫描同步二维码，您也可以直接手动输入 8 位同步码。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AgMuted,
                    modifier = Modifier.padding(vertical = 20.dp)
                )
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = AgAccent)
                ) { Text("授予相机权限", color = Color.White) }
            }
        }
    }
}

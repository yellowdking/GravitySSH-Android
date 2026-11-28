package com.gravityssh.app.ui.sync

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gravityssh.app.ui.theme.*

@Composable
fun QrScannerDialog(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
    qrKind: SyncQrKind = SyncQrKind.PAIR,
    title: String = when (qrKind) {
        SyncQrKind.RELAY -> "扫描自定义中继二维码"
        SyncQrKind.PAIR -> "扫描同步 / 绑定二维码"
        SyncQrKind.ANY -> "扫描 synctool 二维码"
    },
    hint: String = when (qrKind) {
        SyncQrKind.RELAY -> "将 synctool-relay init 生成的 synctool://relay 二维码对准取景框"
        SyncQrKind.PAIR -> "将对端 GravitySSH 显示的 synctool://pair 二维码对准取景框"
        SyncQrKind.ANY -> "将二维码对准取景框自动识别"
    }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permanentlyDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            val activity = context.findActivity()
            permanentlyDenied = activity == null ||
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
        } else {
            permanentlyDenied = false
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                hasPermission = granted
                if (granted) permanentlyDenied = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(18.dp))
                .background(AgPanel)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = AgText)
                TextButton(onClick = onDismiss) { Text("关闭", color = AgMuted) }
            }
            Spacer(Modifier.height(10.dp))
            if (hasPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(AgBg),
                    contentAlignment = Alignment.Center
                ) {
                    QrCameraPreview(qrKind = qrKind, onScanned = onResult)
                    Box(modifier = Modifier.size(216.dp).border(2.dp, AgCyan, RoundedCornerShape(14.dp)))
                }
                Spacer(Modifier.height(10.dp))
                Text(hint, style = MaterialTheme.typography.bodySmall, color = AgMuted)
            } else {
                Text(
                    if (permanentlyDenied) {
                        "相机权限已被拒绝，请前往系统设置开启相机权限后再扫码，或直接手动输入。"
                    } else {
                        "需要相机权限才能扫描二维码，您也可以直接手动输入房间码或 URI。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = AgMuted,
                    modifier = Modifier.padding(vertical = 20.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!permanentlyDenied) {
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            colors = ButtonDefaults.buttonColors(containerColor = AgAccent)
                        ) { Text("授予相机权限", color = Color.White) }
                    }
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                )
                                context.startActivity(intent)
                            }
                        },
                        border = BorderStroke(1.dp, AgCyan)
                    ) { Text("打开系统设置", color = AgCyan) }
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

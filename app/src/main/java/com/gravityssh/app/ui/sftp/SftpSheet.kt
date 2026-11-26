package com.gravityssh.app.ui.sftp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gravityssh.app.model.*
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.theme.AgBg
import kotlinx.coroutines.launch

@Composable
fun SftpSheet(viewModel: AppViewModel, session: SessionTab, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sftp = viewModel.container.sftpService

    var currentPath by remember(session.sessionId) { mutableStateOf(session.currentDir.ifBlank { session.homeDir }) }
    var parentPath by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<SFTPFileEntry>>(emptyList()) }
    var showHidden by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var transfer by remember { mutableStateOf<TransferProgress?>(null) }
    var showMkdir by remember { mutableStateOf(false) }
    var selectedEntry by remember { mutableStateOf<SFTPFileEntry?>(null) }
    var renamingEntry by remember { mutableStateOf<SFTPFileEntry?>(null) }
    var deletingEntry by remember { mutableStateOf<SFTPFileEntry?>(null) }
    var deletingBusy by remember { mutableStateOf(false) }
    var editingFile by remember { mutableStateOf<RemoteFileContent?>(null) }
    var downloadTarget by remember { mutableStateOf<SFTPFileEntry?>(null) }

    fun reload(path: String = currentPath) {
        scope.launch {
            loading = true; errorMsg = null
            runCatching { sftp.list(session.sessionId, path, showHidden) }
                .onSuccess { currentPath = it.path; parentPath = it.parent; entries = it.entries }
                .onFailure { errorMsg = it.message ?: "读取目录失败" }
            loading = false
        }
    }

    LaunchedEffect(session.sessionId, showHidden) { reload(currentPath) }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (name, total) = queryDocumentMeta(context, uri)
            val remote = "${currentPath.trimEnd('/')}/$name"
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { sftp.uploadStream(session.sessionId, remote, it, total) { p -> transfer = p } }
            }.onSuccess { viewModel.showToast("上传完成: $name"); transfer = null; reload() }
                .onFailure { viewModel.showToast("上传失败: ${it.message}"); transfer = null }
        }
    }

    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val target = downloadTarget ?: return@rememberLauncherForActivityResult
        downloadTarget = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { sftp.downloadStream(session.sessionId, target.path, it) { p -> transfer = p } }
            }.onSuccess { viewModel.showToast("下载完成: ${target.name}"); transfer = null }
                .onFailure { viewModel.showToast("下载失败: ${it.message}"); transfer = null }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = AgBg) {
            SftpBrowserContent(
                session.serverName, currentPath, showHidden, loading, errorMsg, transfer, entries,
                onUp = { reload(parentPath) }, onHome = { reload(session.homeDir) }, onRefresh = { reload() },
                onToggleHidden = { showHidden = !showHidden }, onMkdir = { showMkdir = true },
                onUpload = { uploadLauncher.launch(arrayOf("*/*")) },
                onTapEntry = { if (it.isDir) reload(it.path) else selectedEntry = it },
                onMenuEntry = { selectedEntry = it }, onDismiss = onDismiss
            )
        }
    }

    SftpOverlays(
        session.sessionId, currentPath, sftp, showMkdir, selectedEntry, renamingEntry, deletingEntry, deletingBusy, editingFile,
        onDismissMkdir = { showMkdir = false }, onDismissSelected = { selectedEntry = null },
        onStartRename = { selectedEntry = null; renamingEntry = it }, onDismissRename = { renamingEntry = null },
        onStartDelete = { selectedEntry = null; deletingEntry = it }, onDismissDelete = { deletingEntry = null },
        onSetDeleteBusy = { deletingBusy = it }, onOpenEditor = { editingFile = it }, onDismissEditor = { editingFile = null },
        onStartDownload = { selectedEntry = null; downloadTarget = it; downloadLauncher.launch(it.name) },
        onReload = { reload() }, onToast = viewModel::showToast
    )
}

private fun queryDocumentMeta(context: Context, uri: Uri): Pair<String, Long> {
    var name = "upload.bin"; var total = -1L
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (ni >= 0) name = c.getString(ni) ?: name
            if (si >= 0) total = c.getLong(si)
        }
    }
    return name to total
}

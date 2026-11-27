package com.gravityssh.app.ui.sftp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gravityssh.app.model.*
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.theme.AgBg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun SftpSheet(viewModel: AppViewModel, session: SessionTab, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sftp = viewModel.container.sftpService
    val sid = session.sessionId

    var currentPath by remember(sid) { mutableStateOf(session.currentDir.ifBlank { session.homeDir }) }
    var parentPath by remember(sid) { mutableStateOf("/") }
    var entries by remember(sid) { mutableStateOf<List<SFTPFileEntry>>(emptyList()) }
    var showHidden by remember(sid) { mutableStateOf(false) }
    var loading by remember(sid) { mutableStateOf(true) }
    var errorMsg by remember(sid) { mutableStateOf<String?>(null) }
    var transfer by remember(sid) { mutableStateOf<TransferProgress?>(null) }
    var showMkdir by remember(sid) { mutableStateOf(false) }
    var selectedEntry by remember(sid) { mutableStateOf<SFTPFileEntry?>(null) }
    var renamingEntry by remember(sid) { mutableStateOf<SFTPFileEntry?>(null) }
    var deletingEntry by remember(sid) { mutableStateOf<SFTPFileEntry?>(null) }
    var deletingBusy by remember(sid) { mutableStateOf(false) }
    var editingFile by remember(sid) { mutableStateOf<RemoteFileContent?>(null) }
    var downloadTarget by remember(sid) { mutableStateOf<SFTPFileEntry?>(null) }
    var reloadJob by remember(sid) { mutableStateOf<Job?>(null) }
    var reloadGen by remember(sid) { mutableIntStateOf(0) }

    fun reload(path: String = currentPath) {
        reloadJob?.cancel()
        val gen = ++reloadGen
        reloadJob = scope.launch {
            loading = true
            errorMsg = null
            try {
                val res = sftp.list(sid, path, showHidden)
                currentPath = res.path
                parentPath = res.parent
                entries = res.entries
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorMsg = e.message ?: "读取目录失败"
            } finally {
                if (reloadGen == gen) loading = false
            }
        }
    }

    LaunchedEffect(sid, showHidden) { reload(currentPath) }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (name, total) = queryDocumentMeta(context, uri)
            val remote = "${currentPath.trimEnd('/')}/$name"
            val stream = context.contentResolver.openInputStream(uri)
            if (stream == null) {
                viewModel.showToast("上传失败: 无法打开所选文件")
                return@launch
            }
            runCatching {
                stream.use { sftp.uploadStream(sid, remote, it, total) { p -> transfer = p } }
            }.onSuccess { viewModel.showToast("上传完成: $name"); transfer = null; reload() }
                .onFailure { viewModel.showToast("上传失败: ${it.message}"); transfer = null }
        }
    }

    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val target = downloadTarget ?: return@rememberLauncherForActivityResult
        downloadTarget = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val stream = context.contentResolver.openOutputStream(uri)
            if (stream == null) {
                viewModel.showToast("下载失败: 无法写入目标文件")
                return@launch
            }
            runCatching {
                stream.use { sftp.downloadStream(sid, target.path, it) { p -> transfer = p } }
            }.onSuccess { viewModel.showToast("下载完成: ${target.name}"); transfer = null }
                .onFailure { viewModel.showToast("下载失败: ${it.message}"); transfer = null }
        }
    }

    val canNavigateUp = currentPath != "/" && parentPath.isNotBlank() && parentPath != currentPath

    Dialog(
        onDismissRequest = {
            if (canNavigateUp) reload(parentPath) else onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BackHandler(enabled = canNavigateUp) {
            reload(parentPath)
        }
        Surface(modifier = Modifier.fillMaxSize().imePadding(), color = AgBg) {
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
        sid, currentPath, sftp, showMkdir, selectedEntry, renamingEntry, deletingEntry, deletingBusy, editingFile,
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

package com.gravityssh.app.ui.sftp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.model.*
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.cancelSftpTransfer
import com.gravityssh.app.ui.startSftpDownload
import com.gravityssh.app.ui.startSftpUpload
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

    val transfer by viewModel.sftpTransfer.collectAsStateWithLifecycle()
    val mutatedTick by viewModel.sftpMutatedTick.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    var currentPath by remember(sid) { mutableStateOf(session.currentDir.ifBlank { session.homeDir }) }
    var lastLoadedPath by remember(sid) { mutableStateOf("") }
    var parentPath by remember(sid) { mutableStateOf("/") }
    var entries by remember(sid) { mutableStateOf<List<SFTPFileEntry>>(emptyList()) }
    var showHidden by remember(sid) { mutableStateOf(false) }
    var loading by remember(sid) { mutableStateOf(true) }
    var errorMsg by remember(sid) { mutableStateOf<String?>(null) }
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
                val pathChanged = res.path != lastLoadedPath
                currentPath = res.path
                parentPath = res.parent
                entries = res.entries
                lastLoadedPath = res.path
                if (pathChanged) {
                    listState.scrollToItem(0)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorMsg = e.message ?: "读取目录失败"
            } finally {
                if (reloadGen == gen) loading = false
            }
        }
    }

    LaunchedEffect(sid, showHidden, mutatedTick) { reload(currentPath) }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val (name, total) = queryDocumentMeta(context, uri)
        val remote = "${currentPath.trimEnd('/')}/$name"
        viewModel.startSftpUpload(context, sid, remote, name, uri, total)
    }

    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val target = downloadTarget ?: return@rememberLauncherForActivityResult
        downloadTarget = null
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.startSftpDownload(context, sid, target.path, target.name, uri)
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
                serverName = session.serverName,
                currentPath = currentPath,
                canNavigateUp = canNavigateUp,
                showHidden = showHidden,
                loading = loading,
                errorMsg = errorMsg,
                transfer = transfer?.takeIf { it.sessionId == sid },
                entries = entries,
                listState = listState,
                onUp = { if (canNavigateUp) reload(parentPath) },
                onHome = { reload(session.homeDir) },
                onRefresh = { reload() },
                onToggleHidden = { showHidden = !showHidden },
                onMkdir = { showMkdir = true },
                onUpload = { uploadLauncher.launch(arrayOf("*/*")) },
                onCancelTransfer = { viewModel.cancelSftpTransfer() },
                onTapEntry = { if (it.isDir) reload(it.path) else selectedEntry = it },
                onMenuEntry = { selectedEntry = it },
                onDismiss = onDismiss
            )
        }
    }

    SftpOverlays(
        viewModel = viewModel,
        sessionId = sid,
        currentPath = currentPath,
        sftp = sftp,
        showMkdir = showMkdir,
        selectedEntry = selectedEntry,
        renamingEntry = renamingEntry,
        deletingEntry = deletingEntry,
        deletingBusy = deletingBusy,
        editingFile = editingFile,
        onDismissMkdir = { showMkdir = false },
        onDismissSelected = { selectedEntry = null },
        onStartRename = { selectedEntry = null; renamingEntry = it },
        onDismissRename = { renamingEntry = null },
        onStartDelete = { selectedEntry = null; deletingEntry = it },
        onDismissDelete = { deletingEntry = null },
        onSetDeleteBusy = { deletingBusy = it },
        onOpenEditor = { editingFile = it },
        onDismissEditor = { editingFile = null },
        onStartDownload = { selectedEntry = null; downloadTarget = it; downloadLauncher.launch(it.name) },
        onReload = { reload() },
        onToast = viewModel::showToast
    )
}

private fun queryDocumentMeta(context: Context, uri: Uri): Pair<String, Long> {
    var name = "upload.bin"
    var total = -1L
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (ni >= 0 && !c.isNull(ni)) {
                name = (c.getString(ni) ?: name).substringAfterLast('/').ifBlank { name }
            }
            if (si >= 0 && !c.isNull(si)) {
                total = c.getLong(si)
            }
        }
    }
    return name to total
}

package com.gravityssh.app.ui.sftp

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.model.*
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.cancelSftpTransfer
import com.gravityssh.app.ui.deletePartialSafUri
import com.gravityssh.app.ui.startSftpDownload
import com.gravityssh.app.ui.startSftpUploadFromUri
import com.gravityssh.app.ui.theme.AgBg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun SftpSheet(viewModel: AppViewModel, session: SessionTab, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sftp = viewModel.container.sftpService
    val sid = session.sessionId

    val transfer by remember(viewModel, sid) {
        viewModel.sftpTransfers.map { it[sid] }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = viewModel.sftpTransfers.value[sid])
    val mutatedTick by viewModel.sftpMutatedTick.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val initialPath = remember(sid) { session.currentDir.ifBlank { session.homeDir } }
    var currentPath by remember(sid) { mutableStateOf(initialPath) }
    var requestedPath by remember(sid) { mutableStateOf(initialPath) }
    var lastLoadedPath by remember(sid) { mutableStateOf("") }
    var failedPath by remember(sid) { mutableStateOf<String?>(null) }
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
    var savedDownloadPath by rememberSaveable(sid) { mutableStateOf<String?>(null) }
    var savedDownloadName by rememberSaveable(sid) { mutableStateOf<String?>(null) }
    var reloadJob by remember(sid) { mutableStateOf<Job?>(null) }
    var reloadGen by remember(sid) { mutableIntStateOf(0) }

    fun reload(path: String = requestedPath) {
        requestedPath = path
        reloadJob?.cancel()
        val gen = ++reloadGen
        reloadJob = scope.launch {
            loading = true
            errorMsg = null
            failedPath = null
            try {
                val res = sftp.list(sid, path, showHidden)
                if (reloadGen != gen) return@launch
                val pathChanged = res.path != lastLoadedPath
                currentPath = res.path
                requestedPath = res.path
                parentPath = res.parent
                entries = res.entries
                lastLoadedPath = res.path
                if (pathChanged) {
                    listState.scrollToItem(0)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (reloadGen == gen) {
                    errorMsg = e.message ?: "读取目录失败"
                    failedPath = path
                    if (lastLoadedPath.isNotBlank()) {
                        // Keep currently visible directory as target for subsequent hidden-toggle/mutation reloads
                        requestedPath = currentPath
                    }
                }
            } finally {
                if (reloadGen == gen) loading = false
            }
        }
    }

    LaunchedEffect(sid, showHidden, mutatedTick) { reload(requestedPath) }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.startSftpUploadFromUri(context, sid, currentPath, uri)
    }

    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val vmTarget = viewModel.consumePendingSftpDownloadTarget(sid)
        val targetPath = vmTarget?.first ?: savedDownloadPath
        val targetName = vmTarget?.second ?: savedDownloadName
        savedDownloadPath = null
        savedDownloadName = null
        if (uri == null) return@rememberLauncherForActivityResult
        if (targetPath.isNullOrBlank() || targetName.isNullOrBlank()) {
            viewModel.deletePartialSafUri(context, uri)
            viewModel.showToast("下载目标已失效，请重新发起下载")
            return@rememberLauncherForActivityResult
        }
        viewModel.startSftpDownload(context, sid, targetPath, targetName, uri)
    }

    val hasLoadedOnce = lastLoadedPath.isNotBlank()
    val canNavigateUp = currentPath != "/" && parentPath.isNotBlank() && parentPath != currentPath

    fun handleBack(): Boolean {
        if (errorMsg != null && hasLoadedOnce) {
            errorMsg = null
            failedPath = null
            return true
        }
        if (canNavigateUp) {
            reload(parentPath)
            return true
        }
        return false
    }

    Dialog(
        onDismissRequest = {
            if (!handleBack()) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize().imePadding(), color = AgBg) {
            SftpBrowserContent(
                serverName = session.serverName,
                currentPath = currentPath,
                canNavigateUp = canNavigateUp,
                showHidden = showHidden,
                loading = loading,
                hasLoadedOnce = hasLoadedOnce,
                errorMsg = errorMsg,
                transfer = transfer,
                entries = entries,
                listState = listState,
                onUp = { if (canNavigateUp) reload(parentPath) },
                onHome = { reload(session.homeDir) },
                onRefresh = { reload(if (hasLoadedOnce) currentPath else (failedPath ?: requestedPath)) },
                onRetryError = { reload(failedPath ?: requestedPath) },
                onDismissError = { errorMsg = null; failedPath = null },
                onToggleHidden = { showHidden = !showHidden },
                onMkdir = { showMkdir = true },
                onUpload = { uploadLauncher.launch(arrayOf("*/*")) },
                onCancelTransfer = { viewModel.cancelSftpTransfer(sid) },
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
        onStartDownload = { entry ->
            selectedEntry = null
            viewModel.setPendingSftpDownloadTarget(sid, entry.path, entry.name)
            savedDownloadPath = entry.path
            savedDownloadName = entry.name
            downloadLauncher.launch(entry.name)
        },
        onReload = { reload(currentPath) },
        onToast = viewModel::showToast
    )
}

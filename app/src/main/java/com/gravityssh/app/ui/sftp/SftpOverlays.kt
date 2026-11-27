package com.gravityssh.app.ui.sftp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.gravityssh.app.model.RemoteFileContent
import com.gravityssh.app.model.SFTPFileEntry
import com.gravityssh.app.ssh.SftpService
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.runSftpOp
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

@Composable
internal fun SftpOverlays(
    viewModel: AppViewModel,
    sessionId: String,
    currentPath: String,
    sftp: SftpService,
    showMkdir: Boolean,
    selectedEntry: SFTPFileEntry?,
    renamingEntry: SFTPFileEntry?,
    deletingEntry: SFTPFileEntry?,
    deletingBusy: Boolean,
    editingFile: RemoteFileContent?,
    onDismissMkdir: () -> Unit,
    onDismissSelected: () -> Unit,
    onStartRename: (SFTPFileEntry) -> Unit,
    onDismissRename: () -> Unit,
    onStartDelete: (SFTPFileEntry) -> Unit,
    onDismissDelete: () -> Unit,
    onSetDeleteBusy: (Boolean) -> Unit,
    onOpenEditor: (RemoteFileContent) -> Unit,
    onDismissEditor: () -> Unit,
    onStartDownload: (SFTPFileEntry) -> Unit,
    onReload: () -> Unit,
    onToast: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var loadingEdit by remember { mutableStateOf(false) }

    if (showMkdir) {
        SftpNameInputDialog(
            title = "新建文件夹",
            label = "文件夹名称",
            onConfirm = { name, onDone ->
                viewModel.runSftpOp(errorPrefix = "新建失败", onDone = onDone) {
                    sftp.mkdir(sessionId, "${currentPath.trimEnd('/')}/$name")
                }
            },
            onDismiss = onDismissMkdir
        )
    }

    selectedEntry?.let { entry ->
        SftpEntryActionDialog(
            entry = entry,
            loadingEdit = loadingEdit,
            onEdit = {
                if (loadingEdit) return@SftpEntryActionDialog
                loadingEdit = true
                scope.launch {
                    runCatching { sftp.readTextFile(sessionId, entry.path) }
                        .onSuccess {
                            loadingEdit = false
                            onDismissSelected()
                            onOpenEditor(it)
                        }
                        .onFailure {
                            loadingEdit = false
                            onToast("打开失败: ${it.message}")
                        }
                }
            },
            onDownload = { onStartDownload(entry) },
            onRename = { onStartRename(entry) },
            onCompress = {
                onDismissSelected()
                val archive = "${currentPath.trimEnd('/')}/${entry.name}.tar.gz"
                viewModel.runSftpOp(successMsg = "已压缩为 ${entry.name}.tar.gz", errorPrefix = "压缩失败") {
                    sftp.compress(sessionId, listOf(entry.path), archive)
                }
            },
            onExtract = {
                onDismissSelected()
                viewModel.runSftpOp(successMsg = "解压完成", errorPrefix = "解压失败") {
                    sftp.extract(sessionId, entry.path, currentPath)
                }
            },
            onAlignPerms = {
                onDismissSelected()
                viewModel.runSftpOp(errorPrefix = "权限对齐失败") {
                    val mode = sftp.alignPermissions(sessionId, entry.path)
                    onToast("权限已对齐为 $mode")
                }
            },
            onDelete = { onStartDelete(entry) },
            onDismiss = onDismissSelected
        )
    }

    renamingEntry?.let { entry ->
        SftpNameInputDialog(
            title = "重命名",
            label = "新名称",
            initialValue = entry.name,
            confirmLabel = "重命名",
            onConfirm = { newName, onDone ->
                val dst = "${entry.path.substringBeforeLast('/')}/$newName"
                viewModel.runSftpOp(errorPrefix = "重命名失败", onDone = onDone) {
                    sftp.rename(sessionId, entry.path, dst)
                }
            },
            onDismiss = onDismissRename
        )
    }

    deletingEntry?.let { entry ->
        SftpDeleteConfirmDialog(
            entry = entry,
            busy = deletingBusy,
            onConfirm = {
                onSetDeleteBusy(true)
                viewModel.runSftpOp(
                    errorPrefix = "删除失败",
                    onDone = { res ->
                        onSetDeleteBusy(false)
                        if (res.isSuccess) onDismissDelete()
                    }
                ) {
                    sftp.delete(sessionId, entry.path)
                }
            },
            onDismiss = onDismissDelete
        )
    }

    editingFile?.let { file ->
        SftpEditorDialog(
            file = file,
            onSave = { newContent ->
                runCatching {
                    viewModel.viewModelScope.async {
                        sftp.writeTextFile(sessionId, file.path, newContent)
                    }.await()
                }.onSuccess {
                    onToast("文件已保存")
                    onDismissEditor()
                    onReload()
                }.onFailure {
                    onToast("保存失败: ${it.message}")
                }
            },
            onDismiss = onDismissEditor
        )
    }
}

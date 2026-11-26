package com.gravityssh.app.ui.sftp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.gravityssh.app.model.RemoteFileContent
import com.gravityssh.app.model.SFTPFileEntry
import com.gravityssh.app.ssh.SftpService
import kotlinx.coroutines.launch

@Composable
internal fun SftpOverlays(
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

    if (showMkdir) {
        SftpNameInputDialog("新建文件夹", "文件夹名称", onConfirm = { name ->
            onDismissMkdir()
            scope.launch {
                runCatching { sftp.mkdir(sessionId, "${currentPath.trimEnd('/')}/$name") }
                    .onSuccess { onReload() }.onFailure { onToast("新建失败: ${it.message}") }
            }
        }, onDismiss = onDismissMkdir)
    }

    selectedEntry?.let { entry ->
        SftpEntryActionDialog(
            entry = entry,
            onEdit = {
                onDismissSelected()
                scope.launch {
                    runCatching { sftp.readTextFile(sessionId, entry.path) }
                        .onSuccess { onOpenEditor(it) }.onFailure { onToast("打开失败: ${it.message}") }
                }
            },
            onDownload = { onStartDownload(entry) },
            onRename = { onStartRename(entry) },
            onCompress = {
                onDismissSelected()
                scope.launch {
                    val archive = "${currentPath.trimEnd('/')}/${entry.name}.tar.gz"
                    runCatching { sftp.compress(sessionId, listOf(entry.path), archive) }
                        .onSuccess { onToast("已压缩为 ${entry.name}.tar.gz"); onReload() }
                        .onFailure { onToast("压缩失败: ${it.message}") }
                }
            },
            onExtract = {
                onDismissSelected()
                scope.launch {
                    runCatching { sftp.extract(sessionId, entry.path, currentPath) }
                        .onSuccess { onToast("解压完成"); onReload() }
                        .onFailure { onToast("解压失败: ${it.message}") }
                }
            },
            onAlignPerms = {
                onDismissSelected()
                scope.launch {
                    runCatching { sftp.alignPermissions(sessionId, entry.path) }
                        .onSuccess { onToast("权限已对齐为 $it"); onReload() }
                        .onFailure { onToast("权限对齐失败: ${it.message}") }
                }
            },
            onDelete = { onStartDelete(entry) },
            onDismiss = onDismissSelected
        )
    }

    renamingEntry?.let { entry ->
        SftpNameInputDialog("重命名", "新名称", entry.name, "重命名", onConfirm = { newName ->
            onDismissRename()
            val dst = "${entry.path.substringBeforeLast('/')}/$newName"
            scope.launch {
                runCatching { sftp.rename(sessionId, entry.path, dst) }
                    .onSuccess { onReload() }.onFailure { onToast("重命名失败: ${it.message}") }
            }
        }, onDismiss = onDismissRename)
    }

    deletingEntry?.let { entry ->
        SftpDeleteConfirmDialog(entry, deletingBusy, onConfirm = {
            onSetDeleteBusy(true)
            scope.launch {
                runCatching { sftp.delete(sessionId, entry.path) }
                    .onSuccess { onDismissDelete(); onReload() }
                    .onFailure { onToast("删除失败: ${it.message}") }
                onSetDeleteBusy(false)
            }
        }, onDismiss = onDismissDelete)
    }

    editingFile?.let { file ->
        SftpEditorDialog(file, onSave = { newContent ->
            runCatching { sftp.writeTextFile(sessionId, file.path, newContent) }
                .onSuccess { onToast("文件已保存"); onDismissEditor(); onReload() }
                .onFailure { onToast("保存失败: ${it.message}") }
        }, onDismiss = onDismissEditor)
    }
}

package com.gravityssh.app.ssh

import com.gravityssh.app.model.SFTPFileEntry
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import java.time.Instant

internal object SshPathUtils {

    val ENTRY_COMPARATOR: Comparator<SFTPFileEntry> =
        compareByDescending<SFTPFileEntry> { it.isDir }.thenBy { it.name.lowercase() }

    fun normalizePosixPath(path: String): String {
        val parts = ArrayDeque<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(seg)
            }
        }
        return if (parts.isEmpty()) "/" else "/" + parts.joinToString("/")
    }

    fun parentDir(path: String): String {
        val norm = normalizePosixPath(path)
        return if (norm == "/") "/" else norm.substringBeforeLast('/').ifEmpty { "/" }
    }

    fun expandHome(rawPath: String, homeDir: String): String {
        val raw = rawPath.trim().ifBlank { "/" }
        val expanded = if (raw == "~" || raw.startsWith("~/")) {
            val home = homeDir.ifBlank { "/" }
            if (raw == "~") home else "${home.trimEnd('/')}/${raw.removePrefix("~/")}"
        } else {
            raw
        }
        return normalizePosixPath(expanded)
    }

    fun resolvePath(sftp: SFTPClient, raw: String): String {
        val t = raw.trim()
        if (t.isEmpty() || t == "~") {
            return normalizePosixPath(runCatching { sftp.canonicalize(".") }.getOrDefault("/"))
        }
        if (t.startsWith("~/")) {
            val home = runCatching { sftp.canonicalize(".") }.getOrDefault("/")
            return normalizePosixPath("${home.trimEnd('/')}/${t.removePrefix("~/")}")
        }
        return normalizePosixPath(t)
    }

    fun formatPermissions(mask: Int): String {
        val perm = mask and 0x1FF
        val chars = charArrayOf('r', 'w', 'x')
        val sb = StringBuilder(9)
        for (i in 8 downTo 0) {
            sb.append(if ((perm and (1 shl i)) != 0) chars[(8 - i) % 3] else '-')
        }
        return sb.toString()
    }

    fun shellQuote(s: String): String = "'${s.replace("'", "'\"'\"'")}'"

    fun filterLsItems(rawList: List<RemoteResourceInfo>, showHidden: Boolean): List<RemoteResourceInfo> =
        rawList.filter { info ->
            info.name != "." && info.name != ".." && (showHidden || !info.name.startsWith("."))
        }

    fun mapAndSortEntries(
        rawList: List<RemoteResourceInfo>,
        showHidden: Boolean,
        isDirOverride: ((RemoteResourceInfo) -> Boolean)? = null,
        includeModTimeIso: Boolean = true
    ): List<SFTPFileEntry> = filterLsItems(rawList, showHidden)
        .map { info -> toSftpFileEntry(info, isDirOverride?.invoke(info), includeModTimeIso) }
        .sortedWith(ENTRY_COMPARATOR)

    fun toSftpFileEntry(
        info: RemoteResourceInfo,
        isDirOverride: Boolean? = null,
        includeModTimeIso: Boolean = true
    ): SFTPFileEntry {
        val attrs = info.attributes
        val isSymlink = attrs.type == FileMode.Type.SYMLINK
        val isDir = isDirOverride ?: info.isDirectory
        return SFTPFileEntry(
            name = info.name,
            path = info.path,
            isDir = isDir,
            isSymlink = isSymlink,
            size = attrs.size,
            mode = formatPermissions(attrs.mode.mask),
            modTime = if (includeModTimeIso) Instant.ofEpochSecond(attrs.mtime).toString() else "",
            modTimeEpochSec = attrs.mtime
        )
    }
}

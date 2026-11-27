package com.gravityssh.app.terminal

enum class PasteWarningKind {
    DANGER,
    PIPE_SHELL,
    HIDDEN_CHARS,
    HOMOGLYPH,
    MULTILINE
}

data class PasteWarning(
    val kind: PasteWarningKind,
    val label: String,
    val detail: String
)

data class PasteInspection(
    val needsConfirm: Boolean,
    val hasHiddenChars: Boolean,
    val warnings: List<PasteWarning>
)

object PasteGuard {
    private val HIDDEN_CHARS_RE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2069\\uFEFF]")
    private val BRACKETED_PASTE_MARKER_RE = Regex("(?:\\u001B\\[|\\u009B)20[01]~")
    private val DANGEROUS_CONTROL_RE = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u009B]")
    private val PIPE_SHELL_RE = Regex("\\b(curl|wget)\\b[^\\n|]*\\|\\s*(sudo\\s+)?(sh|bash|zsh|ash)\\b", RegexOption.IGNORE_CASE)
    private val MIXED_LATIN_CYRILLIC_RE = Regex(
        "(?=[A-Za-z0-9_-]*[A-Za-z])(?=[A-Za-z0-9_\\u0400-\\u04FF-]*[\\u0400-\\u04FF])[A-Za-z0-9_\\u0400-\\u04FF-]+"
    )

    private const val RM_TARGETS = "(?:/(?:\\s|\$|\\*|;|\\||&)|~/?(?:\\s|\$|\\*|;|\\||&)|\\*(?:\\s|\$|\\*|;|\\||&))"
    private val DANGEROUS_PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex(
            "\\brm\\s+(?:" +
                "(?:-[a-zA-Z]*[rR][a-zA-Z]*f[a-zA-Z]*|-[a-zA-Z]*f[a-zA-Z]*[rR][a-zA-Z]*)|" +
                "(?:(?:-[a-zA-Z]*[rR][a-zA-Z]*|--recursive)\\s+(?:-[a-zA-Z-]+\\s+)*(?:-[a-zA-Z]*f[a-zA-Z]*|--force))|" +
                "(?:(?:-[a-zA-Z]*f[a-zA-Z]*|--force)\\s+(?:-[a-zA-Z-]+\\s+)*(?:-[a-zA-Z]*[rR][a-zA-Z]*|--recursive))" +
                ")(?:\\s+-[a-zA-Z-]+)*\\s+$RM_TARGETS"
        ) to "包含高危递归强制删除 (rm -rf)",
        Regex("\\bmkfs(\\.\\w+)?\\b") to "包含磁盘格式化命令 (mkfs)",
        Regex("\\bdd\\s+[^\\n]*\\bof=/dev/") to "包含直接覆写磁盘设备 (dd of=/dev/...)",
        Regex(":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:") to "包含 Fork 炸弹",
        Regex("\\bchmod\\s+(?:(?:-R|--recursive)\\s+0?777|0?777\\s+(?:-R|--recursive))\\s+/(?:\\s|\$|\\*|;|\\||&)") to "包含根目录全权放开 (chmod -R 777 /)",
        Regex(">\\s*/dev/(sd|nvme|vd|hd)[a-z]") to "包含重定向覆写块设备",
        Regex("\\b(shutdown|poweroff|reboot|init\\s+0)\\b") to "包含关机或重启系统命令",
        Regex("\\biptables\\s+(-[A-Za-z]+\\s+)*-F\\b") to "包含清空防火墙规则 (iptables -F)"
    )

    fun stripHiddenChars(text: String): String = text.replace(HIDDEN_CHARS_RE, "")

    fun sanitizePasteText(raw: String): String {
        if (raw.isEmpty()) return ""
        var s = raw
        while (true) {
            val next = s.replace(BRACKETED_PASTE_MARKER_RE, "")
            if (next == s) break
            s = next
        }
        return s
            .replace("\r\n", "\n")
            .replace("\r", "\n")
    }

    fun inspect(text: String, bracketedPasteActive: Boolean): PasteInspection {
        if (text.isEmpty()) {
            return PasteInspection(needsConfirm = false, hasHiddenChars = false, warnings = emptyList())
        }
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val warnings = mutableListOf<PasteWarning>()
        val hasHidden = HIDDEN_CHARS_RE.containsMatchIn(normalized)
        val purified = if (hasHidden) stripHiddenChars(normalized) else normalized
        val candidates = if (hasHidden) listOf(normalized, purified) else listOf(normalized)

        if (hasHidden) {
            warnings.add(
                PasteWarning(
                    kind = PasteWarningKind.HIDDEN_CHARS,
                    label = "隐藏/双向控制字符",
                    detail = "内容包含零宽空格或 BiDi 方向控制字符，可能隐藏真实执行的命令"
                )
            )
        }

        if (DANGEROUS_CONTROL_RE.containsMatchIn(normalized)) {
            val hasEscape = '\u001B' in normalized || '\u009B' in normalized
            warnings.add(
                PasteWarning(
                    kind = PasteWarningKind.DANGER,
                    label = if (hasEscape) "终端转义/控制序列" else "异常控制字符",
                    detail = if (hasEscape) {
                        "内容包含 ESC/CSI 终端控制序列，可能绕过括号粘贴保护或篡改终端状态"
                    } else {
                        "内容包含不可见 C0 控制字符，可能触发非预期终端操作"
                    }
                )
            )
        }

        if (candidates.any { MIXED_LATIN_CYRILLIC_RE.containsMatchIn(it) }) {
            warnings.add(
                PasteWarning(
                    kind = PasteWarningKind.HOMOGLYPH,
                    label = "可疑同形字混用",
                    detail = "同一单词中混用了拉丁字母与西里尔字母（可能为伪装字符）"
                )
            )
        }

        for ((re, detail) in DANGEROUS_PATTERNS) {
            if (candidates.any { re.containsMatchIn(it) }) {
                warnings.add(PasteWarning(PasteWarningKind.DANGER, "高危系统命令", detail))
                break
            }
        }

        if (candidates.any { PIPE_SHELL_RE.containsMatchIn(it) }) {
            warnings.add(
                PasteWarning(
                    kind = PasteWarningKind.PIPE_SHELL,
                    label = "远端脚本直接执行",
                    detail = "检测到 curl/wget 管道直接交给 shell 执行，请确认来源可信"
                )
            )
        }

        val lines = normalized.split('\n').filter { it.trim().isNotEmpty() }
        if (!bracketedPasteActive) {
            if (lines.size > 1) {
                warnings.add(
                    PasteWarning(
                        kind = PasteWarningKind.MULTILINE,
                        label = "多行立即执行 (${lines.size} 行)",
                        detail = "当前远端未开启括号粘贴，多行内容会立即逐行回车执行"
                    )
                )
            } else if ('\n' in normalized) {
                warnings.add(
                    PasteWarning(
                        kind = PasteWarningKind.MULTILINE,
                        label = "含换行将立即执行",
                        detail = "当前远端未开启括号粘贴，末尾换行符会使命令立即回车执行"
                    )
                )
            }
        }

        return PasteInspection(
            needsConfirm = warnings.isNotEmpty(),
            hasHiddenChars = hasHidden,
            warnings = warnings
        )
    }
}

package com.gravityssh.app.ui.theme

import androidx.compose.ui.graphics.Color

// Antigravity Dark Theme Palette — exact parity with Windows GravitySSH
val AgBg = Color(0xFF0B0D12)
val AgSurface = Color(0xFF10131A)
val AgPanel = Color(0xFF141822)
val AgElevated = Color(0xFF1A202E)
val AgHover = Color(0xFF21283A)
val AgBorder = Color(0xFF23293A)
val AgBorderLight = Color(0xFF2E364D)
val AgInputBg = Color(0xFF0D1017)

val AgAccent = Color(0xFF6366F1)       // Indigo 500
val AgAccentLight = Color(0xFF818CF8)  // Indigo 400
val AgViolet = Color(0xFF8B5CF6)       // Violet 500
val AgCyan = Color(0xFF38BDF8)         // Sky 400

val AgText = Color(0xFFE6EAF5)
val AgMuted = Color(0xFF8C96B0)
val AgSubtle = Color(0xFF5D6782)

val AgSuccess = Color(0xFF22C55E)
val AgWarning = Color(0xFFF59E0B)
val AgDanger = Color(0xFFEF4444)

val ServerColorOptions = listOf(
    "#6366F1", "#38BDF8", "#22C55E", "#F59E0B",
    "#EF4444", "#EC4899", "#A855F7", "#14B8A6"
)

fun parseHexColor(hex: String?, fallback: Color = AgAccent): Color {
    if (hex.isNullOrBlank()) return fallback
    return runCatching {
        val cleaned = hex.trim().removePrefix("#")
        when (cleaned.length) {
            6 -> Color(("FF$cleaned").toLong(16))
            8 -> Color(cleaned.toLong(16))
            else -> fallback
        }
    }.getOrDefault(fallback)
}

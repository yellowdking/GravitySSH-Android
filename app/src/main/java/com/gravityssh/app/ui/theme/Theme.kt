package com.gravityssh.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AntigravityDarkColorScheme = darkColorScheme(
    primary = AgAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF25285A),
    onPrimaryContainer = AgAccentLight,
    secondary = AgCyan,
    onSecondary = AgBg,
    secondaryContainer = Color(0xFF132C3D),
    onSecondaryContainer = AgCyan,
    tertiary = AgViolet,
    onTertiary = Color.White,
    background = AgBg,
    onBackground = AgText,
    surface = AgSurface,
    onSurface = AgText,
    surfaceVariant = AgPanel,
    onSurfaceVariant = AgMuted,
    surfaceContainerLowest = AgBg,
    surfaceContainerLow = AgSurface,
    surfaceContainer = AgPanel,
    surfaceContainerHigh = AgElevated,
    surfaceContainerHighest = AgHover,
    outline = AgBorder,
    outlineVariant = AgBorderLight,
    error = AgDanger,
    onError = Color.White,
    errorContainer = Color(0xFF3A161A),
    onErrorContainer = Color(0xFFFCA5A5)
)

@Composable
fun GravitySshTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AntigravityDarkColorScheme,
        typography = GravityTypography,
        content = content
    )
}

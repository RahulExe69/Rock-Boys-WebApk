package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val GamingColorScheme = darkColorScheme(
    primary = ToxicGreen,
    secondary = CyberCyan,
    tertiary = LaserRed,
    background = CyberBlack,
    surface = CyberDark,
    onPrimary = OnPrimaryDark,
    onSecondary = CyberBlack,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    surfaceVariant = CyberGray,
    onSurfaceVariant = TextSecondary,
    outline = CyberLine
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = GamingColorScheme,
        typography = Typography,
        content = content
    )
}

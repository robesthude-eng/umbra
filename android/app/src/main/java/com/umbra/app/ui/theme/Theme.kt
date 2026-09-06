package com.umbra.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val UmbraDark = darkColorScheme(
    primary = Color(0xFF7C6CF0),          // фиолетовый акцент
    onPrimary = Color.White,
    secondary = Color(0xFF3DD6A3),        // зелёный (индикатор шифрования)
    background = Color(0xFF0A0A12),
    onBackground = Color(0xFFE8E8F0),
    surface = Color(0xFF14141F),
    onSurface = Color(0xFFE8E8F0),
    surfaceVariant = Color(0xFF1E1E2C),
    onSurfaceVariant = Color(0xFFA0A0B0),
    error = Color(0xFFE05A6A),
)

@Composable
fun UmbraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = UmbraDark, content = content)
}

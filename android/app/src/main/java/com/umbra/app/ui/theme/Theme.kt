package com.umbra.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * «Liquid Glass» для Umbra: неоново-голубой/мятный акцент, без фиолетового,
 * стеклянные полупрозрачные поверхности и мягкие градиенты.
 */
object UmbraColors {
    // Акцент (тёплый небесно-голубой)
    val Aqua = Color(0xFF57C7FF)
    val Mint = Color(0xFF4FE0C0)
    val Deep = Color(0xFF0E2437)       // глубокий сине-чёрный (тёмный фон)
    val Night = Color(0xFF0A1A28)
    val GlassDark = Color(0xE6173047)  // «стекло» на тёмном
    val Ice = Color(0xFFE8F6FF)        // светлый текст
    val Fog = Color(0xFFA9C6DB)        // вторичный текст
    val Mist = Color(0xFF3B5E79)       // рамки на тёмном
    val Danger = Color(0xFFFF6B7A)

    // Светлая тема
    val Day = Color(0xFFF4FAFF)
    val GlassLight = Color(0xB3FFFFFF)
    val Ink = Color(0xFF10324B)
    val SkyMist = Color(0xFFBDDCF0)

    val headerGradient = Brush.linearGradient(listOf(Aqua, Color(0xFF2EA9FF), Mint))
}

val UmbraLightColors = lightColorScheme(
    primary = Color(0xFF1E96E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9E8FF),
    onPrimaryContainer = UmbraColors.Ink,
    secondary = UmbraColors.Mint,
    onSecondary = Color(0xFF00382E),
    background = UmbraColors.Day,
    onBackground = UmbraColors.Ink,
    surface = UmbraColors.GlassLight,
    onSurface = UmbraColors.Ink,
    surfaceVariant = Color(0xFFE2EFFA),
    onSurfaceVariant = Color(0xFF4A6B84),
    outline = Color(0xFFB7D0E2),
    error = UmbraColors.Danger,
    onError = Color.White,
)

val UmbraDarkColors = darkColorScheme(
    primary = UmbraColors.Aqua,
    onPrimary = Color(0xFF00344D),
    primaryContainer = Color(0xFF1B4E6B),
    onPrimaryContainer = UmbraColors.Ice,
    secondary = UmbraColors.Mint,
    onSecondary = Color(0xFF00382E),
    background = UmbraColors.Deep,
    onBackground = UmbraColors.Ice,
    surface = UmbraColors.GlassDark,
    onSurface = UmbraColors.Ice,
    surfaceVariant = UmbraColors.Mist,
    onSurfaceVariant = UmbraColors.Fog,
    outline = UmbraColors.Mist,
    error = UmbraColors.Danger,
    onError = Color.White,
)

@Composable
fun UmbraTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) UmbraDarkColors else UmbraLightColors,
        content = content,
    )
}

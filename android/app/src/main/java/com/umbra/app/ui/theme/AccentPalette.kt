package com.umbra.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.umbra.app.data.session.AccentColor

/** Paired accents keep controls readable in both light and dark schemes. */
internal fun accentSwatch(accent: AccentColor, dark: Boolean): Color? = when (accent) {
    AccentColor.DEFAULT -> null
    AccentColor.BLUE -> if (dark) Color(0xFF9BCEFF) else Color(0xFF1765BF)
    AccentColor.TEAL -> if (dark) Color(0xFF62DBC1) else Color(0xFF006F62)
    AccentColor.GREEN -> if (dark) Color(0xFF91D894) else Color(0xFF286B31)
    AccentColor.AMBER -> if (dark) Color(0xFFFFC47D) else Color(0xFF8A5200)
    AccentColor.ROSE -> if (dark) Color(0xFFFFAFCA) else Color(0xFFAB245A)
}

internal fun accentColorScheme(base: ColorScheme, accent: AccentColor, dark: Boolean): ColorScheme {
    val primary = accentSwatch(accent, dark) ?: return base
    val secondary = lerp(primary, if (dark) Color.White else Color.Black, 0.12f)
    val container = lerp(base.surface, primary, if (dark) 0.20f else 0.12f)
    val onPrimary = if (dark) Color(0xFF102026) else Color.White
    return base.copy(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = container, onPrimaryContainer = base.onSurface,
        secondary = secondary, onSecondary = onPrimary,
        secondaryContainer = container, onSecondaryContainer = base.onSurface,
        tertiary = primary, onTertiary = onPrimary,
        tertiaryContainer = container, onTertiaryContainer = base.onSurface,
        inversePrimary = accentSwatch(accent, !dark) ?: base.inversePrimary,
        surfaceTint = primary,
    )
}

internal fun accentChatColors(base: UmbraChatColors, scheme: ColorScheme, dark: Boolean, smokedGlass: Boolean): UmbraChatColors {
    val bubble = if (smokedGlass) scheme.primaryContainer
        else if (dark) lerp(Color.Black, scheme.primary, 0.34f) else scheme.primary
    val end = if (smokedGlass) lerp(bubble, scheme.surface, 0.12f) else lerp(bubble, Color.Black, 0.10f)
    val text = if (smokedGlass) scheme.onPrimaryContainer else Color.White
    return base.copy(
        outgoing = listOf(bubble, end), onOutgoing = text, outgoingMeta = text,
        accent = scheme.primary,
        glowTop = if (smokedGlass) Color.Transparent else scheme.primary.copy(alpha = if (dark) 0.12f else 0.06f),
        glowBottom = if (smokedGlass) Color.Transparent else scheme.secondary.copy(alpha = if (dark) 0.08f else 0.04f),
    )
}

internal fun accentAlienTokens(tokens: AlienTokens, scheme: ColorScheme): AlienTokens = tokens.copy(
    primary = scheme.primary,
    secondary = scheme.secondary,
    tertiary = scheme.tertiary,
    hud = if (tokens.dark) lerp(scheme.primary, Color.White, 0.65f) else scheme.onSurface,
)

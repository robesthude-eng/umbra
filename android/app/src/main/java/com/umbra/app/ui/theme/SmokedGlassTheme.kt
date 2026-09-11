package com.umbra.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** Палитра самостоятельного стиля; светлая тема использует более тёмную бирюзу. */
internal fun smokedGlassColorScheme(dark: Boolean): ColorScheme = if (dark) UmbraDarkColors.copy(
    primary = Color(0xFF59E0E6), onPrimary = Color(0xFF00363B),
    primaryContainer = Color(0xFF204D57), onPrimaryContainer = Color(0xFFD7FCFF),
    secondary = Color(0xFF9ED3DD), onSecondary = Color(0xFF13353C),
    secondaryContainer = Color(0xFF29444E), onSecondaryContainer = Color(0xFFDDF4F7),
    background = Color(0xFF111B23), onBackground = Color(0xFFF0F5F7),
    surface = Color(0xFF26343E), onSurface = Color(0xFFF0F5F7),
    surfaceVariant = Color(0xFF31434D), onSurfaceVariant = Color(0xFFC0D0D8),
    surfaceContainer = Color(0xFF22323C), surfaceContainerHigh = Color(0xFF30434D),
    outline = Color(0xFF91A9B6), outlineVariant = Color(0xFF536A78),
) else UmbraLightColors.copy(
    primary = Color(0xFF006B75), onPrimary = Color.White,
    primaryContainer = Color(0xFFC1EDF0), onPrimaryContainer = Color(0xFF003A42),
    secondary = Color(0xFF386571), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6EAF0), onSecondaryContainer = Color(0xFF153A43),
    background = Color(0xFFE9F0F3), onBackground = Color(0xFF192E38),
    surface = Color(0xFFF3F8FA), onSurface = Color(0xFF192E38),
    surfaceVariant = Color(0xFFDAE7EC), onSurfaceVariant = Color(0xFF455E69),
    surfaceContainer = Color(0xFFE1EBEF), surfaceContainerHigh = Color(0xFFD6E5EB),
    outline = Color(0xFF69828E), outlineVariant = Color(0xFFADC1CB),
)

internal fun smokedGlassVisualTokens(dark: Boolean) = UmbraVisualTokens(
    backdrop = if (dark) listOf(Color(0xFF17232D), Color(0xFF101920), Color(0xFF20313B))
        else listOf(Color(0xFFE1EAF0), Color(0xFFF0F5F7), Color(0xFFD5E6EA)),
    glass = if (dark) Color(0xCF263640) else Color(0xDFF5FAFC),
    glassStrong = if (dark) Color(0xEB273842) else Color(0xF2F4FAFC),
    glassBorder = if (dark) Color(0x709AB9C7) else Color(0x806B929E),
    auraPrimary = if (dark) Color(0xFF59E0E6) else Color(0xFF006B75),
    auraSecondary = if (dark) Color(0xFFAAC4D2) else Color(0xFF597D8C),
    success = if (dark) Color(0xFF74D9B3) else Color(0xFF176F54),
    warning = if (dark) Color(0xFFFFD08B) else Color(0xFF895815),
)

internal fun smokedGlassChatColors(dark: Boolean) = UmbraChatColors(
    screen = smokedGlassVisualTokens(dark).backdrop,
    glowTop = Color.Transparent, glowBottom = Color.Transparent,
    outgoing = if (dark) listOf(Color(0xE02A5A65), Color(0xE0204652))
        else listOf(Color(0xF2C5E9EC), Color(0xF2D0E8ED)),
    onOutgoing = if (dark) Color(0xFFF1FCFE) else Color(0xFF143F48),
    outgoingMeta = if (dark) Color(0xFFD0ECF0) else Color(0xFF365C66),
    incoming = if (dark) Color(0xDF2B3B46) else Color(0xEDF7FBFD),
    onIncoming = if (dark) Color(0xFFF0F5F7) else Color(0xFF192E38),
    incomingMeta = if (dark) Color(0xFFC0D0D8) else Color(0xFF455E69),
    bar = if (dark) Color(0xEB273842) else Color(0xF2F4FAFC),
    field = if (dark) Color(0xB8243540) else Color(0xBFDAE8ED),
    accent = if (dark) Color(0xFF59E0E6) else Color(0xFF006B75),
)

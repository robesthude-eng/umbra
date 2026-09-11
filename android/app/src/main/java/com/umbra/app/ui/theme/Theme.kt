package com.umbra.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Оформление Umbra.
 *
 * Палитра осталась узнаваемой (небесно-голубой и мятный акцент), но набор
 * токенов переписан под современный мессенджер: глубокий «чернильный» фон
 * с мягким свечением, градиентные исходящие пузыри, приглушённые панели
 * поверх фона и единый набор скруглений.
 *
 * Цвета чата вынесены в [UmbraChatColors] и раздаются через
 * [LocalUmbraChatColors]: экран чата берёт их напрямую, поэтому пузыри
 * и панели используют согласованную фирменную или динамическую палитру.
 */
object UmbraColors {
    // Акценты
    val Aqua = Color(0xFF9BCEFF)
    val Mint = Color(0xFF46E0C4)
    val Sky = Color(0xFF2E86F0)

    // Тёмная схема
    val Deep = Color(0xFF0D1521)
    val Night = Color(0xFF172334)
    val GlassDark = Color(0xFF202F42)
    val Ice = Color(0xFFE9F0F8)
    val Fog = Color(0xFFA9B8CB)
    val Mist = Color(0xFF2C3C51)
    val Danger = Color(0xFFFF6B7A)

    // Светлая схема
    val Day = Color(0xFFF3F6FA)
    val GlassLight = Color(0xFFFFFFFF)
    val Ink = Color(0xFF17283C)
    val SkyMist = Color(0xFFDDE5EF)

    /** Градиент аватаров и акцентных элементов. */
    val headerGradient = Brush.linearGradient(listOf(Aqua, Sky, Mint))
}

/** Цвета экрана чата: фон, пузыри, панели. */
@Immutable
data class UmbraChatColors(
    /** Вертикальный градиент фона переписки. */
    val screen: List<Color>,
    /** Мягкие световые пятна поверх фона. */
    val glowTop: Color,
    val glowBottom: Color,
    /** Градиент исходящего пузыря. */
    val outgoing: List<Color>,
    val onOutgoing: Color,
    val outgoingMeta: Color,
    /** Входящий пузырь. */
    val incoming: Color,
    val onIncoming: Color,
    val incomingMeta: Color,
    /** Панели: шапка, поле ввода, плашки. */
    val bar: Color,
    val field: Color,
    val accent: Color,
)

val UmbraChatDark = UmbraChatColors(
    screen = listOf(UmbraColors.Deep, Color(0xFF101C2B)),
    glowTop = Color(0x0921A0E8),
    glowBottom = Color(0x061FBFA8),
    outgoing = listOf(Color(0xFF245893), Color(0xFF235467)),
    onOutgoing = Color(0xFFF5F9FF),
    outgoingMeta = Color(0xFFE0EEFC),
    incoming = UmbraColors.Night,
    onIncoming = UmbraColors.Ice,
    incomingMeta = UmbraColors.Fog,
    bar = UmbraColors.Night,
    field = UmbraColors.GlassDark,
    accent = UmbraColors.Aqua,
)

val UmbraChatLight = UmbraChatColors(
    screen = listOf(UmbraColors.Day, Color(0xFFEDF3F9)),
    glowTop = Color(0x064FA8F5),
    glowBottom = Color(0x0434D3BC),
    outgoing = listOf(Color(0xFF1668D8), Color(0xFF12667D)),
    onOutgoing = Color(0xFFFFFFFF),
    outgoingMeta = Color(0xFFEFF7FF),
    incoming = Color(0xFFFFFFFF),
    onIncoming = UmbraColors.Ink,
    incomingMeta = Color(0xFF55677D),
    bar = Color.White,
    field = Color(0xFFE8EEF5),
    accent = Color(0xFF1765BF),
)

val LocalUmbraChatColors = staticCompositionLocalOf { UmbraChatDark }
val LocalUmbraReducedMotion = staticCompositionLocalOf { false }
val LocalUmbraMessageTextStyle = staticCompositionLocalOf { TextStyle(fontSize = 16.sp, lineHeight = 22.sp) }

val UmbraLightColors = lightColorScheme(
    primary = Color(0xFF1668D8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCECFF),
    onPrimaryContainer = Color(0xFF124F92),
    secondary = Color(0xFF08695F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC7F2EA),
    onSecondaryContainer = Color(0xFF00382E),
    background = UmbraColors.Day,
    onBackground = UmbraColors.Ink,
    surface = UmbraColors.GlassLight,
    onSurface = UmbraColors.Ink,
    surfaceVariant = Color(0xFFE8EEF5),
    onSurfaceVariant = Color(0xFF55677D),
    surfaceContainer = Color(0xFFEDF2F8),
    surfaceContainerHigh = Color(0xFFE8EEF5),
    outline = Color(0xFF718297),
    outlineVariant = UmbraColors.SkyMist,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

val UmbraDarkColors = darkColorScheme(
    primary = UmbraColors.Aqua,
    onPrimary = Color(0xFF002E46),
    primaryContainer = Color(0xFF254568),
    onPrimaryContainer = Color(0xFFDEEDFF),
    secondary = UmbraColors.Mint,
    onSecondary = Color(0xFF00382E),
    secondaryContainer = Color(0xFF12564A),
    onSecondaryContainer = Color(0xFFBFF6EB),
    background = UmbraColors.Deep,
    onBackground = UmbraColors.Ice,
    surface = UmbraColors.Night,
    onSurface = UmbraColors.Ice,
    surfaceVariant = UmbraColors.GlassDark,
    onSurfaceVariant = UmbraColors.Fog,
    surfaceContainer = Color(0xFF1A293B),
    surfaceContainerHigh = UmbraColors.GlassDark,
    outline = Color(0xFF75879E),
    outlineVariant = UmbraColors.Mist,
    error = UmbraColors.Danger,
    onError = Color(0xFF3A0009),
    errorContainer = Color(0xFF6B2530),
    onErrorContainer = Color(0xFFFFDADF),
)

private val Base = Typography()

/** Типографика: плотнее межстрочный интервал, спокойные заголовки. */
val UmbraTypography = Typography(
    headlineMedium = Base.headlineMedium.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.6).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    bodyLarge = Base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    bodyMedium = Base.bodyMedium.copy(lineHeight = 20.sp),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.Medium),
    labelSmall = Base.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp),
)

/** Скругления: крупные, как в современных мессенджерах. */
val UmbraShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/**
 * @param dynamicColor подхватить палитру обоев системы (Android 12+).
 *   По умолчанию выключено: у Umbra собственный фирменный цвет.
 */
@Composable
fun UmbraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    messageTextSize: Int = 16,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> UmbraDarkColors
        else -> UmbraLightColors
    }
    val chat = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicChatColors(scheme)
        else if (darkTheme) UmbraChatDark else UmbraChatLight
    val size = messageTextSize.coerceIn(16, 22)
    CompositionLocalProvider(
        LocalUmbraChatColors provides chat,
        LocalUmbraReducedMotion provides reduceMotion,
        LocalUmbraMessageTextStyle provides UmbraTypography.bodyLarge.copy(fontSize = size.sp, lineHeight = (size + 6).sp),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = UmbraTypography,
            shapes = UmbraShapes,
            content = content,
        )
    }
}

/** Dynamic colors must also reach bubbles and their text, not just Material controls. */
private fun dynamicChatColors(scheme: ColorScheme) = UmbraChatColors(
    screen = listOf(scheme.background, scheme.surfaceContainer),
    glowTop = Color.Transparent,
    glowBottom = Color.Transparent,
    outgoing = listOf(scheme.primary, scheme.primary),
    onOutgoing = scheme.onPrimary,
    outgoingMeta = scheme.onPrimary,
    incoming = scheme.surface,
    onIncoming = scheme.onSurface,
    incomingMeta = scheme.onSurfaceVariant,
    bar = scheme.surface,
    field = scheme.surfaceContainerHigh,
    accent = scheme.primary,
)

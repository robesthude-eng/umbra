package com.umbra.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
 * и панели не зависят от того, включена ли динамическая палитра системы.
 */
object UmbraColors {
    // Акценты
    val Aqua = Color(0xFF5CC8FF)
    val Mint = Color(0xFF46E0C4)
    val Sky = Color(0xFF2E86F0)

    // Тёмная схема
    val Deep = Color(0xFF0A1220)       // базовый фон
    val Night = Color(0xFF0D1A2B)
    val GlassDark = Color(0xFF142438)  // «стекло» на тёмном
    val Ice = Color(0xFFE9F2FB)        // основной текст
    val Fog = Color(0xFF93AEC6)        // вторичный текст
    val Mist = Color(0xFF24374F)       // разделители и рамки
    val Danger = Color(0xFFFF6B7A)

    // Светлая схема
    val Day = Color(0xFFF4F8FD)
    val GlassLight = Color(0xFFFFFFFF)
    val Ink = Color(0xFF0F2A43)
    val SkyMist = Color(0xFFD6E6F5)

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
    screen = listOf(Color(0xFF091120), Color(0xFF0C1A2C), Color(0xFF091422)),
    glowTop = Color(0x3321A0E8),
    glowBottom = Color(0x2A1FBFA8),
    outgoing = listOf(Color(0xFF2277EC), Color(0xFF1FA9C6)),
    onOutgoing = Color(0xFFFFFFFF),
    outgoingMeta = Color(0xCCEAF6FF),
    incoming = Color(0xFF15263A),
    onIncoming = UmbraColors.Ice,
    incomingMeta = Color(0xFF8AA6C0),
    bar = Color(0xF20B1626),
    field = Color(0xFF16283E),
    accent = UmbraColors.Aqua,
)

val UmbraChatLight = UmbraChatColors(
    screen = listOf(Color(0xFFF7FAFE), Color(0xFFEDF4FC), Color(0xFFF4F9FF)),
    glowTop = Color(0x2A4FA8F5),
    glowBottom = Color(0x2434D3BC),
    outgoing = listOf(Color(0xFF2F80ED), Color(0xFF21B6AE)),
    onOutgoing = Color(0xFFFFFFFF),
    outgoingMeta = Color(0xE6FFFFFF),
    incoming = Color(0xFFFFFFFF),
    onIncoming = UmbraColors.Ink,
    incomingMeta = Color(0xFF6C8AA6),
    bar = Color(0xF2FFFFFF),
    field = Color(0xFFEDF3FA),
    accent = UmbraColors.Sky,
)

val LocalUmbraChatColors = staticCompositionLocalOf { UmbraChatDark }

val UmbraLightColors = lightColorScheme(
    primary = Color(0xFF1668D8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E7FF),
    onPrimaryContainer = Color(0xFF06294A),
    secondary = Color(0xFF10877A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC7F2EA),
    onSecondaryContainer = Color(0xFF00382E),
    background = UmbraColors.Day,
    onBackground = UmbraColors.Ink,
    surface = UmbraColors.GlassLight,
    onSurface = UmbraColors.Ink,
    surfaceVariant = Color(0xFFE4EEF9),
    onSurfaceVariant = Color(0xFF4E6B85),
    surfaceContainer = Color(0xFFEFF5FC),
    surfaceContainerHigh = Color(0xFFE8F0F9),
    outline = Color(0xFFB5CCE0),
    outlineVariant = UmbraColors.SkyMist,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

val UmbraDarkColors = darkColorScheme(
    primary = UmbraColors.Aqua,
    onPrimary = Color(0xFF002E46),
    primaryContainer = Color(0xFF17466B),
    onPrimaryContainer = UmbraColors.Ice,
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
    surfaceContainer = Color(0xFF111F31),
    surfaceContainerHigh = Color(0xFF17273B),
    outline = UmbraColors.Mist,
    outlineVariant = Color(0xFF1C2C40),
    error = UmbraColors.Danger,
    onError = Color(0xFF3A0009),
    errorContainer = Color(0xFF6B2530),
    onErrorContainer = Color(0xFFFFDADF),
)

private val Base = Typography()

/** Типографика: плотнее межстрочный интервал, спокойные заголовки. */
val UmbraTypography = Typography(
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    bodyLarge = Base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    bodyMedium = Base.bodyMedium.copy(lineHeight = 20.sp),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = Base.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.2.sp),
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
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> UmbraDarkColors
        else -> UmbraLightColors
    }
    CompositionLocalProvider(LocalUmbraChatColors provides if (darkTheme) UmbraChatDark else UmbraChatLight) {
        MaterialTheme(
            colorScheme = scheme,
            typography = UmbraTypography,
            shapes = UmbraShapes,
            content = content,
        )
    }
}

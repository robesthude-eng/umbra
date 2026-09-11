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
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.umbra.app.data.session.AlienIntensity

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
val LocalUmbraAlienMode = staticCompositionLocalOf { false }

/**
 * Токены Alien Interface: сила режима и вся его палитра в одном месте.
 * Экраны не держат своих «чужих» цветов и alpha — только эти значения.
 */
@Immutable
data class AlienTokens(
    val intensity: AlienIntensity = AlienIntensity.OFF,
    val dark: Boolean = true,
    val primary: Color = Color(0xFF72FFF1),
    val secondary: Color = Color(0xFFE957FF),
    val tertiary: Color = Color(0xFF7C8BFF),
    /** Цвет HUD-подписей: выбран так, чтобы читаться на стекле любой темы. */
    val hud: Color = Color(0xFFBFFFF8),
    val starCount: Int = 0,
    val starAlpha: Float = 0f,
    val ringAlpha: Float = 0f,
    val gridAlpha: Float = 0f,
    val nebulaAlpha: Float = 0f,
    val scanlineAlpha: Float = 0f,
    val glowAlpha: Float = 0f,
    val cometAlpha: Float = 0f,
    val vignetteAlpha: Float = 0f,
) {
    val enabled: Boolean get() = intensity != AlienIntensity.OFF
    val full: Boolean get() = intensity == AlienIntensity.FULL
}

val LocalUmbraAlienTokens = staticCompositionLocalOf { AlienTokens() }

/**
 * Палитра и сила эффектов Alien-режима.
 * Светлая тема получает затемнённые акценты: контраст подписей не должен
 * зависеть ни от прозрачности стекла, ни от яркости фона.
 */
fun alienTokens(intensity: AlienIntensity, dark: Boolean): AlienTokens = when (intensity) {
    AlienIntensity.OFF -> AlienTokens(intensity = AlienIntensity.OFF, dark = dark)
    AlienIntensity.CALM -> AlienTokens(
        intensity = AlienIntensity.CALM,
        dark = dark,
        primary = if (dark) Color(0xFF72FFF1) else Color(0xFF00707F),
        secondary = if (dark) Color(0xFFC77BFF) else Color(0xFF7A34B8),
        tertiary = if (dark) Color(0xFF7C8BFF) else Color(0xFF3B4FB0),
        hud = if (dark) Color(0xFFBFFFF8) else Color(0xFF063A42),
        starCount = 40,
        starAlpha = if (dark) 0.18f else 0.10f,
        ringAlpha = if (dark) 0.10f else 0.07f,
        gridAlpha = if (dark) 0.05f else 0.04f,
        nebulaAlpha = if (dark) 0.10f else 0.06f,
        scanlineAlpha = if (dark) 0.025f else 0.020f,
        glowAlpha = if (dark) 0.10f else 0.07f,
        cometAlpha = 0f,
        vignetteAlpha = if (dark) 0.10f else 0.03f,
    )
    AlienIntensity.FULL -> AlienTokens(
        intensity = AlienIntensity.FULL,
        dark = dark,
        primary = if (dark) Color(0xFF72FFF1) else Color(0xFF005F70),
        secondary = if (dark) Color(0xFFE957FF) else Color(0xFF7A1FB0),
        tertiary = if (dark) Color(0xFF6C7BFF) else Color(0xFF2F3FA8),
        hud = if (dark) Color(0xFFBFFFF8) else Color(0xFF04303A),
        starCount = 88,
        starAlpha = if (dark) 0.32f else 0.16f,
        ringAlpha = if (dark) 0.18f else 0.12f,
        gridAlpha = if (dark) 0.09f else 0.06f,
        nebulaAlpha = if (dark) 0.17f else 0.10f,
        scanlineAlpha = if (dark) 0.050f else 0.035f,
        glowAlpha = if (dark) 0.18f else 0.12f,
        cometAlpha = if (dark) 0.55f else 0.30f,
        vignetteAlpha = if (dark) 0.16f else 0.05f,
    )
}

val LocalUmbraMessageTextStyle = staticCompositionLocalOf { TextStyle(fontSize = 16.sp, lineHeight = 22.sp) }

/** Единые параметры движения вместо случайных duration/spring по экранам. */
@Immutable
data class UmbraMotionTokens(
    val quickMs: Int = 120,
    val standardMs: Int = 220,
    val expressiveMs: Int = 360,
    val dismissVelocity: Float = 1_600f,
    val springStiffness: Float = 500f,
    val springDamping: Float = 0.78f,
)

val LocalUmbraMotion = staticCompositionLocalOf { UmbraMotionTokens() }

/** Семантические токены Future UI: экраны не хранят собственные случайные alpha. */
@Immutable
data class UmbraVisualTokens(
    val backdrop: List<Color>,
    val glass: Color,
    val glassStrong: Color,
    val glassBorder: Color,
    val auraPrimary: Color,
    val auraSecondary: Color,
    val success: Color,
    val warning: Color,
)

val LocalUmbraVisuals = staticCompositionLocalOf {
    UmbraVisualTokens(
        backdrop = listOf(UmbraColors.Deep, Color(0xFF111D2D)),
        glass = Color(0xE6172334),
        glassStrong = Color(0xF21B2A3D),
        glassBorder = Color(0x267EC9FF),
        auraPrimary = Color(0xFF6574FF),
        auraSecondary = UmbraColors.Mint,
        success = Color(0xFF57E0B5),
        warning = Color(0xFFFFC66D),
    )
}

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
    alienInterface: Boolean = false,
    alienIntensity: AlienIntensity? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> UmbraDarkColors
        else -> UmbraLightColors
    }
    val baseChat = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicChatColors(scheme)
        else if (darkTheme) UmbraChatDark else UmbraChatLight
    // Прежние вызовы с одним флагом продолжают работать и дают полную силу.
    val intensity = alienIntensity ?: if (alienInterface) AlienIntensity.FULL else AlienIntensity.OFF
    val tokens = remember(intensity, darkTheme) { alienTokens(intensity, darkTheme) }
    val chat = if (tokens.enabled) alienChatColors(baseChat, tokens, darkTheme) else baseChat
    val visuals = futureVisuals(scheme, tokens)
    val size = messageTextSize.coerceIn(16, 22)
    CompositionLocalProvider(
        LocalUmbraChatColors provides chat,
        LocalUmbraVisuals provides visuals,
        LocalUmbraMotion provides UmbraMotionTokens(),
        LocalUmbraReducedMotion provides reduceMotion,
        LocalUmbraAlienMode provides tokens.enabled,
        LocalUmbraAlienTokens provides tokens,
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

private fun futureVisuals(scheme: ColorScheme, tokens: AlienTokens): UmbraVisualTokens {
    val dark = scheme.background.luminance() < 0.45f
    val alien = tokens.enabled
    val full = tokens.full
    return UmbraVisualTokens(
        backdrop = if (alien && dark) listOf(Color(0xFF010309), Color(0xFF061224), Color(0xFF120823), Color(0xFF03141B))
            else if (alien) listOf(Color(0xFFF4FBFF), Color(0xFFF2F3FF), Color(0xFFFFF3FD))
            else if (dark) listOf(Color(0xFF090F1A), Color(0xFF101C2C), Color(0xFF0C1421))
            else listOf(Color(0xFFF6F8FC), Color(0xFFEDF4FA), Color(0xFFF4F1FB)),
        glass = if (alien && dark) Color(0xB80A1628) else if (alien) Color(0xCCF8FCFF) else if (dark) Color(0xD9162335) else Color(0xE8FFFFFF),
        glassStrong = if (alien && dark) Color(0xE80B1930) else if (alien) Color(0xECFFFFFF) else if (dark) Color(0xF21B2A3D) else Color(0xF8FFFFFF),
        glassBorder = if (alien) tokens.primary.copy(alpha = if (full) 0.50f else 0.30f)
            else if (dark) Color(0x307EC9FF) else Color(0x50698AAF),
        auraPrimary = if (alien) tokens.primary else scheme.primary,
        auraSecondary = if (alien) tokens.secondary else scheme.secondary,
        success = if (dark) Color(0xFF57E0B5) else Color(0xFF087D68),
        warning = if (dark) Color(0xFFFFC66D) else Color(0xFF925900),
    )
}

/**
 * Стабильная персональная палитра диалога без сохранения дополнительных данных.
 * В Alien-режиме оттенки берутся из его токенов и примешиваются слабее,
 * иначе случайные акценты размывают «чужую» гамму.
 */
@Composable
fun rememberUmbraChatColors(seed: String): UmbraChatColors {
    val base = LocalUmbraChatColors.current
    val alien = LocalUmbraAlienTokens.current
    return remember(seed, base, alien) {
        val accents = if (alien.enabled) listOf(alien.primary, alien.secondary, alien.tertiary)
            else listOf(
                Color(0xFF7587FF), Color(0xFF30C9B0), Color(0xFFB879FF),
                Color(0xFF3DA7FF), Color(0xFFFF7CA8), Color(0xFFFFA95E),
            )
        val accent = accents[(seed.hashCode() and Int.MAX_VALUE) % accents.size]
        val dark = base.screen.first().luminance() < 0.45f
        val mix = if (alien.enabled) 0.5f else 1f
        base.copy(
            screen = if (dark) listOf(lerp(base.screen.first(), accent, 0.055f * mix), base.screen.last())
                else listOf(lerp(base.screen.first(), accent, 0.035f * mix), base.screen.last()),
            glowTop = accent.copy(alpha = if (dark) 0.12f else 0.07f),
            glowBottom = base.accent.copy(alpha = if (dark) 0.08f else 0.045f),
            outgoing = listOf(
                lerp(base.outgoing.first(), accent, 0.44f * mix),
                lerp(base.outgoing.last(), accent, 0.22f * mix),
            ),
            bar = lerp(base.bar, accent, (if (dark) 0.055f else 0.025f) * mix).copy(alpha = 0.96f),
            field = lerp(base.field, accent, (if (dark) 0.075f else 0.035f) * mix),
            accent = accent,
        )
    }
}

/** Палитра переписки в Alien-режиме: холодный космос и голографические пузыри. */
private fun alienChatColors(base: UmbraChatColors, tokens: AlienTokens, dark: Boolean): UmbraChatColors {
    val full = tokens.full
    return base.copy(
        screen = if (dark) listOf(Color(0xFF02050B), Color(0xFF081226), Color(0xFF100A26))
            else listOf(Color(0xFFF4FAFF), Color(0xFFF2F3FF), Color(0xFFF9F2FF)),
        glowTop = tokens.primary.copy(alpha = if (full) 0.20f else 0.12f),
        glowBottom = tokens.secondary.copy(alpha = if (full) 0.18f else 0.10f),
        outgoing = if (dark) listOf(Color(0xFF4B38FF), Color(0xFF008DA8), Color(0xFF00A98F))
            else listOf(Color(0xFF3A2AD2), Color(0xFF00707F), Color(0xFF0A7A66)),
        onOutgoing = Color(0xFFF2FFFD),
        outgoingMeta = if (dark) Color(0xFFCFF6FF) else Color(0xFFE8FBFF),
        incoming = if (dark) Color(0xE60F1B33) else Color(0xF7FFFFFF),
        onIncoming = if (dark) Color(0xFFE6FBFF) else Color(0xFF0B2230),
        incomingMeta = if (dark) Color(0xFF93BACB) else Color(0xFF4A6472),
        bar = if (dark) Color(0xF2071021) else Color(0xF7F7FBFF),
        field = if (dark) Color(0xFF10203A) else Color(0xFFE7F1FA),
        accent = tokens.primary,
    )
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

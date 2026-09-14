package com.umbra.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.umbra.app.data.session.InterfaceStyle

/**
 * Различия тем не только в цвете.
 *
 * Каждый [InterfaceStyle] задаёт свою гарнитуру, ритм текста, форму
 * карточек и полей, а также отдельный дизайн кнопок:
 *
 * - [InterfaceStyle.STANDARD] — гротеск, мягкие скругления, плотная заливка кнопки;
 * - [InterfaceStyle.SMOKED_GLASS] — антиква (serif) в заголовках, широкий трекинг,
 *   кнопки-таблетки без тени;
 * - [InterfaceStyle.ALIEN] — моноширинный терминальный шрифт, срезанные углы,
 *   кнопки-рамки HUD с разряженными подписями.
 */

private val Base = Typography()

/** Стандарт: плотный гротеск, спокойные заголовки. */
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

/** Дымчатое стекло: антиква в заголовках, воздушный основной текст. */
val UmbraGlassTypography = Typography(
    displaySmall = Base.displaySmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal),
    headlineLarge = Base.headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, letterSpacing = (-0.4).sp),
    headlineMedium = Base.headlineMedium.copy(fontFamily = FontFamily.Serif, fontSize = 29.sp, lineHeight = 38.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.2).sp),
    headlineSmall = Base.headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal),
    titleLarge = Base.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    titleMedium = Base.titleMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    titleSmall = Base.titleSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
    bodyLarge = Base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 25.sp, fontWeight = FontWeight.Light, letterSpacing = 0.3.sp),
    bodyMedium = Base.bodyMedium.copy(lineHeight = 22.sp, fontWeight = FontWeight.Light, letterSpacing = 0.25.sp),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
    labelMedium = Base.labelMedium.copy(letterSpacing = 0.6.sp),
    labelSmall = Base.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.7.sp),
)

/** Alien: терминальный моноширинный набор с разряженными подписями. */
val UmbraAlienTypography = Typography(
    displaySmall = Base.displaySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light),
    headlineLarge = Base.headlineLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light, letterSpacing = 1.0.sp),
    headlineMedium = Base.headlineMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.Light, letterSpacing = 1.2.sp),
    headlineSmall = Base.headlineSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light, letterSpacing = 1.0.sp),
    titleLarge = Base.titleLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, letterSpacing = 0.8.sp),
    titleMedium = Base.titleMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, letterSpacing = 0.6.sp),
    titleSmall = Base.titleSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.6.sp),
    bodyLarge = Base.bodyLarge.copy(fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 23.sp, letterSpacing = 0.2.sp),
    bodyMedium = Base.bodyMedium.copy(fontFamily = FontFamily.Monospace, lineHeight = 21.sp, letterSpacing = 0.2.sp),
    bodySmall = Base.bodySmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.2.sp),
    labelLarge = Base.labelLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, letterSpacing = 1.6.sp),
    labelMedium = Base.labelMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp),
    labelSmall = Base.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 1.4.sp),
)

fun umbraTypography(style: InterfaceStyle): Typography = when (style) {
    InterfaceStyle.STANDARD -> UmbraTypography
    InterfaceStyle.SMOKED_GLASS -> UmbraGlassTypography
    InterfaceStyle.ALIEN -> UmbraAlienTypography
}

/** Стандарт: крупные скругления, как в современных мессенджерах. */
val UmbraShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/** Стекло: почти капсульные формы. */
val UmbraGlassShapes = Shapes(
    extraSmall = RoundedCornerShape(16.dp),
    small = RoundedCornerShape(22.dp),
    medium = RoundedCornerShape(28.dp),
    large = RoundedCornerShape(34.dp),
    extraLarge = RoundedCornerShape(44.dp),
)

/** Alien: срезанные углы вместо скруглений. */
val UmbraAlienShapes = Shapes(
    extraSmall = CutCornerShape(6.dp),
    small = CutCornerShape(10.dp),
    medium = CutCornerShape(14.dp),
    large = CutCornerShape(18.dp),
    extraLarge = CutCornerShape(22.dp),
)

fun umbraShapes(style: InterfaceStyle): Shapes = when (style) {
    InterfaceStyle.STANDARD -> UmbraShapes
    InterfaceStyle.SMOKED_GLASS -> UmbraGlassShapes
    InterfaceStyle.ALIEN -> UmbraAlienShapes
}

/** Как выглядит заливка основной кнопки в теме. */
enum class UmbraButtonFill {
    /** Плотная заливка primary. */
    SOLID,
    /** Матовый контейнер без тени. */
    FROSTED,
    /** Прозрачная рамка HUD. */
    OUTLINE,
}

/** Токены кнопок: форма, рамка, тень, плотность и стиль подписи. */
@Immutable
data class UmbraButtonTokens(
    val fill: UmbraButtonFill,
    val shape: Shape,
    val borderWidth: Dp,
    val elevation: Dp,
    val minHeight: Dp,
    val contentPadding: PaddingValues,
    val labelStyle: TextStyle,
)

val UmbraStandardButtons = UmbraButtonTokens(
    fill = UmbraButtonFill.SOLID,
    shape = RoundedCornerShape(18.dp),
    borderWidth = 0.dp,
    elevation = 2.dp,
    minHeight = 50.dp,
    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
    labelStyle = UmbraTypography.labelLarge,
)

val UmbraGlassButtons = UmbraButtonTokens(
    fill = UmbraButtonFill.FROSTED,
    shape = RoundedCornerShape(percent = 50),
    borderWidth = 1.dp,
    elevation = 0.dp,
    minHeight = 54.dp,
    contentPadding = PaddingValues(horizontal = 30.dp, vertical = 15.dp),
    labelStyle = UmbraGlassTypography.labelLarge,
)

val UmbraAlienButtons = UmbraButtonTokens(
    fill = UmbraButtonFill.OUTLINE,
    shape = CutCornerShape(topStart = 0.dp, topEnd = 14.dp, bottomEnd = 0.dp, bottomStart = 14.dp),
    borderWidth = 1.5.dp,
    elevation = 0.dp,
    minHeight = 52.dp,
    contentPadding = PaddingValues(horizontal = 26.dp, vertical = 13.dp),
    labelStyle = UmbraAlienTypography.labelLarge,
)

fun umbraButtonTokens(style: InterfaceStyle): UmbraButtonTokens = when (style) {
    InterfaceStyle.STANDARD -> UmbraStandardButtons
    InterfaceStyle.SMOKED_GLASS -> UmbraGlassButtons
    InterfaceStyle.ALIEN -> UmbraAlienButtons
}

val LocalUmbraButtons = staticCompositionLocalOf { UmbraStandardButtons }

/**
 * Основная кнопка экрана. В каждой теме выглядит по-своему:
 * заливка с тенью в стандартной, матовая таблетка в стеклянной,
 * рамка со срезанными углами в Alien.
 *
 * @param colors явные цвета (например красная кнопка сброса звонка); если заданы,
 *   тема меняет только форму, рамку и типографику.
 */
@Composable
fun UmbraPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = LocalUmbraButtons.current
    val scheme = MaterialTheme.colorScheme
    val themed = colors ?: when (tokens.fill) {
        UmbraButtonFill.SOLID -> ButtonDefaults.buttonColors()
        UmbraButtonFill.FROSTED -> ButtonDefaults.buttonColors(
            containerColor = scheme.primaryContainer.copy(alpha = 0.92f),
            contentColor = scheme.onPrimaryContainer,
        )
        UmbraButtonFill.OUTLINE -> ButtonDefaults.buttonColors(
            containerColor = scheme.primary.copy(alpha = 0.14f),
            contentColor = scheme.primary,
        )
    }
    val border = when {
        tokens.borderWidth <= 0.dp -> null
        tokens.fill == UmbraButtonFill.OUTLINE -> BorderStroke(tokens.borderWidth, scheme.primary)
        else -> BorderStroke(tokens.borderWidth, scheme.outlineVariant)
    }
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = tokens.minHeight),
        enabled = enabled,
        shape = tokens.shape,
        colors = themed,
        border = border,
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = tokens.elevation,
            pressedElevation = tokens.elevation,
            hoveredElevation = tokens.elevation,
            focusedElevation = tokens.elevation,
        ),
        contentPadding = tokens.contentPadding,
    ) {
        ProvideTextStyle(tokens.labelStyle) { content() }
    }
}

/** Вторичное действие: та же форма и подпись, но без заливки. */
@Composable
fun UmbraGhostButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = LocalUmbraButtons.current
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = tokens.minHeight),
        enabled = enabled,
        shape = tokens.shape,
        contentPadding = tokens.contentPadding,
    ) {
        ProvideTextStyle(tokens.labelStyle) { content() }
    }
}

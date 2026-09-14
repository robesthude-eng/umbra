package com.umbra.app.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.umbra.app.data.session.InterfaceStyle

/**
 * Формы поверхностей темы: поля ввода, карточки, панели, чипы,
 * миниатюры и пузыри сообщений.
 *
 * Стандартная тема — умеренные скругления, дымчатое стекло — почти
 * капсулы, Alien — срезанные углы. Одиночные скругления в компонентах
 * стоит брать через [UmbraShapeKit.corner], чтобы они следовали за темой.
 */
enum class UmbraCornerStyle { ROUNDED, CUT }

@Immutable
data class UmbraShapeKit(
    val cornerStyle: UmbraCornerStyle,
    /** Множитель радиуса для одиночных значений в компонентах. */
    val cornerScale: Float,
    val field: Shape,
    val card: Shape,
    val panel: Shape,
    val chip: Shape,
    val thumb: Shape,
    val bubbleBig: Dp,
    val bubbleTight: Dp,
    val bubbleTail: Dp,
) {
    /** Форма с одинаковыми углами в стиле текущей темы. */
    fun corner(size: Dp): Shape {
        val scaled = size * cornerScale
        return when (cornerStyle) {
            UmbraCornerStyle.ROUNDED -> RoundedCornerShape(scaled)
            UmbraCornerStyle.CUT -> CutCornerShape(scaled)
        }
    }

    /** Форма со скруглёнными только верхними углами — для нижних панелей. */
    fun cornerTop(size: Dp): Shape {
        val scaled = size * cornerScale
        return when (cornerStyle) {
            UmbraCornerStyle.ROUNDED -> RoundedCornerShape(topStart = scaled, topEnd = scaled, bottomEnd = 0.dp, bottomStart = 0.dp)
            UmbraCornerStyle.CUT -> CutCornerShape(topStart = scaled, topEnd = scaled, bottomEnd = 0.dp, bottomStart = 0.dp)
        }
    }

    /** Пузырь сообщения: «хвост» со стороны автора, слитные углы внутри серии. */
    fun bubble(outgoing: Boolean, first: Boolean, last: Boolean): Shape {
        val big = bubbleBig
        val tight = bubbleTight
        val tail = bubbleTail
        val topStart = if (outgoing) big else if (first) big else tight
        val topEnd = if (outgoing) (if (first) big else tight) else big
        val bottomEnd = if (outgoing) (if (last) tail else tight) else big
        val bottomStart = if (outgoing) big else if (last) tail else tight
        return when (cornerStyle) {
            UmbraCornerStyle.ROUNDED -> RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart)
            UmbraCornerStyle.CUT -> CutCornerShape(topStart, topEnd, bottomEnd, bottomStart)
        }
    }
}

val UmbraStandardShapeKit = UmbraShapeKit(
    cornerStyle = UmbraCornerStyle.ROUNDED,
    cornerScale = 1f,
    field = RoundedCornerShape(18.dp),
    card = RoundedCornerShape(20.dp),
    panel = RoundedCornerShape(24.dp),
    chip = RoundedCornerShape(14.dp),
    thumb = RoundedCornerShape(14.dp),
    bubbleBig = 22.dp,
    bubbleTight = 8.dp,
    bubbleTail = 5.dp,
)

val UmbraGlassShapeKit = UmbraShapeKit(
    cornerStyle = UmbraCornerStyle.ROUNDED,
    cornerScale = 1.3f,
    field = RoundedCornerShape(percent = 50),
    card = RoundedCornerShape(28.dp),
    panel = RoundedCornerShape(34.dp),
    chip = RoundedCornerShape(percent = 50),
    thumb = RoundedCornerShape(20.dp),
    bubbleBig = 28.dp,
    bubbleTight = 14.dp,
    bubbleTail = 12.dp,
)

val UmbraAlienShapeKit = UmbraShapeKit(
    cornerStyle = UmbraCornerStyle.CUT,
    cornerScale = 0.55f,
    field = CutCornerShape(topStart = 0.dp, topEnd = 12.dp, bottomEnd = 0.dp, bottomStart = 12.dp),
    card = CutCornerShape(14.dp),
    panel = CutCornerShape(18.dp),
    chip = CutCornerShape(8.dp),
    thumb = CutCornerShape(9.dp),
    bubbleBig = 16.dp,
    bubbleTight = 4.dp,
    bubbleTail = 2.dp,
)

fun umbraShapeKit(style: InterfaceStyle): UmbraShapeKit = when (style) {
    InterfaceStyle.STANDARD -> UmbraStandardShapeKit
    InterfaceStyle.SMOKED_GLASS -> UmbraGlassShapeKit
    InterfaceStyle.ALIEN -> UmbraAlienShapeKit
}

val LocalUmbraShapeKit = staticCompositionLocalOf { UmbraStandardShapeKit }

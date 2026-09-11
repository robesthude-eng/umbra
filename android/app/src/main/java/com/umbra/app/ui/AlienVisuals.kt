package com.umbra.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.umbra.app.ui.theme.AlienTokens
import com.umbra.app.ui.theme.LocalUmbraAlienTokens
import com.umbra.app.ui.theme.LocalUmbraReducedMotion
import com.umbra.app.ui.theme.LocalUmbraVisuals
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Alien Interface — «чужая» графика приложения в одном файле.
 *
 * Правила, которые здесь соблюдаются и не должны нарушаться при доработках:
 *  - весь фон рисуется ОДНИМ Canvas на экран: слои дешёвые (градиенты, линии,
 *    круги), без битмап, видео и датчиков;
 *  - все ambient-анимации идут от [rememberAlienPhase]; при включённом
 *    «Меньше движения» фаза фиксируется и кадр становится статичным;
 *  - ни один эффект не влияет на контраст текста: подписи берут цвет из токенов
 *    ([AlienTokens.hud]) и остаются читаемыми на светлой и тёмной теме;
 *  - когда режим выключен, каждая функция здесь — no-op: модификаторы
 *    возвращают исходный [Modifier], а виджеты не добавляют ничего в дерево.
 */

/** Одна звезда фонового поля: доля ширины/высоты, радиус и фаза мерцания. */
internal class AlienStar(
    val x: Float,
    val y: Float,
    val radius: Float,
    val phase: Float,
    val warm: Boolean,
)

/** Детерминированное звёздное поле: одинаковое между запусками, без Random в кадре. */
internal fun alienStars(count: Int): List<AlienStar> {
    var state = 0x5EED_1F0AL
    fun next(): Float {
        state = state * 6364136223846793005L + 1442695040888963407L
        return ((state ushr 33).toInt() and 0x7FFF_FFFF) % 10_000 / 10_000f
    }
    return List(count) {
        AlienStar(
            x = next(),
            y = next(),
            radius = 0.5f + next() * 1.3f,
            phase = next(),
            warm = next() > 0.72f,
        )
    }
}

@Composable
internal fun rememberAlienStars(count: Int): List<AlienStar> = remember(count) { alienStars(count) }

/**
 * Общая фаза ambient-анимаций Alien-режима.
 * При «Меньше движения» возвращает [frozen] и не создаёт бесконечный переход.
 */
@Composable
internal fun rememberAlienPhase(periodMs: Int, reverse: Boolean = true, frozen: Float = 0.32f): Float {
    if (LocalUmbraReducedMotion.current) return frozen
    val transition = rememberInfiniteTransition(label = "alien-phase")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(periodMs, easing = LinearEasing),
            if (reverse) RepeatMode.Reverse else RepeatMode.Restart,
        ),
        label = "alien-phase-value",
    )
    return phase
}

/**
 * Quantum backdrop — фон Alien-режима: небула, орбитальные кольца, сетка
 * горизонта, звёздное поле, комета и виньетка. Всё в одном Canvas.
 */
@Composable
internal fun QuantumBackdrop(modifier: Modifier = Modifier) {
    val visual = LocalUmbraVisuals.current
    val tokens = LocalUmbraAlienTokens.current
    val stars = rememberAlienStars(tokens.starCount)
    val drift = rememberAlienPhase(22_000)
    val spin = rememberAlienPhase(120_000, reverse = false)
    val twinkle = rememberAlienPhase(6_000, reverse = false)
    val comet = rememberAlienPhase(17_000, reverse = false)
    Canvas(modifier.fillMaxSize()) {
        drawRect(brush = Brush.verticalGradient(visual.backdrop))
        drawAlienNebula(tokens, drift)
        drawAlienGrid(tokens, drift)
        drawAlienRings(tokens, spin)
        drawAlienStarfield(stars, tokens, twinkle)
        drawAlienComet(tokens, comet)
        drawAlienVignette(tokens)
    }
}

/** Три дрейфующих облака света — «туманность» за интерфейсом. */
internal fun DrawScope.drawAlienNebula(tokens: AlienTokens, phase: Float) {
    if (tokens.nebulaAlpha <= 0f) return
    val radius = size.minDimension * 0.95f
    val top = Offset(size.width * (0.78f - phase * 0.16f), size.height * 0.06f)
    drawCircle(
        Brush.radialGradient(
            listOf(tokens.primary.copy(alpha = tokens.nebulaAlpha), Color.Transparent),
            center = top,
            radius = radius,
        ),
        radius,
        top,
    )
    val bottomRadius = radius * 0.82f
    val bottom = Offset(size.width * (0.10f + phase * 0.18f), size.height * 0.94f)
    drawCircle(
        Brush.radialGradient(
            listOf(tokens.secondary.copy(alpha = tokens.nebulaAlpha * 0.85f), Color.Transparent),
            center = bottom,
            radius = bottomRadius,
        ),
        bottomRadius,
        bottom,
    )
    val midRadius = radius * 0.6f
    val mid = Offset(size.width * (0.40f + phase * 0.12f), size.height * (0.48f - phase * 0.08f))
    drawCircle(
        Brush.radialGradient(
            listOf(tokens.tertiary.copy(alpha = tokens.nebulaAlpha * 0.7f), Color.Transparent),
            center = mid,
            radius = midRadius,
        ),
        midRadius,
        mid,
    )
}

/** Орбиты: две штриховые окружности вращаются в разные стороны, плюс «луны». */
internal fun DrawScope.drawAlienRings(tokens: AlienTokens, spin: Float) {
    if (tokens.ringAlpha <= 0f) return
    val pivot = Offset(size.width * 0.52f, size.height * 0.36f)
    val base = size.minDimension * 0.34f
    rotate(spin * 360f, pivot) {
        drawCircle(
            tokens.primary.copy(alpha = tokens.ringAlpha),
            base,
            pivot,
            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 26f))),
        )
    }
    rotate(-spin * 220f, pivot) {
        drawCircle(
            tokens.secondary.copy(alpha = tokens.ringAlpha * 0.8f),
            base * 1.36f,
            pivot,
            style = Stroke(0.9.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 20f))),
        )
    }
    drawCircle(tokens.tertiary.copy(alpha = tokens.ringAlpha * 0.5f), base * 1.74f, pivot, style = Stroke(0.7.dp.toPx()))
    if (!tokens.full) return
    val angle = spin * 2f * PI.toFloat()
    val moon = Offset(pivot.x + cos(angle) * base * 1.36f, pivot.y + sin(angle) * base * 1.36f)
    drawCircle(tokens.secondary.copy(alpha = 0.55f), 2.2.dp.toPx(), moon)
    val second = angle + PI.toFloat()
    val satellite = Offset(pivot.x + cos(second) * base, pivot.y + sin(second) * base)
    drawCircle(tokens.primary.copy(alpha = 0.5f), 1.6.dp.toPx(), satellite)
}

/** Сетка горизонта с перспективой и медленной сканирующей линией. */
internal fun DrawScope.drawAlienGrid(tokens: AlienTokens, phase: Float) {
    if (tokens.gridAlpha <= 0f) return
    val horizon = size.height * 0.80f
    val vanishing = Offset(size.width * 0.5f, horizon)
    val stroke = 0.8.dp.toPx()
    repeat(11) { index ->
        val t = index / 10f * 2f - 1f
        drawLine(
            tokens.primary.copy(alpha = tokens.gridAlpha * (1f - abs(t) * 0.45f)),
            vanishing,
            Offset(size.width * 0.5f + t * size.width * 1.9f, size.height),
            stroke,
        )
    }
    repeat(6) { index ->
        val p = (index + 1) / 6f
        val y = horizon + (size.height - horizon) * p * p
        drawLine(
            tokens.tertiary.copy(alpha = tokens.gridAlpha * (0.35f + p * 0.65f)),
            Offset(0f, y),
            Offset(size.width, y),
            stroke,
        )
    }
    val scanY = horizon + (size.height - horizon) * (1f - phase)
    drawLine(
        tokens.secondary.copy(alpha = (tokens.gridAlpha * 2.2f).coerceAtMost(0.4f)),
        Offset(0f, scanY),
        Offset(size.width, scanY),
        stroke * 1.6f,
    )
}

/** Звёздное поле: позиции детерминированы, мерцание — от общей фазы. */
internal fun DrawScope.drawAlienStarfield(stars: List<AlienStar>, tokens: AlienTokens, phase: Float) {
    if (tokens.starAlpha <= 0f || stars.isEmpty()) return
    val wave = phase * 2f * PI.toFloat()
    stars.forEach { star ->
        val twinkle = 0.5f + 0.5f * sin(wave + star.phase * 6.283f)
        val color = if (star.warm) tokens.secondary else tokens.primary
        drawCircle(
            color.copy(alpha = (tokens.starAlpha * twinkle).coerceIn(0f, 1f)),
            star.radius.dp.toPx(),
            Offset(size.width * star.x, size.height * star.y),
        )
    }
}

/** Комета: короткое окно раз в цикл, чтобы не превращаться в мигалку. */
internal fun DrawScope.drawAlienComet(tokens: AlienTokens, phase: Float) {
    if (tokens.cometAlpha <= 0f) return
    val window = 0.22f
    if (phase > window) return
    val t = phase / window
    val fade = sin(t * PI.toFloat())
    val head = Offset(size.width * (-0.12f + t * 1.24f), size.height * (0.06f + t * 0.44f))
    val tail = Offset(head.x - size.width * 0.18f, head.y - size.height * 0.07f)
    drawLine(
        Brush.linearGradient(
            listOf(Color.Transparent, tokens.primary.copy(alpha = tokens.cometAlpha * fade)),
            start = tail,
            end = head,
        ),
        tail,
        head,
        1.6.dp.toPx(),
        cap = StrokeCap.Round,
    )
    drawCircle(tokens.hud.copy(alpha = tokens.cometAlpha * fade), 2.dp.toPx(), head)
}

/** Мягкая виньетка: собирает взгляд к центру, не влияя на контраст текста. */
internal fun DrawScope.drawAlienVignette(tokens: AlienTokens) {
    if (tokens.vignetteAlpha <= 0f) return
    val radius = size.maxDimension * 0.78f
    drawRect(
        Brush.radialGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = tokens.vignetteAlpha)),
            center = Offset(size.width * 0.5f, size.height * 0.44f),
            radius = radius,
        ),
    )
}

/**
 * Голографическая кромка: спектральная обводка поверх содержимого с медленным
 * блеском. [cornerRadius] должен совпадать со скруглением самого блока.
 */
@Composable
internal fun Modifier.holoEdge(cornerRadius: Dp = 22.dp, width: Dp = 1.25.dp, animated: Boolean = true): Modifier {
    val tokens = LocalUmbraAlienTokens.current
    if (!tokens.enabled) return this
    val phase = if (animated) rememberAlienPhase(9_000, reverse = false) else 0.25f
    val strength = if (tokens.full) 1f else 0.6f
    return drawWithContent {
        drawContent()
        val stroke = width.toPx()
        val radius = cornerRadius.toPx()
        val shift = (phase * 2f - 1f) * size.width
        drawRoundRect(
            brush = Brush.linearGradient(
                listOf(
                    tokens.secondary.copy(alpha = 0.12f * strength),
                    tokens.primary.copy(alpha = 0.95f * strength),
                    tokens.tertiary.copy(alpha = 0.7f * strength),
                    tokens.secondary.copy(alpha = 0.12f * strength),
                ),
                start = Offset(shift - size.width * 0.45f, 0f),
                end = Offset(shift + size.width * 0.45f, size.height),
            ),
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(
                (size.width - stroke).coerceAtLeast(0f),
                (size.height - stroke).coerceAtLeast(0f),
            ),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(stroke),
        )
    }
}

/** Ореол под элементом: кнопка или иконка выглядит подсвеченной изнутри. */
@Composable
internal fun Modifier.alienGlow(color: Color? = null, strength: Float = 1f): Modifier {
    val tokens = LocalUmbraAlienTokens.current
    if (!tokens.enabled) return this
    val glow = color ?: tokens.primary
    val alpha = (tokens.glowAlpha * strength).coerceIn(0f, 1f)
    if (alpha <= 0f) return this
    return drawBehind {
        val radius = size.maxDimension * 0.95f
        drawCircle(
            Brush.radialGradient(listOf(glow.copy(alpha = alpha), Color.Transparent), center = center, radius = radius),
            radius,
            center,
        )
    }
}

/**
 * Голографические строки развёртки внутри пузыря сообщения.
 * Статичны: список сообщений не должен держать анимацию на каждой строке.
 */
@Composable
internal fun Modifier.holoScanlines(tint: Color, active: Boolean = true): Modifier {
    val tokens = LocalUmbraAlienTokens.current
    if (!tokens.enabled || !active || tokens.scanlineAlpha <= 0f) return this
    val alpha = tokens.scanlineAlpha
    return drawBehind {
        val step = 5.dp.toPx()
        if (step <= 0f) return@drawBehind
        var y = step / 2f
        while (y < size.height) {
            drawLine(tint.copy(alpha = alpha), Offset(0f, y), Offset(size.width, y), 1f)
            y += step
        }
    }
}

/** Светящаяся кромка сверху: используется для панели навигации. */
@Composable
internal fun Modifier.alienTopEdge(): Modifier {
    val tokens = LocalUmbraAlienTokens.current
    if (!tokens.enabled) return this
    val phase = rememberAlienPhase(7_000, reverse = false)
    return drawWithContent {
        drawContent()
        val height = 1.6.dp.toPx()
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(tokens.primary.copy(alpha = 0.28f), tokens.secondary.copy(alpha = 0.22f)),
            ),
            topLeft = Offset.Zero,
            size = Size(size.width, height * 0.6f),
        )
        val shift = (phase * 2f - 1f) * size.width
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, tokens.primary.copy(alpha = 0.85f), Color.Transparent),
                startX = shift - size.width * 0.35f,
                endX = shift + size.width * 0.35f,
            ),
            topLeft = Offset.Zero,
            size = Size(size.width, height),
        )
    }
}

/** Статичное орбитальное кольцо вокруг аватара: дешёвое, без анимации в списках. */
@Composable
internal fun Modifier.alienOrbitRing(strength: Float = 1f): Modifier {
    val tokens = LocalUmbraAlienTokens.current
    if (!tokens.enabled) return this
    val alpha = (if (tokens.full) 0.55f else 0.35f) * strength
    return drawBehind {
        val radius = size.minDimension * 0.5f + 2.5.dp.toPx()
        drawCircle(tokens.primary.copy(alpha = alpha * 0.45f), radius, center, style = Stroke(1.dp.toPx()))
        drawArc(
            color = tokens.secondary.copy(alpha = alpha),
            startAngle = -46f,
            sweepAngle = 122f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round),
        )
        drawArc(
            color = tokens.primary.copy(alpha = alpha * 0.8f),
            startAngle = 150f,
            sweepAngle = 74f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** HUD-подпись: моноширинный верхний регистр. Ничего не рисует вне Alien-режима. */
@Composable
internal fun AlienHudLabel(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    val tokens = LocalUmbraAlienTokens.current
    if (!tokens.enabled) return
    Text(
        text.uppercase(),
        modifier,
        color = if (accent) tokens.secondary else tokens.hud,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.6.sp,
        ),
    )
}

/** Подпись навигации: в Alien-режиме — моноширинный «бортовой» стиль. */
@Composable
internal fun AlienAwareLabel(text: String, modifier: Modifier = Modifier) {
    val tokens = LocalUmbraAlienTokens.current
    if (!tokens.enabled) {
        Text(text, modifier)
        return
    }
    Text(
        if (tokens.full) text.uppercase() else text,
        modifier,
        style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = FontFamily.Monospace,
            letterSpacing = if (tokens.full) 1.1.sp else 0.4.sp,
        ),
    )
}

/** Индикатор канала: четыре «энергетических» столбика вместо текста статуса. */
@Composable
internal fun AlienSignalMeter(level: Int, modifier: Modifier = Modifier) {
    val tokens = LocalUmbraAlienTokens.current
    if (!tokens.enabled) return
    val pulse = rememberAlienPhase(2_600)
    Canvas(modifier.size(width = 22.dp, height = 14.dp)) {
        val bars = 4
        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * (bars - 1)) / bars).coerceAtLeast(1f)
        repeat(bars) { index ->
            val filled = index < level.coerceIn(0, bars)
            val height = size.height * (0.34f + 0.22f * index)
            val alpha = when {
                !filled -> 0.18f
                index == level - 1 -> 0.55f + 0.45f * pulse
                else -> 1f
            }
            val color = if (index >= 3) tokens.secondary else tokens.primary
            drawRoundRect(
                color = color.copy(alpha = alpha.coerceIn(0f, 1f)),
                topLeft = Offset(index * (barWidth + gap), size.height - height),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

/** Разделитель разделов: линия — глиф — линия. */
@Composable
internal fun AlienDivider(label: String, modifier: Modifier = Modifier) {
    val tokens = LocalUmbraAlienTokens.current
    if (!tokens.enabled) return
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.weight(1f).height(1.dp).drawBehind {
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, tokens.primary.copy(alpha = 0.5f)),
                    ),
                )
            },
        )
        AlienHudLabel(label)
        Box(
            Modifier.weight(1f).height(1.dp).drawBehind {
                drawRect(
                    Brush.horizontalGradient(
                        listOf(tokens.secondary.copy(alpha = 0.5f), Color.Transparent),
                    ),
                )
            },
        )
    }
}

/**
 * Однократная заставка при включении режима: расходящиеся кольца и подпись.
 * Ничего не перехватывает у пальцев (нет pointerInput) и молчит при
 * «Меньше движения» — там смена режима происходит мгновенно.
 */
@Composable
internal fun AlienActivationOverlay(modifier: Modifier = Modifier) {
    val tokens = LocalUmbraAlienTokens.current
    val reduced = LocalUmbraReducedMotion.current
    val progress = remember { Animatable(0f) }
    var armed by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    LaunchedEffect(tokens.intensity) {
        if (!armed) {
            armed = true
            return@LaunchedEffect
        }
        if (!tokens.enabled || reduced) return@LaunchedEffect
        running = true
        progress.snapTo(0f)
        progress.animateTo(1f, tween(1_400, easing = LinearEasing))
        running = false
    }
    if (!running) return
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val value = progress.value
            val fade = if (value < 0.2f) value / 0.2f else ((1f - value) / 0.8f).coerceIn(0f, 1f)
            val radius = size.minDimension * (0.12f + value * 0.92f)
            drawCircle(
                Brush.radialGradient(
                    listOf(tokens.primary.copy(alpha = 0.14f * fade), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius,
                center,
            )
            drawCircle(tokens.primary.copy(alpha = 0.5f * fade), radius, center, style = Stroke(1.6.dp.toPx()))
            drawCircle(tokens.secondary.copy(alpha = 0.3f * fade), radius * 0.66f, center, style = Stroke(1.dp.toPx()))
        }
        AlienHudLabel(
            "канал синхронизирован",
            Modifier.graphicsLayer {
                val value = progress.value
                alpha = if (value < 0.25f) value / 0.25f else ((1f - value) / 0.6f).coerceIn(0f, 1f)
            },
        )
    }
}

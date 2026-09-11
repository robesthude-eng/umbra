package com.umbra.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.umbra.app.ui.theme.LocalUmbraReducedMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Свайпы как основной способ управления.
 *
 * Два жеста, уже ставшие мышечной памятью в мессенджерах:
 * - [SwipeMessageActions] — потянуть сообщение вправо, чтобы ответить, и влево, чтобы
 *   переслать;
 * - [SwipeActionRow] — потянуть строку списка чатов, чтобы отметить прочитанным или
 *   позвонить.
 *
 * Общие правила жеста:
 * - содержимое идёт за пальцем с сопротивлением и упирается в предел, поэтому
 *   вертикальная прокрутка ленты не страдает;
 * - подсказка и сдвиг считаются внутри `graphicsLayer`, то есть кадры жеста не
 *   вызывают рекомпозицию сообщения;
 * - на пороге срабатывает тактильный отклик — становится понятно, что действие сработает;
 * - после отпускания содержимое возвращается пружиной, а при «Меньше движения» —
 *   сразу встаёт на место;
 * - любое действие дублируется в меню и отдаётся скринридеру через custom actions:
 *   жест — ускоритель, а не единственный путь.
 */

/** Одно действие свайпа: иконка, подпись, цвет подсветки и обработчик. */
internal data class SwipeAction(
    val icon: ImageVector,
    val label: String,
    val tint: Color,
    val onAction: () -> Unit,
)

/**
 * Свайп по сообщению: вправо — ответить, влево — переслать.
 *
 * @param content получает модификатор с жестом и сдвигом: его надо повесить на
 *   корневой элемент сообщения.
 */
@Composable
internal fun SwipeMessageActions(
    modifier: Modifier = Modifier,
    canReply: Boolean,
    canForward: Boolean,
    accent: Color,
    onReply: () -> Unit,
    onForward: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    if (!canReply && !canForward) {
        Box(modifier) { content(Modifier) }
        return
    }
    val haptics = LocalHapticFeedback.current
    val reduced = LocalUmbraReducedMotion.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val threshold = with(density) { 52.dp.toPx() }
    val limit = with(density) { 86.dp.toPx() }
    val reply by rememberUpdatedState(onReply)
    val forward by rememberUpdatedState(onForward)
    val offset = remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.matchParentSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SwipeHint(Icons.Filled.Reply, "Ответить", accent) {
                if (canReply) offset.floatValue / threshold else 0f
            }
            Spacer(Modifier.weight(1f))
            SwipeHint(Icons.Filled.Forward, "Переслать", accent, alignEnd = true) {
                if (canForward) -offset.floatValue / threshold else 0f
            }
        }
        content(
            Modifier.graphicsLayer { translationX = offset.floatValue }
                .pointerInput(canReply, canForward) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val released = offset.floatValue
                            armed = false
                            if (canReply && released >= threshold) reply()
                            if (canForward && released <= -threshold) forward()
                            settleSwipe(scope, reduced, released) { offset.floatValue = it }
                        },
                        onDragCancel = {
                            armed = false
                            settleSwipe(scope, reduced, offset.floatValue) { offset.floatValue = it }
                        },
                    ) { change, amount ->
                        change.consume()
                        val low = if (canForward) -limit else 0f
                        val high = if (canReply) limit else 0f
                        // Сопротивление: пузырь отстаёт от пальца, жест ощущается упругим.
                        val next = (offset.floatValue + amount * 0.58f).coerceIn(low, high)
                        offset.floatValue = next
                        val reached = abs(next) >= threshold
                        if (reached && !armed) {
                            armed = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        } else if (!reached && armed) {
                            armed = false
                        }
                    }
                }
                .semantics {
                    customActions = buildList {
                        if (canReply) add(CustomAccessibilityAction("Ответить") { reply(); true })
                        if (canForward) add(CustomAccessibilityAction("Переслать") { forward(); true })
                    }
                },
        )
    }
}

/**
 * Свайп по строке списка: [right] срабатывает на движении вправо, [left] — влево.
 *
 * Если действие не передано, соответствующее направление не тянется вовсе.
 */
@Composable
internal fun SwipeActionRow(
    modifier: Modifier = Modifier,
    right: SwipeAction? = null,
    left: SwipeAction? = null,
    content: @Composable (Modifier) -> Unit,
) {
    if (right == null && left == null) {
        Box(modifier) { content(Modifier) }
        return
    }
    val haptics = LocalHapticFeedback.current
    val reduced = LocalUmbraReducedMotion.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val threshold = with(density) { 64.dp.toPx() }
    val limit = with(density) { 108.dp.toPx() }
    val rightAction by rememberUpdatedState(right)
    val leftAction by rememberUpdatedState(left)
    val hasRight = right != null
    val hasLeft = left != null
    val offset = remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.matchParentSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (right != null) SwipeHint(right.icon, right.label, right.tint) {
                offset.floatValue / threshold
            }
            Spacer(Modifier.weight(1f))
            if (left != null) SwipeHint(left.icon, left.label, left.tint, alignEnd = true) {
                -offset.floatValue / threshold
            }
        }
        content(
            Modifier.graphicsLayer { translationX = offset.floatValue }
                .pointerInput(hasRight, hasLeft) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val released = offset.floatValue
                            armed = false
                            if (released >= threshold) rightAction?.onAction?.invoke()
                            if (released <= -threshold) leftAction?.onAction?.invoke()
                            settleSwipe(scope, reduced, released) { offset.floatValue = it }
                        },
                        onDragCancel = {
                            armed = false
                            settleSwipe(scope, reduced, offset.floatValue) { offset.floatValue = it }
                        },
                    ) { change, amount ->
                        change.consume()
                        val low = if (hasLeft) -limit else 0f
                        val high = if (hasRight) limit else 0f
                        val next = (offset.floatValue + amount * 0.62f).coerceIn(low, high)
                        offset.floatValue = next
                        val reached = abs(next) >= threshold
                        if (reached && !armed) {
                            armed = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        } else if (!reached && armed) {
                            armed = false
                        }
                    }
                }
                .semantics {
                    customActions = buildList {
                        right?.let { action ->
                            add(CustomAccessibilityAction(action.label) { action.onAction(); true })
                        }
                        left?.let { action ->
                            add(CustomAccessibilityAction(action.label) { action.onAction(); true })
                        }
                    }
                },
        )
    }
}

/**
 * Подсказка под содержимым: иконка с подписью, которая наливается по ходу свайпа.
 *
 * [progress] считывается внутри `graphicsLayer`, поэтому движение пальца не требует
 * рекомпозиции: пересчитывается только слой отрисовки.
 */
@Composable
private fun SwipeHint(
    icon: ImageVector,
    label: String,
    tint: Color,
    alignEnd: Boolean = false,
    progress: () -> Float,
) {
    Row(
        Modifier.graphicsLayer {
            val value = progress().coerceIn(0f, 1f)
            alpha = value
            val scale = 0.74f + 0.26f * value
            scaleX = scale
            scaleY = scale
            translationX = (1f - value) * (if (alignEnd) 20f else -20f)
        }.clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/** Возврат содержимого на место: пружина, а при «Меньше движения» — мгновенно. */
private fun settleSwipe(
    scope: CoroutineScope,
    reduced: Boolean,
    from: Float,
    apply: (Float) -> Unit,
) {
    if (reduced || from == 0f) {
        apply(0f)
        return
    }
    scope.launch {
        Animatable(from).animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 420f)) { apply(value) }
    }
}

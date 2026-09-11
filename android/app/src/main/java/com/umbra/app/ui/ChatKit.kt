@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.umbra.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.umbra.app.ui.theme.LocalUmbraChatColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

/**
 * Строительные блоки экрана переписки: фон, пузыри, дата-разделители,
 * галочки статуса и звуковая дорожка голосовых сообщений.
 * Вынесены отдельно, чтобы ChatView остался читаемым.
 */

/** Скругления пузыря: «хвост» со стороны отправителя, слитные углы внутри серии. */
internal fun bubbleShape(outgoing: Boolean, first: Boolean, last: Boolean): RoundedCornerShape {
    val big = 22.dp
    val tight = 8.dp
    return if (outgoing) RoundedCornerShape(
        topStart = big,
        topEnd = if (first) big else tight,
        bottomEnd = if (last) 5.dp else tight,
        bottomStart = big,
    ) else RoundedCornerShape(
        topStart = if (first) big else tight,
        topEnd = big,
        bottomEnd = big,
        bottomStart = if (last) 5.dp else tight,
    )
}

/** Фон переписки: вертикальный градиент и два мягких световых пятна. */
@Composable
internal fun ChatBackground(modifier: Modifier = Modifier) {
    val chat = LocalUmbraChatColors.current
    Canvas(modifier) {
        drawRect(brush = Brush.verticalGradient(chat.screen))
        val topRadius = size.minDimension * 0.85f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(chat.glowTop, Color.Transparent),
                center = Offset(size.width * 0.86f, size.height * 0.02f),
                radius = topRadius,
            ),
            radius = topRadius,
            center = Offset(size.width * 0.86f, size.height * 0.02f),
        )
        val bottomRadius = size.minDimension * 0.75f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(chat.glowBottom, Color.Transparent),
                center = Offset(size.width * 0.05f, size.height * 0.92f),
                radius = bottomRadius,
            ),
            radius = bottomRadius,
            center = Offset(size.width * 0.05f, size.height * 0.92f),
        )
    }
}

/** Плашка с датой между днями переписки. */
@Composable
internal fun DateChip(label: String, modifier: Modifier = Modifier) {
    val chat = LocalUmbraChatColors.current
    Box(modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            Modifier.clip(CircleShape).background(chat.bar).padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = chat.incomingMeta,
        )
    }
}

/** Sent means acknowledged by the server; the protocol has no read receipts. */
@Composable
internal fun MessageStatus(pending: Boolean, failed: Boolean, tint: Color, modifier: Modifier = Modifier) {
    val icon = when {
        failed -> Icons.Filled.ErrorOutline
        pending -> Icons.Filled.Schedule
        else -> Icons.Filled.Done
    }
    val description = when {
        failed -> "Не отправлено"
        pending -> "Ожидает отправки"
        else -> "Отправлено"
    }
    Icon(icon, description, modifier.size(14.dp), tint = tint)
}

/** Точка состояния соединения рядом с названием чата. */
@Composable
internal fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(7.dp).clip(CircleShape).background(color))
}

/**
 * Звуковая дорожка голосового: столбики вместо ползунка.
 * [progress] — доля прослушанного, [onSeek] включает перемотку касанием.
 */
@Composable
internal fun VoiceWaveform(
    bars: List<Float>,
    progress: Float,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
    onSeek: ((Float) -> Unit)? = null,
) {
    val seekModifier = if (onSeek == null) Modifier else Modifier
        .pointerInput(bars.size, onSeek) {
            detectTapGestures { position ->
                val width = size.width.toFloat()
                if (width > 0f) onSeek((position.x / width).coerceIn(0f, 1f))
            }
        }
        .pointerInput(bars.size, onSeek) {
            detectHorizontalDragGestures { change, _ ->
                val width = size.width.toFloat()
                if (width > 0f) onSeek((change.position.x / width).coerceIn(0f, 1f))
            }
        }
    Canvas(modifier.then(seekModifier).semantics {
        if (onSeek != null) {
            contentDescription = "Положение голосового сообщения"
            progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
            setProgress { target -> onSeek(target.coerceIn(0f, 1f)); true }
        }
    }) {
        if (bars.isEmpty() || size.width <= 0f) return@Canvas
        val gap = 2.dp.toPx()
        val count = minOf(bars.size, ((size.width + gap) / (2.dp.toPx() + gap)).toInt().coerceAtLeast(1))
        val barWidth = (size.width - gap * (count - 1)) / count
        val played = size.width * progress.coerceIn(0f, 1f)
        repeat(count) { index ->
            val level = bars[index * bars.size / count]
            val x = index * (barWidth + gap)
            val height = (size.height * level.coerceIn(0.12f, 1f)).coerceAtLeast(barWidth)
            val top = (size.height - height) / 2f
            drawRoundRect(
                color = if (x + barWidth / 2f <= played) activeColor else inactiveColor,
                topLeft = Offset(x, top),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

/** Псевдослучайная, но стабильная для одного сообщения форма дорожки. */
internal fun waveformBars(seed: String, count: Int = 30): List<Float> {
    var state = (seed.hashCode().toLong() and 0xFFFFFFFFL) or 1L
    return List(count) { index ->
        state = state * 6364136223846793005L + 1442695040888963407L
        val noise = abs((state ushr 33).toInt() % 1000) / 1000f
        val envelope = 0.55f + 0.45f * sin(index * 0.55f + 1f)
        (0.2f + 0.8f * noise) * envelope.coerceIn(0.35f, 1f)
    }
}

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val dayFormat = DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault())
private val dayYearFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())

/** Время сообщения: только часы и минуты, дата выносится в разделитель. */
internal fun clockText(millis: Long): String =
    clockFormat.format(Instant.ofEpochMilli(if (millis > 0) millis else System.currentTimeMillis()))

private fun localDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(if (millis > 0) millis else System.currentTimeMillis())
        .atZone(ZoneId.systemDefault()).toLocalDate()

internal fun dayKey(millis: Long): String = localDate(millis).toString()

internal fun dayLabel(millis: Long): String {
    val date = localDate(millis)
    val today = LocalDate.now()
    return when {
        date == today -> "Сегодня"
        date == today.minusDays(1) -> "Вчера"
        date.year == today.year -> dayFormat.format(date)
        else -> dayYearFormat.format(date)
    }
}

/** Компактная плашка-подсказка над полем ввода. */
@Composable
internal fun NoticeBar(text: String, color: Color, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = color)
        if (action != null) {
            Spacer(Modifier.width(0.dp))
            action()
        }
    }
}

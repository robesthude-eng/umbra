package com.umbra.app.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.umbra.app.data.repo.ChatRepository
import com.umbra.app.data.repo.UiAttachment
import com.umbra.app.ui.theme.LocalUmbraAlienTokens
import com.umbra.app.ui.theme.LocalUmbraReducedMotion
import com.umbra.app.ui.theme.UmbraChatColors

/**
 * Витрина медиа в переписке.
 *
 * Задача файла — сделать фото и видео главным героем пузыря: без рывков при
 * загрузке, без обрезанных панорам и без чёрных плашек поверх кадра.
 *
 * Приёмы:
 * - пока превью грузится, вместо кружка показывается скелет с мягким блеском:
 *   размер пузыря известен заранее, поэтому лента не дёргается;
 * - вертикальные и панорамные кадры вписываются целиком, а пустоту по бокам
 *   закрывает размытая копия того же кадра (Android 12+; ниже — ровный фон);
 * - подписи (время, статус, длительность) читаются за счёт градиентной тени у
 *   нижней кромки, а не тёмной «пилюли»;
 * - в Alien-режиме кадр получает световую кромку и блеск в цветах режима.
 */
private const val MEDIA_MIN_RATIO = 0.58f
private const val MEDIA_MAX_RATIO = 1.9f

/** Ширина медиапузыря: единая, чтобы лента выглядела как аккуратная колонка кадров. */
internal val MediaBubbleWidth: Dp = 268.dp

/**
 * Фото или видео на всю ширину пузыря.
 *
 * @param overlay слой поверх кадра: время, статус, бейджи. Получает [BoxScope], поэтому
 *   внутри можно свободно пользоваться `align`.
 */
@Composable
internal fun AttachmentPhoto(
    repo: ChatRepository,
    attachment: UiAttachment,
    palette: UmbraChatColors,
    modifier: Modifier = Modifier,
    width: Dp = MediaBubbleWidth,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    var attempt by remember(attachment.mediaId, attachment.localPath) { mutableIntStateOf(0) }
    var failed by remember(attachment.mediaId, attachment.localPath) { mutableStateOf(false) }
    val thumb by produceState<Bitmap?>(null, attachment.mediaId, attachment.localPath, attempt) {
        value = null
        failed = false
        val loaded = runCatching { repo.attachmentThumbnail(attachment) }.getOrNull()
        failed = loaded == null
        value = loaded
    }
    val reduced = LocalUmbraReducedMotion.current
    val tokens = LocalUmbraAlienTokens.current
    val declared = if (attachment.width > 0 && attachment.height > 0)
        attachment.width.toFloat() / attachment.height.toFloat() else 1.35f
    // Крайности (панорамы, длинные скриншоты) не режем, а вписываем в кадр.
    val letterbox = declared < MEDIA_MIN_RATIO || declared > MEDIA_MAX_RATIO
    val ratio = declared.coerceIn(MEDIA_MIN_RATIO, MEDIA_MAX_RATIO)
    val bitmap = thumb
    val appear by animateFloatAsState(
        targetValue = if (bitmap != null) 1f else 0f,
        animationSpec = tween(if (reduced) 0 else 240),
        label = "media-appear",
    )
    val accent = if (tokens.enabled) tokens.primary else palette.accent
    Box(
        modifier.width(width).aspectRatio(ratio).background(palette.field).alienTopEdge(),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> {
                if (letterbox) Image(
                    bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(24.dp).graphicsLayer { alpha = 0.5f * appear },
                    contentScale = ContentScale.Crop,
                )
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = if (attachment.isVideo) "Видео" else "Фото",
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        alpha = appear
                        // Кадр «подъезжает» на пару процентов — мягче, чем резкая подмена.
                        val scale = 1.03f - 0.03f * appear
                        scaleX = scale
                        scaleY = scale
                    },
                    contentScale = if (letterbox) ContentScale.Fit else ContentScale.Crop,
                )
            }
            // Ошибка и кнопка повтора вместо бесконечного кружка.
            failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Превью не загрузилось",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.incomingMeta,
                    textAlign = TextAlign.Center,
                )
                TextButton({ attempt++ }) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Повторить")
                }
            }
            else -> Box(Modifier.fillMaxSize().mediaSkeleton(accent, animated = !reduced))
        }
        // Градиентная тень снизу: время и статус читаются на любом кадре.
        if (bitmap != null) Box(Modifier.fillMaxSize().background(mediaScrim()))
        if (attachment.isVideo && bitmap != null) Box(
            Modifier.size(56.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.34f))
                .alienGlow(strength = 0.9f),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.PlayArrow, "Воспроизвести", Modifier.size(30.dp), tint = Color.White) }
        overlay()
    }
}

/** Бейдж поверх кадра: длительность видео, пометка пересылки и прочее. */
@Composable
internal fun MediaBadge(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.38f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(13.dp), tint = Color.White)
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

/** Тень у нижней кромки кадра — ровно настолько, чтобы подписи не терялись. */
internal fun mediaScrim(): Brush = Brush.verticalGradient(
    0.55f to Color.Transparent,
    0.82f to Color.Black.copy(alpha = 0.16f),
    1f to Color.Black.copy(alpha = 0.46f),
)

/**
 * Скелет на месте кадра: мягкий блеск бежит по диагонали.
 *
 * При «Меньше движения» остаётся статичная подложка — без бегущего блика.
 */
@Composable
internal fun Modifier.mediaSkeleton(accent: Color, animated: Boolean = true): Modifier {
    if (!animated) return this.background(accent.copy(alpha = 0.10f))
    val phase by rememberMediaSkeletonPhase()
    return this.background(
        Brush.linearGradient(
            colors = listOf(
                accent.copy(alpha = 0.05f),
                accent.copy(alpha = 0.22f),
                accent.copy(alpha = 0.05f),
            ),
            start = Offset(-420f + 1200f * phase, -120f),
            end = Offset(120f + 1200f * phase, 420f),
        ),
    )
}

@Composable
private fun rememberMediaSkeletonPhase() =
    androidx.compose.animation.core.rememberInfiniteTransition(label = "media-skeleton").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_500, easing = LinearEasing), RepeatMode.Restart),
        label = "media-skeleton-phase",
    )

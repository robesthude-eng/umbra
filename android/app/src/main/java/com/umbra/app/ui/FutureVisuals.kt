package com.umbra.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.umbra.app.ui.theme.LocalUmbraReducedMotion
import com.umbra.app.ui.theme.LocalUmbraAlienMode
import com.umbra.app.ui.theme.LocalUmbraVisuals
import com.umbra.app.ui.theme.LocalUmbraMotion
import com.umbra.app.ui.theme.LocalUmbraAlienTokens
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Лёгкий Canvas-фон: два градиента без bitmap, шейдерных эффектов и blur. */
@Composable
internal fun FutureBackdrop(modifier: Modifier = Modifier) {
    val visual = LocalUmbraVisuals.current
    val reduced = LocalUmbraReducedMotion.current
    // В Alien-режиме фон рисует QuantumBackdrop: слоёв больше, но Canvas всё также один.
    if (LocalUmbraAlienMode.current) {
        QuantumBackdrop(modifier)
        return
    }
    val transition = rememberInfiniteTransition(label = "umbra-aura")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (reduced) 0f else 1f,
        animationSpec = infiniteRepeatable(tween(18_000), RepeatMode.Reverse),
        label = "aura-phase",
    )
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(visual.backdrop))
        val radius = size.minDimension * 0.78f
        drawCircle(
            Brush.radialGradient(
                listOf(visual.auraPrimary.copy(alpha = 0.13f), Color.Transparent),
                center = Offset(size.width * (0.80f - phase * 0.10f), size.height * 0.08f),
                radius = radius,
            ),
            radius,
            Offset(size.width * (0.80f - phase * 0.10f), size.height * 0.08f),
        )
        drawCircle(
            Brush.radialGradient(
                listOf(visual.auraSecondary.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(size.width * (0.08f + phase * 0.12f), size.height * 0.88f),
                radius = radius * 0.85f,
            ),
            radius * 0.85f,
            Offset(size.width * (0.08f + phase * 0.12f), size.height * 0.88f),
        )
    }
}

/** Контрастная полупрозрачная панель; не зависит от поддержки backdrop blur. */
@Composable
internal fun GlassPanel(
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val visual = LocalUmbraVisuals.current
    val alien = LocalUmbraAlienMode.current
    val shape = RoundedCornerShape(if (strong) 26.dp else 22.dp)
    val edge = if (alien) Brush.linearGradient(listOf(visual.auraPrimary.copy(alpha = 0.78f), visual.auraSecondary.copy(alpha = 0.54f), visual.glassBorder))
        else SolidColor(visual.glassBorder)
    Surface(
        modifier = modifier
            .border(BorderStroke(if (alien) 1.25.dp else 1.dp, edge), shape)
            // Бегущий спектральный блик по кромке — только в Alien-режиме.
            .holoEdge(cornerRadius = if (strong) 26.dp else 22.dp, width = 1.dp),
        shape = shape,
        color = if (strong) visual.glassStrong else visual.glass,
        tonalElevation = if (strong) 3.dp else 1.dp,
        shadowElevation = if (strong) 8.dp else 2.dp,
    ) { Box(content = content) }
}

/** Небольшая физичная реакция на нажатие без изменения layout. */
@Composable
internal fun Modifier.futurePress(interactionSource: MutableInteractionSource): Modifier {
    val reduced = LocalUmbraReducedMotion.current
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (!reduced && pressed) 0.975f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "future-press",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
internal fun rememberFutureInteraction() = remember { MutableInteractionSource() }

/** Единый компактный индикатор записи, отправки и других коротких процессов. */
@Composable
internal fun ActivityIsland(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    val visual = LocalUmbraVisuals.current
    val motion = LocalUmbraMotion.current
    val reduced = LocalUmbraReducedMotion.current
    GlassPanel(modifier, strong = true) {
        AnimatedContent(
            targetState = icon to label,
            transitionSpec = {
                if (reduced) EnterTransition.None togetherWith ExitTransition.None
                else (fadeIn(tween(motion.quickMs)) + scaleIn(initialScale = 0.94f)) togetherWith
                    (fadeOut(tween(motion.quickMs)) + scaleOut(targetScale = 0.94f))
            },
            label = "activity-island-morph",
        ) { (stateIcon, stateLabel) ->
            Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                Icon(stateIcon, null, Modifier.size(18.dp), tint = visual.auraPrimary)
                Spacer(Modifier.width(8.dp))
                Text(stateLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Selected destinations become a quiet orbital object only in Alien mode. */
@Composable
internal fun OrbitalNavIcon(icon: ImageVector, label: String, selected: Boolean) {
    val tokens = LocalUmbraAlienTokens.current
    val alien = tokens.enabled
    val reduced = LocalUmbraReducedMotion.current
    val visual = LocalUmbraVisuals.current
    val transition = rememberInfiniteTransition(label = "alien-orbit")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (alien && selected && !reduced) 1f else 0f,
        animationSpec = infiniteRepeatable(tween(2_600), RepeatMode.Reverse),
        label = "alien-orbit-pulse",
    )
    // Спутник по орбите есть только у выбранного раздела и только на полной силе.
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (tokens.full && selected && !reduced) 1f else 0f,
        animationSpec = infiniteRepeatable(tween(6_000), RepeatMode.Restart),
        label = "alien-orbit-spin",
    )
    Box(
        Modifier.size(42.dp).drawBehind {
            if (!alien) return@drawBehind
            val ring = size.minDimension * 0.45f
            if (!selected) {
                drawCircle(tokens.primary.copy(alpha = 0.16f), radius = ring * 0.92f, style = Stroke(0.7.dp.toPx()))
                return@drawBehind
            }
            drawCircle(visual.auraPrimary.copy(alpha = 0.20f + pulse * 0.10f), radius = size.minDimension * (0.36f + pulse * 0.04f))
            drawCircle(visual.auraSecondary.copy(alpha = 0.52f), radius = ring, style = Stroke(1.dp.toPx()))
            if (!tokens.full) return@drawBehind
            val angle = spin * 2f * PI.toFloat()
            drawCircle(
                tokens.primary.copy(alpha = 0.9f),
                1.9.dp.toPx(),
                Offset(center.x + cos(angle) * ring, center.y + sin(angle) * ring),
            )
        },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) { Icon(icon, label, tint = if (alien && selected) visual.auraPrimary else androidx.compose.ui.graphics.Color.Unspecified) }
}

package com.umbra.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.umbra.app.ui.theme.LocalUmbraReducedMotion
import com.umbra.app.ui.theme.LocalUmbraVisuals

/** Лёгкий Canvas-фон: два градиента без bitmap, шейдерных эффектов и blur. */
@Composable
internal fun FutureBackdrop(modifier: Modifier = Modifier) {
    val visual = LocalUmbraVisuals.current
    val reduced = LocalUmbraReducedMotion.current
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
    val shape = RoundedCornerShape(if (strong) 26.dp else 22.dp)
    Surface(
        modifier = modifier.border(1.dp, visual.glassBorder, shape),
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

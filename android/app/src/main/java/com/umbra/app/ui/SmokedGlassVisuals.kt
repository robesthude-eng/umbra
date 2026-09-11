package com.umbra.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.umbra.app.ui.theme.LocalUmbraSmokedGlass
import com.umbra.app.ui.theme.LocalUmbraVisuals

/**
 * Мягкие расфокусированные пятна одним Canvas: работает с API 26 без bitmap,
 * backdrop blur и постоянно работающей анимации. Текст и медиа не размываются.
 */
@Composable
internal fun SmokedGlassBackdrop(modifier: Modifier = Modifier) {
    val visual = LocalUmbraVisuals.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.45f
    Canvas(modifier.fillMaxSize()) {
        if (size.minDimension <= 0f) return@Canvas
        drawRect(Brush.verticalGradient(visual.backdrop))
        val radius = size.minDimension * 0.95f
        fun cloud(x: Float, y: Float, scale: Float, color: Color) {
            val center = Offset(size.width * x, size.height * y)
            val r = radius * scale
            drawCircle(
                brush = Brush.radialGradient(
                    0f to color, 0.45f to color.copy(alpha = color.alpha * 0.38f),
                    1f to Color.Transparent, center = center, radius = r,
                ),
                radius = r, center = center,
            )
        }
        cloud(0.85f, 0.08f, 1.1f, Color(0xFFBACDDA).copy(alpha = if (dark) 0.20f else 0.32f))
        cloud(0.05f, 0.42f, 0.85f, Color(0xFFAABFCC).copy(alpha = if (dark) 0.16f else 0.22f))
        cloud(0.92f, 0.70f, 0.90f, Color(0xFF3B99A3).copy(alpha = if (dark) 0.14f else 0.10f))
        cloud(0.12f, 0.98f, 0.75f, Color(0xFFB1BECA).copy(alpha = if (dark) 0.14f else 0.22f))
    }
}

/** Кромка и поверхностный блик; прозрачность применяется только к фону. */
@Composable
internal fun Modifier.smokedGlassEdge(shape: Shape, active: Boolean = false): Modifier {
    if (!LocalUmbraSmokedGlass.current) return this
    val visual = LocalUmbraVisuals.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.45f
    val rim = if (active) visual.auraPrimary.copy(alpha = 0.70f) else visual.glassBorder
    return background(
        Brush.verticalGradient(listOf(
            Color.White.copy(alpha = if (dark) 0.075f else 0.36f),
            Color.Transparent,
            Color.Black.copy(alpha = if (dark) 0.07f else 0.015f),
        )), shape,
    ).border(
        BorderStroke(1.dp, Brush.linearGradient(listOf(
            rim, rim.copy(alpha = rim.alpha * 0.34f), rim.copy(alpha = rim.alpha * 0.70f),
        ))), shape,
    )
}

@Composable
internal fun Modifier.smokedGlassSurface(
    shape: Shape = RoundedCornerShape(24.dp),
    strong: Boolean = false,
): Modifier {
    if (!LocalUmbraSmokedGlass.current) return this
    val visual = LocalUmbraVisuals.current
    return background(if (strong) visual.glassStrong else visual.glass, shape).smokedGlassEdge(shape)
}

/** Бирюзовая кромка только у действующей кнопки; не меняет зону касания. */
@Composable
internal fun Modifier.smokedGlassAction(shape: Shape = CircleShape, enabled: Boolean = true): Modifier =
    smokedGlassEdge(shape, active = enabled)

/** Обычный Surface при STANDARD, единое стекло в выбранном новом стиле. */
@Composable
internal fun AppearanceSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    val glass = LocalUmbraSmokedGlass.current
    Surface(
        modifier = if (glass) modifier.smokedGlassSurface(shape) else modifier,
        shape = shape,
        color = if (glass) Color.Transparent else color,
        contentColor = contentColor,
        border = if (glass) null else border,
        content = content,
    )
}

@Composable
internal fun SmokedGlassNavIcon(icon: ImageVector, label: String, selected: Boolean) {
    val visual = LocalUmbraVisuals.current
    Box(
        Modifier.size(42.dp).drawBehind {
            if (selected) {
                drawCircle(visual.auraPrimary.copy(alpha = 0.10f), radius = size.minDimension * 0.46f)
                drawOval(
                    color = visual.auraPrimary.copy(alpha = 0.55f),
                    topLeft = Offset(1.dp.toPx(), 7.dp.toPx()),
                    size = Size(size.width - 2.dp.toPx(), size.height - 14.dp.toPx()),
                    style = Stroke(1.dp.toPx()),
                )
            }
        }, contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (selected) visual.auraPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

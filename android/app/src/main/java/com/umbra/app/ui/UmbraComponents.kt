package com.umbra.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.umbra.app.ui.theme.LocalUmbraReducedMotion
import com.umbra.app.ui.theme.LocalUmbraVisuals
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One live screen at a time, so saved drafts are never duplicated by a transition. */
@Composable
internal fun ScreenEntrance(screenKey: Any, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val reduced = LocalUmbraReducedMotion.current
    val progress = remember(screenKey, reduced) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(progress) {
        if (!reduced) progress.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
    }
    Box(modifier.graphicsLayer {
        alpha = progress.value
        translationY = 6.dp.toPx() * (1f - progress.value)
    }, content = content)
}

@Composable
internal fun PageHeading(title: String, action: (@Composable () -> Unit)? = null) {
    val visual = LocalUmbraVisuals.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            action?.invoke()
        }
        Box(
            Modifier.padding(start = 20.dp, bottom = 8.dp).width(52.dp).height(3.dp)
                .clip(RoundedCornerShape(50)).background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(visual.auraPrimary, visual.auraSecondary.copy(alpha = 0.25f))
                    )
                )
        )
    }
}

@Composable
internal fun AppEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val visual = LocalUmbraVisuals.current
    Box(modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
        GlassPanel(Modifier.widthIn(max = 420.dp), strong = true) {
        Column(
            Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(24.dp))
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(visual.auraPrimary, visual.auraSecondary))),
                contentAlignment = Alignment.Center,
            ) {
                    Icon(icon, null, Modifier.size(30.dp), tint = androidx.compose.ui.graphics.Color.White)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            action?.invoke()
        }
        }
    }
}

@Composable
internal fun GroupAvatar(name: String, size: Dp = 52.dp) {
    val visual = LocalUmbraVisuals.current
    Box(
        Modifier.size(size).clip(RoundedCornerShape(18.dp)).background(
            androidx.compose.ui.graphics.Brush.linearGradient(listOf(visual.auraPrimary, visual.auraSecondary))
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Groups, "Группа: $name", Modifier.size(size * 0.5f), tint = androidx.compose.ui.graphics.Color.White)
    }
}

@Composable
internal fun GlassRow(onClick: (() -> Unit)?, content: @Composable RowScope.() -> Unit) {
    val visual = LocalUmbraVisuals.current
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(visual.glass)
            .let { if (onClick == null) it else it.clickable(onClick = onClick) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Compact, local-time labels; the year stays visible for older conversations. */
internal fun shortDate(millis: Long): String {
    if (millis <= 0L) return ""
    val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    return when {
        date.toLocalDate() == today -> date.format(DateTimeFormatter.ofPattern("HH:mm"))
        date.toLocalDate() == today.minusDays(1) -> "Вчера"
        date.year == today.year -> date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        else -> date.format(DateTimeFormatter.ofPattern("dd.MM.yy"))
    }
}

package com.umbra.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.umbra.app.data.api.LinkPreviewView
import com.umbra.app.data.msg.Links
import com.umbra.app.data.repo.ChatRepository

/**
 * Карточка первой ссылки в сообщении. Карточку собирает сервер,
 * чтобы чужой сайт не увидел IP получателя до клика.
 * Если ссылки нет, сервер старый или сайт молчит — не рисуется ничего.
 */
@Composable
fun LinkPreviewCard(
    repo: ChatRepository,
    text: String,
    contentColor: Color,
    metaColor: Color,
    modifier: Modifier = Modifier,
) {
    val url = remember(text) { Links.first(text) } ?: return
    val context = LocalContext.current
    var preview by remember(url) { mutableStateOf<LinkPreviewView?>(null) }
    LaunchedEffect(url) {
        preview = runCatching { repo.linkPreview(url) }.getOrNull()
    }
    val card = preview ?: return
    Column(
        modifier
            .padding(top = 6.dp)
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(metaColor.copy(alpha = 0.14f))
            .clickable {
                runCatching {
                    val target = card.url.ifBlank { url }
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                }
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (card.siteName.isNotBlank()) Text(
            card.siteName,
            style = MaterialTheme.typography.labelSmall,
            color = metaColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (card.title.isNotBlank()) Text(
            card.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (card.description.isNotBlank()) Text(
            card.description,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor.copy(alpha = 0.8f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

package com.umbra.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import com.umbra.app.data.repo.ChatRepository
import com.umbra.app.ui.theme.UmbraColors

/**
 * Аватар-кружок. Если задан mediaId серверного аватара — качает и показывает
 * фото (кэш в репозитории); иначе градиентный кружок с первой буквой имени.
 */
@Composable
fun Avatar(
    repo: ChatRepository,
    mediaId: String?,
    name: String,
    size: Dp,
    onClick: (() -> Unit)? = null,
) {
    var bytes by remember(mediaId) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(mediaId) {
        bytes = mediaId?.let { id ->
            runCatching { repo.avatarBytesCached(id) }.getOrNull()
        }
    }
    val shape = CircleShape
    var modifier = Modifier.size(size).clip(shape).background(UmbraColors.headerGradient)
    if (onClick != null) modifier = modifier.clickable(onClick = onClick)
    Box(modifier, contentAlignment = Alignment.Center) {
        val bmp = bytes?.let {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
        }
        if (bmp != null) {
            Image(bitmap = bmp.asImageBitmap(), contentDescription = name,
                modifier = Modifier.size(size).clip(shape))
        } else {
            Text(
                name.trim().firstOrNull()?.toString()?.uppercase() ?: "?",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

/** Аватар пользователя по его id (карточка → avatar_media_id → фото). */
@Composable
fun UserAvatar(
    repo: ChatRepository,
    userId: String,
    name: String,
    size: Dp,
    onClick: (() -> Unit)? = null,
) {
    var mediaId by remember(userId) { mutableStateOf<String?>(null) }
    LaunchedEffect(userId) {
        mediaId = runCatching { repo.resolveUser(userId)?.avatarMediaId }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }
    Avatar(repo, mediaId, name, size, onClick)
}

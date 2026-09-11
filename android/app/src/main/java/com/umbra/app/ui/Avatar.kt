package com.umbra.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.umbra.app.data.AvatarImages
import com.umbra.app.data.repo.ChatRepository
import kotlinx.coroutines.CancellationException

@Composable
fun Avatar(repo: ChatRepository, mediaId: String?, name: String, size: Dp, onClick: (() -> Unit)? = null) {
    val bitmap by produceState<Bitmap?>(null, mediaId) {
        value = null
        try { value = mediaId?.takeIf { it.isNotBlank() }?.let { repo.avatarBytesCached(it) }?.let { AvatarImages.decode(it) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Initials remain visible if a remote image is unavailable. */ }
    }
    AvatarFrame(bitmap, name, size, onClick)
}

@Composable
fun UserAvatar(repo: ChatRepository, userId: String, name: String, size: Dp, onClick: (() -> Unit)? = null) {
    val users by repo.userCache.collectAsState()
    LaunchedEffect(userId) {
        try { repo.resolveUser(userId) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Initials are the offline fallback. */ }
    }
    Avatar(repo, users[userId]?.avatarMediaId, name, size, onClick)
}

@Composable
internal fun AvatarPhoto(avatarUri: String?, size: Dp, onClick: (() -> Unit)?) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, avatarUri) {
        value = null
        try { value = avatarUri?.let { AvatarImages.decode(AvatarImages.read(context, Uri.parse(it))) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Saving reports a readable error and allows choosing another image. */ }
    }
    AvatarFrame(bitmap, "+", size, onClick)
}

@Composable
private fun AvatarFrame(bitmap: Bitmap?, name: String, size: Dp, onClick: (() -> Unit)?) {
    // Кольцо-орбита вокруг аватара рисуется только в Alien-режиме.
    var modifier = Modifier.size(size).alienOrbitRing(strength = 0.8f)
        .clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
    if (onClick != null) modifier = modifier.clickable(onClickLabel = "Выбрать фото", onClick = onClick)
    Box(modifier.semantics { contentDescription = if (onClick != null) "Выбрать фото профиля" else "Аватар: $name" }, contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.size(size), contentScale = ContentScale.Crop)
        else Text(name.trim().firstOrNull()?.toString()?.uppercase() ?: "?", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleLarge)
    }
}

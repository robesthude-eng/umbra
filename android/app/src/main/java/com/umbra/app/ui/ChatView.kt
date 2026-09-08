package com.umbra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.umbra.app.data.db.ChatEntity
import com.umbra.app.data.repo.UiMessage
import com.umbra.app.di.AppContainer
import com.umbra.app.ui.theme.UmbraColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Экран чата (личный или групповой). */
@Composable
fun ChatView(container: AppContainer, chatId: String, onBack: () -> Unit) {
    val repo = container.chatRepository
    var chat by remember { mutableStateOf<ChatEntity?>(null) }
    var messages by remember { mutableStateOf<List<UiMessage>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(chatId) {
        repo.chats().collect { list -> chat = list.firstOrNull { it.id == chatId } }
    }
    LaunchedEffect(chatId) {
        repo.messagesFor(chatId).collect { messages = it }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(chat?.title?.ifBlank { chatId.take(12) } ?: chatId.take(12))
                        if (chat?.type == "group") {
                            Text("группа", style = MaterialTheme.typography.labelSmall, color = UmbraColors.Fog)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).background(Color(0xF00E1F30))) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(messages) { m -> MessageBubble(m, group = chat?.type == "group") }
            }
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение…") },
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = UmbraColors.Aqua,
                        unfocusedBorderColor = UmbraColors.Mist,
                        cursorColor = UmbraColors.Aqua,
                    ),
                )
                Spacer(Modifier.width(6.dp))
                FilledIconButton(
                    onClick = {
                        val text = input.trim()
                        if (text.isEmpty() || sending) return@FilledIconButton
                        sending = true; input = ""
                        scope.launch {
                            try { repo.sendText(chatId, text) } catch (_: Exception) { }
                            sending = false
                        }
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = UmbraColors.Aqua),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "отправить", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun MessageBubble(m: UiMessage, group: Boolean) {
    val mine = m.outgoing
    val bubbleColor = if (mine) Color(0xFF165A84) else Color(0xFF243B4E)
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (group && !mine) {
            Text(m.senderId.take(6), style = MaterialTheme.typography.labelSmall, color = UmbraColors.Aqua)
        }
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, if (mine) 4.dp else 18.dp, if (mine) 18.dp else 4.dp),
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 290.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(m.text, color = Color.White)
                Text(
                    timeText(m.createdAtMillis) + if (m.pending) " · …" else if (m.failed) " · не доставлено" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xAAFFFFFF),
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
        }
    }
}

private val timeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private fun timeText(millis: Long): String =
    if (millis <= 0) "" else timeFormat.format(Instant.ofEpochMilli(millis))

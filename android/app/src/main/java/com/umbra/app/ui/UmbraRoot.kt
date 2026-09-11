package com.umbra.app.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.umbra.app.data.repo.SessionPhase
import com.umbra.app.data.update.UpdateInfo
import com.umbra.app.di.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun UmbraRoot(container: AppContainer, pictureInPicture: Boolean = false) {
    val repo = container.chatRepository
    val phase by repo.phase.collectAsState()
    LaunchedEffect(phase) {
        if (phase == SessionPhase.READY) {
            repo.startRealtime()
            while (true) {
                repo.deleteExpiredMessages()
                delay(1000)
            }
        } else repo.stopRealtime()
    }

    // Проверка обновления — на каждый старт приложения (холодный и возврат
    // из фона; AppUpdater сам ограничивает частоту) и по кнопке из настроек.
    // Молча при ошибке и никогда в режиме «картинка в картинке».
    val updater = container.appUpdater
    val scope = rememberCoroutineScope()
    var updateUi by remember { mutableStateOf<UpdateUi?>(null) }
    var declinedVersion by remember { mutableStateOf<Int?>(null) }
    val updateState by updater.updateState.collectAsState()
    LaunchedEffect(updateState.checkedAtMillis) {
        if (!pictureInPicture) updateState.info
            ?.takeIf { it.versionCode != declinedVersion }
            ?.let { updateUi = UpdateUi.Offer(it) }
    }

    when (phase) {
        SessionPhase.LOGGED_OUT, SessionPhase.NEEDS_PROFILE -> AuthScreen(container, onDone = {})
        SessionPhase.READY -> key(repo.me()) {
            var openChatId by rememberSaveable { mutableStateOf<String?>(null) }
            var tab by rememberSaveable { mutableIntStateOf(0) }
            var minimizedCallId by rememberSaveable { mutableStateOf<String?>(null) }
            var showCommands by rememberSaveable { mutableStateOf(false) }
            val screens = rememberSaveableStateHolder()
            val call by repo.activeCall.collectAsState()
            BoxWithConstraints(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.K &&
                    (event.isCtrlPressed || event.isMetaPressed)
                ) {
                    showCommands = true
                    true
                } else false
            }.pointerInput(Unit) {
                var eligible = false
                var distance = 0f
                detectVerticalDragGestures(
                    onDragStart = { start ->
                        eligible = start.y <= 72.dp.toPx()
                        distance = 0f
                    },
                    onVerticalDrag = { change, amount ->
                        if (eligible && amount > 0f) {
                            distance += amount
                            change.consume()
                        }
                    },
                    onDragEnd = {
                        if (eligible && distance >= 96.dp.toPx()) showCommands = true
                    },
                    onDragCancel = { eligible = false },
                )
            }) {
                val twoPane = maxWidth >= 840.dp
                val chatId = openChatId
                Box(Modifier.fillMaxSize()) {
                    if (twoPane) screens.SaveableStateProvider("main") {
                        MainShell(
                            container = container,
                            tab = tab,
                            onTab = { tab = it },
                            onOpenChat = { openChatId = it },
                            selectedChatId = chatId,
                            twoPane = true,
                            onCloseChat = { openChatId = null },
                            sharedChatStateHolder = screens,
                            onCommands = { showCommands = true },
                        )
                    } else ScreenEntrance(chatId ?: "main", Modifier.fillMaxSize()) {
                        if (chatId != null) screens.SaveableStateProvider("chat:$chatId") {
                            ChatView(container, chatId) { openChatId = null }
                        } else screens.SaveableStateProvider("main") {
                            MainShell(
                                container, tab, { tab = it }, { openChatId = it },
                                onCommands = { showCommands = true },
                            )
                        }
                    }
                    call?.let { active ->
                        if (minimizedCallId == active.callId && !active.ringing && !pictureInPicture) {
                            Box(Modifier.align(Alignment.TopCenter)) {
                                MinimizedCallBar(container, active) { minimizedCallId = null }
                            }
                        } else {
                            CallScreen(container, active, pictureInPicture) { minimizedCallId = active.callId }
                        }
                    }
                }
            }
            if (showCommands) CommandCenter(
                repo = repo,
                onDismiss = { showCommands = false },
                onOpenChat = { openChatId = it; tab = 0 },
                onDestination = { destination ->
                    tab = destination
                    if (destination != 0) openChatId = null
                },
            )
        }
    }

    // Диалог обновления поверх любой фазы: обновление касается и экрана входа.
    updateUi?.let { state ->
        UpdateDialog(
            state = state,
            onCancel = {
                declinedVersion = updateState.info?.versionCode
                updateUi = null
            },
            onStart = { info ->
                scope.launch {
                    updateUi = UpdateUi.Downloading(info, 0)
                    runCatching { updater.download(info) { percent -> updateUi = UpdateUi.Downloading(info, percent) } }
                        .onSuccess { updateUi = UpdateUi.Ready(info, it) }
                        .onFailure { updateUi = UpdateUi.Failed(info, it.message ?: "Не удалось скачать обновление") }
                }
            },
            onInstall = { apk ->
                // Если разрешения «неизвестных источников» нет, откроются
                // настройки; диалог остаётся — после возврата нажать ещё раз.
                updater.install(apk)
            },
            onRetry = { info ->
                scope.launch {
                    updateUi = UpdateUi.Downloading(info, 0)
                    runCatching { updater.download(info) { percent -> updateUi = UpdateUi.Downloading(info, percent) } }
                        .onSuccess { updateUi = UpdateUi.Ready(info, it) }
                        .onFailure { updateUi = UpdateUi.Failed(info, it.message ?: "Не удалось скачать обновление") }
                }
            },
        )
    }
    (updateUi as? UpdateUi.Downloading)?.let { downloading ->
        Box(
            Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            ActivityIsland(Icons.Filled.Download, "Обновление ${downloading.percent}%")
        }
    }
}

/** Состояния диалога самообновления. */
private sealed interface UpdateUi {
    data class Offer(val info: UpdateInfo) : UpdateUi
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateUi
    data class Ready(val info: UpdateInfo, val apk: File) : UpdateUi
    data class Failed(val info: UpdateInfo, val message: String) : UpdateUi
}

@Composable
private fun UpdateDialog(
    state: UpdateUi,
    onCancel: () -> Unit,
    onStart: (UpdateInfo) -> Unit,
    onInstall: (File) -> Unit,
    onRetry: (UpdateInfo) -> Unit,
) {
    val info = when (state) {
        is UpdateUi.Offer -> state.info
        is UpdateUi.Downloading -> state.info
        is UpdateUi.Ready -> state.info
        is UpdateUi.Failed -> state.info
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Доступно обновление") },
        text = {
            when (state) {
                is UpdateUi.Offer -> Text(
                    "Версия ${info.versionName} (сборка ${info.versionCode}).\n" +
                        (info.notes.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""),
                )
                is UpdateUi.Downloading -> Column {
                    Text("Скачивается версия ${info.versionName}… ${state.percent}%")
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
                is UpdateUi.Ready -> Text("Обновление скачано. Нажмите «Установить» — откроется системный установщик.")
                is UpdateUi.Failed -> Text("${state.message}\n\nПовторить скачивание?")
            }
        },
        confirmButton = {
            when (state) {
                is UpdateUi.Offer -> TextButton({ onStart(info) }) { Text("Обновить") }
                is UpdateUi.Downloading -> TextButton({}, enabled = false) { Text("Скачивание…") }
                is UpdateUi.Ready -> TextButton({ onInstall(state.apk) }) { Text("Установить") }
                is UpdateUi.Failed -> TextButton({ onRetry(info) }) { Text("Повторить") }
            }
        },
        dismissButton = {
            when (state) {
                is UpdateUi.Downloading -> TextButton({}, enabled = false) { Text("Подождите") }
                is UpdateUi.Ready -> TextButton({ onInstall(state.apk) }) { Text("Установить") }
                else -> TextButton(onCancel) { Text("Позже") }
            }
        },
    )
}

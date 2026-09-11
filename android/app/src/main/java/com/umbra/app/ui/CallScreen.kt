package com.umbra.app.ui

import android.Manifest
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import android.content.Context
import android.content.pm.PackageManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.umbra.app.data.call.CallEngine
import com.umbra.app.data.call.CallPeer
import com.umbra.app.data.repo.ActiveCall
import com.umbra.app.data.repo.ChatRepository
import com.umbra.app.di.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Полноэкранный звонок: входящий вызов, ожидание ответа и сам разговор.
 *
 * Медиа живёт в [CallEngine] внутри AppContainer, поэтому экран можно свободно
 * пересобирать: поворот или свёртывание не обрывает разговор.
 */
@Composable
fun CallScreen(
    container: AppContainer,
    call: ActiveCall,
    pictureInPicture: Boolean = false,
    onMinimize: () -> Unit = {},
) {
    val repo = container.chatRepository
    val engine = container.callEngine
    val media by engine.media.collectAsState()
    val users by repo.userCache.collectAsState()
    val name = users[call.peerUserId]?.fullName() ?: call.peerName
    val tokens = com.umbra.app.ui.theme.LocalUmbraAlienTokens.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(call.callId) { mutableStateOf(false) }
    var error by remember(call.callId) { mutableStateOf<String?>(null) }
    var acceptAfterPermissions by remember(call.callId) { mutableStateOf(false) }

    fun act(status: String) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { repo.setCallStatus(status) }
            catch (e: Exception) { error = e.userMessage() }
            finally { busy = false }
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val micGranted = granted(context, Manifest.permission.RECORD_AUDIO)
        when {
            !micGranted -> error = "Разрешите доступ к микрофону, иначе собеседник вас не услышит."
            acceptAfterPermissions -> act("active")
        }
        acceptAfterPermissions = false
    }

    fun accept() {
        val missing = missingCallPermissions(context, call.video)
        if (missing.isEmpty()) act("active")
        else {
            acceptAfterPermissions = true
            permissions.launch(missing.toTypedArray())
        }
    }

    // Исходящий вызов: спрашиваем разрешения сразу, пока идёт гудок.
    LaunchedEffect(call.callId, call.incoming) {
        if (call.incoming) return@LaunchedEffect
        val missing = missingCallPermissions(context, call.video)
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    // История звонков обновляется, пока виден этот экран.
    LaunchedEffect(call.callId) {
        while (true) {
            runCatching { repo.fetchCalls() }
            delay(5000)
        }
    }

    // Неотвеченный вызов сам закрывается через минуту.
    LaunchedEffect(call.callId, call.ringing) {
        if (call.ringing) {
            delay(60_000)
            act("missed")
        }
    }

    // Звонок слышно: системная мелодия вызова без дополнительных разрешений.
    val ringing = call.incoming && call.ringing
    DisposableEffect(call.callId, ringing) {
        var ringtone: Ringtone? = null
        if (ringing) {
            ringtone = runCatching {
                RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            }.getOrNull()
            ringtone?.let { tone ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { tone.isLooping = true }
                runCatching { tone.play() }
            }
        }
        onDispose { ringtone?.let { tone -> runCatching { tone.stop() } } }
    }

    val startedAt = media?.startedAtMillis ?: 0L
    var elapsed by remember(startedAt) { mutableLongStateOf(0L) }
    LaunchedEffect(startedAt) {
        if (startedAt <= 0L) return@LaunchedEffect
        while (true) {
            elapsed = System.currentTimeMillis() - startedAt
            delay(1000)
        }
    }

    // Кнопка «назад» не должна выбрасывать из разговора случайным нажатием.
    BackHandler { if (!(call.incoming && call.ringing)) onMinimize() }

    val state = media
    val peers = state?.peers.orEmpty()
    // В группе показываем сетку плиток, а вдвоём — привычное видео на весь экран.
    val group = call.group || peers.size > 1
    val remotePeerId = peers.firstOrNull()?.userId ?: call.peerUserId
    val remoteVideoVisible = state?.remoteVideo == true && state.connected && !group
    val status = when {
        ringing -> if (call.video) "Входящий видеозвонок" else "Входящий звонок"
        call.ringing -> "Вызов…"
        state?.reconnecting == true -> "Восстанавливаю связь…"
        state?.connected == true -> callTimeText(elapsed)
        else -> "Соединение…"
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val landscape = maxWidth >= 560.dp && maxHeight < 480.dp
            val identity: @Composable () -> Unit = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(if (group) "Групповой звонок" else name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (state?.connected == true && !state.reconnecting)
                            (if (call.video) "Видеозвонок" else "Аудиозвонок") + " · " + status else status,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    // В alien-режиме состояние соединения читается ещё и шкалой: её видно без чтения текста.
                    if (tokens.enabled) Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AlienSignalMeter(
                            level = when {
                                state?.reconnecting == true -> 2
                                state?.connected == true -> 4
                                ringing || call.ringing -> 3
                                else -> 1
                            },
                        )
                        AlienHudLabel(
                            when {
                                state?.reconnecting == true -> "восстановление канала"
                                state?.connected == true -> "канал шифрован · стабилен"
                                ringing -> "входящий сигнал"
                                else -> "поиск канала"
                            },
                        )
                    }
                    state?.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                    error?.let { problem ->
                        Text(problem, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        TextButton({ repo.dismissCallLocally() }, enabled = !busy) { Text("Закрыть на этом устройстве") }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            val stage: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier.clip(RoundedCornerShape(28.dp)).holoEdge(cornerRadius = 28.dp), contentAlignment = Alignment.Center) {
                    when {
                        group && peers.isNotEmpty() -> PeerGrid(
                            repo = repo, engine = engine, peers = peers,
                            names = peers.associate { peer -> peer.userId to (users[peer.userId]?.fullName() ?: repo.titleFor(peer.userId)) },
                            modifier = Modifier.fillMaxSize(),
                        )
                        group -> CircularProgressIndicator()
                        remoteVideoVisible -> VideoSurface(engine, remotePeerId, false, Modifier.fillMaxSize())
                        else -> Surface(shape = RoundedCornerShape(56.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Box(Modifier.padding(if (landscape) 16.dp else 26.dp)) {
                                UserAvatar(repo, call.peerUserId, name, if (landscape) 80.dp else 112.dp)
                            }
                        }
                    }
                    if (state?.cameraOn == true) {
                        Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)
                            .size(if (landscape) 80.dp else 96.dp, if (landscape) 100.dp else 132.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .holoEdge(cornerRadius = 20.dp, width = 1.dp)) {
                            VideoSurface(engine, peerId = null, mirror = true, modifier = Modifier.fillMaxSize())
                            if (!pictureInPicture) FilledTonalIconButton(
                                onClick = { engine.switchCamera() }, enabled = !busy,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                            ) { Icon(Icons.Filled.Cameraswitch, "Переключить камеру") }
                        }
                    }
                }
            }
            val controls: @Composable () -> Unit = {
                CallControls(
                    incomingRinging = ringing, busy = busy, media = state,
                    onAccept = { accept() }, onDecline = { act("declined") }, onHangUp = { act("ended") },
                    onMic = { engine.toggleMic() }, onSpeaker = { engine.toggleSpeaker() }, onCamera = { engine.toggleCamera() },
                )
            }
            if (pictureInPicture) {
                stage(Modifier.fillMaxSize().padding(4.dp))
            } else if (landscape) {
                Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        identity()
                        stage(Modifier.weight(1f).fillMaxWidth())
                    }
                    Column(Modifier.width(256.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center) { controls() }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    identity()
                    stage(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp))
                    controls()
                }
            }
            if (!ringing && !pictureInPicture) {
                FilledTonalIconButton(
                    onClick = onMinimize,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) { Icon(Icons.Filled.KeyboardArrowDown, "Свернуть звонок") }
            }
        }
    }
}

/** Компактная панель: разговор продолжается, пока человек пользуется чатами. */
@Composable
fun MinimizedCallBar(container: AppContainer, call: ActiveCall, onRestore: () -> Unit) {
    val repo = container.chatRepository
    val media by container.callEngine.media.collectAsState()
    val users by repo.userCache.collectAsState()
    val name = users[call.peerUserId]?.fullName() ?: call.peerName.ifBlank { "Активный звонок" }
    val scope = rememberCoroutineScope()
    var ending by remember(call.callId) { mutableStateOf(false) }
    GlassPanel(
        modifier = Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth().widthIn(max = 560.dp).clip(RoundedCornerShape(20.dp))
            .clickable(enabled = !ending, onClick = onRestore),
        strong = true,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val tokens = com.umbra.app.ui.theme.LocalUmbraAlienTokens.current
            if (tokens.enabled) AlienSignalMeter(level = if (media?.connected == true) 4 else 2)
            Icon(
                if (call.video) Icons.Filled.Videocam else Icons.Filled.PhoneInTalk, null,
                tint = if (tokens.enabled) tokens.primary else androidx.compose.material3.LocalContentColor.current,
            )
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (tokens.enabled) AlienHudLabel(
                    if (media?.connected == true) "канал активен · вернуться" else "синхронизация канала…",
                ) else Text(
                    if (media?.connected == true) "Нажмите, чтобы вернуться" else "Соединение…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalIconButton(
                onClick = {
                    if (!ending) {
                        ending = true
                        scope.launch {
                            runCatching { repo.setCallStatus("ended") }
                            ending = false
                        }
                    }
                },
                enabled = !ending,
                colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Icon(Icons.Filled.CallEnd, "Завершить звонок") }
        }
    }
}

@Composable
private fun CallControls(
    incomingRinging: Boolean,
    busy: Boolean,
    media: com.umbra.app.data.call.CallMedia?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onMic: () -> Unit,
    onSpeaker: () -> Unit,
    onCamera: () -> Unit,
) {
    val endColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
    )
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AlienDivider(if (incomingRinging) "входящий канал" else "управление каналом")
        if (incomingRinging) {
            // Independent rows keep both actions visible with a large system font.
            Button(onClick = onAccept, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).alienGlow(strength = 1.3f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary),
                shape = RoundedCornerShape(22.dp)) {
                Icon(Icons.Filled.Call, null)
                Spacer(Modifier.width(10.dp))
                Text("Ответить")
            }
            Button(onClick = onDecline, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = endColors, shape = RoundedCornerShape(22.dp)) {
                Icon(Icons.Filled.CallEnd, null)
                Spacer(Modifier.width(10.dp))
                Text("Отклонить")
            }
        } else {
            if (media != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CallToggle(
                        title = "Микрофон", icon = if (media.micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        selected = media.micMuted, stateLabel = if (media.micMuted) "Выключен" else "Включён",
                        enabled = !busy, modifier = Modifier.weight(1f), onClick = onMic,
                    )
                    CallToggle(
                        title = "Динамик", icon = if (media.speakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeDown,
                        selected = media.speakerOn, stateLabel = if (media.speakerOn) "Включён" else "Выключен",
                        enabled = !busy, modifier = Modifier.weight(1f), onClick = onSpeaker,
                    )
                    if (media.video) CallToggle(
                        title = "Камера", icon = if (media.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                        selected = media.cameraOn, stateLabel = if (media.cameraOn) "Включена" else "Выключена",
                        enabled = !busy, modifier = Modifier.weight(1f), onClick = onCamera,
                    )
                }
            }
            Button(onClick = onHangUp, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = endColors, shape = RoundedCornerShape(22.dp)) {
                Icon(Icons.Filled.CallEnd, null)
                Spacer(Modifier.width(10.dp))
                Text("Завершить")
            }
        }
    }
}

@Composable
private fun CallToggle(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    stateLabel: String,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 88.dp).holoEdge(cornerRadius = 22.dp, width = 1.dp).semantics { stateDescription = stateLabel },
        shape = RoundedCornerShape(22.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(24.dp))
            Text(title, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Видеоповерхность WebRTC внутри Compose.
 *
 * peerId == null — своя камера, иначе видео конкретного собеседника: в групповом
 * звонке у каждого своя поверхность.
 *
 * При удалении из дерева обязательно отвязываем поток и освобождаем поверхность,
 * иначе при повороте экрана теряется EGL-контекст.
 */
@Composable
private fun VideoSurface(
    engine: CallEngine,
    peerId: String?,
    mirror: Boolean,
    modifier: Modifier = Modifier,
) {
    val egl = engine.eglContext() ?: return
    AndroidView(
        factory = { viewContext ->
            SurfaceViewRenderer(viewContext).apply {
                runCatching { init(egl, null) }
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setMirror(mirror)
                setZOrderMediaOverlay(peerId == null)
                if (peerId != null) engine.bindRemoteVideo(peerId, this) else engine.bindLocalVideo(this)
            }
        },
        modifier = modifier,
        onRelease = { view ->
            engine.unbindVideo(view)
            runCatching { view.release() }
        },
    )
}

/**
 * Групповой звонок: у каждого собеседника своя плитка. Участников не больше
 * четырёх, поэтому сетка укладывается в два столбца без прокрутки.
 */
@Composable
private fun PeerGrid(
    repo: ChatRepository,
    engine: CallEngine,
    peers: List<CallPeer>,
    names: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        peers.chunked(2).forEach { rowPeers ->
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowPeers.forEach { peer ->
                    PeerTile(
                        repo = repo,
                        engine = engine,
                        peer = peer,
                        name = names[peer.userId].orEmpty(),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                // Нечётный ряд: пустое место, чтобы плитка не растягивалась на весь ряд.
                if (rowPeers.size == 1 && peers.size > 2) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Плитка участника: видео, если оно есть, иначе аватарка и состояние связи. */
@Composable
private fun PeerTile(
    repo: ChatRepository,
    engine: CallEngine,
    peer: CallPeer,
    name: String,
    modifier: Modifier = Modifier,
) {
    val label = when {
        peer.reconnecting -> "Связь восстанавливается…"
        !peer.connected -> "Подключается…"
        else -> null
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (peer.video && peer.connected) {
                VideoSurface(engine, peerId = peer.userId, mirror = false, modifier = Modifier.fillMaxSize())
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    UserAvatar(repo, peer.userId, name, 64.dp)
                    Text(name, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
            label?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp),
                )
            }
        }
    }
}

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Микрофон обязателен, камера — только для видео, уведомления — с Android 13. */
internal fun missingCallPermissions(context: Context, video: Boolean): List<String> = buildList {
    if (!granted(context, Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
    if (video && !granted(context, Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !granted(context, Manifest.permission.POST_NOTIFICATIONS)
    ) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun callTimeText(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%d:%02d", minutes, seconds)
}

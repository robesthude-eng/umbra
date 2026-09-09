package com.umbra.app.ui

import android.Manifest
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.umbra.app.data.call.CallEngine
import com.umbra.app.data.repo.ActiveCall
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
fun CallScreen(container: AppContainer, call: ActiveCall) {
    val repo = container.chatRepository
    val engine = container.callEngine
    val media by engine.media.collectAsState()
    val users by repo.userCache.collectAsState()
    val name = users[call.peerUserId]?.fullName() ?: call.peerName
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
    BackHandler {}

    val state = media
    val remoteVideoVisible = state?.remoteVideo == true && state.connected
    val status = when {
        ringing -> if (call.video) "Входящий видеозвонок" else "Входящий звонок"
        call.ringing -> "Вызов…"
        state?.reconnecting == true -> "Восстанавливаю связь…"
        state?.connected == true -> callTimeText(elapsed)
        else -> "Соединение…"
    }

    Surface(
        color = if (remoteVideoVisible) Color.Black else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (remoteVideoVisible) {
                VideoSurface(engine, remote = true, mirror = false, modifier = Modifier.fillMaxSize())
            }
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(24.dp))
                if (!remoteVideoVisible) UserAvatar(repo, call.peerUserId, name, 96.dp)
                Text(
                    name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (remoteVideoVisible) Color.White else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    status,
                    color = if (remoteVideoVisible) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state?.problem?.let { problem ->
                    Text(problem, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                error?.let { problem ->
                    Text(problem, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    TextButton({ repo.dismissCallLocally() }, enabled = !busy) {
                        Text("Закрыть на этом устройстве")
                    }
                }
                if (busy) CircularProgressIndicator()
                Spacer(Modifier.weight(1f))
                if (state?.cameraOn == true) {
                    VideoSurface(
                        engine, remote = false, mirror = true,
                        modifier = Modifier.size(120.dp, 170.dp).clip(RoundedCornerShape(16.dp)),
                    )
                }
                CallControls(
                    incomingRinging = ringing,
                    busy = busy,
                    media = state,
                    onAccept = { accept() },
                    onDecline = { act("declined") },
                    onHangUp = { act("ended") },
                    onMic = { engine.toggleMic() },
                    onSpeaker = { engine.toggleSpeaker() },
                    onCamera = { engine.toggleCamera() },
                    onSwitchCamera = { engine.switchCamera() },
                )
            }
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
    onSwitchCamera: () -> Unit,
) {
    val endColors = IconButtonDefaults.filledIconButtonColors(
        containerColor = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (incomingRinging) {
            FilledIconButton(onAccept, enabled = !busy, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Filled.Call, "Принять звонок")
            }
            FilledIconButton(onDecline, enabled = !busy, modifier = Modifier.size(64.dp), colors = endColors) {
                Icon(Icons.Filled.CallEnd, "Отклонить звонок")
            }
            return@Row
        }
        if (media != null) {
            IconButton(onMic) {
                Icon(
                    if (media.micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    if (media.micMuted) "Включить микрофон" else "Выключить микрофон",
                )
            }
            IconButton(onSpeaker) {
                Icon(
                    if (media.speakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeDown,
                    if (media.speakerOn) "Выключить громкую связь" else "Включить громкую связь",
                )
            }
            if (media.video) {
                IconButton(onCamera) {
                    Icon(
                        if (media.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                        if (media.cameraOn) "Выключить камеру" else "Включить камеру",
                    )
                }
                if (media.cameraOn) IconButton(onSwitchCamera) {
                    Icon(Icons.Filled.Cameraswitch, "Переключить камеру")
                }
            }
        }
        FilledIconButton(onHangUp, enabled = !busy, modifier = Modifier.size(64.dp), colors = endColors) {
            Icon(Icons.Filled.CallEnd, "Завершить звонок")
        }
    }
}

/**
 * Видеоповерхность WebRTC внутри Compose.
 *
 * При удалении из дерева обязательно отвязываем поток и освобождаем поверхность,
 * иначе при повороте экрана теряется EGL-контекст.
 */
@Composable
private fun VideoSurface(
    engine: CallEngine,
    remote: Boolean,
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
                if (remote) engine.bindRemoteVideo(this) else engine.bindLocalVideo(this)
            }
        },
        modifier = modifier,
        onRelease = { view ->
            engine.unbindVideo(view)
            runCatching { view.release() }
        },
    )
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

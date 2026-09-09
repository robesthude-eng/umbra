package com.umbra.app.data.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.ContextCompat
import com.umbra.app.BuildConfig
import com.umbra.app.data.api.CallSignalEvent
import com.umbra.app.data.api.IceServerDto
import com.umbra.app.data.repo.ActiveCall
import com.umbra.app.data.repo.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Состояние медиачасти звонка для интерфейса. */
data class CallMedia(
    val callId: String,
    val video: Boolean,
    /** Звук пошёл в обе стороны (ICE соединился). */
    val connected: Boolean = false,
    val micMuted: Boolean = false,
    val speakerOn: Boolean = false,
    val cameraOn: Boolean = false,
    /** Пришёл видеопоток собеседника. */
    val remoteVideo: Boolean = false,
    /** Связь пропала, идёт восстановление; разговор ещё не завершён. */
    val reconnecting: Boolean = false,
    val startedAtMillis: Long = 0L,
    val problem: String? = null,
)

/**
 * Звонки на WebRTC: захват микрофона/камеры, передача медиа напрямую
 * между устройствами, сигналинг — через сервер Umbra (POST /v1/calls/{id}/signal
 * и WebSocket-событие call_signal). Медиапотоки через сервер не идут.
 *
 * Роли жёсткие: звонящий всегда делает offer, принимающий — answer, поэтому
 * столкновения предложений (glare) невозможны. Медиасессия поднимается
 * только после принятия вызова (status = active), чтобы не держать камеру
 * и микрофон во время звонка.
 *
 * Движок живёт в [com.umbra.app.di.AppContainer], а не в экране: поворот или
 * свёртывание приложения не рвёт разговор.
 */
class CallEngine(
    context: Context,
    private val repo: ChatRepository,
) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _media = MutableStateFlow<CallMedia?>(null)
    val media: StateFlow<CallMedia?> = _media.asStateFlow()

    private var factory: PeerConnectionFactory? = null
    @Volatile private var eglBase: EglBase? = null
    private var connection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudio: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideo: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var remoteVideo: VideoTrack? = null
    private var localSink: SurfaceViewRenderer? = null
    private var remoteSink: SurfaceViewRenderer? = null

    private var sessionCallId: String? = null
    private var peerId: String = ""
    private var outgoing = false
    private var remoteDescriptionSet = false
    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private var pendingOffer: Pair<String, JsonObject>? = null
    private var offerRetryJob: Job? = null
    private var watchdogJob: Job? = null
    private var restartJob: Job? = null
    private var reconnectDeadlineJob: Job? = null
    private var restartAttempts = 0
    private var lastRestartAtMillis = 0L
    private var awaitingAnswer = false
    private var iceServers: List<PeerConnection.IceServer> = emptyList()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var focusRequest: AudioFocusRequest? = null

    init {
        scope.launch {
            repo.activeCall.collect { call ->
                try {
                    if (call == null || call.ringing) stopSession() else startSession(call)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(e.message ?: "Не удалось начать разговор")
                }
            }
        }
        scope.launch {
            repo.callSignals.collect { signal ->
                try {
                    onSignal(signal)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(e.message ?: "Сбой согласования звонка")
                }
            }
        }
    }

    // ------------------------------------------------------------ сессия

    private suspend fun startSession(call: ActiveCall) {
        if (mutex.withLock { sessionCallId } == call.callId) return
        // Учётка TURN временная, поэтому берём её у сервера перед каждым звонком.
        val servers = fetchIceServers()
        val prepared = mutex.withLock {
            if (sessionCallId == call.callId) return
            releaseLocked()
            if (!granted(Manifest.permission.RECORD_AUDIO)) {
                _media.value = CallMedia(
                    call.callId, call.video,
                    problem = "Нет доступа к микрофону. Разрешите его в настройках и позвоните заново.",
                )
                return
            }
            sessionCallId = call.callId
            peerId = call.peerUserId
            outgoing = !call.incoming
            remoteDescriptionSet = false
            awaitingAnswer = false
            restartAttempts = 0
            lastRestartAtMillis = 0L
            iceServers = servers
            pendingRemoteIce.clear()

            val wantCamera = call.video && granted(Manifest.permission.CAMERA)
            // Нативная библиотека и EGL поднимаются ДО публикации состояния: экран
            // звонка создаёт SurfaceViewRenderer сразу и требует готовый EGL-контекст.
            ensureNative(context)
            if (eglBase == null) eglBase = EglBase.create()
            _media.value = CallMedia(
                callId = call.callId,
                video = call.video,
                cameraOn = wantCamera,
                speakerOn = call.video,
            )
            buildPeerConnectionLocked(wantCamera)
            startForegroundService(call)
            acquireAudio(call.video)
            registerNetworkCallback()
            true
        }
        if (!prepared) return

        watchdogJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (_media.value?.connected == false) fail("Связь не установилась. Проверьте интернет и позвоните ещё раз.")
        }

        if (outgoing) sendOffer() else applyPendingOffer()
    }

    /** Вызывать только под [mutex]. */
    private fun buildPeerConnectionLocked(withCamera: Boolean) {
        ensureNative(context)
        val egl = eglBase ?: EglBase.create().also { eglBase = it }
        val peerFactory = factory ?: PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
            .also { factory = it }

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // Непрерывный сбор кандидатов: переход Wi-Fi ↔ мобильный не рвёт звонок.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val peer = peerFactory.createPeerConnection(config, observer)
            ?: throw IllegalStateException("Не удалось подготовить звонок на этом устройстве")
        connection = peer

        val source = peerFactory.createAudioSource(MediaConstraints())
        audioSource = source
        val track = peerFactory.createAudioTrack(AUDIO_TRACK_ID, source)
        localAudio = track
        peer.addTrack(track, listOf(STREAM_ID))

        if (withCamera) startCameraLocked(peerFactory, egl, peer)
    }

    /** Вызывать только под [mutex]. */
    private fun startCameraLocked(
        peerFactory: PeerConnectionFactory,
        egl: EglBase,
        peer: PeerConnection,
    ) {
        val camera = createCapturer() ?: return
        val helper = SurfaceTextureHelper.create("UmbraCapture", egl.eglBaseContext)
        val source = peerFactory.createVideoSource(false)
        camera.initialize(helper, context, source.capturerObserver)
        runCatching { camera.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS) }
        val track = peerFactory.createVideoTrack(VIDEO_TRACK_ID, source)
        peer.addTrack(track, listOf(STREAM_ID))
        localSink?.let { runCatching { track.addSink(it) } }
        surfaceHelper = helper
        videoSource = source
        capturer = camera
        localVideo = track
    }

    private fun createCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        val front = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull() ?: return null
        return enumerator.createCapturer(front, null)
    }

    private suspend fun stopSession() {
        offerRetryJob?.cancel()
        watchdogJob?.cancel()
        restartJob?.cancel()
        reconnectDeadlineJob?.cancel()
        unregisterNetworkCallback()
        mutex.withLock {
            if (sessionCallId == null) return
            releaseLocked()
        }
        releaseAudio()
        stopForegroundService()
        _media.value = null
    }

    /** Вызывать только под [mutex]. Порядок важен: сначала соединение, потом источники. */
    private fun releaseLocked() {
        localSink?.let { sink -> runCatching { localVideo?.removeSink(sink) } }
        remoteSink?.let { sink -> runCatching { remoteVideo?.removeSink(sink) } }
        runCatching { connection?.close() }
        runCatching { connection?.dispose() }
        connection = null
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null
        runCatching { surfaceHelper?.dispose() }
        surfaceHelper = null
        runCatching { localVideo?.dispose() }
        localVideo = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { localAudio?.dispose() }
        localAudio = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        remoteVideo = null
        sessionCallId = null
        peerId = ""
        remoteDescriptionSet = false
        awaitingAnswer = false
        pendingRemoteIce.clear()
        pendingOffer = null
    }

    // ------------------------------------------------------------ сигналинг

    private suspend fun sendOffer(iceRestart: Boolean = false) {
        val peer = mutex.withLock { connection } ?: return
        val constraints = offerConstraints()
        // Перезапуск ICE: новые кандидаты для той же сессии, разговор не начинается заново.
        if (iceRestart) constraints.mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        val offer = peer.createOfferAwait(constraints)
        peer.setLocalDescriptionAwait(offer)
        mutex.withLock { awaitingAnswer = true }
        deliver("offer", sdpPayload(offer))
        // WebSocket собеседника мог отключиться в момент пересылки — повторяем offer,
        // пока не придёт answer. Сервер сигналы не буферизует, иначе звонок зависнет.
        offerRetryJob?.cancel()
        offerRetryJob = scope.launch {
            repeat(OFFER_RETRIES) {
                delay(OFFER_RETRY_MS)
                if (mutex.withLock { !awaitingAnswer || connection == null }) return@launch
                deliver("offer", sdpPayload(offer))
            }
        }
    }

    private suspend fun applyPendingOffer() {
        val (callId, payload) = mutex.withLock { pendingOffer } ?: return
        if (callId != sessionCallId) return
        handleOffer(payload)
    }

    private suspend fun onSignal(signal: CallSignalEvent) {
        val current = mutex.withLock {
            if (sessionCallId != signal.callId) {
                // Offer может обогнать поднятие сессии — придержим его.
                if (signal.kind == "offer") pendingOffer = signal.callId to signal.payload
                null
            } else sessionCallId
        } ?: return
        if (current != signal.callId) return
        when (signal.kind) {
            "offer" -> if (!outgoing) handleOffer(signal.payload)
            "answer" -> if (outgoing) handleAnswer(signal.payload)
            "ice" -> handleRemoteIce(signal.payload)
        }
    }

    private suspend fun handleOffer(payload: JsonObject) {
        val peer = mutex.withLock { connection } ?: return
        val sdp = payload["sdp"]?.jsonPrimitive?.content ?: return
        peer.setRemoteDescriptionAwait(SessionDescription(SessionDescription.Type.OFFER, sdp))
        drainRemoteIce(peer)
        val answer = peer.createAnswerAwait(offerConstraints())
        peer.setLocalDescriptionAwait(answer)
        deliver("answer", sdpPayload(answer))
    }

    private suspend fun handleAnswer(payload: JsonObject) {
        val peer = mutex.withLock { connection } ?: return
        // Ответ принимаем только на своё предложение, иначе это эхо старого offer.
        if (!mutex.withLock { awaitingAnswer }) return
        val sdp = payload["sdp"]?.jsonPrimitive?.content ?: return
        peer.setRemoteDescriptionAwait(SessionDescription(SessionDescription.Type.ANSWER, sdp))
        mutex.withLock { awaitingAnswer = false }
        offerRetryJob?.cancel()
        drainRemoteIce(peer)
    }

    private suspend fun handleRemoteIce(payload: JsonObject) {
        val candidate = IceCandidate(
            payload["sdpMid"]?.jsonPrimitive?.content ?: return,
            payload["sdpMLineIndex"]?.jsonPrimitive?.int ?: return,
            payload["candidate"]?.jsonPrimitive?.content ?: return,
        )
        val peer = mutex.withLock {
            if (!remoteDescriptionSet) {
                pendingRemoteIce += candidate
                null
            } else connection
        } ?: return
        runCatching { peer.addIceCandidate(candidate) }
    }

    private suspend fun drainRemoteIce(peer: PeerConnection) {
        val queued = mutex.withLock {
            remoteDescriptionSet = true
            val copy = pendingRemoteIce.toList()
            pendingRemoteIce.clear()
            copy
        }
        for (candidate in queued) runCatching { peer.addIceCandidate(candidate) }
    }

    /** Отправка SDP: без неё звонок не состоится, поэтому ошибка видна пользователю. */
    private suspend fun deliver(kind: String, payload: JsonObject) {
        val callId = mutex.withLock { sessionCallId } ?: return
        val to = mutex.withLock { peerId }
        try {
            repo.sendCallSignal(callId, to, kind, payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Не удалось согласовать звонок. Проверьте связь и позвоните заново.")
        }
    }

    /** ICE-кандидатов много: потеря одного не фатальна, ошибку не показываем. */
    private fun deliverIce(candidate: IceCandidate) {
        scope.launch {
            val callId = mutex.withLock { sessionCallId } ?: return@launch
            val to = mutex.withLock { peerId }
            val payload = buildJsonObject {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            }
            runCatching { repo.sendCallSignal(callId, to, "ice", payload) }
        }
    }

    private fun sdpPayload(description: SessionDescription): JsonObject = buildJsonObject {
        put("type", description.type.canonicalForm())
        put("sdp", description.description)
    }

    private fun offerConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    }

    // ------------------------------------------------------------ управление из UI

    /** Микрофон: трек отключается локально, пересогласование не нужно. */
    fun toggleMic() {
        scope.launch {
            mutex.withLock {
                val muted = !(_media.value?.micMuted ?: false)
                runCatching { localAudio?.setEnabled(!muted) }
                _media.value = _media.value?.copy(micMuted = muted)
            }
        }
    }

    fun toggleSpeaker() {
        val on = !(_media.value?.speakerOn ?: false)
        setSpeaker(on)
        _media.value = _media.value?.copy(speakerOn = on)
    }

    /** Камера в аудиозвонке не включается: для этого нужно пересогласование потоков. */
    fun toggleCamera() {
        scope.launch {
            mutex.withLock {
                val track = localVideo ?: return@withLock
                val on = !(_media.value?.cameraOn ?: false)
                runCatching { track.setEnabled(on) }
                if (on) runCatching { capturer?.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS) }
                else runCatching { capturer?.stopCapture() }
                _media.value = _media.value?.copy(cameraOn = on)
            }
        }
    }

    fun switchCamera() {
        scope.launch {
            mutex.withLock { (capturer as? CameraVideoCapturer)?.let { runCatching { it.switchCamera(null) } } }
        }
    }

    /** Контекст EGL нужен экрану звонка для SurfaceViewRenderer.init. */
    fun eglContext(): EglBase.Context? = eglBase?.eglBaseContext

    fun bindLocalVideo(view: SurfaceViewRenderer) {
        scope.launch {
            mutex.withLock {
                localSink?.let { old -> runCatching { localVideo?.removeSink(old) } }
                localSink = view
                runCatching { localVideo?.addSink(view) }
            }
        }
    }

    fun bindRemoteVideo(view: SurfaceViewRenderer) {
        scope.launch {
            mutex.withLock {
                remoteSink?.let { old -> runCatching { remoteVideo?.removeSink(old) } }
                remoteSink = view
                runCatching { remoteVideo?.addSink(view) }
            }
        }
    }

    fun unbindVideo(view: SurfaceViewRenderer) {
        scope.launch {
            mutex.withLock {
                if (localSink === view) {
                    runCatching { localVideo?.removeSink(view) }
                    localSink = null
                }
                if (remoteSink === view) {
                    runCatching { remoteVideo?.removeSink(view) }
                    remoteSink = null
                }
            }
        }
    }

    // ------------------------------------------------------------ звук устройства

    private fun audioManager(): AudioManager? = context.getSystemService(AudioManager::class.java)

    private fun acquireAudio(videoCall: Boolean) {
        val manager = audioManager() ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        focusRequest = request
        runCatching { manager.requestAudioFocus(request) }
        runCatching { manager.mode = AudioManager.MODE_IN_COMMUNICATION }
        setSpeaker(videoCall)
    }

    private fun setSpeaker(on: Boolean) {
        val manager = audioManager() ?: return
        // setCommunicationDevice появился в API 31; поддерживаем и старые телефоны.
        @Suppress("DEPRECATION")
        runCatching { manager.isSpeakerphoneOn = on }
    }

    private fun releaseAudio() {
        val manager = audioManager() ?: return
        focusRequest?.let { request -> runCatching { manager.abandonAudioFocusRequest(request) } }
        focusRequest = null
        setSpeaker(false)
        runCatching { manager.mode = AudioManager.MODE_NORMAL }
    }

    private fun startForegroundService(call: ActiveCall) {
        val intent = Intent(context, CallService::class.java)
            .putExtra(CallService.EXTRA_TITLE, call.peerName)
            .putExtra(CallService.EXTRA_VIDEO, call.video)
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    private fun stopForegroundService() {
        runCatching { context.stopService(Intent(context, CallService::class.java)) }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * ICE-серверы: сначала спрашиваем сервер (GET /v1/turn) — учётка TURN там
     * временная и в APK не хранится. Если сервер недоступен, берём адреса сборки.
     */
    private suspend fun fetchIceServers(): List<PeerConnection.IceServer> {
        val remote: List<IceServerDto> = try {
            withTimeoutOrNull(ICE_FETCH_TIMEOUT_MS) { repo.iceServers() }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val servers = remote.mapNotNull { it.toIceServer() }
        return servers.ifEmpty { buildConfigIceServers() }
    }

    private fun IceServerDto.toIceServer(): PeerConnection.IceServer? {
        val addresses = urls.filter { it.isNotBlank() }
        if (addresses.isEmpty()) return null
        return PeerConnection.IceServer.builder(addresses)
            .setUsername(username)
            .setPassword(credential)
            .createIceServer()
    }

    private fun buildConfigIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()
        BuildConfig.STUN_URL.takeIf { it.isNotBlank() }?.let {
            servers += PeerConnection.IceServer.builder(it).createIceServer()
        }
        BuildConfig.TURN_URL.takeIf { it.isNotBlank() }?.let {
            servers += PeerConnection.IceServer.builder(it)
                .setUsername(BuildConfig.TURN_USERNAME)
                .setPassword(BuildConfig.TURN_PASSWORD)
                .createIceServer()
        }
        return servers
    }

    // ------------------------------------------------------------ смена сети

    /**
     * Wi-Fi ↔ мобильный интернет: вместо обрыва пересобираем маршрут. Новое
     * предложение делает только звонящий, иначе оба пришлют offer одновременно.
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val state = _media.value ?: return
                if (state.connected || state.reconnecting) scheduleIceRestart()
            }

            override fun onLost(network: Network) {
                markReconnecting()
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        val manager = context.getSystemService(ConnectivityManager::class.java)
        runCatching { manager?.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    /** Показываем «восстанавливаю связь» и даём себе полминуты на возврат. */
    private fun markReconnecting() {
        val current = _media.value ?: return
        if (!current.connected || current.reconnecting) return
        _media.value = current.copy(reconnecting = true)
        reconnectDeadlineJob?.cancel()
        reconnectDeadlineJob = scope.launch {
            delay(RECONNECT_TIMEOUT_MS)
            if (_media.value?.reconnecting == true) {
                fail("Связь не вернулась. Проверьте интернет и позвоните ещё раз.")
            }
        }
    }

    private fun scheduleIceRestart() {
        restartJob?.cancel()
        restartJob = scope.launch {
            val allowed = mutex.withLock {
                val now = System.currentTimeMillis()
                when {
                    sessionCallId == null || connection == null -> false
                    restartAttempts >= MAX_ICE_RESTARTS -> false
                    now - lastRestartAtMillis < RESTART_MIN_INTERVAL_MS -> false
                    else -> {
                        restartAttempts += 1
                        lastRestartAtMillis = now
                        true
                    }
                }
            }
            if (!allowed) return@launch
            markReconnecting()
            // Принимающий ждёт новый offer: перезапуск начинает только звонящий.
            if (!outgoing) return@launch
            delay(ICE_RESTART_DELAY_MS)
            try {
                sendOffer(iceRestart = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Следующая попытка придёт со следующим событием сети или ICE.
            }
        }
    }

    /** Ошибка показывается на экране звонка, затем звонок завершается. */
    private fun fail(problem: String) {
        _media.value = _media.value?.copy(problem = problem)
        scope.launch {
            delay(FAIL_LINGER_MS)
            runCatching { repo.setCallStatus("ended") }
            repo.dismissCallLocally()
        }
    }

    fun close() {
        scope.launch { stopSession() }
        scope.cancel()
        runCatching { factory?.dispose() }
        factory = null
        runCatching { eglBase?.release() }
        eglBase = null
    }

    // ------------------------------------------------------------ WebRTC callbacks

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    watchdogJob?.cancel()
                    reconnectDeadlineJob?.cancel()
                    val current = _media.value ?: return
                    if (!current.connected || current.reconnecting) {
                        _media.value = current.copy(
                            connected = true,
                            reconnecting = false,
                            // После переподключения таймер разговора продолжается.
                            startedAtMillis = if (current.startedAtMillis > 0L) current.startedAtMillis
                            else System.currentTimeMillis(),
                            problem = null,
                        )
                    }
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    markReconnecting()
                    scheduleIceRestart()
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    // Разговор уже шёл — пробуем пересобрать маршрут, а не рвать звонок.
                    if (_media.value?.connected == true) {
                        markReconnecting()
                        scheduleIceRestart()
                    } else {
                        fail("Связь оборвалась. Если это повторяется, нужен TURN-сервер.")
                    }
                }
                else -> {}
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {}

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate?.let { deliverIce(it) }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

        override fun onAddStream(stream: MediaStream?) {}

        override fun onRemoveStream(stream: MediaStream?) {}

        override fun onDataChannel(channel: DataChannel?) {}

        override fun onRenegotiationNeeded() {}

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track = receiver?.track() ?: return
            if (track.kind() != MediaStreamTrack.VIDEO_TRACK_KIND) return
            val video = track as? VideoTrack ?: return
            scope.launch {
                mutex.withLock {
                    remoteVideo = video
                    remoteSink?.let { sink -> runCatching { video.addSink(sink) } }
                }
                _media.value = _media.value?.copy(remoteVideo = true)
            }
        }
    }

    // ------------------------------------------------------------ SDP в корутинах

    private suspend fun PeerConnection.createOfferAwait(constraints: MediaConstraints): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            createOffer(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (description == null) continuation.resumeWithException(IllegalStateException("Пустой offer"))
                    else continuation.resume(description)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    continuation.resumeWithException(IllegalStateException(error ?: "Не удалось подготовить звонок"))
                }
                override fun onSetFailure(error: String?) {}
            }, constraints)
        }

    private suspend fun PeerConnection.createAnswerAwait(constraints: MediaConstraints): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            createAnswer(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (description == null) continuation.resumeWithException(IllegalStateException("Пустой answer"))
                    else continuation.resume(description)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    continuation.resumeWithException(IllegalStateException(error ?: "Не удалось принять звонок"))
                }
                override fun onSetFailure(error: String?) {}
            }, constraints)
        }

    private suspend fun PeerConnection.setLocalDescriptionAwait(description: SessionDescription) =
        suspendCancellableCoroutine { continuation ->
            setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onSetSuccess() { continuation.resume(Unit) }
                override fun onCreateFailure(error: String?) {}
                override fun onSetFailure(error: String?) {
                    continuation.resumeWithException(IllegalStateException(error ?: "Ошибка локального SDP"))
                }
            }, description)
        }

    private suspend fun PeerConnection.setRemoteDescriptionAwait(description: SessionDescription) =
        suspendCancellableCoroutine { continuation ->
            setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onSetSuccess() { continuation.resume(Unit) }
                override fun onCreateFailure(error: String?) {}
                override fun onSetFailure(error: String?) {
                    continuation.resumeWithException(IllegalStateException(error ?: "Ошибка SDP собеседника"))
                }
            }, description)
        }

    private companion object {
        const val STREAM_ID = "umbra"
        const val AUDIO_TRACK_ID = "umbra_audio"
        const val VIDEO_TRACK_ID = "umbra_video"
        const val CAPTURE_WIDTH = 1280
        const val CAPTURE_HEIGHT = 720
        const val CAPTURE_FPS = 30
        const val CONNECT_TIMEOUT_MS = 45_000L
        const val OFFER_RETRY_MS = 3_000L
        // Телефон, разбуженный push-уведомлением, подключается не сразу:
        // повторяем offer почти весь таймаут, иначе получится «ответил, а тишина».
        const val OFFER_RETRIES = 12
        const val FAIL_LINGER_MS = 2_500L
        const val ICE_FETCH_TIMEOUT_MS = 4_000L
        const val ICE_RESTART_DELAY_MS = 800L
        const val RESTART_MIN_INTERVAL_MS = 4_000L
        const val RECONNECT_TIMEOUT_MS = 30_000L
        const val MAX_ICE_RESTARTS = 5

        @Volatile
        private var nativeReady = false

        /** Нативная библиотека грузится один раз на процесс и только перед первым звонком. */
        fun ensureNative(context: Context) {
            if (nativeReady) return
            synchronized(this) {
                if (nativeReady) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions()
                )
                nativeReady = true
            }
        }
    }
}

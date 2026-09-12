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
import android.util.Log
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

/** Один собеседник в звонке: в группе таких до трёх. */
data class CallPeer(
    val userId: String,
    val connected: Boolean = false,
    /** Пришло видео этого собеседника. */
    val video: Boolean = false,
    val reconnecting: Boolean = false,
)

/** Состояние медиачасти звонка для интерфейса. */
data class CallMedia(
    val callId: String,
    val video: Boolean,
    /** Хотя бы с одним собеседником звук пошёл в обе стороны. */
    val connected: Boolean = false,
    val micMuted: Boolean = false,
    val speakerOn: Boolean = false,
    val cameraOn: Boolean = false,
    /** Пришёл хотя бы один чужой видеопоток. */
    val remoteVideo: Boolean = false,
    /** Связь пропала, идёт восстановление; разговор ещё не завершён. */
    val reconnecting: Boolean = false,
    val startedAtMillis: Long = 0L,
    val problem: String? = null,
    /** Собеседники в порядке приглашения. */
    val peers: List<CallPeer> = emptyList(),
)

/**
 * Звонки на WebRTC: захват микрофона/камеры, передача медиа напрямую
 * между устройствами, сигналинг — через сервер Umbra (POST /v1/calls/{id}/signal
 * и WebSocket-событие call_signal). Медиапотоки через сервер не идут.
 *
 * Групповой звонок собирается mesh-схемой: с каждым собеседником своё
 * соединение и свой набор ICE-кандидатов, общие — только микрофон
 * и камера. Поэтому потолок — четыре участника (три соединения на телефон):
 * дальше нужен серверный микшер (SFU), которого у Umbra нет.
 *
 * Кто делает offer, решается заранее, чтобы предложения не столкнулись (glare):
 * с инициатором звонка — всегда он, между двумя приглашёнными — тот, чей id
 * меньше. Медиасессия поднимается только после принятия вызова
 * (status = active), чтобы не держать камеру и микрофон во время гудка.
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

    // Общее на весь звонок: микрофон и камера захватываются один раз,
    // а треки добавляются в каждое соединение mesh-сети.
    private var audioSource: AudioSource? = null
    private var localAudio: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideo: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var localSink: SurfaceViewRenderer? = null

    /** Соединения по собеседникам, в порядке приглашения. */
    private val sessions = LinkedHashMap<String, PeerSession>()

    private var sessionCallId: String? = null
    private var selfId = ""
    private var callerId = ""
    private var outgoing = false
    /**
     * Сигналы, обогнавшие поднятие сессии, придерживаем по отправителю.
     * Раньше сохранялся только offer, а answer и ICE-кандидаты выбрасывались:
     * если собеседник успевал ответить раньше, соединение не собиралось.
     */
    private val pendingSignals = mutableMapOf<String, PendingSignals>()
    private var watchdogJob: Job? = null
    private var reconnectDeadlineJob: Job? = null
    private var iceServers: List<PeerConnection.IceServer> = emptyList()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var focusRequest: AudioFocusRequest? = null

    /** Придержанные сигналы одного собеседника до старта сессии. */
    private class PendingSignals(val callId: String) {
        var offer: JsonObject? = null
        var answer: JsonObject? = null
        val ice = mutableListOf<JsonObject>()
    }

    /** Одно соединение mesh-сети: своё согласование и свой видеопоток. */
    private inner class PeerSession(val peerId: String) {
        var connection: PeerConnection? = null
        var remoteVideo: VideoTrack? = null
        var sink: SurfaceViewRenderer? = null
        var remoteDescriptionSet = false
        var awaitingAnswer = false
        var connected = false
        var reconnecting = false
        val pendingIce = mutableListOf<IceCandidate>()
        var offerRetryJob: Job? = null
        var restartJob: Job? = null
        var restartAttempts = 0
        var lastRestartAtMillis = 0L

        fun snapshot() = CallPeer(
            userId = peerId,
            connected = connected,
            video = remoteVideo != null,
            reconnecting = reconnecting,
        )

        /** Вызывать только под [mutex]. */
        fun releaseLocked() {
            offerRetryJob?.cancel()
            restartJob?.cancel()
            sink?.let { view -> runCatching { remoteVideo?.removeSink(view) } }
            runCatching { connection?.close() }
            runCatching { connection?.dispose() }
            connection = null
            remoteVideo = null
            sink = null
            pendingIce.clear()
        }
    }

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
        if (mutex.withLock { sessionCallId } == call.callId) {
            // Состав звонка мог измениться: кто-то вышел или только что принял вызов.
            syncPeers(call)
            return
        }
        // Учётка TURN временная, поэтому берём её у сервера перед каждым звонком.
        val servers = fetchIceServers()
        val fresh = mutableListOf<PeerSession>()
        val prepared = mutex.withLock {
            if (sessionCallId == call.callId) return
            releaseAllLocked()
            if (!granted(Manifest.permission.RECORD_AUDIO)) {
                _media.value = CallMedia(
                    call.callId, call.video,
                    problem = "Нет доступа к микрофону. Разрешите его в настройках и позвоните заново.",
                )
                return
            }
            sessionCallId = call.callId
            selfId = call.selfUserId
            callerId = call.callerUserId
            outgoing = !call.incoming
            iceServers = servers

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
            buildLocalMediaLocked(wantCamera)
            for (peerId in call.peers) fresh += openSessionLocked(peerId)
            refreshMediaLocked()
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

        for (session in fresh) startNegotiation(session)
    }

    /** Кто-то присоединился или вышел: держим mesh в соответствии со списком участников. */
    private suspend fun syncPeers(call: ActiveCall) {
        val fresh = mutableListOf<PeerSession>()
        mutex.withLock {
            if (sessionCallId != call.callId) return
            val wanted = call.peers.toSet()
            for (peerId in sessions.keys.toList()) {
                if (peerId in wanted) continue
                sessions.remove(peerId)?.releaseLocked()
            }
            for (peerId in call.peers) {
                if (sessions.containsKey(peerId)) continue
                fresh += openSessionLocked(peerId)
            }
            refreshMediaLocked()
        }
        for (session in fresh) startNegotiation(session)
    }

    /** Первый шаг согласования: предлагаем мы или ждём offer собеседника. */
    private suspend fun startNegotiation(session: PeerSession) {
        // Сначала разбираем придержанные сигналы: собеседник мог ответить
        // раньше нас, и его offer уже лежит в очереди.
        val answered = applyPendingSignals(session)
        if (!answered && shouldOffer(session.peerId)) sendOffer(session)
    }

    /**
     * Кто отправляет offer в паре. Инициатор звонка предлагает всем, между двумя
     * приглашёнными предлагает тот, чей id меньше: правило одинаково на обоих
     * телефонах, поэтому встречных предложений не бывает.
     */
    private fun shouldOffer(peerId: String): Boolean = when {
        selfId.isEmpty() -> outgoing
        selfId == callerId -> true
        peerId == callerId -> false
        else -> selfId < peerId
    }

    /** Вызывать только под [mutex]. */
    private fun buildLocalMediaLocked(withCamera: Boolean) {
        ensureNative(context)
        val egl = eglBase ?: EglBase.create().also { eglBase = it }
        val peerFactory = factory ?: PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
            .also { factory = it }

        val source = peerFactory.createAudioSource(MediaConstraints())
        audioSource = source
        localAudio = peerFactory.createAudioTrack(AUDIO_TRACK_ID, source)

        if (withCamera) startCameraLocked(peerFactory, egl)
    }

    /** Вызывать только под [mutex]. */
    private fun startCameraLocked(peerFactory: PeerConnectionFactory, egl: EglBase) {
        val camera = createCapturer() ?: return
        val helper = SurfaceTextureHelper.create("UmbraCapture", egl.eglBaseContext)
        val source = peerFactory.createVideoSource(false)
        camera.initialize(helper, context, source.capturerObserver)
        runCatching { camera.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS) }
        val track = peerFactory.createVideoTrack(VIDEO_TRACK_ID, source)
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

    /** Вызывать только под [mutex]. Соединение с одним собеседником mesh-сети. */
    private fun openSessionLocked(peerId: String): PeerSession {
        val session = PeerSession(peerId)
        sessions[peerId] = session
        val peerFactory = factory ?: return session
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // Непрерывный сбор кандидатов: переход Wi-Fi ↔ мобильный не рвёт звонок.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val peer = peerFactory.createPeerConnection(config, observerFor(session))
            ?: throw IllegalStateException("Не удалось подготовить звонок на этом устройстве")
        session.connection = peer
        // Микрофон и камера общие: один источник раздаётся во все соединения.
        localAudio?.let { peer.addTrack(it, listOf(STREAM_ID)) }
        localVideo?.let { peer.addTrack(it, listOf(STREAM_ID)) }
        return session
    }

    private suspend fun stopSession() {
        mutex.withLock {
            if (sessionCallId == null && sessions.isEmpty()) return
            releaseAllLocked()
            _media.value = null
        }
        stopForegroundService()
        releaseAudio()
        unregisterNetworkCallback()
    }

    /** Вызывать только под [mutex]. */
    private fun releaseAllLocked() {
        watchdogJob?.cancel()
        watchdogJob = null
        reconnectDeadlineJob?.cancel()
        reconnectDeadlineJob = null
        pendingSignals.clear()
        for (session in sessions.values) session.releaseLocked()
        sessions.clear()
        sessionCallId = null
        selfId = ""
        callerId = ""
        outgoing = false
        iceServers = emptyList()

        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null
        localSink?.let { view -> runCatching { localVideo?.removeSink(view) } }
        localVideo = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { surfaceHelper?.dispose() }
        surfaceHelper = null
        localAudio = null
        runCatching { audioSource?.dispose() }
        audioSource = null
    }

    /**
     * Собирает состояние для экрана из отдельных соединений: звонок считается
     * состоявшимся, пока на связи хотя бы один собеседник.
     * Вызывать только под [mutex].
     */
    private fun refreshMediaLocked() {
        val current = _media.value ?: return
        val peers = sessions.values.map { it.snapshot() }
        val connected = peers.any { it.connected }
        _media.value = current.copy(
            peers = peers,
            connected = connected,
            remoteVideo = peers.any { it.video },
            reconnecting = peers.any { it.reconnecting },
            // После переподключения и выхода участников таймер разговора не сбрасывается.
            startedAtMillis = when {
                current.startedAtMillis > 0L -> current.startedAtMillis
                connected -> System.currentTimeMillis()
                else -> 0L
            },
        )
    }

    // -------------------------------------------------------- согласование

    private suspend fun sendOffer(session: PeerSession, iceRestart: Boolean = false) {
        val peer = mutex.withLock { if (sessions[session.peerId] === session) session.connection else null } ?: return
        val offer = peer.createOfferAwait(offerConstraints(iceRestart))
        peer.setLocalDescriptionAwait(offer)
        mutex.withLock { session.awaitingAnswer = true }
        deliver("offer", sdpPayload(offer), session.peerId)
        // WebSocket мог не донести offer (телефон только проснулся от push):
        // повторяем предложение, пока не придёт ответ.
        val retry = scope.launch {
            var attempt = 0
            while (attempt < OFFER_RETRIES) {
                delay(OFFER_RETRY_MS)
                val waiting = mutex.withLock { sessions[session.peerId] === session && session.awaitingAnswer }
                if (!waiting) return@launch
                deliver("offer", sdpPayload(offer), session.peerId)
                attempt++
            }
        }
        mutex.withLock {
            session.offerRetryJob?.cancel()
            session.offerRetryJob = retry
        }
    }

    /**
     * Сигналы могли прийти раньше, чем поднялась медиасессия: разбираем всё
     * придержанное — offer, ответ и ICE-кандидаты.
     *
     * @return true, если обработали offer собеседника и уже ответили answer.
     */
    private suspend fun applyPendingSignals(session: PeerSession): Boolean {
        val held = mutex.withLock {
            val pending = pendingSignals.remove(session.peerId) ?: return@withLock null
            if (pending.callId != sessionCallId) return@withLock null
            pending
        } ?: return false
        val offer = held.offer
        if (offer != null) handleOffer(session, offer) else held.answer?.let { handleAnswer(session, it) }
        for (candidate in held.ice) handleRemoteIce(session, candidate)
        return offer != null
    }

    private suspend fun onSignal(signal: CallSignalEvent) {
        val from = signal.from
        if (from.isEmpty()) return
        val session = mutex.withLock {
            if (sessionCallId != signal.callId) {
                // Сессия ещё не поднята (идёт гудок) — придерживаем сигнал.
                bufferSignalLocked(from, signal)
                return@withLock null
            }
            // В группе собеседник может принять вызов позже нас — поднимаем соединение по его сигналу.
            sessions[from] ?: if (signal.kind == "offer") openSessionLocked(from) else null
        } ?: return
        when (signal.kind) {
            "offer" -> handleOffer(session, signal.payload)
            "answer" -> handleAnswer(session, signal.payload)
            "ice" -> handleRemoteIce(session, signal.payload)
        }
    }

    /** Вызывать только под [mutex]. */
    private fun bufferSignalLocked(from: String, signal: CallSignalEvent) {
        // Сигналы прошлых звонков не нужны: держим только текущий callId.
        for (peer in pendingSignals.filterValues { it.callId != signal.callId }.keys) pendingSignals.remove(peer)
        val held = pendingSignals.getOrPut(from) { PendingSignals(signal.callId) }
        when (signal.kind) {
            "offer" -> held.offer = signal.payload
            "answer" -> held.answer = signal.payload
            // Кандидатов может быть много: держим разумный запас.
            "ice" -> if (held.ice.size < 64) held.ice.add(signal.payload)
        }
    }

    private suspend fun handleOffer(session: PeerSession, payload: JsonObject) {
        val peer = mutex.withLock { if (sessions[session.peerId] === session) session.connection else null } ?: return
        val sdp = payload["sdp"]?.jsonPrimitive?.content ?: return
        peer.setRemoteDescriptionAwait(SessionDescription(SessionDescription.Type.OFFER, sdp))
        drainRemoteIce(session, peer)
        val answer = peer.createAnswerAwait(offerConstraints())
        peer.setLocalDescriptionAwait(answer)
        deliver("answer", sdpPayload(answer), session.peerId)
    }

    private suspend fun handleAnswer(session: PeerSession, payload: JsonObject) {
        val peer = mutex.withLock { if (sessions[session.peerId] === session) session.connection else null } ?: return
        // Ответ принимаем только на своё предложение, иначе это эхо старого offer.
        if (!mutex.withLock { session.awaitingAnswer }) return
        val sdp = payload["sdp"]?.jsonPrimitive?.content ?: return
        peer.setRemoteDescriptionAwait(SessionDescription(SessionDescription.Type.ANSWER, sdp))
        mutex.withLock {
            session.awaitingAnswer = false
            session.offerRetryJob?.cancel()
            session.offerRetryJob = null
        }
        drainRemoteIce(session, peer)
    }

    private suspend fun handleRemoteIce(session: PeerSession, payload: JsonObject) {
        val candidate = IceCandidate(
            payload["sdpMid"]?.jsonPrimitive?.content ?: return,
            payload["sdpMLineIndex"]?.jsonPrimitive?.int ?: return,
            payload["candidate"]?.jsonPrimitive?.content ?: return,
        )
        val peer = mutex.withLock {
            if (sessions[session.peerId] !== session) return
            if (!session.remoteDescriptionSet) {
                session.pendingIce += candidate
                null
            } else session.connection
        } ?: return
        runCatching { peer.addIceCandidate(candidate) }
    }

    private suspend fun drainRemoteIce(session: PeerSession, peer: PeerConnection) {
        val queued = mutex.withLock {
            session.remoteDescriptionSet = true
            val copy = session.pendingIce.toList()
            session.pendingIce.clear()
            copy
        }
        for (candidate in queued) runCatching { peer.addIceCandidate(candidate) }
    }

    /** Отправка SDP: без неё звонок не состоится, поэтому ошибка видна пользователю. */
    private suspend fun deliver(kind: String, payload: JsonObject, to: String) {
        val callId = mutex.withLock { sessionCallId } ?: return
        try {
            repo.sendCallSignal(callId, to, kind, payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // В группе срывается только эта пара; остальные продолжают разговор.
            dropPeer(to, "Не удалось согласовать звонок. Проверьте связь и позвоните заново.")
        }
    }

    /** ICE-кандидатов много: потеря одного не фатальна, ошибку не показываем. */
    private fun deliverIce(candidate: IceCandidate, to: String) {
        scope.launch {
            val callId = mutex.withLock { sessionCallId } ?: return@launch
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

    private fun offerConstraints(iceRestart: Boolean = false) = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
    }

    // ------------------------------------------------------------ управление из UI

    /** Микрофон: трек отключается локально сразу во всех соединениях. */
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

    /** У каждого собеседника своя плитка, поэтому видео привязывается по id. */
    fun bindRemoteVideo(peerId: String, view: SurfaceViewRenderer) {
        scope.launch {
            mutex.withLock {
                val session = sessions[peerId] ?: return@withLock
                session.sink?.let { old -> runCatching { session.remoteVideo?.removeSink(old) } }
                session.sink = view
                runCatching { session.remoteVideo?.addSink(view) }
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
                for (session in sessions.values) {
                    if (session.sink !== view) continue
                    runCatching { session.remoteVideo?.removeSink(view) }
                    session.sink = null
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
        // Служба переднего плана держит микрофон и камеру, пока приложение
        // свёрнуто. Раньше отказ системы молча проглатывался, и разговор глох
        // при сворачивании. Теперь пробуем обычный старт и пишем в лог.
        val started = runCatching { ContextCompat.startForegroundService(context, intent) }
        val error = started.exceptionOrNull() ?: return
        Log.w("CallEngine", "foreground call service rejected", error)
        runCatching { context.startService(intent) }
            .onFailure { Log.w("CallEngine", "call service start failed", it) }
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
     * Wi-Fi ↔ мобильный интернет: вместо обрыва пересобираем маршрут с каждым
     * собеседником отдельно. Новое предложение делает тот же, кто предлагал
     * изначально, иначе оба пришлют offer одновременно.
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val state = _media.value ?: return
                if (!state.connected && !state.reconnecting) return
                scope.launch {
                    for (session in mutex.withLock { sessions.values.toList() }) scheduleIceRestart(session)
                }
            }

            override fun onLost(network: Network) {
                scope.launch {
                    for (session in mutex.withLock { sessions.values.toList() }) markReconnecting(session)
                }
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

    /** Показываем «восстанавливаю связь» рядом с конкретным собеседником. */
    private fun markReconnecting(session: PeerSession) {
        scope.launch {
            val changed = mutex.withLock {
                if (sessions[session.peerId] !== session) return@withLock false
                if (!session.connected || session.reconnecting) return@withLock false
                session.reconnecting = true
                refreshMediaLocked()
                true
            }
            if (changed) scheduleReconnectDeadline()
        }
    }

    /** Полминуты на возврат: кто не вернулся — выбывает, остальные продолжают разговор. */
    private fun scheduleReconnectDeadline() {
        reconnectDeadlineJob?.cancel()
        reconnectDeadlineJob = scope.launch {
            delay(RECONNECT_TIMEOUT_MS)
            val stalled = mutex.withLock { sessions.values.filter { it.reconnecting }.map { it.peerId } }
            for (peerId in stalled) {
                dropPeer(peerId, "Связь не вернулась. Проверьте интернет и позвоните ещё раз.")
            }
        }
    }

    private fun scheduleIceRestart(session: PeerSession) {
        val job = scope.launch {
            val allowed = mutex.withLock {
                val now = System.currentTimeMillis()
                when {
                    sessionCallId == null -> false
                    sessions[session.peerId] !== session || session.connection == null -> false
                    session.restartAttempts >= MAX_ICE_RESTARTS -> false
                    now - session.lastRestartAtMillis < RESTART_MIN_INTERVAL_MS -> false
                    else -> {
                        session.restartAttempts += 1
                        session.lastRestartAtMillis = now
                        true
                    }
                }
            }
            if (!allowed) return@launch
            markReconnecting(session)
            // Принимающая сторона ждёт новый offer, перезапуск начинает предлагавший.
            if (!shouldOffer(session.peerId)) return@launch
            delay(ICE_RESTART_DELAY_MS)
            try {
                sendOffer(session, iceRestart = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Следующая попытка придёт со следующим событием сети или ICE.
            }
        }
        scope.launch {
            mutex.withLock {
                if (sessions[session.peerId] !== session) {
                    job.cancel()
                    return@withLock
                }
                session.restartJob?.takeIf { it !== job }?.cancel()
                session.restartJob = job
            }
        }
    }

    /**
     * Один собеседник выпал: в группе убираем его плитку и говорим дальше,
     * а в разговоре вдвоём это означает конец звонка.
     */
    private fun dropPeer(peerId: String, problem: String) {
        scope.launch {
            val lastOne = mutex.withLock {
                val session = sessions.remove(peerId) ?: return@withLock false
                session.releaseLocked()
                refreshMediaLocked()
                sessions.isEmpty()
            }
            if (lastOne) fail(problem)
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

    /** У каждого соединения свой наблюдатель: события привязаны к собеседнику. */
    private fun observerFor(session: PeerSession): PeerConnection.Observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> markConnected(session)

                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    markReconnecting(session)
                    scheduleIceRestart(session)
                }

                PeerConnection.IceConnectionState.FAILED -> {
                    if (session.connected) {
                        // Разговор уже шёл — пробуем пересобрать маршрут, а не рвать связь.
                        markReconnecting(session)
                        scheduleIceRestart(session)
                    } else {
                        dropPeer(session.peerId, "Связь оборвалась. Если это повторяется, нужен TURN-сервер.")
                    }
                }

                else -> {}
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {}

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate?.let { deliverIce(it, session.peerId) }
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
                    if (sessions[session.peerId] !== session) return@withLock
                    session.remoteVideo = video
                    session.sink?.let { sink -> runCatching { video.addSink(sink) } }
                    refreshMediaLocked()
                }
            }
        }
    }

    /** Звук пошёл в обе стороны с этим собеседником. */
    private fun markConnected(session: PeerSession) {
        watchdogJob?.cancel()
        scope.launch {
            mutex.withLock {
                if (sessions[session.peerId] !== session) return@withLock
                if (session.connected && !session.reconnecting) return@withLock
                session.connected = true
                session.reconnecting = false
                session.restartAttempts = 0
                _media.value = _media.value?.copy(problem = null)
                refreshMediaLocked()
                if (sessions.values.none { it.reconnecting }) {
                    reconnectDeadlineJob?.cancel()
                    reconnectDeadlineJob = null
                }
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

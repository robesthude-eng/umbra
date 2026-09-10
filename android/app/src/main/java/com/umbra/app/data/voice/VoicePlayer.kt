package com.umbra.app.data.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Состояние проигрывателя для интерфейса. */
data class VoicePlayback(
    /** Совпадает со stableId сообщения. */
    val key: String,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val loading: Boolean,
    /** Скорость проигрывания: 1f, 1.5f или 2f. */
    val speed: Float = 1f,
)

/**
 * Проигрывание голосовых сообщений через MediaPlayer из Android SDK.
 *
 * Активна одна запись: новое сообщение останавливает предыдущее. Файл берётся
 * из локального каталога (своя запись до отправки) либо запрашивается через
 * [resolve] — репозиторий отдаёт файл из кэша или скачивает его с сервера.
 */
class VoicePlayer(private val resolve: suspend (String) -> File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<VoicePlayback?>(null)
    val state: StateFlow<VoicePlayback?> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var currentKey: String? = null

    /** Скорость сохраняется между записями: выбрал 2x — играют так все. */
    private var speed = 1f
    private var speedTouched = false

    /**
     * Играет или ставит на паузу сообщение [key].
     * [localPath] — своя запись, ещё не отправленная на сервер.
     */
    suspend fun toggle(key: String, mediaId: String?, localPath: String?, durationMs: Long) {
        val handled = mutex.withLock {
            val active = player
            // Файл для этого же сообщения уже готовится: повторное нажатие
            // не должно начинать вторую загрузку и второй плеер.
            if (currentKey == key && active == null) return@withLock true
            if (currentKey != key || active == null) return@withLock false
            if (active.isPlaying) {
                active.pause()
                ticker?.cancel()
                ticker = null
                _state.value = _state.value?.copy(positionMs = active.currentPosition.toLong(), playing = false)
            } else {
                // Повторное нажатие в конце записи начинает её заново.
                if (active.currentPosition >= durationOf(active, durationMs) - REPLAY_EDGE_MS) active.seekTo(0)
                active.start()
                applySpeedLocked(active)
                _state.value = _state.value?.copy(playing = true, loading = false, speed = speed)
                startTickerLocked()
            }
            true
        }
        if (handled) return

        mutex.withLock {
            releaseLocked()
            currentKey = key
            _state.value = VoicePlayback(key, 0, durationMs, playing = false, loading = true, speed = speed)
        }
        val file = try {
            withContext(Dispatchers.IO) {
                localPath?.let(::File)?.takeIf { it.isFile && it.length() > 0 }
                    ?: resolve(mediaId?.takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException("Голосовое сообщение недоступно"))
            }
        } catch (e: Throwable) {
            mutex.withLock {
                if (currentKey == key) {
                    currentKey = null
                    _state.value = null
                }
            }
            throw e
        }

        // Плеер готовим вне мьютекса: prepare() блокирующий, и под замком он
        // подвешивал все остальные нажатия на время подготовки.
        val created = try {
            withContext(Dispatchers.IO) { prepared(file) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            mutex.withLock {
                if (currentKey == key) {
                    currentKey = null
                    _state.value = null
                }
            }
            throw IllegalStateException("Не удалось воспроизвести запись. Попробуйте ещё раз.")
        }

        mutex.withLock {
            // Пока файл готовился, пользователь мог нажать на другое сообщение.
            if (currentKey != key) {
                runCatching { created.release() }
                return
            }
            val total = durationOf(created, durationMs)
            created.setOnCompletionListener {
                ticker?.cancel()
                ticker = null
                _state.value = _state.value?.copy(playing = false, positionMs = total)
            }
            // Ошибка прошивки или битый файл: раньше пузырь навсегда
            // оставался «играющим», а MediaPlayer держал файл до перезапуска.
            created.setOnErrorListener { _, _, _ ->
                onPlaybackError(key)
                true
            }
            player = created
            created.start()
            applySpeedLocked(created)
            _state.value = VoicePlayback(key, 0, total, playing = true, loading = false, speed = speed)
            startTickerLocked()
        }
    }

    /** Создание и подготовка MediaPlayer — блокирующая часть без общего замка. */
    private fun prepared(file: File): MediaPlayer {
        val created = MediaPlayer()
        try {
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            created.setDataSource(file.absolutePath)
            created.prepare()
        } catch (e: Throwable) {
            runCatching { created.release() }
            throw e
        }
        return created
    }

    /** Ошибка проигрывания: освобождаем плеер и гасим состояние пузыря. */
    private fun onPlaybackError(key: String) {
        scope.launch {
            mutex.withLock {
                if (currentKey != key) return@withLock
                releaseLocked()
                _state.value = null
            }
        }
    }

    suspend fun stop() = mutex.withLock {
        releaseLocked()
        _state.value = null
    }

    /** Остановка без ожидания: для onDispose, где нельзя вызывать suspend-функции. */
    fun stopAsync() {
        scope.launch { runCatching { stop() } }
    }

    /**
     * Перемотка активной записи. Ключ проверяется: пока файл готовится или
     * играет другое сообщение, ползунок чужого пузыря ничего не двигает.
     */
    suspend fun seekTo(key: String, positionMs: Long) = mutex.withLock {
        val active = player ?: return@withLock
        if (currentKey != key) return@withLock
        val total = durationOf(active, _state.value?.durationMs ?: 0L)
        val target = positionMs.coerceIn(0L, total)
        runCatching { active.seekTo(target.toInt()) }
        _state.value = _state.value?.copy(positionMs = target)
    }

    /**
     * Скорость проигрывания.
     *
     * MediaPlayer.setPlaybackParams на части прошивок сам запускает запись,
     * поэтому на паузе значение только запоминается и применяется при старте.
     */
    suspend fun setSpeed(value: Float) = mutex.withLock {
        speed = value.coerceIn(MIN_SPEED, MAX_SPEED)
        speedTouched = true
        val active = player
        if (active != null && active.isPlaying) applySpeedLocked(active)
        _state.value = _state.value?.copy(speed = speed)
    }

    /** Вызывать только под [mutex]. */
    private fun startTickerLocked() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                delay(TICK_MS)
                val finished = mutex.withLock {
                    val active = player ?: return@withLock true
                    if (!active.isPlaying) return@withLock false
                    _state.value = _state.value?.copy(positionMs = active.currentPosition.toLong())
                    false
                }
                if (finished) return@launch
            }
        }
    }

    /**
     * Вызывать только под [mutex] и только на играющем плеере: PlaybackParams
     * до prepare бросает исключение, поэтому вызов защищён runCatching.
     */
    private fun applySpeedLocked(active: MediaPlayer) {
        if (!speedTouched && speed == 1f) return
        runCatching { active.playbackParams = active.playbackParams.setSpeed(speed) }
    }

    /** Вызывать только под [mutex]. */
    private fun releaseLocked() {
        ticker?.cancel()
        ticker = null
        player?.let { active ->
            runCatching { active.setOnCompletionListener(null) }
            runCatching { active.setOnErrorListener(null) }
            runCatching { if (active.isPlaying) active.stop() }
            runCatching { active.reset() }
            runCatching { active.release() }
        }
        player = null
        currentKey = null
    }

    private fun durationOf(active: MediaPlayer, fallbackMs: Long): Long =
        runCatching { active.duration.toLong() }.getOrNull()?.takeIf { it > 0 } ?: fallbackMs

    fun close() {
        // Сначала тикер и обратные вызовы, иначе они стреляют в освобождённый плеер.
        ticker?.cancel()
        ticker = null
        scope.cancel()
        player?.let { active ->
            runCatching { active.setOnCompletionListener(null) }
            runCatching { active.setOnErrorListener(null) }
            runCatching { active.reset() }
            runCatching { active.release() }
        }
        player = null
        currentKey = null
        _state.value = null
    }

    private companion object {
        const val TICK_MS = 200L
        const val REPLAY_EDGE_MS = 150L
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2f
    }
}

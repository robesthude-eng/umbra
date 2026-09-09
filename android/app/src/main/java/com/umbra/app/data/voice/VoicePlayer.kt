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

    /**
     * Играет или ставит на паузу сообщение [key].
     * [localPath] — своя запись, ещё не отправленная на сервер.
     */
    suspend fun toggle(key: String, mediaId: String?, localPath: String?, durationMs: Long) {
        val handled = mutex.withLock {
            val active = player
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
                _state.value = _state.value?.copy(playing = true, loading = false)
                startTickerLocked()
            }
            true
        }
        if (handled) return

        mutex.withLock {
            releaseLocked()
            currentKey = key
            _state.value = VoicePlayback(key, 0, durationMs, playing = false, loading = true)
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

        mutex.withLock {
            // Пока файл загружался, пользователь мог нажать на другое сообщение.
            if (currentKey != key) return
            val created = MediaPlayer()
            try {
                created.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                created.setDataSource(file.absolutePath)
                withContext(Dispatchers.IO) { created.prepare() }
            } catch (e: Throwable) {
                runCatching { created.release() }
                currentKey = null
                _state.value = null
                if (e is CancellationException) throw e
                throw IllegalStateException("Не удалось воспроизвести запись. Попробуйте ещё раз.")
            }
            val total = durationOf(created, durationMs)
            created.setOnCompletionListener {
                ticker?.cancel()
                ticker = null
                _state.value = _state.value?.copy(playing = false, positionMs = total)
            }
            player = created
            created.start()
            _state.value = VoicePlayback(key, 0, total, playing = true, loading = false)
            startTickerLocked()
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

    /** Вызывать только под [mutex]. */
    private fun releaseLocked() {
        ticker?.cancel()
        ticker = null
        player?.let { active ->
            runCatching { active.setOnCompletionListener(null) }
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
        scope.cancel()
        runCatching { player?.release() }
        player = null
        currentKey = null
        _state.value = null
    }

    private companion object {
        const val TICK_MS = 200L
        const val REPLAY_EDGE_MS = 150L
    }
}

package com.umbra.app.data.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import java.util.UUID

/** Готовая запись: файл лежит в приватном каталоге приложения. */
data class VoiceRecording(val file: File, val durationMs: Long, val mime: String)

/** Состояние записи для интерфейса. */
data class VoiceRecordingState(
    val elapsedMs: Long,
    /** Громкость 0..1 для индикатора. */
    val level: Float,
    /** Запись остановилась сама: достигнут предел длительности. */
    val limitReached: Boolean,
)

/**
 * Запись голосовых сообщений через MediaRecorder: AAC в контейнере MP4
 * (`audio/mp4`, .m4a). Кодек и контейнер входят в Android SDK, новых
 * зависимостей не требуется.
 *
 * Разрешение RECORD_AUDIO запрашивает интерфейс перед вызовом [start].
 * Все переходы состояния защищены мьютексом: MediaRecorder нельзя
 * использовать из нескольких потоков одновременно.
 */
class VoiceRecorder(private val context: Context) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<VoiceRecordingState?>(null)

    /** null — запись не идёт. */
    val state: StateFlow<VoiceRecordingState?> = _state.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var startedAtMs = 0L
    private var ticker: Job? = null
    private var completed: VoiceRecording? = null
    private var smoothedLevel = 0f
    @Volatile private var limitReached = false

    /** Начинает запись. Бросает [IllegalStateException], если микрофон недоступен. */
    suspend fun start() = mutex.withLock {
        check(recorder == null && completed == null) { "Запись уже идёт" }
        val dir = withContext(Dispatchers.IO) { File(context.filesDir, DIR).apply { mkdirs() } }
        val file = File(dir, "rec-${UUID.randomUUID()}.m4a")
        val created = newRecorder()
        try {
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioChannels(1)
            created.setAudioSamplingRate(SAMPLE_RATE)
            created.setAudioEncodingBitRate(BIT_RATE)
            // Страховка на случай, если экран закрыли, не остановив запись.
            created.setMaxDuration(MAX_DURATION_MS.toInt())
            created.setOutputFile(file)
            created.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) limitReached = true
            }
            withContext(Dispatchers.IO) { created.prepare() }
            created.start()
        } catch (e: Throwable) {
            runCatching { created.reset() }
            runCatching { created.release() }
            withContext(NonCancellable) { runCatching { file.delete() } }
            if (e is CancellationException) throw e
            throw IllegalStateException("Не удалось начать запись. Проверьте доступ к микрофону.")
        }
        recorder = created
        target = file
        startedAtMs = SystemClock.elapsedRealtime()
        smoothedLevel = 0f
        limitReached = false
        _state.value = VoiceRecordingState(0, 0f, false)
        ticker = scope.launch { trackProgress() }
    }

    /**
     * Останавливает запись и отдаёт файл. Бросает [IllegalStateException],
     * если запись слишком короткая или не сохранилась.
     */
    suspend fun finish(): VoiceRecording {
        val result = mutex.withLock {
            ticker?.cancel()
            ticker = null
            val done = completed ?: finishLocked()
            completed = null
            limitReached = false
            _state.value = null
            done
        } ?: throw IllegalStateException("Запись не удалась. Попробуйте ещё раз.")
        if (result.durationMs < MIN_DURATION_MS) {
            withContext(NonCancellable) { runCatching { result.file.delete() } }
            throw IllegalStateException("Слишком короткая запись. Запишите чуть дольше.")
        }
        return result
    }

    /** Прерывает запись и удаляет файл. */
    suspend fun cancel() {
        val leftover = mutex.withLock {
            ticker?.cancel()
            ticker = null
            val done = completed ?: finishLocked()
            completed = null
            limitReached = false
            _state.value = null
            done
        }
        if (leftover != null) withContext(NonCancellable) { runCatching { leftover.file.delete() } }
    }

    /** Отмена без ожидания: для onDispose, где нельзя вызывать suspend-функции. */
    fun cancelAsync() {
        scope.launch { runCatching { cancel() } }
    }

    private suspend fun trackProgress() {
        while (true) {
            delay(TICK_MS)
            val finished = mutex.withLock {
                val active = recorder ?: return@withLock true
                val amplitude = runCatching { active.maxAmplitude }.getOrDefault(0)
                smoothedLevel = smoothedLevel * 0.65f + (amplitude / AMPLITUDE_SCALE).coerceIn(0f, 1f) * 0.35f
                val elapsed = SystemClock.elapsedRealtime() - startedAtMs
                if (limitReached || elapsed >= MAX_DURATION_MS) {
                    val done = finishLocked()
                    completed = done
                    _state.value = if (done == null) null else VoiceRecordingState(done.durationMs, 0f, true)
                    true
                } else {
                    _state.value = VoiceRecordingState(elapsed, smoothedLevel, false)
                    false
                }
            }
            if (finished) return
        }
    }

    /** Останавливает MediaRecorder. Вызывать только под [mutex]. */
    private fun finishLocked(): VoiceRecording? {
        val active = recorder ?: return null
        val file = target
        recorder = null
        target = null
        val elapsed = SystemClock.elapsedRealtime() - startedAtMs
        // stop() бросает RuntimeException, если во файл не попало ни одного кадра.
        val stopped = runCatching { active.stop() }.isSuccess
        runCatching { active.reset() }
        runCatching { active.release() }
        if (!stopped || file == null || !file.isFile || file.length() <= 0L) {
            runCatching { file?.delete() }
            return null
        }
        return VoiceRecording(file, elapsed.coerceAtMost(MAX_DURATION_MS), MIME)
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    fun close() {
        scope.cancel()
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { target?.delete() }
        target = null
        runCatching { completed?.file?.delete() }
        completed = null
        _state.value = null
    }

    companion object {
        /** Предел длительности одного голосового сообщения. */
        const val MAX_DURATION_MS = 5 * 60 * 1000L

        /** Короче — это случайное нажатие, а не сообщение. */
        const val MIN_DURATION_MS = 700L

        const val MIME = "audio/mp4"

        private const val DIR = "voice"
        private const val SAMPLE_RATE = 44_100
        private const val BIT_RATE = 64_000
        private const val TICK_MS = 100L
        private const val AMPLITUDE_SCALE = 22_000f
    }
}

package com.umbra.app.data.diag

import android.content.Context
import java.io.File
import java.time.Instant

/**
 * Компактный журнал диагностики клиента.
 *
 * Пишет только технические события: тег, момент, класс и текст ошибки, верхние
 * строки стека. Содержимое сообщений и переписки не записывается никогда.
 * Файл ограничен последними MAX_LINES строками, чтобы не рос бесконечно.
 * Отправляется разработчику кнопкой в «Настройки → Диагностика».
 */
object DiagLog {
    private const val MAX_LINES = 300
    private const val FILE_NAME = "diag.log"

    @Volatile
    private var file: File? = null

    /** Вызывается один раз при старте приложения. */
    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
    }

    @Synchronized
    fun log(tag: String, error: Throwable? = null, note: String = "") {
        val target = file ?: return
        runCatching {
            val line = buildString {
                append(Instant.now())
                append(" [").append(tag).append("]")
                if (note.isNotBlank()) append(" ").append(note)
                if (error != null) {
                    append(": ").append(error.javaClass.simpleName)
                    error.message?.takeIf { it.isNotBlank() }?.let {
                        append(" «").append(it.take(160)).append("»")
                    }
                    error.stackTrace.take(3).forEach { frame ->
                        append(" · ").append(frame.toString().take(140))
                    }
                }
            }
            val existing = runCatching { target.readLines() }.getOrNull().orEmpty()
            target.writeText((existing + line).takeLast(MAX_LINES).joinToString("\n"))
        }
    }

    /** Текст журнала для отправки. */
    fun text(): String =
        runCatching { file?.readText() }.getOrNull()?.ifBlank { "журнал пуст" } ?: "журнал недоступен"

    @Synchronized
    fun clear() {
        runCatching { file?.writeText("") }
    }
}

package com.umbra.app.data.repo

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Разбор серверных меток времени ISO-8601.
 *
 * Сервер отдаёт время в двух видах: свежесозданные значения — с суффиксом «Z»
 * (UTC), а значения, прочитанные из базы, — со смещением зоны процесса
 * (например «+03:00», если сервер запущен в Europe/Moscow). Instant.parse на
 * Android понимает только «Z», и одна такая метка роняла всю синхронизацию:
 * история не догружалась, а очередь отправки не отправлялась. Теперь любой
 * допустимый ISO-8601 вариант разбирается корректно.
 */
object IsoTime {
    fun parse(text: String): Instant = try {
        Instant.parse(text)
    } catch (_: DateTimeParseException) {
        OffsetDateTime.parse(text).toInstant()
    }
}

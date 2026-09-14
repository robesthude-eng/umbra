package com.umbra.app.data.api

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.buffer

/**
 * Обёртка над телом запроса, которая сообщает, сколько байт уже ушло в сеть.
 *
 * OkHttp может повторить запрос (редирект, переподключение), поэтому счётчик
 * сбрасывается в начале каждой записи — иначе прогресс уехал бы за 100%.
 * Колбек вызывается в потоке отправки и не должен блокировать.
 */
class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun isOneShot(): Boolean = delegate.isOneShot()

    override fun writeTo(sink: BufferedSink) {
        val total = runCatching { contentLength() }.getOrDefault(-1L)
        // Buffer -> это подсчёт длины, а не реальная отправка: прогресс не трогаем.
        if (sink is Buffer) {
            delegate.writeTo(sink)
            return
        }
        var sent = 0L
        onProgress(0L, total)
        val counting = object : ForwardingSink(sink) {
            private var lastReported = 0L

            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                sent += byteCount
                // Не чаще чем раз в 64 КБ: лишние вызовы только греют UI.
                if (sent - lastReported >= 64 * 1024 || (total > 0 && sent >= total)) {
                    lastReported = sent
                    onProgress(sent, total)
                }
            }
        }
        val buffered = (counting as Sink).buffer()
        delegate.writeTo(buffered)
        buffered.flush()
        onProgress(if (total > 0) total else sent, total)
    }
}

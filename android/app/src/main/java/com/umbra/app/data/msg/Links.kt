package com.umbra.app.data.msg

/**
 * Поиск ссылки в тексте сообщения для карточки предпросмотра.
 * Берётся только первая ссылка: стена из карточек в одном сообщении не нужна.
 */
object Links {
    private val REGEX = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** Первая http(s)-ссылка или null. Хвостовая пунктуация обрезается. */
    fun first(text: String): String? {
        if (text.isBlank()) return null
        val raw = REGEX.find(text)?.value ?: return null
        val cleaned = raw.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '»', '”')
        return cleaned.takeIf { it.length > 10 && it.length <= 2048 }
    }
}

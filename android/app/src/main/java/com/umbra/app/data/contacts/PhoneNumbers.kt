package com.umbra.app.data.contacts

import android.content.ContentResolver
import android.provider.ContactsContract
import java.security.MessageDigest

/** Контакт из телефонной книги: имя + нормализованный номер E.164. */
data class DeviceContact(val name: String, val phone: String)

/**
 * Нормализация и хэширование номеров телефонов.
 *
 * Правила ДОЛЖНЫ совпадать с серверными (internal/httpapi/phone.go):
 * от расхождения контакты не найдут друг друга. Менять только синхронно.
 *
 *  1. Убираем все символы, кроме цифр и ведущего '+'.
 *  2. "00" в начале заменяется на "+" (международный формат).
 *  3. Без "+": 11 цифр, начинающихся с '8', трактуются как РФ → "+7...";
 *     10 цифр — как РФ без кода страны → "+7..."; иначе считаем, что код
 *     страны уже есть, и просто добавляем "+".
 *  4. Результат обязан соответствовать E.164: '+' + 7..15 цифр, первая не '0'.
 */
object PhoneNumbers {

    /** Приводит номер к E.164 или возвращает null, если номер некорректен. */
    fun normalize(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        var hasPlus = s[0] == '+'
        var digits = buildString {
            for ((i, c) in s.withIndex()) {
                when {
                    c in '0'..'9' -> append(c)
                    i == 0 && c == '+' -> Unit // уже учли hasPlus
                    c == ' ' || c == '-' || c == '(' || c == ')' || c == '.' || c == '\u00A0' -> Unit
                    else -> return null
                }
            }
        }
        if (digits.startsWith("00")) {
            digits = digits.substring(2)
            hasPlus = true
        }
        if (!hasPlus) {
            when {
                digits.length == 11 && digits[0] == '8' -> digits = "7" + digits.substring(1)
                digits.length == 10 -> digits = "7" + digits
            }
        }
        if (digits.length < 7 || digits.length > 15 || digits[0] == '0') return null
        return "+$digits"
    }

    /** SHA-256(hex) от нормализованного номера с доменным префиксом — как на сервере. */
    fun hash(normalized: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("umbra-phone:$normalized".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Читает телефонную книгу (нужно разрешение READ_CONTACTS), нормализует
     * номера и дедуплицирует по номеру. Номера, которые не удалось
     * нормализовать, пропускаются.
     */
    fun readAll(resolver: ContentResolver): List<DeviceContact> {
        val out = LinkedHashMap<String, DeviceContact>()
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
        ) ?: return emptyList()
        cursor.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val number = if (numberIdx >= 0) it.getString(numberIdx) else null
                val normalized = number?.let(::normalize) ?: continue
                if (out.containsKey(normalized)) continue
                val name = if (nameIdx >= 0) it.getString(nameIdx)?.trim().orEmpty() else ""
                out[normalized] = DeviceContact(
                    name = name.ifEmpty { normalized },
                    phone = normalized,
                )
            }
        }
        return out.values.toList()
    }
}

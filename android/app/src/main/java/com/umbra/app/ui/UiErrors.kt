package com.umbra.app.ui

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import java.io.IOException

/** Only known server messages are translated; backend diagnostics never leak into UI. */
internal fun Exception.userMessage(): String {
    if (this is CancellationException) throw this
    if (this is HttpException) {
        val reason = runCatching {
            response()?.errorBody()?.string()?.let { Json.parseToJsonElement(it).jsonObject["error"]?.jsonPrimitive?.content }
        }.getOrNull()
        return when (reason) {
            "неверный код", "invalid code" -> "Неверный код. Проверьте 6 цифр и попробуйте снова."
            "код не найден или истёк. Запросите новый" -> "Срок действия кода истёк. Запросите новый код."
            "слишком много попыток. Запросите новый код" -> "Попытки закончились. Запросите новый код."
            "подтверждение по коду не настроено", "получатель кодов не настроен" -> "Вход ещё не настроен. Обратитесь к владельцу Umbra."
            "не удалось отправить код" -> "Не удалось передать код в Telegram. Попробуйте ещё раз."
            "invalid phone" -> "Проверьте номер телефона и код страны."
            "invalid username" -> "Никнейм: 3–32 латинские буквы, цифры или знак _."
            "username already taken" -> "Этот никнейм уже занят. Выберите другой."
            "invalid name" -> "Имя должно содержать от 1 до 64 символов."
            "invalid last_name" -> "Фамилия — не более 64 символов."
            "invalid title" -> "Слишком длинное название группы. Сократите его."
            else -> when (code()) {
                400 -> "Проверьте введённые данные."
                401 -> "Сессия истекла. Войдите в аккаунт снова."
                403 -> "Нет прав для этого действия. Обратитесь к владельцу группы."
                404 -> "Пользователь или чат больше недоступен."
                409 -> "Данные изменились. Обновите экран и повторите."
                413 -> "Файл превышает допустимый размер. Выберите фото поменьше."
                429 -> "Слишком много запросов. Подождите минуту и повторите."
                507 -> "На сервере закончилось место для файлов. Обратитесь к владельцу Umbra."
                in 500..599 -> "Сервер временно недоступен. Попробуйте позже."
                else -> "Не удалось выполнить действие. Попробуйте ещё раз."
            }
        }
    }
    return when (this) {
        is IOException -> "Нет связи с сервером. Проверьте подключение."
        is SecurityException -> "Нет доступа к выбранному файлу. Выберите его ещё раз."
        is IllegalStateException, is IllegalArgumentException -> message?.takeIf { text -> text.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' } } ?: "Не удалось выполнить действие. Проверьте данные и повторите."
        else -> "Не удалось выполнить действие. Попробуйте ещё раз."
    }
}

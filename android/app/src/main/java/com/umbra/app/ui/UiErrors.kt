package com.umbra.app.ui

import retrofit2.HttpException
import java.io.IOException

/** Понятное пользователю сообщение об ошибке из исключения. */
internal fun Exception.userMessage(): String = when (this) {
    is HttpException -> when (code()) {
        401 -> "Неверный код или сессия истекла. Попробуйте снова."
        404 -> "Не найдено. Проверьте @никнейм или номер."
        409 -> "Конфликт: такие данные уже есть."
        413 -> "Файл слишком большой."
        429 -> "Слишком много запросов. Подождите немного."
        in 500..599 -> "Сервер временно недоступен. Попробуйте позже."
        else -> "Сервер ответил ошибкой (HTTP " + code() + ")"
    }
    is IOException -> "Нет связи с сервером. Проверьте подключение."
    is IllegalStateException, is IllegalArgumentException -> message ?: "Проверьте введённые данные"
    else -> "Что-то пошло не так. Попробуйте ещё раз."
}

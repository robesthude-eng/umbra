package com.umbra.app.ui

import retrofit2.HttpException
import java.io.IOException

internal fun Exception.userMessage(): String = when (this) {
    is HttpException -> when (code()) {
        401 -> "Сессия недействительна. Войдите снова с сохранёнными ключами."
        404 -> "Пользователь или чат не найден"
        409 -> "Конфликт данных. Проверьте имя пользователя и ключи."
        413 -> "Превышен допустимый размер"
        429 -> "Слишком много запросов. Повторите немного позже."
        in 500..599 -> "Сервер временно недоступен"
        else -> "Сервер отклонил запрос (HTTP " + code() + ")"
    }
    is IOException -> "Не удалось связаться с сервером. Проверьте подключение."
    is IllegalStateException, is IllegalArgumentException -> message ?: "Проверьте введённые данные"
    else -> "Операция не завершена. Повторите попытку."
}

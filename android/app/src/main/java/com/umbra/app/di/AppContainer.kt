package com.umbra.app.di

import android.content.Context
import com.umbra.app.crypto.CryptoManager
import com.umbra.app.data.api.UmbraApi
import com.umbra.app.data.api.createUmbraApi
import com.umbra.app.data.db.AppDatabase
import com.umbra.app.data.repo.ChatRepository
import com.umbra.app.data.ws.WebSocketClient

/**
 * Простой граф зависимостей (service locator). Собирает API, БД, WebSocket,
 * криптографию и репозиторий. Заменяем на Hilt/Koin при росте проекта.
 */
class AppContainer(context: Context) {
    // Базовый URL сервера Umbra. Для локальной разработки: http://10.0.2.2:8080
    // (эмулятор видит хост как 10.0.2.2). В проде — https://ваш-домен.
    private val baseUrl = "http://10.0.2.2:8080"

    val cryptoManager = CryptoManager(context)

    val api: UmbraApi = createUmbraApi(baseUrl)

    val database: AppDatabase = AppDatabase.build(context)

    val webSocketClient = WebSocketClient(baseUrl)

    val chatRepository = ChatRepository(api, database, cryptoManager)
}

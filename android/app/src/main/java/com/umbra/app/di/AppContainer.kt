package com.umbra.app.di

import android.content.Context
import com.umbra.app.BuildConfig
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
    private val baseUrl = BuildConfig.SERVER_URL
    val database: AppDatabase = AppDatabase.build(context)
    val cryptoManager = CryptoManager(context, database)

    val api: UmbraApi = createUmbraApi(baseUrl)

    val webSocketClient = WebSocketClient(baseUrl)

    val chatRepository = ChatRepository(context, api, database, cryptoManager, webSocketClient)
}

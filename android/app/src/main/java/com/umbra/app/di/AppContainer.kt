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
    // Базовый URL сервера Umbra.
    // Сейчас — тестовый стенд (прод-VPS, HTTP на :8081; cleartext для него
    // разрешён в res/xml/network_security_config.xml — убрать при переезде
    // на HTTPS-домен). Для эмулятора с сервером на хост-машине: http://10.0.2.2:8080
    private val baseUrl = "http://194.226.126.253:8081"

    val cryptoManager = CryptoManager(context)

    val api: UmbraApi = createUmbraApi(baseUrl)

    val database: AppDatabase = AppDatabase.build(context)

    val webSocketClient = WebSocketClient(baseUrl)

    val chatRepository = ChatRepository(context.applicationContext, api, database, cryptoManager, webSocketClient)
}

package com.umbra.app.di

import android.content.Context
import com.umbra.app.BuildConfig
import com.umbra.app.data.api.UmbraApi
import com.umbra.app.data.api.createUmbraApi
import com.umbra.app.data.call.CallEngine
import com.umbra.app.data.db.AppDatabase
import com.umbra.app.data.diag.DiagLog
import com.umbra.app.data.repo.ChatRepository
import com.umbra.app.data.session.SessionStore
import com.umbra.app.data.session.UiPreferences
import com.umbra.app.data.update.AppUpdater
import com.umbra.app.data.voice.VoicePlayer
import com.umbra.app.data.voice.VoiceRecorder
import com.umbra.app.data.ws.WebSocketClient

/** Простой граф зависимостей (service locator). */
class AppContainer(context: Context) {
    private val baseUrl = BuildConfig.SERVER_URL

    init {
        // Журнал диагностики: ошибки синхронизации и отправки без содержимого сообщений.
        DiagLog.init(context)
    }
    val database: AppDatabase = AppDatabase.build(context)
    val session = SessionStore(context)
    val uiPreferences = UiPreferences(context)
    val api: UmbraApi = createUmbraApi(baseUrl)
    val webSocketClient = WebSocketClient(baseUrl)
    val chatRepository = ChatRepository(context, api, database, session, webSocketClient)

    /** Запись голосовых сообщений: MediaRecorder из Android SDK, без новых зависимостей. */
    val voiceRecorder = VoiceRecorder(context)

    /** Проверка версии при запуске и самообновление из системного установщика. */
    val appUpdater = AppUpdater(context, baseUrl)

    /** Проигрывание: файл берётся из локального кэша либо скачивается репозиторием. */
    val voicePlayer = VoicePlayer { mediaId -> chatRepository.voiceFile(mediaId) }

    /**
     * Звонки на WebRTC. Движок принадлежит приложению, а не экрану: поворот
     * телефона и свёртывание не рвут разговор.
     */
    val callEngine = CallEngine(context, chatRepository)
}

package com.umbra.app

import android.app.Application
import com.umbra.app.di.AppContainer

/**
 * Точка входа приложения. Создаёт [AppContainer] — граф зависимостей
 * (ручной DI: без Hilt, чтобы не усложнять сборку и обфускацию).
 */
class UmbraApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

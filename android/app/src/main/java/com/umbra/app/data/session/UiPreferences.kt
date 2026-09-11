package com.umbra.app.data.session

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Сила Alien Interface.
 *
 * - [OFF] — обычный Future UI;
 * - [CALM] — «чужая» палитра и дышащий фон без ярких деталей;
 * - [FULL] — всё: звёздное поле, орбиты, сетка горизонта, HUD-подписи.
 */
enum class AlienIntensity { OFF, CALM, FULL }

data class AppearancePreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val messageTextSize: Int = 16,
    val reduceMotion: Boolean = false,
    val alienIntensity: AlienIntensity = AlienIntensity.OFF,
) {
    /** Совместимость с прежним переключателем: режим «включён» при любой силе. */
    val alienInterface: Boolean get() = alienIntensity != AlienIntensity.OFF
}

/**
 * Сила Alien-режима из настроек. До 0.16.10 хранился только флаг
 * `alien_interface`, поэтому уже включённый режим читается как [AlienIntensity.FULL].
 */
private fun readAlienIntensity(prefs: SharedPreferences): AlienIntensity {
    val stored = prefs.getString("alien_intensity", null)
    if (!stored.isNullOrBlank()) {
        return runCatching { AlienIntensity.valueOf(stored) }.getOrDefault(AlienIntensity.OFF)
    }
    return if (prefs.getBoolean("alien_interface", false)) AlienIntensity.FULL else AlienIntensity.OFF
}

/** Device-local appearance. Contains no account data and survives signing out. */
class UiPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("umbra_appearance", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(
        AppearancePreferences(
            theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM").orEmpty()) }
                .getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = prefs.getBoolean("dynamic_color", false),
            messageTextSize = prefs.getInt("message_text_size", 16).coerceIn(16, 22),
            reduceMotion = prefs.getBoolean("reduce_motion", false),
            alienIntensity = readAlienIntensity(prefs),
        ),
    )
    val state = mutableState.asStateFlow()

    fun setTheme(value: ThemeMode) {
        prefs.edit().putString("theme", value.name).apply()
        mutableState.value = mutableState.value.copy(theme = value)
    }

    fun setDynamicColor(value: Boolean) {
        prefs.edit().putBoolean("dynamic_color", value).apply()
        mutableState.value = mutableState.value.copy(dynamicColor = value)
    }

    fun setMessageTextSize(value: Int) {
        val size = value.coerceIn(16, 22)
        prefs.edit().putInt("message_text_size", size).apply()
        mutableState.value = mutableState.value.copy(messageTextSize = size)
    }

    fun setReduceMotion(value: Boolean) {
        prefs.edit().putBoolean("reduce_motion", value).apply()
        mutableState.value = mutableState.value.copy(reduceMotion = value)
    }

    fun setAlienIntensity(value: AlienIntensity) {
        prefs.edit()
            .putString("alien_intensity", value.name)
            // Старый флаг пишем тоже: откат на прежнюю сборку не потеряет режим.
            .putBoolean("alien_interface", value != AlienIntensity.OFF)
            .apply()
        mutableState.value = mutableState.value.copy(alienIntensity = value)
    }

    /** Совместимость: простое включение без выбора силы. */
    fun setAlienInterface(value: Boolean) =
        setAlienIntensity(if (value) AlienIntensity.FULL else AlienIntensity.OFF)
}

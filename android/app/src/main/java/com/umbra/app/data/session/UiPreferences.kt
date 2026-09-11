package com.umbra.app.data.session

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppearancePreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val messageTextSize: Int = 16,
    val reduceMotion: Boolean = false,
)

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
}

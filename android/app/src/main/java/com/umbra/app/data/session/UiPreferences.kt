package com.umbra.app.data.session

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Три взаимоисключающие темы; светлая/тёмная схема настраивается отдельно. */
enum class InterfaceStyle { STANDARD, SMOKED_GLASS, ALIEN }

enum class AccentColor { DEFAULT, BLUE, TEAL, GREEN, AMBER, ROSE }

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
    val interfaceStyle: InterfaceStyle = InterfaceStyle.STANDARD,
    val accentColor: AccentColor = AccentColor.DEFAULT,
) {
    /** Сохранённая сила Alien не включает эффекты в других темах. */
    val effectiveAlienIntensity: AlienIntensity get() = resolveAlienIntensity(interfaceStyle, alienIntensity)
    val alienInterface: Boolean get() = effectiveAlienIntensity != AlienIntensity.OFF
}

fun resolveAlienIntensity(style: InterfaceStyle, saved: AlienIntensity): AlienIntensity =
    if (style == InterfaceStyle.ALIEN) saved.takeUnless { it == AlienIntensity.OFF } ?: AlienIntensity.CALM
    else AlienIntensity.OFF

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

private const val INTERFACE_THEME = "interface_theme"

/** The new key distinguishes explicit Standard from the old Standard + Alien combination. */
private fun readInterfaceStyle(prefs: SharedPreferences): InterfaceStyle {
    prefs.getString(INTERFACE_THEME, null)?.let { selected ->
        return runCatching { InterfaceStyle.valueOf(selected) }.getOrDefault(InterfaceStyle.STANDARD)
    }
    return when (prefs.getString("interface_style", null)) {
        "SMOKED_GLASS" -> InterfaceStyle.SMOKED_GLASS
        "ALIEN" -> InterfaceStyle.ALIEN
        null, "STANDARD" -> if (readAlienIntensity(prefs) != AlienIntensity.OFF) InterfaceStyle.ALIEN else InterfaceStyle.STANDARD
        else -> InterfaceStyle.STANDARD
    }
}

/** Device-local appearance. Contains no account data and survives signing out. */
class UiPreferences(context: Context, preferenceName: String = "umbra_appearance") {
    private val prefs = context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(
        AppearancePreferences(
            theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM").orEmpty()) }
                .getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = prefs.getBoolean("dynamic_color", false),
            messageTextSize = prefs.getInt("message_text_size", 16).coerceIn(16, 22),
            reduceMotion = prefs.getBoolean("reduce_motion", false),
            alienIntensity = readAlienIntensity(prefs),
            interfaceStyle = readInterfaceStyle(prefs),
            accentColor = runCatching {
                AccentColor.valueOf(prefs.getString("accent_color", "DEFAULT").orEmpty())
            }.getOrDefault(AccentColor.DEFAULT),
        ),
    )
    val state = mutableState.asStateFlow()

    init {
        // Migrate once, preserving a previously chosen style. A new install starts in Standard.
        if (!prefs.contains(INTERFACE_THEME)) {
            prefs.edit().putString(INTERFACE_THEME, mutableState.value.interfaceStyle.name).apply()
        }
    }

    fun setInterfaceStyle(value: InterfaceStyle) {
        val intensity = if (value == InterfaceStyle.ALIEN)
            resolveAlienIntensity(value, mutableState.value.alienIntensity) else mutableState.value.alienIntensity
        prefs.edit()
            .putString(INTERFACE_THEME, value.name)
            .putString("interface_style", value.name)
            .putString("alien_intensity", intensity.name)
            .putBoolean("alien_interface", value == InterfaceStyle.ALIEN)
            .apply()
        // Схема, цвет, текст, анимация и сила Alien сохраняются при переключении темы.
        mutableState.value = mutableState.value.copy(interfaceStyle = value, alienIntensity = intensity)
    }

    fun setAccentColor(value: AccentColor) {
        prefs.edit().putString("accent_color", value.name).apply()
        mutableState.value = mutableState.value.copy(accentColor = value)
    }

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
        val style = if (value == AlienIntensity.OFF && mutableState.value.interfaceStyle == InterfaceStyle.ALIEN)
            InterfaceStyle.STANDARD else mutableState.value.interfaceStyle
        prefs.edit()
            .putString("alien_intensity", value.name)
            .putString(INTERFACE_THEME, style.name)
            .putString("interface_style", style.name)
            .putBoolean("alien_interface", style == InterfaceStyle.ALIEN)
            .apply()
        mutableState.value = mutableState.value.copy(alienIntensity = value, interfaceStyle = style)
    }

}

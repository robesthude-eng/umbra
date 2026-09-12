package com.umbra.app.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.umbra.app.data.session.AlienIntensity
import com.umbra.app.data.session.AccentColor
import com.umbra.app.data.session.AppearancePreferences
import com.umbra.app.data.session.InterfaceStyle
import com.umbra.app.data.session.ThemeMode
import com.umbra.app.data.session.UiPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Реальные SharedPreferences; тесты изолированы от настроек установленного аккаунта. */
@RunWith(AndroidJUnit4::class)
class UiPreferencesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "umbra_appearance_test_${UUID.randomUUID()}"

    @After fun cleanUp() { context.deleteSharedPreferences(preferenceName) }

    @Test fun newInstallStartsWithStandardTheme() {
        val state = UiPreferences(context, preferenceName).state.value
        assertEquals(AppearancePreferences(), state)
        assertEquals(AlienIntensity.OFF, state.effectiveAlienIntensity)
        assertFalse(state.alienInterface)
    }

    @Test fun upgradingKeepsLegacyAlienAsSeparateTheme() {
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit()
            .putBoolean("alien_interface", true)
            .putString("theme", "DARK")
            .putInt("message_text_size", 22)
            .commit()
        val state = UiPreferences(context, preferenceName).state.value
        assertEquals(InterfaceStyle.ALIEN, state.interfaceStyle)
        assertEquals(AlienIntensity.FULL, state.alienIntensity)
        assertEquals(AlienIntensity.FULL, state.effectiveAlienIntensity)
        assertEquals(ThemeMode.DARK, state.theme)
        assertEquals(22, state.messageTextSize)
    }

    @Test fun stylePersistsAndRoundTripKeepsOtherPreferences() {
        val preferences = UiPreferences(context, preferenceName)
        preferences.setTheme(ThemeMode.LIGHT)
        preferences.setInterfaceStyle(InterfaceStyle.ALIEN)
        preferences.setAlienIntensity(AlienIntensity.CALM)
        preferences.setAccentColor(AccentColor.TEAL)
        preferences.setDynamicColor(true)
        preferences.setReduceMotion(true)
        preferences.setMessageTextSize(20)
        val original = preferences.state.value
        preferences.setInterfaceStyle(InterfaceStyle.SMOKED_GLASS)

        val recreated = UiPreferences(context, preferenceName)
        assertEquals(original.copy(interfaceStyle = InterfaceStyle.SMOKED_GLASS), recreated.state.value)
        assertEquals("SMOKED_GLASS", context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
            .getString("interface_style", null))
        assertEquals(AlienIntensity.OFF, recreated.state.value.effectiveAlienIntensity)
        recreated.setInterfaceStyle(InterfaceStyle.ALIEN)
        assertEquals(original, UiPreferences(context, preferenceName).state.value)
    }

    @Test fun upgradingStandardWithAlienKeepsCalmEffects() {
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit()
            .putString("interface_style", "STANDARD")
            .putString("alien_intensity", "CALM")
            .putBoolean("dynamic_color", true)
            .commit()
        val state = UiPreferences(context, preferenceName).state.value
        assertEquals(InterfaceStyle.ALIEN, state.interfaceStyle)
        assertEquals(AlienIntensity.CALM, state.effectiveAlienIntensity)
        assertTrue(state.dynamicColor)
        assertEquals(state, UiPreferences(context, preferenceName).state.value)
    }

    @Test fun returningToStandardStaysStandardAfterRestart() {
        val preferences = UiPreferences(context, preferenceName)
        preferences.setInterfaceStyle(InterfaceStyle.ALIEN)
        assertEquals(AlienIntensity.CALM, preferences.state.value.effectiveAlienIntensity)
        preferences.setAlienIntensity(AlienIntensity.FULL)
        preferences.setInterfaceStyle(InterfaceStyle.STANDARD)

        val recreated = UiPreferences(context, preferenceName)
        assertEquals(InterfaceStyle.STANDARD, recreated.state.value.interfaceStyle)
        assertEquals(AlienIntensity.OFF, recreated.state.value.effectiveAlienIntensity)
        assertFalse(recreated.state.value.alienInterface)
        // Remembering the strength must not reactivate the Alien theme on a later restart.
        assertEquals(AlienIntensity.FULL, recreated.state.value.alienIntensity)
        recreated.setInterfaceStyle(InterfaceStyle.ALIEN)
        assertEquals(AlienIntensity.FULL, UiPreferences(context, preferenceName).state.value.effectiveAlienIntensity)
    }

    @Test fun upgradingGlassDoesNotActivateDormantAlien() {
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit()
            .putString("interface_style", "SMOKED_GLASS")
            .putString("alien_intensity", "FULL")
            .commit()
        val preferences = UiPreferences(context, preferenceName)
        assertEquals(InterfaceStyle.SMOKED_GLASS, preferences.state.value.interfaceStyle)
        assertEquals(AlienIntensity.OFF, preferences.state.value.effectiveAlienIntensity)
        assertEquals(preferences.state.value, UiPreferences(context, preferenceName).state.value)
    }

    @Test fun unknownStyleFallsBackWithoutLosingValidSettings() {
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit()
            .putString("interface_style", "FUTURE_STYLE")
            .putString("alien_intensity", "FULL")
            .putString("accent_color", "FUTURE_COLOR")
            .putBoolean("reduce_motion", true)
            .commit()
        val state = UiPreferences(context, preferenceName).state.value
        assertEquals(InterfaceStyle.STANDARD, state.interfaceStyle)
        assertEquals(AlienIntensity.FULL, state.alienIntensity)
        assertEquals(AlienIntensity.OFF, state.effectiveAlienIntensity)
        assertEquals(AccentColor.DEFAULT, state.accentColor)
        assertTrue(state.reduceMotion)
    }
}

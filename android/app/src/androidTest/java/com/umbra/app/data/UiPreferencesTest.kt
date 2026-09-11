package com.umbra.app.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.umbra.app.data.session.AlienIntensity
import com.umbra.app.data.session.InterfaceStyle
import com.umbra.app.data.session.ThemeMode
import com.umbra.app.data.session.UiPreferences
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test fun upgradingKeepsLegacyAlienAndUsesStandardStyle() {
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit()
            .putBoolean("alien_interface", true)
            .putString("theme", "DARK")
            .putInt("message_text_size", 22)
            .commit()
        val state = UiPreferences(context, preferenceName).state.value
        assertEquals(InterfaceStyle.STANDARD, state.interfaceStyle)
        assertEquals(AlienIntensity.FULL, state.alienIntensity)
        assertEquals(ThemeMode.DARK, state.theme)
        assertEquals(22, state.messageTextSize)
    }

    @Test fun stylePersistsAndRoundTripKeepsOtherPreferences() {
        val preferences = UiPreferences(context, preferenceName)
        preferences.setTheme(ThemeMode.LIGHT)
        preferences.setAlienIntensity(AlienIntensity.CALM)
        preferences.setDynamicColor(true)
        preferences.setReduceMotion(true)
        preferences.setMessageTextSize(20)
        val original = preferences.state.value
        preferences.setInterfaceStyle(InterfaceStyle.SMOKED_GLASS)

        val recreated = UiPreferences(context, preferenceName)
        assertEquals(original.copy(interfaceStyle = InterfaceStyle.SMOKED_GLASS), recreated.state.value)
        assertEquals("SMOKED_GLASS", context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
            .getString("interface_style", null))
        recreated.setInterfaceStyle(InterfaceStyle.STANDARD)
        assertEquals(original, UiPreferences(context, preferenceName).state.value)
    }

    @Test fun unknownStyleFallsBackWithoutLosingValidSettings() {
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit()
            .putString("interface_style", "FUTURE_STYLE")
            .putString("alien_intensity", "FULL")
            .putBoolean("reduce_motion", true)
            .commit()
        val state = UiPreferences(context, preferenceName).state.value
        assertEquals(InterfaceStyle.STANDARD, state.interfaceStyle)
        assertEquals(AlienIntensity.FULL, state.alienIntensity)
        assertTrue(state.reduceMotion)
    }
}

package com.umbra.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.umbra.app.data.session.AccentColor
import com.umbra.app.data.session.AlienIntensity
import com.umbra.app.data.session.InterfaceStyle
import com.umbra.app.data.session.ThemeMode
import com.umbra.app.data.session.UiPreferences
import com.umbra.app.ui.theme.LocalUmbraChatColors
import com.umbra.app.ui.theme.LocalUmbraMessageTextStyle
import com.umbra.app.ui.theme.LocalUmbraAlienTokens
import com.umbra.app.ui.theme.UmbraDarkColors
import com.umbra.app.ui.theme.UmbraLightColors
import com.umbra.app.ui.theme.accentSwatch
import com.umbra.app.ui.theme.alienTokens
import com.umbra.app.ui.theme.smokedGlassColorScheme
import kotlin.math.roundToInt

@Composable
internal fun AppearanceSettings(preferences: UiPreferences) {
    val appearance by preferences.state.collectAsState()
    val palette = LocalUmbraChatColors.current
    var previewSize by remember(appearance.messageTextSize) { mutableFloatStateOf(appearance.messageTextSize.toFloat()) }
    InterfaceStyleSettings(preferences)
    AppearanceSurface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Цветовая схема", style = MaterialTheme.typography.titleMedium)
            // Radio rows also fit large system fonts and 320 dp windows without truncation.
            Column(Modifier.selectableGroup()) {
                listOf(ThemeMode.SYSTEM to "Как в системе", ThemeMode.LIGHT to "Светлая", ThemeMode.DARK to "Тёмная").forEach { (mode, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                            .selectable(selected = appearance.theme == mode, role = Role.RadioButton, onClick = { preferences.setTheme(mode) })
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = appearance.theme == mode, onClick = null)
                        Text(label, Modifier.padding(start = 12.dp).weight(1f))
                    }
                }
            }
        }
    }
    AccentColorSettings(preferences)
    AppearanceSurface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Текст сообщений", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(previewSize.roundToInt().toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = previewSize, onValueChange = { previewSize = it },
                onValueChangeFinished = { preferences.setMessageTextSize(previewSize.roundToInt()) },
                valueRange = 16f..22f, steps = 5,
            )
            Box(Modifier.fillMaxWidth().clip(bubbleShape(outgoing = true, first = true, last = true))
                .background(Brush.linearGradient(palette.outgoing)).padding(14.dp)) {
                Text("Увидимся вечером ❤️", color = palette.onOutgoing,
                    style = LocalUmbraMessageTextStyle.current.copy(fontSize = previewSize.sp, lineHeight = (previewSize + 6).sp))
            }
        }
    }
    if (appearance.interfaceStyle == InterfaceStyle.ALIEN) {
        AlienSettingsCard(appearance.effectiveAlienIntensity, preferences::setAlienIntensity)
    }
    AppearanceSurface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        PreferenceSwitch("Уменьшить анимацию", "Спокойные переходы между экранами", appearance.reduceMotion, preferences::setReduceMotion, Modifier.padding(16.dp))
    }
}

@Composable
private fun InterfaceStyleSettings(preferences: UiPreferences) {
    val appearance by preferences.state.collectAsState()
    SettingsCard("Тема интерфейса") {
        Column(Modifier.selectableGroup()) {
            InterfaceStyle.entries.forEach { style ->
                val (title, subtitle) = when (style) {
                    InterfaceStyle.STANDARD -> "Стандартная" to "Исходный интерфейс Umbra · по умолчанию"
                    InterfaceStyle.SMOKED_GLASS -> "Дымчатое стекло" to "Матовые панели и мягкие акценты"
                    InterfaceStyle.ALIEN -> "Alien Interface" to "Космический фон и голографические эффекты"
                }
                AppearanceRadioRow(
                    selected = appearance.interfaceStyle == style,
                    title = title, subtitle = subtitle,
                    onClick = { preferences.setInterfaceStyle(style) },
                )
            }
        }
    }
}

@Composable
private fun AccentColorSettings(preferences: UiPreferences) {
    val appearance by preferences.state.collectAsState()
    val dark = LocalUmbraAlienTokens.current.dark
    val wallpaperAvailable = appearance.interfaceStyle == InterfaceStyle.STANDARD && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val wallpaperActive = wallpaperAvailable && appearance.dynamicColor
    val defaultColor = when (appearance.interfaceStyle) {
        InterfaceStyle.STANDARD -> if (dark) UmbraDarkColors.primary else UmbraLightColors.primary
        InterfaceStyle.SMOKED_GLASS -> smokedGlassColorScheme(dark).primary
        InterfaceStyle.ALIEN -> alienTokens(appearance.effectiveAlienIntensity, dark).primary
    }
    var chooseColor by rememberSaveable { mutableStateOf(false) }
    SettingsCard("Цвет оформления") {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.small)
                .clickable(enabled = !wallpaperActive, role = Role.Button, onClickLabel = "Изменить цвет") { chooseColor = true }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AccentDot(if (wallpaperActive) MaterialTheme.colorScheme.primary else accentSwatch(appearance.accentColor, dark) ?: defaultColor)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (wallpaperActive) "Цвета обоев" else appearance.accentColor.label(), style = MaterialTheme.typography.titleSmall)
                Text("Кнопки, акценты и сообщения", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!wallpaperActive) Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (wallpaperAvailable) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PreferenceSwitch("Цвета обоев", "Использовать системную палитру вместо выбранного акцента", appearance.dynamicColor, preferences::setDynamicColor)
        } else if (appearance.interfaceStyle == InterfaceStyle.STANDARD) {
            SettingsDescription("Цвета обоев доступны на Android 12 и новее.")
        }
        SettingsDescription("Акцент сохраняется при смене темы. «Цвета темы» возвращает её исходную палитру.")
    }
    if (chooseColor) AlertDialog(
        onDismissRequest = { chooseColor = false },
        title = { Text("Цвет оформления") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                AccentColor.entries.forEach { color ->
                    AppearanceRadioRow(
                        selected = appearance.accentColor == color,
                        title = color.label(),
                        leading = { AccentDot(accentSwatch(color, dark) ?: defaultColor) },
                        onClick = { preferences.setAccentColor(color); chooseColor = false },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { chooseColor = false }) { Text("Закрыть") } },
    )
}

private fun AccentColor.label(): String = when (this) {
    AccentColor.DEFAULT -> "Цвета темы"
    AccentColor.BLUE -> "Синий"
    AccentColor.TEAL -> "Бирюзовый"
    AccentColor.GREEN -> "Зелёный"
    AccentColor.AMBER -> "Янтарный"
    AccentColor.ROSE -> "Розовый"
}

@Composable
private fun AccentDot(color: Color) {
    Box(Modifier.size(24.dp).clip(CircleShape).background(color))
}

@Composable
private fun AppearanceRadioRow(
    selected: Boolean,
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.small)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Сила эффектов самостоятельной темы Alien; предпросмотр использует её текущие цвета. */
@Composable
private fun AlienSettingsCard(intensity: AlienIntensity, onIntensity: (AlienIntensity) -> Unit) {
    val preview = LocalUmbraAlienTokens.current
    AppearanceSurface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = if (intensity != AlienIntensity.OFF)
            androidx.compose.foundation.BorderStroke(1.dp, Brush.linearGradient(listOf(preview.primary, preview.secondary)))
        else null,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Эффекты Alien", style = MaterialTheme.typography.titleMedium)
            Text(
                "Живой космический фон, голографическое стекло и орбитальная навигация. " +
                    "Сила влияет только на оформление: сообщения, звонки и уведомления работают как обычно.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Для отключения эффектов выбирается другая тема интерфейса.
            Column(Modifier.selectableGroup()) {
                listOf(
                    AlienIntensity.CALM to "Спокойный",
                    AlienIntensity.FULL to "Полный",
                ).forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                            .selectable(selected = intensity == value, role = Role.RadioButton, onClick = { onIntensity(value) })
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = intensity == value, onClick = null)
                        Text(label, Modifier.padding(start = 12.dp).weight(1f))
                    }
                }
            }
            CompositionLocalProvider(com.umbra.app.ui.theme.LocalUmbraAlienTokens provides preview) {
                Box(
                    Modifier.fillMaxWidth().height(116.dp).clip(RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    QuantumBackdrop(Modifier.matchParentSize())
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AlienHudLabel(
                            when (intensity) {
                                AlienIntensity.OFF -> "предпросмотр режима"
                                AlienIntensity.CALM -> "спокойный контур"
                                AlienIntensity.FULL -> "канал открыт"
                            },
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AlienSignalMeter(level = if (intensity == AlienIntensity.FULL) 4 else 2)
                            Text(
                                "${preview.starCount} звёзд · орбиты · сетка горизонта",
                                style = MaterialTheme.typography.labelSmall,
                                color = preview.hud,
                            )
                        }
                    }
                }
            }
            AlienDivider("umbra · alien")
        }
    }
}

@Composable
private fun PreferenceSwitch(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
        .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null)
    }
}

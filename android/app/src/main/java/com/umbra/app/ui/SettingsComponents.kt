package com.umbra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.umbra.app.BuildConfig
import com.umbra.app.data.repo.ChatRepository
import com.umbra.app.data.session.InterfaceStyle
import com.umbra.app.data.session.ThemeMode
import com.umbra.app.data.session.UiPreferences

internal enum class SettingsCategory(val title: String, val subtitle: String, val icon: ImageVector) {
    PROFILE("Профиль", "Имя, никнейм и фото", Icons.Filled.Person),
    APPEARANCE("Темы и оформление", "Стиль, цвета, размер текста и анимация", Icons.Filled.Palette),
    NOTIFICATIONS("Уведомления и звуки", "Сообщения, звонки, звук и вибрация", Icons.Filled.Notifications),
    NETWORK("Сеть", "Подключение, синхронизация и диагностика", Icons.Filled.Wifi),
    STORAGE("Память и данные", "Хранение переписки и файлов", Icons.Filled.Storage),
    PRIVACY("Конфиденциальность", "Доступ к данным и управление аккаунтом", Icons.Filled.Lock),
    ABOUT("О приложении", "Версия и обновления Umbra", Icons.Filled.Info),
}

@Composable
internal fun SettingsOverview(repo: ChatRepository, preferences: UiPreferences, onOpen: (SettingsCategory) -> Unit) {
    val appearance by preferences.state.collectAsState()
    val style = when (appearance.interfaceStyle) {
        InterfaceStyle.STANDARD -> "Стандартная тема"
        InterfaceStyle.SMOKED_GLASS -> "Дымчатое стекло"
        InterfaceStyle.ALIEN -> "Alien Interface"
    }
    val theme = when (appearance.theme) {
        ThemeMode.SYSTEM -> "тема как в системе"
        ThemeMode.LIGHT -> "светлая тема"
        ThemeMode.DARK -> "тёмная тема"
    }
    SettingsProfileSummary(repo) { onOpen(SettingsCategory.PROFILE) }
    SettingsSectionLabel("Личное")
    SettingsGroup {
        SettingsCategoryRow(SettingsCategory.APPEARANCE, "$style · $theme", onOpen)
        SettingsDivider()
        SettingsCategoryRow(SettingsCategory.NOTIFICATIONS, onOpen = onOpen)
        SettingsDivider()
        SettingsCategoryRow(SettingsCategory.PRIVACY, onOpen = onOpen)
    }
    SettingsSectionLabel("Приложение")
    SettingsGroup {
        SettingsCategoryRow(SettingsCategory.NETWORK, onOpen = onOpen)
        SettingsDivider()
        SettingsCategoryRow(SettingsCategory.STORAGE, onOpen = onOpen)
        SettingsDivider()
        SettingsCategoryRow(SettingsCategory.ABOUT, "Umbra ${BuildConfig.VERSION_NAME}", onOpen)
    }
}

@Composable
private fun SettingsCategoryRow(
    category: SettingsCategory,
    subtitle: String = category.subtitle,
    onOpen: (SettingsCategory) -> Unit,
) {
    SettingsActionRow(category.title, subtitle, category.icon, onClick = { onOpen(category) })
}

@Composable
internal fun SettingsSectionLabel(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    AppearanceSurface(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
internal fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    AppearanceSurface(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
internal fun SettingsDescription(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
}

@Composable
internal fun SettingsActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(MaterialTheme.shapes.medium).background(accent.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(22.dp), tint = accent.copy(alpha = if (enabled) 1f else 0.38f)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title, style = MaterialTheme.typography.titleSmall,
                color = (if (destructive) accent else MaterialTheme.colorScheme.onSurface).copy(alpha = if (enabled) 1f else 0.38f),
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SettingsProfileSummary(repo: ChatRepository, onClick: (() -> Unit)? = null) {
    val me by repo.account.collectAsState()
    GlassRow(onClick) {
        Avatar(repo, me.avatarMediaId, me.displayName, 64.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Профиль", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                listOf(me.displayName, me.lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Без имени" },
                style = MaterialTheme.typography.titleLarge,
            )
            if (me.username.isNotBlank()) Text("@${me.username}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onClick != null) Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

package com.umbra.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.umbra.app.data.repo.ChatRepository

@Composable
internal fun ProfileSettings(repo: ChatRepository, busy: Boolean, onEdit: () -> Unit, onPhoto: () -> Unit) {
    val me by repo.account.collectAsState()
    SettingsProfileSummary(repo)
    SettingsGroup {
        SettingsActionRow("Редактировать профиль", "Имя, фамилия и никнейм", Icons.Filled.Edit, enabled = !busy, onClick = onEdit)
        SettingsDivider()
        SettingsActionRow("Изменить фото профиля", "Выбрать изображение из галереи", Icons.Filled.AddAPhoto, enabled = !busy, onClick = onPhoto)
    }
    SettingsCard("Данные профиля") {
        ProfileValue("Имя", me.displayName)
        ProfileValue("Фамилия", me.lastName)
        ProfileValue("Никнейм", me.username.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty())
        ProfileValue("Номер телефона", me.phone)
    }
}

@Composable
private fun ProfileValue(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "Не указан" }, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun PrivacySettings(busy: Boolean, onLogout: () -> Unit, onDelete: () -> Unit) {
    SettingsCard("Доступ к переписке") {
        SettingsDescription("Сообщения и файлы хранятся на сервере владельца Umbra. Сквозное шифрование в этой версии не используется.")
    }
    SettingsSectionLabel("Аккаунт")
    SettingsGroup {
        SettingsActionRow(
            "Выйти из аккаунта", "Для повторного входа понадобится новый код",
            Icons.AutoMirrored.Filled.Logout, enabled = !busy, onClick = onLogout,
        )
    }
    SettingsSectionLabel("Удаление аккаунта")
    SettingsGroup {
        SettingsActionRow(
            "Удалить аккаунт", "Удаление профиля, сообщений и созданных вами групп",
            Icons.Filled.DeleteForever, enabled = !busy, destructive = true, onClick = onDelete,
        )
    }
}

package com.umbra.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat

@Composable
internal fun NotificationSettings() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    SettingsCard("Сообщения и звонки") {
        Text(
            if (enabled) "Уведомления разрешены в Android" else "Уведомления выключены в Android",
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        SettingsDescription(
            "В системных настройках можно выбрать звук, вибрацию и показ на экране блокировки. " +
                "Отдельные категории сообщений и звонков могут быть выключены, даже если уведомления приложения разрешены.",
        )
    }
    AndroidSettingsRow(
        title = "Настроить уведомления",
        subtitle = "Открыть настройки Umbra в Android",
        icon = Icons.Filled.Notifications,
        intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        onReturn = { enabled = NotificationManagerCompat.from(context).areNotificationsEnabled() },
    )
}

@Composable
internal fun StorageSettings() {
    val context = LocalContext.current
    SettingsCard("На этом устройстве") {
        SettingsDescription("Umbra сохраняет локальную историю, загруженные вложения и очередь неотправленных сообщений. Занимаемое место можно посмотреть в настройках Android.")
    }
    SettingsCard("Хранение данных на сервере") {
        SettingsDescription("Сообщения, фото, видео и другие отправленные файлы хранятся на сервере владельца Umbra.")
    }
    AndroidSettingsRow(
        title = "Память в настройках Android",
        subtitle = "На странице приложения откройте «Память» или «Хранилище»",
        icon = Icons.Filled.Storage,
        intent = appDetailsIntent(context.packageName),
    )
    SettingsDescription("Очистка данных приложения в Android удалит локальную историю и неотправленные сообщения на этом устройстве и потребует нового входа.")
}

private fun appDetailsIntent(packageName: String) = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.fromParts("package", packageName, null),
)

/** The system owns notification preferences and storage; these rows open the real controls. */
@Composable
private fun AndroidSettingsRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    intent: Intent,
    onReturn: () -> Unit = {},
) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onReturn() }
    SettingsGroup {
        SettingsActionRow(title, subtitle, icon, onClick = {
            error = null
            // Some Android variants do not expose the requested page. App details is the fallback.
            runCatching { launcher.launch(intent) }.onFailure {
                runCatching { launcher.launch(appDetailsIntent(context.packageName)) }.onFailure {
                    error = "Не удалось открыть настройки Android. Откройте их вручную: Приложения → Umbra."
                }
            }
        })
        error?.let { Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

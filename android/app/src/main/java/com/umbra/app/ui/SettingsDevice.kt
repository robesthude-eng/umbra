package com.umbra.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
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
import com.umbra.app.data.media.Attachments
import com.umbra.app.data.session.AutoDownloadMode
import kotlinx.coroutines.launch

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
internal fun StorageSettings(
    repo: com.umbra.app.data.repo.ChatRepository,
    preferences: com.umbra.app.data.session.UiPreferences,
) {
    val context = LocalContext.current
    val ui by preferences.state.collectAsState()
    val quality = ui.mediaQuality
    SettingsCard("Качество отправляемых фото и видео") {
        SettingsDescription(
            "Авто: сжимаем на мобильном интернете и для тяжёлых файлов, по Wi-Fi отправляем как есть. " +
                "Сжатие: фото до 2048 px, видео до 720p. Оригинал: без потерь качества.",
        )
    }
    SettingsGroup {
        MediaQualityRow("Автоматически", "Решает по типу сети и размеру", quality == com.umbra.app.data.media.MediaSendQuality.AUTO) {
            preferences.setMediaQuality(com.umbra.app.data.media.MediaSendQuality.AUTO)
        }
        MediaQualityRow("Всегда сжимать", "Экономит трафик и время", quality == com.umbra.app.data.media.MediaSendQuality.COMPRESS) {
            preferences.setMediaQuality(com.umbra.app.data.media.MediaSendQuality.COMPRESS)
        }
        MediaQualityRow("Без сжатия", "Исходное качество, файлы тяжелее", quality == com.umbra.app.data.media.MediaSendQuality.ORIGINAL) {
            preferences.setMediaQuality(com.umbra.app.data.media.MediaSendQuality.ORIGINAL)
        }
    }
    SettingsCard("Автозагрузка вложений") {
        SettingsDescription(
            "Когда автозагрузка выключена, превью появляется по кнопке «Загрузить» в самом сообщении. " +
                "Открытие файла вручную работает всегда.",
        )
    }
    SettingsGroup {
        MediaQualityRow("Всегда", "Любая сеть", ui.autoDownload == AutoDownloadMode.ALWAYS) {
            preferences.setAutoDownload(AutoDownloadMode.ALWAYS)
        }
        MediaQualityRow("Только по Wi-Fi", "На мобильном интернете — вручную", ui.autoDownload == AutoDownloadMode.WIFI) {
            preferences.setAutoDownload(AutoDownloadMode.WIFI)
        }
        MediaQualityRow("Никогда", "Экономит трафик и место", ui.autoDownload == AutoDownloadMode.NEVER) {
            preferences.setAutoDownload(AutoDownloadMode.NEVER)
        }
    }
    MediaCacheCard(repo)
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

/** Выбор качества: одна активная строка из трёх. */
@Composable
private fun MediaQualityRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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

/**
 * Сколько занято медиа на устройстве и кнопка очистки кэша.
 * Очередь неотправленного и записи не удаляются: их неоткуда восстановить.
 */
@Composable
private fun MediaCacheCard(repo: com.umbra.app.data.repo.ChatRepository) {
    val scope = rememberCoroutineScope()
    var bytes by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var freed by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { bytes = runCatching { repo.localMediaBytes() }.getOrNull() }
    SettingsCard("Медиа на этом устройстве") {
        SettingsDescription(
            when (val size = bytes) {
                null -> "Считаем занятое место…"
                else -> "Скачанные файлы, записи и очередь отправки занимают ${Attachments.sizeText(size)}."
            },
        )
        freed?.let { SettingsDescription("Освобождено ${Attachments.sizeText(it)}.") }
        TextButton(
            onClick = {
                if (busy) return@TextButton
                busy = true
                scope.launch {
                    freed = runCatching { repo.clearMediaCache() }.getOrNull()
                    bytes = runCatching { repo.localMediaBytes() }.getOrNull()
                    busy = false
                }
            },
            enabled = !busy,
        ) { Text(if (busy) "Очищаем…" else "Очистить кэш скачанных файлов") }
    }
    SettingsCard("Хранение данных на сервере") {
        SettingsDescription("Сообщения, фото, видео и другие отправленные файлы хранятся на сервере владельца Umbra.")
    }
}

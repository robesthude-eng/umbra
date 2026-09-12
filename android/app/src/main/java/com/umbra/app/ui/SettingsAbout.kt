package com.umbra.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.umbra.app.di.AppContainer
import kotlinx.coroutines.launch

@Composable
internal fun AboutSettings(container: AppContainer) {
    SettingsCard("Umbra") {
        SettingsDescription("Личное пространство для общения с близкими: переписка, медиа и звонки.")
    }
    UpdateCard(container)
}

@Composable
internal fun UpdateCard(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    AppearanceSurface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Обновление", style = MaterialTheme.typography.titleMedium)
            Text(
                "Установлена версия ${com.umbra.app.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Button(
                onClick = {
                    if (checking) return@Button
                    checking = true
                    status = null
                    scope.launch {
                        try {
                            val info = container.appUpdater.runCheck(force = true)
                            status = if (info == null) "Новая версия не найдена. Если сеть недоступна, повторите проверку позже."
                            else "Доступна версия ${info.versionName} — сейчас появится предложение обновиться"
                        } catch (e: Exception) {
                            status = e.userMessage()
                        } finally {
                            checking = false
                        }
                    }
                },
                enabled = !checking,
            ) {
                Text(if (checking) "Проверяем…" else "Проверить обновление")
            }
        }
    }
}

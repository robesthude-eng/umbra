package com.umbra.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.umbra.app.data.diag.DiagLog
import com.umbra.app.data.repo.ChatRepository
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun NetworkSettings(repo: ChatRepository) {
    val context = LocalContext.current
    val realtime by repo.realtimeDiagnostics.collectAsState()
    val lastSync by repo.lastSuccessfulSyncAtMillis.collectAsState()
    val lastSyncText = remember(lastSync) {
        lastSync?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it)) }
            ?: "ещё не выполнялась"
    }
    val network by repo.networkSnapshot.collectAsState()
    val mode by repo.networkMode.collectAsState()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<com.umbra.app.data.repo.NetworkCheckReport?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var logMessage by remember { mutableStateOf<String?>(null) }
    AppearanceSurface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Соединение и диагностика", style = MaterialTheme.typography.titleMedium)
            Text(
                if (realtime.connected) "Онлайн-канал: подключён"
                else realtime.lastFailure ?: "Онлайн-канал: переподключение",
                style = MaterialTheme.typography.bodyMedium,
                color = if (realtime.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            if (!realtime.connected && realtime.attempt > 0) Text(
                "Попытка ${realtime.attempt}; следующий повтор через ${realtime.retryInMs / 1000} с",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Последняя успешная синхронизация: $lastSyncText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Режим: ${when (mode) { com.umbra.app.data.repo.NetworkMode.ONLINE -> "онлайн"; com.umbra.app.data.repo.NetworkMode.POLLING -> "медленный REST"; com.umbra.app.data.repo.NetworkMode.OFFLINE -> "только локально" }} · ${network.transport}${if (network.vpn) " · VPN" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = {
                checking = true; actionMessage = null; report = null
                scope.launch {
                    try { report = repo.runNetworkCheck() }
                    catch (e: Exception) { actionMessage = e.userMessage() }
                    finally { checking = false }
                }
            }, enabled = !checking) {
                if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.NetworkCheck, null)
                Spacer(Modifier.width(8.dp)); Text(if (checking) "Проверяем…" else "Проверить сеть")
            }
            TextButton(onClick = {
                scope.launch {
                    actionMessage = try { repo.reconnectNow(); "Переподключение запущено" }
                    catch (e: Exception) { e.userMessage() }
                }
            }, enabled = !checking) { Text("Переподключиться") }
            report?.let { result ->
                AppearanceSurface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (result.successful) "Все проверки пройдены" else "Обнаружена проблема", style = MaterialTheme.typography.titleSmall)
                        result.items.forEach { item -> Text(
                            "${if (item.ok) "✓" else "✕"} ${item.name}: ${item.detail} · ${item.durationMs} мс",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        ) }
                        TextButton(onClick = {
                            runCatching {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TITLE, "Umbra — проверка сети")
                                    putExtra(Intent.EXTRA_TEXT, result.asText())
                                }
                                context.startActivity(Intent.createChooser(send, "Отправить отчёт"))
                            }.onFailure { actionMessage = "Не удалось открыть список приложений для отправки отчёта." }
                        }) { Text("Отправить отчёт") }
                    }
                }
            }
            actionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    SettingsCard("Журнал диагностики") {
        Text(
            "Журнал технических событий: ошибки синхронизации и отправки (содержимое сообщений не записывается). Если что-то работает не так — отправьте журнал разработчику.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = {
            logMessage = null
            runCatching {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "Umbra — журнал диагностики")
                    putExtra(Intent.EXTRA_TEXT, DiagLog.text())
                }
                context.startActivity(Intent.createChooser(send, "Отправить журнал"))
            }.onFailure { logMessage = "Не удалось открыть список приложений для отправки журнала." }
        }) { Text("Отправить журнал") }
        TextButton(onClick = { DiagLog.clear(); logMessage = "Журнал очищен" }) { Text("Очистить журнал") }
        logMessage?.let { SettingsDescription(it) }
    }
}

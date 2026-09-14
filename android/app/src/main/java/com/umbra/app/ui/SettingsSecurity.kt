package com.umbra.app.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.umbra.app.data.api.AuthSession
import com.umbra.app.data.repo.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun DevicesDialog(repo: ChatRepository, onDismiss: () -> Unit) {
    var devices by remember { mutableStateOf<List<AuthSession>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<AuthSession?>(null) }
    var confirmOthers by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun reload() { devices = repo.sessions() }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { action(); reload() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.userMessage() }
            finally { busy = false }
        }
    }
    LaunchedEffect(repo) {
        try { reload() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.userMessage() }
        finally { busy = false }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Устройства") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Названия устройств сообщают сами приложения. Если вход вам неизвестен, завершите его сессию.")
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                devices.forEach { device ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(device.deviceName.ifBlank { "Неизвестное устройство" }, style = MaterialTheme.typography.titleSmall)
                        if (device.current) Text("Это устройство", color = MaterialTheme.colorScheme.primary)
                        Text("Вход: ${sessionTime(device.createdAt)}", style = MaterialTheme.typography.bodySmall)
                        Text("Обновление сессии: ${sessionTime(device.lastSeenAt)}", style = MaterialTheme.typography.bodySmall)
                        if (!device.current) TextButton({ pending = device }, enabled = !busy) { Text("Завершить сессию") }
                    }
                    HorizontalDivider()
                }
                TextButton({ perform { } }, enabled = !busy) { Text("Обновить") }
                if (devices.any { !it.current }) TextButton({ confirmOthers = true }, enabled = !busy) { Text("Завершить все другие сессии") }
            }
        }, confirmButton = { TextButton(onDismiss, enabled = !busy) { Text("Закрыть") } })
    if (pending != null || confirmOthers) AlertDialog(onDismissRequest = { pending = null; confirmOthers = false },
        title = { Text("Завершить ${if (confirmOthers) "другие сессии" else "сессию"}?") },
        text = { Text("На выбранных устройствах потребуется новый вход. Уже загруженные файлы могут сохраниться.") },
        confirmButton = { TextButton({
            val id = pending?.id; val all = confirmOthers
            pending = null; confirmOthers = false
            perform { if (all) repo.revokeOtherSessions() else if (id != null) repo.revokeSession(id) }
        }) { Text("Завершить") } },
        dismissButton = { TextButton({ pending = null; confirmOthers = false }) { Text("Отмена") } })
}

@Composable
internal fun AccountDeletionDialog(repo: ChatRepository, onDismiss: () -> Unit) {
    var requestId by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resendAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(resendAt) {
        now = SystemClock.elapsedRealtime()
        while (now < resendAt) { delay(1000); now = SystemClock.elapsedRealtime() }
    }
    val seconds = ((resendAt - now + 999) / 1000).coerceAtLeast(0)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Удалить аккаунт навсегда?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Будут удалены аккаунт, его сообщения, созданные вами группы и локальная история. Файлы будут поставлены в очередь удаления. Чужие копии и резервные копии могут сохраниться.")
                Text("Запросите отдельный код удаления у владельца сервера. Код входа сюда не подходит.")
                TextButton(enabled = !busy && seconds == 0L, onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            val response = repo.requestDeletionCode()
                            requestId = response.requestId; code = ""
                            resendAt = SystemClock.elapsedRealtime() + response.retryAfter.coerceIn(60, 1800) * 1000L
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.userMessage() }
                        finally { busy = false }
                    }
                }) { Text(if (seconds > 0) "Повторить через $seconds с" else "Получить код удаления") }
                if (requestId.isNotBlank()) OutlinedTextField(value = code,
                    onValueChange = { code = it.filter { c -> c in '0'..'9' }.take(6) },
                    label = { Text("Код удаления") }, enabled = !busy, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = {
            TextButton(enabled = !busy && requestId.isNotBlank() && code.length == 6, onClick = {
                busy = true; error = null
                scope.launch {
                    try { repo.deleteAccount(requestId, code); onDismiss() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = "Удаление не подтверждено. " + e.userMessage() }
                    finally { busy = false }
                }
            }) { Text("Удалить навсегда", color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("Отмена") } })
}

private fun sessionTime(value: String): String = try {
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
} catch (_: Exception) { "—" }

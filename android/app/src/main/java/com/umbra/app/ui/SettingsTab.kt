package com.umbra.app.ui

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.umbra.app.data.InputRules
import com.umbra.app.data.repo.ChatRepository
import com.umbra.app.di.AppContainer
import com.umbra.app.data.session.ThemeMode
import com.umbra.app.data.diag.DiagLog
import com.umbra.app.data.session.UiPreferences
import com.umbra.app.ui.theme.LocalUmbraChatColors
import com.umbra.app.ui.theme.LocalUmbraMessageTextStyle
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SettingsTab(container: AppContainer) {
    val repo = container.chatRepository
    val me by repo.account.collectAsState()
    val scope = rememberCoroutineScope()
    var editProfile by rememberSaveable { mutableStateOf(false) }
    var confirmBurn by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var showStorageInfo by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && !busy) {
            busy = true; error = null
            scope.launch {
                try { repo.uploadAndSetAvatar(uri) }
                catch (e: Exception) { error = e.userMessage() }
                finally { busy = false }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        GlassRow(if (busy) null else ({ editProfile = true })) {
            Avatar(repo, me.avatarMediaId, me.displayName, 72.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(listOf(me.displayName, me.lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Без имени" }, style = MaterialTheme.typography.titleLarge)
                Text("@${me.username}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Text(me.phone, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Filled.ChevronRight, "Редактировать профиль", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.fillMaxWidth(), enabled = !busy) {
            Icon(Icons.Filled.AddAPhoto, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Изменить фото профиля")
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        AppearanceSettings(container.uiPreferences, repo)
        GlassRow({ showStorageInfo = true }) {
            Icon(Icons.Filled.Storage, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Хранение данных", style = MaterialTheme.typography.titleSmall)
                Text("На сервере владельца Umbra", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(4.dp)) {
                TextButton({ confirmLogout = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy) { Text("Выйти из аккаунта") }
                TextButton({ error = null; confirmBurn = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy) { Text("Удалить аккаунт", color = MaterialTheme.colorScheme.error) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (showStorageInfo) AlertDialog(onDismissRequest = { showStorageInfo = false },
        title = { Text("Хранение данных") },
        text = { Text("Сообщения и фото хранятся на сервере владельца Umbra. Сквозное шифрование в этой версии не используется.") },
        confirmButton = { TextButton({ showStorageInfo = false }) { Text("Понятно") } })
    if (editProfile) {
        var name by rememberSaveable { mutableStateOf(me.displayName) }
        var lastName by rememberSaveable { mutableStateOf(me.lastName) }
        var username by rememberSaveable { mutableStateOf(me.username) }
        var saving by remember { mutableStateOf(false) }
        var problem by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { if (!saving) editProfile = false }, title = { Text("Мой профиль") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileFields(name, lastName, username, { name = it; problem = null }, { lastName = it; problem = null }, { username = it; problem = null }, !saving)
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = {
            TextButton(enabled = !saving && name.isNotBlank() && InputRules.validUsername(username), onClick = {
                if (!saving) {
                    saving = true; problem = null
                    scope.launch {
                        try { repo.updateProfile(name, lastName, username); editProfile = false }
                        catch (e: Exception) { problem = e.userMessage() }
                        finally { saving = false }
                    }
                }
            }) { Text("Сохранить") }
        }, dismissButton = { TextButton({ editProfile = false }, enabled = !saving) { Text("Отмена") } })
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("Выйти из аккаунта?") },
        text = { Text("Для входа понадобится новый код. Неотправленные сообщения сохранятся на этом устройстве для этого номера. При входе в другой аккаунт локальные данные будут очищены.") },
        confirmButton = { TextButton({ confirmLogout = false; scope.launch { repo.logout() } }) { Text("Выйти") } },
        dismissButton = { TextButton({ confirmLogout = false }) { Text("Отмена") } })
    if (confirmBurn) AlertDialog(onDismissRequest = { if (!busy) confirmBurn = false }, title = { Text("Удалить аккаунт навсегда?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Будут удалены аккаунт, его сообщения, созданные вами группы и локальная история. Файлы будут поставлены в очередь удаления. Чужие копии и резервные копии могут сохраниться.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } }, confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (!busy) {
                    busy = true; error = null
                    scope.launch {
                        try { repo.deleteAccount() }
                        catch (e: Exception) { error = "Удаление не подтверждено. " + e.userMessage() }
                        finally { busy = false }
                    }
                }
            }) { Text("Удалить навсегда", color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton({ confirmBurn = false }, enabled = !busy) { Text("Отмена") } })
}

@Composable
private fun AppearanceSettings(preferences: UiPreferences, repo: ChatRepository) {
    val appearance by preferences.state.collectAsState()
    val palette = LocalUmbraChatColors.current
    var previewSize by remember(appearance.messageTextSize) { mutableFloatStateOf(appearance.messageTextSize.toFloat()) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Оформление", style = MaterialTheme.typography.titleMedium)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PreferenceSwitch("Цвета обоев", "Использовать системную палитру", appearance.dynamicColor, preferences::setDynamicColor)
            }
        }
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
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
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = if (appearance.alienInterface) androidx.compose.foundation.BorderStroke(1.dp, Brush.linearGradient(listOf(Color(0xFF72FFF1), Color(0xFFE957FF)))) else null,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PreferenceSwitch(
                "Alien Interface",
                "Живой космический фон, голографическое стекло и орбитальная навигация",
                appearance.alienInterface,
                preferences::setAlienInterface,
            )
            if (appearance.alienInterface) {
                Box(
                    Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF061224), Color(0xFF160927), Color(0xFF031A1D)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.matchParentSize()) {
                        drawCircle(Color(0x4472FFF1), size.minDimension * 0.42f, center.copy(x = size.width * 0.25f))
                        drawCircle(Color(0x33E957FF), size.minDimension * 0.5f, center.copy(x = size.width * 0.78f))
                    }
                    Text("ALIEN MODE ACTIVE", color = Color(0xFFBFFFF8), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        PreferenceSwitch("Уменьшить анимацию", "Спокойные переходы между экранами", appearance.reduceMotion, preferences::setReduceMotion, Modifier.padding(16.dp))
    }
    DiagnosticsCard(repo)
}

@Composable
private fun DiagnosticsCard(repo: ChatRepository) {
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
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Диагностика", style = MaterialTheme.typography.titleMedium)
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
                checking = true; actionMessage = null
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
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
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
                            }
                        }) { Text("Отправить отчёт") }
                    }
                }
            }
            actionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
                "Журнал технических событий: ошибки синхронизации и отправки (содержимое сообщений не записывается). Если что-то работает не так — отправьте журнал разработчику.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                runCatching {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, "Umbra — журнал диагностики")
                        putExtra(Intent.EXTRA_TEXT, DiagLog.text())
                    }
                    context.startActivity(Intent.createChooser(send, "Отправить журнал"))
                }
            }) { Text("Отправить журнал") }
            TextButton(onClick = { DiagLog.clear(); actionMessage = "Журнал очищен" }) { Text("Очистить журнал") }
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

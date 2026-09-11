package com.umbra.app.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.umbra.app.data.InputRules
import com.umbra.app.data.repo.ChatRepository
import com.umbra.app.di.AppContainer
import com.umbra.app.data.session.ThemeMode
import com.umbra.app.data.session.UiPreferences
import com.umbra.app.ui.theme.LocalUmbraChatColors
import com.umbra.app.ui.theme.LocalUmbraMessageTextStyle
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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
        AppearanceSettings(container.uiPreferences)
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
private fun AppearanceSettings(preferences: UiPreferences) {
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
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        PreferenceSwitch("Уменьшить анимацию", "Спокойные переходы между экранами", appearance.reduceMotion, preferences::setReduceMotion, Modifier.padding(16.dp))
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

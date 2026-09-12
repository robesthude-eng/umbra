package com.umbra.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.umbra.app.data.InputRules
import com.umbra.app.di.AppContainer
import kotlinx.coroutines.launch

@Composable
internal fun SettingsTab(container: AppContainer) {
    val repo = container.chatRepository
    val me by repo.account.collectAsState()
    val scope = rememberCoroutineScope()
    // A stable name survives rotation, process recreation and MainShell tab changes.
    var categoryName by rememberSaveable { mutableStateOf<String?>(null) }
    val category = SettingsCategory.entries.firstOrNull { it.name == categoryName }
    val pages = rememberSaveableStateHolder()
    var editProfile by rememberSaveable { mutableStateOf(false) }
    var confirmBurn by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
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
    BackHandler(enabled = category != null && !editProfile && !confirmBurn && !confirmLogout) {
        categoryName = null
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            val title = category?.title ?: "Настройки"
            Row(
                Modifier.fillMaxWidth().semantics { paneTitle = title }
                    .padding(start = if (category == null) 20.dp else 4.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (category != null) {
                    IconButton(onClick = { categoryName = null }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад к настройкам")
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
            }
            val pageKey = category?.name ?: "overview"
            ScreenEntrance(pageKey, Modifier.weight(1f).fillMaxWidth()) {
                pages.SaveableStateProvider(pageKey) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        when (category) {
                            null -> SettingsOverview(repo, container.uiPreferences) { categoryName = it.name }
                            SettingsCategory.PROFILE -> ProfileSettings(
                                repo = repo, busy = busy,
                                onEdit = { error = null; editProfile = true },
                                onPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            )
                            SettingsCategory.APPEARANCE -> AppearanceSettings(container.uiPreferences)
                            SettingsCategory.NOTIFICATIONS -> NotificationSettings()
                            SettingsCategory.NETWORK -> NetworkSettings(repo)
                            SettingsCategory.STORAGE -> StorageSettings()
                            SettingsCategory.PRIVACY -> PrivacySettings(
                                busy = busy,
                                onLogout = { error = null; confirmLogout = true },
                                onDelete = { error = null; confirmBurn = true },
                            )
                            SettingsCategory.ABOUT -> AboutSettings(container)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
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

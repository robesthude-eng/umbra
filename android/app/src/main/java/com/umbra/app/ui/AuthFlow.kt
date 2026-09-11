package com.umbra.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.umbra.app.data.InputRules
import com.umbra.app.data.repo.SessionPhase
import com.umbra.app.di.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AuthStep { PHONE, CODE, PROFILE }

@Composable
fun AuthScreen(container: AppContainer, onDone: () -> Unit) {
    val repo = container.chatRepository
    val phase by repo.phase.collectAsState()
    var step by rememberSaveable { mutableStateOf(AuthStep.PHONE.name) }
    var phone by rememberSaveable { mutableStateOf(repo.accountInfo().phone) }
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(repo.accountInfo().displayName) }
    var lastName by rememberSaveable { mutableStateOf(repo.accountInfo().lastName) }
    var username by rememberSaveable { mutableStateOf("") }
    var avatarUri by rememberSaveable { mutableStateOf<String?>(null) }
    var resendAt by rememberSaveable { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { avatarUri = uri.toString(); error = null }
    }
    val profileStep = phase == SessionPhase.NEEDS_PROFILE
    val currentStep = if (profileStep) AuthStep.PROFILE else AuthStep.valueOf(step).let { if (it == AuthStep.PROFILE) AuthStep.PHONE else it }
    LaunchedEffect(phase) { if (phase == SessionPhase.READY) onDone() }
    LaunchedEffect(resendAt) {
        now = System.currentTimeMillis()
        while (now < resendAt) { delay(1000); now = System.currentTimeMillis() }
    }
    fun backToPhone() { step = AuthStep.PHONE.name; code = ""; error = null }
    BackHandler(currentStep == AuthStep.CODE) { if (!loading) backToPhone() }
    BackHandler(loading) { /* Let an in-flight action finish. */ }

    fun requestCode() {
        if (loading) return
        loading = true; error = null
        scope.launch {
            try {
                repo.requestCode(phone)
                phone = InputRules.normalizePhone(phone) ?: phone
                code = ""; step = AuthStep.CODE.name
                resendAt = System.currentTimeMillis() + 60_000L
            } catch (e: Exception) { error = e.userMessage() }
            finally { loading = false }
        }
    }
    fun verifyCode() {
        if (loading || code.length != 6) return
        loading = true; error = null
        scope.launch {
            try { repo.verifyCode(phone, code) }
            catch (e: Exception) { error = e.userMessage() }
            finally { loading = false }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.primaryContainer)))
            .safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
            Text("U", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.headlineLarge)
        }
        Spacer(Modifier.height(12.dp))
        Text("Umbra", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
        Text("Мессенджер для семьи", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (currentStep) {
                    AuthStep.PHONE -> {
                        Text("Вход по номеру телефона", style = MaterialTheme.typography.titleLarge)
                        Text("Код придёт владельцу Umbra в Telegram. Попросите его передать вам код.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            phone, { phone = it.take(32); error = null }, Modifier.fillMaxWidth(), enabled = !loading,
                            label = { Text("Номер телефона") }, placeholder = { Text("+7 999 123-45-67") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (InputRules.normalizePhone(phone) != null) requestCode() }),
                        )
                        Button(::requestCode, Modifier.fillMaxWidth(), enabled = !loading && InputRules.normalizePhone(phone) != null) { Text("Получить код") }
                    }
                    AuthStep.CODE -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(::backToPhone, enabled = !loading) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Изменить номер") }
                            Text("Код для $phone", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        }
                        Text("Попросите у владельца код из Telegram. Он действует 5 минут.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            code, { code = it.filter { ch -> ch in '0'..'9' }.take(6); error = null }, Modifier.fillMaxWidth(),
                            enabled = !loading, label = { Text("Код из 6 цифр") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { verifyCode() }),
                        )
                        Button(::verifyCode, Modifier.fillMaxWidth(), enabled = !loading && code.length == 6) { Text("Войти") }
                        val seconds = ((resendAt - now + 999L) / 1000L).coerceAtLeast(0L)
                        TextButton(::requestCode, Modifier.fillMaxWidth(), enabled = !loading && seconds == 0L) {
                            Text(if (seconds > 0) "Повторить через $seconds с" else "Запросить новый код")
                        }
                    }
                    AuthStep.PROFILE -> {
                        Text("Расскажите о себе", style = MaterialTheme.typography.titleLarge)
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            AvatarPhoto(avatarUri, 80.dp, if (loading) null else ({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }))
                        }
                        if (avatarUri != null) TextButton({ avatarUri = null; error = null }, enabled = !loading) { Text("Убрать выбранное фото") }
                        ProfileFields(name, lastName, username,
                            { name = it; error = null }, { lastName = it; error = null }, { username = it; error = null }, !loading)
                        Button(onClick = {
                            if (!loading) {
                                loading = true; error = null
                                scope.launch {
                                    try {
                                        avatarUri?.let { repo.uploadAndSetAvatar(Uri.parse(it)); avatarUri = null }
                                        repo.updateProfile(name, lastName, username)
                                    } catch (e: Exception) { error = e.userMessage() }
                                    finally { loading = false }
                                }
                            }
                        }, Modifier.fillMaxWidth(), enabled = !loading && name.isNotBlank() && InputRules.validUsername(username)) { Text("Продолжить") }
                        TextButton({ scope.launch { repo.logout() } }, enabled = !loading) { Text("Войти с другим номером") }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun ProfileFields(name: String, lastName: String, username: String,
    onName: (String) -> Unit, onLastName: (String) -> Unit, onUsername: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(name, { onName(it.take(64)) }, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true, label = { Text("Имя *") })
    OutlinedTextField(lastName, { onLastName(it.take(64)) }, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true, label = { Text("Фамилия") })
    OutlinedTextField(username, { onUsername(it.take(33)) }, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
        label = { Text("@никнейм *") }, isError = username.isNotEmpty() && !InputRules.validUsername(username),
        supportingText = { Text("3–32 латинские буквы, цифры или _") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrect = false))
}

package com.umbra.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.umbra.app.data.repo.SessionPhase
import com.umbra.app.di.AppContainer
import com.umbra.app.ui.theme.UmbraColors
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import android.graphics.BitmapFactory
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap

/** Шаг регистрации/входа. */
private enum class AuthStep { PHONE, CODE, PROFILE }

/** Экран «номер → код из Telegram → профиль». После входа вызывает onDone(). */
@Composable
fun AuthScreen(container: AppContainer, onDone: () -> Unit) {
    val repo = container.chatRepository
    var step by rememberSaveable { mutableStateOf(AuthStep.PHONE.name) }
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var lastName by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var avatarUri by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) avatarUri = uri.toString()
    }

    val phase by repo.phase.collectAsState()

    // Профиль не заполнен после входа — показать его даже после перезапуска приложения.
    LaunchedEffect(phase) {
        if (phase == SessionPhase.NEEDS_PROFILE) step = AuthStep.PROFILE.name
        if (phase == SessionPhase.READY) onDone()
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(UmbraColors.Deep, Color(0xFF10273C)))),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        GlassLogo()
        Spacer(Modifier.height(16.dp))
        Text("Umbra", style = MaterialTheme.typography.headlineLarge, color = UmbraColors.Ice)
        Text("Приватный мессенджер семьи", color = UmbraColors.Fog, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))

        when (AuthStep.valueOf(step)) {
            AuthStep.PHONE -> PhoneStep(
                phone = phone, onPhone = { phone = it }, loading = loading,
                onSubmit = {
                    if (phone.isBlank()) { error = "Введите номер телефона"; return@PhoneStep }
                    scope.launch {
                        loading = true; error = null
                        try { repo.requestCode(phone); step = AuthStep.CODE.name }
                        catch (e: Exception) { error = e.userMessage() }
                        finally { loading = false }
                    }
                },
            )
            AuthStep.CODE -> CodeStep(
                phone = phone, code = code, onCode = { code = it }, loading = loading,
                onBack = { step = AuthStep.PHONE.name },
                onSubmit = {
                    if (code.length != 6) { error = "Код — 6 цифр"; return@CodeStep }
                    scope.launch {
                        loading = true; error = null
                        try {
                            val res = repo.verifyCode(phone, code)
                            if (res.profileComplete || !res.newAccount && repo.accountInfo().displayName.isNotBlank()) onDone()
                            else step = AuthStep.PROFILE.name
                        } catch (e: Exception) { error = e.userMessage() }
                        finally { loading = false }
                    }
                },
            )
            AuthStep.PROFILE -> ProfileStep(
                avatarUri = avatarUri,
                name = name, lastName = lastName, username = username,
                onAvatarPick = { avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onName = { name = it }, onLastName = { lastName = it }, onUsername = { username = it },
                loading = loading,
                onBack = { step = AuthStep.CODE.name },
                onSubmit = {
                    if (name.isBlank()) { error = "Введите имя"; return@ProfileStep }
                    if (username.isBlank()) { error = "Введите @никнейм — он обязателен"; return@ProfileStep }
                    scope.launch {
                        loading = true; error = null
                        try {
                            // Сначала фото: updateProfile переводит фазу в READY и AuthScreen
                            // уйдёт из композиции (корутина отменилась бы на полпути).
                            avatarUri?.let { uri ->
                                runCatching { repo.uploadAndSetAvatar(Uri.parse(uri)) }
                            }
                            repo.updateProfile(name.trim(), lastName.trim(), username.trim())
                            onDone()
                        } catch (e: Exception) { error = e.userMessage() }
                        finally { loading = false }
                    }
                },
            )
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = UmbraColors.Danger, style = MaterialTheme.typography.bodyMedium)
        }
        if (loading) { Spacer(Modifier.height(12.dp)); CircularProgressIndicator(color = UmbraColors.Aqua) }
    }
}

@Composable
private fun GlassLogo() {
    Box(
        Modifier.size(76.dp).clip(CircleShape).background(UmbraColors.headerGradient),
        contentAlignment = Alignment.Center,
    ) { Text("U", color = Color.White, style = MaterialTheme.typography.headlineLarge) }
}

@Composable
private fun PhoneStep(phone: String, onPhone: (String) -> Unit, loading: Boolean, onSubmit: () -> Unit) {
    GlassCard {
        Text("Введите номер телефона", style = MaterialTheme.typography.titleMedium, color = UmbraColors.Ice)
        Spacer(Modifier.height(4.dp))
        Text("Код подтверждения придёт вам в Telegram", style = MaterialTheme.typography.bodySmall, color = UmbraColors.Fog)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = phone, onValueChange = { onPhone(it.take(20)) }, singleLine = true,
            label = { Text("Номер", color = UmbraColors.Fog) },
            placeholder = { Text("+7 999 123-45-67", color = UmbraColors.Mist) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = UmbraColors.Ice),
            colors = glassFieldColors(),
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onSubmit, enabled = !loading && phone.isNotBlank(),
            modifier = Modifier.fillMaxWidth(), colors = glassButtonColors()) { Text("Получить код") }
    }
}

@Composable
private fun CodeStep(phone: String, code: String, onCode: (String) -> Unit, loading: Boolean,
                    onBack: () -> Unit, onSubmit: () -> Unit) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = UmbraColors.Ice) }
            Text("Код для $phone", style = MaterialTheme.typography.titleMedium, color = UmbraColors.Ice)
        }
        Text("Скажите владельцу Umbra, что ждёте код, — он продиктует его из Telegram.",
            style = MaterialTheme.typography.bodySmall, color = UmbraColors.Fog)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = code, onValueChange = { onCode(it.filter { c -> c.isDigit() }.take(6)) }, singleLine = true,
            label = { Text("6 цифр", color = UmbraColors.Fog) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = MaterialTheme.typography.headlineMedium.copy(color = UmbraColors.Ice),
            colors = glassFieldColors(),
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onSubmit, enabled = !loading && code.length == 6,
            modifier = Modifier.fillMaxWidth(), colors = glassButtonColors()) { Text("Войти") }
    }
}

/** Экран профиля нового аккаунта: аватар-кружок, @ник, имя (обязательно), фамилия (необязательно). */
@Composable
fun ProfileStep(avatarUri: String?, name: String, lastName: String, username: String,
                onAvatarPick: () -> Unit, onName: (String) -> Unit, onLastName: (String) -> Unit,
                onUsername: (String) -> Unit, loading: Boolean,
                onBack: (() -> Unit)? = null, onSubmit: () -> Unit) {
    GlassCard {
        onBack?.let { back ->
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = UmbraColors.Ice) }
        }
        Text("Расскажите о себе", style = MaterialTheme.typography.titleMedium, color = UmbraColors.Ice)
        Spacer(Modifier.height(12.dp))

        // Кружок-аватар
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            AvatarPhoto(avatarUri = avatarUri, size = 96.dp, onClick = onAvatarPick)
        }
        Spacer(Modifier.height(16.dp))

        GlassField("Имя *", name, { onName(it.take(64)) })
        Spacer(Modifier.height(10.dp))
        GlassField("Фамилия", lastName, { onLastName(it.take(64)) })
        Spacer(Modifier.height(10.dp))
        GlassField("@никнейм *", username, { onUsername(it.filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '.' }.take(32)) })
        Spacer(Modifier.height(20.dp))
        Button(onClick = onSubmit, enabled = !loading && name.isNotBlank() && username.isNotBlank(),
            modifier = Modifier.fillMaxWidth(), colors = glassButtonColors()) { Text(if (loading) "Сохраняем…" else "Продолжить") }
    }
}

@Composable
private fun AvatarPhoto(avatarUri: String?, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var bytes by remember(avatarUri) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(avatarUri) {
        bytes = avatarUri?.let { uri ->
            runCatching { ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() } }.getOrNull()
        }
    }
    Box(
        Modifier.size(size).clip(CircleShape).clickable(onClick = onClick).background(UmbraColors.headerGradient),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size).clip(CircleShape))
        } else {
            Text("＋", color = Color.White, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(0.92f)
            .background(Color(0xCC122B40), shape = MaterialTheme.shapes.large)
            .padding(24.dp),
        content = content,
    )
}

@Composable
private fun GlassField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onValue, singleLine = true,
        label = { Text(label, color = UmbraColors.Fog) },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = UmbraColors.Ice),
        colors = glassFieldColors(),
    )
}

@Composable
private fun glassFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = UmbraColors.Aqua, unfocusedBorderColor = UmbraColors.Mist,
    cursorColor = UmbraColors.Aqua, focusedLabelColor = UmbraColors.Aqua,
)

@Composable
private fun glassButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color(0xFF1E96E8), contentColor = Color.White,
)

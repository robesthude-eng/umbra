package com.umbra.app.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.umbra.app.BuildConfig
import com.umbra.app.data.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Описание последнего релиза: nginx отдаёт его статикой как /app/latest.json. */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    /** Путь к APK от корня сервера, например "/app/umbra-latest.apk". */
    val apk: String,
    /** Контрольная сумма APK (sha256, hex); проверяется после скачивания. */
    val sha256: String = "",
    /** Короткое описание изменений для диалога. */
    val notes: String = "",
)

/**
 * Самообновление приложения.
 *
 * Сервер отдаёт /app/latest.json и APK как статические файлы. Клиент при
 * запуске сравнивает versionCode со своей сборкой; если сервер новее —
 * предлагает обновление, скачивает APK в приватный кэш, сверяет контрольную
 * сумму и открывает системный установщик. Подпись сборок стабильная, поэтому
 * установка проходит как обновление поверх, без потери данных.
 *
 * Секретов в клиенте нет: файлы публичные, как и сам APK.
 */
class AppUpdater(
    private val context: Context,
    baseUrl: String,
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val client = client

    /** Результат последней проверки с меткой времени (метка меняется — подписчики видят каждую проверку). */
    data class UpdateState(val checkedAtMillis: Long, val info: UpdateInfo?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _updateState = MutableStateFlow(UpdateState(0L, null))

    /** Последний результат проверки: UmbraRoot показывает диалог, настройки — статус кнопки. */
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()
    private var lastCheckAtMillis = 0L

    /**
     * Вызывается из Activity.onStart — срабатывает и на холодном старте, и на
     * каждом возврате из фона: процесс Android живёт неделями, и «переоткрытие»
     * приложения без этого не проверяет версию.
     */
    fun onAppStart() {
        scope.launch { runCheck() }
    }

    /**
     * Проверка с защитой от лишних запросов: автоматически не чаще раза в
     * 15 минут, вручную (кнопка в настройках) — всегда.
     */
    suspend fun runCheck(force: Boolean = false): UpdateInfo? {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckAtMillis < 15 * 60_000L) return _updateState.value.info
        lastCheckAtMillis = now
        val info = check()
        _updateState.value = UpdateState(now, info)
        return info
    }

    /**
     * Проверка обновления. null — сборка актуальна либо сервер не ответил:
     * проверка фоновая и молчаливая, ошибки пользователю не показываем.
     */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        if (base.isEmpty()) return@withContext null
        val body = runCatching {
            client.newCall(Request.Builder().url("$base/app/latest.json").build()).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.bytes()?.decodeToString()
            }
        }.onFailure { DiagLog.log("update-check", it) }.getOrNull() ?: return@withContext null
        val info = runCatching { json.decodeFromString<UpdateInfo>(body) }
            .onFailure { DiagLog.log("update-check", it) }
            .getOrNull()
            ?: return@withContext null
        // Предлагаем только более новую сборку: сервер знает больший versionCode.
        info.takeIf { it.versionCode > BuildConfig.VERSION_CODE && it.apk.isNotBlank() }
    }

    /**
     * Готовый к установке файл, если эта сборка уже скачана и цела.
     * Позволяет показать сразу «Установить» без повторной загрузки трафика.
     */
    fun downloadedApk(info: UpdateInfo): File? {
        val target = File(File(context.cacheDir, "updates"), "umbra-${info.versionCode}.apk")
        if (!target.isFile || target.length() <= 0L) return null
        if (info.sha256.isNotBlank() && !sha256Hex(target).equals(info.sha256, ignoreCase = true)) {
            target.delete()
            return null
        }
        return target
    }

    /**
     * Скачивает APK в приватный кэш (updates/), ведёт прогресс (0..100)
     * и проверяет sha256 из latest.json. При несовпадении файл удаляется.
     *
     * Экономия трафика: уже скачанный целый файл возвращается сразу; недкачанный
     * догружается через Range (если сервер умеет, ответ 206), старые версии чистятся.
     */
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "umbra-${info.versionCode}.apk")
        val part = File(dir, "umbra-${info.versionCode}.apk.part")
        // Кэш на будущее не нужен: всё, что не относится к этой сборке, удаляем.
        dir.listFiles()?.forEach { f ->
            if (f != target && f != part) f.delete()
        }
        downloadedApk(info)?.let {
            onProgress(100)
            return@withContext it
        }
        val url = if (info.apk.startsWith("http")) info.apk else base + info.apk
        var alreadyRead = if (part.isFile) part.length() else 0L
        val request = Request.Builder().url(url).apply {
            if (alreadyRead > 0L) header("Range", "bytes=$alreadyRead-")
        }.build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Сервер вернул ${resp.code} при скачивании обновления")
            val body = resp.body ?: error("Пустой ответ при скачивании обновления")
            // 206 — сервер поддержал докачку; иначе начинаем файл заново.
            val resuming = resp.code == 206 && alreadyRead > 0L
            if (!resuming) alreadyRead = 0L
            val total = body.contentLength().takeIf { it > 0 }?.plus(alreadyRead) ?: -1L
            var read = alreadyRead
            var lastPercent = -1
            body.byteStream().use { input ->
                java.io.FileOutputStream(part, resuming).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = (read * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        }
        if (info.sha256.isNotBlank() && !sha256Hex(part).equals(info.sha256, ignoreCase = true)) {
            part.delete()
            error("Скачанный файл повреждён: контрольная сумма не совпала")
        }
        target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        onProgress(100)
        target
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Открывает системный установщик. На Android 8+ перед этим нужно разовое
     * разрешение «устанавливать неизвестные приложения» — если его нет,
     * открываем нужный экран настроек и возвращаем false: пользователь
     * вернётся в приложение и нажмёт «Установить» ещё раз.
     */
    /** Есть ли разрешение «устанавливать неизвестные приложения» (нужно для подсказки в UI). */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun install(apk: File): Boolean {
        if (!canInstall()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}

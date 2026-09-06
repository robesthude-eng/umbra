# Umbra — Android-клиент

Нативный клиент мессенджера Umbra: Kotlin + Jetpack Compose + libsignal.

## Архитектура

- **Слои данных**: Retrofit (HTTP API) + OkHttp WebSocket (realtime) + Room (кэш/очередь).
- **Криптография**: Signal Protocol (X3DH + Double Ratchet) через `libsignal-client`;
  Ed25519 — для аутентификации (подпись challenge).
- **Хранение ключей**: приватные ключи в `EncryptedSharedPreferences` (мастер-ключ в
  Android Keystore). В БД — только ciphertext, открытый текст расшифровывается в памяти.

## Сборка

```bash
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Требования: JDK 17, Android SDK (compileSdk 35).

## Настройка сервера

По умолчанию клиент обращается к `http://10.0.2.2:8080` (эмулятор → хост-машина).
Для реального устройства укажите адрес вашего сервера в
`di/AppContainer.kt` (`baseUrl`).

## Важно о приватности

- Приватные ключи никогда не покидают устройство.
- Сервер хранит только ciphertext и метаданные.
- Для боевого использования замените `baseUrl` на `https://` домен за пределами РФ
  и включите `android:usesCleartextTraffic="false"` (уже по умолчанию).

## Дорожная карта клиента

- [x] Каркас проекта (Gradle, Compose, Room, Retrofit, WebSocket)
- [x] Криптографический слой (Ed25519 + libsignal X3DH/Double Ratchet)
- [x] Регистрация/вход (username + ключ, без телефона)
- [x] Список чатов и отправка/приём сообщений
- [ ] Медиа (шифрование файлов, загрузка/скачивание)
- [ ] Групповые ключи (Sender Keys / MLS) на клиенте
- [ ] Звонки (WebRTC) на клиенте
- [ ] Секретные сообщения (самоуничтожение)

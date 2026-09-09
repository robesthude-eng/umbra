# Голосовые сообщения Umbra 0.5.0

## Сделано
- Запись: `data/voice/VoiceRecorder.kt` — MediaRecorder, AAC/`audio/mp4`, таймер,
  индикатор громкости, предел 5 минут, минимум 0,7 с, отмена с удалением файла.
- Проигрывание: `data/voice/VoicePlayer.kt` — MediaPlayer, одна активная запись,
  прогресс каждые 200 мс, файл из локального кэша или скачивание.
- Конверт: `kind: "voice"` и `media.durationMs` в `data/msg/MessageContent.kt`;
  подпись «Голосовое сообщение · 0:07» для списка чатов и пузыря.
- Отправка: `sendVoice` и `ensureMediaUploaded` в `ChatRepository` — строка сразу
  в очереди, загрузка в `POST /v1/media`, затем конверт с `media.id`; повторы для
  сетевых ошибок и 5xx, отдельные тексты для 413 и 507.
- Кэш и уборка: `voiceFile`, бюджет кэша 256 МБ, удаление локальных записей
  при смене аккаунта и при удалении аккаунта.
- Room 4: колонки `localMediaPath`, `localMediaMime`, `localMediaDurationMs` и
  `MIGRATION_3_4`.
- Интерфейс: кнопка микрофона в пустом поле, панель записи с отменой,
  пузырь с плеером и прогрессом, запрос `RECORD_AUDIO` (`ui/ChatView.kt`).
- Версия Android: versionCode 9, versionName 0.5.0.
- Тесты: `DatabaseMigrationTest` (миграция 3→4) и `UserFlowsTest`
  (`voiceMessageUploadsRecordingThenSendsVoiceEnvelope`).
- Документация: `README.md`, `android/README.md`, `docs/api.md`, `CHANGES.md`.

## Сейчас
- Изменения исходников подготовлены. Серверный код не менялся.
- Сборка, lint и instrumented-тесты не запускались: в среде подготовки архива
  нет Android SDK, Gradle с зависимостями и устройства.
- Открытый вопрос с прошлой фазы: какой versionCode был у 0.4.1 —
  `android/README.md` называл 7, `USER_FIXES.md` называет 6.

## Дальше
- Собрать и проверить: `./gradlew assembleDebug assembleDebugAndroidTest lintDebug`
  и `./gradlew connectedDebugAndroidTest`; при ошибках прислать лог.
- Ручная проверка на устройстве: запись без разрешения и с отказом, отмена,
  предел 5 минут, отправка без сети и досылка после восстановления связи,
  прослушивание своей и входящей записи, групповой чат.
- Обновить приложение на всех устройствах семьи: 0.4.2 и старше покажут
  заглушку вместо записи.

Не входит в эту фазу: файлы и фото в чатах, WebRTC-звонки, push, новые
зависимости, изменения сервера.

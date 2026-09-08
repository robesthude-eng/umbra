-- 009_otp_telegram.sql — вход/регистрация по номеру с кодом из Telegram (v0.4, «как в Telegram»).
--
-- Модель T1 (облачная, как обычные чаты Telegram): аккаунт привязан к номеру,
-- сообщения хранятся на сервере и доступны после входа по коду. Подтверждение
-- номера — кодом, который приходит в Telegram-бот (одноразовая привязка номера
-- к чату бота: член семьи один раз пишет боту свой номер).

-- Привязка «номер телефона (E.164) ↔ Telegram chat_id»: на этот chat_id бот шлёт коды.
CREATE TABLE IF NOT EXISTS tg_bindings (
    phone      TEXT PRIMARY KEY,
    tg_chat_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Профиль пользователя: аватар (id медиа-блока). Имя и @username живут в users.
CREATE TABLE IF NOT EXISTS user_profiles (
    user_id        TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    avatar_media_id TEXT,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 010_last_name.sql — фамилия в профиле (v0.4). display_name хранит имя,
-- last_name — необязательную фамилию.
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_name TEXT;

-- 013_call_participants.sql — групповые звонки (mesh до 4 участников).
--
-- До 0.9.0 звонок был всегда на двоих: caller_id + callee_id. Теперь полный
-- список участников (включая звонящего) лежит в participants. Старые записи
-- остаются валидными: у них массив пуст, и сервер собирает список из
-- caller_id/callee_id. В групповом звонке callee_id по-прежнему заполнен
-- (первый приглашённый) — так продолжают работать клиенты 0.8.0 и не ломается
-- каскадное удаление вместе с пользователем.
--
-- Внимание: массив participants в ON DELETE CASCADE не участвует, поэтому в
-- истории могут остаться id удалённых пользователей. Сами записи о звонках
-- по-прежнему удаляются каскадом от caller_id/callee_id.

ALTER TABLE calls ADD COLUMN IF NOT EXISTS participants TEXT[] NOT NULL DEFAULT '{}';

-- Заполняем участников для звонков, созданных до этой миграции.
UPDATE calls
   SET participants = ARRAY[caller_id, callee_id]
 WHERE cardinality(participants) = 0;

-- «Все звонки этого пользователя» ищутся по массиву участников.
CREATE INDEX IF NOT EXISTS calls_participants_idx ON calls USING GIN (participants);

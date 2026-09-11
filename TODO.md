# Umbra — актуальное состояние после 0.16.7

## Готово

- Надёжная отправка независимо от успешной загрузки истории.
- Tolerant-разбор ISO времени `Z` и смещений вроде `+03:00`.
- Диагностический журнал без содержимого сообщений.
- Точные состояния REST и WebSocket.
- Future UI, Motion Engine и Spatial Media.
- Source-aware медиапереход, Predictive Back, swipe-dismiss и pan/zoom arbitration.
- Телефонная и планшетная компоновки.

## Обязательно проверить перед production

- `assembleDebug`, `assembleRelease`, `lintDebug`.
- Room migration и instrumented tests.
- Два аккаунта на двух устройствах через общий hotspot, отдельный Wi-Fi и VPN.
- HTTP 401/403/429/5xx и отключение WebSocket при работающем REST.
- Android 8/9, 12, 14/15; телефон, foldable и планшет.
- Плавность 60/90/120 Гц, reduced motion, TalkBack и крупный шрифт.

## Следующий продуктовый этап

- Umbra 0.17: Tasks — модель задач, создание из сообщения, раздел «Сегодня», offline queue и синхронизация.

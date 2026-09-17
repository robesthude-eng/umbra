// Package store — абстракция хранилища. Позволяет запускать сервер
// с in-memory хранилищем (разработка/тесты) или PostgreSQL (продакшн).
package store

import (
	"context"
	"errors"
	"time"

	"umbra/server/internal/model"
)

var (
	// ErrNotFound — запись не найдена.
	ErrNotFound = errors.New("store: not found")
	// ErrConflict — конфликт (например, username уже занят).
	ErrConflict = errors.New("store: conflict")
	// ErrForbidden — операция запрещена правилами (например, удаление владельца).
	ErrForbidden = errors.New("store: forbidden")
	ErrQuota     = errors.New("store: media quota exceeded")
)

// Store — единый интерфейс персистентности.
type Store interface {
	CreateSession(context.Context, *model.AuthSession) error
	ListSessions(context.Context, string) ([]model.AuthSession, error)
	RevokeSession(context.Context, string, string) error
	RevokeOtherSessions(context.Context, string, string) error
	SavePushDeviceForSession(context.Context, string, string, string, string) error
	// Пользователи и ключи.
	CreateUser(ctx context.Context, u *model.User) error
	GetUserByUsername(ctx context.Context, username string) (*model.User, error)
	GetUserByID(ctx context.Context, id string) (*model.User, error)
	// GetUserByPhone ищет пользователя по нормализованному номеру E.164.
	GetUserByPhone(ctx context.Context, phone string) (*model.User, error)
	// FindUsersByPhoneHashes возвращает пользователей, чей phone_hash входит
	// в переданный список (приватный поиск контактов). Пользователи без номера
	// не участвуют в поиске.
	FindUsersByPhoneHashes(ctx context.Context, hashes []string) ([]*model.User, error)
	// TakeOneTimePrekey извлекает и УДАЛЯЕТ один одноразовый pre-key (однократное использование).
	TakeOneTimePrekey(ctx context.Context, userID string) ([]byte, error)
	TakePrekeyBundle(ctx context.Context, username string) (*model.User, []byte, error)
	UpdateKeys(ctx context.Context, userID string, keys *model.User) error
	OneTimePrekeyCount(ctx context.Context, userID string) (int, error)

	// Сессионные токены.
	PutToken(ctx context.Context, tokenHash, userID string, expires time.Time) error
	GetUserIDByTokenHash(ctx context.Context, tokenHash string) (string, error)
	// RenewToken extends a live token atomically without changing the credential.
	// Missing, expired, revoked or differently owned tokens return ErrNotFound.
	RenewToken(ctx context.Context, tokenHash, userID string, expires time.Time) (time.Time, error)
	DeleteToken(ctx context.Context, tokenHash string) error

	// Сообщения: API-конверт шифруется PostgreSQL-хранилищем при включённом cloud storage.
	SaveMessage(ctx context.Context, m *model.Message) error
	// ListMessages возвращает личные (recipient_id = userID) и групповые
	// (userID — участник чата) сообщения, созданные после since.
	ListMessages(ctx context.Context, userID string, since time.Time) ([]*model.Message, error)
	ListMessagesPage(ctx context.Context, userID string, since time.Time, afterID string, limit int) ([]*model.Message, error)

	// Метаданные медиа; зашифрованные байты хранятся отдельно в BlobStore.
	SaveMedia(ctx context.Context, m *model.Media) error
	GetMedia(ctx context.Context, id string) (*model.Media, error)
	// BlobReferenced checks physical IDs, including migrated objects.
	BlobReferenced(ctx context.Context, id string) (bool, error)
	// MediaBytesForUser — суммарный объём медиа пользователя (для квоты).
	MediaBytesForUser(ctx context.Context, userID string) (int64, error)
	SaveMediaWithQuota(ctx context.Context, m *model.Media, limit int64) error
	// Upload держит shared lock до записи метаданных; GC — exclusive lock.
	LockBlobs(ctx context.Context, exclusive bool) (func(), error)
	PendingBlobDeletes(ctx context.Context) ([]string, error)
	CompleteBlobDelete(ctx context.Context, id string) error
	PurgeExpired(ctx context.Context, now time.Time) error

	// Чаты, участники и роли.
	CreateChat(ctx context.Context, c *model.Chat) error // создаёт чат и добавляет создателя как owner
	GetChat(ctx context.Context, chatID string) (*model.Chat, error)
	AddMember(ctx context.Context, chatID, userID string, role model.MemberRole) error
	RemoveMember(ctx context.Context, chatID, userID string) error
	GetMember(ctx context.Context, chatID, userID string) (*model.ChatMember, error)
	ListMembers(ctx context.Context, chatID string) ([]*model.ChatMember, error)
	ListChatsForUser(ctx context.Context, userID string) ([]*model.Chat, error)

	// Контакты.
	AddContact(ctx context.Context, userID, contactID string) error
	ListContacts(ctx context.Context, userID string) ([]string, error)

	// Вход по коду из Telegram (v0.4): привязка номера к чату бота.
	// BindTelegram — идемпотентная запись chat_id для номера (номер должен быть E.164).
	BindTelegram(ctx context.Context, phone string, tgChatID int64) error
	// TelegramChatForPhone возвращает chat_id бота, привязанный к номеру.
	TelegramChatForPhone(ctx context.Context, phone string) (int64, error)

	// Профиль аккаунта (имя, фамилия, @username, аватар).
	// UpdateAccountProfile меняет username/имя(firstName)/фамилию(lastName);
	// конфликт по username -> ErrConflict.
	UpdateAccountProfile(ctx context.Context, userID, username, firstName, lastName string) error
	SetAvatar(ctx context.Context, userID, mediaID string) error
	// GetAvatar возвращает id медиа-аватара; ErrNotFound, если аватара нет.
	GetAvatar(ctx context.Context, userID string) (string, error)

	// «Был(а) в сети». TouchPresence вызывается с дебаунсом из requireAuth,
	// поэтому реализация должна быть дешёвой и не ломаться на удалённом пользователе.
	TouchPresence(ctx context.Context, userID string, at time.Time) error
	// GetPresence отдаёт время последнего визита и флаг скрытия.
	GetPresence(ctx context.Context, userID string) (time.Time, bool, error)
	// SetPresenceHidden включает взаимное скрытие «был(а) в сети».
	SetPresenceHidden(ctx context.Context, userID string, hidden bool) error

	// «Прочитано» в личной переписке: курсор чтения readerID по чату с peerID.
	// SetReadCursor не двигает курсор назад; GetReadCursor отдаёт нулевое время,
	// если человек ещё ничего не прочитал, и ErrNotFound на неизвестного пользователя.
	SetReadCursor(ctx context.Context, readerID, peerID string, at time.Time) error
	GetReadCursor(ctx context.Context, readerID, peerID string) (time.Time, error)

	// Прочтения в группах и каналах: курсор на участника.
	// SetChatRead также не двигает курсор назад; ListChatReads отдаёт
	// только тех, кто уже что-то прочитал (пустая карта — не ошибка).
	SetChatRead(ctx context.Context, chatID, readerID string, at time.Time) error
	ListChatReads(ctx context.Context, chatID string) (map[string]time.Time, error)

	// Звонки (метаданные; медиа идёт peer-to-peer).
	SaveCall(ctx context.Context, c *model.Call) error
	GetCall(ctx context.Context, id string) (*model.Call, error)
	UpdateCallStatus(ctx context.Context, id string, status model.CallStatus) error
	ListCallsForUser(ctx context.Context, userID string) ([]*model.Call, error)
	// ExpireRingingCalls переводит неотвеченные вызовы, созданные раньше
	// olderThan, в missed и возвращает их id. Без этого запись оставалась
	// ringing навсегда, если оба клиента умерли, не отправив статус.
	ExpireRingingCalls(ctx context.Context, olderThan time.Time) ([]string, error)

	// Push-уведомления: токены устройств Firebase.
	// SavePushDevice идемпотентен; токен, пришедший от другого аккаунта,
	// перепривязывается к текущему пользователю.
	SavePushDevice(ctx context.Context, userID, token, platform string) error
	ListPushDevices(ctx context.Context, userID string) ([]model.PushDevice, error)
	DeletePushDevice(ctx context.Context, token string) error

	// DeleteUser полностью удаляет пользователя и все его данные (аккаунт,
	// ключи, сообщения, медиа, членства в чатах, контакты, звонки) — «сжечь аккаунт».
	DeleteUser(ctx context.Context, userID string) error

	// Перенос аккаунта между устройствами (v0.4). Vault — непрозрачный
	// зашифрованный клиентом blob; сервер его не читает.
	// PutAccountTransfer создаёт одноразовый код переноса (codeHash = SHA-256 кода)
	// и отзывает предыдущие неиспользованные коды пользователя.
	PutAccountTransfer(ctx context.Context, userID, codeHash string, vault []byte, expiresAt time.Time) error
	// TakeAccountTransfer извлекает vault по коду (однократно). Возвращает
	// ErrNotFound, если кода нет, он использован или истёк.
	TakeAccountTransfer(ctx context.Context, codeHash string) (string, []byte, error)

	// OTP-состояние (v0.19): коды и бюджеты живут в хранилище, а не в памяти
	// процесса, иначе рестарт сбрасывал коды и лимиты, а несколько инстансов
	// считали попытки независимо.
	// ReserveOTPSend списывает одну отправку; при отказе возвращает время до
	// следующей попытки и ErrOTPThrottled либо ErrOTPCapacity.
	ReserveOTPSend(ctx context.Context, phone string, now time.Time, policy OTPPolicy) (time.Duration, error)
	SaveOTPCode(ctx context.Context, code *model.OTPCode) error
	LoadOTPCode(ctx context.Context, phone, purpose string) (*model.OTPCode, *model.OTPBudget, error)
	FailOTPAttempt(ctx context.Context, phone, purpose, requestID string, now time.Time, window time.Duration) error
	// ConsumeOTPCode гасит код после успешной проверки (однократность).
	ConsumeOTPCode(ctx context.Context, phone, purpose, requestID string) error
	DeleteOTPCode(ctx context.Context, phone, purpose, requestID string) error
	// PurgeOTPState чистит истёкшие коды и остывшие бюджеты (cmd/gc).
	PurgeOTPState(ctx context.Context, now time.Time, window time.Duration) error

	// Инвайт-коды (v0.19): владелец приглашает новых пользователей без правки
	// AUTH_ALLOWED_PHONES и рестарта сервера. Хранится только хеш кода.
	CreateInvite(ctx context.Context, inv *model.Invite) error
	ListInvites(ctx context.Context, ownerID string) ([]model.Invite, error)
	RevokeInvite(ctx context.Context, ownerID, id string) error
	GetInviteByHash(ctx context.Context, codeHash string, now time.Time) (*model.Invite, error)
	// ClaimInvite атомарно списывает одно использование; повторный вход того же
	// номера не тратит новое использование.
	ClaimInvite(ctx context.Context, codeHash, phoneHash, userID string, now time.Time) error
	InviteUses(ctx context.Context, ownerID, id string) ([]InviteUse, error)

	Close() error
}

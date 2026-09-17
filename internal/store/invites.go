package store

import (
	"sort"
	"time"

	"umbra/server/internal/model"
)

// InviteUse — факт использования инвайт-кода. Номер хранится только хешем.
type InviteUse struct {
	PhoneHash string
	UserID    string
	UsedAt    time.Time
}

// sortInvites — свежие сверху, стабильный порядок по id.
func sortInvites(list []model.Invite) {
	sort.Slice(list, func(i, j int) bool {
		if list[i].CreatedAt.Equal(list[j].CreatedAt) {
			return list[i].ID < list[j].ID
		}
		return list[i].CreatedAt.After(list[j].CreatedAt)
	})
}

func sortInviteUses(list []InviteUse) {
	sort.Slice(list, func(i, j int) bool {
		if list[i].UsedAt.Equal(list[j].UsedAt) {
			return list[i].PhoneHash < list[j].PhoneHash
		}
		return list[i].UsedAt.After(list[j].UsedAt)
	})
}

package store

import (
	"context"
	"errors"
	"testing"
	"time"
	"umbra/server/internal/model"
)

func sessionContract(t *testing.T, st Store, ctx context.Context) {
	t.Helper()
	for _, name := range []string{"owner", "peer"} {
		mustStore(t, st.CreateUser(ctx, testUser(name)))
	}
	now := time.Now().UTC()
	for _, s := range []model.AuthSession{
		{ID: "a", UserID: "id-owner", TokenHash: "a-hash", DeviceName: "Phone", CreatedAt: now, LastSeenAt: now, ExpiresAt: now.Add(365 * 24 * time.Hour)},
		{ID: "b", UserID: "id-owner", TokenHash: "b-hash", DeviceName: "Tablet", CreatedAt: now.Add(-89 * 24 * time.Hour), LastSeenAt: now, ExpiresAt: now.Add(365 * 24 * time.Hour)},
		{ID: "expired", UserID: "id-owner", TokenHash: "expired-hash", CreatedAt: now.Add(-91 * 24 * time.Hour), LastSeenAt: now, ExpiresAt: now.Add(time.Hour)},
		{ID: "peer", UserID: "id-peer", TokenHash: "peer-hash", CreatedAt: now, LastSeenAt: now, ExpiresAt: now.Add(time.Hour)},
	} {
		mustStore(t, st.CreateSession(ctx, &s))
		if s.ExpiresAt.After(s.CreatedAt.Add(MaxSessionLifetime)) {
			t.Fatal("uncapped")
		}
	}
	list, err := st.ListSessions(ctx, "id-owner")
	mustStore(t, err)
	if len(list) != 2 {
		t.Fatal("expired listed", list)
	}
	if _, err = st.GetUserIDByTokenHash(ctx, "expired-hash"); !errors.Is(err, ErrNotFound) {
		t.Fatal("expired authenticated", err)
	}
	if _, err = st.RenewToken(ctx, "expired-hash", "id-owner", now.Add(time.Hour)); !errors.Is(err, ErrNotFound) {
		t.Fatal("expired renewed", err)
	}
	if err = st.SavePushDeviceForSession(ctx, "id-peer", "wrong", "android", "a-hash"); !errors.Is(err, ErrNotFound) {
		t.Fatal("foreign push", err)
	}
	mustStore(t, st.SavePushDeviceForSession(ctx, "id-owner", "push-a", "android", "a-hash"))
	mustStore(t, st.SavePushDeviceForSession(ctx, "id-owner", "push-b", "android", "b-hash"))
	if err = st.RevokeSession(ctx, "id-peer", "a"); !errors.Is(err, ErrNotFound) {
		t.Fatal("foreign revocation", err)
	}
	mustStore(t, st.RevokeOtherSessions(ctx, "id-owner", "a-hash"))
	devices, err := st.ListPushDevices(ctx, "id-owner")
	mustStore(t, err)
	if len(devices) != 1 || devices[0].SessionID != "a" {
		t.Fatal("push cascade", devices)
	}
	if _, err = st.GetUserIDByTokenHash(ctx, "peer-hash"); err != nil {
		t.Fatal("peer affected", err)
	}
	mustStore(t, st.RevokeSession(ctx, "id-owner", "a"))
	devices, err = st.ListPushDevices(ctx, "id-owner")
	mustStore(t, err)
	if len(devices) != 0 {
		t.Fatal(devices)
	}
	if pg, ok := st.(*PostgresStore); ok {
		_, err := pg.pool.Exec(ctx, "SET TIME ZONE 'Europe/Berlin'")
		mustStore(t, err)
	}
	mustStore(t, st.PutToken(ctx, "legacy", "id-owner", now.Add(365*24*time.Hour)))
	list, err = st.ListSessions(ctx, "id-owner")
	mustStore(t, err)
	if len(list) != 1 || list[0].ID == "" || list[0].DeviceName == "" || list[0].ExpiresAt.After(list[0].CreatedAt.Add(MaxSessionLifetime)) {
		t.Fatal("legacy metadata", list)
	}
}
func TestMemorySessionSecurity(t *testing.T) {
	sessionContract(t, NewMemoryStore(), context.Background())
}
func TestPostgresSessionSecurity(t *testing.T) {
	st, ctx := cloudPostgres(t)
	mustStore(t, st.CheckSessionSchema(ctx))
	sessionContract(t, st, ctx)
	_, err := st.pool.Exec(ctx, "ALTER TABLE auth_tokens DROP COLUMN device_name")
	mustStore(t, err)
	if st.CheckSessionSchema(ctx) == nil {
		t.Fatal("missing schema accepted")
	}
}

package store

import (
	"context"
	"errors"
	"testing"
	"time"

	"umbra/server/internal/model"
)

func TestMemoryMedia(t *testing.T) {
	ctx := context.Background()
	s := NewMemoryStore()
	if err := s.CreateUser(ctx, &model.User{ID: "owner", Username: "owner"}); err != nil {
		t.Fatal(err)
	}
	m := model.Media{
		ID: "media-id", OwnerID: "owner", ContentType: "application/octet-stream",
		Size: 123, CreatedAt: time.Now().UTC(),
	}
	want := m
	if err := s.SaveMedia(ctx, &m); err != nil {
		t.Fatal(err)
	}
	m.Size = 999
	got, err := s.GetMedia(ctx, want.ID)
	if err != nil || *got != want {
		t.Fatalf("round trip: %#v, %v; want %#v", got, err, want)
	}
	got.OwnerID = "changed"
	again, err := s.GetMedia(ctx, want.ID)
	if err != nil || *again != want {
		t.Fatalf("returned value aliases stored data: %#v, %v", again, err)
	}
	if err := s.SaveMedia(ctx, &m); !errors.Is(err, ErrConflict) {
		t.Fatalf("duplicate: %v", err)
	}
	if _, err := s.GetMedia(ctx, "missing"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("missing: %v", err)
	}
	m.ID, m.OwnerID = "orphan", "unknown-owner"
	if err := s.SaveMedia(ctx, &m); !errors.Is(err, ErrNotFound) {
		t.Fatalf("missing owner: %v", err)
	}
	if _, err := s.GetMedia(ctx, m.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("orphan persisted: %v", err)
	}
}

package cloudcrypto

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"testing"
)

func newKeys(t *testing.T) *Keyring {
	t.Helper()
	k, err := Generate(nil)
	if err != nil {
		t.Fatal(err)
	}
	return k
}

func TestRecordsAuthenticationAndRotation(t *testing.T) {
	k := newKeys(t)
	ctx := Context("message", "id", "sender", "recipient", "")
	plain := []byte("private family message")
	a, err := k.Seal(plain, ctx)
	if err != nil {
		t.Fatal(err)
	}
	b, err := k.Seal(plain, ctx)
	if err != nil || bytes.Equal(a, b) || bytes.Contains(a, plain) {
		t.Fatal("encryption not randomized", err)
	}
	for i := range a {
		corrupt := bytes.Clone(a)
		corrupt[i] ^= 1
		if _, err := k.Open(corrupt, ctx); err == nil {
			t.Fatalf("accepted altered byte %d", i)
		}
	}
	for _, other := range []*Keyring{newKeys(t), nil} {
		if _, err := other.Open(a, ctx); err == nil {
			t.Fatal("accepted unavailable key")
		}
	}
	if _, err := k.Open(a, Context("message", "other", "sender", "recipient", "")); err == nil {
		t.Fatal("accepted substituted record")
	}
	next, err := Generate(k)
	if err != nil {
		t.Fatal(err)
	}
	got, err := next.Open(a, ctx)
	if err != nil || !bytes.Equal(got, plain) {
		t.Fatal("rotation lost history", err)
	}
	newData, err := next.Seal(plain, ctx)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := k.Open(newData, ctx); err == nil {
		t.Fatal("old ring read new key")
	}
}

func TestKeyFileSafety(t *testing.T) {
	k := newKeys(t)
	path := filepath.Join(t.TempDir(), "storage-keys.json")
	if err := k.WriteNew(path); err != nil {
		t.Fatal(err)
	}
	if err := newKeys(t).WriteNew(path); !errors.Is(err, os.ErrExist) {
		t.Fatal("overwrote keys", err)
	}
	got, err := LoadFile(path)
	if err != nil || got.ActiveID() != k.ActiveID() {
		t.Fatal("key reload", err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Parse(append(data, []byte("{}")...)); err == nil {
		t.Fatal("trailing JSON accepted")
	}
	if _, err := Parse(bytes.ReplaceAll(data, []byte(k.ActiveID()), []byte("00000000000000000000000000000000"))); err == nil {
		t.Fatal("key identity mismatch accepted")
	}
	if err := os.Chmod(path, 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := LoadFile(path); err == nil {
		t.Fatal("world-readable key file accepted")
	}
}

func TestStreams(t *testing.T) {
	k := newKeys(t)
	for _, size := range []int{0, 1, 65534, 65535, 65536, 65537, 131072, 2 << 20} {
		t.Run(fmt.Sprint(size), func(t *testing.T) {
			plain := bytes.Repeat([]byte{0x8d}, size)
			r, err := k.EncryptReader(bytes.NewReader(plain), "media-id")
			if err != nil {
				t.Fatal(err)
			}
			data, err := io.ReadAll(r)
			if err != nil {
				t.Fatal(err)
			}
			dec, err := k.DecryptReader(bytes.NewReader(data), "media-id")
			if err != nil {
				t.Fatal(err)
			}
			got, err := io.ReadAll(dec)
			if err != nil || !bytes.Equal(got, plain) {
				t.Fatal("roundtrip", err)
			}
			if _, err := k.DecryptReader(bytes.NewReader(data), "other-id"); err == nil {
				t.Fatal("substituted blob accepted")
			}
			bad := bytes.Clone(data)
			bad[len(bad)-1] ^= 1
			for _, invalid := range [][]byte{data[:len(data)-1], data[:wrappedKeySize], bad, append(bytes.Clone(data), 0)} {
				dec, err := k.DecryptReader(bytes.NewReader(invalid), "media-id")
				if err == nil {
					_, err = io.ReadAll(dec)
				}
				if err == nil {
					t.Fatal("accepted corrupt/truncated/trailing stream")
				}
			}
		})
	}
}

type failingSource struct{}

func (failingSource) Read([]byte) (int, error) { return 0, io.ErrUnexpectedEOF }

func TestSourceFailureDoesNotBecomeSuccessfulCiphertext(t *testing.T) {
	r, err := newKeys(t).EncryptReader(io.MultiReader(bytes.NewBufferString("partial upload"), failingSource{}), "media")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := io.ReadAll(r); !errors.Is(err, io.ErrUnexpectedEOF) {
		t.Fatalf("source failure lost: %v", err)
	}
}

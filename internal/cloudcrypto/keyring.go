// Package cloudcrypto encrypts server storage. This is not end-to-end encryption:
// the running server holds the keys and returns plaintext to authorized clients.
package cloudcrypto

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
)

var ErrInvalid = errors.New("cloud storage: invalid ciphertext or unavailable key")

const magic = "UMBRAST\x01"
const headerSize = len(magic) + 16
const wrappedKeySize = headerSize + 12 + 32 + 16

// Keyring is immutable after construction and safe for concurrent requests.
type Keyring struct {
	active string
	keys   map[string][]byte
}

type keyFile struct {
	Version int               `json:"version"`
	Active  string            `json:"active"`
	Keys    map[string]string `json:"keys"`
}

func keyID(key []byte) string {
	h := sha256.Sum256(key)
	return hex.EncodeToString(h[:16])
}

func Generate(previous *Keyring) (*Keyring, error) {
	k := &Keyring{keys: make(map[string][]byte)}
	if previous != nil {
		for id, key := range previous.keys {
			k.keys[id] = bytes.Clone(key)
		}
	}
	if len(k.keys) >= 128 {
		return nil, errors.New("cloud storage: keyring full")
	}
	key := make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		return nil, err
	}
	k.active = keyID(key)
	k.keys[k.active] = key
	return k, nil
}

func (k *Keyring) ActiveID() string {
	if k == nil {
		return ""
	}
	return k.active
}

func (k *Keyring) Has(id string) bool {
	return k != nil && len(k.keys[id]) == 32
}

func Parse(data []byte) (*Keyring, error) {
	if len(data) > 1<<20 {
		return nil, errors.New("cloud storage: key file too large")
	}
	var f keyFile
	d := json.NewDecoder(bytes.NewReader(data))
	d.DisallowUnknownFields()
	if err := d.Decode(&f); err != nil {
		return nil, fmt.Errorf("cloud storage: invalid key file: %w", err)
	}
	if err := d.Decode(new(any)); err != io.EOF {
		return nil, errors.New("cloud storage: trailing key file data")
	}
	if f.Version != 1 || len(f.Keys) == 0 || len(f.Keys) > 128 {
		return nil, errors.New("cloud storage: unsupported or empty key file")
	}
	k := &Keyring{active: f.Active, keys: make(map[string][]byte)}
	for id, value := range f.Keys {
		key, err := base64.StdEncoding.Strict().DecodeString(value)
		if err != nil || len(key) != 32 || keyID(key) != id {
			return nil, errors.New("cloud storage: invalid key identity")
		}
		k.keys[id] = key
	}
	if !k.Has(k.active) {
		return nil, errors.New("cloud storage: active key missing")
	}
	return k, nil
}

func LoadFile(path string) (*Keyring, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("open storage keys (create once with umbra-storage init): %w", err)
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 {
		return nil, errors.New("cloud storage: key file must be a regular file with owner-only permissions (0600 or 0400)")
	}
	data, err := io.ReadAll(io.LimitReader(f, (1<<20)+1))
	if err != nil {
		return nil, err
	}
	return Parse(data)
}

// WriteNew never overwrites a key file. The caller backs it up before enabling writes.
func (k *Keyring) WriteNew(path string) error {
	if !k.Has(k.ActiveID()) {
		return ErrInvalid
	}
	f := keyFile{Version: 1, Active: k.active, Keys: make(map[string]string)}
	for id, key := range k.keys {
		f.Keys[id] = base64.StdEncoding.EncodeToString(key)
	}
	data, err := json.MarshalIndent(f, "", "  ")
	if err != nil {
		return err
	}
	out, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	defer out.Close()
	if _, err := out.Write(append(data, '\n')); err != nil {
		return err
	}
	if err := out.Sync(); err != nil {
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	dir, err := os.Open(filepath.Dir(path))
	if err != nil {
		return err
	}
	defer dir.Close()
	return dir.Sync()
}

// Context uses unambiguous length-delimited JSON, including empty addressing fields.
func Context(parts ...string) []byte {
	b, _ := json.Marshal(parts)
	return b
}

func (k *Keyring) aead(id string) (cipher.AEAD, error) {
	if !k.Has(id) {
		return nil, ErrInvalid
	}
	block, err := aes.NewCipher(k.keys[id])
	if err != nil {
		return nil, err
	}
	return cipher.NewGCMWithRandomNonce(block)
}

func (k *Keyring) Seal(plaintext, context []byte) ([]byte, error) {
	a, err := k.aead(k.ActiveID())
	if err != nil {
		return nil, err
	}
	id, _ := hex.DecodeString(k.active)
	header := append([]byte(magic), id...)
	aad := append(bytes.Clone(header), context...)
	return append(header, a.Seal(nil, nil, plaintext, aad)...), nil
}

func (k *Keyring) Open(data, context []byte) ([]byte, error) {
	if len(data) < headerSize || string(data[:len(magic)]) != magic {
		return nil, ErrInvalid
	}
	a, err := k.aead(hex.EncodeToString(data[len(magic):headerSize]))
	if err != nil {
		return nil, ErrInvalid
	}
	aad := append(bytes.Clone(data[:headerSize]), context...)
	plain, err := a.Open(nil, nil, data[headerSize:], aad)
	if err != nil {
		return nil, ErrInvalid
	}
	return plain, nil
}

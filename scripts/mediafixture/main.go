// Вспомогательный КЛИЕНТ для curl-проверки; сервер не использует этот пакет.
// Все приватные ключи и открытый текст создаются только во временной папке клиента.
package main

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
)

func main() {
	if len(os.Args) < 3 {
		panic("usage: mediafixture prepare DIR LIMIT | sign DIR ROLE | verify DIR")
	}
	dir := os.Args[2]
	switch os.Args[1] {
	case "prepare":
		if len(os.Args) != 4 {
			panic("prepare requires a byte limit")
		}
		limit, err := strconv.Atoi(os.Args[3])
		must(err)
		if limit < 64 || limit > 64<<20 {
			panic("smoke limit must be between 64 bytes and 64 MiB")
		}
		must(os.MkdirAll(dir, 0700))
		for _, role := range []string{"alice", "bob"} {
			pub, priv, err := ed25519.GenerateKey(rand.Reader)
			must(err)
			ix, err := ecdh.X25519().GenerateKey(rand.Reader)
			must(err)
			spk, err := ecdh.X25519().GenerateKey(rand.Reader)
			must(err)
			otk, err := ecdh.X25519().GenerateKey(rand.Reader)
			must(err)
			name := fmt.Sprintf("%s_media_%x", role, randomBytes(4))
			writeJSON(dir, role+"-register.json", map[string]any{
				"username": name, "identity_ed25519": encode(pub),
				"identity_x25519":         encode(ix.PublicKey().Bytes()),
				"signed_prekey":           encode(spk.PublicKey().Bytes()),
				"signed_prekey_signature": encode(ed25519.Sign(priv, spk.PublicKey().Bytes())),
				"one_time_prekeys":        []string{encode(otk.PublicKey().Bytes())},
			})
			writeJSON(dir, role+"-challenge-request.json", map[string]string{"username": name})
			write(dir, role+".key", priv)
		}
		key := randomBytes(32)
		block, err := aes.NewCipher(key)
		must(err)
		aead, err := cipher.NewGCM(block)
		must(err)
		plain := randomBytes(min(512, limit-aead.Overhead()))
		nonce := randomBytes(aead.NonceSize())
		write(dir, "client-key.bin", key)
		write(dir, "client-nonce.bin", nonce)
		write(dir, "client-plaintext.bin", plain)
		write(dir, "encrypted.bin", aead.Seal(nil, nonce, plain, nil))
		// Каждый тестовый ciphertext использует новый nonce.
		write(dir, "exact-limit.bin", aead.Seal(nil, randomBytes(aead.NonceSize()), randomBytes(limit-aead.Overhead()), nil))
		write(dir, "oversized.bin", aead.Seal(nil, randomBytes(aead.NonceSize()), randomBytes(limit+1-aead.Overhead()), nil))
	case "sign":
		if len(os.Args) != 4 {
			panic("sign requires alice or bob")
		}
		role := os.Args[3]
		if role != "alice" && role != "bob" {
			panic("invalid role")
		}
		var registration struct {
			Username string `json:"username"`
		}
		must(json.Unmarshal(read(dir, role+"-register.json"), &registration))
		var challenge struct {
			Challenge string `json:"challenge"`
		}
		must(json.Unmarshal(read(dir, role+"-challenge.json"), &challenge))
		priv := ed25519.PrivateKey(read(dir, role+".key"))
		writeJSON(dir, role+"-verify.json", map[string]string{
			"username": registration.Username, "challenge": challenge.Challenge,
			"signature": encode(ed25519.Sign(priv, []byte(challenge.Challenge))),
		})
	case "verify":
		block, err := aes.NewCipher(read(dir, "client-key.bin"))
		must(err)
		aead, err := cipher.NewGCM(block)
		must(err)
		plain, err := aead.Open(nil, read(dir, "client-nonce.bin"), read(dir, "download.bin"), nil)
		must(err)
		if !bytes.Equal(plain, read(dir, "client-plaintext.bin")) {
			panic("decrypted content differs")
		}
		fmt.Println("client-side AES-GCM decryption: OK")
	default:
		panic("unknown command")
	}
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func randomBytes(size int) []byte {
	b := make([]byte, size)
	_, err := rand.Read(b)
	must(err)
	return b
}

func encode(b []byte) string { return base64.StdEncoding.EncodeToString(b) }

func write(dir, name string, b []byte) { must(os.WriteFile(filepath.Join(dir, name), b, 0600)) }

func read(dir, name string) []byte {
	b, err := os.ReadFile(filepath.Join(dir, name))
	must(err)
	return b
}

func writeJSON(dir, name string, v any) {
	b, err := json.Marshal(v)
	must(err)
	write(dir, name, b)
}

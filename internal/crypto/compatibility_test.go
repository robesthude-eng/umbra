package crypto

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"testing"
)

func TestEd25519RawAndLegacyDER(t *testing.T) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil { t.Fatal(err) }
	der, err := x509.MarshalPKIXPublicKey(pub)
	if err != nil { t.Fatal(err) }
	msg := []byte("challenge")
	sig := ed25519.Sign(priv, msg)
	for _, key := range [][]byte{pub, der} {
		raw, err := NormalizeEd25519(key)
		if err != nil || !bytes.Equal(raw, pub) { t.Fatalf("normalize: %x %v", raw, err) }
		if !VerifyEd25519(key, msg, sig) { t.Fatal("valid signature rejected") }
		if VerifyEd25519(key, msg, sig[:63]) { t.Fatal("short signature accepted") }
		if VerifyEd25519(key, []byte("other"), sig) { t.Fatal("wrong message accepted") }
	}
	if _, err := NormalizeEd25519(der[:len(der)-1]); err == nil { t.Fatal("truncated DER accepted") }
}

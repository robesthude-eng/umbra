package cloudcrypto

import (
	"bytes"
	"crypto/rand"
	"fmt"
	"io"

	"github.com/minio/sio"
)

// strictSource preserves a failed upload even when a dependency treats a direct
// io.ErrUnexpectedEOF as a normal short final block.
type strictSource struct{ io.Reader }

func (r strictSource) Read(p []byte) (int, error) {
	n, err := r.Reader.Read(p)
	if err != nil && err != io.EOF {
		err = fmt.Errorf("read plaintext source: %w", err)
	}
	return n, err
}

func streamConfig(key []byte) sio.Config {
	return sio.Config{Key: key, MinVersion: sio.Version20, MaxVersion: sio.Version20, CipherSuites: []byte{sio.AES_GCM}}
}

// EncryptReader uses a fresh 256-bit data key per object and DARE v2 framing.
// The wrapped key authenticates the logical object ID. Memory usage is bounded.
func (k *Keyring) EncryptReader(src io.Reader, objectID string) (io.Reader, error) {
	key := make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		return nil, err
	}
	header, err := k.Seal(key, Context("blob-key", objectID))
	if err != nil {
		return nil, err
	}
	// An authenticated marker also makes empty files produce a non-empty stream.
	enc, err := sio.EncryptReader(io.MultiReader(bytes.NewReader([]byte{0}), strictSource{src}), streamConfig(key))
	if err != nil {
		return nil, err
	}
	return io.MultiReader(bytes.NewReader(header), enc), nil
}

// Callers must consume through EOF and propagate errors, including a bad final
// block. Do not restore a database from this stream until it has fully verified.
func (k *Keyring) DecryptReader(src io.Reader, objectID string) (io.Reader, error) {
	header := make([]byte, wrappedKeySize)
	if _, err := io.ReadFull(src, header); err != nil {
		return nil, ErrInvalid
	}
	key, err := k.Open(header, Context("blob-key", objectID))
	if err != nil || len(key) != 32 {
		return nil, ErrInvalid
	}
	dec, err := sio.DecryptReader(src, streamConfig(key))
	if err != nil {
		return nil, ErrInvalid
	}
	var marker [1]byte
	if _, err := io.ReadFull(dec, marker[:]); err != nil || marker[0] != 0 {
		return nil, ErrInvalid
	}
	return dec, nil
}

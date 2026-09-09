package httpapi

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"net/http"

	"umbra/server/internal/crypto"
	"umbra/server/internal/model"
	"umbra/server/internal/store"
)

func curveKeyValid(key []byte, signal bool) bool {
	return len(key) == 33 && key[0] == 5 || !signal && len(key) == 32
}

func prepareKeyMaterial(u *model.User, ids []int) error {
	if u.KeyVersion == 0 {
		u.KeyVersion = 1
	}
	if u.RegistrationID == 0 {
		u.RegistrationID = 1
	}
	if u.SignedPrekeyID == 0 {
		u.SignedPrekeyID = 1
	}
	if u.KeyVersion != 1 && u.KeyVersion != 2 {
		return errors.New("unsupported key_version")
	}
	if u.RegistrationID < 1 || u.RegistrationID > 16380 || u.SignedPrekeyID < 1 || u.SignedPrekeyID > 0xffffff {
		return errors.New("invalid key identifiers")
	}
	signal := u.KeyVersion == 2
	if signal && (u.KeyBundleID == "" || !validMessageOptions(u.KeyBundleID, 0)) {
		return errors.New("invalid key_bundle_id")
	}
	if !curveKeyValid(u.IdentityX25519, signal) || !curveKeyValid(u.SignedPrekey, signal) || len(u.SignedPrekeySig) != 64 {
		return errors.New("invalid public key material")
	}
	if !signal && !crypto.VerifyEd25519(u.IdentityEd25519, u.SignedPrekey, u.SignedPrekeySig) {
		return errors.New("invalid legacy prekey signature")
	}
	if len(u.OneTimePrekeys) > 1000 || signal && len(ids) != len(u.OneTimePrekeys) {
		return errors.New("invalid one-time prekeys")
	}
	seen := make(map[int]bool)
	for i, pk := range u.OneTimePrekeys {
		if !curveKeyValid(pk, signal) {
			return errors.New("invalid one-time prekey")
		}
		if signal {
			id := ids[i]
			if id < 1 || id > 0xffffff || seen[id] {
				return errors.New("invalid or duplicate prekey id")
			}
			seen[id] = true
			packed := make([]byte, 4+len(pk))
			binary.BigEndian.PutUint32(packed, uint32(id))
			copy(packed[4:], pk)
			u.OneTimePrekeys[i] = packed
		}
	}
	return nil
}

func (s *Server) handleUpdateKeys(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	var req registerRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&req); err != nil {
		writeError(w, 400, "invalid request body")
		return
	}
	u, err := s.store.GetUserByID(r.Context(), userID)
	if err != nil {
		writeError(w, 404, "user not found")
		return
	}
	if req.KeyVersion != 2 {
		writeError(w, 400, "key_version 2 required")
		return
	}
	ix, err1 := b64(req.IdentityX25519)
	spk, err2 := b64(req.SignedPrekey)
	sig, err3 := b64(req.SignedPrekeySig)
	if err1 != nil || err2 != nil || err3 != nil {
		writeError(w, 400, "invalid key material")
		return
	}
	keys := &model.User{IdentityEd25519: u.IdentityEd25519, IdentityX25519: ix, SignedPrekey: spk, SignedPrekeySig: sig,
		KeyVersion: req.KeyVersion, RegistrationID: req.RegistrationID, SignedPrekeyID: req.SignedPrekeyID, KeyBundleID: req.KeyBundleID}
	for _, value := range req.OneTimePrekeys {
		pk, err := b64(value)
		if err != nil {
			writeError(w, 400, "invalid prekey")
			return
		}
		keys.OneTimePrekeys = append(keys.OneTimePrekeys, pk)
	}
	if err := prepareKeyMaterial(keys, req.OneTimePrekeyIDs); err != nil {
		writeError(w, 400, err.Error())
		return
	}
	if err := s.store.UpdateKeys(r.Context(), userID, keys); err != nil {
		if errors.Is(err, store.ErrConflict) {
			writeError(w, 409, "identity change is not allowed")
			return
		}
		writeError(w, 500, "internal error")
		return
	}
	writeJSON(w, 200, map[string]string{"status": "updated"})
}

func validMessageOptions(clientID string, expiresIn int64) bool {
	if expiresIn < 0 || expiresIn > 30*24*60*60 || len(clientID) > 128 {
		return false
	}
	for _, c := range clientID {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_') {
			return false
		}
	}
	return true
}

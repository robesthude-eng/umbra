package config

import (
	"testing"
	"time"
)

func TestAuthAndProxyDefaults(t *testing.T) {
	t.Setenv("ALLOW_LEGACY_AUTH", "")
	t.Setenv("TOKEN_TTL_SECONDS", "")
	t.Setenv("TRUSTED_PROXIES", "")
	c := Load()
	if c.AllowLegacyAuth || c.TokenTTL != 30*24*time.Hour || c.TrustedProxies != "" { t.Fatal("unsafe or unexpected defaults") }
	for _, input := range []string{"0.0.0.0/0", "::/0", "*", "127.0.0.1,invalid", "127.0.0.1,"} {
		if _, err := ParseTrustedProxies(input); err == nil { t.Fatalf("accepted %q", input) }
	}
	if prefixes, err := ParseTrustedProxies("127.0.0.1, ::1, 10.1.0.0/16"); err != nil || len(prefixes) != 3 { t.Fatalf("valid proxies: %v", err) }
}

func TestMediaConfig(t *testing.T) {
	t.Setenv("BLOB_DIR", "")
	for _, tc := range []struct {
		value string
		want  int
	}{
		{"", DefaultMaxMediaBytes}, {"4096", 4096}, {"0", DefaultMaxMediaBytes},
		{"-1", DefaultMaxMediaBytes}, {"invalid", DefaultMaxMediaBytes},
		{"999999999999999999999999", DefaultMaxMediaBytes},
	} {
		t.Run(tc.value, func(t *testing.T) {
			t.Setenv("MAX_MEDIA_BYTES", tc.value)
			cfg := Load()
			if cfg.MaxMediaBytes != tc.want || cfg.BlobDir != "./data/blobs" {
				t.Fatalf("config: max=%d dir=%q", cfg.MaxMediaBytes, cfg.BlobDir)
			}
		})
	}
	t.Setenv("BLOB_DIR", "/custom/blobs")
	if cfg := Load(); cfg.BlobDir != "/custom/blobs" {
		t.Fatalf("custom BLOB_DIR ignored: %q", cfg.BlobDir)
	}
}

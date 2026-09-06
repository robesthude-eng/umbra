package config

import "testing"

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

// umbra-storage manages server keys, data migration and encrypted backups.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"syscall"

	"umbra/server/internal/blobstore"
	"umbra/server/internal/cloudcrypto"
	"umbra/server/internal/cloudstorage"
	"umbra/server/internal/config"
	"umbra/server/internal/store"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run(ctx context.Context, args []string) error {
	if len(args) == 0 {
		return errors.New("usage: umbra-storage {init|rotate|status|migrate|encrypt-backup|decrypt-backup} [-key-file PATH] [-out NEW_KEY_FILE]")
	}
	cfg := config.Load()
	flags := flag.NewFlagSet("umbra-storage "+args[0], flag.ContinueOnError)
	path := flags.String("key-file", cfg.StorageKeyFile, "owner-only server key file")
	out := flags.String("out", "", "new key file for rotation; must not exist")
	if err := flags.Parse(args[1:]); err != nil {
		return err
	}
	if flags.NArg() != 0 {
		return errors.New("unexpected positional argument")
	}
	if *out != "" && args[0] != "rotate" {
		return errors.New("-out is only for rotate")
	}
	if args[0] == "init" {
		keys, err := cloudcrypto.Generate(nil)
		if err != nil {
			return err
		}
		if err := keys.WriteNew(*path); err != nil {
			return err
		}
		fmt.Fprintln(os.Stderr, "Key file created:", *path, "— back it up separately before starting the server.")
		return nil
	}
	keys, err := cloudcrypto.LoadFile(*path)
	if err != nil {
		return err
	}
	switch args[0] {
	case "rotate":
		if *out == "" {
			return errors.New("rotate requires -out NEW_KEY_FILE; old keys must be retained")
		}
		next, err := cloudcrypto.Generate(keys)
		if err != nil {
			return err
		}
		if err := next.WriteNew(*out); err != nil {
			return err
		}
		fmt.Fprintln(os.Stderr, "New key file created:", *out, "— back it up, then switch STORAGE_KEY_FILE and restart every server instance.")
		return nil
	case "encrypt-backup", "decrypt-backup":
		var stream io.Reader
		if args[0] == "encrypt-backup" {
			stream, err = keys.EncryptReader(os.Stdin, "backup")
		} else {
			stream, err = keys.DecryptReader(os.Stdin, "backup")
		}
		if err != nil {
			return err
		}
		done := make(chan struct{})
		defer close(done)
		go func() {
			select {
			case <-ctx.Done():
				_ = os.Stdin.Close()
			case <-done:
			}
		}()
		_, err = io.Copy(os.Stdout, stream)
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return err
	case "status", "migrate":
		if cfg.Store != "postgres" || cfg.DatabaseURL == "" {
			return errors.New("STORE=postgres and DATABASE_URL are required")
		}
		if err := cfg.Validate(); err != nil {
			return err
		}
		st, err := store.NewPostgresStore(ctx, cfg.DatabaseURL)
		if err != nil {
			return err
		}
		defer st.Close()
		if args[0] == "migrate" {
			if err := st.EnableCloudStorage(ctx, keys); err != nil {
				return err
			}
			var blobs blobstore.BlobStore
			if cfg.BlobStoreType == "s3" {
				blobs, err = blobstore.NewS3BlobStore(cfg.S3Endpoint, cfg.S3AccessKey, cfg.S3SecretKey, cfg.S3Bucket, cfg.S3Region, cfg.S3UseSSL)
			} else {
				blobs, err = blobstore.NewFileBlobStore(cfg.BlobDir)
			}
			if err != nil {
				return err
			}
			defer blobs.Close()
			if err := cloudstorage.Migrate(ctx, st, blobs, keys); err != nil {
				return err
			}
		}
		status, err := st.CloudStorageStatus(ctx)
		if err != nil {
			return err
		}
		return json.NewEncoder(os.Stdout).Encode(status)
	default:
		return errors.New("unknown storage command")
	}
}

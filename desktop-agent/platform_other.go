//go:build !windows

package main

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
)

const localSecretPrefix = "aesgcm:v1:"

var localSecretKeyMu sync.Mutex

func protectSecret(plain []byte) (string, error) {
	key, err := loadOrCreateLocalSecretKey()
	if err != nil {
		return "", err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return "", err
	}
	sealed := gcm.Seal(nonce, nonce, plain, []byte("Murong Desktop Agent credential v1"))
	return localSecretPrefix + base64.RawStdEncoding.EncodeToString(sealed), nil
}

func unprotectSecret(encoded string) ([]byte, error) {
	if !strings.HasPrefix(encoded, localSecretPrefix) {
		// Compatibility with unreleased developer builds that only base64-encoded
		// secrets on non-Windows systems.
		return base64.RawStdEncoding.DecodeString(encoded)
	}
	payload, err := base64.RawStdEncoding.DecodeString(strings.TrimPrefix(encoded, localSecretPrefix))
	if err != nil {
		return nil, err
	}
	key, err := loadOrCreateLocalSecretKey()
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	if len(payload) < gcm.NonceSize() {
		return nil, errors.New("本机凭据密文损坏")
	}
	return gcm.Open(nil, payload[:gcm.NonceSize()], payload[gcm.NonceSize():], []byte("Murong Desktop Agent credential v1"))
}

func replaceFile(source, target string) error {
	return os.Rename(source, target)
}

func loadOrCreateLocalSecretKey() ([]byte, error) {
	localSecretKeyMu.Lock()
	defer localSecretKeyMu.Unlock()
	directory := strings.TrimSpace(os.Getenv("MURONG_DESKTOP_DATA_DIR"))
	if directory == "" {
		base, err := os.UserConfigDir()
		if err != nil {
			return nil, err
		}
		directory = filepath.Join(base, "Murong")
	}
	path := filepath.Join(directory, "desktop-secret.key")
	if data, err := os.ReadFile(path); err == nil {
		if len(data) != 32 {
			return nil, errors.New("本机凭据密钥长度无效")
		}
		return data, nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return nil, err
	}
	key := make([]byte, 32)
	if _, err := io.ReadFull(rand.Reader, key); err != nil {
		return nil, err
	}
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if errors.Is(err, os.ErrExist) {
		data, readErr := os.ReadFile(path)
		if readErr != nil || len(data) != 32 {
			return nil, fmt.Errorf("读取并发创建的本机凭据密钥失败：%w", readErr)
		}
		return data, nil
	}
	if err != nil {
		return nil, err
	}
	if _, err := file.Write(key); err != nil {
		_ = file.Close()
		return nil, err
	}
	if err := file.Sync(); err != nil {
		_ = file.Close()
		return nil, err
	}
	if err := file.Close(); err != nil {
		return nil, err
	}
	return key, nil
}

func executableProcessID(string) int { return 0 }

func terminateProcess(int) error { return nil }

func prepareHiddenCommand(*exec.Cmd) {}

func prepareApplicationIdentity() {}

func ensureApplicationWindowIcon(context.Context) {}

func activateProcessWindow(int) bool { return false }

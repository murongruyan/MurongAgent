//go:build !windows

package desktopbridge

import (
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

const localNodeSecretPrefix = "aesgcm:v1:"

var localNodeSecretKeyMu sync.Mutex

func protectSecret(plain []byte) (string, error) {
	key, err := loadOrCreateLocalNodeSecretKey()
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
	sealed := gcm.Seal(nonce, nonce, plain, []byte("Murong Desktop Node credential v1"))
	return localNodeSecretPrefix + base64.RawStdEncoding.EncodeToString(sealed), nil
}

func unprotectSecret(encoded string) ([]byte, error) {
	if !strings.HasPrefix(encoded, localNodeSecretPrefix) {
		return base64.RawStdEncoding.DecodeString(encoded)
	}
	payload, err := base64.RawStdEncoding.DecodeString(strings.TrimPrefix(encoded, localNodeSecretPrefix))
	if err != nil {
		return nil, err
	}
	key, err := loadOrCreateLocalNodeSecretKey()
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
		return nil, errors.New("本机节点凭据密文损坏")
	}
	return gcm.Open(nil, payload[:gcm.NonceSize()], payload[gcm.NonceSize():], []byte("Murong Desktop Node credential v1"))
}

func replaceFile(source, target string) error {
	return os.Rename(source, target)
}

func removeIfExists(path string) {
	_ = os.Remove(path)
}

func prepareHiddenCommand(*exec.Cmd) {}

func loadOrCreateLocalNodeSecretKey() ([]byte, error) {
	localNodeSecretKeyMu.Lock()
	defer localNodeSecretKeyMu.Unlock()
	configPath, err := defaultNodeConfigPath()
	if err != nil {
		return nil, err
	}
	directory := filepath.Dir(configPath)
	path := filepath.Join(directory, "computer-node-secret.key")
	if data, err := os.ReadFile(path); err == nil {
		if len(data) != 32 {
			return nil, errors.New("本机节点凭据密钥长度无效")
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
			return nil, fmt.Errorf("读取并发创建的本机节点凭据密钥失败：%w", readErr)
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

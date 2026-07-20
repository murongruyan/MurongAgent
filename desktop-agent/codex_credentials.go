package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

const maxCodexAuthBytes = 256 << 10

func ensurePrivateCodexHome(path string) error {
	path = strings.TrimSpace(path)
	if path == "" {
		return errors.New("Murong 私有 Codex Home 路径为空")
	}
	if err := os.MkdirAll(path, 0o700); err != nil {
		return fmt.Errorf("无法创建 Murong 私有 Codex Home：%w", err)
	}
	target := filepath.Join(path, "auth.json")
	if _, err := os.Stat(target); err == nil {
		return nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	// The previous Windows build inherited ~/.codex. Copy its valid login once
	// into Murong's private home; never modify or delete the source file.
	if strings.TrimSpace(os.Getenv("MURONG_DESKTOP_DATA_DIR")) != "" {
		return nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return nil
	}
	legacy := filepath.Join(home, ".codex", "auth.json")
	data, err := os.ReadFile(legacy)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return nil
	}
	defer clearBytes(data)
	if err := validateCodexAuthJSON(data); err != nil {
		return nil
	}
	return writeBytesAtomic(target, data)
}

func readCodexAuthJSON(codexHome string) ([]byte, error) {
	data, err := os.ReadFile(filepath.Join(codexHome, "auth.json"))
	if err != nil {
		return nil, err
	}
	if err := validateCodexAuthJSON(data); err != nil {
		clearBytes(data)
		return nil, err
	}
	return data, nil
}

func writeCodexAuthJSON(codexHome string, data []byte) error {
	if err := validateCodexAuthJSON(data); err != nil {
		return err
	}
	if err := os.MkdirAll(codexHome, 0o700); err != nil {
		return err
	}
	return writeBytesAtomic(filepath.Join(codexHome, "auth.json"), data)
}

func validateCodexAuthJSON(data []byte) error {
	if len(data) == 0 || len(data) > maxCodexAuthBytes {
		return errors.New("Codex 登录文件大小无效")
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	var root map[string]json.RawMessage
	if err := decoder.Decode(&root); err != nil {
		return errors.New("Codex 登录文件不是有效 JSON")
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return errors.New("Codex 登录文件包含尾随内容")
	}
	var mode string
	if value, ok := root["auth_mode"]; !ok || json.Unmarshal(value, &mode) != nil || strings.TrimSpace(mode) == "" {
		return errors.New("Codex 登录文件缺少 auth_mode")
	}
	tokens, hasTokens := root["tokens"]
	apiKey, hasAPIKey := root["OPENAI_API_KEY"]
	if !hasTokens && !hasAPIKey {
		return errors.New("Codex 登录文件缺少凭据")
	}
	validTokens := false
	if hasTokens && string(tokens) != "null" {
		var parsed map[string]json.RawMessage
		validTokens = json.Unmarshal(tokens, &parsed) == nil && len(parsed) > 0
	}
	validAPIKey := false
	if hasAPIKey && string(apiKey) != "null" {
		var parsed string
		validAPIKey = json.Unmarshal(apiKey, &parsed) == nil && strings.TrimSpace(parsed) != ""
	}
	if !validTokens && !validAPIKey {
		return errors.New("Codex 登录文件中的凭据为空或格式无效")
	}
	return nil
}

func environmentWithValue(environment []string, key, value string) []string {
	prefix := strings.ToUpper(key) + "="
	result := make([]string, 0, len(environment)+1)
	for _, entry := range environment {
		if strings.HasPrefix(strings.ToUpper(entry), prefix) {
			continue
		}
		result = append(result, entry)
	}
	return append(result, key+"="+value)
}

func clearBytes(value []byte) {
	for index := range value {
		value[index] = 0
	}
}

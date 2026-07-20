package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestCodexAuthValidationAndPrivateAtomicStorage(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	codexHome := filepath.Join(t.TempDir(), "codex-home")
	valid := []byte(`{"auth_mode":"chatgpt","OPENAI_API_KEY":null,"tokens":{"access_token":"test"}}`)
	if err := validateCodexAuthJSON(valid); err != nil {
		t.Fatalf("valid Codex auth was rejected: %v", err)
	}
	if err := writeCodexAuthJSON(codexHome, valid); err != nil {
		t.Fatal(err)
	}
	loaded, err := readCodexAuthJSON(codexHome)
	if err != nil {
		t.Fatal(err)
	}
	if string(loaded) != string(valid) {
		t.Fatal("private Codex auth did not round-trip")
	}
	if info, err := os.Stat(filepath.Join(codexHome, "auth.json")); err != nil || info.IsDir() {
		t.Fatalf("private Codex auth was not stored as a file: %v", err)
	}

	for _, invalid := range [][]byte{
		[]byte(`{"auth_mode":"chatgpt","OPENAI_API_KEY":null,"tokens":null}`),
		[]byte(`{"auth_mode":"chatgpt","tokens":{}}`),
		[]byte(`{"auth_mode":"chatgpt","OPENAI_API_KEY":""}`),
		[]byte(`{"auth_mode":"chatgpt","tokens":{"a":"b"}}{}`),
	} {
		if err := validateCodexAuthJSON(invalid); err == nil {
			t.Fatalf("invalid Codex auth was accepted: %s", invalid)
		}
	}
}

func TestEnvironmentWithValueReplacesExistingCodexHome(t *testing.T) {
	actual := environmentWithValue([]string{"Path=C:\\Windows", "CODEX_HOME=C:\\old"}, "CODEX_HOME", "C:\\murong")
	if len(actual) != 2 || actual[1] != "CODEX_HOME=C:\\murong" {
		t.Fatalf("unexpected environment: %#v", actual)
	}
}

package main

import (
	"archive/zip"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestDesktopBackupArchiveRoundTripAndManifestFirst(t *testing.T) {
	root := t.TempDir()
	archivePath := filepath.Join(root, "backup.zip")
	payloads := []desktopBackupPayload{
		{Path: desktopBackupSettingsPath, Category: backupCategorySettings, Data: []byte(`{"dailyBackupEnabled":false,"maxBackupCount":7}`)},
		{Path: desktopBackupSessionsPath, Category: backupCategoryConversations, Data: []byte(`{"schemaVersion":1,"sourcePlatform":"windows","sessions":[]}`)},
	}
	manifest, err := writeDesktopBackupArchive(archivePath, backupKindManual, payloads, time.UnixMilli(1_700_000_000_000))
	if err != nil {
		t.Fatal(err)
	}
	if len(manifest.Entries) != len(payloads) || manifest.Format != desktopBackupFormat {
		t.Fatalf("unexpected manifest: %#v", manifest)
	}
	reader, err := zip.OpenReader(archivePath)
	if err != nil {
		t.Fatal(err)
	}
	if len(reader.File) != 3 || reader.File[0].Name != desktopBackupManifestPath {
		reader.Close()
		t.Fatalf("manifest is not the first ZIP entry: %#v", reader.File)
	}
	reader.Close()
	staging := filepath.Join(root, "staging")
	validated, err := extractAndValidateDesktopBackup(archivePath, staging)
	if err != nil {
		t.Fatal(err)
	}
	if validated.Manifest.Kind != backupKindManual {
		t.Fatalf("unexpected validated manifest: %#v", validated.Manifest)
	}
	data, err := os.ReadFile(filepath.Join(staging, filepath.FromSlash(desktopBackupSessionsPath)))
	if err != nil || !strings.Contains(string(data), `"sessions":[]`) {
		t.Fatalf("payload did not round trip: %q, %v", data, err)
	}
}

func TestDesktopBackupArchiveRejectsUnsafeAndTamperedEntries(t *testing.T) {
	for _, path := range []string{"../outside", "data/../outside", `data\outside`, "C:/outside", "data/session:stream", "data/CON/file.json", "data/bad?.json"} {
		if _, err := validateDesktopBackupRelativePath(path); err == nil {
			t.Fatalf("unsafe backup path was accepted: %q", path)
		}
	}
	root := t.TempDir()
	manifest := DesktopBackupManifest{
		Format: desktopBackupFormat, FormatVersion: desktopBackupFormatVersion,
		CreatedAtEpochMillis: time.Now().UnixMilli(), AppVersionName: desktopBackupAppVersion,
		AppVersionCode: 1, Kind: backupKindManual,
		Entries: []DesktopBackupEntry{{
			Path: desktopBackupSettingsPath, Category: backupCategorySettings,
			SizeBytes: 2, SHA256: strings.Repeat("0", 64),
		}},
	}
	manifestData, err := json.Marshal(manifest)
	if err != nil {
		t.Fatal(err)
	}
	archivePath := filepath.Join(root, "tampered.zip")
	writeRawDesktopBackupZip(t, archivePath, []rawBackupZipEntry{
		{name: desktopBackupManifestPath, data: manifestData},
		{name: desktopBackupSettingsPath, data: []byte(`{}`)},
	})
	if _, err := extractAndValidateDesktopBackup(archivePath, filepath.Join(root, "staging")); err == nil || !strings.Contains(err.Error(), "哈希不匹配") {
		t.Fatalf("tampered payload was not rejected with a hash error: %v", err)
	}
}

func TestDesktopBackupArchiveRejectsManifestNotFirstAndUnknownJSON(t *testing.T) {
	root := t.TempDir()
	wrongOrder := filepath.Join(root, "wrong-order.zip")
	writeRawDesktopBackupZip(t, wrongOrder, []rawBackupZipEntry{
		{name: desktopBackupSettingsPath, data: []byte(`{}`)},
		{name: desktopBackupManifestPath, data: []byte(`{}`)},
	})
	if _, err := extractAndValidateDesktopBackup(wrongOrder, filepath.Join(root, "order-staging")); err == nil || !strings.Contains(err.Error(), "首条目") {
		t.Fatalf("wrong ZIP order was accepted: %v", err)
	}
	unknownField := filepath.Join(root, "unknown-field.zip")
	writeRawDesktopBackupZip(t, unknownField, []rawBackupZipEntry{{
		name: desktopBackupManifestPath,
		data: []byte(`{"format":"murong-backup","formatVersion":1,"createdAtEpochMillis":1,"appVersionName":"x","appVersionCode":1,"kind":"MANUAL","entries":[],"excludedByDefault":[],"unexpected":true}`),
	}})
	if _, err := extractAndValidateDesktopBackup(unknownField, filepath.Join(root, "unknown-staging")); err == nil || !strings.Contains(err.Error(), "unknown field") {
		t.Fatalf("unknown manifest field was accepted: %v", err)
	}
}

type rawBackupZipEntry struct {
	name string
	data []byte
}

func writeRawDesktopBackupZip(t *testing.T, path string, entries []rawBackupZipEntry) {
	t.Helper()
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	writer := zip.NewWriter(file)
	for _, entry := range entries {
		output, err := writer.Create(entry.name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := output.Write(entry.data); err != nil {
			t.Fatal(err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
}

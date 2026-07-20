package main

import (
	"archive/zip"
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

const (
	desktopBackupManifestPath       = "manifest.json"
	desktopBackupMaxEntryCount      = 10_000
	desktopBackupMaxManifestBytes   = 1 * 1024 * 1024
	desktopBackupMaxSingleEntry     = 128 * 1024 * 1024
	desktopBackupMaxTotalPayload    = 512 * 1024 * 1024
	desktopBackupMaxCompressedBytes = 600 * 1024 * 1024
)

var desktopBackupKnownPaths = map[string]string{
	"state/provider-settings.json":     backupCategoryProvider,
	"state/mcp-config.json":            backupCategoryMCP,
	"state/saved-workflows.json":       backupCategoryWorkflows,
	"state/voice-settings.json":        backupCategoryVoice,
	"state/ui-settings.json":           backupCategoryUI,
	"state/backup-settings.json":       backupCategorySettings,
	"state/portable-state.json":        backupCategoryPortableState,
	"data/conversations/sessions.json": backupCategoryConversations,
	"data/memories/knowledge.json":     backupCategoryMemories,
	"data/project_audit/archive.json":  backupCategoryProjectAudit,
}

func writeDesktopBackupArchive(path, kind string, payloads []desktopBackupPayload, createdAt time.Time) (DesktopBackupManifest, error) {
	if len(payloads) > desktopBackupMaxEntryCount {
		return DesktopBackupManifest{}, errors.New("备份条目数量超过上限")
	}
	if kind != backupKindManual && kind != backupKindAutomatic && kind != backupKindPreRestore {
		return DesktopBackupManifest{}, errors.New("备份类型无效")
	}
	if createdAt.IsZero() {
		createdAt = time.Now()
	}
	normalized := make([]desktopBackupPayload, 0, len(payloads))
	seen := make(map[string]bool, len(payloads))
	entries := make([]DesktopBackupEntry, 0, len(payloads))
	var total int64
	for _, payload := range payloads {
		entryPath, err := validateDesktopBackupRelativePath(payload.Path)
		if err != nil {
			return DesktopBackupManifest{}, err
		}
		pathKey := strings.ToLower(entryPath)
		if seen[pathKey] {
			return DesktopBackupManifest{}, fmt.Errorf("备份条目路径重复：%s", entryPath)
		}
		seen[pathKey] = true
		expectedCategory, err := desktopBackupCategoryForPath(entryPath)
		if err != nil {
			return DesktopBackupManifest{}, err
		}
		if payload.Category != expectedCategory {
			return DesktopBackupManifest{}, fmt.Errorf("备份条目类别与路径不匹配：%s", entryPath)
		}
		size := int64(len(payload.Data))
		if size > desktopBackupMaxSingleEntry {
			return DesktopBackupManifest{}, fmt.Errorf("备份条目大小超过上限：%s", entryPath)
		}
		if total > desktopBackupMaxTotalPayload-size {
			return DesktopBackupManifest{}, errors.New("备份总大小超过上限")
		}
		total += size
		hash := sha256.Sum256(payload.Data)
		normalized = append(normalized, desktopBackupPayload{Path: entryPath, Category: payload.Category, Data: payload.Data})
		entries = append(entries, DesktopBackupEntry{Path: entryPath, Category: payload.Category, SizeBytes: size, SHA256: hex.EncodeToString(hash[:])})
	}
	sort.Slice(normalized, func(i, j int) bool { return normalized[i].Path < normalized[j].Path })
	sort.Slice(entries, func(i, j int) bool { return entries[i].Path < entries[j].Path })
	manifest := DesktopBackupManifest{
		Format: desktopBackupFormat, FormatVersion: desktopBackupFormatVersion,
		CreatedAtEpochMillis: createdAt.UnixMilli(), AppVersionName: desktopBackupAppVersion,
		AppVersionCode: desktopBackupAppCode, Kind: kind, Entries: entries,
		ExcludedByDefault: append([]string{}, desktopBackupDefaultExclusions...),
	}
	manifestData, err := json.MarshalIndent(manifest, "", "  ")
	if err != nil {
		return DesktopBackupManifest{}, err
	}
	if len(manifestData) > desktopBackupMaxManifestBytes {
		return DesktopBackupManifest{}, errors.New("版本清单过大")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return DesktopBackupManifest{}, err
	}
	temporary, err := os.CreateTemp(filepath.Dir(path), ".murong-backup-*.tmp")
	if err != nil {
		return DesktopBackupManifest{}, err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err := temporary.Chmod(0o600); err != nil {
		temporary.Close()
		return DesktopBackupManifest{}, err
	}
	buffered := bufio.NewWriter(temporary)
	archive := zip.NewWriter(buffered)
	writeEntry := func(name string, data []byte) error {
		header := &zip.FileHeader{Name: name, Method: zip.Deflate}
		header.SetModTime(createdAt)
		header.SetMode(0o600)
		writer, createErr := archive.CreateHeader(header)
		if createErr != nil {
			return createErr
		}
		_, writeErr := writer.Write(data)
		return writeErr
	}
	writeErr := writeEntry(desktopBackupManifestPath, manifestData)
	if writeErr == nil {
		for _, payload := range normalized {
			if writeErr = writeEntry(payload.Path, payload.Data); writeErr != nil {
				break
			}
		}
	}
	if closeErr := archive.Close(); writeErr == nil {
		writeErr = closeErr
	}
	if flushErr := buffered.Flush(); writeErr == nil {
		writeErr = flushErr
	}
	if syncErr := temporary.Sync(); writeErr == nil {
		writeErr = syncErr
	}
	if closeErr := temporary.Close(); writeErr == nil {
		writeErr = closeErr
	}
	if writeErr != nil {
		return DesktopBackupManifest{}, writeErr
	}
	info, err := os.Stat(temporaryPath)
	if err != nil {
		return DesktopBackupManifest{}, err
	}
	if info.Size() > desktopBackupMaxCompressedBytes {
		return DesktopBackupManifest{}, errors.New("备份包文件大小超过上限")
	}
	if err := replaceFile(temporaryPath, path); err != nil {
		return DesktopBackupManifest{}, err
	}
	return manifest, nil
}

func extractAndValidateDesktopBackup(path, destination string) (validatedDesktopBackup, error) {
	info, err := os.Stat(path)
	if err != nil {
		return validatedDesktopBackup{}, fmt.Errorf("无法读取备份包：%w", err)
	}
	if !info.Mode().IsRegular() {
		return validatedDesktopBackup{}, errors.New("备份路径不是普通文件")
	}
	if info.Size() <= 0 || info.Size() > desktopBackupMaxCompressedBytes {
		return validatedDesktopBackup{}, errors.New("备份包文件大小无效或超过上限")
	}
	reader, err := zip.OpenReader(path)
	if err != nil {
		return validatedDesktopBackup{}, fmt.Errorf("无法打开备份 ZIP：%w", err)
	}
	defer reader.Close()
	if len(reader.File) == 0 {
		return validatedDesktopBackup{}, errors.New("备份包为空")
	}
	if len(reader.File)-1 > desktopBackupMaxEntryCount {
		return validatedDesktopBackup{}, errors.New("备份条目数量超过上限")
	}
	first := reader.File[0]
	if first.Name != desktopBackupManifestPath || first.FileInfo().IsDir() || first.Mode()&os.ModeSymlink != 0 {
		return validatedDesktopBackup{}, errors.New("备份包首条目必须是 manifest.json")
	}
	manifestData, err := readZipFileLimited(first, desktopBackupMaxManifestBytes, "版本清单过大")
	if err != nil {
		return validatedDesktopBackup{}, err
	}
	var manifest DesktopBackupManifest
	if err := decodeStrictJSON(manifestData, &manifest); err != nil {
		return validatedDesktopBackup{}, fmt.Errorf("版本清单无法解析：%w", err)
	}
	if err := validateDesktopBackupManifest(manifest); err != nil {
		return validatedDesktopBackup{}, err
	}
	if err := os.MkdirAll(destination, 0o700); err != nil {
		return validatedDesktopBackup{}, err
	}
	root, err := filepath.Abs(destination)
	if err != nil {
		return validatedDesktopBackup{}, err
	}
	expected := make(map[string]DesktopBackupEntry, len(manifest.Entries))
	for _, entry := range manifest.Entries {
		expected[strings.ToLower(entry.Path)] = entry
	}
	seen := make(map[string]bool, len(expected))
	var totalActual int64
	for _, file := range reader.File[1:] {
		if file.FileInfo().IsDir() || file.Mode()&os.ModeSymlink != 0 {
			return validatedDesktopBackup{}, fmt.Errorf("备份包不允许目录或符号链接条目：%s", file.Name)
		}
		entryPath, err := validateDesktopBackupRelativePath(file.Name)
		if err != nil {
			return validatedDesktopBackup{}, err
		}
		pathKey := strings.ToLower(entryPath)
		expectedEntry, ok := expected[pathKey]
		if !ok {
			return validatedDesktopBackup{}, fmt.Errorf("备份包含清单外条目：%s", entryPath)
		}
		if seen[pathKey] {
			return validatedDesktopBackup{}, fmt.Errorf("备份包含重复条目：%s", entryPath)
		}
		seen[pathKey] = true
		if file.UncompressedSize64 > uint64(desktopBackupMaxSingleEntry) {
			return validatedDesktopBackup{}, fmt.Errorf("备份条目解压后超过上限：%s", entryPath)
		}
		outputPath, err := safeDesktopBackupOutputPath(root, entryPath)
		if err != nil {
			return validatedDesktopBackup{}, err
		}
		if err := os.MkdirAll(filepath.Dir(outputPath), 0o700); err != nil {
			return validatedDesktopBackup{}, err
		}
		input, err := file.Open()
		if err != nil {
			return validatedDesktopBackup{}, err
		}
		output, err := os.OpenFile(outputPath, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
		if err != nil {
			input.Close()
			return validatedDesktopBackup{}, err
		}
		hash := sha256.New()
		limited := io.LimitReader(input, expectedEntry.SizeBytes+1)
		actualSize, copyErr := io.Copy(io.MultiWriter(output, hash), limited)
		closeOutputErr := output.Close()
		closeInputErr := input.Close()
		if copyErr != nil {
			return validatedDesktopBackup{}, copyErr
		}
		if closeOutputErr != nil {
			return validatedDesktopBackup{}, closeOutputErr
		}
		if closeInputErr != nil {
			return validatedDesktopBackup{}, closeInputErr
		}
		if actualSize != expectedEntry.SizeBytes {
			return validatedDesktopBackup{}, fmt.Errorf("备份条目大小不匹配：%s", entryPath)
		}
		if totalActual > desktopBackupMaxTotalPayload-actualSize {
			return validatedDesktopBackup{}, errors.New("备份解压总大小超过上限")
		}
		totalActual += actualSize
		if !strings.EqualFold(hex.EncodeToString(hash.Sum(nil)), expectedEntry.SHA256) {
			return validatedDesktopBackup{}, fmt.Errorf("备份条目哈希不匹配：%s", entryPath)
		}
	}
	if len(seen) != len(expected) {
		for path := range expected {
			if !seen[path] {
				return validatedDesktopBackup{}, fmt.Errorf("备份缺少清单条目：%s", path)
			}
		}
	}
	return validatedDesktopBackup{Manifest: manifest, PayloadRoot: root}, nil
}

func validateDesktopBackupManifest(manifest DesktopBackupManifest) error {
	if manifest.Format != desktopBackupFormat {
		return errors.New("不是 Murong 备份包")
	}
	if manifest.FormatVersion < desktopBackupMinVersion || manifest.FormatVersion > desktopBackupFormatVersion {
		return fmt.Errorf("不支持的备份格式版本：%d", manifest.FormatVersion)
	}
	if manifest.CreatedAtEpochMillis <= 0 || manifest.AppVersionCode <= 0 || strings.TrimSpace(manifest.AppVersionName) == "" {
		return errors.New("备份应用版本或创建时间无效")
	}
	if manifest.Kind != backupKindManual && manifest.Kind != backupKindAutomatic && manifest.Kind != backupKindPreRestore {
		return errors.New("备份类型无效")
	}
	if len(manifest.Entries) > desktopBackupMaxEntryCount {
		return errors.New("备份条目数量超过上限")
	}
	seen := make(map[string]bool, len(manifest.Entries))
	var total int64
	for _, entry := range manifest.Entries {
		entryPath, err := validateDesktopBackupRelativePath(entry.Path)
		if err != nil {
			return err
		}
		pathKey := strings.ToLower(entryPath)
		if seen[pathKey] {
			return fmt.Errorf("清单包含重复路径：%s", entryPath)
		}
		seen[pathKey] = true
		category, err := desktopBackupCategoryForPath(entryPath)
		if err != nil {
			return err
		}
		if entry.Category != category {
			return fmt.Errorf("清单条目类别与路径不匹配：%s", entryPath)
		}
		if entry.SizeBytes < 0 || entry.SizeBytes > desktopBackupMaxSingleEntry {
			return fmt.Errorf("清单条目大小无效：%s", entryPath)
		}
		if len(entry.SHA256) != 64 {
			return fmt.Errorf("清单 SHA-256 无效：%s", entryPath)
		}
		if _, err := hex.DecodeString(entry.SHA256); err != nil {
			return fmt.Errorf("清单 SHA-256 无效：%s", entryPath)
		}
		if total > desktopBackupMaxTotalPayload-entry.SizeBytes {
			return errors.New("清单总大小超过上限")
		}
		total += entry.SizeBytes
	}
	return nil
}

func validateDesktopBackupRelativePath(raw string) (string, error) {
	if raw == "" || len(raw) > 512 {
		return "", errors.New("备份条目路径无效")
	}
	if strings.ContainsAny(raw, "\\\x00:<>\"|?*") || strings.HasPrefix(raw, "/") {
		return "", errors.New("备份条目必须使用安全的相对路径")
	}
	segments := strings.Split(raw, "/")
	for _, segment := range segments {
		if segment == "" || segment == "." || segment == ".." || strings.TrimRight(segment, " .") != segment || desktopBackupReservedWindowsName(segment) {
			return "", errors.New("备份条目存在路径穿越或 Windows 非法路径")
		}
		for _, character := range segment {
			if character < 32 {
				return "", errors.New("备份条目包含控制字符")
			}
		}
	}
	if segments[0] != "data" && segments[0] != "state" {
		return "", errors.New("备份条目不在允许的命名空间")
	}
	return strings.Join(segments, "/"), nil
}

func desktopBackupReservedWindowsName(segment string) bool {
	base := strings.ToUpper(strings.SplitN(segment, ".", 2)[0])
	if base == "CON" || base == "PRN" || base == "AUX" || base == "NUL" {
		return true
	}
	if len(base) == 4 && (strings.HasPrefix(base, "COM") || strings.HasPrefix(base, "LPT")) && base[3] >= '1' && base[3] <= '9' {
		return true
	}
	return false
}

func desktopBackupCategoryForPath(path string) (string, error) {
	if category := desktopBackupKnownPaths[path]; category != "" {
		return category, nil
	}
	switch {
	case strings.HasPrefix(path, "data/conversations/"):
		return backupCategoryConversations, nil
	case strings.HasPrefix(path, "data/conversation_media/"):
		return backupCategoryMedia, nil
	case strings.HasPrefix(path, "data/memories/"):
		return backupCategoryMemories, nil
	case strings.HasPrefix(path, "data/project_audit/"):
		return backupCategoryProjectAudit, nil
	default:
		return "", fmt.Errorf("清单包含未知条目路径：%s", path)
	}
}

func safeDesktopBackupOutputPath(root, relative string) (string, error) {
	output := filepath.Join(root, filepath.FromSlash(relative))
	cleanOutput, err := filepath.Abs(output)
	if err != nil {
		return "", err
	}
	prefix := strings.TrimRight(root, string(os.PathSeparator)) + string(os.PathSeparator)
	if !strings.HasPrefix(strings.ToLower(cleanOutput), strings.ToLower(prefix)) {
		return "", errors.New("备份条目越过恢复目录")
	}
	return cleanOutput, nil
}

func readZipFileLimited(file *zip.File, maximum int64, message string) ([]byte, error) {
	if file.UncompressedSize64 > uint64(maximum) {
		return nil, errors.New(message)
	}
	reader, err := file.Open()
	if err != nil {
		return nil, err
	}
	defer reader.Close()
	data, err := io.ReadAll(io.LimitReader(reader, maximum+1))
	if err != nil {
		return nil, err
	}
	if int64(len(data)) > maximum {
		return nil, errors.New(message)
	}
	return data, nil
}

func decodeStrictJSON(data []byte, target any) error {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return err
	}
	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		if err == nil {
			return errors.New("JSON 包含多余内容")
		}
		return err
	}
	return nil
}

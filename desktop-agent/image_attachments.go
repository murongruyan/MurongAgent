package main

import (
	"bytes"
	"encoding/base64"
	"errors"
	"fmt"
	"image"
	_ "image/gif"
	"image/jpeg"
	"image/png"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
	"unicode"

	"github.com/wailsapp/wails/v2/pkg/runtime"
	_ "golang.org/x/image/bmp"
	"golang.org/x/image/draw"
	_ "golang.org/x/image/webp"
)

const (
	maxChatImagesPerMessage = 8
	maxChatImageSourceBytes = 20 * 1024 * 1024
	maxChatImageStoredBytes = 4 * 1024 * 1024
	maxChatImageDimension   = 2048
	targetChatImageBytes    = 500_000
)

var chatImageIDPattern = regexp.MustCompile(`^image-[0-9a-f]{24}$`)

type modelImageAttachment struct {
	MimeType   string
	Base64Data string
}

func (store *desktopStore) conversationMediaRoot() string {
	return filepath.Join(filepath.Dir(store.sessionsPath), "conversation_media")
}

func (app *DesktopAgentApp) SelectChatImages() ([]SelectedChatImage, error) {
	if !app.store.rawConfig().EnableMultimodalMessages {
		return nil, errors.New("多模态消息已在设置中关闭")
	}
	if app.ctx == nil {
		return nil, errors.New("窗口尚未就绪")
	}
	paths, err := runtime.OpenMultipleFilesDialog(app.ctx, runtime.OpenDialogOptions{
		Title: "选择图片",
		Filters: []runtime.FileFilter{{
			DisplayName: "图片 (*.jpg;*.jpeg;*.png;*.webp;*.gif;*.bmp)",
			Pattern:     "*.jpg;*.jpeg;*.png;*.webp;*.gif;*.bmp",
		}},
	})
	if err != nil || len(paths) == 0 {
		return nil, err
	}
	if len(paths) > maxChatImagesPerMessage {
		return nil, fmt.Errorf("每条消息最多选择 %d 张图片", maxChatImagesPerMessage)
	}
	selected := make([]SelectedChatImage, 0, len(paths))
	for _, path := range paths {
		value, importErr := importChatImage(app.store.conversationMediaRoot(), path)
		if importErr != nil {
			for _, imported := range selected {
				_ = os.Remove(filepath.Join(app.store.conversationMediaRoot(), imported.Attachment.CacheFile))
			}
			return nil, fmt.Errorf("无法导入图片 %q：%w", filepath.Base(path), importErr)
		}
		selected = append(selected, value)
	}
	return selected, nil
}

func (app *DesktopAgentApp) DiscardChatImage(request DiscardChatImageRequest) error {
	request.ImageID = strings.TrimSpace(request.ImageID)
	request.CacheFile = strings.TrimSpace(request.CacheFile)
	if !chatImageIDPattern.MatchString(request.ImageID) || request.CacheFile == "" || filepath.Base(request.CacheFile) != request.CacheFile {
		return errors.New("待删除图片标识无效")
	}
	for _, session := range app.store.allSessions() {
		for _, message := range session.Messages {
			for _, attachment := range message.ImageAttachments {
				if attachment.ID == request.ImageID || strings.EqualFold(attachment.CacheFile, request.CacheFile) {
					return errors.New("图片已属于会话，不能作为待发送图片删除")
				}
			}
		}
	}
	path, err := safeChatImagePath(app.store.conversationMediaRoot(), request.CacheFile)
	if err != nil {
		return err
	}
	if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}

func (app *DesktopAgentApp) GetChatImageDataURL(request ChatImagePreviewRequest) (string, error) {
	session := app.store.getSession(strings.TrimSpace(request.SessionID))
	if session == nil {
		return "", errors.New("会话不存在")
	}
	for _, message := range session.Messages {
		if message.ID != strings.TrimSpace(request.MessageID) {
			continue
		}
		for _, attachment := range message.ImageAttachments {
			if attachment.ID == strings.TrimSpace(request.ImageID) {
				return chatImageDataURL(app.store.conversationMediaRoot(), attachment)
			}
		}
		return "", errors.New("消息中不存在这张图片")
	}
	return "", errors.New("消息不存在")
}

func importChatImage(mediaRoot, sourcePath string) (SelectedChatImage, error) {
	info, err := os.Lstat(sourcePath)
	if err != nil {
		return SelectedChatImage{}, err
	}
	if !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 || info.Size() <= 0 || info.Size() > maxChatImageSourceBytes {
		return SelectedChatImage{}, errors.New("图片必须是 20 MiB 以内的普通文件")
	}
	file, err := os.Open(sourcePath)
	if err != nil {
		return SelectedChatImage{}, err
	}
	data, readErr := io.ReadAll(io.LimitReader(file, maxChatImageSourceBytes+1))
	closeErr := file.Close()
	if readErr != nil {
		return SelectedChatImage{}, readErr
	}
	if closeErr != nil {
		return SelectedChatImage{}, closeErr
	}
	if len(data) == 0 || len(data) > maxChatImageSourceBytes {
		return SelectedChatImage{}, errors.New("图片为空或超过 20 MiB")
	}
	decoded, format, err := image.Decode(bytes.NewReader(data))
	if err != nil {
		return SelectedChatImage{}, errors.New("文件不是受支持的 JPEG、PNG、WebP、GIF 或 BMP 图片")
	}
	bounds := decoded.Bounds()
	width, height := bounds.Dx(), bounds.Dy()
	if width < 1 || height < 1 || width > 32_768 || height > 32_768 {
		return SelectedChatImage{}, errors.New("图片尺寸无效或过大")
	}
	outputMime := "image/jpeg"
	extension := "jpg"
	if format == "png" {
		outputMime, extension = "image/png", "png"
	}
	needsEncoding := len(data) > targetChatImageBytes || width > maxChatImageDimension || height > maxChatImageDimension || (format != "jpeg" && format != "png")
	output := data
	if needsEncoding {
		decoded = resizeChatImage(decoded, maxChatImageDimension)
		bounds = decoded.Bounds()
		width, height = bounds.Dx(), bounds.Dy()
		output, err = encodeChatImage(decoded, outputMime)
		if err != nil {
			return SelectedChatImage{}, err
		}
	}
	if len(output) == 0 || len(output) > maxChatImageStoredBytes {
		return SelectedChatImage{}, errors.New("处理后的图片仍超过 4 MiB")
	}
	if err := os.MkdirAll(mediaRoot, 0o700); err != nil {
		return SelectedChatImage{}, err
	}
	id := newID("image")
	cacheFile := id + "." + extension
	target, err := safeChatImagePath(mediaRoot, cacheFile)
	if err != nil {
		return SelectedChatImage{}, err
	}
	if err := writeChatImageAtomic(target, output); err != nil {
		return SelectedChatImage{}, err
	}
	attachment := MessageImageAttachment{
		ID: id, FileName: normalizeChatImageFileName(filepath.Base(sourcePath), extension),
		MimeType: outputMime, CacheFile: cacheFile, Width: width, Height: height, SizeBytes: int64(len(output)),
	}
	return SelectedChatImage{Attachment: attachment, PreviewDataURL: dataURL(outputMime, output)}, nil
}

func resizeChatImage(source image.Image, maximum int) image.Image {
	bounds := source.Bounds()
	width, height := bounds.Dx(), bounds.Dy()
	longest := max(width, height)
	if longest <= maximum {
		return source
	}
	scale := float64(maximum) / float64(longest)
	targetWidth := max(1, int(float64(width)*scale+0.5))
	targetHeight := max(1, int(float64(height)*scale+0.5))
	target := image.NewRGBA(image.Rect(0, 0, targetWidth, targetHeight))
	draw.CatmullRom.Scale(target, target.Bounds(), source, bounds, draw.Over, nil)
	return target
}

func encodeChatImage(value image.Image, mimeType string) ([]byte, error) {
	var output bytes.Buffer
	if mimeType == "image/png" {
		if err := png.Encode(&output, value); err != nil {
			return nil, err
		}
		return output.Bytes(), nil
	}
	quality := 85
	for {
		output.Reset()
		if err := jpeg.Encode(&output, value, &jpeg.Options{Quality: quality}); err != nil {
			return nil, err
		}
		if output.Len() <= targetChatImageBytes || quality <= 55 {
			return append([]byte(nil), output.Bytes()...), nil
		}
		quality -= 10
	}
}

func writeChatImageAtomic(path string, data []byte) error {
	temporary, err := os.CreateTemp(filepath.Dir(path), ".murong-image-*.tmp")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err := temporary.Chmod(0o600); err != nil {
		temporary.Close()
		return err
	}
	if _, err := temporary.Write(data); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Sync(); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	return replaceFile(temporaryPath, path)
}

func normalizeChatImageFileName(value, extension string) string {
	value = filepath.Base(strings.TrimSpace(value))
	value = strings.Map(func(character rune) rune {
		if unicode.IsControl(character) || strings.ContainsRune(`<>:"/\\|?*`, character) {
			return '_'
		}
		return character
	}, value)
	value = strings.Trim(value, " .")
	if value == "" {
		value = "image." + extension
	}
	return truncateRunes(value, 180)
}

func safeChatImagePath(mediaRoot, cacheFile string) (string, error) {
	if cacheFile == "" || filepath.Base(cacheFile) != cacheFile || strings.ContainsAny(cacheFile, "\\/\x00:") {
		return "", errors.New("图片缓存文件名无效")
	}
	root, err := filepath.Abs(mediaRoot)
	if err != nil {
		return "", err
	}
	path, err := filepath.Abs(filepath.Join(root, cacheFile))
	if err != nil {
		return "", err
	}
	if !strings.EqualFold(filepath.Dir(path), root) {
		return "", errors.New("图片缓存路径越界")
	}
	return path, nil
}

func validateChatImageAttachment(mediaRoot string, attachment MessageImageAttachment) (string, error) {
	if err := validateChatImageAttachmentMetadata(attachment); err != nil {
		return "", err
	}
	path, err := safeChatImagePath(mediaRoot, attachment.CacheFile)
	if err != nil {
		return "", err
	}
	info, err := os.Lstat(path)
	if err != nil {
		return "", errors.New("图片附件缓存不存在")
	}
	if !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 || info.Size() != attachment.SizeBytes {
		return "", errors.New("图片附件缓存类型或大小不匹配")
	}
	return path, nil
}

func validateChatImageAttachmentMetadata(attachment MessageImageAttachment) error {
	if !chatImageIDPattern.MatchString(attachment.ID) || attachment.FileName == "" || len([]rune(attachment.FileName)) > 180 {
		return errors.New("图片附件标识或名称无效")
	}
	extension := ".jpg"
	if attachment.MimeType == "image/png" {
		extension = ".png"
	} else if attachment.MimeType != "image/jpeg" {
		return errors.New("图片附件 MIME 类型无效")
	}
	if attachment.CacheFile != attachment.ID+extension || attachment.Width < 1 || attachment.Height < 1 || attachment.Width > maxChatImageDimension || attachment.Height > maxChatImageDimension || attachment.SizeBytes < 1 || attachment.SizeBytes > maxChatImageStoredBytes {
		return errors.New("图片附件元数据无效")
	}
	return nil
}

func (store *desktopStore) validateMessageImages(values []MessageImageAttachment) ([]MessageImageAttachment, error) {
	if len(values) > maxChatImagesPerMessage {
		return nil, fmt.Errorf("每条消息最多包含 %d 张图片", maxChatImagesPerMessage)
	}
	result := make([]MessageImageAttachment, 0, len(values))
	seen := map[string]bool{}
	for _, attachment := range values {
		if seen[attachment.ID] {
			return nil, errors.New("消息包含重复图片")
		}
		seen[attachment.ID] = true
		if _, err := validateChatImageAttachment(store.conversationMediaRoot(), attachment); err != nil {
			return nil, err
		}
		result = append(result, attachment)
	}
	return result, nil
}

func (store *desktopStore) modelImages(values []MessageImageAttachment) ([]modelImageAttachment, error) {
	result := make([]modelImageAttachment, 0, len(values))
	for _, attachment := range values {
		path, err := validateChatImageAttachment(store.conversationMediaRoot(), attachment)
		if err != nil {
			return nil, err
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return nil, err
		}
		result = append(result, modelImageAttachment{MimeType: attachment.MimeType, Base64Data: base64.StdEncoding.EncodeToString(data)})
	}
	return result, nil
}

func (store *desktopStore) imagePaths(values []MessageImageAttachment) ([]string, error) {
	result := make([]string, 0, len(values))
	for _, attachment := range values {
		path, err := validateChatImageAttachment(store.conversationMediaRoot(), attachment)
		if err != nil {
			return nil, err
		}
		result = append(result, path)
	}
	return result, nil
}

func chatImageDataURL(mediaRoot string, attachment MessageImageAttachment) (string, error) {
	path, err := validateChatImageAttachment(mediaRoot, attachment)
	if err != nil {
		return "", err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	return dataURL(attachment.MimeType, data), nil
}

func dataURL(mimeType string, data []byte) string {
	return "data:" + mimeType + ";base64," + base64.StdEncoding.EncodeToString(data)
}

func (store *desktopStore) allSessions() []*ChatSession {
	store.mu.Lock()
	defer store.mu.Unlock()
	result := make([]*ChatSession, 0, len(store.sessions))
	for _, session := range store.sessions {
		result = append(result, cloneSession(session))
	}
	return result
}

func (store *desktopStore) cleanupOrphanedChatImages() {
	referenced := map[string]bool{}
	for _, session := range store.allSessions() {
		for _, message := range session.Messages {
			for _, attachment := range message.ImageAttachments {
				referenced[strings.ToLower(attachment.CacheFile)] = true
			}
		}
	}
	entries, err := os.ReadDir(store.conversationMediaRoot())
	if err != nil {
		return
	}
	cutoff := time.Now().Add(-24 * time.Hour)
	for _, entry := range entries {
		if entry.IsDir() || referenced[strings.ToLower(entry.Name())] {
			continue
		}
		info, infoErr := entry.Info()
		if infoErr == nil && info.ModTime().Before(cutoff) {
			_ = os.Remove(filepath.Join(store.conversationMediaRoot(), entry.Name()))
		}
	}
}

func buildConversationMediaBackupPayloads(store *desktopStore, sessions []*ChatSession) ([]desktopBackupPayload, error) {
	result := []desktopBackupPayload{}
	seen := map[string]bool{}
	for _, session := range sessions {
		if session == nil {
			continue
		}
		for _, message := range session.Messages {
			for _, attachment := range message.ImageAttachments {
				key := strings.ToLower(attachment.CacheFile)
				if seen[key] {
					continue
				}
				path, err := validateChatImageAttachment(store.conversationMediaRoot(), attachment)
				if err != nil {
					return nil, fmt.Errorf("会话 %s 的图片 %q 无法备份：%w", session.ID, attachment.FileName, err)
				}
				data, err := os.ReadFile(path)
				if err != nil {
					return nil, err
				}
				seen[key] = true
				result = append(result, desktopBackupPayload{
					Path: "data/conversation_media/" + attachment.CacheFile, Category: backupCategoryMedia, Data: data,
				})
			}
		}
	}
	return result, nil
}

func validateConversationMediaBackup(validated validatedDesktopBackup, sessions []*ChatSession) error {
	manifestMedia := map[string]bool{}
	for _, entry := range validated.Manifest.Entries {
		if entry.Category == backupCategoryMedia {
			manifestMedia[strings.ToLower(strings.TrimPrefix(entry.Path, "data/conversation_media/"))] = true
		}
	}
	referenced := map[string]bool{}
	mediaRoot := filepath.Join(validated.PayloadRoot, "data", "conversation_media")
	for _, session := range sessions {
		if session == nil {
			continue
		}
		for _, message := range session.Messages {
			for _, attachment := range message.ImageAttachments {
				key := strings.ToLower(attachment.CacheFile)
				if !manifestMedia[key] {
					return fmt.Errorf("会话图片缺少备份媒体条目：%s", attachment.CacheFile)
				}
				if _, err := validateChatImageAttachment(mediaRoot, attachment); err != nil {
					return fmt.Errorf("备份会话图片无效：%w", err)
				}
				referenced[key] = true
			}
		}
	}
	for name := range manifestMedia {
		if !referenced[name] {
			return fmt.Errorf("备份包含未被会话引用的媒体：%s", name)
		}
	}
	return nil
}

type conversationMediaRestore struct {
	target   string
	staged   string
	previous string
	swapped  bool
}

func prepareConversationMediaRestore(store *desktopStore, payloadRoot string) (*conversationMediaRestore, error) {
	target := store.conversationMediaRoot()
	parent := filepath.Dir(target)
	if err := os.MkdirAll(parent, 0o700); err != nil {
		return nil, err
	}
	staged, err := os.MkdirTemp(parent, ".conversation-media-restore-")
	if err != nil {
		return nil, err
	}
	transaction := &conversationMediaRestore{
		target: target, staged: staged, previous: filepath.Join(parent, ".conversation-media-rollback-"+newID("media")),
	}
	source := filepath.Join(payloadRoot, "data", "conversation_media")
	entries, readErr := os.ReadDir(source)
	if readErr != nil && !errors.Is(readErr, os.ErrNotExist) {
		transaction.Finish()
		return nil, readErr
	}
	for _, entry := range entries {
		if entry.IsDir() {
			transaction.Finish()
			return nil, errors.New("备份媒体目录包含子目录")
		}
		name := entry.Name()
		if filepath.Base(name) != name {
			transaction.Finish()
			return nil, errors.New("备份媒体文件名无效")
		}
		data, err := os.ReadFile(filepath.Join(source, name))
		if err != nil {
			transaction.Finish()
			return nil, err
		}
		if err := writeChatImageAtomic(filepath.Join(staged, name), data); err != nil {
			transaction.Finish()
			return nil, err
		}
	}
	return transaction, nil
}

func (transaction *conversationMediaRestore) Commit() error {
	if transaction == nil || transaction.swapped {
		return nil
	}
	if _, err := os.Stat(transaction.target); err == nil {
		if err := os.Rename(transaction.target, transaction.previous); err != nil {
			return err
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if err := os.Rename(transaction.staged, transaction.target); err != nil {
		if _, previousErr := os.Stat(transaction.previous); previousErr == nil {
			_ = os.Rename(transaction.previous, transaction.target)
		}
		return err
	}
	transaction.staged = ""
	transaction.swapped = true
	return nil
}

func (transaction *conversationMediaRestore) Rollback() error {
	if transaction == nil || !transaction.swapped {
		return nil
	}
	if err := os.RemoveAll(transaction.target); err != nil {
		return err
	}
	if _, err := os.Stat(transaction.previous); err == nil {
		if err := os.Rename(transaction.previous, transaction.target); err != nil {
			return err
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	transaction.swapped = false
	return nil
}

func (transaction *conversationMediaRestore) Finish() {
	if transaction == nil {
		return
	}
	if transaction.staged != "" {
		_ = os.RemoveAll(transaction.staged)
	}
	_ = os.RemoveAll(transaction.previous)
}

package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"image"
	_ "image/gif"
	_ "image/jpeg"
	_ "image/png"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"

	_ "golang.org/x/image/bmp"
	_ "golang.org/x/image/webp"
)

const maxWorkbenchImageBytes = 16 * 1024 * 1024

var workbenchImageMIMETypes = map[string]string{
	".png": "image/png",
	".jpg": "image/jpeg", ".jpeg": "image/jpeg",
	".gif":  "image/gif",
	".webp": "image/webp",
	".bmp":  "image/bmp",
	".ico":  "image/x-icon",
}

var workbenchImageFormats = map[string]string{
	".png": "png",
	".jpg": "jpeg", ".jpeg": "jpeg",
	".gif":  "gif",
	".webp": "webp",
	".bmp":  "bmp",
}

type WorkbenchFileEntry struct {
	Path      string `json:"path"`
	Name      string `json:"name"`
	Directory bool   `json:"directory"`
	Size      int64  `json:"size,omitempty"`
	Preview   string `json:"preview,omitempty"`
}

type WorkbenchFileDocument struct {
	Path    string `json:"path"`
	Content string `json:"content"`
	SHA256  string `json:"sha256"`
	Size    int64  `json:"size"`
}

type WorkbenchSaveFileRequest struct {
	Path           string `json:"path"`
	Content        string `json:"content"`
	ExpectedSHA256 string `json:"expectedSha256"`
}

type WorkbenchFileAsset struct {
	Path     string `json:"path"`
	MIMEType string `json:"mimeType"`
	Base64   string `json:"base64"`
	SHA256   string `json:"sha256"`
	Size     int64  `json:"size"`
	Width    int    `json:"width,omitempty"`
	Height   int    `json:"height,omitempty"`
}

type WorkbenchGitSnapshot struct {
	Repository bool   `json:"repository"`
	Branch     string `json:"branch,omitempty"`
	Status     string `json:"status"`
	Diff       string `json:"diff"`
	Message    string `json:"message,omitempty"`
}

func (app *DesktopAgentApp) workbenchWorkspace() (*localWorkspace, error) {
	if app == nil || app.store == nil {
		return nil, errors.New("桌面工作区尚未初始化")
	}
	return newLocalWorkspace(app.store.rawConfig().ProjectPath)
}

func (app *DesktopAgentApp) ListWorkbenchFiles(relative string) ([]WorkbenchFileEntry, error) {
	workspace, err := app.workbenchWorkspace()
	if err != nil {
		return nil, err
	}
	entries, err := workspace.list(relative, 1)
	if err != nil {
		return nil, err
	}
	result := make([]WorkbenchFileEntry, 0, len(entries))
	for _, entry := range entries {
		preview := ""
		if !entry.Directory {
			if _, ok := workbenchImageMIMETypes[strings.ToLower(filepath.Ext(entry.Path))]; ok {
				preview = "image"
			}
		}
		result = append(result, WorkbenchFileEntry{
			Path: entry.Path, Name: filepath.Base(filepath.FromSlash(entry.Path)),
			Directory: entry.Directory, Size: entry.Size, Preview: preview,
		})
	}
	sort.SliceStable(result, func(left, right int) bool {
		if result[left].Directory != result[right].Directory {
			return result[left].Directory
		}
		return strings.ToLower(result[left].Name) < strings.ToLower(result[right].Name)
	})
	return result, nil
}

func (app *DesktopAgentApp) ReadWorkbenchAsset(relative string) (WorkbenchFileAsset, error) {
	workspace, err := app.workbenchWorkspace()
	if err != nil {
		return WorkbenchFileAsset{}, err
	}
	extension := strings.ToLower(filepath.Ext(strings.TrimSpace(relative)))
	mimeType, allowed := workbenchImageMIMETypes[extension]
	if !allowed {
		return WorkbenchFileAsset{}, errors.New("该文件类型不允许作为图片预览")
	}
	path, err := workspace.resolveExisting(relative, false)
	if err != nil {
		return WorkbenchFileAsset{}, err
	}
	info, err := os.Stat(path)
	if err != nil || !info.Mode().IsRegular() {
		return WorkbenchFileAsset{}, errors.New("图片目标不是普通文件")
	}
	if info.Size() > maxWorkbenchImageBytes {
		return WorkbenchFileAsset{}, errors.New("图片超过 16 MiB 预览上限")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return WorkbenchFileAsset{}, err
	}
	width, height := 0, 0
	if extension == ".ico" {
		if len(data) < 6 || !bytes.Equal(data[:4], []byte{0, 0, 1, 0}) {
			return WorkbenchFileAsset{}, errors.New("ICO 图片头无效")
		}
	} else {
		config, format, decodeErr := image.DecodeConfig(bytes.NewReader(data))
		if decodeErr != nil {
			return WorkbenchFileAsset{}, errors.New("图片内容损坏或与扩展名不匹配")
		}
		if expected := workbenchImageFormats[extension]; format != expected {
			return WorkbenchFileAsset{}, errors.New("图片内容与文件扩展名不匹配")
		}
		width, height = config.Width, config.Height
	}
	digest := sha256.Sum256(data)
	return WorkbenchFileAsset{
		Path: relative, MIMEType: mimeType, Base64: base64.StdEncoding.EncodeToString(data),
		SHA256: hex.EncodeToString(digest[:]), Size: info.Size(), Width: width, Height: height,
	}, nil
}

func (app *DesktopAgentApp) ReadWorkbenchFile(relative string) (WorkbenchFileDocument, error) {
	workspace, err := app.workbenchWorkspace()
	if err != nil {
		return WorkbenchFileDocument{}, err
	}
	snapshot, err := workspace.read(relative)
	if err != nil {
		return WorkbenchFileDocument{}, err
	}
	return WorkbenchFileDocument(snapshot), nil
}

func (app *DesktopAgentApp) SaveWorkbenchFile(request WorkbenchSaveFileRequest) (WorkbenchFileDocument, error) {
	workspace, err := app.workbenchWorkspace()
	if err != nil {
		return WorkbenchFileDocument{}, err
	}
	request.Path = strings.TrimSpace(request.Path)
	if app.workspace != nil {
		app.workspace.IgnoreAgentPaths(workspace.root, request.Path)
	}
	written, _, err := workspace.write(request.Path, request.Content, strings.TrimSpace(request.ExpectedSHA256))
	if err != nil {
		return WorkbenchFileDocument{}, err
	}
	return WorkbenchFileDocument(written), nil
}

func (app *DesktopAgentApp) GetWorkbenchGit() (WorkbenchGitSnapshot, error) {
	workspace, err := app.workbenchWorkspace()
	if err != nil {
		return WorkbenchGitSnapshot{}, err
	}
	git, err := exec.LookPath("git")
	if err != nil {
		return WorkbenchGitSnapshot{}, errors.New("当前电脑没有安装 Git")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	inside, insideErr := runWorkbenchGit(ctx, git, workspace.root, "rev-parse", "--is-inside-work-tree")
	if insideErr != nil || strings.TrimSpace(inside) != "true" {
		return WorkbenchGitSnapshot{Repository: false, Message: "当前项目还不是 Git 仓库"}, nil
	}
	status, err := runWorkbenchGit(ctx, git, workspace.root, "status", "--short", "--branch")
	if err != nil {
		return WorkbenchGitSnapshot{}, err
	}
	diff, err := runWorkbenchGit(ctx, git, workspace.root, "diff", "--no-ext-diff", "--")
	if err != nil {
		return WorkbenchGitSnapshot{}, err
	}
	branch := ""
	if first, _, found := strings.Cut(status, "\n"); found || first != "" {
		branch = strings.TrimSpace(strings.TrimPrefix(first, "##"))
	}
	return WorkbenchGitSnapshot{
		Repository: true, Branch: branch, Status: strings.TrimSpace(status), Diff: strings.TrimSpace(diff),
		Message: "Git 工作树已刷新",
	}, nil
}

func runWorkbenchGit(ctx context.Context, executable, directory string, arguments ...string) (string, error) {
	command := exec.CommandContext(ctx, executable, arguments...)
	command.Dir = directory
	prepareHiddenCommand(command)
	var stdout, stderr cappedBuffer
	stdout.limit = maxLocalOutputBytes / 2
	stderr.limit = maxLocalOutputBytes / 2
	command.Stdout = &stdout
	command.Stderr = &stderr
	if err := command.Run(); err != nil {
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			return "", errors.New("Git 命令执行超时")
		}
		message := strings.TrimSpace(decodeCommandBytes(stderr.data))
		if message == "" {
			message = err.Error()
		}
		return "", fmt.Errorf("Git 执行失败：%s", message)
	}
	return decodeCommandBytes(stdout.data), nil
}

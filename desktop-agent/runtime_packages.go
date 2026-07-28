package main

import (
	"archive/tar"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	stdruntime "runtime"
	"strings"
	"sync"
	"time"
)

const (
	desktopRuntimeDownloadTimeout = 30 * time.Minute
	desktopRuntimeManifestMax     = int64(2 << 20)
)

type desktopReleasePackage struct {
	FileName  string `json:"fileName"`
	Size      int64  `json:"size"`
	SHA256    string `json:"sha256"`
	Kind      string `json:"kind,omitempty"`
	Component string `json:"component,omitempty"`
	OS        string `json:"os,omitempty"`
	Arch      string `json:"arch,omitempty"`
	Version   string `json:"version,omitempty"`
}

type desktopRuntimeInstallSpec struct {
	PackageName     string
	TargetDirectory string
	StripPrefix     string
	RequiredPaths   []string
	MaxArchiveBytes int64
	MaxFiles        int
	MaxExtractBytes int64
}

var desktopRuntimeInstallMu sync.Mutex

func desktopRuntimePackageName(component, version string) string {
	return fmt.Sprintf(
		"murong-runtime-%s-%s-%s-%s.tgz",
		strings.ToLower(strings.TrimSpace(component)),
		stdruntime.GOOS,
		stdruntime.GOARCH,
		strings.TrimSpace(version),
	)
}

func desktopRuntimeReleaseBaseURL() string {
	return "https://github.com/murongruyan/MurongAgent/releases/download/murong-suite-v" +
		desktopAgentVersion
}

func ensureDesktopRuntimePackage(ctx context.Context, spec desktopRuntimeInstallSpec) error {
	desktopRuntimeInstallMu.Lock()
	defer desktopRuntimeInstallMu.Unlock()

	if err := validateDesktopRuntimeInstallSpec(spec); err != nil {
		return err
	}
	if desktopRuntimeTargetReady(spec.TargetDirectory, spec.RequiredPaths) {
		return nil
	}
	parent, err := filepath.Abs(filepath.Dir(spec.TargetDirectory))
	if err != nil {
		return err
	}
	if err := os.MkdirAll(parent, 0o700); err != nil {
		return fmt.Errorf("无法创建运行时目录：%w", err)
	}

	downloadContext, cancel := context.WithTimeout(ctx, desktopRuntimeDownloadTimeout)
	defer cancel()
	releasePackage, err := fetchDesktopRuntimePackageManifest(
		downloadContext,
		http.DefaultClient,
		spec.PackageName,
	)
	if err != nil {
		return err
	}
	if releasePackage.Size <= 0 || releasePackage.Size > spec.MaxArchiveBytes {
		return fmt.Errorf("运行时包 %s 的发布大小无效", spec.PackageName)
	}

	archive, err := os.CreateTemp(parent, ".murong-runtime-*.tgz.download")
	if err != nil {
		return err
	}
	archivePath := archive.Name()
	if closeErr := archive.Close(); closeErr != nil {
		_ = os.Remove(archivePath)
		return closeErr
	}
	defer os.Remove(archivePath)

	assetURL := desktopRuntimeReleaseBaseURL() + "/" + spec.PackageName
	if err := downloadDesktopRuntimeArchive(
		downloadContext,
		http.DefaultClient,
		assetURL,
		archivePath,
		releasePackage,
		spec.MaxArchiveBytes,
	); err != nil {
		return err
	}

	temp, err := os.MkdirTemp(parent, ".murong-runtime-install-*")
	if err != nil {
		return err
	}
	defer os.RemoveAll(temp)
	if err := extractDesktopRuntimeArchive(
		archivePath,
		temp,
		spec.StripPrefix,
		spec.MaxFiles,
		spec.MaxExtractBytes,
	); err != nil {
		return fmt.Errorf("解压运行时包 %s 失败：%w", spec.PackageName, err)
	}
	if !desktopRuntimeTargetReady(temp, spec.RequiredPaths) {
		return fmt.Errorf("运行时包 %s 缺少必需文件", spec.PackageName)
	}
	marker := strings.ToLower(strings.TrimSpace(releasePackage.SHA256)) + "\n"
	if err := os.WriteFile(filepath.Join(temp, ".runtime-package-sha256"), []byte(marker), 0o600); err != nil {
		return err
	}
	if err := replaceDesktopRuntimeDirectory(parent, spec.TargetDirectory, temp); err != nil {
		return err
	}
	return nil
}

func validateDesktopRuntimeInstallSpec(spec desktopRuntimeInstallSpec) error {
	if strings.TrimSpace(spec.PackageName) == "" ||
		filepath.Base(spec.PackageName) != spec.PackageName ||
		!strings.HasSuffix(strings.ToLower(spec.PackageName), ".tgz") {
		return errors.New("运行时包名称无效")
	}
	if strings.TrimSpace(spec.TargetDirectory) == "" || len(spec.RequiredPaths) == 0 {
		return errors.New("运行时安装目标无效")
	}
	if spec.MaxArchiveBytes <= 0 || spec.MaxFiles <= 0 || spec.MaxExtractBytes <= 0 {
		return errors.New("运行时安全限制无效")
	}
	return nil
}

func desktopRuntimeTargetReady(root string, requiredPaths []string) bool {
	for _, relative := range requiredPaths {
		clean := filepath.Clean(relative)
		if clean == "." || filepath.IsAbs(clean) || clean == ".." ||
			strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
			return false
		}
		info, err := os.Stat(filepath.Join(root, clean))
		if err != nil || !info.Mode().IsRegular() {
			return false
		}
	}
	return true
}

func fetchDesktopRuntimePackageManifest(
	ctx context.Context,
	client *http.Client,
	packageName string,
) (desktopReleasePackage, error) {
	manifestURL := desktopRuntimeReleaseBaseURL() + "/release-manifest.json"
	if err := validateDesktopReleaseURL(manifestURL); err != nil {
		return desktopReleasePackage{}, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, manifestURL, nil)
	if err != nil {
		return desktopReleasePackage{}, err
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("User-Agent", "Murong-Desktop-Agent/"+desktopAgentVersion)
	response, err := client.Do(request)
	if err != nil {
		return desktopReleasePackage{}, fmt.Errorf("获取运行时清单失败：%w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return desktopReleasePackage{}, fmt.Errorf("获取运行时清单失败：GitHub 返回 HTTP %d", response.StatusCode)
	}
	var manifest desktopReleaseManifest
	if err := json.NewDecoder(io.LimitReader(response.Body, desktopRuntimeManifestMax)).
		Decode(&manifest); err != nil {
		return desktopReleasePackage{}, fmt.Errorf("解析运行时清单失败：%w", err)
	}
	for _, item := range manifest.Packages {
		if item.FileName != packageName {
			continue
		}
		hash := strings.TrimSpace(item.SHA256)
		if len(hash) != sha256.Size*2 {
			return desktopReleasePackage{}, errors.New("运行时清单中的 SHA-256 无效")
		}
		if _, err := hex.DecodeString(hash); err != nil {
			return desktopReleasePackage{}, errors.New("运行时清单中的 SHA-256 无效")
		}
		return item, nil
	}
	return desktopReleasePackage{}, fmt.Errorf("当前 Release 不包含运行时包 %s", packageName)
}

func downloadDesktopRuntimeArchive(
	ctx context.Context,
	client *http.Client,
	rawURL string,
	target string,
	releasePackage desktopReleasePackage,
	maxBytes int64,
) error {
	if err := validateDesktopReleaseURL(rawURL); err != nil {
		return err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return err
	}
	request.Header.Set("User-Agent", "Murong-Desktop-Agent/"+desktopAgentVersion)
	response, err := client.Do(request)
	if err != nil {
		return fmt.Errorf("下载运行时包失败：%w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("下载运行时包失败：GitHub 返回 HTTP %d", response.StatusCode)
	}
	if response.ContentLength > maxBytes {
		return errors.New("运行时包超过安全大小限制")
	}
	file, err := os.OpenFile(target, os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	hasher := sha256.New()
	written, copyErr := io.Copy(io.MultiWriter(file, hasher), io.LimitReader(response.Body, maxBytes+1))
	closeErr := file.Close()
	if copyErr != nil {
		return copyErr
	}
	if closeErr != nil {
		return closeErr
	}
	if written > maxBytes || written != releasePackage.Size {
		return errors.New("运行时包下载大小与发布清单不一致")
	}
	actual := hex.EncodeToString(hasher.Sum(nil))
	if !strings.EqualFold(actual, releasePackage.SHA256) {
		return errors.New("运行时包 SHA-256 校验失败")
	}
	return nil
}

func extractDesktopRuntimeArchive(
	archivePath string,
	destination string,
	stripPrefix string,
	maxFiles int,
	maxBytes int64,
) error {
	file, err := os.Open(archivePath)
	if err != nil {
		return err
	}
	defer file.Close()
	gzipReader, err := gzip.NewReader(file)
	if err != nil {
		return err
	}
	defer gzipReader.Close()
	reader := tar.NewReader(gzipReader)
	normalizedPrefix := strings.Trim(filepath.ToSlash(stripPrefix), "/")
	var total int64
	files := 0
	for {
		header, nextErr := reader.Next()
		if errors.Is(nextErr, io.EOF) {
			break
		}
		if nextErr != nil {
			return nextErr
		}
		name := filepath.ToSlash(header.Name)
		if normalizedPrefix != "" {
			if name == normalizedPrefix || name == normalizedPrefix+"/" {
				continue
			}
			prefix := normalizedPrefix + "/"
			if !strings.HasPrefix(name, prefix) {
				continue
			}
			name = strings.TrimPrefix(name, prefix)
		}
		clean := filepath.Clean(filepath.FromSlash(name))
		if clean == "." {
			continue
		}
		if filepath.IsAbs(clean) || clean == ".." ||
			strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
			return errors.New("运行时包包含越界路径")
		}
		target := filepath.Join(destination, clean)
		switch header.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, 0o700); err != nil {
				return err
			}
		case tar.TypeReg, tar.TypeRegA:
			files++
			total += header.Size
			if files > maxFiles || header.Size < 0 || total > maxBytes {
				return errors.New("运行时包超过解压安全限制")
			}
			if err := os.MkdirAll(filepath.Dir(target), 0o700); err != nil {
				return err
			}
			mode := os.FileMode(header.Mode).Perm()
			if mode == 0 {
				mode = 0o600
			}
			output, err := os.OpenFile(target, os.O_CREATE|os.O_EXCL|os.O_WRONLY, mode)
			if err != nil {
				return err
			}
			written, copyErr := io.CopyN(output, reader, header.Size)
			closeErr := output.Close()
			if copyErr != nil || written != header.Size {
				return errors.New("运行时包文件读取不完整")
			}
			if closeErr != nil {
				return closeErr
			}
		default:
			return errors.New("运行时包包含不支持的链接或设备文件")
		}
	}
	if files == 0 {
		return errors.New("运行时包为空")
	}
	return nil
}

func replaceDesktopRuntimeDirectory(parent, target, replacement string) error {
	absoluteParent, err := filepath.Abs(parent)
	if err != nil {
		return err
	}
	absoluteTarget, err := filepath.Abs(target)
	if err != nil {
		return err
	}
	relative, err := filepath.Rel(absoluteParent, absoluteTarget)
	if err != nil || relative == "." || relative == ".." ||
		strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return errors.New("拒绝替换越界的运行时目录")
	}
	if err := os.RemoveAll(absoluteTarget); err != nil {
		return err
	}
	if err := os.Rename(replacement, absoluteTarget); err != nil {
		return fmt.Errorf("启用运行时包失败：%w", err)
	}
	return nil
}

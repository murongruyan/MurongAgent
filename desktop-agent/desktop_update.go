package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/wailsapp/wails/v2/pkg/runtime"
)

const (
	desktopLatestReleaseAPI = "https://api.github.com/repos/murongruyan/MurongAgent/releases/latest"
	desktopReleaseHost      = "github.com"
	desktopReleasePath      = "/murongruyan/MurongAgent/releases/"
)

var desktopReleaseVersionPattern = regexp.MustCompile(`(?m)^#{1,3}\s*(?:MurongAgent\s+)?v?([0-9]+(?:\.[0-9]+){1,3})\b`)

type DesktopUpdateInfo struct {
	CurrentVersion  string `json:"currentVersion"`
	LatestVersion   string `json:"latestVersion,omitempty"`
	UpdateAvailable bool   `json:"updateAvailable"`
	ReleaseURL      string `json:"releaseUrl,omitempty"`
	DownloadURL     string `json:"downloadUrl,omitempty"`
	PackageName     string `json:"packageName,omitempty"`
	PublishedAt     string `json:"publishedAt,omitempty"`
}

type githubReleaseAsset struct {
	Name               string `json:"name"`
	BrowserDownloadURL string `json:"browser_download_url"`
}

type githubLatestRelease struct {
	HTMLURL     string               `json:"html_url"`
	TagName     string               `json:"tag_name"`
	Body        string               `json:"body"`
	PublishedAt string               `json:"published_at"`
	Assets      []githubReleaseAsset `json:"assets"`
}

type desktopReleaseManifest struct {
	Versions struct {
		DesktopAgent struct {
			Name string `json:"name"`
		} `json:"desktopAgent"`
	} `json:"versions"`
	Packages []desktopReleasePackage `json:"packages"`
}

func (app *DesktopAgentApp) CheckDesktopUpdate() (DesktopUpdateInfo, error) {
	parent := app.ctx
	if parent == nil {
		parent = context.Background()
	}
	ctx, cancel := context.WithTimeout(parent, 15*time.Second)
	defer cancel()
	return checkDesktopUpdate(ctx, http.DefaultClient, currentDesktopPlatformInfo())
}

func (app *DesktopAgentApp) OpenDesktopUpdateDownload() error {
	info, err := app.CheckDesktopUpdate()
	if err != nil {
		return err
	}
	target := info.DownloadURL
	if target == "" {
		target = info.ReleaseURL
	}
	if target == "" {
		return errors.New("当前发布页没有适用于这台电脑的安装包")
	}
	if err := validateDesktopReleaseURL(target); err != nil {
		return err
	}
	if app.ctx == nil {
		return errors.New("窗口尚未就绪")
	}
	runtime.BrowserOpenURL(app.ctx, target)
	return nil
}

func checkDesktopUpdate(ctx context.Context, client *http.Client, platform DesktopPlatformInfo) (DesktopUpdateInfo, error) {
	info := DesktopUpdateInfo{CurrentVersion: desktopAgentVersion}
	release, err := fetchDesktopLatestRelease(ctx, client)
	if err != nil {
		return info, err
	}
	if err := validateDesktopReleaseURL(release.HTMLURL); err != nil {
		return info, fmt.Errorf("发布页地址无效: %w", err)
	}
	info.ReleaseURL = release.HTMLURL
	info.PublishedAt = release.PublishedAt
	if asset, ok := selectDesktopReleaseAsset(release.Assets, platform.OS, platform.Architecture); ok {
		if err := validateDesktopReleaseURL(asset.BrowserDownloadURL); err == nil {
			info.DownloadURL = asset.BrowserDownloadURL
			info.PackageName = asset.Name
		}
	}
	info.LatestVersion = releaseVersionFromText(release.Body)
	if manifestAsset, ok := findReleaseAsset(release.Assets, "release-manifest.json"); ok {
		if manifestVersion, manifestErr := fetchDesktopManifestVersion(ctx, client, manifestAsset.BrowserDownloadURL); manifestErr == nil && manifestVersion != "" {
			info.LatestVersion = manifestVersion
		}
	}
	if info.LatestVersion == "" {
		info.LatestVersion = releaseVersionFromText(release.TagName)
	}
	if info.LatestVersion == "" {
		return info, errors.New("最新发布未声明电脑版版本")
	}
	info.UpdateAvailable = compareDesktopVersions(info.LatestVersion, info.CurrentVersion) > 0
	return info, nil
}

func fetchDesktopLatestRelease(ctx context.Context, client *http.Client) (githubLatestRelease, error) {
	var release githubLatestRelease
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, desktopLatestReleaseAPI, nil)
	if err != nil {
		return release, err
	}
	request.Header.Set("Accept", "application/vnd.github+json")
	request.Header.Set("User-Agent", "Murong-Desktop-Agent/"+desktopAgentVersion)
	response, err := client.Do(request)
	if err != nil {
		return release, fmt.Errorf("检查更新失败: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 64<<10))
		return release, fmt.Errorf("检查更新失败: GitHub 返回 HTTP %d", response.StatusCode)
	}
	decoder := json.NewDecoder(io.LimitReader(response.Body, 2<<20))
	if err := decoder.Decode(&release); err != nil {
		return release, fmt.Errorf("解析更新信息失败: %w", err)
	}
	return release, nil
}

func fetchDesktopManifestVersion(ctx context.Context, client *http.Client, rawURL string) (string, error) {
	if err := validateDesktopReleaseURL(rawURL); err != nil {
		return "", err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return "", err
	}
	request.Header.Set("User-Agent", "Murong-Desktop-Agent/"+desktopAgentVersion)
	response, err := client.Do(request)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return "", fmt.Errorf("更新清单返回 HTTP %d", response.StatusCode)
	}
	var manifest desktopReleaseManifest
	if err := json.NewDecoder(io.LimitReader(response.Body, 1<<20)).Decode(&manifest); err != nil {
		return "", err
	}
	return strings.TrimSpace(manifest.Versions.DesktopAgent.Name), nil
}

func selectDesktopReleaseAsset(assets []githubReleaseAsset, goos string, goarch string) (githubReleaseAsset, bool) {
	prefix := "murong-desktop-agent-" + strings.ToLower(strings.TrimSpace(goos)) + "-" + strings.ToLower(strings.TrimSpace(goarch))
	for _, asset := range assets {
		name := strings.ToLower(strings.TrimSpace(asset.Name))
		if name == prefix+".exe" || name == prefix+".app.zip" || name == prefix+".tar.gz" {
			return asset, true
		}
	}
	return githubReleaseAsset{}, false
}

func findReleaseAsset(assets []githubReleaseAsset, name string) (githubReleaseAsset, bool) {
	for _, asset := range assets {
		if strings.EqualFold(strings.TrimSpace(asset.Name), name) {
			return asset, true
		}
	}
	return githubReleaseAsset{}, false
}

func releaseVersionFromText(value string) string {
	match := desktopReleaseVersionPattern.FindStringSubmatch(strings.TrimSpace(value))
	if len(match) == 2 {
		return match[1]
	}
	tag := strings.TrimSpace(value)
	tag = strings.TrimPrefix(strings.ToLower(tag), "v")
	if regexp.MustCompile(`^[0-9]+(?:\.[0-9]+){1,3}$`).MatchString(tag) {
		return tag
	}
	return ""
}

func compareDesktopVersions(left string, right string) int {
	leftParts := numericVersionParts(left)
	rightParts := numericVersionParts(right)
	count := len(leftParts)
	if len(rightParts) > count {
		count = len(rightParts)
	}
	for index := 0; index < count; index++ {
		leftValue, rightValue := 0, 0
		if index < len(leftParts) {
			leftValue = leftParts[index]
		}
		if index < len(rightParts) {
			rightValue = rightParts[index]
		}
		if leftValue < rightValue {
			return -1
		}
		if leftValue > rightValue {
			return 1
		}
	}
	return 0
}

func numericVersionParts(value string) []int {
	value = strings.TrimPrefix(strings.TrimSpace(strings.ToLower(value)), "v")
	rawParts := strings.Split(value, ".")
	parts := make([]int, 0, len(rawParts))
	for _, rawPart := range rawParts {
		part, err := strconv.Atoi(rawPart)
		if err != nil || part < 0 {
			return nil
		}
		parts = append(parts, part)
	}
	return parts
}

func validateDesktopReleaseURL(rawURL string) error {
	parsed, err := url.Parse(strings.TrimSpace(rawURL))
	if err != nil || parsed.Scheme != "https" || parsed.User != nil {
		return errors.New("只允许打开 GitHub HTTPS 地址")
	}
	host := strings.ToLower(parsed.Hostname())
	if host != desktopReleaseHost ||
		!strings.HasPrefix(strings.ToLower(parsed.EscapedPath()), strings.ToLower(desktopReleasePath)) {
		return errors.New("更新地址不属于 GitHub")
	}
	return nil
}

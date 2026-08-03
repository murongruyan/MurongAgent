package main

import (
	"context"
	"io"
	"net/http"
	"strings"
	"testing"
)

func TestCompareDesktopVersions(t *testing.T) {
	for _, test := range []struct {
		left  string
		right string
		want  int
	}{
		{left: "1.34", right: "1.33", want: 1},
		{left: "1.33.0", right: "1.33", want: 0},
		{left: "1.32", right: "1.33", want: -1},
		{left: "v2.0", right: "1.99", want: 1},
	} {
		if got := compareDesktopVersions(test.left, test.right); got != test.want {
			t.Fatalf("compareDesktopVersions(%q, %q) = %d, want %d", test.left, test.right, got, test.want)
		}
	}
}

func TestSelectDesktopReleaseAsset(t *testing.T) {
	assets := []githubReleaseAsset{
		{Name: "murong-desktop-agent-windows-amd64.exe", BrowserDownloadURL: "https://github.com/example/windows"},
		{Name: "murong-desktop-agent-linux-arm64.tar.gz", BrowserDownloadURL: "https://github.com/example/linux"},
	}
	asset, ok := selectDesktopReleaseAsset(assets, "windows", "amd64")
	if !ok || asset.Name != "murong-desktop-agent-windows-amd64.exe" {
		t.Fatalf("unexpected asset: %#v, found=%v", asset, ok)
	}
	if _, ok := selectDesktopReleaseAsset(assets, "darwin", "arm64"); ok {
		t.Fatal("selected an asset for an unavailable platform")
	}
}

func TestCheckDesktopUpdatePrefersManifestVersion(t *testing.T) {
	client := &http.Client{Transport: roundTripFunc(func(request *http.Request) (*http.Response, error) {
		body := ""
		switch {
		case strings.HasSuffix(request.URL.Path, "/releases/latest"):
			body = `{
				"html_url":"https://github.com/murongruyan/MurongAgent/releases/tag/murong-suite-v11",
				"tag_name":"murong-suite-v11",
				"body":"# MurongAgent 1.32",
				"published_at":"2026-07-28T00:00:00Z",
				"assets":[
					{"name":"release-manifest.json","browser_download_url":"https://github.com/murongruyan/MurongAgent/releases/download/murong-suite-v11/release-manifest.json"},
					{"name":"murong-desktop-agent-windows-amd64.exe","browser_download_url":"https://github.com/murongruyan/MurongAgent/releases/download/murong-suite-v11/murong-desktop-agent-windows-amd64.exe"}
				]
			}`
		case strings.HasSuffix(request.URL.Path, "/release-manifest.json"):
			body = `{"versions":{"desktopAgent":{"name":"1.38"}}}`
		default:
			return &http.Response{
				StatusCode: http.StatusNotFound,
				Header:     make(http.Header),
				Body:       io.NopCloser(strings.NewReader("not found")),
				Request:    request,
			}, nil
		}
		return &http.Response{
			StatusCode: http.StatusOK,
			Header:     http.Header{"Content-Type": []string{"application/json"}},
			Body:       io.NopCloser(strings.NewReader(body)),
			Request:    request,
		}, nil
	})}
	info, err := checkDesktopUpdate(
		context.Background(),
		client,
		DesktopPlatformInfo{OS: "windows", Architecture: "amd64"},
	)
	if err != nil {
		t.Fatal(err)
	}
	if info.LatestVersion != "1.38" || !info.UpdateAvailable {
		t.Fatalf("unexpected update info: %#v", info)
	}
	if info.PackageName != "murong-desktop-agent-windows-amd64.exe" {
		t.Fatalf("desktop package = %q", info.PackageName)
	}
}

func TestValidateDesktopReleaseURLRejectsUntrustedHost(t *testing.T) {
	if err := validateDesktopReleaseURL("https://github.com/murongruyan/MurongAgent/releases/latest"); err != nil {
		t.Fatal(err)
	}
	if err := validateDesktopReleaseURL("https://evil.example/update.exe"); err == nil {
		t.Fatal("untrusted update host was accepted")
	}
	if err := validateDesktopReleaseURL("https://github.com/another/project/releases/download/v1/update.exe"); err == nil {
		t.Fatal("another GitHub repository was accepted as an update source")
	}
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (function roundTripFunc) RoundTrip(request *http.Request) (*http.Response, error) {
	return function(request)
}

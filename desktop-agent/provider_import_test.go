package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"testing"
)

const providerImportTestUsageScript = `({
  request: {
    url: "{{baseUrl}}/v1/usage",
    method: "GET",
    headers: { "Authorization": "Bearer {{apiKey}}" }
  },
  extractor: function(response) {
    const remaining = response?.remaining ?? response?.quota?.remaining ?? response?.balance;
    const unit = response?.unit ?? response?.quota?.unit ?? "USD";
    return { isValid: response?.is_active ?? response?.isValid ?? true, remaining, unit };
  }
})`

func TestParseProviderImportDeepLinkConvertsSupportedUsageRule(t *testing.T) {
	payload, err := parseProviderImportDeepLink(providerImportTestLink(providerImportTestUsageScript, "https://api.example.com"))
	if err != nil {
		t.Fatal(err)
	}
	if payload.App != "codex" || payload.Name != "Test relay" || payload.APIKey != "sk-secret-value" {
		t.Fatalf("unexpected payload: %#v", payload)
	}
	if payload.UsageRule == nil || payload.UsageRule.Endpoint != "https://api.example.com/v1/usage" || payload.UsageRule.IntervalMins != 30 {
		t.Fatalf("unexpected usage rule: %#v", payload.UsageRule)
	}
	preview := providerImportPreview("request-1", payload)
	if strings.Contains(preview.MaskedAPIKey, "secret-value") || preview.MaskedAPIKey == payload.APIKey {
		t.Fatalf("preview leaked key: %q", preview.MaskedAPIKey)
	}
}

func TestParseProviderImportDeepLinkRejectsUnsafeInputs(t *testing.T) {
	for name, link := range map[string]string{
		"duplicate": providerImportTestLink("", "https://api.example.com") + "&apiKey=other",
		"http":      providerImportTestLink("", "http://api.example.com"),
		"long name": providerImportTestLink("", "https://api.example.com") + "&name=" + strings.Repeat("x", 101),
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := parseProviderImportDeepLink(link); err == nil {
				t.Fatal("expected rejection")
			}
		})
	}
}

func TestParseProviderImportDeepLinkDoesNotConvertUnsafeScript(t *testing.T) {
	for name, script := range map[string]string{
		"post":         strings.Replace(providerImportTestUsageScript, `method: "GET"`, `method: "POST"`, 1),
		"cross origin": strings.Replace(providerImportTestUsageScript, `{{baseUrl}}/v1/usage`, `https://evil.example/v1/usage`, 1),
	} {
		t.Run(name, func(t *testing.T) {
			payload, err := parseProviderImportDeepLink(providerImportTestLink(script, "https://api.example.com"))
			if err != nil {
				t.Fatal(err)
			}
			if payload.UsageRule != nil {
				t.Fatalf("unsafe script converted: %#v", payload.UsageRule)
			}
		})
	}
}

func TestProviderUsageRefreshUsesBearerAndRejectsInactiveKey(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	active := true
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/v1/usage" || request.Header.Get("Authorization") != "Bearer sk-balance-secret" {
			http.Error(response, "unexpected request", http.StatusUnauthorized)
			return
		}
		response.Header().Set("Content-Type", "application/json")
		if active {
			_, _ = response.Write([]byte(`{"is_active":true,"quota":{"remaining":"12.75","unit":"usd"}}`))
		} else {
			_, _ = response.Write([]byte(`{"is_active":false,"remaining":12.75,"unit":"usd"}`))
		}
	}))
	defer server.Close()
	payload := providerImportPayload{
		App: "codex", Name: "Balance relay", Endpoints: []string{server.URL}, APIKey: "sk-balance-secret", Model: "gpt-test",
	}
	_, profileID, err := store.importProviderProfile(payload, true)
	if err != nil {
		t.Fatal(err)
	}
	manager, err := newDesktopProviderUsageManager(filepath.Join(t.TempDir(), "usage.json"), store)
	if err != nil {
		t.Fatal(err)
	}
	if err := manager.Configure(profileID, &providerImportUsageRule{Endpoint: server.URL + "/v1/usage", IntervalMins: 30}, true); err != nil {
		t.Fatal(err)
	}
	if err := manager.Refresh(context.Background(), profileID); err != nil {
		t.Fatal(err)
	}
	status := manager.State().Items[0]
	if status.Remaining == nil || *status.Remaining != 12.75 || status.Unit != "USD" || status.LastSyncedAt == 0 {
		t.Fatalf("unexpected balance status: %#v", status)
	}
	active = false
	if err := manager.Refresh(context.Background(), profileID); err == nil || !strings.Contains(err.Error(), "已停用") {
		t.Fatalf("inactive API key response was accepted: %v", err)
	}
}

func TestImportedProviderAppPresetsSelectMatchingProtocol(t *testing.T) {
	for app, expected := range map[string]string{
		"codex": providerOpenAI, "opencode": providerOpenAI, "claude": providerClaude, "gemini": providerGemini,
	} {
		if actual := providerIDForImportedApp(app); actual != expected {
			t.Fatalf("app %q selected provider %q, want %q", app, actual, expected)
		}
	}
	if importedProviderAPIMode(providerClaude) != "messages" || importedProviderAPIMode(providerGemini) != "gemini" {
		t.Fatal("imported provider presets lost their native API protocol")
	}
}

func providerImportTestLink(script, endpoint string) string {
	values := url.Values{
		"resource": {"provider"}, "app": {"codex"}, "name": {"Test relay"},
		"homepage": {"https://example.com"}, "endpoint": {endpoint}, "apiKey": {"sk-secret-value"},
		"model": {"gpt-test"}, "usageEnabled": {"true"}, "usageAutoInterval": {"30"},
	}
	if script != "" {
		values.Set("usageScript", script)
	}
	return "ccswitch://v1/import?" + values.Encode()
}

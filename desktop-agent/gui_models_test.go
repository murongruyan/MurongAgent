package main

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestLocalModelBaseURLDetection(t *testing.T) {
	local := []string{
		"http://127.0.0.1:11434/v1",
		"http://localhost:1234/v1",
		"http://10.0.2.2:8080/v1",
		"http://192.168.50.20:8000/v1",
		"http://desktop.local:8000/v1",
		"http://[::1]:8000/v1",
	}
	for _, value := range local {
		if !isLocalModelBaseURL(value) {
			t.Fatalf("expected local model URL: %s", value)
		}
	}
	for _, value := range []string{"https://api.openai.com/v1", "https://fd-public.example/v1", "file:///tmp/model", ""} {
		if isLocalModelBaseURL(value) {
			t.Fatalf("expected non-local model URL: %s", value)
		}
	}
}

func TestGUIConfigMigrationUsesPrivateLocalFirstDefaults(t *testing.T) {
	legacyTools := []string{
		"shell", "file", "code_edit", "code_search", "web_fetch", "web_search",
		"subagent_launch", "explore", "research", "review", "security_review", "github", "mcp",
	}
	config := normalizeDesktopConfig(desktopConfig{
		SchemaVersion:       10,
		BaseURL:             "https://api.openai.com/v1",
		Model:               "gpt-test",
		ApprovalMode:        approvalAskAll,
		EnabledBuiltinTools: legacyTools,
	})

	if config.GuiInferenceMode != guiInferenceLocalFirst {
		t.Fatalf("unexpected GUI inference mode: %q", config.GuiInferenceMode)
	}
	if config.GuiLocalBaseURL != "" {
		t.Fatalf("unexpected local GUI URL: %q", config.GuiLocalBaseURL)
	}
	if config.GuiAllowRemoteSemantic || config.GuiAllowRemoteScreenshots || config.GuiAllowRemoteFullScreen {
		t.Fatalf("unexpected GUI privacy defaults: %#v", config)
	}
	if !containsGUIString(config.EnabledBuiltinTools, "gui") {
		t.Fatalf("legacy default tools did not gain GUI support: %#v", config.EnabledBuiltinTools)
	}
}

func TestGUIToolDefinitionAndCropDetection(t *testing.T) {
	if name := functionToolName(guiToolDefinition()); name != "gui" {
		t.Fatalf("unexpected tool name: %q", name)
	}
	if guiHasCrop(map[string]json.RawMessage{}) {
		t.Fatal("empty GUI request must be full-screen")
	}
	if guiHasCrop(map[string]json.RawMessage{"cropLeft": json.RawMessage("0")}) {
		t.Fatal("partial crop must not bypass full-screen privacy")
	}
	if !guiHasCrop(map[string]json.RawMessage{
		"cropLeft": json.RawMessage("0"), "cropTop": json.RawMessage("0"),
		"cropRight": json.RawMessage("100"), "cropBottom": json.RawMessage("100"),
	}) {
		t.Fatal("complete crop rectangle was not detected")
	}
}

func TestLocalVisionResultRedactionKeepsCoordinatesOnly(t *testing.T) {
	result := sanitizeLocalGUIVisionResult(
		`{"summary":"secret account balance","targetFound":true,"x":120,"y":340,"confidence":0.9,"reason":"OCR secret"}`,
	)
	if strings.Contains(result, "secret") || strings.Contains(result, "balance") {
		t.Fatalf("sensitive local vision text leaked into remote result: %s", result)
	}
	for _, marker := range []string{`"targetFound":true`, `"x":120`, `"y":340`, `"privacyRedacted":true`} {
		if !strings.Contains(result, marker) {
			t.Fatalf("redacted result is missing %q: %s", marker, result)
		}
	}
}

func TestCroppedVisionCoordinatesAreTranslatedToScreen(t *testing.T) {
	adjusted := adjustGUIVisionCoordinates(
		`{"targetFound":true,"x":20,"y":30}`,
		guiScreenshot{OriginX: 100, OriginY: 200},
	)
	for _, marker := range []string{`"x":120`, `"y":230`, `"coordinates":"screen"`} {
		if !strings.Contains(adjusted, marker) {
			t.Fatalf("translated result is missing %q: %s", marker, adjusted)
		}
	}
}

func containsGUIString(values []string, wanted string) bool {
	for _, value := range values {
		if value == wanted {
			return true
		}
	}
	return false
}

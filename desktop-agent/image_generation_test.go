package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

const imageGenerationTestPNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9p0nEAAAAASUVORK5CYII="

func TestImageGenerationsEndpointNormalizesChatAndRootURLs(t *testing.T) {
	cases := map[string]string{
		"https://api.openai.com/v1":                  "https://api.openai.com/v1/images/generations",
		"https://api.openai.com/v1/chat/completions": "https://api.openai.com/v1/images/generations",
		"https://provider.example/api":               "https://provider.example/api/v1/images/generations",
		"https://provider.example/v1/responses":      "https://provider.example/v1/images/generations",
	}
	for input, want := range cases {
		if got := imageGenerationsEndpoint(input); got != want {
			t.Fatalf("imageGenerationsEndpoint(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestNormalizeImageGenerationSizeAcceptsNative4KAndRejectsInvalidGeometry(t *testing.T) {
	cases := map[string]string{
		"3840x2160":  "3840x2160",
		"2160x3840":  "2160x3840",
		"2048x2048":  "2048x2048",
		"3841x2160":  "1024x1024",
		"1024x4096":  "1024x1024",
		"1024x1024 ": "1024x1024",
		"256x256":    "1024x1024", // below the official minimum total pixel count
		"3840x1264":  "1024x1024", // exceeds the 3:1 aspect-ratio limit
	}
	for input, want := range cases {
		if got := normalizeImageGenerationSize(input); got != want {
			t.Fatalf("normalizeImageGenerationSize(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestGenerateOpenAIImageStreamsPartialImage(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/v1/images/generations" {
			t.Fatalf("unexpected request path %s", request.URL.Path)
		}
		if request.Header.Get("Authorization") != "Bearer test-key" {
			t.Fatal("missing bearer authorization")
		}
		body, _ := io.ReadAll(request.Body)
		if !strings.Contains(string(body), `"stream":true`) || !strings.Contains(string(body), `"partial_images":2`) {
			t.Fatalf("streaming body is incomplete: %s", body)
		}
		if strings.Contains(string(body), `"output_compression"`) {
			t.Fatalf("PNG generation must not send output compression: %s", body)
		}
		writer.Header().Set("Content-Type", "text/event-stream")
		_, _ = writer.Write([]byte("data: {\"type\":\"image_generation.partial_image\",\"partial_image_b64\":\"" + imageGenerationTestPNG + "\"}\n\ndata: [DONE]\n\n"))
	}))
	defer server.Close()

	previewCount := 0
	images, err := generateOpenAIImage(context.Background(), server.Client(), desktopConfig{
		ImageGenerationSize: "1024x1024", ImageGenerationQuality: "auto", ImageGenerationFormat: "png", ImageGenerationPartialImages: 2,
	}, ProviderProfile{ProviderID: providerOpenAI, BaseURL: server.URL, Model: "gpt-image-2"}, "test-key", "a pink square", func(data []byte) {
		previewCount++
	})
	if err != nil {
		t.Fatalf("generateOpenAIImage() error = %v", err)
	}
	if previewCount != 1 || len(images) != 1 {
		t.Fatalf("previewCount=%d images=%d, want one", previewCount, len(images))
	}
	want, _ := base64.StdEncoding.DecodeString(imageGenerationTestPNG)
	if !bytes.Equal(images[0], want) {
		t.Fatal("streamed image payload differs")
	}
}

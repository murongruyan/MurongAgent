package main

import (
	"bytes"
	"context"
	"encoding/json"
	"image"
	"image/color"
	"image/png"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestReplicateUpscaleImageCompletesOnlyWith4KOutput(t *testing.T) {
	output := solidPNG(t, 3840, 2160)
	var server *httptest.Server
	server = httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.URL.Path == "/v1/models/nightmareai/real-esrgan/predictions" {
			if request.Method != http.MethodPost || request.Header.Get("Authorization") != "Token replicate-test" {
				t.Fatalf("unexpected submit request: %s %q", request.Method, request.Header.Get("Authorization"))
			}
			var payload struct {
				Input struct {
					Image string `json:"image"`
					Scale int    `json:"scale"`
				} `json:"input"`
			}
			if err := json.NewDecoder(request.Body).Decode(&payload); err != nil {
				t.Fatal(err)
			}
			if payload.Input.Scale != 4 || !strings.HasPrefix(payload.Input.Image, "data:image/png;base64,") {
				t.Fatalf("unexpected input: scale=%d image=%q", payload.Input.Scale, payload.Input.Image[:min(len(payload.Input.Image), 32)])
			}
			_ = json.NewEncoder(writer).Encode(map[string]any{
				"id": "prediction-1", "status": "starting", "urls": map[string]string{"get": server.URL + "/predictions/prediction-1"},
			})
			return
		}
		if request.URL.Path == "/predictions/prediction-1" {
			_ = json.NewEncoder(writer).Encode(map[string]any{"id": "prediction-1", "status": "succeeded", "output": server.URL + "/output.png"})
			return
		}
		if request.URL.Path == "/output.png" {
			writer.Header().Set("Content-Type", "image/png")
			_, _ = writer.Write(output)
			return
		}
		http.NotFound(writer, request)
	}))
	defer server.Close()

	input := solidPNG(t, 960, 540)
	result, width, height, err := replicateUpscaleImage(
		context.Background(), server.Client(), desktopConfig{
			ImageUpscaleBaseURL: server.URL, ImageUpscaleModel: defaultImageUpscaleModel, ImageUpscaleScale: 4,
		}, "replicate-test", "image/png", input, nil,
	)
	if err != nil {
		t.Fatal(err)
	}
	if width != 3840 || height != 2160 || !bytes.Equal(result, output) {
		t.Fatalf("unexpected result: %dx%d, %d bytes", width, height, len(result))
	}
}

func TestImportUpscaledChatImageRejectsNon4K(t *testing.T) {
	_, err := importUpscaledChatImage(t.TempDir(), solidPNG(t, 1920, 1080))
	if err == nil || !strings.Contains(err.Error(), "未达到 4K") {
		t.Fatalf("expected 4K validation error, got %v", err)
	}
}

func solidPNG(t *testing.T, width, height int) []byte {
	t.Helper()
	value := image.NewNRGBA(image.Rect(0, 0, width, height))
	for y := 0; y < height; y++ {
		for x := 0; x < width; x++ {
			value.SetNRGBA(x, y, color.NRGBA{R: 218, G: 82, B: 143, A: 255})
		}
	}
	var output bytes.Buffer
	if err := png.Encode(&output, value); err != nil {
		t.Fatal(err)
	}
	return output.Bytes()
}

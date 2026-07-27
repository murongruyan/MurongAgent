package main

import (
	"bufio"
	"bytes"
	"encoding/base64"
	"image"
	"image/color"
	"image/png"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestBuiltinVisionCatalogOffersQwenGemmaZhipuAndDeepSeek(t *testing.T) {
	if len(builtinVisionDescriptors) != 13 {
		t.Fatalf("expected thirteen selectable built-in models, got %d", len(builtinVisionDescriptors))
	}
	seen := map[string]bool{}
	for _, descriptor := range builtinVisionDescriptors {
		seen[descriptor.Tier] = true
		if descriptor.totalBytes() <= 0 || len(descriptor.Files) == 0 {
			t.Fatalf("%s has no downloadable files", descriptor.DisplayName)
		}
		for _, file := range descriptor.Files {
			if file.Size != 0 || file.SHA256 != "" {
				t.Fatalf("%s/%s must resolve mutable metadata at install time", descriptor.DisplayName, file.Name)
			}
			if url := descriptor.fileURL(file); !strings.HasPrefix(url, "https://") {
				t.Fatalf("%s must download over HTTPS: %s", descriptor.DisplayName, url)
			}
		}
	}
	for _, tier := range []string{
		builtinVisionGemma12B,
		builtinVisionQwen9B,
		builtinVisionCoder7B,
		builtinVisionQwen4B,
		builtinVisionQwen2B,
		builtinVisionGemma4B,
		builtinVisionGemma2B,
		builtinVisionGLMEdgeV2B,
		builtinVisionGLMEdge15B,
		builtinVisionDeepSeekR1Qwen7B,
		builtinVisionDeepSeekR1Qwen15B,
		builtinVisionDeepSeekR1Llama8B,
		builtinVisionDeepSeekCoderV2Lite,
	} {
		if !seen[tier] {
			t.Fatalf("missing selectable model tier %q", tier)
		}
	}
}

func TestResolveRequiredModelFilesUsesOfficialManifestMetadata(t *testing.T) {
	required := []builtinVisionModelFile{{Name: "config.json"}}
	available := map[string]builtinVisionModelFile{
		"config.json": {
			Name: "config.json", Size: 652,
			SHA256: "92853033efe602f95efca3e1c05cd8b108f973c8beed417843a9671f8147ed8d",
		},
	}
	files, err := resolveRequiredModelFiles(required, available)
	if err != nil {
		t.Fatal(err)
	}
	if len(files) != 1 || files[0].Size != 652 || files[0].SHA256 == "" {
		t.Fatalf("unexpected resolved metadata: %#v", files)
	}
}

func TestVisionProtocolReadsStreamingChunkEvent(t *testing.T) {
	reader := bufio.NewReader(strings.NewReader(
		"MURONG_VLM_CHUNK_BEGIN 6\n你好\nMURONG_VLM_PAYLOAD_END\n",
	))
	event, payload, err := readVisionProtocolEvent(reader)
	if err != nil {
		t.Fatalf("read streaming event: %v", err)
	}
	if event != "chunk" || payload != "你好" {
		t.Fatalf("unexpected streaming event: %q %q", event, payload)
	}
}

func TestBuiltinVisionManagerUsesPrivateSiblingStorage(t *testing.T) {
	configPath := filepath.Join(t.TempDir(), "settings", "desktop-agent.json")
	manager := newBuiltinVisionManager(configPath, nil)
	defer manager.Close()
	expected := filepath.Join(filepath.Dir(configPath), "vision-models", builtinVisionPackageVersion)
	if manager.root != expected {
		t.Fatalf("unexpected model root: %q, want %q", manager.root, expected)
	}
	if status := manager.Status(); len(status.Models) != 13 || status.InstallingTier != "" {
		t.Fatalf("unexpected initial status: %#v", status)
	}
}

func TestDesktopMNNRuntimeStartsFromLaunchConfig(t *testing.T) {
	if visionMNNConfigFileName != "config.json" {
		t.Fatalf("MNN must start from config.json, got %q", visionMNNConfigFileName)
	}
}

func TestDesktopMNNCacheFingerprintTracksRuntimeRelevantFiles(t *testing.T) {
	modelDir := t.TempDir()
	for name, content := range map[string]string{
		"config.json":     `{"backend_type":"cpu"}`,
		"llm_config.json": `{"model_type":"qwen3_5"}`,
		"llm.mnn":         "model-v1",
	} {
		if err := os.WriteFile(filepath.Join(modelDir, name), []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	initial, err := desktopMNNCacheFingerprint(modelDir)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(
		filepath.Join(modelDir, "config.json"),
		[]byte(`{"backend_type":"opencl"}`),
		0o600,
	); err != nil {
		t.Fatal(err)
	}
	changed, err := desktopMNNCacheFingerprint(modelDir)
	if err != nil {
		t.Fatal(err)
	}
	if len(initial) != 20 || initial == changed {
		t.Fatalf("cache fingerprint did not track config change: %q -> %q", initial, changed)
	}

	runtime := &desktopVisionRuntime{dataRoot: t.TempDir()}
	cacheDir, err := runtime.prepareMNNCache(modelDir, builtinVisionDescriptor{Tier: builtinVisionQwen9B})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(cacheDir, visionMNNCacheSchema) || !strings.HasSuffix(cacheDir, changed) {
		t.Fatalf("unexpected fingerprinted cache path: %q", cacheDir)
	}
}

func TestVisionProtocolReadsReasoningChunkEvent(t *testing.T) {
	reader := bufio.NewReader(strings.NewReader(
		"MURONG_VLM_REASONING_CHUNK_BEGIN 6\n思考\nMURONG_VLM_PAYLOAD_END\n",
	))
	event, payload, err := readVisionProtocolEvent(reader)
	if err != nil {
		t.Fatalf("read reasoning event: %v", err)
	}
	if event != "reasoning" || payload != "思考" {
		t.Fatalf("unexpected reasoning event: %q %q", event, payload)
	}
}

func TestVisionChunkGuardRejectsCollapsedOutputBeforeStreaming(t *testing.T) {
	guard := visionChunkGuard{repeatedChunkLimit: 4}
	var streamed strings.Builder
	var guardErr error
	for index := 0; index < 4; index++ {
		_, text, err := guard.Accept(visionStreamReasoning, "!")
		streamed.WriteString(text)
		if err != nil {
			guardErr = err
			break
		}
	}
	if guardErr == nil {
		t.Fatal("collapsed output should trip the repetition guard")
	}
	if streamed.Len() != 0 {
		t.Fatalf("collapsed output must not reach the UI: %q", streamed.String())
	}
}

func TestVisionChunkGuardPreservesHealthyStreaming(t *testing.T) {
	guard := visionChunkGuard{}
	var streamed strings.Builder
	for _, chunk := range []string{"你", "好", "！"} {
		_, text, err := guard.Accept(visionStreamContent, chunk)
		if err != nil {
			t.Fatalf("healthy output was rejected: %v", err)
		}
		streamed.WriteString(text)
	}
	_, tail := guard.Finish()
	streamed.WriteString(tail)
	if streamed.String() != "你好！" {
		t.Fatalf("healthy output changed: %q", streamed.String())
	}
}

func TestBuiltinVisionCapabilitiesMatchActualModels(t *testing.T) {
	coder, ok := findBuiltinVisionDescriptor(builtinVisionCoder7B)
	if !ok || coder.SupportsVision || len(coder.ReasoningModes) != 0 {
		t.Fatalf("unexpected coder capabilities: %#v", coder)
	}
	qwen, _ := findBuiltinVisionDescriptor(builtinVisionQwen9B)
	if !qwen.SupportsVision || qwen.resolveReasoningMode("on") != "on" ||
		qwen.resolveReasoningMode("unknown") != "off" {
		t.Fatalf("unexpected Qwen reasoning capabilities: %#v", qwen)
	}
	glmVision, ok := findBuiltinVisionDescriptor(builtinVisionGLMEdgeV2B)
	if !ok || !glmVision.SupportsVision || glmVision.Engine != "llama" ||
		glmVision.llamaModelFileName() != "ggml-model-Q4_K_M.gguf" ||
		glmVision.llamaProjectorFileName() != "mmproj-model-f16.gguf" {
		t.Fatalf("unexpected GLM Edge vision descriptor: %#v", glmVision)
	}
	deepSeekCoder, ok := findBuiltinVisionDescriptor(builtinVisionDeepSeekCoderV2Lite)
	if !ok || deepSeekCoder.SupportsVision || deepSeekCoder.Engine != "llama" ||
		deepSeekCoder.llamaModelFileName() == "" {
		t.Fatalf("unexpected DeepSeek Coder descriptor: %#v", deepSeekCoder)
	}
}

func TestPrepareDesktopVisionImageUsesRawBGRForMNNAndPNGForLiteRT(t *testing.T) {
	source := image.NewNRGBA(image.Rect(0, 0, 2, 1))
	source.Set(0, 0, color.NRGBA{R: 10, G: 20, B: 30, A: 255})
	source.Set(1, 0, color.NRGBA{R: 40, G: 50, B: 60, A: 255})
	var encoded bytes.Buffer
	if err := png.Encode(&encoded, source); err != nil {
		t.Fatal(err)
	}
	payload := base64.StdEncoding.EncodeToString(encoded.Bytes())

	bgr, width, height, err := prepareDesktopVisionImage(payload, "mnn")
	if err != nil {
		t.Fatal(err)
	}
	if width != 2 || height != 1 || !bytes.Equal(bgr, []byte{30, 20, 10, 60, 50, 40}) {
		t.Fatalf("unexpected MNN image: %dx%d %#v", width, height, bgr)
	}
	lite, width, height, err := prepareDesktopVisionImage(payload, "litert")
	if err != nil {
		t.Fatal(err)
	}
	if width != 2 || height != 1 || !bytes.HasPrefix(lite, []byte{0x89, 0x50, 0x4e, 0x47}) {
		t.Fatalf("unexpected LiteRT image: %dx%d %#v", width, height, lite[:min(8, len(lite))])
	}
}

func TestRestoreDesktopVisionCoordinatesMapsScaledImageToScreenCapture(t *testing.T) {
	result := restoreDesktopVisionCoordinates(
		`{"targetFound":true,"x":320,"y":640}`,
		1080,
		2400,
		576,
		1280,
	)
	for _, marker := range []string{`"x":600`, `"y":1200`} {
		if !strings.Contains(result, marker) {
			t.Fatalf("restored result is missing %s: %s", marker, result)
		}
	}
}

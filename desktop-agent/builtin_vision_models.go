package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	builtinVisionPackageVersion = "builtin-vlm-2026-07-v1"
	builtinVisionReadyMarker    = ".murong-ready-" + builtinVisionPackageVersion
	builtinVisionManifest       = ".murong-model-manifest.json"

	builtinVisionGemma12B            = "gemma4-12b-experimental"
	builtinVisionQwen9B              = "qwen35-9b-ultra"
	builtinVisionCoder7B             = "qwen25-coder-7b"
	builtinVisionQwen4B              = "qwen35-4b-pro"
	builtinVisionQwen2B              = "qwen35-2b-lite"
	builtinVisionGemma4B             = "gemma4-e4b"
	builtinVisionGemma2B             = "gemma4-e2b"
	builtinVisionGLMEdgeV2B          = "glm-edge-v-2b"
	builtinVisionGLMEdge15B          = "glm-edge-1.5b-chat"
	builtinVisionDeepSeekR1Qwen7B    = "deepseek-r1-distill-qwen-7b"
	builtinVisionDeepSeekR1Qwen15B   = "deepseek-r1-distill-qwen-1.5b"
	builtinVisionDeepSeekR1Llama8B   = "deepseek-r1-distill-llama-8b"
	builtinVisionDeepSeekCoderV2Lite = "deepseek-coder-v2-lite"
)

type builtinVisionModelFile struct {
	Name   string `json:"name"`
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
}

type BuiltinVisionReasoningMode struct {
	ID          string `json:"id"`
	DisplayName string `json:"displayName"`
}

type builtinVisionDescriptor struct {
	Tier                 string
	DisplayName          string
	Engine               string
	Repository           string
	Hub                  string
	Files                []builtinVisionModelFile
	EstimatedDownload    int64
	MinimumFree          int64
	Recommendation       string
	SupportsVision       bool
	ReasoningModes       []BuiltinVisionReasoningMode
	DefaultReasoningMode string
	Revision             string
}

type builtinVisionInstallManifest struct {
	Version    int                      `json:"version"`
	Repository string                   `json:"repository"`
	Revision   string                   `json:"revision"`
	Files      []builtinVisionModelFile `json:"files"`
}

func (descriptor builtinVisionDescriptor) totalBytes() int64 {
	var total int64
	for _, file := range descriptor.Files {
		total += file.Size
	}
	if total == 0 {
		return descriptor.EstimatedDownload
	}
	return total
}

func (descriptor builtinVisionDescriptor) fileURL(file builtinVisionModelFile) string {
	if descriptor.Hub == "huggingface" {
		revision := descriptor.Revision
		if revision == "" {
			revision = "main"
		}
		return "https://huggingface.co/" + descriptor.Repository +
			"/resolve/" + url.PathEscape(revision) + "/" + escapeModelPath(file.Name) + "?download=true"
	}
	revision := descriptor.Revision
	if revision == "" {
		revision = "master"
	}
	return "https://modelscope.cn/models/" + descriptor.Repository +
		"/resolve/" + url.PathEscape(revision) + "/" + escapeModelPath(file.Name)
}

func escapeModelPath(path string) string {
	parts := strings.Split(filepath.ToSlash(path), "/")
	for index := range parts {
		parts[index] = url.PathEscape(parts[index])
	}
	return strings.Join(parts, "/")
}

func (descriptor builtinVisionDescriptor) resolveReasoningMode(requested string) string {
	requested = strings.ToLower(strings.TrimSpace(requested))
	for _, mode := range descriptor.ReasoningModes {
		if mode.ID == requested {
			return mode.ID
		}
	}
	for _, mode := range descriptor.ReasoningModes {
		if mode.ID == descriptor.DefaultReasoningMode {
			return mode.ID
		}
	}
	if len(descriptor.ReasoningModes) > 0 {
		return descriptor.ReasoningModes[0].ID
	}
	return ""
}

func (descriptor builtinVisionDescriptor) llamaModelFileName() string {
	for _, file := range descriptor.Files {
		name := strings.ToLower(file.Name)
		if strings.HasSuffix(name, ".gguf") && !strings.Contains(name, "mmproj") {
			return file.Name
		}
	}
	return ""
}

func (descriptor builtinVisionDescriptor) llamaProjectorFileName() string {
	for _, file := range descriptor.Files {
		name := strings.ToLower(file.Name)
		if strings.HasSuffix(name, ".gguf") && strings.Contains(name, "mmproj") {
			return file.Name
		}
	}
	return ""
}

var builtinThinkingToggle = []BuiltinVisionReasoningMode{
	{ID: "off", DisplayName: "关闭思考（更快）"},
	{ID: "on", DisplayName: "开启思考（显示过程）"},
}

var builtinVisionDescriptors = []builtinVisionDescriptor{
	{
		Tier: builtinVisionGemma12B, DisplayName: "Gemma 4 12B（桌面实验档）", Engine: "litert",
		Hub: "huggingface", Repository: "litert-community/gemma-4-12B-it-litert-lm",
		EstimatedDownload: 6_550_000_000,
		MinimumFree:       12 * 1024 * 1024 * 1024,
		Recommendation:    "高内存电脑的高质量代码/Agent 档；约 6.55GB，当前 LiteRT 包仅支持文本与音频，图像支持等待上游更新。",
		SupportsVision:    false, ReasoningModes: builtinThinkingToggle, DefaultReasoningMode: "off",
		Files: []builtinVisionModelFile{
			{Name: "gemma-4-12B-it.litertlm"},
		},
	},
	{
		Tier: builtinVisionQwen9B, DisplayName: "Qwen3.5-9B Ultra", Engine: "mnn",
		Hub: "modelscope", Repository: "MNN/Qwen3.5-9B-MNN",
		EstimatedDownload: 7_275_000_000,
		MinimumFree:       12 * 1024 * 1024 * 1024,
		Recommendation:    "16GB 旗舰设备的最高质量档；代码、Agent 和视觉更强，但更慢、更耗电。",
		SupportsVision:    true, ReasoningModes: builtinThinkingToggle, DefaultReasoningMode: "off",
		Files: []builtinVisionModelFile{
			{Name: "config.json"},
			{Name: "embeddings_bf16.bin"},
			{Name: "llm.mnn"},
			{Name: "llm.mnn.json"},
			{Name: "llm.mnn.weight"},
			{Name: "llm_config.json"},
			{Name: "tokenizer.txt"},
			{Name: "visual.mnn"},
			{Name: "visual.mnn.weight"},
		},
	},
	{
		Tier: builtinVisionCoder7B, DisplayName: "Qwen2.5-Coder-7B", Engine: "mnn",
		Hub: "modelscope", Repository: "MNN/Qwen2.5-Coder-7B-Instruct-MNN",
		EstimatedDownload: 4_427_000_000,
		MinimumFree:       7 * 1024 * 1024 * 1024,
		Recommendation:    "纯文本代码专用档；补全、重构和解释代码更稳，不支持图片。",
		SupportsVision:    false,
		Files: []builtinVisionModelFile{
			{Name: "config.json"},
			{Name: "llm_config.json"},
			{Name: "llm.mnn"},
			{Name: "llm.mnn.json"},
			{Name: "llm.mnn.weight"},
			{Name: "tokenizer.mtok"},
		},
	},
	{
		Tier: builtinVisionQwen4B, DisplayName: "Qwen3.5-4B Pro", Engine: "mnn",
		Hub: "modelscope", Repository: "MNN/Qwen3.5-4B-MNN",
		EstimatedDownload: 2_846_000_000,
		MinimumFree:       6 * 1024 * 1024 * 1024,
		Recommendation:    "中文、代码和手机/电脑 GUI Agent 首选。",
		SupportsVision:    true, ReasoningModes: builtinThinkingToggle, DefaultReasoningMode: "off",
		Files: []builtinVisionModelFile{
			{Name: "config.json"},
			{Name: "llm.mnn"},
			{Name: "llm.mnn.json"},
			{Name: "llm.mnn.weight"},
			{Name: "llm_config.json"},
			{Name: "tokenizer.txt"},
			{Name: "visual.mnn"},
			{Name: "visual.mnn.weight"},
		},
	},
	{
		Tier: builtinVisionQwen2B, DisplayName: "Qwen3.5-2B Lite", Engine: "mnn",
		Hub: "modelscope", Repository: "MNN/Qwen3.5-2B-MNN",
		EstimatedDownload: 1_387_000_000,
		MinimumFree:       3 * 1024 * 1024 * 1024,
		Recommendation:    "内存较小设备的中文 GUI 与代码轻量档。",
		SupportsVision:    true, ReasoningModes: builtinThinkingToggle, DefaultReasoningMode: "off",
		Files: []builtinVisionModelFile{
			{Name: "config.json"},
			{Name: "llm.mnn"},
			{Name: "llm.mnn.json"},
			{Name: "llm.mnn.weight"},
			{Name: "llm_config.json"},
			{Name: "tokenizer.txt"},
			{Name: "visual.mnn"},
			{Name: "visual.mnn.weight"},
		},
	},
	{
		Tier: builtinVisionGemma4B, DisplayName: "Gemma 4 E4B", Engine: "litert",
		Hub: "huggingface", Repository: "litert-community/gemma-4-E4B-it-litert-lm",
		EstimatedDownload: 3_660_000_000,
		MinimumFree:       7 * 1024 * 1024 * 1024,
		Recommendation:    "端侧优化、原生图像/音频；中文 GUI 定位不如 Qwen 有针对性。",
		SupportsVision:    true, ReasoningModes: builtinThinkingToggle, DefaultReasoningMode: "off",
		Files: []builtinVisionModelFile{
			{Name: "gemma-4-E4B-it.litertlm"},
		},
	},
	{
		Tier: builtinVisionGemma2B, DisplayName: "Gemma 4 E2B", Engine: "litert",
		Hub: "huggingface", Repository: "litert-community/gemma-4-E2B-it-litert-lm",
		EstimatedDownload: 2_589_000_000,
		MinimumFree:       5 * 1024 * 1024 * 1024,
		Recommendation:    "最省运行内存的完整图像/音频档，适合中端设备。",
		SupportsVision:    true, ReasoningModes: builtinThinkingToggle, DefaultReasoningMode: "off",
		Files: []builtinVisionModelFile{
			{Name: "gemma-4-E2B-it.litertlm"},
		},
	},
	{
		Tier: builtinVisionGLMEdgeV2B, DisplayName: "GLM-Edge-V-2B", Engine: "llama",
		Hub: "modelscope", Repository: "ZhipuAI/glm-edge-v-2b-gguf",
		EstimatedDownload: 1_920_000_000,
		MinimumFree:       5 * 1024 * 1024 * 1024,
		Recommendation:    "智谱端侧视觉模型；可本地读图、聊天和 GUI 操作，电脑端通过内置 llama.cpp 运行。",
		SupportsVision:    true,
		Files: []builtinVisionModelFile{
			{Name: "ggml-model-Q4_K_M.gguf"},
			{Name: "mmproj-model-f16.gguf"},
		},
	},
	{
		Tier: builtinVisionGLMEdge15B, DisplayName: "GLM-Edge-1.5B-Chat", Engine: "llama",
		Hub: "modelscope", Repository: "ZhipuAI/glm-edge-1.5b-chat-gguf",
		EstimatedDownload: 980_000_000,
		MinimumFree:       3 * 1024 * 1024 * 1024,
		Recommendation:    "智谱轻量中文对话模型；速度和内存占用更友好，不支持读取图片。",
		SupportsVision:    false,
		Files: []builtinVisionModelFile{
			{Name: "ggml-model-Q4_K_M.gguf"},
		},
	},
	{
		Tier: builtinVisionDeepSeekR1Qwen7B, DisplayName: "DeepSeek-R1-Distill-Qwen-7B", Engine: "litert",
		Hub: "huggingface", Repository: "litert-community/DeepSeek-R1-Distill-Qwen-7B",
		EstimatedDownload: 4_530_000_000,
		MinimumFree:       8 * 1024 * 1024 * 1024,
		Recommendation:    "DeepSeek 推理档；适合离线分析、代码解释和复杂文本任务，不支持读取图片。",
		SupportsVision:    false, ReasoningModes: builtinThinkingToggle, DefaultReasoningMode: "on",
		Files: []builtinVisionModelFile{
			{Name: "DeepSeek-R1-Distill-Qwen-7B_q4_block32_ekv4096.litertlm"},
		},
	},
	{
		Tier: builtinVisionDeepSeekR1Qwen15B, DisplayName: "DeepSeek-R1-Distill-Qwen-1.5B", Engine: "litert",
		Hub: "huggingface", Repository: "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
		EstimatedDownload: 1_830_000_000,
		MinimumFree:       4 * 1024 * 1024 * 1024,
		Recommendation:    "最轻量的 DeepSeek 推理档；适合离线思考和短代码任务，不支持读取图片。",
		SupportsVision:    false, ReasoningModes: builtinThinkingToggle, DefaultReasoningMode: "on",
		Files: []builtinVisionModelFile{
			{Name: "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm"},
		},
	},
	{
		Tier: builtinVisionDeepSeekR1Llama8B, DisplayName: "DeepSeek-R1-Distill-Llama-8B", Engine: "llama",
		Hub: "huggingface", Repository: "bartowski/DeepSeek-R1-Distill-Llama-8B-GGUF",
		EstimatedDownload: 4_920_000_000,
		MinimumFree:       8 * 1024 * 1024 * 1024,
		Recommendation:    "较强的本地推理档；适合代码和复杂长文本，不支持读取图片。",
		SupportsVision:    false, ReasoningModes: builtinThinkingToggle, DefaultReasoningMode: "on",
		Files: []builtinVisionModelFile{
			{Name: "DeepSeek-R1-Distill-Llama-8B-Q4_K_M.gguf"},
		},
	},
	{
		Tier: builtinVisionDeepSeekCoderV2Lite, DisplayName: "DeepSeek-Coder-V2-Lite", Engine: "llama",
		Hub: "huggingface", Repository: "QuantFactory/DeepSeek-Coder-V2-Lite-Instruct-GGUF",
		EstimatedDownload: 6_430_000_000,
		MinimumFree:       10 * 1024 * 1024 * 1024,
		Recommendation:    "代码专用模型（16B 总参数、MoE）；电脑端可用，手机端仅建议高内存设备尝试，不支持读取图片。",
		SupportsVision:    false,
		Files: []builtinVisionModelFile{
			{Name: "DeepSeek-Coder-V2-Lite-Instruct.Q2_K.gguf"},
		},
	},
}

type BuiltinVisionModelInfo struct {
	Tier                 string                       `json:"tier"`
	DisplayName          string                       `json:"displayName"`
	Engine               string                       `json:"engine"`
	SizeBytes            int64                        `json:"sizeBytes"`
	Installed            bool                         `json:"installed"`
	Active               bool                         `json:"active"`
	Available            bool                         `json:"available"`
	UnavailableWhy       string                       `json:"unavailableReason,omitempty"`
	Recommendation       string                       `json:"recommendation"`
	SupportsVision       bool                         `json:"supportsVision"`
	ReasoningModes       []BuiltinVisionReasoningMode `json:"reasoningModes"`
	DefaultReasoningMode string                       `json:"defaultReasoningMode,omitempty"`
}

type BuiltinVisionModelStatus struct {
	Models          []BuiltinVisionModelInfo `json:"models"`
	InstallingTier  string                   `json:"installingTier,omitempty"`
	DownloadedBytes int64                    `json:"downloadedBytes"`
	TotalBytes      int64                    `json:"totalBytes"`
	Message         string                   `json:"message,omitempty"`
	Error           string                   `json:"error,omitempty"`
	Recommendation  string                   `json:"deviceRecommendation"`
}

func (status BuiltinVisionModelStatus) IsInstalling() bool {
	return status.InstallingTier != ""
}

type builtinVisionManager struct {
	mu         sync.Mutex
	root       string
	client     *http.Client
	status     BuiltinVisionModelStatus
	cancel     context.CancelFunc
	onChanged  func()
	lastNotify time.Time
}

func newBuiltinVisionManager(configPath string, onChanged func()) *builtinVisionManager {
	transport := &http.Transport{
		Proxy:                 http.ProxyFromEnvironment,
		DialContext:           (&net.Dialer{Timeout: 30 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
		ForceAttemptHTTP2:     true,
		ResponseHeaderTimeout: 60 * time.Second,
		IdleConnTimeout:       90 * time.Second,
	}
	manager := &builtinVisionManager{
		root: filepath.Join(
			filepath.Dir(configPath),
			"vision-models",
			builtinVisionPackageVersion,
		),
		client:    &http.Client{Transport: transport},
		onChanged: onChanged,
	}
	manager.status = manager.snapshot("", "")
	return manager
}

func (manager *builtinVisionManager) Status() BuiltinVisionModelStatus {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	manager.status.Models = manager.modelInfos(manager.activeTier())
	return cloneBuiltinVisionStatus(manager.status)
}

func (manager *builtinVisionManager) StartInstall(tier string) error {
	descriptor, ok := findBuiltinVisionDescriptor(tier)
	if !ok {
		return fmt.Errorf("未知的内置视觉模型：%s", tier)
	}
	if available, reason := builtinVisionDescriptorAvailable(descriptor); !available {
		return errors.New(reason)
	}
	manager.mu.Lock()
	if manager.cancel != nil {
		manager.mu.Unlock()
		return errors.New("已有模型正在安装")
	}
	ctx, cancel := context.WithCancel(context.Background())
	manager.cancel = cancel
	manager.status = manager.snapshotLocked("正在准备 "+descriptor.DisplayName+"…", "")
	manager.status.InstallingTier = tier
	manager.status.TotalBytes = descriptor.totalBytes()
	manager.mu.Unlock()
	manager.notify()

	go manager.install(ctx, descriptor)
	return nil
}

func (manager *builtinVisionManager) CancelInstall() {
	manager.mu.Lock()
	cancel := manager.cancel
	manager.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

func (manager *builtinVisionManager) Select(tier string) error {
	descriptor, ok := findBuiltinVisionDescriptor(tier)
	if !ok {
		return fmt.Errorf("未知的内置视觉模型：%s", tier)
	}
	if available, reason := builtinVisionDescriptorAvailable(descriptor); !available {
		return errors.New(reason)
	}
	if !manager.isInstalled(descriptor) {
		return fmt.Errorf("%s 尚未安装", descriptor.DisplayName)
	}
	if err := manager.writeSelection(tier); err != nil {
		return err
	}
	manager.mu.Lock()
	manager.status = manager.snapshotLocked("已选择 "+descriptor.DisplayName+"。", "")
	manager.mu.Unlock()
	manager.notify()
	return nil
}

func (manager *builtinVisionManager) Delete(tier string) error {
	descriptor, ok := findBuiltinVisionDescriptor(tier)
	if !ok {
		return fmt.Errorf("未知的内置视觉模型：%s", tier)
	}
	manager.mu.Lock()
	if manager.cancel != nil {
		manager.mu.Unlock()
		return errors.New("请先暂停当前模型下载，再删除模型")
	}
	manager.mu.Unlock()
	target := manager.modelDirectory(descriptor)
	relative, err := filepath.Rel(manager.root, target)
	if err != nil || relative == "." || strings.HasPrefix(relative, "..") || filepath.IsAbs(relative) {
		return errors.New("拒绝删除视觉模型目录之外的路径")
	}
	if err := os.RemoveAll(target); err != nil {
		return err
	}
	if manager.activeTier() == tier {
		replacement := ""
		for _, candidate := range builtinVisionDescriptors {
			available, _ := builtinVisionDescriptorAvailable(candidate)
			if candidate.Tier != tier && available && manager.isInstalled(candidate) {
				replacement = candidate.Tier
				break
			}
		}
		if err := manager.writeSelection(replacement); err != nil {
			return err
		}
	}
	manager.mu.Lock()
	manager.status = manager.snapshotLocked(descriptor.DisplayName+" 已删除。", "")
	manager.mu.Unlock()
	manager.notify()
	return nil
}

func (manager *builtinVisionManager) ActiveDescriptor() (builtinVisionDescriptor, string, error) {
	active := manager.activeTier()
	if descriptor, ok := findBuiltinVisionDescriptor(active); ok &&
		manager.isInstalled(descriptor) {
		if available, _ := builtinVisionDescriptorAvailable(descriptor); available {
			return descriptor, manager.modelDirectory(descriptor), nil
		}
	}
	for _, descriptor := range builtinVisionDescriptors {
		available, _ := builtinVisionDescriptorAvailable(descriptor)
		if available && manager.isInstalled(descriptor) {
			return descriptor, manager.modelDirectory(descriptor), nil
		}
	}
	return builtinVisionDescriptor{}, "", errors.New("尚未安装或选择内置视觉模型")
}

func (manager *builtinVisionManager) ActiveVisionDescriptor() (builtinVisionDescriptor, string, error) {
	active := manager.activeTier()
	if descriptor, ok := findBuiltinVisionDescriptor(active); ok &&
		descriptor.SupportsVision && manager.isInstalled(descriptor) {
		if available, _ := builtinVisionDescriptorAvailable(descriptor); available {
			return descriptor, manager.modelDirectory(descriptor), nil
		}
	}
	for _, descriptor := range builtinVisionDescriptors {
		available, _ := builtinVisionDescriptorAvailable(descriptor)
		if descriptor.SupportsVision && available && manager.isInstalled(descriptor) {
			return descriptor, manager.modelDirectory(descriptor), nil
		}
	}
	return builtinVisionDescriptor{}, "", errors.New("尚未安装支持图片的内置模型")
}

func (manager *builtinVisionManager) Close() {
	manager.CancelInstall()
	if transport, ok := manager.client.Transport.(*http.Transport); ok {
		transport.CloseIdleConnections()
	}
}

func (manager *builtinVisionManager) install(ctx context.Context, descriptor builtinVisionDescriptor) {
	resolved, err := manager.resolveRemoteDescriptor(ctx, descriptor)
	if err == nil {
		descriptor = resolved
		manager.setProgress(descriptor, 0, "已读取官方文件清单，正在准备下载…")
	}
	modelDir := manager.modelDirectory(descriptor)
	if err == nil {
		err = os.MkdirAll(modelDir, 0o700)
	}
	if err == nil {
		err = requireDesktopFreeSpace(modelDir, descriptor.MinimumFree)
	}
	if err == nil {
		_ = os.Remove(filepath.Join(modelDir, builtinVisionReadyMarker))
		_ = os.Remove(filepath.Join(modelDir, builtinVisionManifest))
		err = manager.writeInstallManifest(modelDir, descriptor)
	}
	if err == nil {
		var completed int64
		for index, file := range descriptor.Files {
			if ctx.Err() != nil {
				err = ctx.Err()
				break
			}
			target := filepath.Join(modelDir, file.Name)
			if fileMatches(target, file) {
				completed += file.Size
				manager.setProgress(
					descriptor,
					completed,
					fmt.Sprintf("已校验 %d/%d：%s", index+1, len(descriptor.Files), file.Name),
				)
				continue
			}
			_ = os.Remove(target)
			err = manager.downloadFile(ctx, descriptor, file, modelDir, completed, index)
			if err != nil {
				break
			}
			completed += file.Size
		}
	}
	if err == nil {
		marker := filepath.Join(modelDir, builtinVisionReadyMarker)
		err = os.WriteFile(
			marker,
			[]byte(descriptor.Tier+"\n"+descriptor.Revision+"\n"+strconv.FormatInt(descriptor.totalBytes(), 10)+"\n"),
			0o600,
		)
	}
	if err == nil {
		err = manager.writeSelection(descriptor.Tier)
	}

	manager.mu.Lock()
	manager.cancel = nil
	if errors.Is(err, context.Canceled) {
		manager.status = manager.snapshotLocked("已暂停下载；断点文件已保留。", "")
	} else if err != nil {
		manager.status = manager.snapshotLocked("模型安装未完成；再次点击会从断点继续。", err.Error())
	} else {
		capability := "可用于离线聊天和代码任务。"
		if descriptor.SupportsVision {
			capability = "可用于离线聊天、看图和 GUI 操作。"
		}
		manager.status = manager.snapshotLocked(descriptor.DisplayName+" 已安装，"+capability, "")
	}
	manager.mu.Unlock()
	manager.notify()
}

func (manager *builtinVisionManager) resolveRemoteDescriptor(
	ctx context.Context,
	descriptor builtinVisionDescriptor,
) (builtinVisionDescriptor, error) {
	switch descriptor.Hub {
	case "modelscope":
		return manager.resolveModelScopeDescriptor(ctx, descriptor)
	case "huggingface":
		return manager.resolveHuggingFaceDescriptor(ctx, descriptor)
	default:
		return builtinVisionDescriptor{}, fmt.Errorf("不支持的模型仓库：%s", descriptor.Hub)
	}
}

func (manager *builtinVisionManager) resolveModelScopeDescriptor(
	ctx context.Context,
	descriptor builtinVisionDescriptor,
) (builtinVisionDescriptor, error) {
	var response struct {
		Code    int    `json:"Code"`
		Success bool   `json:"Success"`
		Message string `json:"Message"`
		Data    struct {
			Files []struct {
				Path   string `json:"Path"`
				Type   string `json:"Type"`
				Size   int64  `json:"Size"`
				SHA256 string `json:"Sha256"`
			} `json:"Files"`
			LatestCommitter struct {
				ShortID string `json:"ShortId"`
			} `json:"LatestCommitter"`
		} `json:"Data"`
	}
	endpoint := "https://modelscope.cn/api/v1/models/" + descriptor.Repository +
		"/repo/files?Recursive=true"
	if err := manager.getJSON(ctx, endpoint, &response); err != nil {
		return builtinVisionDescriptor{}, fmt.Errorf("读取 ModelScope 官方清单：%w", err)
	}
	if !response.Success || response.Code != http.StatusOK {
		return builtinVisionDescriptor{}, fmt.Errorf(
			"ModelScope 官方清单读取失败：%s",
			strings.TrimSpace(response.Message),
		)
	}
	available := make(map[string]builtinVisionModelFile, len(response.Data.Files))
	for _, file := range response.Data.Files {
		if file.Type != "blob" {
			continue
		}
		available[file.Path] = builtinVisionModelFile{
			Name: file.Path, Size: file.Size, SHA256: strings.ToLower(file.SHA256),
		}
	}
	files, err := resolveRequiredModelFiles(descriptor.Files, available)
	if err != nil {
		return builtinVisionDescriptor{}, err
	}
	descriptor.Files = files
	descriptor.Revision = strings.TrimSpace(response.Data.LatestCommitter.ShortID)
	if descriptor.Revision == "" {
		descriptor.Revision = "master"
	}
	return descriptor, nil
}

func (manager *builtinVisionManager) resolveHuggingFaceDescriptor(
	ctx context.Context,
	descriptor builtinVisionDescriptor,
) (builtinVisionDescriptor, error) {
	var model struct {
		SHA string `json:"sha"`
	}
	if err := manager.getJSON(
		ctx,
		"https://huggingface.co/api/models/"+descriptor.Repository,
		&model,
	); err != nil {
		return builtinVisionDescriptor{}, fmt.Errorf("读取 Hugging Face 模型版本：%w", err)
	}
	revision := strings.TrimSpace(model.SHA)
	if revision == "" {
		revision = "main"
	}
	var tree []struct {
		Type string `json:"type"`
		Path string `json:"path"`
		Size int64  `json:"size"`
		LFS  *struct {
			Size   int64  `json:"size"`
			SHA256 string `json:"sha256"`
		} `json:"lfs"`
	}
	treeURL := "https://huggingface.co/api/models/" + descriptor.Repository +
		"/tree/" + url.PathEscape(revision) + "?recursive=true&expand=true"
	if err := manager.getJSON(ctx, treeURL, &tree); err != nil {
		return builtinVisionDescriptor{}, fmt.Errorf("读取 Hugging Face 官方清单：%w", err)
	}
	available := make(map[string]builtinVisionModelFile, len(tree))
	for _, file := range tree {
		if file.Type != "file" || file.LFS == nil {
			continue
		}
		size := file.LFS.Size
		if size <= 0 {
			size = file.Size
		}
		available[file.Path] = builtinVisionModelFile{
			Name: file.Path, Size: size, SHA256: strings.ToLower(file.LFS.SHA256),
		}
	}
	files, err := resolveRequiredModelFiles(descriptor.Files, available)
	if err != nil {
		return builtinVisionDescriptor{}, err
	}
	descriptor.Files = files
	descriptor.Revision = revision
	return descriptor, nil
}

func (manager *builtinVisionManager) getJSON(ctx context.Context, endpoint string, target any) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return err
	}
	response, err := manager.client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d", response.StatusCode)
	}
	return json.NewDecoder(io.LimitReader(response.Body, 16*1024*1024)).Decode(target)
}

func resolveRequiredModelFiles(
	required []builtinVisionModelFile,
	available map[string]builtinVisionModelFile,
) ([]builtinVisionModelFile, error) {
	files := make([]builtinVisionModelFile, 0, len(required))
	for _, expected := range required {
		file, ok := available[expected.Name]
		if !ok {
			return nil, fmt.Errorf("官方模型清单缺少 %s", expected.Name)
		}
		decoded, decodeErr := hex.DecodeString(file.SHA256)
		if file.Size <= 0 || decodeErr != nil || len(decoded) != sha256.Size {
			return nil, fmt.Errorf("%s 的官方大小或 SHA-256 无效", expected.Name)
		}
		file.Name = expected.Name
		files = append(files, file)
	}
	return files, nil
}

func (manager *builtinVisionManager) writeInstallManifest(
	modelDir string,
	descriptor builtinVisionDescriptor,
) error {
	data, err := json.Marshal(builtinVisionInstallManifest{
		Version: 1, Repository: descriptor.Repository,
		Revision: descriptor.Revision, Files: descriptor.Files,
	})
	if err != nil {
		return err
	}
	target := filepath.Join(modelDir, builtinVisionManifest)
	partial := target + ".part"
	if err := os.WriteFile(partial, data, 0o600); err != nil {
		return err
	}
	if err := os.Remove(target); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return os.Rename(partial, target)
}

func (manager *builtinVisionManager) downloadFile(
	ctx context.Context,
	descriptor builtinVisionDescriptor,
	file builtinVisionModelFile,
	modelDir string,
	completed int64,
	index int,
) error {
	partial := filepath.Join(modelDir, file.Name+".part")
	info, _ := os.Stat(partial)
	offset := int64(0)
	if info != nil {
		offset = info.Size()
	}
	if offset > file.Size {
		if err := os.Remove(partial); err != nil {
			return err
		}
		offset = 0
	}
	if offset == file.Size {
		if fileMatches(partial, file) {
			target := filepath.Join(modelDir, file.Name)
			if err := os.Remove(target); err != nil && !errors.Is(err, os.ErrNotExist) {
				return err
			}
			if err := os.Rename(partial, target); err != nil {
				return err
			}
			manager.setProgress(
				descriptor,
				completed+file.Size,
				fmt.Sprintf("已校验 %d/%d：%s", index+1, len(descriptor.Files), file.Name),
			)
			return nil
		}
		if err := os.Remove(partial); err != nil {
			return err
		}
		offset = 0
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, descriptor.fileURL(file), nil)
	if err != nil {
		return err
	}
	if offset > 0 {
		request.Header.Set("Range", "bytes="+strconv.FormatInt(offset, 10)+"-")
	}
	response, err := manager.client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	appendMode := offset > 0 && response.StatusCode == http.StatusPartialContent
	if response.StatusCode != http.StatusOK && response.StatusCode != http.StatusPartialContent {
		return fmt.Errorf("下载 %s 失败：HTTP %d", file.Name, response.StatusCode)
	}
	flags := os.O_CREATE | os.O_WRONLY
	started := int64(0)
	if appendMode {
		flags |= os.O_APPEND
		started = offset
	} else {
		flags |= os.O_TRUNC
	}
	output, err := os.OpenFile(partial, flags, 0o600)
	if err != nil {
		return err
	}
	buffer := make([]byte, 1024*1024)
	written := started
	for {
		count, readErr := response.Body.Read(buffer)
		if count > 0 {
			if _, err = output.Write(buffer[:count]); err != nil {
				_ = output.Close()
				return err
			}
			written += int64(count)
			manager.setProgress(
				descriptor,
				completed+written,
				fmt.Sprintf("下载 %d/%d：%s", index+1, len(descriptor.Files), file.Name),
			)
		}
		if readErr != nil {
			if errors.Is(readErr, io.EOF) {
				break
			}
			_ = output.Close()
			return readErr
		}
	}
	if err := output.Close(); err != nil {
		return err
	}
	info, err = os.Stat(partial)
	if err != nil {
		return err
	}
	if info.Size() != file.Size {
		return fmt.Errorf("%s 大小不正确：%d / %d", file.Name, info.Size(), file.Size)
	}
	if !fileMatches(partial, file) {
		return fmt.Errorf("%s SHA-256 校验失败", file.Name)
	}
	if err := os.Remove(filepath.Join(modelDir, file.Name)); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return os.Rename(partial, filepath.Join(modelDir, file.Name))
}

func (manager *builtinVisionManager) setProgress(
	descriptor builtinVisionDescriptor,
	downloaded int64,
	message string,
) {
	manager.mu.Lock()
	manager.status.InstallingTier = descriptor.Tier
	manager.status.DownloadedBytes = min(downloaded, descriptor.totalBytes())
	manager.status.TotalBytes = descriptor.totalBytes()
	manager.status.Message = message
	manager.status.Error = ""
	manager.status.Models = manager.modelInfos(manager.activeTier())
	shouldNotify := time.Since(manager.lastNotify) >= 250*time.Millisecond
	if shouldNotify {
		manager.lastNotify = time.Now()
	}
	manager.mu.Unlock()
	if shouldNotify {
		manager.notify()
	}
}

func (manager *builtinVisionManager) snapshot(message string, errorMessage string) BuiltinVisionModelStatus {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	return manager.snapshotLocked(message, errorMessage)
}

func (manager *builtinVisionManager) snapshotLocked(message string, errorMessage string) BuiltinVisionModelStatus {
	active := manager.activeTier()
	return BuiltinVisionModelStatus{
		Models:         manager.modelInfos(active),
		Message:        message,
		Error:          errorMessage,
		Recommendation: desktopVisionRecommendation(),
	}
}

func (manager *builtinVisionManager) modelInfos(active string) []BuiltinVisionModelInfo {
	result := make([]BuiltinVisionModelInfo, 0, len(builtinVisionDescriptors))
	for _, descriptor := range builtinVisionDescriptors {
		available, unavailableReason := builtinVisionDescriptorAvailable(descriptor)
		result = append(result, BuiltinVisionModelInfo{
			Tier: descriptor.Tier, DisplayName: descriptor.DisplayName,
			Engine: descriptor.Engine, SizeBytes: descriptor.totalBytes(),
			Installed: manager.isInstalled(descriptor), Active: available && active == descriptor.Tier,
			Available: available, UnavailableWhy: unavailableReason,
			Recommendation:       descriptor.Recommendation,
			SupportsVision:       descriptor.SupportsVision,
			ReasoningModes:       append([]BuiltinVisionReasoningMode(nil), descriptor.ReasoningModes...),
			DefaultReasoningMode: descriptor.DefaultReasoningMode,
		})
	}
	return result
}

func (manager *builtinVisionManager) modelDirectory(descriptor builtinVisionDescriptor) string {
	return filepath.Join(manager.root, descriptor.Tier)
}

func (manager *builtinVisionManager) isInstalled(descriptor builtinVisionDescriptor) bool {
	modelDir := manager.modelDirectory(descriptor)
	if info, err := os.Stat(filepath.Join(modelDir, builtinVisionReadyMarker)); err != nil || info.IsDir() {
		return false
	}
	files := descriptor.Files
	if data, err := os.ReadFile(filepath.Join(modelDir, builtinVisionManifest)); err == nil {
		var manifest builtinVisionInstallManifest
		if json.Unmarshal(data, &manifest) != nil {
			return false
		}
		available := make(map[string]builtinVisionModelFile, len(manifest.Files))
		for _, file := range manifest.Files {
			available[file.Name] = file
		}
		resolved, resolveErr := resolveRequiredModelFiles(descriptor.Files, available)
		if resolveErr != nil {
			return false
		}
		files = resolved
	}
	for _, file := range files {
		info, err := os.Stat(filepath.Join(modelDir, file.Name))
		if err != nil || !info.Mode().IsRegular() || info.Size() <= 0 ||
			(file.Size > 0 && info.Size() != file.Size) {
			return false
		}
	}
	return true
}

func (manager *builtinVisionManager) selectionPath() string {
	return filepath.Join(manager.root, "selection.json")
}

func (manager *builtinVisionManager) activeTier() string {
	data, err := os.ReadFile(manager.selectionPath())
	if err != nil {
		return ""
	}
	var selection struct {
		Tier string `json:"tier"`
	}
	if json.Unmarshal(data, &selection) != nil {
		return ""
	}
	return selection.Tier
}

func (manager *builtinVisionManager) writeSelection(tier string) error {
	if err := os.MkdirAll(manager.root, 0o700); err != nil {
		return err
	}
	data, err := json.Marshal(struct {
		Tier string `json:"tier"`
	}{Tier: tier})
	if err != nil {
		return err
	}
	partial := manager.selectionPath() + ".part"
	if err := os.WriteFile(partial, data, 0o600); err != nil {
		return err
	}
	if err := os.Remove(manager.selectionPath()); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return os.Rename(partial, manager.selectionPath())
}

func (manager *builtinVisionManager) notify() {
	if manager.onChanged != nil {
		manager.onChanged()
	}
}

func findBuiltinVisionDescriptor(tier string) (builtinVisionDescriptor, bool) {
	for _, descriptor := range builtinVisionDescriptors {
		if descriptor.Tier == tier {
			return descriptor, true
		}
	}
	return builtinVisionDescriptor{}, false
}

func builtinVisionDescriptorAvailable(descriptor builtinVisionDescriptor) (bool, string) {
	if runtime.GOOS != "windows" {
		return false, "当前内置桌面视觉运行时先支持 Windows；此系统请使用用户 API"
	}
	if descriptor.Engine == "litert" && runtime.GOARCH != "amd64" {
		return false, "LiteRT-LM 0.14 尚未提供 Windows ARM64 运行库；可选择 Qwen 或用户 API"
	}
	if descriptor.Engine == "llama" && runtime.GOARCH != "amd64" {
		return false, "当前正式包仅内置 Windows x64 llama.cpp 运行库；Windows ARM64 请使用 Qwen MNN 或用户 API"
	}
	return true, ""
}

func fileMatches(path string, expected builtinVisionModelFile) bool {
	info, err := os.Stat(path)
	if err != nil || info.Size() != expected.Size || !info.Mode().IsRegular() {
		return false
	}
	file, err := os.Open(path)
	if err != nil {
		return false
	}
	defer file.Close()
	digest := sha256.New()
	if _, err := io.Copy(digest, file); err != nil {
		return false
	}
	return strings.EqualFold(hex.EncodeToString(digest.Sum(nil)), expected.SHA256)
}

func cloneBuiltinVisionStatus(status BuiltinVisionModelStatus) BuiltinVisionModelStatus {
	status.Models = append([]BuiltinVisionModelInfo(nil), status.Models...)
	for index := range status.Models {
		status.Models[index].ReasoningModes = append(
			[]BuiltinVisionReasoningMode(nil),
			status.Models[index].ReasoningModes...,
		)
	}
	return status
}

package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"embed"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/color"
	_ "image/jpeg"
	"image/png"
	"io"
	"io/fs"
	"math"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	xdraw "golang.org/x/image/draw"
)

//go:embed generated/vlm/*
var embeddedVisionRuntimes embed.FS

const (
	visionProtocolMagic      = uint32(0x314C564D)
	visionMaxImageSide       = 1280
	visionStreamContent      = "content"
	visionStreamReasoning    = "reasoning"
	visionRepeatedChunkLimit = 32
	visionMNNConfigFileName  = "config.json"
	visionMNNCacheSchema     = "mnn-3.5.0-config-entry-v2"
)

type desktopVisionRuntime struct {
	mu          sync.Mutex
	dataRoot    string
	process     *desktopVisionProcess
	llamaServer *desktopLlamaServer
	llamaClient *http.Client
}

type desktopVisionProcess struct {
	tier   string
	engine string
	cmd    *exec.Cmd
	stdin  io.WriteCloser
	stdout *bufio.Reader
	stderr *boundedVisionLog
}

type desktopLlamaServer struct {
	tier     string
	modelDir string
	baseURL  string
	cmd      *exec.Cmd
	log      *boundedVisionLog
}

type boundedVisionLog struct {
	mu    sync.Mutex
	data  []byte
	limit int
}

type visionChunkGuard struct {
	repeatedChunkLimit int
	pendingKind        string
	pendingChunk       string
	pendingText        strings.Builder
	repeatedChunkCount int
}

func (guard *visionChunkGuard) Accept(kind string, chunk string) (string, string, error) {
	if chunk == "" {
		return "", "", nil
	}
	if guard.pendingKind != kind || guard.pendingChunk != chunk {
		readyKind, readyText := guard.flush()
		guard.pendingKind = kind
		guard.pendingChunk = chunk
		guard.repeatedChunkCount = 0
		guard.pendingText.WriteString(chunk)
		guard.repeatedChunkCount++
		return readyKind, readyText, nil
	}
	guard.pendingText.WriteString(chunk)
	guard.repeatedChunkCount++
	limit := guard.repeatedChunkLimit
	if limit <= 1 {
		limit = visionRepeatedChunkLimit
	}
	if guard.repeatedChunkCount >= limit && strings.TrimSpace(chunk) != "" {
		guard.pendingText.Reset()
		return "", "", errors.New(
			"本地模型输出异常：连续重复相同 Token，已自动停止推理；" +
				"请改用 CPU 后端或重新选择模型",
		)
	}
	return "", "", nil
}

func (guard *visionChunkGuard) Finish() (string, string) {
	return guard.flush()
}

func (guard *visionChunkGuard) flush() (string, string) {
	if guard.pendingText.Len() == 0 {
		return "", ""
	}
	kind := guard.pendingKind
	text := guard.pendingText.String()
	guard.pendingText.Reset()
	return kind, text
}

func (log *boundedVisionLog) Write(data []byte) (int, error) {
	log.mu.Lock()
	defer log.mu.Unlock()
	log.data = append(log.data, data...)
	if len(log.data) > log.limit {
		log.data = append([]byte(nil), log.data[len(log.data)-log.limit:]...)
	}
	return len(data), nil
}

func (log *boundedVisionLog) String() string {
	log.mu.Lock()
	defer log.mu.Unlock()
	return string(log.data)
}

func newDesktopVisionRuntime(dataRoot string) *desktopVisionRuntime {
	return &desktopVisionRuntime{
		dataRoot: filepath.Join(dataRoot, "vlm-runtimes", builtinVisionPackageVersion),
		llamaClient: &http.Client{
			Timeout: 0,
		},
	}
}

func (runtime *desktopVisionRuntime) Infer(
	ctx context.Context,
	descriptor builtinVisionDescriptor,
	modelDir string,
	prompt string,
	screenshot guiScreenshot,
) (string, error) {
	requestPrompt := `你是 Murong 的 GUI 视觉定位器。只分析当前截图，不推测屏幕外内容。
优先返回严格 JSON：
{"summary":"界面摘要","targetFound":true,"x":123,"y":456,"confidence":0.92,"reason":"简短依据"}
目标不存在时 targetFound=false，x 和 y 为 null。坐标必须基于输入图片像素。

用户任务：
` + prompt
	result, inferenceWidth, inferenceHeight, err := runtime.inferRaw(
		ctx,
		descriptor,
		modelDir,
		requestPrompt,
		&screenshot.Base64,
		512,
		"off",
		nil,
	)
	if err != nil {
		return "", err
	}
	return restoreDesktopVisionCoordinates(
		result,
		screenshot.Width,
		screenshot.Height,
		inferenceWidth,
		inferenceHeight,
	), nil
}

func (runtime *desktopVisionRuntime) Chat(
	ctx context.Context,
	descriptor builtinVisionDescriptor,
	modelDir string,
	prompt string,
	image *modelImageAttachment,
	maxTokens int,
	reasoningMode string,
	onChunk func(string, string),
) (string, error) {
	var encoded *string
	if image != nil && strings.TrimSpace(image.Base64Data) != "" {
		encoded = &image.Base64Data
	}
	result, _, _, err := runtime.inferRaw(
		ctx,
		descriptor,
		modelDir,
		prompt,
		encoded,
		maxTokens,
		reasoningMode,
		onChunk,
	)
	return result, err
}

func (runtime *desktopVisionRuntime) inferRaw(
	ctx context.Context,
	descriptor builtinVisionDescriptor,
	modelDir string,
	prompt string,
	encodedImage *string,
	maxTokens int,
	reasoningMode string,
	onChunk func(string, string),
) (string, int, int, error) {
	runtime.mu.Lock()
	defer runtime.mu.Unlock()
	if descriptor.Engine == "llama" {
		return runtime.inferLlamaLocked(
			ctx,
			descriptor,
			modelDir,
			prompt,
			encodedImage,
			maxTokens,
			reasoningMode,
			onChunk,
		)
	}
	runtime.closeLlamaLocked()

	if runtime.process == nil || runtime.process.tier != descriptor.Tier {
		runtime.closeLocked()
		process, err := runtime.startProcess(descriptor, modelDir)
		if err != nil {
			return "", 0, 0, err
		}
		runtime.process = process
	}
	var imagePayload []byte
	var width, height int
	if encodedImage != nil {
		var err error
		imagePayload, width, height, err = prepareDesktopVisionImage(
			*encodedImage,
			descriptor.Engine,
		)
		if err != nil {
			return "", 0, 0, err
		}
	}
	promptBytes := []byte(prompt)
	if len(promptBytes) > 1024*1024 {
		return "", 0, 0, errors.New("本地模型提示过长")
	}
	if len(imagePayload) > 128*1024*1024 {
		return "", 0, 0, errors.New("图片数据过大")
	}
	if maxTokens < 64 {
		maxTokens = 64
	}
	if maxTokens > desktopLocalMaxOutputTokens {
		maxTokens = desktopLocalMaxOutputTokens
	}

	activeProcess := runtime.process
	stopWatchdog := context.AfterFunc(ctx, func() {
		if activeProcess != nil && activeProcess.cmd.Process != nil {
			_ = activeProcess.cmd.Process.Kill()
		}
	})
	defer stopWatchdog()
	streamGuard := visionChunkGuard{}

	header := make([]byte, 28)
	binary.LittleEndian.PutUint32(header[0:4], visionProtocolMagic)
	binary.LittleEndian.PutUint32(header[4:8], uint32(len(promptBytes)))
	binary.LittleEndian.PutUint32(header[8:12], uint32(width))
	binary.LittleEndian.PutUint32(header[12:16], uint32(height))
	binary.LittleEndian.PutUint32(header[16:20], uint32(len(imagePayload)))
	binary.LittleEndian.PutUint32(header[20:24], uint32(maxTokens))
	if strings.EqualFold(strings.TrimSpace(reasoningMode), "on") {
		binary.LittleEndian.PutUint32(header[24:28], 1)
	}
	if _, err := runtime.process.stdin.Write(header); err != nil {
		runtime.closeLocked()
		return "", 0, 0, fmt.Errorf("无法向内置模型运行时发送请求：%w", err)
	}
	if _, err := runtime.process.stdin.Write(promptBytes); err != nil {
		runtime.closeLocked()
		return "", 0, 0, err
	}
	if _, err := runtime.process.stdin.Write(imagePayload); err != nil {
		runtime.closeLocked()
		return "", 0, 0, err
	}

	for {
		event, payload, err := readVisionProtocolEvent(runtime.process.stdout)
		if err != nil {
			stderr := strings.TrimSpace(runtime.process.stderr.String())
			runtime.closeLocked()
			if stderr != "" {
				return "", 0, 0, fmt.Errorf("内置模型运行时中断：%w；%s", err, truncateRunes(stderr, 1000))
			}
			return "", 0, 0, fmt.Errorf("内置模型运行时中断：%w", err)
		}
		switch event {
		case "chunk":
			kind, text, guardErr := streamGuard.Accept(visionStreamContent, payload)
			if guardErr != nil {
				runtime.closeLocked()
				return "", 0, 0, guardErr
			}
			if onChunk != nil && text != "" {
				onChunk(kind, text)
			}
		case "reasoning":
			kind, text, guardErr := streamGuard.Accept(visionStreamReasoning, payload)
			if guardErr != nil {
				runtime.closeLocked()
				return "", 0, 0, guardErr
			}
			if onChunk != nil && text != "" {
				onChunk(kind, text)
			}
		case "result":
			if strings.TrimSpace(payload) == "" {
				return "", 0, 0, errors.New("内置模型没有返回内容")
			}
			if kind, text := streamGuard.Finish(); onChunk != nil && text != "" {
				onChunk(kind, text)
			}
			return strings.TrimSpace(payload), width, height, nil
		case "error":
			return "", 0, 0, errors.New(payload)
		default:
			runtime.closeLocked()
			return "", 0, 0, errors.New(payload)
		}
	}
}

func (runtime *desktopVisionRuntime) inferLlamaLocked(
	ctx context.Context,
	descriptor builtinVisionDescriptor,
	modelDir string,
	prompt string,
	encodedImage *string,
	maxTokens int,
	reasoningMode string,
	onChunk func(string, string),
) (string, int, int, error) {
	runtime.closeProcessLocked()
	if maxTokens < 64 {
		maxTokens = 64
	}
	if maxTokens > desktopLocalMaxOutputTokens {
		maxTokens = desktopLocalMaxOutputTokens
	}
	if strings.EqualFold(strings.TrimSpace(reasoningMode), "off") {
		prompt += "\n\n请直接给出结论和必要步骤，不要输出 <think> 思考过程。"
	}
	server, err := runtime.ensureLlamaServerLocked(descriptor, modelDir)
	if err != nil {
		return "", 0, 0, err
	}

	var content any = prompt
	var width, height int
	if encodedImage != nil {
		pngPayload, imageWidth, imageHeight, imageErr := prepareDesktopVisionImage(*encodedImage, "litert")
		if imageErr != nil {
			return "", 0, 0, imageErr
		}
		width, height = imageWidth, imageHeight
		content = []map[string]any{
			{"type": "text", "text": prompt},
			{
				"type": "image_url",
				"image_url": map[string]any{
					"url": "data:image/png;base64," + base64.StdEncoding.EncodeToString(pngPayload),
				},
			},
		}
	}
	requestBody, err := json.Marshal(map[string]any{
		"model":       descriptor.Tier,
		"stream":      true,
		"max_tokens":  maxTokens,
		"temperature": 0.2,
		"messages": []map[string]any{
			{"role": "user", "content": content},
		},
	})
	if err != nil {
		return "", 0, 0, err
	}
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodPost,
		server.baseURL+"/v1/chat/completions",
		bytes.NewReader(requestBody),
	)
	if err != nil {
		return "", 0, 0, err
	}
	request.Header.Set("Content-Type", "application/json")
	response, err := runtime.llamaClient.Do(request)
	if err != nil {
		return "", 0, 0, fmt.Errorf("llama.cpp 本地服务请求失败：%w", err)
	}
	defer response.Body.Close()
	if response.StatusCode < http.StatusOK || response.StatusCode >= http.StatusMultipleChoices {
		body, _ := io.ReadAll(io.LimitReader(response.Body, 8*1024))
		return "", 0, 0, fmt.Errorf("llama.cpp 本地服务返回 %s：%s", response.Status, strings.TrimSpace(string(body)))
	}

	var contentOutput strings.Builder
	var reasoningOutput strings.Builder
	scanner := bufio.NewScanner(response.Body)
	scanner.Buffer(make([]byte, 64*1024), 8*1024*1024)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if !strings.HasPrefix(line, "data:") {
			continue
		}
		payload := strings.TrimSpace(strings.TrimPrefix(line, "data:"))
		if payload == "[DONE]" {
			break
		}
		var event struct {
			Choices []struct {
				Delta struct {
					Content          string `json:"content"`
					ReasoningContent string `json:"reasoning_content"`
				} `json:"delta"`
			} `json:"choices"`
		}
		if json.Unmarshal([]byte(payload), &event) != nil {
			continue
		}
		for _, choice := range event.Choices {
			if chunk := choice.Delta.ReasoningContent; chunk != "" {
				reasoningOutput.WriteString(chunk)
				if onChunk != nil {
					onChunk(visionStreamReasoning, chunk)
				}
			}
			if chunk := choice.Delta.Content; chunk != "" {
				contentOutput.WriteString(chunk)
				if onChunk != nil {
					onChunk(visionStreamContent, chunk)
				}
			}
		}
	}
	if err := scanner.Err(); err != nil {
		return "", 0, 0, fmt.Errorf("读取 llama.cpp 流式输出失败：%w", err)
	}
	if contentOutput.Len() == 0 && reasoningOutput.Len() == 0 {
		return "", 0, 0, errors.New("llama.cpp 本地模型没有返回内容")
	}
	if reasoningOutput.Len() > 0 {
		return "<think>" + reasoningOutput.String() + "</think>" + contentOutput.String(), width, height, nil
	}
	return contentOutput.String(), width, height, nil
}

func (runtime *desktopVisionRuntime) ensureLlamaServerLocked(
	descriptor builtinVisionDescriptor,
	modelDir string,
) (*desktopLlamaServer, error) {
	if server := runtime.llamaServer; server != nil && server.tier == descriptor.Tier &&
		server.modelDir == modelDir && server.cmd.Process != nil && server.cmd.ProcessState == nil {
		if runtime.llamaServerHealthy(server.baseURL) {
			return server, nil
		}
	}
	runtime.closeLlamaLocked()
	engineRoot, err := runtime.materialize("llama")
	if err != nil {
		return nil, err
	}
	executable := filepath.Join(engineRoot, "llama-server.exe")
	if _, err := os.Stat(executable); err != nil {
		return nil, errors.New("Windows llama.cpp 本地运行时未打包；请使用正式 Release 构建")
	}
	modelName := descriptor.llamaModelFileName()
	if modelName == "" {
		return nil, fmt.Errorf("%s 缺少 GGUF 主模型文件", descriptor.DisplayName)
	}
	modelPath := filepath.Join(modelDir, modelName)
	if _, err := os.Stat(modelPath); err != nil {
		return nil, fmt.Errorf("本地 GGUF 模型文件不存在：%w", err)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, fmt.Errorf("无法分配 llama.cpp 本地端口：%w", err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	_ = listener.Close()
	arguments := []string{
		"-m", modelPath,
		"--host", "127.0.0.1",
		"--port", strconv.Itoa(port),
		"--ctx-size", "4096",
		"--parallel", "1",
		"-ngl", "0",
	}
	if descriptor.SupportsVision {
		projectorName := descriptor.llamaProjectorFileName()
		if projectorName == "" {
			return nil, fmt.Errorf("%s 缺少视觉投影文件", descriptor.DisplayName)
		}
		projectorPath := filepath.Join(modelDir, projectorName)
		if _, err := os.Stat(projectorPath); err != nil {
			return nil, fmt.Errorf("本地视觉投影文件不存在：%w", err)
		}
		arguments = append(arguments, "--mmproj", projectorPath)
	}
	command := exec.Command(executable, arguments...)
	command.Dir = engineRoot
	prepareHiddenCommand(command)
	log := &boundedVisionLog{limit: 64 * 1024}
	command.Stdout = log
	command.Stderr = log
	if err := command.Start(); err != nil {
		return nil, fmt.Errorf("启动 llama.cpp 本地服务失败：%w", err)
	}
	server := &desktopLlamaServer{
		tier: descriptor.Tier, modelDir: modelDir,
		baseURL: "http://127.0.0.1:" + strconv.Itoa(port),
		cmd:     command, log: log,
	}
	deadline := time.Now().Add(45 * time.Second)
	for time.Now().Before(deadline) {
		if runtime.llamaServerHealthy(server.baseURL) {
			runtime.llamaServer = server
			return server, nil
		}
		if command.ProcessState != nil {
			break
		}
		time.Sleep(200 * time.Millisecond)
	}
	if command.Process != nil {
		_ = command.Process.Kill()
	}
	_ = command.Wait()
	return nil, fmt.Errorf("llama.cpp 本地服务启动超时：%s", truncateRunes(strings.TrimSpace(log.String()), 1000))
}

func (runtime *desktopVisionRuntime) llamaServerHealthy(baseURL string) bool {
	requestContext, cancel := context.WithTimeout(context.Background(), 1200*time.Millisecond)
	defer cancel()
	request, err := http.NewRequestWithContext(requestContext, http.MethodGet, baseURL+"/health", nil)
	if err != nil {
		return false
	}
	response, err := runtime.llamaClient.Do(request)
	if err != nil {
		return false
	}
	_ = response.Body.Close()
	return response.StatusCode >= http.StatusOK && response.StatusCode < http.StatusMultipleChoices
}

func (runtime *desktopVisionRuntime) Close() {
	runtime.mu.Lock()
	defer runtime.mu.Unlock()
	runtime.closeLocked()
}

func (runtime *desktopVisionRuntime) closeLocked() {
	runtime.closeLlamaLocked()
	runtime.closeProcessLocked()
}

func (runtime *desktopVisionRuntime) closeProcessLocked() {
	if runtime.process == nil {
		return
	}
	_ = runtime.process.stdin.Close()
	if runtime.process.cmd.Process != nil {
		_ = runtime.process.cmd.Process.Kill()
	}
	_ = runtime.process.cmd.Wait()
	runtime.process = nil
}

func (runtime *desktopVisionRuntime) closeLlamaLocked() {
	if runtime.llamaServer == nil {
		return
	}
	if runtime.llamaServer.cmd.Process != nil {
		_ = runtime.llamaServer.cmd.Process.Kill()
	}
	_ = runtime.llamaServer.cmd.Wait()
	runtime.llamaServer = nil
}

func (runtime *desktopVisionRuntime) startProcess(
	descriptor builtinVisionDescriptor,
	modelDir string,
) (*desktopVisionProcess, error) {
	var command *exec.Cmd
	switch descriptor.Engine {
	case "mnn":
		engineRoot, err := runtime.materialize("mnn")
		if err != nil {
			return nil, err
		}
		executable := filepath.Join(engineRoot, "murong-mnn-vlm.exe")
		if _, err := os.Stat(executable); err != nil {
			return nil, errors.New("Windows MNN 视觉运行时未打包；请使用正式 Release 构建")
		}
		cacheDir, err := runtime.prepareMNNCache(modelDir, descriptor)
		if err != nil {
			return nil, err
		}
		command = exec.Command(
			executable,
			filepath.Join(modelDir, visionMNNConfigFileName),
			cacheDir,
		)
		command.Dir = engineRoot
	case "litert":
		engineRoot, err := runtime.materialize("litert")
		if err != nil {
			return nil, err
		}
		javaExecutable := filepath.Join(engineRoot, "runtime", "bin", "java.exe")
		libPattern := filepath.Join(engineRoot, "lib", "*")
		if _, err := os.Stat(javaExecutable); err != nil {
			return nil, errors.New("Windows LiteRT-LM 视觉运行时未打包；请使用正式 Release 构建")
		}
		modelFile := descriptor.Files[0].Name
		cacheDir := filepath.Join(runtime.dataRoot, "cache", descriptor.Tier)
		if err := os.MkdirAll(cacheDir, 0o700); err != nil {
			return nil, err
		}
		command = exec.Command(
			javaExecutable,
			"-cp",
			libPattern,
			"com.murong.agent.vlm.LiteRtVisionMain",
			filepath.Join(modelDir, modelFile),
			cacheDir,
		)
		command.Dir = engineRoot
	default:
		return nil, fmt.Errorf("不支持的内置视觉引擎：%s", descriptor.Engine)
	}
	stdin, err := command.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := command.StdoutPipe()
	if err != nil {
		_ = stdin.Close()
		return nil, err
	}
	process := &desktopVisionProcess{
		tier: descriptor.Tier, engine: descriptor.Engine, cmd: command,
		stdin: stdin, stdout: bufio.NewReaderSize(stdout, 1024*1024),
		stderr: &boundedVisionLog{limit: 64 * 1024},
	}
	command.Stderr = process.stderr
	prepareHiddenCommand(command)
	if err := command.Start(); err != nil {
		return nil, err
	}
	event, payload, err := readVisionProtocolEvent(process.stdout)
	if err != nil {
		_ = command.Process.Kill()
		_ = command.Wait()
		return nil, fmt.Errorf("内置视觉运行时启动失败：%w", err)
	}
	if event != "ready" {
		_ = command.Process.Kill()
		_ = command.Wait()
		return nil, fmt.Errorf("内置视觉运行时启动失败：%s", payload)
	}
	return process, nil
}

func (runtime *desktopVisionRuntime) prepareMNNCache(
	modelDir string,
	descriptor builtinVisionDescriptor,
) (string, error) {
	fingerprint, err := desktopMNNCacheFingerprint(modelDir)
	if err != nil {
		return "", err
	}
	cacheDir := filepath.Join(
		runtime.dataRoot,
		"cache",
		visionMNNCacheSchema,
		descriptor.Tier,
		"cpu",
		fingerprint,
	)
	if err := os.MkdirAll(cacheDir, 0o700); err != nil {
		return "", fmt.Errorf("创建 MNN 指纹缓存失败：%w", err)
	}
	return cacheDir, nil
}

func desktopMNNCacheFingerprint(modelDir string) (string, error) {
	hasher := sha256.New()
	_, _ = io.WriteString(hasher, visionMNNCacheSchema)
	for _, name := range []string{
		visionMNNConfigFileName,
		"llm_config.json",
		"llm.mnn",
	} {
		path := filepath.Join(modelDir, name)
		file, err := os.Open(path)
		if err != nil {
			return "", fmt.Errorf("读取 MNN 缓存指纹文件 %s：%w", name, err)
		}
		_, _ = hasher.Write([]byte{0})
		_, _ = io.WriteString(hasher, name)
		_, copyErr := io.Copy(hasher, file)
		closeErr := file.Close()
		if copyErr != nil {
			return "", fmt.Errorf("计算 MNN 缓存指纹 %s：%w", name, copyErr)
		}
		if closeErr != nil {
			return "", fmt.Errorf("关闭 MNN 缓存指纹文件 %s：%w", name, closeErr)
		}
	}
	return fmt.Sprintf("%x", hasher.Sum(nil))[:20], nil
}

func (runtime *desktopVisionRuntime) materialize(engine string) (string, error) {
	sourceRoot := filepath.ToSlash(filepath.Join("generated", "vlm", engine))
	targetRoot := filepath.Join(runtime.dataRoot, engine)
	if _, err := fs.Stat(embeddedVisionRuntimes, sourceRoot); err != nil {
		return targetRoot, nil
	}
	err := fs.WalkDir(embeddedVisionRuntimes, sourceRoot, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		relative, err := filepath.Rel(filepath.FromSlash(sourceRoot), filepath.FromSlash(path))
		if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
			return errors.New("内置视觉运行时包含非法路径")
		}
		target := filepath.Join(targetRoot, relative)
		if entry.IsDir() {
			return os.MkdirAll(target, 0o700)
		}
		data, err := embeddedVisionRuntimes.ReadFile(path)
		if err != nil {
			return err
		}
		if existing, err := os.ReadFile(target); err == nil &&
			sha256.Sum256(existing) == sha256.Sum256(data) {
			return nil
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o700); err != nil {
			return err
		}
		partial := target + ".part"
		if err := os.WriteFile(partial, data, 0o700); err != nil {
			return err
		}
		if err := os.Remove(target); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
		return os.Rename(partial, target)
	})
	return targetRoot, err
}

func readVisionProtocolEvent(reader *bufio.Reader) (string, string, error) {
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			return "", "", err
		}
		line = strings.TrimSpace(line)
		if line == "MURONG_VLM_READY" {
			return "ready", "", nil
		}
		var event string
		switch {
		case strings.HasPrefix(line, "MURONG_VLM_CHUNK_BEGIN "):
			event = "chunk"
		case strings.HasPrefix(line, "MURONG_VLM_REASONING_CHUNK_BEGIN "):
			event = "reasoning"
		case strings.HasPrefix(line, "MURONG_VLM_RESULT_BEGIN "):
			event = "result"
		case strings.HasPrefix(line, "MURONG_VLM_ERROR_BEGIN "):
			event = "error"
		case strings.HasPrefix(line, "MURONG_VLM_FATAL_BEGIN "):
			event = "fatal"
		default:
			continue
		}
		fields := strings.Fields(line)
		if len(fields) != 2 {
			return "", "", errors.New("视觉运行时返回了无效长度")
		}
		length, err := strconv.Atoi(fields[1])
		if err != nil || length < 0 || length > 16*1024*1024 {
			return "", "", errors.New("视觉运行时返回长度超出限制")
		}
		payload := make([]byte, length)
		if _, err := io.ReadFull(reader, payload); err != nil {
			return "", "", err
		}
		_, _ = reader.ReadString('\n')
		end, err := reader.ReadString('\n')
		if err != nil {
			return "", "", err
		}
		if strings.TrimSpace(end) != "MURONG_VLM_PAYLOAD_END" {
			return "", "", errors.New("视觉运行时响应边界无效")
		}
		return event, string(payload), nil
	}
}

func prepareDesktopVisionImage(encoded string, engine string) ([]byte, int, int, error) {
	raw, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return nil, 0, 0, errors.New("截图 Base64 无法解码")
	}
	source, _, err := image.Decode(bytes.NewReader(raw))
	if err != nil {
		return nil, 0, 0, errors.New("截图图像无法解码")
	}
	bounds := source.Bounds()
	width, height := bounds.Dx(), bounds.Dy()
	if width <= 0 || height <= 0 {
		return nil, 0, 0, errors.New("截图尺寸无效")
	}
	scale := 1.0
	if max(width, height) > visionMaxImageSide {
		scale = float64(visionMaxImageSide) / float64(max(width, height))
	}
	targetWidth := max(1, int(float64(width)*scale))
	targetHeight := max(1, int(float64(height)*scale))
	target := image.NewNRGBA(image.Rect(0, 0, targetWidth, targetHeight))
	xdraw.CatmullRom.Scale(target, target.Bounds(), source, bounds, xdraw.Over, nil)

	if engine == "litert" {
		var output bytes.Buffer
		if err := png.Encode(&output, target); err != nil {
			return nil, 0, 0, err
		}
		return output.Bytes(), targetWidth, targetHeight, nil
	}
	bgr := make([]byte, targetWidth*targetHeight*3)
	index := 0
	for y := 0; y < targetHeight; y++ {
		for x := 0; x < targetWidth; x++ {
			pixel := color.NRGBAModel.Convert(target.At(x, y)).(color.NRGBA)
			bgr[index] = pixel.B
			bgr[index+1] = pixel.G
			bgr[index+2] = pixel.R
			index += 3
		}
	}
	return bgr, targetWidth, targetHeight, nil
}

func restoreDesktopVisionCoordinates(
	result string,
	originalWidth int,
	originalHeight int,
	inferenceWidth int,
	inferenceHeight int,
) string {
	if originalWidth <= 0 || originalHeight <= 0 ||
		inferenceWidth <= 0 || inferenceHeight <= 0 ||
		(originalWidth == inferenceWidth && originalHeight == inferenceHeight) {
		return result
	}
	start, end := strings.Index(result, "{"), strings.LastIndex(result, "}")
	if start < 0 || end <= start {
		return result
	}
	var parsed map[string]any
	if json.Unmarshal([]byte(result[start:end+1]), &parsed) != nil {
		return result
	}
	for _, coordinate := range []struct {
		key       string
		original  int
		inference int
	}{
		{key: "x", original: originalWidth, inference: inferenceWidth},
		{key: "y", original: originalHeight, inference: inferenceHeight},
	} {
		if value, ok := parsed[coordinate.key].(float64); ok {
			parsed[coordinate.key] = int(math.Round(
				value * float64(coordinate.original) / float64(coordinate.inference),
			))
		}
	}
	data, err := json.Marshal(parsed)
	if err != nil {
		return result
	}
	return string(data)
}

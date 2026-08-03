package main

import (
	"archive/tar"
	"bytes"
	"compress/bzip2"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// DesktopVoiceEngine wraps the Sherpa-onnx Windows binaries that are shipped in
// the official release archive. The exact same VITS TTS model and streaming
// Zipformer ASR model are used on Android, so the desktop agent gets identical,
// fully offline read-aloud and microphone dictation without depending on any
// vendor system engine.
type DesktopVoiceEngine struct {
	root         string
	sherpaDir    string
	ttsModelDir  string
	asrModelDir  string
	sileroVad    string
	mu           sync.Mutex
	busy         bool
	statusCall   func(VoiceRuntimeStatus)
	recorder     *voiceRecorder
	speaking     bool
	speakStop    atomic.Bool
	speakMu      sync.Mutex
	listening    bool
	partialText  string
	streamRec    voiceStreamHandle
	streamServer *exec.Cmd
	streamPort   int
	lastVoiceAt  time.Time
	finalHandler func(string)
	ttsEngine    *voiceTtsEngine
	lastError    string
}

type VoiceRuntimeStatus struct {
	RuntimeInstalled bool   `json:"runtimeInstalled"`
	TtsModelInstalled bool  `json:"ttsModelInstalled"`
	AsrModelInstalled bool  `json:"asrModelInstalled"`
	Busy             bool   `json:"busy"`
	Message          string `json:"message"`
	Recording        bool   `json:"recording"`
	Listening        bool   `json:"listening"`
	PartialText      string `json:"partialText"`
	Speaking         bool   `json:"speaking"`
	LastError        string `json:"lastError"`
}

// voiceStreamHandle is implemented by the platform streaming recognizer so the
// cross-platform engine can tear it down without knowing its concrete type.
type voiceStreamHandle interface {
	finish() (string, error)
}

const (
	sherpaOnnxVersion      = "1.13.2"
	sherpaWinRuntimeURL    = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.2/sherpa-onnx-v1.13.2-win-x64-shared-MD-Release.tar.bz2"
	sherpaWinRuntimeSHA256 = "f91f488186e797dd9e9bc2a3dcbe18ddd244627af5d9fa3707f7a2f3bc4032ce"

	ttsModelURL    = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-zh_en.tar.bz2"
	ttsModelSHA256 = "e58351ed7149f290a54534538badd4077cdbe6fddc964b24d0bee870415d1514"

	asrModelURL    = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2"
	asrModelSHA256 = "8a6fca056e1a342546edd78be4d50274e2c01898e7b8ae8fc336f6410319c399"

	sileroVadURL    = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
	sileroVadSHA256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"

	voiceDownloadTimeout = 30 * time.Minute
)

func newDesktopVoiceEngine(
	root string,
	statusCall func(VoiceRuntimeStatus),
	finalHandler func(string),
) *DesktopVoiceEngine {
	engine := &DesktopVoiceEngine{
		root:        root,
		sherpaDir:   filepath.Join(root, "sherpa"),
		ttsModelDir: filepath.Join(root, "tts-model"),
		asrModelDir: filepath.Join(root, "asr-model"),
		sileroVad:   filepath.Join(root, "silero_vad.onnx"),
		statusCall:  statusCall,
		finalHandler: finalHandler,
	}
	engine.recorder = newVoiceRecorder(filepath.Join(root, "recordings"))
	return engine
}

func (e *DesktopVoiceEngine) Status() VoiceRuntimeStatus {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.snapshotLocked()
}

func (e *DesktopVoiceEngine) snapshotLocked() VoiceRuntimeStatus {
	return VoiceRuntimeStatus{
		RuntimeInstalled:  voiceFileReady(filepath.Join(e.sherpaDir, "bin", "sherpa-onnx-offline-tts.exe")) &&
			voiceFileReady(filepath.Join(e.sherpaDir, "bin", "sherpa-onnx-vad-with-online-asr.exe")),
		TtsModelInstalled: voiceFileReady(filepath.Join(e.ttsModelDir, "model.onnx")) &&
			voiceFileReady(filepath.Join(e.ttsModelDir, "tokens.txt")),
		AsrModelInstalled: voiceFileReady(filepath.Join(e.asrModelDir, "encoder.int8.onnx")) &&
			voiceFileReady(filepath.Join(e.asrModelDir, "tokens.txt")) &&
			voiceFileReady(e.sileroVad),
		Busy:      e.busy,
		Message:   e.lastError,
		Recording: e.recorder.isRecording(),
		Listening: e.listening,
		PartialText: e.partialText,
		Speaking:  e.speaking,
		LastError: e.lastError,
	}
}

func (e *DesktopVoiceEngine) emitLocked() {
	if e.statusCall == nil {
		return
	}
	e.statusCall(e.snapshotLocked())
}

func voiceFileReady(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular() && info.Size() > 0
}

// InstallRuntime downloads and verifies the Sherpa-onnx Windows shared package.
func (e *DesktopVoiceEngine) InstallRuntime(ctx context.Context) error {
	e.mu.Lock()
	if e.busy {
		e.mu.Unlock()
		return errors.New("语音引擎正在安装中")
	}
	e.busy = true
	e.lastError = ""
	e.emitLocked()
	e.mu.Unlock()

	err := e.installRuntimeLocked(ctx)

	e.mu.Lock()
	e.busy = false
	if err != nil {
		e.lastError = err.Error()
	}
	e.emitLocked()
	e.mu.Unlock()
	return err
}

func (e *DesktopVoiceEngine) installRuntimeLocked(ctx context.Context) error {
	if voiceFileReady(filepath.Join(e.sherpaDir, "bin", "sherpa-onnx-offline-tts.exe")) &&
		voiceFileReady(filepath.Join(e.sherpaDir, "bin", "sherpa-onnx-vad-with-online-asr.exe")) {
		return nil
	}
	if err := os.MkdirAll(e.root, 0o700); err != nil {
		return err
	}
	archive := filepath.Join(e.root, "sherpa-win.tar.bz2")
	if err := e.downloadVerified(ctx, sherpaWinRuntimeURL, sherpaWinRuntimeSHA256, archive, 120<<20); err != nil {
		return err
	}
	staging := filepath.Join(e.root, ".sherpa-staging")
	if err := os.RemoveAll(staging); err != nil {
		return err
	}
	if err := extractTarBz2(archive, staging, "", 400, 300<<20); err != nil {
		return err
	}
	packed, err := firstDirectory(staging)
	if err != nil {
		return err
	}
	target := e.sherpaDir
	if err := os.RemoveAll(target); err != nil {
		return err
	}
	if err := copyTree(packed, target); err != nil {
		return err
	}
	_ = os.RemoveAll(staging)
	_ = os.Remove(archive)
	return nil
}

// InstallTtsModel downloads the VITS zh-ll Chinese voice model.
func (e *DesktopVoiceEngine) InstallTtsModel(ctx context.Context) error {
	e.mu.Lock()
	if e.busy {
		e.mu.Unlock()
		return errors.New("语音引擎正在安装中")
	}
	e.busy = true
	e.lastError = ""
	e.emitLocked()
	e.mu.Unlock()

	err := e.installTtsModelLocked(ctx)

	e.mu.Lock()
	e.busy = false
	if err != nil {
		e.lastError = err.Error()
	}
	e.emitLocked()
	e.mu.Unlock()
	return err
}

func (e *DesktopVoiceEngine) installTtsModelLocked(ctx context.Context) error {
	if voiceFileReady(filepath.Join(e.ttsModelDir, "model.onnx")) {
		return nil
	}
	if err := os.MkdirAll(e.root, 0o700); err != nil {
		return err
	}
	archive := filepath.Join(e.root, "tts-model.tar.bz2")
	if err := e.downloadVerified(ctx, ttsModelURL, ttsModelSHA256, archive, 190<<20); err != nil {
		return err
	}
	staging := filepath.Join(e.root, ".tts-staging")
	if err := os.RemoveAll(staging); err != nil {
		return err
	}
	if err := extractTarBz2(archive, staging, "", 120, 180<<20); err != nil {
		return err
	}
	packed, err := firstDirectory(staging)
	if err != nil {
		return err
	}
	target := e.ttsModelDir
	if err := os.RemoveAll(target); err != nil {
		return err
	}
	if err := copyTree(packed, target); err != nil {
		return err
	}
	_ = os.RemoveAll(staging)
	_ = os.Remove(archive)
	return nil
}

// InstallAsrModel downloads the streaming Zipformer recognition model plus the
// Silero VAD model used to find speech segments in a recorded WAV file.
func (e *DesktopVoiceEngine) InstallAsrModel(ctx context.Context) error {
	e.mu.Lock()
	if e.busy {
		e.mu.Unlock()
		return errors.New("语音引擎正在安装中")
	}
	e.busy = true
	e.lastError = ""
	e.emitLocked()
	e.mu.Unlock()

	err := e.installAsrModelLocked(ctx)

	e.mu.Lock()
	e.busy = false
	if err != nil {
		e.lastError = err.Error()
	}
	e.emitLocked()
	e.mu.Unlock()
	return err
}

func (e *DesktopVoiceEngine) installAsrModelLocked(ctx context.Context) error {
	if voiceFileReady(filepath.Join(e.asrModelDir, "encoder.int8.onnx")) &&
		voiceFileReady(filepath.Join(e.asrModelDir, "tokens.txt")) &&
		voiceFileReady(e.sileroVad) {
		return nil
	}
	if err := os.MkdirAll(e.root, 0o700); err != nil {
		return err
	}
	if !voiceFileReady(e.sileroVad) {
		if err := e.downloadVerified(ctx, sileroVadURL, sileroVadSHA256, e.sileroVad, 4<<20); err != nil {
			return err
		}
	}
	archive := filepath.Join(e.root, "asr-model.tar.bz2")
	if err := e.downloadVerified(ctx, asrModelURL, asrModelSHA256, archive, 160<<20); err != nil {
		return err
	}
	staging := filepath.Join(e.root, ".asr-staging")
	if err := os.RemoveAll(staging); err != nil {
		return err
	}
	if err := extractTarBz2(archive, staging, "", 160, 220<<20); err != nil {
		return err
	}
	packed, err := firstDirectory(staging)
	if err != nil {
		return err
	}
	target := e.asrModelDir
	if err := os.RemoveAll(target); err != nil {
		return err
	}
	if err := copyTree(packed, target); err != nil {
		return err
	}
	_ = os.RemoveAll(staging)
	_ = os.Remove(archive)
	return nil
}

func (e *DesktopVoiceEngine) downloadVerified(
	ctx context.Context,
	rawURL string,
	expectedSHA256 string,
	target string,
	maxBytes int64,
) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return err
	}
	request.Header.Set("User-Agent", "Murong-Desktop-Agent/"+desktopAgentVersion)
	client := &http.Client{Timeout: voiceDownloadTimeout}
	response, err := client.Do(request)
	if err != nil {
		return fmt.Errorf("下载失败：%w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("下载失败：HTTP %d", response.StatusCode)
	}
	if response.ContentLength > maxBytes {
		return errors.New("下载超过安全大小限制")
	}
	output, err := os.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	hasher := sha256.New()
	written, copyErr := io.Copy(io.MultiWriter(output, hasher), io.LimitReader(response.Body, maxBytes+1))
	closeErr := output.Close()
	if copyErr != nil {
		return copyErr
	}
	if closeErr != nil {
		return closeErr
	}
	if written > maxBytes {
		return errors.New("下载超过安全大小限制")
	}
	actual := hex.EncodeToString(hasher.Sum(nil))
	if strings.EqualFold(actual, expectedSHA256) {
		return nil
	}
	// The baseline pins the version this release was tested with; if upstream
	// re-packaged the asset, fall back to the official checksum so users are not
	// stuck on a stale hash.
	if official, fetchErr := fetchOfficialChecksum(rawURL); fetchErr == nil {
		if strings.EqualFold(actual, official) {
			return nil
		}
	}
	_ = os.Remove(target)
	return errors.New("SHA-256 校验失败，已删除不完整的下载文件")
}

func fetchOfficialChecksum(rawURL string) (string, error) {
	parts := strings.Split(rawURL, "/releases/download/")
	if len(parts) != 2 {
		return "", errors.New("无法从下载地址解析官方校验文件")
	}
	segments := strings.SplitN(parts[1], "/", 2)
	if len(segments) != 2 || strings.TrimSpace(segments[0]) == "" || strings.TrimSpace(segments[1]) == "" {
		return "", errors.New("无法从下载地址解析官方校验文件")
	}
	releaseTag := segments[0]
	fileName := segments[1]
	checksumURL := "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
		releaseTag + "/checksum.txt"
	request, err := http.NewRequest(http.MethodGet, checksumURL, nil)
	if err != nil {
		return "", err
	}
	request.Header.Set("User-Agent", "Murong-Desktop-Agent/"+desktopAgentVersion)
	client := &http.Client{Timeout: 60 * time.Second}
	response, err := client.Do(request)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return "", fmt.Errorf("官方校验文件 HTTP %d", response.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, 4<<20))
	if err != nil {
		return "", err
	}
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, fileName+"\t") {
			hash := strings.TrimSpace(strings.TrimPrefix(line, fileName+"\t"))
			if len(hash) == sha256.Size*2 {
				return strings.ToLower(hash), nil
			}
		}
	}
	return "", errors.New("官方校验文件不包含该资源")
}

func extractTarBz2(archivePath, destination, stripPrefix string, maxFiles int, maxBytes int64) error {
	file, err := os.Open(archivePath)
	if err != nil {
		return err
	}
	defer file.Close()
	bzipReader := bzip2.NewReader(file)
	reader := tar.NewReader(bzipReader)
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
			return errors.New("语音资源包包含越界路径")
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
				return errors.New("语音资源包超过解压安全限制")
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
				return errors.New("语音资源包文件读取不完整")
			}
			if closeErr != nil {
				return closeErr
			}
		default:
			return errors.New("语音资源包包含不支持的链接或设备文件")
		}
	}
	if files == 0 {
		return errors.New("语音资源包为空")
	}
	return nil
}

func firstDirectory(root string) (string, error) {
	entries, err := os.ReadDir(root)
	if err != nil {
		return "", err
	}
	for _, entry := range entries {
		if entry.IsDir() {
			return filepath.Join(root, entry.Name()), nil
		}
	}
	return root, nil
}

func copyTree(source, target string) error {
	return filepath.Walk(source, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		relative, relErr := filepath.Rel(source, path)
		if relErr != nil {
			return relErr
		}
		destination := filepath.Join(target, relative)
		if info.IsDir() {
			return os.MkdirAll(destination, 0o700)
		}
		if !info.Mode().IsRegular() {
			return nil
		}
		if err := os.MkdirAll(filepath.Dir(destination), 0o700); err != nil {
			return err
		}
		data, readErr := os.ReadFile(path)
		if readErr != nil {
			return readErr
		}
		return os.WriteFile(destination, data, 0o600)
	})
}

// Speak synthesizes the text with the offline TTS model and plays it through
// the default audio device. Synthesis and playback run in a pipeline: the next
// sentence is generated while the current one plays, so the first sound starts
// quickly and there are no gaps between sentences.
func (e *DesktopVoiceEngine) Speak(text string, rate float64) error {
	e.mu.Lock()
	if !voiceFileReady(filepath.Join(e.ttsModelDir, "model.onnx")) {
		e.mu.Unlock()
		return errors.New("离线朗读模型尚未安装，请先在语音设置中安装")
	}
	if e.speaking {
		_ = stopWavPlayback()
		e.speaking = false
	}
	e.speaking = true
	e.emitLocked()
	e.mu.Unlock()

	segments := splitVoiceSegments(text)
	if len(segments) == 0 {
		e.mu.Lock()
		e.speaking = false
		e.emitLocked()
		e.mu.Unlock()
		return errors.New("没有可朗读的文本")
	}
	speed := rate
	if speed <= 0 {
		speed = 1.0
	}
	if speed < 0.5 {
		speed = 0.5
	}
	if speed > 2.0 {
		speed = 2.0
	}
	e.speakStop.Store(false)
	go func() {
		e.speakMu.Lock()
		defer e.speakMu.Unlock()
		e.speakPipeline(segments, speed)
	}()
	return nil
}

func (e *DesktopVoiceEngine) speakPipeline(segments []string, speed float64) {
	tts, err := e.ensureTtsEngine()
	if err != nil {
		e.mu.Lock()
		e.lastError = err.Error()
		e.mu.Unlock()
		e.finishSpeaking()
		return
	}

	playChannel := make(chan string, 4)
	playDone := make(chan struct{})
	go func() {
		defer close(playDone)
		for path := range playChannel {
			if e.speakStop.Load() {
				break
			}
			_ = playWavSync(path)
		}
	}()

	for _, segment := range segments {
		if e.speakStop.Load() {
			break
		}
		samples, rate, generateErr := tts.generate(segment, speed)
		if generateErr != nil {
			e.mu.Lock()
			e.lastError = generateErr.Error()
			e.mu.Unlock()
			break
		}
		if len(samples) == 0 {
			continue
		}
		path := filepath.Join(e.root, "recordings", fmt.Sprintf("tts-%d.wav", time.Now().UnixMilli()))
		if err := writeFloat32Wav(path, samples, rate); err != nil {
			e.mu.Lock()
			e.lastError = err.Error()
			e.mu.Unlock()
			break
		}
		playChannel <- path
	}
	close(playChannel)
	<-playDone
	e.finishSpeaking()
}

func (e *DesktopVoiceEngine) finishSpeaking() {
	e.mu.Lock()
	e.speaking = false
	e.emitLocked()
	e.mu.Unlock()
}

func splitVoiceSegments(text string) []string {
	var segments []string
	var current strings.Builder
	runes := 0
	flush := func() {
		trimmed := strings.TrimSpace(current.String())
		if trimmed != "" {
			segments = append(segments, trimmed)
		}
		current.Reset()
		runes = 0
	}
	for _, r := range text {
		current.WriteRune(r)
		runes++
		if runes >= 4 && (r == '。' || r == '！' || r == '？' || r == '；' || r == '\n') {
			flush()
		} else if runes >= 60 {
			flush()
		}
	}
	if current.Len() > 0 {
		flush()
	}
	return segments
}

func (e *DesktopVoiceEngine) StopSpeaking() {
	e.speakStop.Store(true)
	_ = stopWavPlayback()
	e.finishSpeaking()
}

// StartRecording begins capturing microphone audio (16 kHz mono PCM).
func (e *DesktopVoiceEngine) StartRecording() error {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.recorder.isRecording() {
		return nil
	}
	if err := e.recorder.start(nil); err != nil {
		e.lastError = err.Error()
		e.emitLocked()
		return err
	}
	e.lastError = ""
	e.emitLocked()
	return nil
}

// StopRecordingAndRecognize stops the microphone capture and runs offline
// recognition over the recorded audio. It returns the recognized text.
func (e *DesktopVoiceEngine) StopRecordingAndRecognize() (string, error) {
	e.mu.Lock()
	recording := e.recorder.isRecording()
	e.mu.Unlock()
	if !recording {
		return "", errors.New("当前没有正在进行的录音")
	}
	wavPath, err := e.recorder.stop()
	if err != nil {
		e.mu.Lock()
		e.lastError = err.Error()
		e.emitLocked()
		e.mu.Unlock()
		return "", err
	}
	text, err := e.RecognizeWav(wavPath)
	if err != nil {
		e.mu.Lock()
		e.lastError = err.Error()
		e.emitLocked()
		e.mu.Unlock()
		return "", err
	}
	return text, nil
}

var vadResultPattern = regexp.MustCompile(`vad segment\(\d+:[^)]*\) results:\s*(.+)`)

// RecognizeWav runs VAD + streaming Zipformer recognition over a WAV file.
func (e *DesktopVoiceEngine) RecognizeWav(wavPath string) (string, error) {
	e.mu.Lock()
	if !voiceFileReady(filepath.Join(e.asrModelDir, "encoder.int8.onnx")) {
		e.mu.Unlock()
		return "", errors.New("离线语音识别模型尚未安装，请先在语音设置中安装")
	}
	engine := e
	e.mu.Unlock()

	vadExe := filepath.Join(engine.sherpaDir, "bin", "sherpa-onnx-vad-with-online-asr.exe")
	args := []string{
		"--silero-vad-model=" + engine.sileroVad,
		"--tokens=" + filepath.Join(engine.asrModelDir, "tokens.txt"),
		"--encoder=" + filepath.Join(engine.asrModelDir, "encoder.int8.onnx"),
		"--decoder=" + filepath.Join(engine.asrModelDir, "decoder.onnx"),
		"--joiner=" + filepath.Join(engine.asrModelDir, "joiner.int8.onnx"),
		"--provider=cpu",
		"--num-threads=2",
		"--enable-endpoint=false",
		wavPath,
	}
	command := exec.Command(vadExe, args...)
	hideVoiceChildWindow(command)
	command.Env = append(os.Environ(),
		"PATH="+filepath.Join(engine.sherpaDir, "bin")+";"+filepath.Join(engine.sherpaDir, "lib")+";"+os.Getenv("PATH"),
	)
	var outputBuffer bytes.Buffer
	command.Stdout = &outputBuffer
	command.Stderr = &outputBuffer
	runErr := command.Run()
	if runErr != nil {
		return "", fmt.Errorf("离线语音识别失败：%v：%s", runErr, lastVoiceOutputLine(outputBuffer.String()))
	}
	var segments []string
	for _, line := range strings.Split(outputBuffer.String(), "\n") {
		match := vadResultPattern.FindStringSubmatch(strings.TrimSpace(line))
		if len(match) == 2 {
			text := strings.TrimSpace(match[1])
			if text != "" {
				segments = append(segments, text)
			}
		}
	}
	if len(segments) == 0 {
		return "", errors.New("没有识别到语音，请靠近麦克风再试一次")
	}
	return strings.Join(segments, ""), nil
}

func lastVoiceOutputLine(output string) string {
	lines := strings.FieldsFunc(output, func(r rune) bool { return r == '\n' || r == '\r' })
	if len(lines) == 0 {
		return ""
	}
	line := strings.TrimSpace(lines[len(lines)-1])
	if len(line) > 240 {
		return line[:240]
	}
	return line
}

func (e *DesktopVoiceEngine) Close() {
	_ = stopWavPlayback()
	e.recorder.close()
	e.mu.Lock()
	if e.streamServer != nil {
		_ = e.streamServer.Process.Kill()
		e.streamServer = nil
		e.streamPort = 0
	}
	if e.ttsEngine != nil {
		e.ttsEngine.close()
		e.ttsEngine = nil
	}
	e.mu.Unlock()
}

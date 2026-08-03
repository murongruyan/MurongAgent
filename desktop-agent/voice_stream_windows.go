//go:build windows

package main

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"math/rand"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

// voiceStreamRecognizer wraps the official sherpa-onnx online websocket server
// so microphone PCM can be decoded incrementally: partial results are delivered
// while the user is still speaking, just like on Android.
type voiceStreamRecognizer struct {
	conn           *websocket.Conn
	mu             sync.Mutex
	partialHandler func(string)
	partial        string
	done           chan struct{}
}

// ensureStreamServer keeps one sherpa websocket ASR server alive so the model
// is loaded only once; later dictation sessions connect instantly.
func (e *DesktopVoiceEngine) ensureStreamServer() (int, error) {
	e.mu.Lock()
	if e.streamServer != nil && e.streamPort > 0 {
		port := e.streamPort
		e.mu.Unlock()
		return port, nil
	}
	e.mu.Unlock()

	port := 22000 + rand.Intn(20000)
	wsExe := filepath.Join(e.sherpaDir, "bin", "sherpa-onnx-online-websocket-server.exe")
	if !voiceFileReady(wsExe) {
		return 0, errors.New("离线识别引擎未安装")
	}
	args := []string{
		"--port=" + strconv.Itoa(port),
		"--tokens=" + filepath.Join(e.asrModelDir, "tokens.txt"),
		"--encoder=" + filepath.Join(e.asrModelDir, "encoder.int8.onnx"),
		"--decoder=" + filepath.Join(e.asrModelDir, "decoder.onnx"),
		"--joiner=" + filepath.Join(e.asrModelDir, "joiner.int8.onnx"),
		"--num-work-threads=2",
		"--provider=cpu",
	}
	command := exec.Command(wsExe, args...)
	hideVoiceChildWindow(command)
	command.Env = append(os.Environ(),
		"PATH="+filepath.Join(e.sherpaDir, "bin")+";"+filepath.Join(e.sherpaDir, "lib")+";"+os.Getenv("PATH"),
	)
	if err := command.Start(); err != nil {
		return 0, fmt.Errorf("离线识别服务启动失败：%w", err)
	}

	ctx := context.Background()
	deadline := time.Now().Add(45 * time.Second)
	ready := false
	for time.Now().Before(deadline) {
		candidate, _, dialErr := websocket.Dial(ctx, "ws://127.0.0.1:"+strconv.Itoa(port), nil)
		if dialErr == nil {
			_ = candidate.Close(websocket.StatusNormalClosure, "")
			ready = true
			break
		}
		time.Sleep(250 * time.Millisecond)
	}
	if !ready {
		_ = command.Process.Kill()
		return 0, errors.New("离线识别服务连接超时")
	}
	e.mu.Lock()
	e.streamServer = command
	e.streamPort = port
	e.mu.Unlock()
	return port, nil
}

func (e *DesktopVoiceEngine) connectStreamRecognizer(
	partial func(string),
) (*voiceStreamRecognizer, error) {
	port, err := e.ensureStreamServer()
	if err != nil {
		return nil, err
	}
	ctx := context.Background()
	conn, _, dialErr := websocket.Dial(ctx, "ws://127.0.0.1:"+strconv.Itoa(port), nil)
	if dialErr != nil {
		return nil, fmt.Errorf("离线识别连接失败：%w", dialErr)
	}

	recognizer := &voiceStreamRecognizer{
		conn:           conn,
		partialHandler: partial,
		done:           make(chan struct{}),
	}
	go recognizer.readLoop()
	return recognizer, nil
}

// feed converts int16 PCM into the float32 samples expected by the server and
// sends them over the websocket. Called from the recorder drain goroutine.
func (sr *voiceStreamRecognizer) feed(pcmInt16 []byte) {
	count := len(pcmInt16) / 2
	if count == 0 {
		return
	}
	floats := make([]byte, count*4)
	for index := 0; index < count; index++ {
		sample := int16(binary.LittleEndian.Uint16(pcmInt16[index*2:]))
		value := float32(sample) / 32768.0
		binary.LittleEndian.PutUint32(floats[index*4:], math.Float32bits(value))
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_ = sr.conn.Write(ctx, websocket.MessageBinary, floats)
}

func (sr *voiceStreamRecognizer) readLoop() {
	defer close(sr.done)
	ctx := context.Background()
	for {
		_, data, err := sr.conn.Read(ctx)
		if err != nil {
			return
		}
		message := string(data)
		if message == "Done!" {
			return
		}
		var decoded struct {
			Text string `json:"text"`
		}
		if err := json.Unmarshal(data, &decoded); err != nil || strings.TrimSpace(decoded.Text) == "" {
			continue
		}
		message = decoded.Text
		sr.mu.Lock()
		sr.partial = message
		handler := sr.partialHandler
		sr.mu.Unlock()
		if handler != nil {
			handler(message)
		}
	}
}

// finish signals the end of audio, waits for the final result, and tears down
// the server process.
func (sr *voiceStreamRecognizer) finish() (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	err := sr.conn.Write(ctx, websocket.MessageText, []byte("Done"))
	cancel()
	if err != nil {
		return "", errors.New("离线识别结束失败")
	}
	select {
	case <-sr.done:
	case <-time.After(12 * time.Second):
	}
	sr.mu.Lock()
	final := sr.partial
	sr.mu.Unlock()
	_ = sr.conn.Close(websocket.StatusNormalClosure, "")
	return final, nil
}

func normalizeRecognizedText(text string) string {
	// The Zipformer transducer emits one space between CJK tokens; strip them
	// while keeping punctuation produced by the model.
	return strings.ReplaceAll(strings.TrimSpace(text), " ", "")
}

// StartListening begins microphone capture with incremental offline
// recognition; partial results stream through the status callback.
func (e *DesktopVoiceEngine) StartListening() error {
	e.mu.Lock()
	if e.listening {
		e.mu.Unlock()
		return nil
	}
	if !voiceFileReady(filepath.Join(e.asrModelDir, "encoder.int8.onnx")) {
		e.mu.Unlock()
		return errors.New("离线语音识别模型尚未安装，请先在语音设置中安装")
	}
	e.mu.Unlock()

	recognizer, err := e.connectStreamRecognizer(func(text string) {
		e.mu.Lock()
		e.partialText = text
		e.emitLocked()
		e.mu.Unlock()
	})
	if err != nil {
		e.mu.Lock()
		e.lastError = err.Error()
		e.emitLocked()
		e.mu.Unlock()
		return err
	}
	e.mu.Lock()
	e.lastVoiceAt = time.Now()
	e.mu.Unlock()
	feed := func(pcm []byte) {
		recognizer.feed(pcm)
		if rmsOfPcm(pcm) > 0.004 {
			e.mu.Lock()
			e.lastVoiceAt = time.Now()
			e.mu.Unlock()
		}
	}
	if err := e.recorder.start(feed); err != nil {
		_, _ = recognizer.finish()
		e.mu.Lock()
		e.lastError = err.Error()
		e.emitLocked()
		e.mu.Unlock()
		return err
	}
	e.mu.Lock()
	e.listening = true
	e.streamRec = recognizer
	e.partialText = ""
	e.lastError = ""
	e.emitLocked()
	e.mu.Unlock()
	go e.autoStopWatcher()
	return nil
}

// autoStopWatcher ends dictation after a period of silence so the user does not
// have to click stop, and stale silence never degrades the partial text.
func (e *DesktopVoiceEngine) autoStopWatcher() {
	for {
		time.Sleep(400 * time.Millisecond)
		e.mu.Lock()
		listening := e.listening
		idle := time.Since(e.lastVoiceAt)
		handler := e.finalHandler
		e.mu.Unlock()
		if !listening {
			return
		}
		if idle >= 2500*time.Millisecond {
			text, _ := e.StopListeningAndGetFinal()
			if handler != nil && text != "" {
				handler(text)
			}
			return
		}
	}
}

func rmsOfPcm(pcm []byte) float64 {
	count := len(pcm) / 2
	if count == 0 {
		return 0
	}
	var sum float64
	for index := 0; index < count; index++ {
		sample := float64(int16(binary.LittleEndian.Uint16(pcm[index*2:]))) / 32768.0
		sum += sample * sample
	}
	return math.Sqrt(sum / float64(count))
}

// StopListeningAndGetFinal stops the microphone, finalizes recognition, and
// returns the recognized text with CJK spacing normalized.
func (e *DesktopVoiceEngine) StopListeningAndGetFinal() (string, error) {
	e.mu.Lock()
	recognizer := e.streamRec
	listening := e.listening
	e.mu.Unlock()
	if !listening || recognizer == nil {
		return "", errors.New("当前没有正在进行的语音输入")
	}
	_, stopErr := e.recorder.stop()
	text, finishErr := recognizer.finish()
	e.mu.Lock()
	e.streamRec = nil
	e.listening = false
	e.partialText = ""
	if finishErr != nil {
		e.lastError = finishErr.Error()
	}
	e.emitLocked()
	e.mu.Unlock()
	text = normalizeRecognizedText(text)
	if stopErr != nil {
		return text, stopErr
	}
	return text, finishErr
}

//go:build windows

package main

import (
	"encoding/binary"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

// hideVoiceChildWindow prevents the Sherpa CLI helpers from flashing a console
// window while the agent is reading aloud or dictating.
func hideVoiceChildWindow(command *exec.Cmd) {
	command.SysProcAttr = &syscall.SysProcAttr{
		CreationFlags: createNoWindow,
	}
}

var (
	winmm            = syscall.NewLazyDLL("winmm.dll")
	playSound        = winmm.NewProc("PlaySoundW")
	waveInOpen       = winmm.NewProc("waveInOpen")
	waveInPrepareHdr = winmm.NewProc("waveInPrepareHeader")
	waveInAddBuffer  = winmm.NewProc("waveInAddBuffer")
	waveInStart      = winmm.NewProc("waveInStart")
	waveInStop       = winmm.NewProc("waveInStop")
	waveInReset      = winmm.NewProc("waveInReset")
	waveInUnprepare  = winmm.NewProc("waveInUnprepareHeader")
	waveInClose      = winmm.NewProc("waveInClose")
)

const (
	sndFilename  = 0x00020000
	sndAsync     = 0x0001
	sndSync      = 0x0000
	sndNoDefault = 0x0002

	waveMapper        = uintptr(0xFFFFFFFF)
	callBackNull      = 0
	waveFormatPCM     = 1
	whdrDone          = 0x00000001
	mmSysErrorBase    = 0
	waveInOpenErr     = 0
	waveInOpenNoError = 0
	recordingSampleRate = 16000
)

type waveFormatEx struct {
	formatTag      uint16
	channels       uint16
	samplesPerSec  uint32
	avgBytesPerSec uint32
	blockAlign     uint16
	bitsPerSample  uint16
	cbSize         uint16
}

type waveHdr struct {
	data       *byte
	bufferLen  uint32
	bytesRec   uint32
	user       uintptr
	flags      uint32
	loops      uint32
	next       uintptr
	reserved   uintptr
}

// voiceRecorder captures 16 kHz mono PCM from the default microphone using the
// classic winmm waveIn API, then writes a WAV file on stop.
type voiceRecorder struct {
	dir      string
	mu       sync.Mutex
	handle   uintptr
	buffers  []waveHdr
	chunks   [][]byte
	pending  [][]byte
	recording bool
	wavPath   string
	stopSignal chan struct{}
	doneSignal chan struct{}
	feed      func(pcmInt16 []byte)
}

func newVoiceRecorder(dir string) *voiceRecorder {
	return &voiceRecorder{
		dir:        dir,
		stopSignal: make(chan struct{}),
		doneSignal: make(chan struct{}),
	}
}

func (r *voiceRecorder) isRecording() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.recording
}

func (r *voiceRecorder) start(feed func(pcmInt16 []byte)) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.recording {
		return nil
	}
	r.feed = feed
	if err := os.MkdirAll(r.dir, 0o700); err != nil {
		return err
	}
	var format waveFormatEx
	format.formatTag = waveFormatPCM
	format.channels = 1
	format.samplesPerSec = recordingSampleRate
	format.bitsPerSample = 16
	format.blockAlign = 2
	format.avgBytesPerSec = recordingSampleRate * 2

	var handle uintptr
	result, _, callErr := waveInOpen.Call(
		uintptr(unsafe.Pointer(&handle)),
		waveMapper,
		uintptr(unsafe.Pointer(&format)),
		0,
		0,
		callBackNull,
	)
	if result != waveInOpenNoError {
		return fmt.Errorf("无法打开麦克风（错误码 %d：%v）", result, callErr)
	}

	r.handle = handle
	r.chunks = nil
	bufferSize := 3200 // 100 ms of 16 kHz mono 16-bit PCM
	r.buffers = make([]waveHdr, 8)
	for index := range r.buffers {
		chunk := make([]byte, bufferSize)
		r.buffers[index].data = &chunk[0]
		r.buffers[index].bufferLen = uint32(bufferSize)
		result, _, _ = waveInPrepareHdr.Call(
			handle,
			uintptr(unsafe.Pointer(&r.buffers[index])),
			unsafe.Sizeof(r.buffers[index]),
		)
		if result != waveInOpenNoError {
			_ = r.cleanupLocked()
			return fmt.Errorf("麦克风缓冲准备失败（错误码 %d）", result)
		}
		result, _, _ = waveInAddBuffer.Call(
			handle,
			uintptr(unsafe.Pointer(&r.buffers[index])),
			unsafe.Sizeof(r.buffers[index]),
		)
		if result != waveInOpenNoError {
			_ = r.cleanupLocked()
			return fmt.Errorf("麦克风缓冲提交失败（错误码 %d）", result)
		}
		r.chunks = append(r.chunks, chunk)
	}
	result, _, _ = waveInStart.Call(handle)
	if result != waveInOpenNoError {
		_ = r.cleanupLocked()
		return fmt.Errorf("麦克风启动失败（错误码 %d）", result)
	}
	r.recording = true
	r.stopSignal = make(chan struct{})
	r.doneSignal = make(chan struct{})
	go r.drainLoop()
	return nil
}

// drainLoop copies completed buffers into the total stream until stop is
// signalled, keeping buffers recycled so long recordings never lose audio.
func (r *voiceRecorder) drainLoop() {
	period := 25 * time.Millisecond
	ticker := time.NewTicker(period)
	defer ticker.Stop()
	completed := map[int]bool{}
	for {
		select {
		case <-r.stopSignal:
			r.collectCompleted(completed)
			close(r.doneSignal)
			return
		case <-ticker.C:
			r.collectCompleted(completed)
		}
	}
}

func (r *voiceRecorder) collectCompleted(completed map[int]bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	handle := r.handle
	for index := range r.buffers {
		if completed[index] {
			continue
		}
		header := &r.buffers[index]
		flags := header.flags
		if flags&whdrDone == 0 {
			continue
		}
		header.flags &^= whdrDone
		recorded := int(header.bytesRec)
		if recorded > int(header.bufferLen) {
			recorded = int(header.bufferLen)
		}
		if recorded > 0 {
			chunk := r.chunks[index]
			r.pending = append(r.pending, append([]byte(nil), chunk[:recorded]...))
			if r.feed != nil {
				r.feed(chunk[:recorded])
			}
		}
		result, _, _ := waveInAddBuffer.Call(
			handle,
			uintptr(unsafe.Pointer(header)),
			unsafe.Sizeof(*header),
		)
		if result != waveInOpenNoError {
			completed[index] = true
			continue
		}
		completed[index] = false
	}
}

func (r *voiceRecorder) stop() (string, error) {
	r.mu.Lock()
	if !r.recording {
		r.mu.Unlock()
		return "", errors.New("没有正在进行的录音")
	}
	r.recording = false
	handle := r.handle
	close(r.stopSignal)
	r.mu.Unlock()

	select {
	case <-r.doneSignal:
	case <-time.After(2 * time.Second):
	}

	r.mu.Lock()
	defer r.mu.Unlock()
	_, _, _ = waveInStop.Call(handle)
	_, _, _ = waveInReset.Call(handle)
	for index := range r.buffers {
		_, _, _ = waveInUnprepare.Call(
			handle,
			uintptr(unsafe.Pointer(&r.buffers[index])),
			unsafe.Sizeof(r.buffers[index]),
		)
	}
	_, _, _ = waveInClose.Call(handle)
	r.handle = 0
	r.feed = nil
	samples := r.pending
	r.pending = nil
	if len(samples) == 0 {
		return "", errors.New("没有录制到音频数据")
	}
	var total int
	for _, chunk := range samples {
		total += len(chunk)
	}
	path := filepath.Join(r.dir, fmt.Sprintf("record-%d.wav", time.Now().UnixMilli()))
	if err := writePcmWav(path, samples, recordingSampleRate); err != nil {
		return "", err
	}
	return path, nil
}

func (r *voiceRecorder) cleanupLocked() error {
	if r.handle == 0 {
		return nil
	}
	handle := r.handle
	_, _, _ = waveInStop.Call(handle)
	_, _, _ = waveInReset.Call(handle)
	for index := range r.buffers {
		_, _, _ = waveInUnprepare.Call(
			handle,
			uintptr(unsafe.Pointer(&r.buffers[index])),
			unsafe.Sizeof(r.buffers[index]),
		)
	}
	_, _, _ = waveInClose.Call(handle)
	r.handle = 0
	r.recording = false
	r.feed = nil
	return nil
}

func (r *voiceRecorder) close() {
	r.mu.Lock()
	if r.recording {
		close(r.stopSignal)
		r.recording = false
	}
	_ = r.cleanupLocked()
	r.mu.Unlock()
}

func writePcmWav(path string, chunks [][]byte, sampleRate int) error {
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()
	var dataSize int
	for _, chunk := range chunks {
		dataSize += len(chunk)
	}
	header := make([]byte, 44)
	copy(header[0:4], "RIFF")
	binary.LittleEndian.PutUint32(header[4:8], uint32(36+dataSize))
	copy(header[8:12], "WAVE")
	copy(header[12:16], "fmt ")
	binary.LittleEndian.PutUint32(header[16:20], 16)
	binary.LittleEndian.PutUint16(header[20:22], waveFormatPCM)
	binary.LittleEndian.PutUint16(header[22:24], 1)
	binary.LittleEndian.PutUint32(header[24:28], uint32(sampleRate))
	binary.LittleEndian.PutUint32(header[28:32], uint32(sampleRate*2))
	binary.LittleEndian.PutUint16(header[32:34], 2)
	binary.LittleEndian.PutUint16(header[34:36], 16)
	copy(header[36:40], "data")
	binary.LittleEndian.PutUint32(header[40:44], uint32(dataSize))
	if _, err := file.Write(header); err != nil {
		return err
	}
	for _, chunk := range chunks {
		if _, err := file.Write(chunk); err != nil {
			return err
		}
	}
	return nil
}

func playWavSync(path string) error {
	wide, err := syscall.UTF16PtrFromString(path)
	if err != nil {
		return err
	}
	result, _, callErr := playSound.Call(
		uintptr(unsafe.Pointer(wide)),
		0,
		sndFilename|sndSync|sndNoDefault,
	)
	if result == 0 {
		return fmt.Errorf("PlaySound 失败：%v", callErr)
	}
	return nil
}

func stopWavPlayback() error {
	result, _, callErr := playSound.Call(0, 0, 0)
	if result == 0 {
		return fmt.Errorf("PlaySound 停止失败：%v", callErr)
	}
	return nil
}

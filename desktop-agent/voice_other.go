//go:build !windows

package main

import (
	"errors"
	"os/exec"
)

type voiceRecorder struct{}

func newVoiceRecorder(dir string) *voiceRecorder {
	return &voiceRecorder{}
}

func (r *voiceRecorder) isRecording() bool {
	return false
}

func (r *voiceRecorder) start(feed func(pcmInt16 []byte)) error {
	return errors.New("当前平台暂不支持语音输入")
}

func (r *voiceRecorder) stop() (string, error) {
	return "", errors.New("当前平台暂不支持语音输入")
}

func (r *voiceRecorder) close() {}

// voiceTtsEngine mirrors the Windows-only engine type so the cross-platform
// pipeline compiles; every method returns "not supported" on macOS/Linux.
type voiceTtsEngine struct{}

func (e *DesktopVoiceEngine) ensureTtsEngine() (*voiceTtsEngine, error) {
	return nil, errors.New("当前平台暂不支持离线朗读")
}

func (e *voiceTtsEngine) generate(text string, speed float64) ([]float32, int, error) {
	return nil, 0, errors.New("当前平台暂不支持离线朗读")
}

func (e *voiceTtsEngine) close() {}

func writeFloat32Wav(path string, samples []float32, sampleRate int) error {
	return errors.New("当前平台暂不支持离线朗读")
}

func playWavSync(path string) error {
	return errors.New("当前平台暂不支持朗读")
}

func stopWavPlayback() error {
	return nil
}

func hideVoiceChildWindow(command *exec.Cmd) {}

func (e *DesktopVoiceEngine) StartListening() error {
	return errors.New("当前平台暂不支持语音输入")
}

func (e *DesktopVoiceEngine) StopListeningAndGetFinal() (string, error) {
	return "", errors.New("当前平台暂不支持语音输入")
}

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

func (r *voiceRecorder) start() error {
	return errors.New("当前平台暂不支持语音输入")
}

func (r *voiceRecorder) stop() (string, error) {
	return "", errors.New("当前平台暂不支持语音输入")
}

func (r *voiceRecorder) close() {}

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

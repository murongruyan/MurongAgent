package main

import "context"

// VoiceStatus returns the current offline speech engine availability.
func (app *DesktopAgentApp) VoiceStatus() VoiceRuntimeStatus {
	if app.voiceEngine == nil {
		return VoiceRuntimeStatus{}
	}
	return app.voiceEngine.Status()
}

// VoiceInstallRuntime downloads and verifies the Sherpa-onnx Windows binaries.
func (app *DesktopAgentApp) VoiceInstallRuntime() error {
	if app.voiceEngine == nil {
		return errVoiceEngineUnavailable
	}
	return app.voiceEngine.InstallRuntime(context.Background())
}

// VoiceInstallTtsModel downloads the offline Chinese TTS voice model.
func (app *DesktopAgentApp) VoiceInstallTtsModel() error {
	if app.voiceEngine == nil {
		return errVoiceEngineUnavailable
	}
	return app.voiceEngine.InstallTtsModel(context.Background())
}

// VoiceInstallAsrModel downloads the offline recognition model.
func (app *DesktopAgentApp) VoiceInstallAsrModel() error {
	if app.voiceEngine == nil {
		return errVoiceEngineUnavailable
	}
	return app.voiceEngine.InstallAsrModel(context.Background())
}

// VoiceSpeak synthesizes and plays text with the offline voice.
func (app *DesktopAgentApp) VoiceSpeak(text string, rate float64) error {
	if app.voiceEngine == nil {
		return errVoiceEngineUnavailable
	}
	return app.voiceEngine.Speak(text, rate)
}

// VoiceStopSpeaking stops any running read-aloud playback.
func (app *DesktopAgentApp) VoiceStopSpeaking() {
	if app.voiceEngine == nil {
		return
	}
	app.voiceEngine.StopSpeaking()
}

// VoiceStartRecording begins microphone capture.
func (app *DesktopAgentApp) VoiceStartRecording() error {
	if app.voiceEngine == nil {
		return errVoiceEngineUnavailable
	}
	return app.voiceEngine.StartRecording()
}

// VoiceStartListening begins microphone capture with incremental offline
// recognition; partial results are pushed through the voice_status event.
func (app *DesktopAgentApp) VoiceStartListening() error {
	if app.voiceEngine == nil {
		return errVoiceEngineUnavailable
	}
	return app.voiceEngine.StartListening()
}

// VoiceStopListeningAndGetFinal stops capture and returns the final text.
func (app *DesktopAgentApp) VoiceStopListeningAndGetFinal() (string, error) {
	if app.voiceEngine == nil {
		return "", errVoiceEngineUnavailable
	}
	return app.voiceEngine.StopListeningAndGetFinal()
}

// VoiceStopRecordingAndRecognize stops capture and returns recognized text.
func (app *DesktopAgentApp) VoiceStopRecordingAndRecognize() (string, error) {
	if app.voiceEngine == nil {
		return "", errVoiceEngineUnavailable
	}
	return app.voiceEngine.StopRecordingAndRecognize()
}

var errVoiceEngineUnavailable = &voiceEngineUnavailableError{}

type voiceEngineUnavailableError struct{}

func (e *voiceEngineUnavailableError) Error() string {
	return "语音引擎未初始化"
}

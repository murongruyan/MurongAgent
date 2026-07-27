package main

func (app *DesktopAgentApp) GetBuiltinVisionModelStatus() BuiltinVisionModelStatus {
	return app.vision.Status()
}

func (app *DesktopAgentApp) StartBuiltinVisionModelInstall(tier string) error {
	return app.vision.StartInstall(tier)
}

func (app *DesktopAgentApp) CancelBuiltinVisionModelInstall() {
	app.vision.CancelInstall()
}

func (app *DesktopAgentApp) SelectBuiltinVisionModel(tier string) error {
	if app.visionRuntime != nil {
		app.visionRuntime.Close()
	}
	return app.vision.Select(tier)
}

func (app *DesktopAgentApp) DeleteBuiltinVisionModel(tier string) error {
	if app.visionRuntime != nil {
		app.visionRuntime.Close()
	}
	return app.vision.Delete(tier)
}

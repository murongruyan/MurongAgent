package main

func (app *DesktopAgentApp) GetProviderImportProtocolState() ProviderImportProtocolState {
	return getProviderImportProtocolState()
}

func (app *DesktopAgentApp) SetCCSwitchProtocolCompatibility(enabled bool) (ProviderImportProtocolState, error) {
	return setCCSwitchProtocolCompatibility(enabled)
}

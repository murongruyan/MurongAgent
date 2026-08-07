package main

import (
	"errors"
	"strings"
)

func providerImportTargetProfileID(config desktopConfig, payload providerImportPayload) string {
	providerID := providerIDForImportedApp(payload.App)
	profileID := importedProviderProfileID(providerID, payload.Name, payload.Endpoints[0])
	for _, existing := range config.ProviderProfiles {
		if existing.ID == profileID || existing.ProviderID == providerID &&
			strings.EqualFold(strings.TrimSpace(existing.Name), strings.TrimSpace(payload.Name)) &&
			strings.EqualFold(strings.TrimRight(existing.BaseURL, "/"), strings.TrimRight(payload.Endpoints[0], "/")) {
			return existing.ID
		}
	}
	return profileID
}

func (store *desktopStore) importProviderProfile(payload providerImportPayload, activate bool) (PublicDesktopConfig, string, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	if len(payload.Endpoints) == 0 {
		return PublicDesktopConfig{}, "", errors.New("导入配置缺少 API 端点")
	}
	providerID := providerIDForImportedApp(payload.App)
	profileID := importedProviderProfileID(providerID, payload.Name, payload.Endpoints[0])
	protected, err := protectSecret([]byte(payload.APIKey))
	if err != nil {
		return PublicDesktopConfig{}, "", err
	}
	profile := ProviderProfile{
		ID: profileID, ProviderID: providerID, Name: payload.Name, BaseURL: payload.Endpoints[0],
		Model: payload.Model, ReasoningEffort: "high", APIMode: importedProviderAPIMode(providerID),
		ProtectedAPIKey: protected,
	}
	updated := store.config
	existingIndex := -1
	for index, existing := range updated.ProviderProfiles {
		if existing.ID == profileID || existing.ProviderID == providerID &&
			strings.EqualFold(strings.TrimSpace(existing.Name), strings.TrimSpace(payload.Name)) &&
			strings.EqualFold(strings.TrimRight(existing.BaseURL, "/"), strings.TrimRight(payload.Endpoints[0], "/")) {
			existingIndex = index
			profile.ID = existing.ID
			profileID = existing.ID
			break
		}
	}
	if existingIndex >= 0 {
		updated.ProviderProfiles = append([]ProviderProfile(nil), updated.ProviderProfiles...)
		updated.ProviderProfiles[existingIndex] = profile
	} else {
		updated.ProviderProfiles = append(append([]ProviderProfile(nil), updated.ProviderProfiles...), profile)
	}
	if activate {
		updated.ActiveProviderProfileID = profileID
	}
	updated = normalizeDesktopConfig(updated)
	if err := writeJSONAtomic(store.configPath, updated); err != nil {
		return PublicDesktopConfig{}, "", err
	}
	store.config = updated
	return publicConfig(updated), profileID, nil
}

func (store *desktopStore) providerProfileAPIKey(profileID string) (string, error) {
	store.mu.Lock()
	profile := findProviderProfile(store.config.ProviderProfiles, strings.TrimSpace(profileID))
	protected := ""
	if profile != nil {
		protected = profile.ProtectedAPIKey
	}
	store.mu.Unlock()
	if profile == nil {
		return "", errors.New("模型连接不存在")
	}
	if protected == "" {
		return "", errors.New("模型连接没有 API Key")
	}
	plain, err := unprotectSecret(protected)
	if err != nil {
		return "", err
	}
	return string(plain), nil
}

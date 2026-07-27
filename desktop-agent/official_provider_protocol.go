package main

import "strings"

// These names deliberately live next to the desktop transport code rather than
// in the UI.  A profile can arrive through a backup or phone sync without ever
// being opened by the desktop settings page, so the wire rules must be selected
// from ProviderID as well as from the official default URL.
type desktopOfficialVendor string

const (
	desktopVendorStandard desktopOfficialVendor = "standard"
	desktopVendorKimi     desktopOfficialVendor = "kimi"
	desktopVendorGLM      desktopOfficialVendor = "glm"
	desktopVendorQwen     desktopOfficialVendor = "qwen"
	desktopVendorMiniMax  desktopOfficialVendor = "minimax"
	desktopVendorGrok     desktopOfficialVendor = "grok"
	desktopVendorMiMo     desktopOfficialVendor = "mimo"
	desktopVendorHy3      desktopOfficialVendor = "hy3"
)

func desktopOfficialVendorFor(profile ProviderProfile) desktopOfficialVendor {
	switch profile.ProviderID {
	case providerKimi:
		return desktopVendorKimi
	case providerGLM:
		return desktopVendorGLM
	case providerQwen:
		return desktopVendorQwen
	case providerMiniMax:
		return desktopVendorMiniMax
	case providerGrok:
		return desktopVendorGrok
	case providerMiMo:
		return desktopVendorMiMo
	case providerHy3:
		return desktopVendorHy3
	}
	baseURL := strings.ToLower(profile.BaseURL)
	switch {
	case strings.Contains(baseURL, "moonshot") || strings.Contains(baseURL, "kimi.com"):
		return desktopVendorKimi
	case strings.Contains(baseURL, "bigmodel.cn") || strings.Contains(baseURL, "api.z.ai"):
		return desktopVendorGLM
	case strings.Contains(baseURL, "dashscope") || strings.Contains(baseURL, "qwen"):
		return desktopVendorQwen
	case strings.Contains(baseURL, "minimaxi.com") || strings.Contains(baseURL, "minimax.io"):
		return desktopVendorMiniMax
	case strings.Contains(baseURL, "api.x.ai"):
		return desktopVendorGrok
	case strings.Contains(baseURL, "xiaomimimo.com"):
		return desktopVendorMiMo
	case strings.Contains(baseURL, "tencentmaas.com") || strings.Contains(baseURL, "hunyuan"):
		return desktopVendorHy3
	default:
		return desktopVendorStandard
	}
}

func normalizeDesktopOfficialReasoning(vendor desktopOfficialVendor, effort string) string {
	effort = strings.ToLower(strings.TrimSpace(effort))
	if effort == "" || effort == "off" || effort == "disabled" {
		return ""
	}
	switch vendor {
	case desktopVendorGLM:
		if effort == "low" || effort == "medium" || effort == "high" {
			return "high"
		}
		return "max"
	case desktopVendorQwen:
		switch effort {
		case "low", "medium", "xhigh":
			return effort
		default:
			return "xhigh"
		}
	case desktopVendorGrok:
		switch effort {
		case "low", "medium", "high":
			return effort
		default:
			return "high"
		}
	case desktopVendorKimi, desktopVendorMiniMax, desktopVendorMiMo, desktopVendorHy3:
		// Their official OpenAI-compatible documents do not define OpenAI's
		// generic reasoning_effort field.  Sending it causes avoidable 400s.
		return ""
	default:
		return effort
	}
}

func applyDesktopOfficialChatOptions(profile ProviderProfile, request *chatCompletionRequest) {
	vendor := desktopOfficialVendorFor(profile)
	effort := strings.ToLower(strings.TrimSpace(profile.ReasoningEffort))
	request.ReasoningEffort = normalizeDesktopOfficialReasoning(vendor, effort)
	thinkingEnabled := effort != "" && effort != "off" && effort != "disabled"
	switch vendor {
	case desktopVendorKimi, desktopVendorGLM:
		state := "disabled"
		if thinkingEnabled {
			state = "enabled"
		}
		request.Thinking = &chatThinkingConfig{Type: state}
	case desktopVendorQwen:
		request.EnableThinking = &thinkingEnabled
		request.PreserveThinking = strings.Contains(strings.ToLower(profile.Model), "qwen3.8")
	}
}

func desktopPreservesReasoning(profile ProviderProfile) bool {
	switch desktopOfficialVendorFor(profile) {
	case desktopVendorGLM, desktopVendorQwen:
		return true
	default:
		return false
	}
}

func desktopUsesResponses(profile ProviderProfile) bool {
	return profile.ProviderID == providerGrok || profile.ProviderID == providerMiMo
}

package main

import "strings"

func normalizeExecutionProfileModel(value string) string {
	return truncateRunes(strings.TrimSpace(value), 200)
}

func normalizeExecutionProfileReasoning(value string) string {
	value = strings.ToLower(strings.TrimSpace(value))
	switch value {
	case "", "low", "medium", "high", "xhigh", "max":
		return value
	default:
		return ""
	}
}

func applyProviderProfileOverrides(profile ProviderProfile, enabled bool, model, reasoning string) ProviderProfile {
	if !enabled {
		return profile
	}
	if model = normalizeExecutionProfileModel(model); model != "" {
		profile.Model = model
	}
	if reasoning = normalizeExecutionProfileReasoning(reasoning); reasoning != "" {
		profile.ReasoningEffort = reasoning
	}
	return profile
}

func plannerProviderProfile(config desktopConfig, profile ProviderProfile, planMode bool) ProviderProfile {
	if !planMode {
		return profile
	}
	return applyProviderProfileOverrides(
		profile,
		config.PlannerProfileEnabled,
		config.PlannerModel,
		config.PlannerReasoningEffort,
	)
}

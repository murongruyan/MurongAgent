package main

import "testing"

func TestSessionProviderUsagePersistsAndDistinguishesPartialReporting(t *testing.T) {
	directory := t.TempDir()
	t.Setenv("MURONG_DESKTOP_DATA_DIR", directory)
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("用量测试")
	if err != nil {
		t.Fatal(err)
	}
	profile := ProviderProfile{ID: "provider-test", ProviderID: providerOpenAI, Model: "usage-model"}
	if err := store.recordModelUsage(session.ID, profile, &modelTokenUsage{
		InputTokens: 100, OutputTokens: 40, TotalTokens: 140, CachedInputTokens: 25, ReasoningOutputTokens: 10,
	}); err != nil {
		t.Fatal(err)
	}
	if err := store.recordModelUsage(session.ID, profile, nil); err != nil {
		t.Fatal(err)
	}

	reloaded, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	stats := sessionStats(reloaded.getSession(session.ID))
	if !stats.ProviderUsageAvailable || stats.ProviderUsageComplete || stats.ModelRequests != 2 || stats.ReportedUsageRequests != 1 {
		t.Fatalf("unexpected reporting coverage: %#v", stats)
	}
	if stats.ProviderInputTokens != 100 || stats.ProviderOutputTokens != 40 || stats.ProviderTotalTokens != 140 || stats.CachedInputTokens != 25 || stats.ReasoningOutputTokens != 10 || stats.LastModel != "usage-model" {
		t.Fatalf("unexpected provider usage totals: %#v", stats)
	}
}

func TestSessionUsageRejectsInconsistentOrOversizedValues(t *testing.T) {
	for name, usage := range map[string]SessionUsage{
		"reported-over-total":   {ModelRequests: 1, ReportedUsageRequests: 2},
		"negative":              {InputTokens: -1},
		"cached-over-input":     {InputTokens: 2, OutputTokens: 1, TotalTokens: 3, CachedInputTokens: 3},
		"reasoning-over-output": {InputTokens: 2, OutputTokens: 1, TotalTokens: 3, ReasoningOutputTokens: 2},
	} {
		t.Run(name, func(t *testing.T) {
			if err := validateSessionUsage(usage); err == nil {
				t.Fatalf("expected invalid usage to fail: %#v", usage)
			}
		})
	}
}

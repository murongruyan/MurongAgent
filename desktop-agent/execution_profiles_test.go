package main

import "testing"

func TestPlannerProviderProfileOnlyOverridesPlanTurns(t *testing.T) {
	config := defaultDesktopConfig()
	base := ProviderProfile{ProviderID: providerOpenAI, Model: "main-model", ReasoningEffort: "medium"}
	config.PlannerProfileEnabled = true
	config.PlannerModel = "planner-model"
	config.PlannerReasoningEffort = "xhigh"

	ordinary := plannerProviderProfile(config, base, false)
	if ordinary.Model != "main-model" || ordinary.ReasoningEffort != "medium" {
		t.Fatalf("ordinary chat was changed by planner profile: %#v", ordinary)
	}
	planner := plannerProviderProfile(config, base, true)
	if planner.Model != "planner-model" || planner.ReasoningEffort != "xhigh" {
		t.Fatalf("planner profile was not applied: %#v", planner)
	}
	config.PlannerModel = ""
	config.PlannerReasoningEffort = ""
	inherited := plannerProviderProfile(config, base, true)
	if inherited.Model != base.Model || inherited.ReasoningEffort != base.ReasoningEffort {
		t.Fatalf("blank planner fields did not inherit: %#v", inherited)
	}
}

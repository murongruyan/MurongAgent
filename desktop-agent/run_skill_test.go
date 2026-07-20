package main

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
)

func TestRunSkillInlineUsesEnabledSkillAndStrictArguments(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.mutateKnowledge(func(config *desktopConfig) error {
		config.GlobalSkills = append(config.GlobalSkills, GlobalSkill{
			ID: "release-review", Title: "发布审查", Description: "检查发布风险", Content: "先检查变更，再运行验证。", RunAs: "INLINE", Enabled: true,
		})
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	app := &DesktopAgentApp{store: store, approvals: map[string]chan bool{}, runs: map[string]context.CancelFunc{}}
	call := modelToolCall{ID: "call-1", Type: "function", Function: modelToolFunction{
		Name: "run_skill", Arguments: `{"skill":"发布","task":"检查当前版本","source":"global"}`,
	}}
	result, err := app.executeRunSkill(context.Background(), "session-1", desktopConfig{ApprovalMode: approvalYolo}, nil, call)
	if err != nil || !strings.Contains(result, `"runAs":"inline"`) || !strings.Contains(result, "先检查变更") || !strings.Contains(result, "检查当前版本") {
		t.Fatalf("unexpected inline Skill result: %v %s", err, result)
	}
	call.Function.Arguments = `{"skill":"发布审查","unexpected":true}`
	if _, err := app.executeRunSkill(context.Background(), "session-1", desktopConfig{ApprovalMode: approvalYolo}, nil, call); err == nil || !strings.Contains(err.Error(), "unknown field") {
		t.Fatalf("run_skill accepted an unknown field: %v", err)
	}
}

func TestRunSkillReportsAmbiguousMatchesWithoutExecuting(t *testing.T) {
	entries := []scopedSkill{
		{Skill: GlobalSkill{ID: "global-review", Title: "发布审查", Enabled: true}, Scope: "global"},
		{Skill: GlobalSkill{ID: "project-review", Title: "发布审查", Enabled: true}, Scope: "project"},
	}
	matched, ambiguous := matchRunSkill(entries, "发布审查")
	if matched.Skill.ID != "" || len(ambiguous) != 2 {
		t.Fatalf("duplicate titles were not reported as ambiguous: %#v %#v", matched, ambiguous)
	}
	matched, ambiguous = matchRunSkill(entries, "project-review")
	if matched.Skill.ID != "project-review" || len(ambiguous) != 0 {
		t.Fatalf("exact Skill ID did not resolve uniquely: %#v %#v", matched, ambiguous)
	}
}

func TestRunSkillSubagentArgumentsPreserveBoundedCapabilities(t *testing.T) {
	entry := scopedSkill{Scope: "project", Skill: GlobalSkill{
		ID: "fix", Title: "修复", Description: "实现并验证", Content: "只修改任务要求的文件。", RunAs: "SUBAGENT",
		AllowedTools: []string{"file", "shell", "web", "run_skill", "ask_user"}, PreferredModel: "child-model",
	}}
	raw, err := runSkillSubagentArguments(entry, runSkillArguments{Task: "修复问题", Background: true})
	if err != nil {
		t.Fatal(err)
	}
	if rawString(raw, "model", "") != "child-model" || !rawBool(raw, "background", false) || !rawBool(raw, "allowWriteAccess", false) || !rawBool(raw, "allowShell", false) || !rawBool(raw, "enableWebSearch", false) {
		t.Fatalf("Skill subagent execution policy was not preserved: %#v", raw)
	}
	var allowed []string
	if err := json.Unmarshal(raw["allowedTools"], &allowed); err != nil {
		t.Fatal(err)
	}
	joined := strings.Join(allowed, ",")
	for _, expected := range []string{"read_file", "write_file", "run_terminal", "web_search"} {
		if !strings.Contains(joined, expected) {
			t.Fatalf("expanded allowedTools missing %s: %#v", expected, allowed)
		}
	}
	if strings.Contains(joined, "run_skill") || strings.Contains(joined, "ask_user") || !isKnowledgeToolName("run_skill") {
		t.Fatalf("recursive or interactive Skill tools leaked into child budget: %#v", allowed)
	}
}

func TestRunSkillRejectsInstructionsThatCannotFitChildGoal(t *testing.T) {
	entry := scopedSkill{Scope: "global", Skill: GlobalSkill{ID: "huge", Title: "Huge", Content: strings.Repeat("指", maxRunSkillInstructionRunes), RunAs: "SUBAGENT"}}
	_, err := runSkillSubagentArguments(entry, runSkillArguments{Task: strings.Repeat("任", 3_000)})
	if err == nil || !strings.Contains(err.Error(), "goal 上限") {
		t.Fatalf("oversized child goal was not rejected: %v", err)
	}
}

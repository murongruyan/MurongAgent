package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestComposerContextValidatesProjectPathsSkillsAndSubagents(t *testing.T) {
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, "src"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "src", "main.go"), []byte("package main"), 0o644); err != nil {
		t.Fatal(err)
	}
	config := defaultDesktopConfig()
	config.ProjectPath = root
	config.GlobalSkills = []GlobalSkill{{ID: "skill_global", Title: "全局审查", Content: "先读取修改再审查", RunAs: "INLINE", Enabled: true}}
	config.ProjectKnowledge[projectKnowledgeKey(root)] = KnowledgeLibrary{Skills: []GlobalSkill{{
		ID: "skill_project", Title: "项目探索", Content: "检查项目入口", RunAs: "SUBAGENT", Enabled: true,
	}}}
	items, err := validateComposerContext(config, []ComposerContextItem{
		{Kind: "file", Path: "src/main.go"},
		{Kind: "folder", Path: "src"},
		{Kind: "skill", ID: "skill_project", Scope: "project"},
		{Kind: "subagent", ID: "review"},
		{Kind: "file", Path: "src/main.go"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 4 || items[0].Label != "src/main.go" || items[2].Label != "项目探索" || items[3].Label != "Review" {
		t.Fatalf("unexpected normalized composer context: %#v", items)
	}
	message := materializeUserMessage(config, ChatMessage{Role: "user", Content: "分析这里", Context: items})
	for _, expected := range []string{"@src/main.go", "@src", "检查项目入口", "必须通过 subagent_launch", "必须调用 review"} {
		if !strings.Contains(message, expected) {
			t.Fatalf("materialized message missing %q: %s", expected, message)
		}
	}
}

func TestComposerContextRejectsTypeMismatchAndUnknownMCP(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "file.txt"), []byte("text"), 0o644); err != nil {
		t.Fatal(err)
	}
	config := defaultDesktopConfig()
	config.ProjectPath = root
	if _, err := validateComposerContext(config, []ComposerContextItem{{Kind: "folder", Path: "file.txt"}}); err == nil {
		t.Fatal("expected file/folder type mismatch to be rejected")
	}
	if _, err := validateComposerContext(config, []ComposerContextItem{{Kind: "mcp", ID: "missing"}}); err == nil {
		t.Fatal("expected unavailable MCP context to be rejected")
	}
}

func TestComposerCatalogIncludesEnabledGlobalAndProjectSkills(t *testing.T) {
	root := t.TempDir()
	config := defaultDesktopConfig()
	config.ProjectPath = root
	config.GlobalSkills = []GlobalSkill{
		{ID: "enabled-global", Title: "Global", Description: "全局说明", RunAs: "INLINE", Enabled: true},
		{ID: "disabled-global", Title: "Hidden", Enabled: false},
	}
	config.ProjectKnowledge[projectKnowledgeKey(root)] = KnowledgeLibrary{Skills: []GlobalSkill{{
		ID: "enabled-project", Title: "Project", RunAs: "SUBAGENT", PreferredModel: "review-model", AllowedTools: []string{"code_search"}, Enabled: true,
	}}}
	store := &desktopStore{
		configPath: filepath.Join(t.TempDir(), "config.json"), sessionsPath: filepath.Join(t.TempDir(), "sessions.json"),
		config: config, sessions: map[string]*ChatSession{},
	}
	catalog := (&DesktopAgentApp{store: store}).GetComposerCatalog()
	if len(catalog.Skills) != 2 || catalog.Skills[0].Scope != "project" || catalog.Skills[1].Scope != "global" || len(catalog.Subagents) != 5 {
		t.Fatalf("unexpected composer catalog: %#v", catalog)
	}
	if !strings.Contains(catalog.Skills[0].Detail, "子代理执行") || !strings.Contains(catalog.Skills[0].Detail, "review-model") || !strings.Contains(catalog.Skills[1].Detail, "内联执行") {
		t.Fatalf("skill execution metadata missing from catalog: %#v", catalog.Skills)
	}
}

func TestComposerCatalogValidatesAndMaterializesProjectSubagentTemplate(t *testing.T) {
	project := t.TempDir()
	config := defaultDesktopConfig()
	config.ProjectPath = project
	config.ProjectToolPreferences[projectKnowledgeKey(project)] = ToolPreferences{InheritGlobal: true, SubagentTemplates: []ProjectSubagentTemplate{
		{ID: "release-review", Title: "发布审查", Description: "检查发布风险", GoalMatchers: []string{"发布"}, Enabled: true},
		{ID: "disabled", Title: "已关闭", Enabled: false},
	}}
	store := &desktopStore{configPath: filepath.Join(t.TempDir(), "config.json"), sessionsPath: filepath.Join(t.TempDir(), "sessions.json"), config: config, sessions: map[string]*ChatSession{}}
	catalog := (&DesktopAgentApp{store: store}).GetComposerCatalog()
	if len(catalog.Subagents) != len(composerSubagentChoices)+1 || catalog.Subagents[0].ID != "release-review" || catalog.Subagents[0].Scope != "project" {
		t.Fatalf("project template was not added to composer: %#v", catalog.Subagents)
	}
	items, err := validateComposerContext(config, []ComposerContextItem{{Kind: "subagent", ID: "release-review", Scope: "project"}})
	if err != nil || len(items) != 1 || items[0].Label != "发布审查" {
		t.Fatalf("project template context was not validated: %#v, %v", items, err)
	}
	materialized := materializeUserMessage(config, ChatMessage{Role: "user", Content: "检查这一版", Context: items})
	if !strings.Contains(materialized, "必须调用 subagent_launch") || !strings.Contains(materialized, `templateId="release-review"`) {
		t.Fatalf("project template was not materialized into model context: %s", materialized)
	}
}

func TestSessionCloneKeepsComposerContextIsolated(t *testing.T) {
	session := &ChatSession{Messages: []ChatMessage{{Role: "user", Content: "test", Context: []ComposerContextItem{{Kind: "file", Path: "a.txt"}}}}}
	cloned := cloneSession(session)
	cloned.Messages[0].Context[0].Path = "changed.txt"
	if session.Messages[0].Context[0].Path != "a.txt" {
		t.Fatal("session clone shared composer context backing storage")
	}
}

func TestSessionGoalAndPlanModePersistAndMaterialize(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("")
	if err != nil {
		t.Fatal(err)
	}
	session, err = store.setSessionPlanMode(session.ID, true)
	if err != nil || !session.PlanModeEnabled {
		t.Fatalf("plan mode was not enabled: %#v, %v", session, err)
	}
	session, err = store.appendMessage(session.ID, ChatMessage{Role: "user", Content: "完成桌面功能对等", Mode: "goal_plan"})
	if err != nil {
		t.Fatal(err)
	}
	if session.Goal != "完成桌面功能对等" || session.Messages[0].Mode != "goal_plan" {
		t.Fatalf("goal mode was not persisted: %#v", session)
	}
	materialized := materializeUserMessage(defaultDesktopConfig(), session.Messages[0])
	if !strings.Contains(materialized, "更新长期目标") || !strings.Contains(materialized, "不要立即执行计划") {
		t.Fatalf("goal+plan mode was not materialized: %s", materialized)
	}
	reloaded, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	loaded := reloaded.getSession(session.ID)
	if loaded == nil || !loaded.PlanModeEnabled || loaded.Goal != "完成桌面功能对等" {
		t.Fatalf("session modes were not reloaded: %#v", loaded)
	}
	cleared, err := reloaded.clearSessionGoal(session.ID)
	if err != nil || cleared.Goal != "" {
		t.Fatalf("session goal was not cleared: %#v, %v", cleared, err)
	}
}

func TestPlanModeToolFilterKeepsOnlyReadOnlyInvestigationTools(t *testing.T) {
	config := defaultDesktopConfig()
	config = planModeToolConfig(config)
	if config.ApprovalMode != approvalReadOnly || isBuiltinToolEnabled(config, "shell") || isBuiltinToolEnabled(config, "code_edit") {
		t.Fatalf("plan mode retained mutating tools: %#v", config)
	}
	tools := []any{
		functionTool("read_file", "read", map[string]any{}, nil),
		functionTool("write_file", "write", map[string]any{}, nil),
		functionTool("research", "research", map[string]any{}, nil),
		functionTool("remember_memory", "memory", map[string]any{}, nil),
	}
	filtered := filterPlanModeToolDefinitions(tools)
	if len(filtered) != 2 || functionToolName(filtered[0]) != "read_file" || functionToolName(filtered[1]) != "research" {
		t.Fatalf("unexpected plan mode tools: %#v", filtered)
	}
}

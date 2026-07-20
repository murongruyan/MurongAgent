package main

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestResolveSubagentPresetPolicies(t *testing.T) {
	config := defaultDesktopConfig()
	raw := map[string]json.RawMessage{"goal": json.RawMessage(`"inspect architecture"`)}
	explore, err := resolveSubagentPolicy(config, "explore", raw)
	if err != nil {
		t.Fatal(err)
	}
	if explore.ReasoningEffort != "medium" || len(explore.EnabledBuiltins) != 1 || explore.EnabledBuiltins[0] != "file" || len(explore.EnabledFileOps) != 3 {
		t.Fatalf("unexpected explore policy: %#v", explore)
	}
	research, err := resolveSubagentPolicy(config, "research", raw)
	if err != nil || !containsString(research.EnabledBuiltins, "web_search") || !containsString(research.EnabledBuiltins, "web_fetch") {
		t.Fatalf("unexpected research policy: %#v, %v", research, err)
	}
}

func TestSubagentDefaultProfileYieldsToExplicitAndProjectTemplateOverrides(t *testing.T) {
	config := defaultDesktopConfig()
	config.SubagentProfileEnabled = true
	config.SubagentModel = "default-child-model"
	config.SubagentReasoningEffort = "xhigh"
	raw := map[string]json.RawMessage{"goal": json.RawMessage(`"inspect architecture"`)}
	explore, err := resolveSubagentPolicy(config, "explore", raw)
	if err != nil {
		t.Fatal(err)
	}
	if explore.Model != "default-child-model" || explore.ReasoningEffort != "xhigh" {
		t.Fatalf("subagent default profile was not applied: %#v", explore)
	}
	raw["model"] = json.RawMessage(`"explicit-model"`)
	raw["reasoningEffort"] = json.RawMessage(`"low"`)
	explicit, err := resolveSubagentPolicy(config, "explore", raw)
	if err != nil {
		t.Fatal(err)
	}
	if explicit.Model != "explicit-model" || explicit.ReasoningEffort != "low" {
		t.Fatalf("explicit subagent profile did not win: %#v", explicit)
	}

	project := t.TempDir()
	config.ProjectPath = project
	config.ProjectToolPreferences[projectKnowledgeKey(project)] = ToolPreferences{
		InheritGlobal: true,
		SubagentTemplates: []ProjectSubagentTemplate{{
			ID: "template", Title: "模板", PreferredModel: "template-model", PreferredReasoningEffort: "high", EnableWebSearch: true, Enabled: true,
		}},
	}
	templated, err := resolveSubagentPolicy(config, "subagent_launch", map[string]json.RawMessage{
		"goal": json.RawMessage(`"use the project template"`), "templateId": json.RawMessage(`"template"`),
	})
	if err != nil {
		t.Fatal(err)
	}
	if templated.Model != "template-model" || templated.ReasoningEffort != "high" {
		t.Fatalf("project template did not win over the global child default: %#v", templated)
	}
}

func TestResolveGenericSubagentIntersectsParentBudget(t *testing.T) {
	config := defaultDesktopConfig()
	config.EnabledBuiltinTools = []string{"file", "code_search", "shell"}
	raw := map[string]json.RawMessage{
		"goal":         json.RawMessage(`"run diagnostics"`),
		"allowShell":   json.RawMessage(`true`),
		"allowedTools": json.RawMessage(`["shell","file(read,exists)"]`),
	}
	policy, err := resolveSubagentPolicy(config, "subagent_launch", raw)
	if err != nil {
		t.Fatal(err)
	}
	if !containsString(policy.EnabledBuiltins, "shell") || !policy.AllowedToolNames["run_terminal"] || !policy.AllowedToolNames["read_file"] || policy.AllowedToolNames["write_file"] {
		t.Fatalf("unexpected generic policy: %#v", policy)
	}
	config.EnabledBuiltinTools = []string{"file"}
	policy, err = resolveSubagentPolicy(config, "subagent_launch", raw)
	if err != nil || containsString(policy.EnabledBuiltins, "shell") {
		t.Fatalf("parent budget was bypassed: %#v, %v", policy, err)
	}
}

func TestProjectSubagentTemplateAutoMatchDefaultsOverridesAndRisk(t *testing.T) {
	project := t.TempDir()
	config := defaultDesktopConfig()
	config.ProjectPath = project
	config.ProjectToolPreferences[projectKnowledgeKey(project)] = ToolPreferences{
		InheritGlobal: true,
		SubagentTemplates: []ProjectSubagentTemplate{
			{ID: "general-review", Title: "普通审查", GoalMatchers: []string{"review"}, EnableWebSearch: true, Enabled: true},
			{
				ID: "security-review", Title: "安全审查", GoalMatchers: []string{"review", "security"},
				PreferredModel: "security-model", PreferredReasoningEffort: "xhigh", EnableWebSearch: false,
				AllowWriteAccess: true, AllowCodeEdits: true, AllowShell: true, Enabled: true,
			},
		},
	}
	raw := map[string]json.RawMessage{"goal": json.RawMessage(`"security review this project"`)}
	policy, err := resolveSubagentPolicy(config, "subagent_launch", raw)
	if err != nil {
		t.Fatal(err)
	}
	if policy.TemplateID != "security-review" || policy.Label != "安全审查" || policy.Model != "security-model" || policy.ReasoningEffort != "xhigh" {
		t.Fatalf("template defaults did not resolve: %#v", policy)
	}
	if containsString(policy.EnabledBuiltins, "web_search") || !containsString(policy.EnabledBuiltins, "shell") || !containsString(policy.EnabledBuiltins, "code_edit") || subagentPolicyRisk(policy) != "high" {
		t.Fatalf("template tool budget or risk is wrong: %#v", policy)
	}
	raw["templateId"] = json.RawMessage(`"security-review"`)
	raw["allowShell"] = json.RawMessage(`false`)
	raw["allowCodeEdits"] = json.RawMessage(`false`)
	raw["allowWriteAccess"] = json.RawMessage(`false`)
	raw["enableWebSearch"] = json.RawMessage(`true`)
	raw["model"] = json.RawMessage(`"override-model"`)
	policy, err = resolveSubagentPolicy(config, "subagent_launch", raw)
	if err != nil {
		t.Fatal(err)
	}
	if policy.Model != "override-model" || containsString(policy.EnabledBuiltins, "shell") || containsString(policy.EnabledBuiltins, "code_edit") || !containsString(policy.EnabledBuiltins, "web_search") || subagentPolicyRisk(policy) != "low" {
		t.Fatalf("explicit arguments did not override template defaults: %#v", policy)
	}
}

func TestSubagentLaunchSchemaAdvertisesProjectTemplates(t *testing.T) {
	project := t.TempDir()
	config := defaultDesktopConfig()
	config.ProjectPath = project
	config.ProjectToolPreferences[projectKnowledgeKey(project)] = ToolPreferences{InheritGlobal: true, SubagentTemplates: []ProjectSubagentTemplate{{
		ID: "project-audit", Title: "项目审计", Enabled: true,
	}}}
	for _, raw := range (&DesktopAgentApp{}).toolDefinitions(config) {
		definition := raw.(map[string]any)["function"].(map[string]any)
		if definition["name"] != "subagent_launch" {
			continue
		}
		properties := definition["parameters"].(map[string]any)["properties"].(map[string]any)
		if properties["templateId"] == nil || !strings.Contains(definition["description"].(string), "project-audit=项目审计") {
			t.Fatalf("project template was not advertised in tool schema: %#v", definition)
		}
		return
	}
	t.Fatal("subagent_launch tool is missing")
}

func TestParseSubagentParallelPlanValidatesDependencies(t *testing.T) {
	raw := map[string]json.RawMessage{
		"goal":       json.RawMessage(`"audit and summarize"`),
		"batchLabel": json.RawMessage(`"release audit"`),
		"parallelTasks": json.RawMessage(`[
            {"label":"discover","goal":"find affected files"},
            {"label":"review","goal":"review the change","dependsOn":[1]},
            {"label":"summarize","goal":"summarize evidence","dependsOn":[1,2]}
        ]`),
	}
	plan, err := parseSubagentExecutionPlan(raw)
	if err != nil {
		t.Fatal(err)
	}
	if plan == nil || plan.Label != "release audit" || len(plan.Tasks) != 3 || len(plan.Tasks[2].DependsOn) != 2 {
		t.Fatalf("unexpected parallel plan: %#v", plan)
	}

	raw["parallelTasks"] = json.RawMessage(`[
        {"goal":"first","dependsOn":[2]},
        {"goal":"second","dependsOn":[1]}
    ]`)
	if _, err := parseSubagentExecutionPlan(raw); err == nil {
		t.Fatal("cyclic subagent dependency graph was accepted")
	}
	raw["parallelTasks"] = json.RawMessage(`[
        {"goal":"first"},
        {"goal":"second","dependsOn":[3]}
    ]`)
	if _, err := parseSubagentExecutionPlan(raw); err == nil {
		t.Fatal("out-of-range subagent dependency was accepted")
	}
}

func TestSubagentDependencyPlanRunsTwoAtOnceAndSkipsFailedDependants(t *testing.T) {
	plan := subagentExecutionPlan{Label: "test", ParentGoal: "test", Tasks: []subagentTaskSpec{
		{Index: 1, Label: "discover", Goal: "discover"},
		{Index: 2, Label: "risk", Goal: "risk"},
		{Index: 3, Label: "summarize", Goal: "summarize", DependsOn: []int{1}},
		{Index: 4, Label: "blocked", Goal: "blocked", DependsOn: []int{2}},
	}}
	var running atomic.Int32
	var peak atomic.Int32
	var discoverDone atomic.Bool
	runner := func(_ context.Context, task subagentTaskSpec) (string, error) {
		current := running.Add(1)
		for {
			observed := peak.Load()
			if current <= observed || peak.CompareAndSwap(observed, current) {
				break
			}
		}
		defer running.Add(-1)
		time.Sleep(20 * time.Millisecond)
		switch task.Index {
		case 1:
			discoverDone.Store(true)
			return `{"success":true,"result":"found"}`, nil
		case 2:
			return "", errors.New("risk scan failed")
		case 3:
			if !discoverDone.Load() {
				return "", errors.New("dependency started too early")
			}
			return `{"success":true,"result":"summary"}`, nil
		default:
			return `{"success":true}`, nil
		}
	}

	results := executeSubagentDependencyPlan(context.Background(), plan, 2, runner)
	if peak.Load() != 2 {
		t.Fatalf("expected concurrency of 2, got %d", peak.Load())
	}
	wantStatuses := []string{"completed", "failed", "completed", "skipped"}
	for index, wanted := range wantStatuses {
		if results[index].Index != index+1 || results[index].Status != wanted {
			t.Fatalf("unexpected result %d: %#v", index, results[index])
		}
	}
	if results[3].Error == "" || results[3].Output != "" {
		t.Fatalf("dependency failure was not explained safely: %#v", results[3])
	}
	aggregate := marshalSubagentPlanResult(plan, results)
	var envelope struct {
		Success   bool `json:"success"`
		Completed int  `json:"completed"`
		Failed    int  `json:"failed"`
		Skipped   int  `json:"skipped"`
	}
	if err := json.Unmarshal([]byte(aggregate), &envelope); err != nil {
		t.Fatal(err)
	}
	if envelope.Success || envelope.Completed != 2 || envelope.Failed != 1 || envelope.Skipped != 1 {
		t.Fatalf("unexpected aggregate: %s", aggregate)
	}
}

func TestSubagentDependencyPlanPropagatesCancellation(t *testing.T) {
	plan := subagentExecutionPlan{Label: "cancel", ParentGoal: "cancel", Tasks: []subagentTaskSpec{
		{Index: 1, Label: "running", Goal: "running"},
		{Index: 2, Label: "waiting", Goal: "waiting", DependsOn: []int{1}},
	}}
	ctx, cancel := context.WithCancel(context.Background())
	started := make(chan struct{})
	runnerObservedCancellation := make(chan struct{})
	done := make(chan []subagentTaskResult, 1)
	go func() {
		done <- executeSubagentDependencyPlan(ctx, plan, 1, func(taskContext context.Context, _ subagentTaskSpec) (string, error) {
			close(started)
			<-taskContext.Done()
			close(runnerObservedCancellation)
			return "", taskContext.Err()
		})
	}()

	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("first subagent did not start")
	}
	cancel()

	var results []subagentTaskResult
	select {
	case results = <-done:
	case <-time.After(time.Second):
		t.Fatal("cancelled subagent plan did not finish")
	}
	select {
	case <-runnerObservedCancellation:
	default:
		t.Fatal("running subagent did not receive parent cancellation")
	}
	if results[0].Status != "cancelled" || results[1].Status != "cancelled" {
		t.Fatalf("unexpected cancellation results: %#v", results)
	}
	if results[1].Error != "父任务已取消，子代理未启动" {
		t.Fatalf("pending cancellation was not explained: %#v", results[1])
	}
}

func TestParseSubagentParallelPlanRejectsUnknownFields(t *testing.T) {
	raw := map[string]json.RawMessage{
		"goal":          json.RawMessage(`"strict task graph"`),
		"parallelTasks": json.RawMessage(`[{"goal":"first","unexpected":true},{"goal":"second"}]`),
	}
	if _, err := parseSubagentExecutionPlan(raw); err == nil {
		t.Fatal("parallel task with unknown fields was accepted")
	}
}

func TestSubagentToolSchemaExposesBatchAndDependencyGraph(t *testing.T) {
	app := &DesktopAgentApp{}
	foundLaunch, foundJobs := false, false
	for _, raw := range app.toolDefinitions(defaultDesktopConfig()) {
		definition := raw.(map[string]any)["function"].(map[string]any)
		switch definition["name"] {
		case "subagent_launch":
			foundLaunch = true
			parameters := definition["parameters"].(map[string]any)
			properties := parameters["properties"].(map[string]any)
			if properties["batchGoals"] == nil || properties["parallelTasks"] == nil || properties["batchLabel"] == nil || properties["background"] == nil {
				t.Fatalf("subagent orchestration schema is incomplete: %#v", properties)
			}
			parallel := properties["parallelTasks"].(map[string]any)
			if parallel["maxItems"] != maxSubagentBatchTasks {
				t.Fatalf("unexpected subagent batch limit: %#v", parallel)
			}
		case "subagent_jobs":
			foundJobs = true
			parameters := definition["parameters"].(map[string]any)
			properties := parameters["properties"].(map[string]any)
			action := properties["action"].(map[string]any)
			if len(action["enum"].([]string)) != 3 || properties["jobId"] == nil {
				t.Fatalf("background subagent job schema is incomplete: %#v", properties)
			}
		}
	}
	if !foundLaunch || !foundJobs {
		t.Fatalf("subagent tools were not exposed: launch=%t jobs=%t", foundLaunch, foundJobs)
	}
}

func containsString(values []string, wanted string) bool {
	for _, value := range values {
		if value == wanted {
			return true
		}
	}
	return false
}

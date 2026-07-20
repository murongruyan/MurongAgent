package main

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
)

func TestParseDesktopWorkflowPlanIsBoundedAndCaseInsensitive(t *testing.T) {
	raw := "# 执行计划\nsummary: 修复并验证发布流程\n1. 检查发布配置\n2) 修复发现的问题\n- 运行完整验证\n1. 检查发布配置"
	plan, err := parseDesktopWorkflowPlan("完成桌面发布", raw, "message-plan", 100)
	if err != nil {
		t.Fatal(err)
	}
	if plan.Summary != "修复并验证发布流程" || len(plan.Steps) != 3 || plan.Steps[2] != "运行完整验证" || plan.Status != workflowPlanReady {
		t.Fatalf("unexpected parsed plan: %#v", plan)
	}

	var many strings.Builder
	many.WriteString("摘要：最多保留八步\n")
	for index := 1; index <= maxWorkflowPlanSteps+3; index++ {
		fmt.Fprintf(&many, "%d. 步骤 %d\n", index, index)
	}
	bounded, err := parseDesktopWorkflowPlan("限制步骤", many.String(), "message-plan", 100)
	if err != nil || len(bounded.Steps) != maxWorkflowPlanSteps {
		t.Fatalf("plan step budget was not enforced: %#v, %v", bounded, err)
	}

	fallback, err := parseDesktopWorkflowPlan("单步目标", "只完成这一项", "message-plan", 100)
	if err != nil || len(fallback.Steps) != 1 || fallback.Steps[0] != "只完成这一项" {
		t.Fatalf("plain-text plan did not get a safe fallback step: %#v, %v", fallback, err)
	}
	for name, args := range map[string][3]string{
		"missing goal":   {"", raw, "message-plan"},
		"missing plan":   {"目标", "", "message-plan"},
		"missing source": {"目标", raw, ""},
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := parseDesktopWorkflowPlan(args[0], args[1], args[2], 100); err == nil {
				t.Fatal("invalid plan input was accepted")
			}
		})
	}
}

func TestWorkflowPlanPersistsClonesAndSettlesWithoutFalseCompletion(t *testing.T) {
	store, session, _ := createWorkflowPlanFixture(t, []string{"读取配置", "运行测试"})
	ready := store.getSession(session.ID)
	if ready.WorkflowPlan == nil || ready.WorkflowPlan.Status != workflowPlanReady || ready.WorkflowPlan.CurrentStepIndex != 0 {
		t.Fatalf("ready plan was not persisted: %#v", ready.WorkflowPlan)
	}
	ready.WorkflowPlan.Steps[0] = "外部错误修改"
	if store.getSession(session.ID).WorkflowPlan.Steps[0] != "读取配置" {
		t.Fatal("session clone shared workflow plan slices with the store")
	}

	reloaded, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	persisted := reloaded.getSession(session.ID)
	if persisted == nil || persisted.WorkflowPlan == nil || persisted.WorkflowPlan.Steps[1] != "运行测试" {
		t.Fatalf("workflow plan did not survive restart: %#v", persisted)
	}
	if err := validateDesktopWorkflowPlan(persisted.WorkflowPlan, persisted.Messages); err != nil {
		t.Fatalf("persisted plan failed strict validation: %v", err)
	}
	snapshot := reloaded.backupSnapshot()
	if len(snapshot.Sessions) != 1 || snapshot.Sessions[0].WorkflowPlan == nil {
		t.Fatalf("backup snapshot omitted the canonical plan: %#v", snapshot.Sessions)
	}

	started, _, err := reloaded.startWorkflowPlan(session.ID)
	if err != nil || started.WorkflowPlan.Status != workflowPlanExecuting || started.WorkflowPlan.ExecutionStartedAt <= 0 || started.PlanModeEnabled {
		t.Fatalf("workflow plan did not enter execution: %#v, %v", started, err)
	}
	settled, err := reloaded.settleWorkflowPlan(session.ID, "本轮没有工具签收")
	if err != nil || settled.WorkflowPlan.Status != workflowPlanBlocked || !strings.Contains(settled.WorkflowPlan.NextStepHint, "读取配置") {
		t.Fatalf("unsigned execution was falsely completed: %#v, %v", settled.WorkflowPlan, err)
	}
}

func TestCompleteStepRequiresMatchingUniqueToolReceipts(t *testing.T) {
	store, session, _ := createWorkflowPlanFixture(t, []string{"运行测试", "读取报告"})
	if _, _, err := store.startWorkflowPlan(session.ID); err != nil {
		t.Fatal(err)
	}
	failed, err := store.appendMessage(session.ID, ChatMessage{
		Role: "tool", Kind: "tool", ToolName: "run_terminal", ToolCallID: "call-failed",
		ToolArguments: `{"command":"go test ./..."}`, ToolStatus: "failed", Content: "exit code 1",
	})
	if err != nil {
		t.Fatal(err)
	}
	app := &DesktopAgentApp{store: store}
	if _, err := executeWorkflowStepForTest(app, session.ID, "运行测试", "已观察到预期失败", map[string]any{
		"summary": "测试命令", "toolName": "run_terminal", "command": "go test ./...",
	}); err == nil {
		t.Fatal("failed receipt satisfied default mustSucceed evidence")
	}
	if _, err := executeWorkflowStepForTest(app, session.ID, "错误步骤", "错误步骤", map[string]any{
		"summary": "测试命令", "toolName": "run_terminal", "command": "go test ./...", "mustSucceed": false,
	}); err == nil {
		t.Fatal("complete_step advanced a non-current step")
	}
	if _, err := executeWorkflowStepForTest(app, session.ID, "运行测试", "确认测试失败信息", map[string]any{
		"summary": "测试命令", "toolName": "run_terminal", "command": "go test ./...", "mustSucceed": false,
	}); err != nil {
		t.Fatalf("explicit failure evidence was not accepted: %v", err)
	}
	afterFirst := store.getSession(session.ID)
	if afterFirst.WorkflowPlan.CurrentStepIndex != 1 || len(afterFirst.WorkflowPlan.StepSignOffs) != 1 ||
		afterFirst.WorkflowPlan.StepSignOffs[0].EvidenceMessageIDs[0] != failed.Messages[len(failed.Messages)-1].ID {
		t.Fatalf("first step sign-off was not persisted: %#v", afterFirst.WorkflowPlan)
	}
	if _, err := executeWorkflowStepForTest(app, session.ID, "读取报告", "错误复用", map[string]any{
		"summary": "重复收据", "toolName": "run_terminal", "command": "go test ./...", "mustSucceed": false,
	}); err == nil {
		t.Fatal("one tool receipt was reused for a second step")
	}
	if _, err := store.appendMessage(session.ID, ChatMessage{
		Role: "tool", Kind: "tool", ToolName: "read_file", ToolCallID: "call-read",
		ToolArguments: `{"path":"reports/result.txt"}`, ToolStatus: "success", Content: "all checks passed",
	}); err != nil {
		t.Fatal(err)
	}
	if _, err := executeWorkflowStepForTest(app, session.ID, "读取报告", "报告确认通过", map[string]any{
		"summary": "读取验证报告", "toolName": "read_file", "path": "reports/result.txt",
	}); err != nil {
		t.Fatalf("successful receipt did not complete the second step: %v", err)
	}
	completed := store.getSession(session.ID)
	if completed.WorkflowPlan.Status != workflowPlanCompleted || completed.WorkflowPlan.CurrentStepIndex != 2 {
		t.Fatalf("workflow was not completed by two signed steps: %#v", completed.WorkflowPlan)
	}
	if err := validateDesktopWorkflowPlan(completed.WorkflowPlan, completed.Messages); err != nil {
		t.Fatalf("completed workflow failed strict validation: %v", err)
	}
}

func TestCompleteStepArgumentsRejectUnknownTrailingAndAnchorlessEvidence(t *testing.T) {
	for name, input := range map[string]string{
		"unknown":    `{"step":"检查","result":"完成","evidence":[{"summary":"证据","toolName":"read_file","extra":true}]}`,
		"trailing":   `{"step":"检查","result":"完成","evidence":[{"summary":"证据","toolName":"read_file"}]} {}`,
		"anchorless": `{"step":"检查","result":"完成","evidence":[{"summary":"只有描述"}]}`,
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := parseCompleteStepArguments(input); err == nil {
				t.Fatal("invalid complete_step payload was accepted")
			}
		})
	}
}

func TestCompleteStepToolExposureAndWorkflowPlanUIContract(t *testing.T) {
	app := &DesktopAgentApp{}
	if !toolListContains(app.toolDefinitions(defaultDesktopConfig()), completeStepToolName) {
		t.Fatal("ordinary provider tools omitted complete_step")
	}
	if toolListContains(app.planModeToolDefinitions(app.toolDefinitions(defaultDesktopConfig())), completeStepToolName) {
		t.Fatal("plan-generation mode exposed complete_step")
	}
	regularCodex := map[string]bool{}
	for _, raw := range app.codexDynamicTools(false) {
		regularCodex[raw.(map[string]any)["name"].(string)] = true
	}
	planCodex := map[string]bool{}
	for _, raw := range app.codexDynamicTools(true) {
		planCodex[raw.(map[string]any)["name"].(string)] = true
	}
	if !regularCodex[completeStepToolName] || planCodex[completeStepToolName] {
		t.Fatalf("Codex complete_step exposure is unsafe: regular=%v plan=%v", regularCodex[completeStepToolName], planCodex[completeStepToolName])
	}

	index, err := frontendAssets.ReadFile("frontend/dist/index.html")
	if err != nil {
		t.Fatal(err)
	}
	script, err := frontendAssets.ReadFile("frontend/dist/app.js")
	if err != nil {
		t.Fatal(err)
	}
	styles, err := frontendAssets.ReadFile("frontend/dist/styles.css")
	if err != nil {
		t.Fatal(err)
	}
	html, js, css := string(index), string(script), string(styles)
	for _, marker := range []string{`id="workflow-plan-card"`, `id="workflow-plan-steps"`, `id="execute-workflow-plan"`, `id="dismiss-workflow-plan"`} {
		if !strings.Contains(html, marker) {
			t.Fatalf("workflow plan UI is missing %q", marker)
		}
	}
	for _, marker := range []string{`function renderWorkflowPlan()`, `backend().ExecuteWorkflowPlan`, `backend().DismissWorkflowPlan`, `真实工具收据签收`} {
		if !strings.Contains(js, marker) {
			t.Fatalf("workflow plan UI wiring is missing %q", marker)
		}
	}
	for _, marker := range []string{".workflow-plan-card", ".workflow-plan-step.current", ".workflow-plan-step.completed"} {
		if !strings.Contains(css, marker) {
			t.Fatalf("workflow plan styling is missing %q", marker)
		}
	}
}

func TestCodexCompletedItemsPersistCanonicalPlanAndToolReceipt(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("Codex 规范计划")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.appendMessage(session.ID, ChatMessage{Role: "user", Content: "检查并验证", Mode: "goal_plan"}); err != nil {
		t.Fatal(err)
	}
	app := &DesktopAgentApp{store: store}
	planText := "摘要：Codex 计划\n1. 检查文件\n2. 运行验证"
	if err := app.persistCodexCompletedItem(session.ID, codexThreadItem{Type: "agentMessage", ID: "codex-plan", Text: planText}, true); err != nil {
		t.Fatal(err)
	}
	planned := store.getSession(session.ID)
	if planned.WorkflowPlan == nil || planned.WorkflowPlan.Summary != "Codex 计划" || planned.WorkflowPlan.SourceMessageID != planned.Messages[len(planned.Messages)-1].ID {
		t.Fatalf("Codex plan item was not captured canonically: %#v", planned)
	}
	if _, _, err := store.startWorkflowPlan(session.ID); err != nil {
		t.Fatal(err)
	}
	output := "README contents"
	if err := app.persistCodexCompletedItem(session.ID, codexThreadItem{
		Type: "commandExecution", ID: "codex-command", Command: "go test ./...", Cwd: "project", Status: "completed", AggregatedOutput: &output,
	}, false); err != nil {
		t.Fatal(err)
	}
	withReceipt := store.getSession(session.ID)
	receipt := withReceipt.Messages[len(withReceipt.Messages)-1]
	if receipt.Role != "tool" || receipt.ToolCallID != "codex-command" || receipt.ToolStatus != "success" ||
		!strings.Contains(receipt.ToolArguments, "go test ./...") || !strings.Contains(receipt.ToolArguments, "project") {
		t.Fatalf("Codex tool receipt was not persisted for evidence matching: %#v", receipt)
	}
}

func TestRemoteWorkflowPlanProjectionIsBoundedAndReceiptSafe(t *testing.T) {
	store, session, _ := createWorkflowPlanFixture(t, []string{"读取秘密文件", "运行验证"})
	if _, _, err := store.startWorkflowPlan(session.ID); err != nil {
		t.Fatal(err)
	}
	withReceipt, err := store.appendMessage(session.ID, ChatMessage{
		Role: "tool", Kind: "tool", ToolName: "read_file", ToolCallID: "private-call-id",
		ToolArguments: `{"path":"C:/private/project/secret.txt"}`, ToolStatus: "success", Content: "redacted contents",
	})
	if err != nil {
		t.Fatal(err)
	}
	receiptID := withReceipt.Messages[len(withReceipt.Messages)-1].ID
	app := &DesktopAgentApp{store: store}
	if _, err := executeWorkflowStepForTest(app, session.ID, "读取秘密文件", "文件已读取", map[string]any{
		"summary": "读取文件", "toolName": "read_file", "path": "C:/private/project/secret.txt",
	}); err != nil {
		t.Fatal(err)
	}
	snapshot := app.desktopAgentSnapshot()
	if snapshot.ActiveSession == nil || snapshot.ActiveSession.WorkflowPlan == nil {
		t.Fatalf("remote snapshot omitted workflow progress: %#v", snapshot)
	}
	projection := snapshot.ActiveSession.WorkflowPlan
	if projection.CurrentStepIndex != 1 || projection.Status != workflowPlanExecuting ||
		len(projection.StepSignOffs) != 1 || projection.StepSignOffs[0].MatchedToolNames[0] != "read_file" {
		t.Fatalf("unexpected remote workflow projection: %#v", projection)
	}
	encoded, err := json.Marshal(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	for _, secret := range []string{"private-call-id", "C:/private/project/secret.txt", `toolArguments`, `evidenceMessageIds`} {
		if strings.Contains(string(encoded), secret) {
			t.Fatalf("remote workflow projection leaked %q: %s", secret, encoded)
		}
	}
	planJSON, err := json.Marshal(projection)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(planJSON), receiptID) {
		t.Fatalf("workflow projection leaked the receipt message identity: %s", planJSON)
	}
}

func TestWorkflowPlanForkRollbackPortableAndHandoffBoundaries(t *testing.T) {
	store, session, planMessageID := createWorkflowPlanFixture(t, []string{"读取文件", "运行测试"})
	if _, _, err := store.startWorkflowPlan(session.ID); err != nil {
		t.Fatal(err)
	}
	withReceipt, err := store.appendMessage(session.ID, ChatMessage{
		Role: "tool", Kind: "tool", ToolName: "read_file", ToolCallID: "call-read",
		ToolArguments: `{"path":"README.md"}`, ToolStatus: "success", Content: "contents",
	})
	if err != nil {
		t.Fatal(err)
	}
	app := &DesktopAgentApp{store: store}
	if _, err := executeWorkflowStepForTest(app, session.ID, "读取文件", "读取完成", map[string]any{
		"summary": "README", "toolName": "read_file", "path": "README.md",
	}); err != nil {
		t.Fatal(err)
	}

	forked, err := store.forkSession(session.ID, "")
	if err != nil {
		t.Fatal(err)
	}
	if forked.WorkflowPlan == nil || forked.WorkflowPlan.SourceMessageID == planMessageID || forked.WorkflowPlan.Status != workflowPlanBlocked ||
		forked.WorkflowPlan.CurrentStepIndex != 1 || forked.WorkflowPlan.StepSignOffs[0].EvidenceMessageIDs[0] == withReceipt.Messages[len(withReceipt.Messages)-1].ID {
		t.Fatalf("fork did not remap the plan and receipt identities: %#v", forked.WorkflowPlan)
	}
	if err := validateDesktopWorkflowPlan(forked.WorkflowPlan, forked.Messages); err != nil {
		t.Fatalf("forked workflow failed validation: %v", err)
	}

	portableJSON, err := encodePortableSession(store.getSession(session.ID))
	if err != nil {
		t.Fatal(err)
	}
	portable, err := decodePortableSession(portableJSON)
	if err != nil {
		t.Fatal(err)
	}
	if portable.WorkflowPlan != nil {
		t.Fatal("portable session leaked a desktop-only canonical plan")
	}
	for _, message := range portable.Messages {
		if message.ToolCallID != "" || message.ToolArguments != "" || message.ToolStatus != "" {
			t.Fatalf("portable session leaked tool receipt metadata: %#v", message)
		}
	}
	if _, _, err := store.beginSessionHandoff(session.ID); err == nil || !strings.Contains(err.Error(), "未完成") {
		t.Fatalf("active canonical plan was handed to an incompatible phone schema: %v", err)
	}

	rolledBack, err := store.rollbackSession(session.ID, planMessageID)
	if err != nil {
		t.Fatal(err)
	}
	if rolledBack.WorkflowPlan == nil || rolledBack.WorkflowPlan.Status != workflowPlanBlocked || rolledBack.WorkflowPlan.CurrentStepIndex != 0 || len(rolledBack.WorkflowPlan.StepSignOffs) != 0 {
		t.Fatalf("rollback did not remove sign-offs whose receipts disappeared: %#v", rolledBack.WorkflowPlan)
	}
	if err := validateDesktopWorkflowPlan(rolledBack.WorkflowPlan, rolledBack.Messages); err != nil {
		t.Fatalf("rolled-back workflow failed validation: %v", err)
	}
	earlyFork, err := store.forkSession(session.ID, rolledBack.Messages[0].ID)
	if err != nil {
		t.Fatal(err)
	}
	if earlyFork.WorkflowPlan != nil {
		t.Fatalf("fork before the plan source retained an orphan plan: %#v", earlyFork.WorkflowPlan)
	}
}

func TestValidateDesktopWorkflowPlanRejectsForgedSignOffs(t *testing.T) {
	store, session, _ := createWorkflowPlanFixture(t, []string{"读取文件"})
	if _, _, err := store.startWorkflowPlan(session.ID); err != nil {
		t.Fatal(err)
	}
	current := store.getSession(session.ID)
	for name, mutate := range map[string]func(*DesktopWorkflowPlan){
		"ready with progress": func(plan *DesktopWorkflowPlan) {
			plan.Status = workflowPlanReady
			plan.ExecutionStartedAt = 0
			plan.CurrentStepIndex = 1
		},
		"missing source": func(plan *DesktopWorkflowPlan) { plan.SourceMessageID = "message-missing" },
		"forged receipt": func(plan *DesktopWorkflowPlan) {
			plan.Status = workflowPlanCompleted
			plan.CurrentStepIndex = 1
			plan.StepSignOffs = []WorkflowStepSignOff{{
				StepIndex: 0, Step: "读取文件", ReportedStep: "读取文件", ResultSummary: "伪造完成",
				MatchedEvidence: 1, TotalEvidence: 1, MatchedToolNames: []string{"read_file"},
				EvidenceMessageIDs: []string{"message-missing"}, SignedOffAt: plan.ExecutionStartedAt,
			}}
			plan.UpdatedAt = plan.ExecutionStartedAt
		},
	} {
		t.Run(name, func(t *testing.T) {
			plan := cloneDesktopWorkflowPlan(current.WorkflowPlan)
			mutate(plan)
			if err := validateDesktopWorkflowPlan(plan, current.Messages); err == nil {
				t.Fatal("forged workflow plan passed strict validation")
			}
		})
	}
}

func createWorkflowPlanFixture(t *testing.T, steps []string) (*desktopStore, *ChatSession, string) {
	t.Helper()
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("规范计划")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.appendMessage(session.ID, ChatMessage{Role: "user", Content: "完成规范计划", Mode: "goal"}); err != nil {
		t.Fatal(err)
	}
	rawPlan := "摘要：按步骤完成并验证\n"
	for index, step := range steps {
		rawPlan += fmt.Sprintf("%d. %s\n", index+1, step)
	}
	withPlanMessage, err := store.appendMessage(session.ID, ChatMessage{Role: "assistant", Content: rawPlan, Kind: "plan"})
	if err != nil {
		t.Fatal(err)
	}
	planMessageID := withPlanMessage.Messages[len(withPlanMessage.Messages)-1].ID
	if _, err := store.captureWorkflowPlan(session.ID, planMessageID, rawPlan); err != nil {
		t.Fatal(err)
	}
	return store, store.getSession(session.ID), planMessageID
}

func executeWorkflowStepForTest(app *DesktopAgentApp, sessionID, step, result string, evidence map[string]any) (string, error) {
	payload, _ := json.Marshal(map[string]any{
		"step": step, "result": result, "evidence": []any{evidence},
	})
	return app.executeCompleteStep(context.Background(), sessionID, modelToolCall{
		ID:       "call-complete-step",
		Function: modelToolFunction{Name: completeStepToolName, Arguments: string(payload)},
	})
}

package main

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestCodexInitializeAdvertisesExperimentalAPIForDynamicTools(t *testing.T) {
	params := codexClientInitializeParams()
	capabilities, ok := params["capabilities"].(map[string]any)
	if !ok || capabilities["experimentalApi"] != true {
		t.Fatalf("Codex dynamic tools require experimentalApi capability: %#v", params)
	}
}

func TestCodexApprovalSettingsPreserveFourModes(t *testing.T) {
	tests := []struct {
		mode, policy, sandbox string
		plan                  bool
	}{
		{approvalReadOnly, "never", "read-only", false},
		{approvalAskAll, "on-request", "danger-full-access", false},
		{approvalAllowlist, "on-request", "danger-full-access", false},
		{approvalYolo, "never", "danger-full-access", false},
		{approvalYolo, "never", "read-only", true},
	}
	for _, test := range tests {
		policy, sandbox := codexApprovalSettings(test.mode, test.plan)
		if policy != test.policy || sandbox != test.sandbox {
			t.Fatalf("mode %s plan=%v: got %s/%s", test.mode, test.plan, policy, sandbox)
		}
	}
}

func TestBuildCodexTurnInputUsesOnlyUnsyncedUserMessagesOnResume(t *testing.T) {
	session := &ChatSession{CodexThreadID: "thread-1", CodexSyncedID: "user-1", Messages: []ChatMessage{
		{ID: "user-1", Role: "user", Content: "旧问题"},
		{ID: "assistant-1", Role: "assistant", Content: "旧答案"},
		{ID: "user-2", Role: "user", Content: "新问题"},
	}}
	input, synced, err := buildCodexTurnInput(defaultDesktopConfig(), session, false)
	if err != nil {
		t.Fatal(err)
	}
	if synced != "user-2" || !strings.Contains(input, "新问题") || strings.Contains(input, "旧答案") || strings.Contains(input, "旧问题") {
		t.Fatalf("unexpected resumed input %q, synced %q", input, synced)
	}
	input, _, err = buildCodexTurnInput(defaultDesktopConfig(), session, true)
	if err != nil || !strings.Contains(input, "旧问题") || !strings.Contains(input, "旧答案") {
		t.Fatalf("fresh thread did not receive portable history: %q, %v", input, err)
	}
}

func TestBuildCodexTurnPayloadSendsOnlyCurrentImages(t *testing.T) {
	oldImage := MessageImageAttachment{ID: "image-000000000000000000000001", FileName: "old.png", MimeType: "image/png", CacheFile: "image-000000000000000000000001.png", Width: 10, Height: 10, SizeBytes: 10}
	currentImage := MessageImageAttachment{ID: "image-000000000000000000000002", FileName: "current.png", MimeType: "image/png", CacheFile: "image-000000000000000000000002.png", Width: 10, Height: 10, SizeBytes: 10}
	session := &ChatSession{Messages: []ChatMessage{
		{ID: "old", Role: "user", Content: "old", ImageAttachments: []MessageImageAttachment{oldImage}},
		{ID: "answer", Role: "assistant", Content: "done"},
		{ID: "current", Role: "user", Content: "current", ImageAttachments: []MessageImageAttachment{currentImage}},
	}}
	payload, err := buildCodexTurnPayload(defaultDesktopConfig(), session, true)
	if err != nil {
		t.Fatal(err)
	}
	if len(payload.Images) != 1 || payload.Images[0].ID != currentImage.ID || !strings.Contains(payload.Text, "old.png") {
		t.Fatalf("Codex image payload was not bounded to the current turn: %#v %q", payload.Images, payload.Text)
	}
}

func TestCodexHistoryDynamicToolMatchesAppServerSchema(t *testing.T) {
	tool := codexSessionHistoryDynamicTool()
	if tool["type"] != "function" || tool["name"] != sessionHistoryToolName || strings.TrimSpace(tool["description"].(string)) == "" {
		t.Fatalf("unexpected dynamic tool identity: %#v", tool)
	}
	if _, wrapped := tool["function"]; wrapped {
		t.Fatalf("Codex dynamic tool must not use the Responses API function wrapper: %#v", tool)
	}
	schema, ok := tool["inputSchema"].(map[string]any)
	if !ok || schema["type"] != "object" || schema["additionalProperties"] != false {
		t.Fatalf("unexpected Codex dynamic tool input schema: %#v", tool["inputSchema"])
	}
	properties, ok := schema["properties"].(map[string]any)
	if !ok || properties["message_reference"] == nil || properties["project_only"] == nil {
		t.Fatalf("history inputs missing from Codex dynamic tool: %#v", properties)
	}
}

func TestCodexAskUserDynamicToolMatchesAppServerSchema(t *testing.T) {
	tool := codexAskUserDynamicTool()
	if tool["type"] != "function" || tool["name"] != askUserToolName || strings.TrimSpace(tool["description"].(string)) == "" {
		t.Fatalf("unexpected ask_user dynamic tool identity: %#v", tool)
	}
	if _, wrapped := tool["function"]; wrapped {
		t.Fatalf("Codex dynamic tool must not use the Responses API function wrapper: %#v", tool)
	}
	schema, ok := tool["inputSchema"].(map[string]any)
	if !ok || schema["type"] != "object" || schema["additionalProperties"] != false {
		t.Fatalf("unexpected ask_user input schema: %#v", tool["inputSchema"])
	}
	properties, ok := schema["properties"].(map[string]any)
	if !ok || properties["questions"] == nil {
		t.Fatalf("ask_user questions missing from Codex dynamic tool: %#v", properties)
	}
}

func TestCodexDynamicToolsExposeKnowledgeAndKeepPlanModeReadOnly(t *testing.T) {
	app := &DesktopAgentApp{}
	regular, plan := app.codexDynamicTools(false), app.codexDynamicTools(true)
	names := func(values []any) map[string]bool {
		result := map[string]bool{}
		for _, raw := range values {
			definition := raw.(map[string]any)
			name, _ := definition["name"].(string)
			result[name] = true
			if _, wrapped := definition["function"]; wrapped {
				t.Fatalf("Codex dynamic tool retained Responses wrapper: %#v", definition)
			}
			if _, ok := definition["inputSchema"].(map[string]any); !ok {
				t.Fatalf("Codex dynamic tool has no input schema: %#v", definition)
			}
		}
		return result
	}
	regularNames, planNames := names(regular), names(plan)
	for _, name := range []string{sessionHistoryToolName, askUserToolName, "memory_list", "memory_search", "memory_read", "read_skill", "remember_memory", "forget_memory", "run_skill", "create_global_skill", "create_project_skill"} {
		if !regularNames[name] {
			t.Fatalf("regular Codex dynamic tools missing %s: %#v", name, regularNames)
		}
	}
	for _, name := range []string{sessionHistoryToolName, askUserToolName, "memory_list", "memory_search", "memory_read", "read_skill"} {
		if !planNames[name] {
			t.Fatalf("plan Codex dynamic tools missing safe tool %s: %#v", name, planNames)
		}
	}
	for _, name := range []string{"remember_memory", "forget_memory", "run_skill", "create_global_skill", "create_project_skill"} {
		if planNames[name] {
			t.Fatalf("plan Codex dynamic tools exposed mutating/executing tool %s", name)
		}
	}
}

func TestCodexDynamicKnowledgeAndInlineSkillCalls(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.mutateKnowledge(func(config *desktopConfig) error {
		config.GlobalSkills = append(config.GlobalSkills, GlobalSkill{ID: "release", Title: "发布检查", Content: "运行发布测试。", RunAs: "INLINE", Enabled: true})
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	app := &DesktopAgentApp{store: store, approvals: map[string]chan bool{}, runs: map[string]context.CancelFunc{}}
	config := defaultDesktopConfig()
	config.ApprovalMode = approvalYolo
	invoke := func(tool string, arguments any, planMode bool) codexDynamicToolCallResponse {
		encodedArguments, _ := json.Marshal(arguments)
		params, _ := json.Marshal(codexDynamicToolCallParams{Arguments: encodedArguments, CallID: "call-knowledge", ThreadID: "thread-1", Tool: tool, TurnID: "turn-1"})
		return app.executeCodexDynamicToolRequestForMode(context.Background(), "session-1", config, planMode, params)
	}
	read := invoke("read_skill", map[string]any{"skill": "release"}, false)
	if !read.Success || !strings.Contains(read.ContentItems[0].Text, "运行发布测试") {
		t.Fatalf("Codex could not read Skill: %#v", read)
	}
	run := invoke("run_skill", map[string]any{"skill": "release", "task": "检查当前版本"}, false)
	if !run.Success || !strings.Contains(run.ContentItems[0].Text, `"runAs":"inline"`) || !strings.Contains(run.ContentItems[0].Text, "运行发布测试") {
		t.Fatalf("Codex could not run inline Skill: %#v", run)
	}
	blocked := invoke("run_skill", map[string]any{"skill": "release"}, true)
	if blocked.Success || !strings.Contains(blocked.ContentItems[0].Text, "不支持") {
		t.Fatalf("plan mode accepted run_skill: %#v", blocked)
	}
}

func TestCodexDynamicHistoryCallReturnsProtocolResponse(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	project := t.TempDir()
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.activateProject(project); err != nil {
		t.Fatal(err)
	}
	current, _ := store.createSession("当前任务")
	history, _ := store.createSession("历史任务")
	history, err = store.appendMessage(history.ID, ChatMessage{Role: "assistant", Content: "跨平台发布需要六个原生构建。"})
	if err != nil {
		t.Fatal(err)
	}
	arguments, _ := json.Marshal(map[string]any{"query": "六个原生构建", "project_only": true})
	params, _ := json.Marshal(codexDynamicToolCallParams{
		Arguments: arguments, CallID: "call-1", ThreadID: "thread-1", Tool: sessionHistoryToolName, TurnID: "turn-1",
	})
	config := defaultDesktopConfig()
	config.ProjectPath = project
	config.ApprovalMode = approvalYolo
	response := (&DesktopAgentApp{store: store}).executeCodexDynamicToolRequest(context.Background(), current.ID, config, params)
	if !response.Success || len(response.ContentItems) != 1 || response.ContentItems[0].Type != "inputText" || !strings.Contains(response.ContentItems[0].Text, history.ID) {
		t.Fatalf("unexpected dynamic tool response: %#v", response)
	}

	unknown := strings.Replace(string(params), sessionHistoryToolName, "unknown_tool", 1)
	failure := (&DesktopAgentApp{store: store}).executeCodexDynamicToolRequest(context.Background(), current.ID, config, json.RawMessage(unknown))
	if failure.Success || len(failure.ContentItems) != 1 || failure.ContentItems[0].Type != "inputText" || !strings.Contains(failure.ContentItems[0].Text, "不支持") {
		t.Fatalf("unknown dynamic tool did not return a bounded failure: %#v", failure)
	}
}

func TestCodexDynamicAskUserCallBlocksUntilResolved(t *testing.T) {
	arguments := json.RawMessage(`{"questions":[{"header":"范围","question":"先做哪一项？","options":[{"label":"桌面"},{"label":"手机"}]}]}`)
	params, _ := json.Marshal(codexDynamicToolCallParams{
		Arguments: arguments, CallID: "call-ask", ThreadID: "thread-1", Tool: askUserToolName, TurnID: "turn-1",
	})
	app := &DesktopAgentApp{asks: map[string]chan askResult{}, pendingAsks: map[string]AskRequest{}, pendingApprovals: map[string]ApprovalRequest{}}
	responseChannel := make(chan codexDynamicToolCallResponse, 1)
	go func() {
		responseChannel <- app.executeCodexDynamicToolRequest(context.Background(), "session-1", defaultDesktopConfig(), params)
	}()
	pending := waitForPendingAsk(t, app, "session-1")
	if _, err := app.ResolveAsk(AskDecision{ID: pending.ID, Answers: []AskAnswer{{QuestionID: "q1", SelectedOptions: []string{"桌面"}}}}); err != nil {
		t.Fatal(err)
	}
	select {
	case response := <-responseChannel:
		if !response.Success || len(response.ContentItems) != 1 || !strings.Contains(response.ContentItems[0].Text, "范围：桌面") {
			t.Fatalf("unexpected ask_user response: %#v", response)
		}
	case <-time.After(time.Second):
		t.Fatal("Codex ask_user call did not resume")
	}
}

func TestPortableSessionExcludesCodexThreadLinkage(t *testing.T) {
	session := &ChatSession{ID: "session-1", Title: "测试", CreatedAt: 1, UpdatedAt: 2,
		CodexThreadID: "private-thread", CodexSyncedID: "private-message", CodexToolsVersion: codexDynamicToolsVersion, Messages: []ChatMessage{{ID: "message-1", Role: "user", Content: "你好", CreatedAt: 1}}}
	data, err := encodePortableSession(session)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), "private-thread") || strings.Contains(string(data), "private-message") || strings.Contains(string(data), "codexToolsVersion") {
		t.Fatalf("portable export leaked Codex linkage: %s", data)
	}
	decoded, err := decodePortableSession(data)
	if err != nil {
		t.Fatal(err)
	}
	if decoded.CodexThreadID != "" || decoded.CodexSyncedID != "" || decoded.CodexToolsVersion != 0 {
		t.Fatalf("portable session retained Codex linkage: %#v", decoded)
	}
}

func TestDesktopBackupKeepsCodexProviderButExcludesMachineLinkage(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	config, err := store.saveSettings(SaveSettingsRequest{
		ApprovalMode: approvalAskAll, MaxToolIterations: 999, SystemPrompt: defaultDesktopSystemPrompt, ResponseVerbosity: "BALANCED",
		ActiveProviderProfileID: "provider-codex", ProviderProfiles: []SaveProviderProfile{{
			ID: "provider-codex", ProviderID: providerCodex, Name: "Codex", ReasoningEffort: "high", APIMode: "app-server",
			ExecutablePath: `C:\private\codex.exe`,
		}},
	})
	if err != nil || config.ProviderProfiles[0].ProviderID != providerCodex {
		t.Fatalf("failed to save Codex provider: %#v, %v", config, err)
	}
	session, err := store.createSession("Codex backup")
	if err != nil {
		t.Fatal(err)
	}
	if err := store.setCodexThreadState(session.ID, "private-thread", "private-message", codexDynamicToolsVersion); err != nil {
		t.Fatal(err)
	}
	workflows, err := newSavedWorkflowStore(filepath.Join(t.TempDir(), "workflows.json"))
	if err != nil {
		t.Fatal(err)
	}
	payloads, err := buildDesktopBackupPayloads(store, workflows, nil, DesktopBackupSettings{MaxBackupCount: 7})
	if err != nil {
		t.Fatal(err)
	}
	portable := desktopPortableBackupState{}
	for _, payload := range payloads {
		switch payload.Path {
		case desktopBackupProviderPath:
			err = json.Unmarshal(payload.Data, &portable.ProviderSettings)
		case desktopBackupKnowledgePath:
			err = json.Unmarshal(payload.Data, &portable.Knowledge)
		case desktopBackupMCPPath:
			err = json.Unmarshal(payload.Data, &portable.MCP)
		case desktopBackupWorkflowsPath:
			err = json.Unmarshal(payload.Data, &portable.Workflows)
		case desktopBackupSessionsPath:
			var sessions desktopSessionsBackup
			err = json.Unmarshal(payload.Data, &sessions)
			portable.Sessions = sessions.Sessions
		case desktopBackupSettingsPath:
			err = json.Unmarshal(payload.Data, &portable.BackupSettings)
		}
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(string(payload.Data), `C:\\private\\codex.exe`) || strings.Contains(string(payload.Data), "private-thread") || strings.Contains(string(payload.Data), "private-message") || strings.Contains(string(payload.Data), "codexToolsVersion") {
			t.Fatalf("backup leaked machine-local Codex data in %s", payload.Path)
		}
	}
	if err := validateDesktopPortableState(portable); err != nil {
		t.Fatalf("Codex provider backup should validate: %v", err)
	}
}

func TestDecodeCodexUsageAndCompletion(t *testing.T) {
	usage, turnID, ok := decodeCodexUsage(json.RawMessage(`{"turnId":"turn-1","tokenUsage":{"last":{"totalTokens":15,"inputTokens":10,"cachedInputTokens":4,"outputTokens":5,"reasoningOutputTokens":2}}}`))
	if !ok || turnID != "turn-1" || usage.InputTokens != 10 || usage.OutputTokens != 5 || usage.ReasoningOutputTokens != 2 {
		t.Fatalf("unexpected usage: %#v %q %v", usage, turnID, ok)
	}
	status, message, completedID := decodeCodexTurnCompletion(json.RawMessage(`{"threadId":"thread-1","turn":{"id":"turn-1","status":"failed","error":{"message":"boom"}}}`))
	if status != "failed" || message != "boom" || completedID != "turn-1" {
		t.Fatalf("unexpected completion: %q %q %q", status, message, completedID)
	}
}

func TestNormalizeCodexRateLimitClampsAndPreservesWindows(t *testing.T) {
	id, name, plan := "codex", "Codex", "plus"
	duration, reset := int64(300), int64(2_000_000_000)
	balance := "12.50"
	value := normalizeCodexRateLimit(codexRawRateLimit{
		LimitID: &id, LimitName: &name, PlanType: &plan,
		Primary: &codexRawRateLimitWindow{UsedPercent: 120, WindowDurationMins: &duration, ResetsAt: &reset},
		Credits: &struct {
			HasCredits bool    `json:"hasCredits"`
			Unlimited  bool    `json:"unlimited"`
			Balance    *string `json:"balance"`
		}{HasCredits: true, Balance: &balance},
	})
	if value.LimitID != "codex" || value.PlanType != "plus" || value.Primary == nil || value.Primary.UsedPercent != 100 || value.Primary.WindowDurationMins != 300 || value.Credits == nil || value.Credits.Balance != "12.50" {
		t.Fatalf("unexpected normalized rate limit: %#v", value)
	}
	secondary := &CodexRateLimitWindow{UsedPercent: 42, WindowDurationMins: 10_080}
	updatedDuration := int64(60)
	merged := mergeCodexRateLimit(CodexRateLimitInfo{LimitID: "codex", LimitName: "Codex", Secondary: secondary, Credits: value.Credits}, codexRawRateLimit{
		Primary: &codexRawRateLimitWindow{UsedPercent: 25, WindowDurationMins: &updatedDuration},
	})
	if merged.LimitID != "codex" || merged.LimitName != "Codex" || merged.Primary == nil || merged.Primary.UsedPercent != 25 || merged.Secondary != secondary || merged.Credits != value.Credits {
		t.Fatalf("sparse rate-limit update cleared existing fields: %#v", merged)
	}
}

func TestCodexLiveAccountAndModels(t *testing.T) {
	executable := strings.TrimSpace(os.Getenv("MURONG_CODEX_LIVE_EXECUTABLE"))
	if executable == "" {
		t.Skip("set MURONG_CODEX_LIVE_EXECUTABLE to run the live app-server contract test")
	}
	server := newCodexAppServer(t.TempDir())
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	status, err := server.Refresh(ctx, executable)
	if err != nil {
		t.Fatal(err)
	}
	if !status.Available || !status.Running || !status.LoggedIn || len(status.Models) == 0 || len(status.RateLimits) == 0 || status.RateLimitError != "" {
		t.Fatalf("unexpected live Codex status: %#v", status)
	}
}

func TestCodexLiveReadOnlyTurnStreamsAndCompletes(t *testing.T) {
	executable := strings.TrimSpace(os.Getenv("MURONG_CODEX_LIVE_EXECUTABLE"))
	if executable == "" {
		t.Skip("set MURONG_CODEX_LIVE_EXECUTABLE to run the live app-server contract test")
	}
	server := newCodexAppServer(t.TempDir())
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	defer cancel()
	threadID, err := server.StartThread(ctx, executable, map[string]any{
		"cwd": t.TempDir(), "approvalPolicy": "never", "approvalsReviewer": "user", "sandbox": "read-only",
		"baseInstructions": "Do not use tools. Follow the user's requested output exactly.", "ephemeral": true,
	})
	if err != nil {
		t.Fatal(err)
	}
	events, unsubscribe := server.Subscribe(ctx, threadID, "live-test")
	defer unsubscribe()
	turnID, err := server.StartTurn(ctx, executable, threadID, "Reply with exactly: MURONG_CODEX_OK", "", "low", t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	var output strings.Builder
	for {
		select {
		case <-ctx.Done():
			t.Fatalf("live turn timed out; partial output %q", output.String())
		case event := <-events:
			switch event.Method {
			case "item/agentMessage/delta":
				var delta struct {
					TurnID string `json:"turnId"`
					Delta  string `json:"delta"`
				}
				if json.Unmarshal(event.Params, &delta) == nil && delta.TurnID == turnID {
					output.WriteString(delta.Delta)
				}
			case "turn/completed":
				status, message, completedID := decodeCodexTurnCompletion(event.Params)
				if completedID != turnID {
					continue
				}
				if status != "completed" {
					t.Fatalf("live turn status %q: %s", status, message)
				}
				if !strings.Contains(output.String(), "MURONG_CODEX_OK") {
					t.Fatalf("unexpected live output %q", output.String())
				}
				return
			}
		}
	}
}

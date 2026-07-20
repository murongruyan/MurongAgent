package main

import (
	"strings"
	"testing"
)

func TestDesktopChatComposerKeepsPrimaryControlsVisible(t *testing.T) {
	index, err := frontendAssets.ReadFile("frontend/dist/index.html")
	if err != nil {
		t.Fatal(err)
	}
	html := string(index)
	for _, marker := range []string{
		`id="delete-session"`,
		`>删除</button>`,
		`id="composer-add"`,
		`composer-model-control`,
		`<span>模型</span>`,
		`id="composer-model-select"`,
		`composer-reasoning-control`,
		`<span>推理</span>`,
		`id="composer-reasoning-select"`,
	} {
		if !strings.Contains(html, marker) {
			t.Fatalf("desktop chat UI is missing %q", marker)
		}
	}
	if !strings.Contains(html, `<option value="xhigh">超高</option>`) || !strings.Contains(html, `<option value="max">最大</option>`) {
		t.Fatal("desktop composer reasoning control is missing the full effort range")
	}
	if strings.Contains(html, `data-composer-picker="files"`) {
		t.Fatal("file and folder selection must live inside the plus menu, not beside it")
	}
}

func TestDesktopBackgroundSubagentStatusAndCancellationAreVisible(t *testing.T) {
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
	for _, marker := range []string{`id="subagent-jobs"`, `aria-label="后台子代理"`} {
		if !strings.Contains(html, marker) {
			t.Fatalf("background subagent UI is missing %q", marker)
		}
	}
	for _, marker := range []string{`function renderSubagentJobs()`, `backend().CancelSubagentJob`, `backgroundSubagentJobs`, `满足依赖即并行`} {
		if !strings.Contains(js, marker) {
			t.Fatalf("background subagent UI wiring is missing %q", marker)
		}
	}
	for _, marker := range []string{".subagent-jobs", ".subagent-job-cancel", ".subagent-job-status.running"} {
		if !strings.Contains(css, marker) {
			t.Fatalf("background subagent styling is missing %q", marker)
		}
	}
}

func TestRemoteNodeDesktopTaskPermissionsAreExplicit(t *testing.T) {
	index, err := frontendAssets.ReadFile("frontend/dist/index.html")
	if err != nil {
		t.Fatal(err)
	}
	script, err := frontendAssets.ReadFile("frontend/dist/app.js")
	if err != nil {
		t.Fatal(err)
	}
	html := string(index)
	js := string(script)
	for _, marker := range []string{`id="remote-share-desktop-tasks"`, `id="remote-allow-agent-control"`, "在手机显示桌面任务", "允许手机控制桌面任务"} {
		if !strings.Contains(html, marker) {
			t.Fatalf("remote node task permission UI is missing %q", marker)
		}
	}
	for _, marker := range []string{"shareDesktopTasks", "allowAgentControl"} {
		if !strings.Contains(js, marker) {
			t.Fatalf("remote node task permission is not wired: %q", marker)
		}
	}
}

func TestDesktopPlusMenuContainsModesSkillsSubagentsAndMCP(t *testing.T) {
	script, err := frontendAssets.ReadFile("frontend/dist/app.js")
	if err != nil {
		t.Fatal(err)
	}
	js := string(script)
	for _, marker := range []string{
		`appendComposerPickerSection(list, "运行模式"`,
		`id: "plan"`,
		`id: "goal"`,
		`"Skills",`,
		`"子代理",`,
		`"MCP",`,
		`请先打开或新建项目，再从这里选择文件或文件夹`,
		`尚未创建可用 Skill`,
		`尚无可用子代理`,
		`尚无已连接的 MCP 工具`,
	} {
		if !strings.Contains(js, marker) {
			t.Fatalf("desktop plus menu is missing %q", marker)
		}
	}
}

func TestDesktopBackupRestoreControlsAreWired(t *testing.T) {
	index, err := frontendAssets.ReadFile("frontend/dist/index.html")
	if err != nil {
		t.Fatal(err)
	}
	script, err := frontendAssets.ReadFile("frontend/dist/app.js")
	if err != nil {
		t.Fatal(err)
	}
	html := string(index)
	js := string(script)
	for _, marker := range []string{
		`id="backup-panel"`, `id="create-manual-backup"`, `id="select-backup-restore"`,
		`id="backup-restore-modal"`, `id="backup-restore-confirm-text"`,
		`v2 ZIP`, `异系统恢复不会覆盖项目路径`,
	} {
		if !strings.Contains(html, marker) {
			t.Fatalf("desktop backup UI is missing %q", marker)
		}
	}
	for _, marker := range []string{
		`backend().UpdateBackupSettings`, `backend().CreateManualBackup`,
		`backend().SelectBackupForRestore`, `backend().RestoreBackup`, `value.trim() === "恢复"`,
	} {
		if !strings.Contains(js, marker) {
			t.Fatalf("desktop backup UI wiring is missing %q", marker)
		}
	}
}

func TestDesktopSessionAdvancedControlsAreWired(t *testing.T) {
	index, err := frontendAssets.ReadFile("frontend/dist/index.html")
	if err != nil {
		t.Fatal(err)
	}
	script, err := frontendAssets.ReadFile("frontend/dist/app.js")
	if err != nil {
		t.Fatal(err)
	}
	html := string(index)
	js := string(script)
	for _, marker := range []string{
		`id="session-details"`, `>任务详情</button>`, `id="session-details-modal"`,
		`id="rename-session-form"`, `id="fork-session"`, `id="import-session"`,
		`id="export-session-json"`, `id="export-session-portable"`, `id="export-session-markdown"`, `id="session-action-modal"`,
		`id="compress-session"`, `id="toggle-session-compression"`,
	} {
		if !strings.Contains(html, marker) {
			t.Fatalf("desktop session UI is missing %q", marker)
		}
	}
	for _, marker := range []string{
		`backend().RenameSession`, `backend().ForkSession`, `backend().RollbackSession`,
		`backend().CompressSession`, `backend().SetSessionCompressionActive`,
		`backend().GetSessionStats`, `backend().ExportSession`, `backend().ImportSession`, `exportActiveSession("portable")`,
		`从此处分叉`, `回退到此处`, `Token 为本地估算，不代表供应商账单用量`,
	} {
		if !strings.Contains(js, marker) {
			t.Fatalf("desktop session UI wiring is missing %q", marker)
		}
	}
	statsDeclaration := strings.Index(js, `const stats = state.sessionStats || {};`)
	compressionUse := strings.Index(js, `stats.compressionAvailable`)
	if statsDeclaration < 0 || compressionUse < 0 || statsDeclaration > compressionUse {
		t.Fatal("session details must declare stats before rendering compression controls")
	}
}

func TestDesktopWorkspaceChangesTimelineAndDiffAreWired(t *testing.T) {
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
	for _, marker := range []string{
		`id="workspace-changes-chip"`, `id="workspace-changes-modal"`,
		`id="workspace-change-list"`, `id="workspace-diff-content"`,
		`id="clear-workspace-changes"`, `id="project-audit-tab"`, `id="project-audit-search"`,
		`id="project-audit-source"`, `id="export-project-audit-json"`, `id="export-project-audit-markdown"`,
		`id="clear-project-audit"`, `id="project-audit-load-more"`,
	} {
		if !strings.Contains(html, marker) {
			t.Fatalf("desktop workspace timeline UI is missing %q", marker)
		}
	}
	for _, marker := range []string{
		`backend().GetWorkspaceChanges`, `backend().ClearWorkspaceChanges`,
		`backend().GetWorkspaceDiff`, `window.runtime.EventsOn("workspace:changed"`,
		`backend().GetProjectAudit`, `backend().ClearProjectAudit(true)`, `backend().ExportProjectAudit(format)`,
		`window.runtime.EventsOn("project-audit:changed"`, `renderProjectAuditModal()`,
		`createWorkspaceMessageNote(message.workspaceChanges || [], message.workspaceChangesOmitted || 0)`,
	} {
		if !strings.Contains(js, marker) {
			t.Fatalf("desktop workspace timeline wiring is missing %q", marker)
		}
	}
	for _, marker := range []string{".workspace-change-chip", ".workspace-changes-layout", ".workspace-message-note", ".workspace-timeline-tabs", ".project-audit-filters", ".project-audit-item"} {
		if !strings.Contains(css, marker) {
			t.Fatalf("desktop workspace timeline styling is missing %q", marker)
		}
	}
}

func TestDesktopGitHubRepositoryToolToggleIsVisible(t *testing.T) {
	index, err := frontendAssets.ReadFile("frontend/dist/index.html")
	if err != nil {
		t.Fatal(err)
	}
	script, err := frontendAssets.ReadFile("frontend/dist/app.js")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(index), `data-builtin-tool="github"> GitHub 仓库`) {
		t.Fatal("desktop settings are missing the GitHub repository tool toggle")
	}
	if !strings.Contains(string(script), `"security_review", "github", "mcp"`) {
		t.Fatal("desktop default tool state is missing GitHub repository tools")
	}
}

func TestDesktopMCPIncludesExplicitLegacySSETransport(t *testing.T) {
	index, err := frontendAssets.ReadFile("frontend/dist/index.html")
	if err != nil {
		t.Fatal(err)
	}
	script, err := frontendAssets.ReadFile("frontend/dist/app.js")
	if err != nil {
		t.Fatal(err)
	}
	for _, marker := range []string{`value="legacy_sse">HTTP + SSE（旧版兼容）`, `["streamable_http", "legacy_sse"].includes(server.transport)`, `旧版 SSE 如 /sse`} {
		if !strings.Contains(string(index)+string(script), marker) {
			t.Fatalf("desktop MCP legacy SSE UI is missing %q", marker)
		}
	}
}

func TestDesktopCodexProviderUsesBuiltinRuntimeAndReasoningControl(t *testing.T) {
	index, err := frontendAssets.ReadFile("frontend/dist/index.html")
	if err != nil {
		t.Fatal(err)
	}
	script, err := frontendAssets.ReadFile("frontend/dist/app.js")
	if err != nil {
		t.Fatal(err)
	}
	html, js := string(index), string(script)
	for _, marker := range []string{
		`value="codex-chatgpt"`, `正式版已内置 Codex`, `id="start-codex-login"`,
		`高级：使用外部 Codex CLI`, `id="reasoning-effort"`,
	} {
		if !strings.Contains(html, marker) {
			t.Fatalf("desktop Codex provider UI is missing %q", marker)
		}
	}
	for _, marker := range []string{
		`backend().RefreshCodexStatus`, `backend().StartCodexDeviceLogin`,
		`window.runtime.EventsOn("codex:changed"`, `executablePath: profile.executablePath`, `formatCodexRateLimits`,
	} {
		if !strings.Contains(js, marker) {
			t.Fatalf("desktop Codex provider wiring is missing %q", marker)
		}
	}
}

func TestDesktopCredentialSyncIsExplicitEncryptedAndBidirectional(t *testing.T) {
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
	for _, marker := range []string{
		`id="sync-provider-credentials"`, `id="sync-codex-login"`, `id="sync-github-credentials"`,
		`id="sync-agent-settings"`, `id="sync-knowledge"`, `id="sync-mcp"`,
		`id="sync-mcp-credentials"`, `id="sync-saved-workflows"`,
		`id="pull-credentials"`, `id="push-credentials"`,
		`id="credential-sync-modal"`, `端到端加密`, `GitHub 登录与 Token`, `失败都会回滚`,
	} {
		if !strings.Contains(html, marker) {
			t.Fatalf("desktop credential sync UI is missing %q", marker)
		}
	}
	for _, marker := range []string{
		`backend().PushCredentialsToPhone`, `backend().PullCredentialsFromPhone`,
		`config.secureSyncReady`, `openCredentialSyncConfirmation`, `includeGitHubCredentials`, `importedGitHubToken`, `includeMcpCredentials`,
	} {
		if !strings.Contains(js, marker) {
			t.Fatalf("desktop credential sync wiring is missing %q", marker)
		}
	}
	if !strings.Contains(css, ".credential-sync-confirm-panel") {
		t.Fatal("desktop credential sync confirmation styling is missing")
	}
}

func TestDesktopRemoteNodeOffersDirectAndEncryptedCloudRelayModes(t *testing.T) {
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
	for _, marker := range []string{
		`id="remote-connection-mode"`, `value="direct"`, `value="cloud_relay"`,
		`id="remote-cloud-relay-url"`, `id="remote-cloud-relay-code"`,
		`端到端加密`, `公网地址必须使用 WSS`, `一次性配对码`,
		`Tailscale 直连（推荐，无需服务器）`, `普通用户保持官方地址即可`,
		`wss://murongagent.rl1.cc/relay/v1/connect`, `自建中继说明`,
	} {
		if !strings.Contains(html, marker) {
			t.Fatalf("desktop cloud relay UI is missing %q", marker)
		}
	}
	for _, marker := range []string{
		`connectionMode: $("#remote-connection-mode").value`,
		`cloudRelayUrl: $("#remote-cloud-relay-url").value.trim()`,
		`cloudRelayCode: $("#remote-cloud-relay-code").value.trim()`,
		`renderRemoteConnectionMode`, `config.cloudRelayConfigured`,
	} {
		if !strings.Contains(js, marker) {
			t.Fatalf("desktop cloud relay wiring is missing %q", marker)
		}
	}
	if !strings.Contains(css, ".secure-relay-panel") || !strings.Contains(css, ".relay-deploy-help") {
		t.Fatal("desktop cloud relay styling is missing")
	}
}

func TestDesktopGenerationSettingsAreVisibleSavedAndSynced(t *testing.T) {
	index, err := frontendAssets.ReadFile("frontend/dist/index.html")
	if err != nil {
		t.Fatal(err)
	}
	script, err := frontendAssets.ReadFile("frontend/dist/app.js")
	if err != nil {
		t.Fatal(err)
	}
	html, js := string(index), string(script)
	for _, marker := range []string{`id="temperature"`, `id="max-tokens"`, `id="enable-multimodal-messages"`, `生成参数、图片开关，以及计划/子代理默认执行 Profile`} {
		if !strings.Contains(html, marker) {
			t.Fatalf("desktop generation settings UI is missing %q", marker)
		}
	}
	for _, marker := range []string{`temperature: Number($("#temperature").value)`, `maxTokens: Number($("#max-tokens").value)`, `enableMultimodalMessages: $("#enable-multimodal-messages").checked`} {
		if !strings.Contains(js, marker) {
			t.Fatalf("desktop generation settings are not saved: %q", marker)
		}
	}
}

func TestDesktopExecutionProfilesAreVisibleSavedAndAppliedToTheComposer(t *testing.T) {
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
	for _, marker := range []string{
		`id="planner-profile-enabled"`, `id="planner-model"`, `id="planner-reasoning-effort"`,
		`id="subagent-profile-enabled"`, `id="subagent-default-model"`, `id="subagent-default-reasoning-effort"`,
	} {
		if !strings.Contains(html, marker) {
			t.Fatalf("desktop execution profile UI is missing %q", marker)
		}
	}
	for _, marker := range []string{
		`plannerProfileEnabled: $("#planner-profile-enabled").checked`,
		`plannerModel: $("#planner-model").value.trim()`,
		`plannerReasoningEffort: $("#planner-reasoning-effort").value`,
		`subagentDefaultProfileEnabled: $("#subagent-profile-enabled").checked`,
		`subagentDefaultModel: $("#subagent-default-model").value.trim()`,
		`subagentDefaultReasoningEffort: $("#subagent-default-reasoning-effort").value`,
		`function renderExecutionProfileControls()`,
		`const plannerActive = Boolean(state.active?.planModeEnabled && state.config?.plannerProfileEnabled);`,
	} {
		if !strings.Contains(js, marker) {
			t.Fatalf("desktop execution profile wiring is missing %q", marker)
		}
	}
	if !strings.Contains(css, ".execution-profile-grid") {
		t.Fatal("desktop execution profile styling is missing")
	}
}

func TestDesktopComposerMultimodalImagesAreSelectedPreviewedAndSent(t *testing.T) {
	script, err := frontendAssets.ReadFile("frontend/dist/app.js")
	if err != nil {
		t.Fatal(err)
	}
	styles, err := frontendAssets.ReadFile("frontend/dist/styles.css")
	if err != nil {
		t.Fatal(err)
	}
	js, css := string(script), string(styles)
	for _, marker := range []string{
		`kind: "image", id: "select-images"`, `backend().SelectChatImages()`,
		`images: state.composerImages.map`, `backend().GetChatImageDataURL`, `backend().DiscardChatImage`,
		`state.config?.enableMultimodalMessages !== false`, `图片消息已在 Agent 设置中关闭`,
	} {
		if !strings.Contains(js, marker) {
			t.Fatalf("desktop multimodal composer is missing %q", marker)
		}
	}
	for _, marker := range []string{".message-image-gallery", ".composer-context-chip.image-chip"} {
		if !strings.Contains(css, marker) {
			t.Fatalf("desktop multimodal UI is missing %q", marker)
		}
	}
}

func TestDesktopProjectSubagentTemplateEditorPersistsBesideInheritedTools(t *testing.T) {
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
	for _, marker := range []string{`id="project-subagent-template-panel"`, `id="add-project-subagent-template"`, `id="project-subagent-template-list"`, `即使当前项目沿用全局审批设置`} {
		if !strings.Contains(html, marker) {
			t.Fatalf("project subagent template UI is missing %q", marker)
		}
	}
	for _, marker := range []string{`collectProjectSubagentTemplates()`, `renderProjectSubagentTemplates()`, `preferredReasoningEffort`, `allowCodeEdits`, `state.projectTools.preferences.subagentTemplates`} {
		if !strings.Contains(js, marker) {
			t.Fatalf("project subagent template wiring is missing %q", marker)
		}
	}
	for _, marker := range []string{`.project-subagent-template-panel`, `.subagent-template-editor`, `.subagent-template-permissions`} {
		if !strings.Contains(css, marker) {
			t.Fatalf("project subagent template styling is missing %q", marker)
		}
	}
}

func TestDesktopAskUserCardRestoresAndSubmitsValidatedAnswers(t *testing.T) {
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
	for _, marker := range []string{`id="ask-user-card"`, `id="ask-user-questions"`, `id="submit-ask-user"`, `id="dismiss-ask-user"`} {
		if !strings.Contains(html, marker) {
			t.Fatalf("desktop ask_user UI is missing %q", marker)
		}
	}
	for _, marker := range []string{
		`window.runtime.EventsOn("agent:ask-user"`, `window.runtime.EventsOn("agent:ask-user-cleared"`,
		`backend().PendingAsk(sessionId)`, `backend().ResolveAsk(decision)`, `function collectAskUserAnswers()`,
		`input.type = question.multiSelect ? "checkbox" : "radio"`, `custom.maxLength = 500`,
	} {
		if !strings.Contains(js, marker) {
			t.Fatalf("desktop ask_user wiring is missing %q", marker)
		}
	}
	for _, marker := range []string{`.ask-user-card`, `.ask-question`, `.ask-option:has(input:checked)`, `.ask-custom-answer`} {
		if !strings.Contains(css, marker) {
			t.Fatalf("desktop ask_user styling is missing %q", marker)
		}
	}
}

func TestDesktopSessionProjectBindingIsVisibleAndDrivesSelection(t *testing.T) {
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
	for _, marker := range []string{`id="session-project-binding"`, `id="session-project-path"`, `id="session-project-action"`} {
		if !strings.Contains(html, marker) {
			t.Fatalf("session project UI is missing %q", marker)
		}
	}
	for _, marker := range []string{`backend().SelectSession(id)`, `backend().BindSessionProject({`, `function activeProjectPath()`, `state.sessionProjectAvailable`} {
		if !strings.Contains(js, marker) {
			t.Fatalf("session project wiring is missing %q", marker)
		}
	}
	for _, marker := range []string{`.session-project-binding`, `.meta-chip.error`} {
		if !strings.Contains(css, marker) {
			t.Fatalf("session project styling is missing %q", marker)
		}
	}
}

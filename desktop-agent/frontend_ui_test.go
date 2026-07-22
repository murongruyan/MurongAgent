package main

import (
	"os"
	"strings"
	"testing"
)

func TestAsyncModelSelectorsCaptureTheEventTargetBeforeAwait(t *testing.T) {
	script, err := frontendAssets.ReadFile("frontend/dist/app.js")
	if err != nil {
		t.Fatal(err)
	}
	js := string(script)
	for _, marker := range []string{
		"async function activateComposerModel(event) {\n  const target = event.currentTarget;",
		"async function setComposerReasoningEffort(event) {\n  const target = event.currentTarget;",
		"const selectedLabel = target?.selectedOptions?.[0]?.textContent",
	} {
		if !strings.Contains(js, marker) {
			t.Fatalf("async model selector regression guard is missing %q", marker)
		}
	}
}

func TestDesktopSidebarGroupsSessionsByProjectAndUsesCategorisedSettingsDialog(t *testing.T) {
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
		`<div class="section-caption">项目</div>`, `class="sidebar-nav" aria-label="功能面板"`,
		`id="open-settings"`, `id="settings-modal"`, `id="settings-category-list"`, `id="settings-category-content"`,
		`data-settings-section="model"`, `data-settings-section="rules"`, `data-settings-section="memory"`,
		`data-settings-section="skills"`, `data-settings-section="mcp"`, `data-settings-section="remote"`,
	} {
		if !strings.Contains(html, marker) {
			t.Fatalf("desktop project sidebar or utility dialog is missing %q", marker)
		}
	}
	if strings.Contains(html, `class="nav-item active" data-view="chat"`) {
		t.Fatal("chat must remain the base page instead of occupying a utility navigation icon")
	}
	if strings.Count(html, `class="nav-item"`) != 1 {
		t.Fatal("desktop sidebar must expose one compact settings entry")
	}
	for _, marker := range []string{
		`function effectiveSessionProjectPath(session)`, `session?.projectPath || state.config?.projectPath`,
		`function sessionProjectGroupKey(projectPath)`, `session-project-header`, `session-project-toggle-label`,
		`murong.sessions.projectCollapsed`, `function initialiseSettingsDialog()`,
		`modal.parentElement !== document.body`, `document.body.append(modal)`,
		`function openSettingsCategory(categoryKey = "model")`, `document.querySelectorAll("[data-settings-section]")`,
	} {
		if !strings.Contains(js, marker) {
			t.Fatalf("desktop project grouping or utility dialog wiring is missing %q", marker)
		}
	}
	for _, marker := range []string{
		`.session-project-group`, `.session-project-header`, `.session-project-group.collapsed .session-project-sessions`,
		`.settings-modal-backdrop`, `.settings-dialog`, `.settings-category-list`, `.settings-category-content`,
	} {
		if !strings.Contains(css, marker) {
			t.Fatalf("desktop project grouping or utility dialog styling is missing %q", marker)
		}
	}
}

func TestDesktopChatComposerKeepsPrimaryControlsVisible(t *testing.T) {
	index, err := frontendAssets.ReadFile("frontend/dist/index.html")
	if err != nil {
		t.Fatal(err)
	}
	html := string(index)
	for _, marker := range []string{
		`id="toggle-summary-rail"`,
		`id="toggle-bottom-terminal"`,
		`id="toggle-workbench"`,
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
	if strings.Contains(html, `id="session-details"`) || strings.Contains(html, `id="delete-session"`) {
		t.Fatal("task details and deletion must live in the session context menu, not the chat topbar")
	}
}

func TestDesktopChatComposerRecallsPersistentInputHistory(t *testing.T) {
	script, err := frontendAssets.ReadFile("frontend/dist/app.js")
	if err != nil {
		t.Fatal(err)
	}
	js := string(script)
	for _, marker := range []string{
		`const composerInputHistoryStorageKey = "murong.chat.inputHistory";`,
		`const composerInputHistoryLimit = 50;`,
		`restoreComposerInputHistory();`,
		`syncComposerInputHistoryFromActiveSession();`,
		`function syncComposerInputHistoryFromActiveSession()`,
		`.filter((message) => message?.role === "user")`,
		`.map((message) => String(message?.content || "").trim())`,
		`rememberComposerInput(content);`,
		`function recallPreviousComposerInput(input)`,
		`function recallNextComposerInput(input)`,
		`function handleComposerInputHistoryKeydown(event)`,
		`event.key === "ArrowUp"`,
		`event.key === "ArrowDown"`,
		`resetComposerInputHistoryNavigation(updatedText)`,
	} {
		if !strings.Contains(js, marker) {
			t.Fatalf("desktop composer input history is missing %q", marker)
		}
	}
	if !strings.Contains(js, `state.composerInputHistory = state.composerInputHistory.filter((value) => value !== sentText);`) {
		t.Fatal("desktop composer input history must match Android duplicate removal")
	}
	if !strings.Contains(js, `state.composerInputDraftBeforeHistory = input.value;`) || !strings.Contains(js, `applyRecalledComposerInput(input, draft);`) {
		t.Fatal("desktop composer history navigation must preserve and restore the current draft")
	}
	if !strings.Contains(js, `merged = merged.filter((value) => value !== sentText);`) || !strings.Contains(js, `merged.push(sentText);`) {
		t.Fatal("desktop composer must seed history from the active session in chronological order")
	}
}

func TestDesktopConversationSummaryRailAndPanelButtonsAreWired(t *testing.T) {
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
	for _, marker := range []string{`id="conversation-summary-rail"`, `id="conversation-summary-markers"`, `id="toggle-summary-rail"`, `id="toggle-bottom-terminal"`, `id="toggle-workbench"`} {
		if !strings.Contains(html, marker) {
			t.Fatalf("desktop summary or panel control is missing %q", marker)
		}
	}
	for _, marker := range []string{`function renderConversationSummaryRail()`, `function highlightSummaryMarkers(activeIndex)`, `marker.dataset.distance = distance`, `function toggleBottomTerminal()`, `function toggleSideWorkbench()`} {
		if !strings.Contains(js, marker) {
			t.Fatalf("desktop summary or panel wiring is missing %q", marker)
		}
	}
	for _, marker := range []string{`.conversation-summary-marker[data-distance="0"]`, `width: 40px`, `.conversation-summary-marker[data-distance="1"]`, `width: 30px`, `.conversation-summary-marker[data-distance="2"]`, `width: 20px`} {
		if !strings.Contains(css, marker) {
			t.Fatalf("desktop summary hover hierarchy is missing %q", marker)
		}
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
		`id="session-context-menu"`, `id="session-details-modal"`,
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
		`function openSessionContextMenu(event, session)`, `任务详情与重命名`, `在侧边任务中打开`, `再次点击确认删除`,
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
		`function activeProviderSendProblem()`, `profile.providerId === "codex-chatgpt"`,
		`const providerProblem = activeProviderSendProblem()`,
	} {
		if !strings.Contains(js, marker) {
			t.Fatalf("desktop Codex provider wiring is missing %q", marker)
		}
	}
	if strings.Contains(js, `!state.config.model || !state.config.hasApiKey`) {
		t.Fatal("desktop send preflight must not require an API key from Codex / ChatGPT profiles")
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
		`id="sync-sessions"`, `id="sync-provider-credentials"`, `id="sync-codex-login"`, `id="sync-github-credentials"`,
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
		`config.secureSyncReady`, `openCredentialSyncConfirmation`, `includeSessions`, `importedSessions`, `includeGitHubCredentials`, `importedGitHubToken`, `includeMcpCredentials`,
	} {
		if !strings.Contains(js, marker) {
			t.Fatalf("desktop credential sync wiring is missing %q", marker)
		}
	}
	if !strings.Contains(css, ".credential-sync-confirm-panel") {
		t.Fatal("desktop credential sync confirmation styling is missing")
	}
}

func TestDesktopRemoteNodeOffersDeviceIDLANAndADBModes(t *testing.T) {
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
		`id="remote-device-id"`, `id="copy-remote-device-id"`, `id="share-remote-device-id"`,
		`id="remote-paired-device"`, `id="reconnect-remote-paired"`, `id="clear-remote-pairing"`,
		`id="remote-target-device-id"`, `id="connect-remote-device"`, `请输入要配对的设备`,
		`id="remote-environment-devices"`, `id="refresh-remote-environment"`, `当前环境已有设备`,
		`id="remote-connect-modal"`, `id="remote-connect-auth-method"`, `id="remote-connect-secret"`,
		`id="request-remote-connect"`, `id="confirm-remote-connect"`,
		`id="remote-more-settings"`, `id="remote-do-not-disturb"`, `id="remote-blocked-peers"`,
		`id="block-remote-incoming"`,
	} {
		if !strings.Contains(html, marker) {
			t.Fatalf("desktop device connection UI is missing %q", marker)
		}
	}
	for _, marker := range []string{
		`connectionMode: $("#remote-connection-mode").value`,
		`adbSerial: $("#remote-adb-serial").value.trim()`,
		`peerDeviceId: $("#remote-target-device-id").value.trim()`,
		`backend().DiscoverRemoteDevices()`, `backend().DiscoverRemoteADBDevices()`,
		`refreshRemoteEnvironment`, `renderRemoteEnvironmentDevices`,
		`openRemoteConnectDialog`, `confirmRemoteConnect`, `shareRemoteDeviceID`,
		`$("#remote-pair-code").value = ""`, `await startRemoteNode()`,
		`SetRemoteDoNotDisturb`, `BlockRemoteConnectionRequest`, `UnblockRemotePeer`,
	} {
		if !strings.Contains(js, marker) {
			t.Fatalf("desktop device connection wiring is missing %q", marker)
		}
	}
	if !strings.Contains(css, ".remote-environment-device") || !strings.Contains(css, ".remote-connect-panel") || !strings.Contains(css, ".remote-blocked-peer") {
		t.Fatal("desktop device connection styling is missing")
	}
	for _, removed := range []string{
		`value="cloud_relay"`, `remote-cloud-relay-code`, `MR1.`, `自建中继说明`,
		`id="remote-pairing-code-panel"`, `输入手机本机 ID（推荐，支持异网）`, `REMOTE NODE`,
	} {
		if strings.Contains(html, removed) || strings.Contains(js, removed) {
			t.Fatalf("removed MR1 UI is still present: %q", removed)
		}
	}
}

func TestDesktopWindowCloseUsesNotificationTray(t *testing.T) {
	script, err := frontendAssets.ReadFile("frontend/dist/app.js")
	if err != nil {
		t.Fatal(err)
	}
	index, err := frontendAssets.ReadFile("frontend/dist/index.html")
	if err != nil {
		t.Fatal(err)
	}
	mainSource, err := os.ReadFile("main.go")
	if err != nil {
		t.Fatal(err)
	}
	traySource, err := os.ReadFile("application_tray_windows.go")
	if err != nil {
		t.Fatal(err)
	}
	js, html, mainGo, trayGo := string(script), string(index), string(mainSource), string(traySource)
	if !strings.Contains(js, `$("#window-close").addEventListener("click", () => backend().HideMainWindow())`) {
		t.Fatal("desktop close button does not hide the main window through the tray-aware backend")
	}
	if strings.Contains(js, `$("#window-close").addEventListener("click", () => window.runtime?.Quit())`) {
		t.Fatal("desktop close button still exits the process directly")
	}
	if !strings.Contains(mainGo, "OnBeforeClose:            app.beforeClose") {
		t.Fatal("native window close is not routed through the tray-aware close guard")
	}
	for _, marker := range []string{"Shell_NotifyIconW", "打开 Murong", "退出 Murong", "trayWMLButtonDblClk"} {
		if !strings.Contains(trayGo, marker) {
			t.Fatalf("Windows tray implementation is missing %q", marker)
		}
	}
	if !strings.Contains(html, "关闭主窗口后 Murong 会驻留系统托盘并继续调度") {
		t.Fatal("desktop scheduling copy does not explain tray residency")
	}
}

func TestDesktopWorkbenchProvidesTabbedComputerToolsAndStatus(t *testing.T) {
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
	vendor, err := frontendAssets.ReadFile("frontend/dist/workbench-vendor.js")
	if err != nil {
		t.Fatal(err)
	}
	vendorStyles, err := frontendAssets.ReadFile("frontend/dist/workbench-vendor.css")
	if err != nil {
		t.Fatal(err)
	}
	html, js, css := string(index), string(script), string(styles)
	for _, marker := range []string{
		`id="toggle-workbench"`, `id="workbench-panel"`, `id="workbench-resizer"`, `id="workbench-tabstrip"`,
		`data-workbench-tool="terminal"`, `data-workbench-tool="browser"`, `data-workbench-tool="editor"`,
		`data-workbench-tool="git"`, `data-workbench-tool="subagents"`, `data-workbench-tool="sidechat"`,
		`id="terminal-dock-setting"`, `id="default-terminal-setting"`, `id="workbench-terminal-host"`,
		`id="workbench-code-editor"`, `id="workbench-image-preview"`,
		`id="agent-statusbar"`, `id="statusbar-cache"`, `id="statusbar-tokens"`, `id="statusbar-context"`,
	} {
		if !strings.Contains(html, marker) {
			t.Fatalf("desktop workbench UI is missing %q", marker)
		}
	}
	for _, marker := range []string{
		"openWorkbenchTab", "StartWorkbenchTerminalSession", "WriteWorkbenchTerminalSession", "ResizeWorkbenchTerminalSession", "CloseWorkbenchTerminalSession",
		"ListWorkbenchFiles", "ReadWorkbenchFile", "ReadWorkbenchAsset", "SaveWorkbenchFile", "MurongWorkbenchVendor",
		"GetWorkbenchGit", "BrowserOpenURL", "refreshWorkbenchSideChat", "refreshStatusBar",
		"beginWorkbenchResize", "clampWorkbenchWidth", "murong.workbench.width",
	} {
		if !strings.Contains(js, marker) {
			t.Fatalf("desktop workbench wiring is missing %q", marker)
		}
	}
	for _, marker := range []string{".workbench-panel", ".workbench-resizer", ".workbench-tab", ".workbench-terminal-host", ".workbench-editor-layout", ".workbench-image-preview", ".agent-statusbar", ".workbench-terminal-bottom", "--sidebar-width", "--workbench-width"} {
		if !strings.Contains(css, marker) {
			t.Fatalf("desktop workbench styling is missing %q", marker)
		}
	}
	for _, marker := range []string{"Terminal", "FitAddon", "createCodeEditor", "setCodeEditorDocument"} {
		if !strings.Contains(string(vendor), marker) {
			t.Fatalf("desktop workbench vendor bundle is missing %q", marker)
		}
	}
	if !strings.Contains(string(vendorStyles), ".xterm") {
		t.Fatal("desktop workbench vendor CSS does not contain xterm styling")
	}
	for _, obsolete := range []string{`id="workbench-terminal-form"`, `id="workbench-terminal-command"`, `id="workbench-editor-content"`} {
		if strings.Contains(html, obsolete) {
			t.Fatalf("desktop workbench still contains obsolete command-form UI %q", obsolete)
		}
	}
}

func TestWindowsStartupClosesTheWailsSingleInstanceRace(t *testing.T) {
	source, err := os.ReadFile("application_instance_windows.go")
	if err != nil {
		t.Fatal(err)
	}
	text := string(source)
	for _, marker := range []string{"CreateMutex", "ERROR_ALREADY_EXISTS", "notifyExistingApplicationInstance", "wailsSingleInstanceCopyDataID", "activateExistingApplicationWindow"} {
		if !strings.Contains(text, marker) {
			t.Fatalf("Windows early single-instance guard is missing %q", marker)
		}
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

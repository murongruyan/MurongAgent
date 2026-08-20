const state = {
	platform: { os: "", architecture: "", label: "电脑端", credentialProtection: "本机凭据保护", packageKind: "", version: "" },
  desktopUpdate: { checking: false, checked: false, error: "", currentVersion: "", latestVersion: "", updateAvailable: false, releaseUrl: "", downloadUrl: "", packageName: "", publishedAt: "" },
  config: null,
  sessions: [],
  active: null,
  sessionProjectAvailable: false,
  sessionProjectError: "",
  terminals: [],
  remoteNode: null,
  remoteDevices: [],
  remoteDiscoveryBusy: false,
  remoteDiscoveryLastAt: 0,
  remoteAdbDevices: [],
  remoteAdbDiscoveryBusy: false,
  remoteAdbDiscoveryLastAt: 0,
  pendingRemoteConnectMode: "device_id",
  knowledge: { rules: [], memories: [], skills: [] },
  projectKnowledge: { projectPath: "", projectLabel: "", hasProject: false, library: { rules: [], memories: [], skills: [] } },
  projectTools: { projectPath: "", projectLabel: "", hasProject: false, usesGlobal: true, preferences: null },
  mcp: { servers: [], statuses: [], tools: [] },
  savedWorkflows: { github: { apiBaseUrl: "https://api.github.com", hasToken: false, viewer: "" }, githubAccounts: [], activeGitHubAccount: "", workflows: [] },
  backup: { settings: { dailyBackupEnabled: false, maxBackupCount: 7 }, automaticBackupCount: 0, preRestoreSnapshotCount: 0 },
  mcpExpanded: false,
  composerCatalog: { skills: [], subagents: [], mcpTools: [] },
  composerContext: [],
  composerImages: [],
  imageGenerationMode: false,
  composerPickerMode: "",
  composerProjectEntries: [],
  composerBrowserPath: "",
  composerSearchToken: 0,
  goalModeEnabled: false,
  knowledgeScope: "global",
  toolPolicyScope: "global",
  running: false,
  runningSessions: {},
  autoFollowMessages: true,
  providerModelCatalogs: {},
  approval: null,
  askUser: null,
  pendingWorkflowRunId: "",
  pendingGitHubAccountRemoval: null,
  pendingBackupRestore: null,
  sessionStats: null,
  sessionCompressionRunning: false,
  pendingSessionPoint: null,
  pendingCredentialSyncDirection: "",
  credentialSyncRunning: false,
  workspaceChanges: { projectPath: "", watching: false, pendingChanges: [], recentChanges: [], omittedCount: 0, error: "" },
  projectAudit: { projectPath: "", entries: [], totalCount: 0, filteredCount: 0, hasMore: false, archiveVersion: 1 },
  workspaceTimelineMode: "live",
  projectAuditQuery: { search: "", source: "all", beforeAt: 0, beforeId: "" },
  selectedProjectAuditId: "",
  codex: { available: false, builtin: false, running: false, loggedIn: false, requiresAuth: true, models: [], login: {}, accountPool: { activeAccountId: "", settings: { autoSwitch: true, reservePercent: 10, cooldownMinutes: 15 }, accounts: [] } },
  selectedWorkspacePath: "",
  view: "chat",
  settingsCategory: "model",
  summaryRailVisible: true,
  sessionContextId: "",
  sessionProjectCollapsed: {},
  composerInputHistory: [],
  composerInputHistoryIndex: -1,
  composerInputDraftBeforeHistory: "",
  composerInputHistorySessionId: "",
  composerAIConfigCategory: "",
  layout: { sidebarWidth: 280, contentPadding: 48 },
  workbench: { open: false, tabs: [], activeId: "", terminalDock: "side", defaultTerminalId: "", width: 620, resizing: null, sequence: 0 },
  statusStats: null,
  statusStatsRequest: 0,
  builtinVision: {
    models: [],
    installingTier: "",
    downloadedBytes: 0,
    totalBytes: 0,
    message: "",
    error: "",
    deviceRecommendation: "",
  },
  pendingProviderImport: null,
  providerImportBusy: false,
  providerImportProtocol: { supported: false, murongRegistered: false, ccSwitchCompatibilityEnabled: false, ccSwitchCurrentHandlerLabel: "尚未检查" },
  providerUsage: { items: [] },
};

const $ = (selector) => document.querySelector(selector);
const backend = () => window.go?.main?.DesktopAgentApp;
const composerInputHistoryStorageKey = "murong.chat.inputHistory";
const composerInputHistoryLimit = 50;
let workbenchEditorController = null;
let builtinVisionPollTimer = 0;

const settingsCategories = [
  { key: "project", label: "项目", view: "settings", description: "选择当前任务使用的电脑项目目录。" },
    { key: "model", label: "模型", view: "settings", description: "管理模型连接、API Key、模型与推理强度。" },
    { key: "voice", label: "语音", view: "settings", description: "管理完全离线的中文朗读与语音输入模型。" },
    { key: "permissions", label: "审批与工具", view: "settings", description: "设置审批模式、白名单和 Agent 可调用的工具。" },
  { key: "gui", label: "界面操作", view: "settings", description: "配置 Windows 语义树、截图识别、本地视觉模型与远程隐私边界。" },
  { key: "agent", label: "Agent", view: "settings", description: "调整系统提示词、回答详细度和执行 Profile。" },
  { key: "rules", label: "规则", view: "knowledge", description: "管理每轮都会遵守的全局或项目规则。" },
  { key: "memory", label: "记忆", view: "knowledge", description: "管理按需检索的长期记忆。" },
  { key: "skills", label: "Skills", view: "knowledge", description: "管理可复用的 Agent 工作方法。" },
  { key: "mcp", label: "MCP", view: "knowledge", description: "配置本机或远程 MCP 工具服务器。" },
  { key: "github", label: "GitHub", view: "automation", description: "管理 GitHub API 地址与登录凭据。" },
  { key: "workflows", label: "工作流", view: "automation", description: "创建和管理保存的自动化工作流。" },
  { key: "remote", label: "远程控制", view: "remote", description: "连接手机、管理设备和电脑远程能力。" },
  { key: "device-sync", label: "设备同步", view: "remote", description: "选择手机与电脑之间需要同步的数据。" },
  { key: "backup", label: "备份与恢复", view: "settings", description: "创建完整备份、恢复快照并设置保留数量。" },
  { key: "terminal", label: "终端", view: "settings", description: "选择默认 Shell 和终端显示位置。" },
  { key: "appearance", label: "界面", view: "settings", description: "调整侧栏、内容边距和工作台宽度。" },
  { key: "about", label: "关于与更新", view: "settings", description: "查看电脑版版本，并检查 GitHub 最新正式版。" },
];

document.addEventListener("DOMContentLoaded", initialise);

async function initialise() {
  try {
    await waitForBackend();
    restoreWorkbenchPreferences();
    restoreSessionProjectPreferences();
    restoreSummaryRailPreference();
    restoreComposerInputHistory();
    initialiseSettingsDialog();
    bindEvents();
    bindRuntimeEvents();
    const bootstrap = await backend().Bootstrap();
    applyBootstrap(bootstrap);
    await refreshProviderImportIntegration();
    await refreshBuiltinVisionStatus();
    await restorePendingAsk();
    await refreshComposerCatalog();
    $("#loading").classList.add("hidden");
    $("#app").classList.remove("hidden");
    void checkDesktopUpdate(false);
  } catch (error) {
    console.error(error);
    $("#loading p").textContent = `启动失败：${errorText(error)}`;
  }
}

function waitForBackend() {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const timer = setInterval(() => {
      if (backend()) {
        clearInterval(timer);
        resolve();
      } else if (Date.now() - started > 15000) {
        clearInterval(timer);
        reject(new Error("Wails 后端绑定超时"));
      }
    }, 40);
  });
}

function applyBootstrap(value) {
	state.platform = value.platform || state.platform;
  state.config = value.config;
  state.sessions = value.sessions || [];
  state.active = value.activeSession || null;
  syncComposerInputHistoryFromActiveSession();
  state.sessionProjectAvailable = state.active ? Boolean(value.activeProjectAvailable) : Boolean(value.config?.projectPath);
  state.sessionProjectError = value.activeProjectError || "";
  state.terminals = value.terminals || [];
  state.remoteNode = value.remoteNode;
  state.knowledge = value.knowledge || { rules: [], memories: [], skills: [] };
  state.projectKnowledge = value.projectKnowledge || { projectPath: "", projectLabel: "", hasProject: false, library: { rules: [], memories: [], skills: [] } };
  state.projectTools = value.projectTools || { projectPath: "", projectLabel: "", hasProject: false, usesGlobal: true, preferences: null };
  state.mcp = value.mcp || { servers: [], statuses: [], tools: [] };
  state.savedWorkflows = value.savedWorkflows || { github: { apiBaseUrl: "https://api.github.com", hasToken: false, viewer: "" }, workflows: [] };
  state.backup = value.backup || { settings: { dailyBackupEnabled: false, maxBackupCount: 7 }, automaticBackupCount: 0, preRestoreSnapshotCount: 0 };
  state.workspaceChanges = value.workspaceChanges || { projectPath: "", watching: false, pendingChanges: [], recentChanges: [], omittedCount: 0, error: "" };
  state.codex = value.codex || { available: false, builtin: false, running: false, loggedIn: false, requiresAuth: true, models: [], login: {} };
  renderSessions();
  renderActiveSession();
  renderSettings();
  renderKnowledge();
  renderMCP();
  renderAutomation();
  renderRemoteNode();
  renderComposerContext();
  renderWorkbench();
  refreshStatusBar();
}

function bindEvents() {
  $("#window-minimise").addEventListener("click", () => window.runtime?.WindowMinimise());
  $("#window-maximise").addEventListener("click", () => window.runtime?.WindowToggleMaximise());
  $("#window-close").addEventListener("click", () => backend().HideMainWindow());
  $("#toggle-workbench").addEventListener("click", toggleSideWorkbench);
  $("#toggle-bottom-terminal").addEventListener("click", toggleBottomTerminal);
  $("#toggle-summary-rail").addEventListener("click", toggleSummaryRail);
  $("#close-workbench").addEventListener("click", closeWorkbench);
  $("#workbench-resizer").addEventListener("pointerdown", beginWorkbenchResize);
  $("#workbench-resizer").addEventListener("pointermove", continueWorkbenchResize);
  $("#workbench-resizer").addEventListener("pointerup", finishWorkbenchResize);
  $("#workbench-resizer").addEventListener("pointercancel", finishWorkbenchResize);
  $("#workbench-resizer").addEventListener("dblclick", resetWorkbenchWidth);
  $("#workbench-resizer").addEventListener("keydown", resizeWorkbenchFromKeyboard);
  window.addEventListener("resize", applyWorkbenchWidth);
  $("#workbench-add-tab").addEventListener("click", toggleWorkbenchAddMenu);
  document.querySelectorAll("[data-workbench-tool]").forEach((button) => button.addEventListener("click", () => {
    closeWorkbenchAddMenu();
    openWorkbenchTab(button.dataset.workbenchTool);
  }));
  document.addEventListener("click", closeWorkbenchAddMenuFromOutside);
  document.addEventListener("keydown", handleWorkbenchShortcut);
  $("#terminal-dock-setting").addEventListener("change", updateTerminalDockPreference);
  $("#default-terminal-setting").addEventListener("change", updateDefaultTerminalPreference);
  $("#apply-layout-settings").addEventListener("click", applyLayoutSettingsFromForm);
  $("#reset-layout-settings").addEventListener("click", resetLayoutSettings);
  $("#check-desktop-update").addEventListener("click", () => checkDesktopUpdate(true));
  $("#download-desktop-update").addEventListener("click", openDesktopUpdateDownload);
  $("#desktop-update-banner-download").addEventListener("click", openDesktopUpdateDownload);
  $("#desktop-update-banner-dismiss").addEventListener("click", () => $("#desktop-update-banner").classList.add("hidden"));
  $("#clear-workbench-terminal").addEventListener("click", clearWorkbenchTerminal);
  $("#workbench-browser-form").addEventListener("submit", navigateWorkbenchBrowser);
  $("#workbench-browser-back").addEventListener("click", () => navigateWorkbenchBrowserHistory(-1));
  $("#workbench-browser-forward").addEventListener("click", () => navigateWorkbenchBrowserHistory(1));
  $("#workbench-browser-refresh").addEventListener("click", refreshWorkbenchBrowser);
  $("#workbench-browser-external").addEventListener("click", openWorkbenchBrowserExternal);
  $("#workbench-editor-up").addEventListener("click", workbenchEditorUp);
  $("#workbench-editor-new").addEventListener("click", newWorkbenchEditorFile);
  $("#workbench-editor-refresh").addEventListener("click", refreshWorkbenchEditor);
  $("#workbench-editor-save").addEventListener("click", saveWorkbenchEditorFile);
  document.querySelectorAll("[data-markdown-mode]").forEach((button) => button.addEventListener("click", setWorkbenchMarkdownMode));
  $("#workbench-git-refresh").addEventListener("click", refreshWorkbenchGit);
  $("#workbench-sidechat-session").addEventListener("change", changeWorkbenchSideChatSession);
  $("#workbench-sidechat-refresh").addEventListener("click", refreshWorkbenchSideChat);
  $("#workbench-sidechat-form").addEventListener("submit", sendWorkbenchSideChat);
  $("#new-session").addEventListener("click", createSession);
  $("#send-message").addEventListener("click", handleComposerAction);
  $("#message-input").addEventListener("keydown", (event) => {
    if (handleComposerInputHistoryKeydown(event)) return;
    if (event.key === "@" && !event.ctrlKey && !event.altKey && !event.metaKey) {
      const input = event.currentTarget;
      const before = input.value.slice(0, input.selectionStart);
      if (before === "" || /\s$/.test(before)) {
        event.preventDefault();
        openComposerPicker("files");
        return;
      }
    }
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      handleComposerAction();
    }
  });
  $("#message-input").addEventListener("input", handleComposerInputChange);
  $("#messages").addEventListener("scroll", updateMessageAutoFollow, { passive: true });
  $("#close-session-details").addEventListener("click", closeSessionDetails);
  $("#session-details-modal").addEventListener("click", (event) => { if (event.target === event.currentTarget) closeSessionDetails(); });
  $("#rename-session-form").addEventListener("submit", renameActiveSession);
  $("#session-project-action").addEventListener("click", () => {
    closeSessionDetails();
    openProjectModal();
  });
  $("#fork-session").addEventListener("click", () => forkSessionFrom(""));
  $("#compress-session").addEventListener("click", compressActiveSession);
  $("#toggle-session-compression").addEventListener("click", toggleActiveSessionCompression);
  $("#import-session").addEventListener("click", importSession);
  $("#export-session-json").addEventListener("click", () => exportActiveSession("json"));
  $("#export-session-portable").addEventListener("click", () => exportActiveSession("portable"));
  $("#export-session-markdown").addEventListener("click", () => exportActiveSession("markdown"));
  $("#cancel-session-action").addEventListener("click", closeSessionActionConfirmation);
  $("#confirm-session-action").addEventListener("click", confirmSessionRollback);
  $("#session-action-modal").addEventListener("click", (event) => { if (event.target === event.currentTarget) closeSessionActionConfirmation(); });
  $("#workspace-changes-chip").addEventListener("click", () => openWorkspaceChanges());
  $("#close-workspace-changes").addEventListener("click", closeWorkspaceChanges);
  $("#workspace-changes-modal").addEventListener("click", (event) => { if (event.target === event.currentTarget) closeWorkspaceChanges(); });
  $("#refresh-workspace-changes").addEventListener("click", () => {
    if (state.workspaceTimelineMode === "audit") refreshProjectAudit(true);
    else refreshWorkspaceChanges();
  });
  $("#clear-workspace-changes").addEventListener("click", clearWorkspaceChanges);
  $("#workspace-live-tab").addEventListener("click", () => setWorkspaceTimelineMode("live"));
  $("#project-audit-tab").addEventListener("click", () => setWorkspaceTimelineMode("audit"));
  let auditSearchTimer = 0;
  $("#project-audit-search").addEventListener("input", (event) => {
    state.projectAuditQuery.search = event.currentTarget.value;
    clearTimeout(auditSearchTimer);
    auditSearchTimer = setTimeout(() => refreshProjectAudit(true), 180);
  });
  $("#project-audit-source").addEventListener("change", (event) => {
    state.projectAuditQuery.source = event.currentTarget.value;
    refreshProjectAudit(true);
  });
  $("#project-audit-load-more").addEventListener("click", () => refreshProjectAudit(false));
  $("#export-project-audit-json").addEventListener("click", () => exportProjectAudit("json"));
  $("#export-project-audit-markdown").addEventListener("click", () => exportProjectAudit("markdown"));
  let clearAuditArmedUntil = 0;
  $("#clear-project-audit").addEventListener("click", async () => {
    if (Date.now() > clearAuditArmedUntil) {
      clearAuditArmedUntil = Date.now() + 4000;
      showToast("再点一次清空当前项目的全部审计归档");
      return;
    }
    try {
      state.projectAudit = await backend().ClearProjectAudit(true);
      state.selectedProjectAuditId = "";
      renderWorkspaceChangesModal();
      showToast("当前项目审计归档已清空");
    } catch (error) {
      showToast(errorText(error), true);
    }
  });
  let reclaimArmedUntil = 0;
  $("#reclaim-session-execution").addEventListener("click", async () => {
    if (!state.active || !phoneOwnsActiveSession()) return;
    if (Date.now() > reclaimArmedUntil) {
      reclaimArmedUntil = Date.now() + 5000;
      showToast("强制收回会使手机接管令牌立即失效；再点一次确认");
      return;
    }
    try {
      state.active = await backend().ReclaimSessionExecution(state.active.id);
      renderSessions();
      renderActiveSession();
      showToast("电脑已强制收回执行权；手机副本不会自动合并");
    } catch (error) {
      showToast(errorText(error), true);
    }
  });
  $("#open-settings").addEventListener("click", () => openSettingsCategory(state.settingsCategory || "model"));
  $("#close-settings").addEventListener("click", closeSettingsDialog);
  $("#settings-modal").addEventListener("click", (event) => { if (event.target === event.currentTarget) closeSettingsDialog(); });
  document.addEventListener("click", closeSessionContextMenuFromOutside);
  document.addEventListener("contextmenu", closeSessionContextMenuFromOutside);
  document.addEventListener("click", closeWorkbenchFileContextMenuFromOutside);
  document.addEventListener("contextmenu", closeWorkbenchFileContextMenuFromOutside);
  $("#project-chip").addEventListener("click", () => openProjectModal());
  $("#choose-project").addEventListener("click", chooseProject);
  $("#manage-projects").addEventListener("click", () => openProjectModal());
  $("#create-project").addEventListener("click", () => openProjectModal(true));
  $("#close-project-modal").addEventListener("click", closeProjectModal);
  $("#project-modal").addEventListener("click", (event) => { if (event.target === event.currentTarget) closeProjectModal(); });
  $("#open-project-folder").addEventListener("click", chooseProject);
  $("#show-create-project").addEventListener("click", showCreateProjectForm);
  $("#back-to-projects").addEventListener("click", showProjectHome);
  $("#cancel-create-project").addEventListener("click", showProjectHome);
  $("#choose-project-parent").addEventListener("click", chooseProjectParent);
  $("#create-project-form").addEventListener("submit", createProject);
  $("#composer-add").addEventListener("click", () => openComposerPicker("all"));
  $("#composer-image-generate").addEventListener("click", toggleComposerImageGenerationMode);
  $("#close-composer-picker").addEventListener("click", closeComposerPicker);
  $("#composer-picker-search").addEventListener("input", scheduleComposerPickerRender);
  $("#composer-ai-config-trigger").addEventListener("click", toggleComposerAIConfigMenu);
  $("#composer-ai-config-back").addEventListener("click", showComposerAIConfigRoot);
  $("#composer-ai-config-list").addEventListener("click", handleComposerAIConfigAction);
  document.addEventListener("click", closeComposerAIConfigFromOutside);
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") closeComposerAIConfigMenu();
  });
  $("#save-settings").addEventListener("click", saveSettings);
  $("#refresh-provider-models").addEventListener("click", refreshProviderModels);
  $("#model-name").addEventListener("input", () => {
    const profile = activeProviderProfile();
    if (profile) profile.model = $("#model-name").value.trim();
    renderProviderModelCandidates();
  });
  $("#refresh-builtin-vision-models").addEventListener("click", refreshBuiltinVisionStatus);
  $("#cancel-builtin-vision-install").addEventListener("click", cancelBuiltinVisionInstall);
  $("#builtin-vision-model-list").addEventListener("click", handleBuiltinVisionModelAction);
  $("#gui-allow-remote-screenshots").addEventListener("change", (event) => {
    const fullScreen = $("#gui-allow-remote-fullscreen");
    fullScreen.disabled = !event.target.checked;
    if (!event.target.checked) fullScreen.checked = false;
  });
  ["#planner-profile-enabled", "#subagent-profile-enabled"].forEach((selector) => {
    $(selector).addEventListener("change", renderExecutionProfileControls);
  });
  ["#planner-model", "#planner-reasoning-effort", "#subagent-default-model", "#subagent-default-reasoning-effort"].forEach((selector) => {
    $(selector).addEventListener("input", renderExecutionProfileControls);
    $(selector).addEventListener("change", renderExecutionProfileControls);
  });
  $("#save-backup-settings").addEventListener("click", saveBackupSettings);
  $("#create-manual-backup").addEventListener("click", createManualBackup);
  $("#select-backup-restore").addEventListener("click", selectBackupForRestore);
  $("#cancel-backup-restore").addEventListener("click", closeBackupRestore);
  $("#confirm-backup-restore").addEventListener("click", confirmBackupRestore);
  $("#backup-restore-confirm-text").addEventListener("input", updateBackupRestoreConfirmation);
  $("#backup-restore-modal").addEventListener("click", (event) => { if (event.target === event.currentTarget) closeBackupRestore(); });
  $("#provider-profile-select").addEventListener("change", (event) => {
    commitProviderProfileForm();
    state.config.activeProviderProfileId = event.target.value;
    renderProviderProfileEditor();
    renderExecutionProfileControls();
  });
  $("#add-provider-profile").addEventListener("click", addProviderProfile);
  $("#remove-provider-profile").addEventListener("click", removeProviderProfile);
  $("#provider-kind").addEventListener("change", changeProviderKind);
  $("#refresh-codex-status").addEventListener("click", refreshCodexStatus);
  $("#start-codex-login").addEventListener("click", startCodexLogin);
  $("#add-codex-account").addEventListener("click", createCodexAccount);
  $("#save-codex-pool-settings").addEventListener("click", saveCodexAccountPoolSettings);
  $("#pin-session-codex-account").addEventListener("change", pinActiveSessionCodexAccount);
  $("#codex-account-list").addEventListener("click", handleCodexAccountAction);
  $("#ccswitch-protocol-compatibility").addEventListener("change", updateCCSwitchProtocolCompatibility);
  $("#refresh-provider-usage").addEventListener("click", refreshActiveProviderUsage);
  $("#close-provider-import").addEventListener("click", cancelProviderImport);
  $("#cancel-provider-import").addEventListener("click", cancelProviderImport);
  $("#confirm-provider-import").addEventListener("click", confirmProviderImport);
  $("#codex-account-list").addEventListener("change", handleCodexAccountToggle);
  document.querySelectorAll("[data-tool-scope]").forEach((button) => {
    button.addEventListener("click", () => switchToolPolicyScope(button.dataset.toolScope));
  });
  $("#project-tools-use-global").addEventListener("change", (event) => {
    commitToolPolicyForm();
    state.projectTools.usesGlobal = event.target.checked;
    renderToolPolicyEditor();
  });
  $("#add-project-subagent-template").addEventListener("click", addProjectSubagentTemplate);
  $("#add-rule").addEventListener("click", () => addKnowledgeItem("rules"));
  $("#add-memory").addEventListener("click", () => addKnowledgeItem("memories"));
  $("#add-skill").addEventListener("click", () => addKnowledgeItem("skills"));
  $("#save-knowledge").addEventListener("click", saveKnowledge);
  $("#toggle-mcp-panel").addEventListener("click", toggleMCPPanel);
  $("#add-nuphus-mcp").addEventListener("click", addNuphusMCPServer);
  $("#add-mcp-server").addEventListener("click", addMCPServer);
  $("#save-mcp-servers").addEventListener("click", saveMCPServers);
  $("#connect-mcp-servers").addEventListener("click", connectMCPServers);
  $("#save-github").addEventListener("click", saveGitHubConfig);
  $("#test-github").addEventListener("click", testGitHubConnection);
  $("#add-github-account").addEventListener("click", createGitHubAccount);
  $("#github-account-list").addEventListener("click", handleGitHubAccountAction);
  $("#cancel-github-account-remove").addEventListener("click", closeGitHubAccountRemoval);
  $("#confirm-github-account-remove").addEventListener("click", confirmGitHubAccountRemoval);
  $("#github-account-remove-modal").addEventListener("click", (event) => { if (event.target === event.currentTarget) closeGitHubAccountRemoval(); });
  $("#add-saved-workflow").addEventListener("click", () => openWorkflowEditor());
  $("#close-workflow-editor").addEventListener("click", closeWorkflowEditor);
  $("#cancel-workflow-editor").addEventListener("click", closeWorkflowEditor);
  $("#workflow-editor-modal").addEventListener("click", (event) => { if (event.target === event.currentTarget) closeWorkflowEditor(); });
  $("#workflow-editor-form").addEventListener("submit", saveSavedWorkflow);
  $("#workflow-template").addEventListener("change", updateWorkflowEditorTemplate);
  $("#choose-workflow-project").addEventListener("click", chooseWorkflowProject);
  $("#cancel-workflow-run").addEventListener("click", closeWorkflowRunConfirmation);
  $("#confirm-workflow-run").addEventListener("click", confirmSavedWorkflowRun);
  $("#workflow-confirm-modal").addEventListener("click", (event) => { if (event.target === event.currentTarget) closeWorkflowRunConfirmation(); });
  document.querySelectorAll("[data-knowledge-scope]").forEach((button) => {
    button.addEventListener("click", () => switchKnowledgeScope(button.dataset.knowledgeScope));
  });
  $("#approval-mode").addEventListener("change", updateAllowlistVisibility);
  $("#approve-tool").addEventListener("click", () => resolveApproval(true));
  $("#reject-tool").addEventListener("click", () => resolveApproval(false));
  $("#submit-ask-user").addEventListener("click", submitAskUser);
  $("#dismiss-ask-user").addEventListener("click", dismissAskUser);
  $("#choose-remote-workspace").addEventListener("click", chooseRemoteWorkspace);
  $("#connect-remote-device").addEventListener("click", () => openRemoteConnectDialog("device_id"));
  $("#refresh-remote-environment").addEventListener("click", () => refreshRemoteEnvironment(true));
  $("#copy-remote-device-id").addEventListener("click", async () => {
    const deviceID = state.remoteNode?.config?.deviceDisplayId || "";
    if (!deviceID) return;
    try {
      await navigator.clipboard.writeText(deviceID);
      showToast("本机 ID 已复制");
    } catch (error) {
      showToast(errorText(error), true);
    }
  });
  $("#share-remote-device-id").addEventListener("click", shareRemoteDeviceID);
  $("#copy-remote-temporary-code").addEventListener("click", async () => {
    const code = state.remoteNode?.temporaryCode || "";
    if (code) {
      await navigator.clipboard.writeText(code);
      showToast("临时验证码已复制");
    }
  });
  $("#rotate-remote-temporary-code").addEventListener("click", rotateRemoteTemporaryCode);
  $("#change-remote-security-password").addEventListener("click", () => {
    $("#remote-security-password").value = "";
    $("#remote-password-modal").classList.remove("hidden");
    $("#remote-security-password").focus();
  });
  $("#cancel-remote-security-password").addEventListener("click", closeRemoteSecurityPassword);
  $("#save-remote-security-password").addEventListener("click", saveRemoteSecurityPassword);
  $("#clear-remote-security-password").addEventListener("click", clearRemoteSecurityPassword);
  $("#reconnect-remote-paired").addEventListener("click", startRemoteNode);
  $("#remote-connect-auth-method").addEventListener("change", renderRemoteConnectDialog);
  $("#cancel-remote-connect").addEventListener("click", closeRemoteConnectDialog);
  $("#request-remote-connect").addEventListener("click", () => confirmRemoteConnect(true));
  $("#confirm-remote-connect").addEventListener("click", () => confirmRemoteConnect(false));
  $("#approve-remote-incoming").addEventListener("click", () => decideRemoteIncoming(true));
  $("#reject-remote-incoming").addEventListener("click", () => decideRemoteIncoming(false));
  $("#block-remote-incoming").addEventListener("click", () => decideRemoteIncoming("block"));
  $("#remote-do-not-disturb").addEventListener("change", updateRemoteDoNotDisturb);
  $("#remote-connect-modal").addEventListener("click", (event) => { if (event.target === event.currentTarget) closeRemoteConnectDialog(); });
  $("#save-remote").addEventListener("click", saveRemoteNodeConfig);
  $("#stop-remote").addEventListener("click", stopRemoteNode);
  $("#push-credentials").addEventListener("click", () => openCredentialSyncConfirmation("to_phone"));
  $("#pull-credentials").addEventListener("click", () => openCredentialSyncConfirmation("from_phone"));
  $("#cancel-credential-sync").addEventListener("click", closeCredentialSyncConfirmation);
  $("#confirm-credential-sync").addEventListener("click", performCredentialSync);
  $("#credential-sync-modal").addEventListener("click", (event) => { if (event.target === event.currentTarget) closeCredentialSyncConfirmation(); });
  $("#sync-mcp").addEventListener("change", renderCredentialSyncState);
  $("#remote-share-desktop-tasks").addEventListener("change", (event) => {
    const control = $("#remote-allow-agent-control");
    control.disabled = !event.target.checked;
    if (!event.target.checked) control.checked = false;
  });
  let clearRemoteArmedUntil = 0;
  $("#clear-remote-pairing").addEventListener("click", async () => {
    if (Date.now() > clearRemoteArmedUntil) {
      clearRemoteArmedUntil = Date.now() + 3000;
      showToast("再点一次撤销该设备的配对");
      return;
    }
    try {
      state.remoteNode = await backend().ClearRemoteNodePairing();
      renderRemoteNode();
      showToast("已在双方撤销配对");
    } catch (error) {
      showToast(errorText(error), true);
    }
  });
  document.querySelectorAll(".suggestion").forEach((button) => {
    button.addEventListener("click", () => {
      setComposerInputValue(button.textContent.trim());
    });
  });
  document.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    if (!$("#composer-picker").classList.contains("hidden")) closeComposerPicker();
    else if (!$("#project-modal").classList.contains("hidden")) closeProjectModal();
    else if (!$("#workflow-editor-modal").classList.contains("hidden")) closeWorkflowEditor();
    else if (!$("#workflow-confirm-modal").classList.contains("hidden")) closeWorkflowRunConfirmation();
    else if (!$("#backup-restore-modal").classList.contains("hidden")) closeBackupRestore();
    else if (!$("#provider-import-modal").classList.contains("hidden")) cancelProviderImport();
    else if (!$("#credential-sync-modal").classList.contains("hidden")) closeCredentialSyncConfirmation();
    else if (!$("#workspace-changes-modal").classList.contains("hidden")) closeWorkspaceChanges();
    else if (!$("#session-action-modal").classList.contains("hidden")) closeSessionActionConfirmation();
    else if (!$("#session-details-modal").classList.contains("hidden")) closeSessionDetails();
    else if (!$("#session-context-menu").classList.contains("hidden")) closeSessionContextMenu();
    else if (state.view !== "chat") closeSettingsDialog();
  });
  bindEmptyProjectActions(document);
}

function bindRuntimeEvents() {
  if (!window.runtime?.EventsOn) return;
  window.runtime.EventsOn("workbench:terminal-output", (payload) => {
    const tab = workbenchTerminalTab(payload);
    if (!tab?.terminalView || !payload?.base64) return;
    try {
      tab.terminalView.write(decodeBase64Bytes(payload.base64));
    } catch (error) {
      tab.terminalView.writeln(`\r\n[终端输出解码失败] ${errorText(error)}`);
    }
  });
  window.runtime.EventsOn("workbench:terminal-exit", (payload) => {
    const tab = workbenchTerminalTab(payload);
    if (!tab) return;
    tab.exited = true;
    tab.starting = false;
    tab.sessionId = "";
    tab.terminalView?.writeln(`\r\n[终端已退出 · 代码 ${payload?.exitCode ?? -1}]`);
    if (activeWorkbenchTab()?.id === tab.id) renderWorkbenchTerminal(tab);
  });
  window.runtime.EventsOn("sessions:changed", (payload) => {
    state.sessions = payload.sessions || [];
    if (payload.activeSession && (!state.active || payload.activeSession.id === state.active.id)) {
      state.active = payload.activeSession;
    } else if (state.active && !state.sessions.some((session) => session.id === state.active.id)) {
      state.active = null;
    }
    syncComposerInputHistoryFromActiveSession();
    renderSessions();
    renderActiveSession();
    const sideChat = activeWorkbenchTab("sidechat");
    if (sideChat && payload.activeSession?.id === sideChat.sessionId) {
      sideChat.session = payload.activeSession;
      renderWorkbench();
    }
    refreshStatusBar(true);
  });
  window.runtime.EventsOn("settings:changed", (config) => {
    const projectChanged = (state.config?.projectPath || "") !== (config?.projectPath || "");
    state.config = config;
    if (projectChanged) {
      state.composerContext = [];
      state.projectAudit = { projectPath: config?.projectPath || "", entries: [], totalCount: 0, filteredCount: 0, hasMore: false, archiveVersion: 1 };
      state.selectedProjectAuditId = "";
      state.selectedWorkspacePath = "";
      $("#project-audit-count").textContent = "0";
      closeComposerPicker();
      renderComposerContext();
		if (!$("#workspace-changes-modal").classList.contains("hidden")) refreshProjectAudit(true);
    }
    renderSettings();
    if (projectChanged) renderActiveSession();
    else renderHeaderMeta();
    if (!$("#project-modal").classList.contains("hidden")) renderProjectModal();
  });
  window.runtime.EventsOn("builtin_vision_status", (status) => {
    applyBuiltinVisionStatus(status);
  });
  window.runtime.EventsOn("knowledge:changed", (knowledge) => {
    state.knowledge = knowledge || { rules: [], memories: [], skills: [] };
    renderKnowledge();
    refreshComposerCatalog();
  });
  window.runtime.EventsOn("project-knowledge:changed", (snapshot) => {
    state.projectKnowledge = snapshot || { projectPath: "", projectLabel: "", hasProject: false, library: { rules: [], memories: [], skills: [] } };
    if (!state.projectKnowledge.hasProject && state.knowledgeScope === "project") state.knowledgeScope = "global";
    renderKnowledge();
    refreshComposerCatalog();
  });
  window.runtime.EventsOn("project-tools:changed", (snapshot) => {
    state.projectTools = snapshot || { projectPath: "", projectLabel: "", hasProject: false, usesGlobal: true, preferences: null };
    if (!state.projectTools.hasProject && state.toolPolicyScope === "project") state.toolPolicyScope = "global";
    renderToolPolicyEditor();
  });
  window.runtime.EventsOn("mcp:changed", (snapshot) => {
    state.mcp = snapshot || { servers: [], statuses: [], tools: [] };
    renderMCP();
    refreshComposerCatalog();
  });
  window.runtime.EventsOn("saved-workflows:changed", (snapshot) => {
    state.savedWorkflows = snapshot || { github: { apiBaseUrl: "https://api.github.com", hasToken: false, viewer: "" }, githubAccounts: [], activeGitHubAccount: "", workflows: [] };
    renderAutomation();
  });
  window.runtime.EventsOn("external-workflow:status", (payload) => {
    openSettingsCategory("workflows");
    showToast(payload?.message || "外部工作流状态已更新", !payload?.success);
  });
  window.runtime.EventsOn("backup:changed", (snapshot) => {
    state.backup = snapshot || { settings: { dailyBackupEnabled: false, maxBackupCount: 7 }, automaticBackupCount: 0, preRestoreSnapshotCount: 0 };
    renderBackup();
  });
  window.runtime.EventsOn("workspace:changed", (snapshot) => {
    state.workspaceChanges = snapshot || { projectPath: "", watching: false, pendingChanges: [], recentChanges: [], omittedCount: 0, error: "" };
    renderWorkspaceChangeChip();
    if (!$("#workspace-changes-modal").classList.contains("hidden")) renderWorkspaceChangesModal();
  });
  window.runtime.EventsOn("project-audit:changed", (snapshot) => {
    if (!state.projectAuditQuery.search && (!state.projectAuditQuery.source || state.projectAuditQuery.source === "all")) {
      state.projectAudit = snapshot || { projectPath: "", entries: [], totalCount: 0, filteredCount: 0, hasMore: false, archiveVersion: 1 };
      $("#project-audit-count").textContent = formatCount(state.projectAudit.totalCount || 0);
      if (state.workspaceTimelineMode === "audit" && !$("#workspace-changes-modal").classList.contains("hidden")) renderWorkspaceChangesModal();
    } else if (state.workspaceTimelineMode === "audit" && !$("#workspace-changes-modal").classList.contains("hidden")) {
      refreshProjectAudit(true);
    }
  });
  window.runtime.EventsOn("remote-node:changed", (snapshot) => {
    state.remoteNode = snapshot;
    renderRemoteNode(false);
  });
  window.runtime.EventsOn("codex:changed", (snapshot) => {
    state.codex = snapshot || { available: false, builtin: false, running: false, loggedIn: false, requiresAuth: true, models: [], login: {} };
    renderCodexProviderStatus();
  });
  window.runtime.EventsOn("agent:status", (payload) => {
    $("#statusbar-run").textContent = payload.text || payload.state || "就绪";
    if (!payload.sessionId) return;
    state.runningSessions[payload.sessionId] = payload.state === "running";
    if (payload.state === "generating_image") state.runningSessions[payload.sessionId] = true;
    if (!state.active || payload.sessionId !== state.active.id) return;
    state.running = payload.state === "running" || payload.state === "generating_image";
    $("#run-status").textContent = payload.text || payload.state;
		renderComposerModelSelect();
		updateComposerActionState();
		renderComposerContext();
    if (payload.state === "error") showToast(payload.text, true);
  });
  window.runtime.EventsOn("agent:stream-start", (payload) => {
    if (state.active?.id !== payload.sessionId) return;
    createStreamingMessage(payload.streamId);
  });
  window.runtime.EventsOn("agent:delta", (payload) => {
    if (state.active?.id !== payload.sessionId) return;
    const body = document.querySelector(`[data-stream-id="${cssEscape(payload.streamId)}"] .message-body`);
    if (body) {
      body.dataset.raw = (body.dataset.raw || "") + (payload.delta || "");
      body.textContent = body.dataset.raw;
      scrollMessages();
    }
  });
  window.runtime.EventsOn("agent:reasoning-start", (payload) => {
    if (state.active?.id !== payload.sessionId) return;
    const message = document.querySelector(`[data-stream-id="${cssEscape(payload.streamId)}"]`);
    if (!message || message.querySelector(".message-reasoning")) return;
    const stack = message.querySelector(".message-content-stack");
    if (stack) stack.prepend(createReasoningDetails("", true));
    scrollMessages();
  });
  window.runtime.EventsOn("agent:reasoning-delta", (payload) => {
    if (state.active?.id !== payload.sessionId) return;
    const body = document.querySelector(`[data-stream-id="${cssEscape(payload.streamId)}"] .message-reasoning-body`);
    if (!body) return;
    body.dataset.raw = (body.dataset.raw || "") + (payload.delta || "");
    body.textContent = body.dataset.raw || "模型正在生成思考过程…";
    scrollMessages();
  });
  window.runtime.EventsOn("agent:stream-end", (payload) => {
    const message = document.querySelector(`[data-stream-id="${cssEscape(payload.streamId)}"]`);
    if (!message) return;
    const body = message.querySelector(".message-body");
    body.classList.remove("stream-cursor");
    body.innerHTML = renderMarkdown(body.dataset.raw || "");
    const reasoning = message.querySelector(".message-reasoning");
    if (reasoning) {
      reasoning.classList.remove("active");
      reasoning.open = false;
      const summary = reasoning.querySelector("summary");
      const reasoningBody = reasoning.querySelector(".message-reasoning-body");
      if (summary) summary.textContent = "思考过程";
      if (reasoningBody) reasoningBody.innerHTML = renderMarkdown(reasoningBody.dataset.raw || "");
    }
    refreshStatusBar(true);
  });
  window.runtime.EventsOn("agent:approval", (payload) => {
    if (state.active?.id !== payload.sessionId) return;
    state.approval = payload;
    $("#approval-summary").textContent = payload.summary;
    $("#approval-detail").textContent = payload.detail;
    $("#approval-card").classList.remove("hidden");
    $("#run-status").textContent = "等待审批";
  });
  window.runtime.EventsOn("agent:ask-user", (payload) => {
    if (state.active?.id !== payload.sessionId) return;
    state.askUser = payload;
    renderAskUser();
    $("#run-status").textContent = "等待回答";
  });
  window.runtime.EventsOn("agent:ask-user-cleared", (payload) => {
    if (state.askUser?.id !== payload.id) return;
    state.askUser = null;
    renderAskUser();
    if (state.running) $("#run-status").textContent = "正在继续";
  });
}

function initialiseSettingsDialog() {
  const modal = $("#settings-modal");
  if (modal && modal.parentElement !== document.body) document.body.append(modal);
  const list = $("#settings-category-list");
  list.replaceChildren();
  settingsCategories.forEach((category) => {
    const button = document.createElement("button");
    button.type = "button";
    button.dataset.settingsCategory = category.key;
    button.textContent = category.label;
    button.addEventListener("click", () => openSettingsCategory(category.key));
    list.append(button);
  });
}

function openSettingsCategory(categoryKey = "model") {
  const category = settingsCategories.find((item) => item.key === categoryKey) || settingsCategories.find((item) => item.key === "model");
  if (!category) return;
  state.settingsCategory = category.key;
  state.view = "settings";
  $("#settings-modal").classList.remove("hidden");
  $("#open-settings").classList.add("active");
  $("#open-settings").setAttribute("aria-pressed", "true");
  $("#settings-category-title").textContent = category.label;
  $("#settings-category-description").textContent = category.description;
  document.querySelectorAll("[data-settings-category]").forEach((button) => {
    const active = button.dataset.settingsCategory === category.key;
    button.classList.toggle("active", active);
    button.setAttribute("aria-current", active ? "page" : "false");
  });
  document.querySelectorAll(".utility-view").forEach((element) => {
    const active = element.id === `view-${category.view}`;
    element.classList.toggle("hidden", !active);
    element.setAttribute("aria-hidden", String(!active));
  });
  document.querySelectorAll("[data-settings-section]").forEach((element) => {
    element.classList.toggle("settings-section-hidden", element.dataset.settingsSection !== category.key);
  });
  document.querySelectorAll("[data-settings-visible-for]").forEach((element) => {
    const visible = element.dataset.settingsVisibleFor.split(",").includes(category.key);
    element.classList.toggle("settings-section-hidden", !visible);
  });
  if (category.key === "mcp" && !state.mcpExpanded) {
    state.mcpExpanded = true;
    renderMCP();
  }
  $("#settings-category-content").scrollTop = 0;
  if (category.view === "remote") refreshRemoteEnvironment(false);
}

function closeSettingsDialog() {
  state.view = "chat";
  $("#settings-modal").classList.add("hidden");
  $("#open-settings").classList.remove("active");
  $("#open-settings").setAttribute("aria-pressed", "false");
}

function switchView(view) {
  const aliases = { knowledge: "rules", automation: "github", settings: "model", remote: "remote" };
  if (view === "chat") closeSettingsDialog();
  else openSettingsCategory(aliases[view] || view);
}

async function createSession() {
  try {
    state.autoFollowMessages = true;
    state.active = await backend().CreateSession();
    syncComposerInputHistoryFromActiveSession();
    state.askUser = null;
    state.sessionProjectAvailable = Boolean(state.active?.projectPath || state.config?.projectPath);
    state.sessionProjectError = "";
    state.goalModeEnabled = false;
    switchView("chat");
    renderActiveSession();
    $("#message-input").focus();
  } catch (error) {
    showToast(errorText(error), true);
  }
}

async function selectSession(id) {
  try {
    state.autoFollowMessages = true;
    const selection = await backend().SelectSession(id);
    state.active = selection.session;
    syncComposerInputHistoryFromActiveSession();
    state.askUser = null;
    state.sessionProjectAvailable = Boolean(selection.projectAvailable);
    state.sessionProjectError = selection.projectError || "";
    await applyProjectConfiguration(selection.config, false);
    state.goalModeEnabled = false;
    switchView("chat");
    renderSessions();
    renderActiveSession();
    await restorePendingAsk();
  } catch (error) {
    showToast(errorText(error), true);
  }
}

function isComplexTask(text) {
  const normalized = String(text || "").trim();
  if (!normalized) return false;
  const keywords = [
    "自动", "下载", "接入", "集成", "重构", "架构", "规划", "计划", "迁移",
    "批量", "排查", "调试", "分析", "审查", "搜索", "联网", "修复", "实现",
    "设计", "优化", "整体", "深入", "彻底", "复杂",
    "mcp", "skill", "workflow", "subagent", "review", "debug", "refactor",
  ];
  const lower = normalized.toLowerCase();
  return keywords.some((word) => lower.includes(word));
}

function detectGoalModeIntent(text) {
  const normalized = String(text || "").trim();
  if (!normalized) return null;
  const phrases = [
    "开启目标模式", "打开目标模式", "进入目标模式", "设置目标模式",
    "启用目标模式", "开个目标模式", "开目标模式", "目标模式开始干",
    "开始目标模式", "用目标模式", "开启目标", "开个目标", "设为目标",
    "open goal mode", "start goal mode", "goal mode", "goal模式", "目标模式",
  ];
  let matched = null;
  let matchIndex = -1;
  const lower = normalized.toLowerCase();
  phrases.forEach((phrase) => {
    const idx = lower.indexOf(phrase.toLowerCase());
    if (idx >= 0 && (matchIndex < 0 || idx < matchIndex)) {
      matched = phrase;
      matchIndex = idx;
    }
  });
  if (!matched) return null;
  const punctuation = /^[，,。！!？?：:、 \n\t]+|[，,。！!？?：:、 \n\t]+$/g;
  const before = normalized.slice(0, matchIndex).replace(punctuation, "").trim();
  const after = normalized.slice(matchIndex + matched.length).replace(punctuation, "").trim();
  const filler = /(?:开始干吧|开始干|开始吧|开干|干吧|开始)$/;
  const usable = (value) =>
    value.length >= 2 && /[a-zA-Z0-9\u4e00-\u9fa5]/.test(value) && !filler.test(value);
  const preferred = matchIndex <= 6 ? after : before;
  if (usable(preferred)) return preferred;
  if (usable(before)) return before;
  if (usable(after)) return after;
  return normalized;
}

async function sendMessage() {
  const input = $("#message-input");
  const content = input.value.trim();
  if (!content && !state.composerImages.length) return;
  if (phoneOwnsActiveSession()) {
    showToast("这项任务正在手机上继续；请先从手机归还或强制收回", true);
    return;
  }
  const imageGeneration = state.imageGenerationMode;
  const projectPath = activeProjectPath();
  if (!imageGeneration && state.active && state.active.projectPath && !state.sessionProjectAvailable) {
    showToast(`当前任务绑定的项目不可用${state.sessionProjectError ? `：${state.sessionProjectError}` : ""}，请重新选择项目`, true);
    openProjectModal();
    return;
  }
  const providerProblem = activeProviderSendProblem();
  const imageGenerationProblem = imageGeneration ? imageGenerationSendProblem() : "";
  const sendProblem = imageGenerationProblem || providerProblem;
  if ((!imageGeneration && !projectPath) || sendProblem) {
    showToast(sendProblem || "请先选择项目", true);
    openSettingsCategory(sendProblem ? "model" : "project");
    return;
  }
  try {
    if (!state.active) state.active = await backend().CreateSession();
    const sessionId = state.active.id;
    if (imageGeneration) {
      if (state.composerImages.length) throw new Error("图片生成暂不支持把已选图片作为编辑输入，请先移除图片附件");
      state.autoFollowMessages = true;
      state.runningSessions[sessionId] = true;
      state.running = true;
      updateComposerActionState();
      renderComposerModelSelect();
      await backend().GenerateImage({ sessionId, prompt: content });
      rememberComposerInput(content);
      input.value = "";
      resetComposerInputHistoryNavigation("");
      state.imageGenerationMode = false;
      closeComposerPicker();
      renderComposerContext();
      autoSizeComposer();
      updateComposerActionState();
      return;
    }
    const planMode = Boolean(state.active.planModeEnabled);
    let mode = state.goalModeEnabled && planMode ? "goal_plan" : state.goalModeEnabled ? "goal" : planMode ? "plan" : "";
    let goal = "";
    if (!state.goalModeEnabled && !(state.active?.goal || "").trim()) {
      const autoGoal = detectGoalModeIntent(content);
      if (autoGoal) {
        goal = autoGoal;
        mode = planMode ? "goal_plan" : "goal";
      }
    }
    if (!mode && isComplexTask(content)) {
      mode = "plan";
      showToast("检测到复杂任务，已自动进入计划模式：模型先生成计划，点击计划卡片即可执行");
    }
    state.autoFollowMessages = true;
    state.runningSessions[sessionId] = true;
    state.running = true;
    updateComposerActionState();
    renderComposerModelSelect();
    await backend().SendMessage({
      sessionId,
      content,
      goal,
      context: state.composerContext,
      images: state.composerImages.map((value) => value.attachment),
      mode,
    });
    rememberComposerInput(content);
    input.value = "";
    resetComposerInputHistoryNavigation("");
    state.composerContext = [];
    state.composerImages = [];
    state.goalModeEnabled = false;
    closeComposerPicker();
    renderComposerContext();
    autoSizeComposer();
    updateComposerActionState();
  } catch (error) {
    const sessionId = state.active?.id;
    if (sessionId && state.runningSessions[sessionId] === true) {
      state.runningSessions[sessionId] = false;
      refreshRunningState();
      updateComposerActionState();
      renderComposerModelSelect();
    }
    showToast(errorText(error), true);
  }
}

async function handleComposerAction() {
  const hasMessage = Boolean($("#message-input").value.trim() || state.composerImages.length);
  if (state.running && !hasMessage) {
    if (state.active) await backend().CancelRun(state.active.id);
    return;
  }
  await sendMessage();
}

function updateComposerActionState() {
  const button = $("#send-message");
  if (!button) return;
  const hasMessage = Boolean($("#message-input")?.value.trim() || (!state.imageGenerationMode && state.composerImages.length));
  const locked = phoneOwnsActiveSession();
  const stopping = state.running && !hasMessage;
  button.dataset.action = stopping ? "stop" : "send";
  button.textContent = stopping ? "■" : "➤";
  button.title = stopping ? "终止生成" : state.running ? "发送引导" : "发送";
  button.setAttribute("aria-label", button.title);
  button.disabled = locked || (!state.running && !hasMessage);
  button.classList.toggle("stop", stopping);
}

function renderSessions() {
  const list = $("#session-list");
  list.replaceChildren();
  if (!state.sessions.length) {
    const empty = document.createElement("div");
    empty.className = "section-caption";
    empty.textContent = "还没有任务";
    list.append(empty);
    return;
  }
  const groups = new Map();
  state.sessions.forEach((session) => {
    const projectPath = effectiveSessionProjectPath(session);
    const key = sessionProjectGroupKey(projectPath);
    if (!groups.has(key)) groups.set(key, { key, projectPath, sessions: [] });
    groups.get(key).sessions.push(session);
  });
  const activeGroupKey = state.active ? sessionProjectGroupKey(effectiveSessionProjectPath(state.active)) : "";
  Array.from(groups.values()).forEach((group, index) => {
    const hasPreference = Object.prototype.hasOwnProperty.call(state.sessionProjectCollapsed, group.key);
    const collapsed = hasPreference ? Boolean(state.sessionProjectCollapsed[group.key]) : Boolean(activeGroupKey ? group.key !== activeGroupKey : index > 0);
    const section = document.createElement("section");
    section.className = `session-project-group${collapsed ? " collapsed" : ""}`;

    const header = document.createElement("button");
    header.type = "button";
    header.className = "session-project-header";
    header.title = group.projectPath || "这些任务尚未绑定项目";
    header.setAttribute("aria-expanded", String(!collapsed));
    const icon = document.createElement("span");
    icon.className = "session-project-icon";
    icon.setAttribute("aria-hidden", "true");
    icon.innerHTML = '<svg viewBox="0 0 24 24"><path d="M3.5 6.5A2.5 2.5 0 0 1 6 4h4l2 2h6A2.5 2.5 0 0 1 20.5 8.5v8A2.5 2.5 0 0 1 18 19H6a2.5 2.5 0 0 1-2.5-2.5Z"/></svg>';
    const label = document.createElement("strong");
    label.textContent = group.projectPath ? projectName(group.projectPath) : "未绑定项目";
    const count = document.createElement("small");
    count.textContent = String(group.sessions.length);
    const toggleLabel = document.createElement("span");
    toggleLabel.className = "session-project-toggle-label";
    toggleLabel.textContent = collapsed ? "展开" : "折叠";
    header.append(icon, label, count, toggleLabel);
    header.addEventListener("click", () => {
      state.sessionProjectCollapsed[group.key] = !collapsed;
      persistSessionProjectPreferences();
      renderSessions();
    });

    const sessions = document.createElement("div");
    sessions.className = "session-project-sessions";
    group.sessions.forEach((session) => {
      const button = document.createElement("button");
      button.type = "button";
      button.className = `session-item${state.active?.id === session.id ? " active" : ""}`;
      const title = document.createElement("span");
      title.className = "session-item-title";
      title.textContent = session.title || "新对话";
      const small = document.createElement("small");
      small.textContent = session.executionOwner === "android"
        ? `${session.messageCount} 条 · 手机接管中`
        : `${session.messageCount} 条 · ${formatTime(session.updatedAt)}`;
      button.append(title, small);
      button.addEventListener("click", () => selectSession(session.id));
      button.addEventListener("contextmenu", (event) => openSessionContextMenu(event, session));
      sessions.append(button);
    });
    section.append(header, sessions);
    list.append(section);
  });
}

function openSessionContextMenu(event, session) {
  event.preventDefault();
  event.stopPropagation();
  if (!session?.id) return;
  state.sessionContextId = session.id;
  const menu = $("#session-context-menu");
  menu.replaceChildren();
  const addAction = (label, action, options = {}) => {
    const button = document.createElement("button");
    button.type = "button";
    button.role = "menuitem";
    button.textContent = label;
    button.className = options.danger ? "danger" : "";
    button.disabled = Boolean(options.disabled);
    button.addEventListener("click", async (clickEvent) => {
      clickEvent.stopPropagation();
      if (options.confirm && button.dataset.armed !== "true") {
        button.dataset.armed = "true";
        button.textContent = options.confirm;
        button.classList.add("armed");
        setTimeout(() => {
          if (!button.isConnected) return;
          button.dataset.armed = "false";
          button.textContent = label;
          button.classList.remove("armed");
        }, 3000);
        return;
      }
      closeSessionContextMenu();
      try {
        await action();
      } catch (error) {
        showToast(errorText(error), true);
      }
    });
    menu.append(button);
  };
  const selectTarget = async () => {
    if (state.active?.id !== session.id) await selectSession(session.id);
  };
  addAction("打开任务", selectTarget);
  addAction("任务详情与重命名", async () => { await selectTarget(); await openSessionDetails(); });
  addAction("在侧边任务中打开", async () => {
    await selectTarget();
    const existing = state.workbench.tabs.find((tab) => tab.type === "sidechat" && tab.sessionId === session.id);
    if (existing) {
      state.workbench.open = true;
      state.workbench.activeId = existing.id;
      renderWorkbench();
    } else {
      openWorkbenchTab("sidechat");
    }
  });
  addAction("分叉任务", async () => { await selectTarget(); await forkSessionFrom(""); }, { disabled: state.running });
  const separator = document.createElement("div");
  separator.className = "session-context-separator";
  separator.role = "separator";
  menu.append(separator);
  addAction("导出 JSON", async () => { await selectTarget(); await exportActiveSession("json"); });
  addAction("导出 Markdown", async () => { await selectTarget(); await exportActiveSession("markdown"); });
  addAction("导出跨端 JSON", async () => { await selectTarget(); await exportActiveSession("portable"); });
  addAction("复制任务 ID", async () => { await navigator.clipboard.writeText(session.id); showToast("任务 ID 已复制"); });
  addAction("删除任务", async () => deleteSessionById(session.id), {
    danger: true,
    disabled: session.executionOwner === "android",
    confirm: "再次点击确认删除",
  });
  menu.classList.remove("hidden");
  const rect = menu.getBoundingClientRect();
  const margin = 8;
  menu.style.left = `${Math.max(margin, Math.min(event.clientX, window.innerWidth - rect.width - margin))}px`;
  menu.style.top = `${Math.max(38, Math.min(event.clientY, window.innerHeight - rect.height - 32))}px`;
}

function closeSessionContextMenu() {
  state.sessionContextId = "";
  $("#session-context-menu").classList.add("hidden");
}

function closeSessionContextMenuFromOutside(event) {
  const menu = $("#session-context-menu");
  if (!menu || menu.classList.contains("hidden") || menu.contains(event.target)) return;
  closeSessionContextMenu();
}

async function deleteSessionById(id) {
  if (!id) return;
  await backend().DeleteSession(id);
  state.sessions = state.sessions.filter((session) => session.id !== id);
  if (state.active?.id === id) {
    state.active = null;
    state.goalModeEnabled = false;
    renderComposerContext();
    renderActiveSession();
  }
  renderSessions();
  showToast("任务已删除");
}

function effectiveSessionProjectPath(session) {
  return String(session?.projectPath || state.config?.projectPath || "").trim();
}

function sessionProjectGroupKey(projectPath) {
  const normalized = String(projectPath || "").trim().replace(/[\\/]+$/, "").replace(/\\/g, "/").toLocaleLowerCase();
  return normalized ? `project:${normalized}` : "unbound";
}

function restoreSessionProjectPreferences() {
  try {
    const stored = JSON.parse(localStorage.getItem("murong.sessions.projectCollapsed") || "{}");
    state.sessionProjectCollapsed = stored && typeof stored === "object" && !Array.isArray(stored) ? stored : {};
  } catch (_) {
    state.sessionProjectCollapsed = {};
  }
}

function persistSessionProjectPreferences() {
  try {
    localStorage.setItem("murong.sessions.projectCollapsed", JSON.stringify(state.sessionProjectCollapsed));
  } catch (_) {}
}

function renderActiveSession() {
  refreshRunningState();
  renderHeaderMeta();
  renderExecutionHandoff();
  renderComposerContext();
  renderSubagentJobs();
  renderAskUser();
  refreshWorkbenchLiveViews();
  refreshStatusBar();
  const container = $("#messages");
  const preserveScroll = !state.autoFollowMessages;
  const distanceFromBottom = container.scrollHeight - container.scrollTop;
  container.replaceChildren();
  if (!state.active || !state.active.messages?.length) {
    const activeProject = activeProjectPath();
    container.innerHTML = `
      <div class="empty-state">
        <div class="empty-orbit"><span>✦</span></div>
        <h3>让 Murong 在电脑上直接工作</h3>
        <p>${activeProject ? `当前项目是 ${escapeHtml(projectName(activeProject))}。可以用下方上下文栏选择文件、Skill、子代理或 MCP。` : "选择项目和模型后，可以读取、编辑文件并调用当前系统实际安装的 Shell。"}</p>
        <div class="empty-project-actions">
          <button class="secondary" data-project-action="open">${activeProject ? "切换项目" : "打开项目文件夹"}</button>
          <button class="primary" data-project-action="create">新建项目</button>
        </div>
        <div class="suggestion-grid">
          <button class="suggestion">分析这个项目的结构</button>
          <button class="suggestion">运行测试并修复失败项</button>
          <button class="suggestion">检查最近的 Git 修改</button>
        </div>
      </div>`;
    container.querySelectorAll(".suggestion").forEach((button) => button.addEventListener("click", () => {
      setComposerInputValue(button.textContent.trim());
    }));
    bindEmptyProjectActions(container);
    renderConversationSummaryRail();
    return;
  }
  appendMessageTimeline(state.active.messages);
  renderConversationSummaryRail();
  if (preserveScroll) {
    requestAnimationFrame(() => {
      container.scrollTop = Math.max(0, container.scrollHeight - distanceFromBottom);
    });
  } else {
    scrollMessages();
  }
}

function restoreSummaryRailPreference() {
  try {
    state.summaryRailVisible = localStorage.getItem("murong.chat.summaryRail") !== "hidden";
  } catch (_) {
    state.summaryRailVisible = true;
  }
  applySummaryRailVisibility();
}

function toggleSummaryRail() {
  state.summaryRailVisible = !state.summaryRailVisible;
  try {
    localStorage.setItem("murong.chat.summaryRail", state.summaryRailVisible ? "visible" : "hidden");
  } catch (_) {}
  applySummaryRailVisibility();
}

function applySummaryRailVisibility() {
  const rail = $("#conversation-summary-rail");
  const button = $("#toggle-summary-rail");
  if (!rail || !button) return;
  rail.classList.toggle("hidden", !state.summaryRailVisible);
  button.classList.toggle("active", state.summaryRailVisible);
  button.setAttribute("aria-pressed", String(state.summaryRailVisible));
  button.title = state.summaryRailVisible ? "隐藏摘要跳转" : "显示摘要跳转";
  button.setAttribute("aria-label", button.title);
}

function renderConversationSummaryRail() {
  const rail = $("#conversation-summary-rail");
  const markers = $("#conversation-summary-markers");
  if (!rail || !markers) return;
  applySummaryRailVisibility();
  markers.replaceChildren();
  const messages = state.active?.messages || [];
  const segments = [];
  messages.forEach((message, index) => {
    if (message.role !== "user") return;
    const nextUserOffset = messages.slice(index + 1).findIndex((candidate) => candidate.role === "user");
    const segmentEnd = nextUserOffset < 0 ? messages.length : index + 1 + nextUserOffset;
    const response = messages.slice(index + 1, segmentEnd).find((candidate) => candidate.role === "assistant");
    segments.push({ message, response, index });
  });
  rail.classList.toggle("empty", segments.length === 0);
  segments.forEach((segment, segmentIndex) => {
    const marker = document.createElement("button");
    marker.type = "button";
    marker.className = "conversation-summary-marker";
    marker.classList.toggle("edge-start", segmentIndex === 0);
    marker.classList.toggle("edge-end", segmentIndex === segments.length - 1);
    marker.style.top = `${segments.length === 1 ? 50 : 4 + (segmentIndex / (segments.length - 1)) * 92}%`;
    marker.dataset.summaryIndex = String(segmentIndex);
    marker.setAttribute("aria-label", `跳转到第 ${segmentIndex + 1} 段：${summaryPreview(segment.message.content, 48)}`);
    const dash = document.createElement("span");
    dash.className = "conversation-summary-dash";
    const tooltip = document.createElement("span");
    tooltip.className = "conversation-summary-tooltip";
    const heading = document.createElement("strong");
    heading.textContent = `第 ${segmentIndex + 1} 段`;
    const userText = document.createElement("span");
    userText.textContent = summaryPreview(segment.message.content, 92) || "用户消息";
    tooltip.append(heading, userText);
    if (segment.response?.content) {
      const responseText = document.createElement("small");
      responseText.textContent = summaryPreview(segment.response.content, 120);
      tooltip.append(responseText);
    }
    marker.append(dash, tooltip);
    marker.addEventListener("mouseenter", () => highlightSummaryMarkers(segmentIndex));
    marker.addEventListener("focus", () => highlightSummaryMarkers(segmentIndex));
    marker.addEventListener("click", () => {
      const target = segment.message.id
        ? Array.from(document.querySelectorAll(".message[data-message-id]")).find((element) => element.dataset.messageId === segment.message.id)
        : $("#messages")?.children[segment.index];
      target?.scrollIntoView({ behavior: "smooth", block: "center" });
    });
    markers.append(marker);
  });
  rail.onmouseleave = clearSummaryMarkerHighlights;
  rail.onfocusout = (event) => { if (!rail.contains(event.relatedTarget)) clearSummaryMarkerHighlights(); };
}

function highlightSummaryMarkers(activeIndex) {
  document.querySelectorAll(".conversation-summary-marker").forEach((marker) => {
    const distance = Math.abs(Number(marker.dataset.summaryIndex) - activeIndex);
    marker.dataset.distance = distance <= 2 ? String(distance) : "far";
  });
}

function clearSummaryMarkerHighlights() {
  document.querySelectorAll(".conversation-summary-marker").forEach((marker) => delete marker.dataset.distance);
}

function summaryPreview(value, maxLength) {
  const text = String(value || "")
    .replace(/```[\s\S]*?```/g, " [代码] ")
    .replace(/[`*_>#\[\]()~-]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  return text.length > maxLength ? `${text.slice(0, maxLength)}…` : text;
}

async function executeWorkflowPlan() {
	if (!state.active || state.running || phoneOwnsActiveSession()) return;
	const sessionId = state.active.id;
	try {
		state.runningSessions[sessionId] = true;
		state.running = true;
		updateComposerActionState();
		renderComposerModelSelect();
		await backend().ExecuteWorkflowPlan(sessionId);
		if (state.runningSessions[sessionId] === true) {
			$("#run-status").textContent = "正在按计划执行";
		}
		updateComposerActionState();
		renderComposerContext();
	} catch (error) {
		if (state.runningSessions[sessionId] === true) {
			state.runningSessions[sessionId] = false;
			refreshRunningState();
			updateComposerActionState();
			renderComposerModelSelect();
		}
		showToast(errorText(error), true);
	}
}

async function dismissWorkflowPlan() {
	if (!state.active || state.running || phoneOwnsActiveSession()) return;
	try {
		state.active = await backend().DismissWorkflowPlan(state.active.id);
		renderActiveSession();
		showToast("已取消当前计划");
	} catch (error) {
		showToast(errorText(error), true);
	}
}

async function restorePendingAsk() {
  const sessionId = state.active?.id;
  if (!sessionId) {
    state.askUser = null;
    renderAskUser();
    return;
  }
  try {
    const pending = await backend().PendingAsk(sessionId);
    if (state.active?.id !== sessionId) return;
    state.askUser = pending || null;
    renderAskUser();
    if (state.askUser) $("#run-status").textContent = "等待回答";
  } catch (error) {
    showToast(`无法恢复待回答问题：${errorText(error)}`, true);
  }
}

function renderAskUser() {
  const card = $("#ask-user-card");
  if (!card) return;
  const request = state.askUser;
  const visible = Boolean(request && request.sessionId === state.active?.id && request.questions?.length);
  card.classList.toggle("hidden", !visible);
  if (!visible) {
    $("#ask-user-questions").replaceChildren();
    return;
  }
  $("#ask-user-count").textContent = `${request.questions.length} 个问题`;
  const container = $("#ask-user-questions");
  container.replaceChildren();
  request.questions.forEach((question, questionIndex) => {
    const section = document.createElement("fieldset");
    section.className = "ask-question";
    section.dataset.questionId = question.id;
    const legend = document.createElement("legend");
    const badge = document.createElement("span");
    badge.textContent = question.header || `问题 ${questionIndex + 1}`;
    const title = document.createElement("strong");
    title.textContent = question.question;
    legend.append(badge, title);
    section.append(legend);

    const choices = document.createElement("div");
    choices.className = "ask-options";
    (question.options || []).forEach((option) => {
      const label = document.createElement("label");
      label.className = "ask-option";
      const input = document.createElement("input");
      input.type = question.multiSelect ? "checkbox" : "radio";
      input.name = `ask-${request.id}-${question.id}`;
      input.value = option.label;
      const copy = document.createElement("span");
      const name = document.createElement("strong");
      name.textContent = option.label;
      copy.append(name);
      if (option.description) {
        const description = document.createElement("small");
        description.textContent = option.description;
        copy.append(description);
      }
      input.addEventListener("change", () => {
        if (input.checked) section.querySelector(".ask-custom-answer").value = "";
        updateAskUserSubmitState();
      });
      label.append(input, copy);
      choices.append(label);
    });
    section.append(choices);

    const custom = document.createElement("input");
    custom.type = "text";
    custom.className = "ask-custom-answer";
    custom.maxLength = 500;
    custom.placeholder = "或输入自定义答案";
    custom.addEventListener("input", () => {
      if (custom.value.trim()) section.querySelectorAll(".ask-option input").forEach((input) => { input.checked = false; });
      updateAskUserSubmitState();
    });
    section.append(custom);
    container.append(section);
  });
  updateAskUserSubmitState();
}

function collectAskUserAnswers() {
  if (!state.askUser) return [];
  return state.askUser.questions.map((question) => {
    const section = document.querySelector(`.ask-question[data-question-id="${cssEscape(question.id)}"]`);
    const custom = section?.querySelector(".ask-custom-answer")?.value.trim() || "";
    const selectedOptions = custom
      ? [custom]
      : Array.from(section?.querySelectorAll(".ask-option input:checked") || []).map((input) => input.value);
    return { questionId: question.id, selectedOptions };
  });
}

function updateAskUserSubmitState() {
  const button = $("#submit-ask-user");
  if (!button) return;
  const answers = collectAskUserAnswers();
  button.disabled = !state.askUser || answers.length !== state.askUser.questions.length || answers.some((answer) => !answer.selectedOptions.length);
}

async function submitAskUser() {
  if (!state.askUser) return;
  const decision = { id: state.askUser.id, answers: collectAskUserAnswers(), dismiss: false };
  if (decision.answers.some((answer) => !answer.selectedOptions.length)) return;
  $("#submit-ask-user").disabled = true;
  try {
    await backend().ResolveAsk(decision);
  } catch (error) {
    showToast(errorText(error), true);
    updateAskUserSubmitState();
  }
}

async function dismissAskUser() {
  if (!state.askUser) return;
  $("#dismiss-ask-user").disabled = true;
  try {
    await backend().ResolveAsk({ id: state.askUser.id, answers: [], dismiss: true });
  } catch (error) {
    showToast(errorText(error), true);
  } finally {
    $("#dismiss-ask-user").disabled = false;
  }
}

function phoneOwnsActiveSession() {
  return state.active?.executionHandoff?.owner === "android";
}

function refreshRunningState() {
  state.running = Boolean(state.runningSessions[state.active?.id]);
  return state.running;
}

function renderExecutionHandoff() {
  const locked = phoneOwnsActiveSession();
  const banner = $("#execution-handoff-banner");
  banner.classList.toggle("hidden", !locked);
  const startedAt = state.active?.executionHandoff?.startedAt || 0;
  $("#execution-handoff-detail").textContent = startedAt
    ? `电脑端已冻结，${formatTime(startedAt)} 起等待手机安全归还。断网或重启不会自动解冻。`
    : "电脑端已冻结，等待手机安全归还。断网或重启不会自动解冻。";
  const input = $("#message-input");
  input.disabled = locked;
  if (locked) input.placeholder = "任务正在手机上继续，归还后可在电脑输入";
  $("#composer-add").disabled = locked;
  updateComposerActionState();
  $(".composer").classList.toggle("execution-locked", locked);
  if (locked) {
    $("#run-status").textContent = "手机接管中";
  } else if (!state.running) {
    $("#run-status").textContent = "就绪";
  }
}

function renderHeaderMeta() {
  $("#chat-title").textContent = state.active?.title || "新会话";
  const projectPath = activeProjectPath();
  const projectMissing = Boolean(state.active?.projectPath && !state.sessionProjectAvailable);
  $("#project-chip").textContent = projectMissing ? `! ${projectName(projectPath)}` : projectPath ? `⌄ ${projectName(projectPath)}` : "＋ 选择项目";
  $("#project-chip").title = projectMissing ? `任务绑定的项目不可用：${projectPath}` : projectPath || "打开或新建项目";
  $("#project-chip").classList.toggle("error", projectMissing);
  $("#project-chip").disabled = state.running || phoneOwnsActiveSession();
  const profile = state.config?.providerProfiles?.find((item) => item.id === state.config.activeProviderProfileId);
  const plannerActive = Boolean(state.active?.planModeEnabled && state.config?.plannerProfileEnabled);
  const displayModel = plannerActive && state.config?.plannerModel
    ? state.config.plannerModel
    : providerProfileModelLabel(profile);
  $("#model-chip").textContent = profile
    ? `${profile.name} · ${plannerActive ? "计划 " : ""}${displayModel || "模型自动决定"}`
    : state.config?.model || "未配置模型";
  const approvalLabels = { readonly: "只读", ask: "全审批", allowlist: "白名单", yolo: "全自动" };
  $("#approval-chip").textContent = approvalLabels[state.config?.approvalMode] || "全审批";
  renderWorkspaceChangeChip();
  renderComposerModelSelect();
}

function renderWorkspaceChangeChip() {
  const chip = $("#workspace-changes-chip");
  if (!chip) return;
  const hasProject = Boolean(activeProjectPath()) && state.sessionProjectAvailable;
  chip.classList.toggle("hidden", !hasProject);
  if (!hasProject) return;
  const snapshot = state.workspaceChanges || {};
  const pending = (snapshot.pendingChanges?.length || 0) + (snapshot.omittedCount || 0);
  chip.textContent = pending ? `项目变化 ${formatCount(pending)}` : snapshot.error ? "监听异常" : "项目变化";
  chip.classList.toggle("attention", pending > 0);
  chip.classList.toggle("error", Boolean(snapshot.error));
  chip.title = snapshot.error || (pending ? `${pending} 个变化将在下一条消息中提供给 Agent` : "查看终端、Git 或其他编辑器造成的项目变化");
}

function renderComposerModelSelect() {
  const trigger = $("#composer-ai-config-trigger");
  if (!trigger || !state.config) return;
  const profiles = state.config.providerProfiles || [];
  const activeProfile = profiles.find((profile) => profile.id === state.config.activeProviderProfileId) || null;
  const reasoningOptions = reasoningOptionsForProfile(activeProfile, "默认");
  const reasoning = reasoningOptions.find((option) => option.value === (activeProfile?.reasoningEffort || "")) || reasoningOptions[0];
  $("#composer-ai-config-summary").textContent = activeProfile
    ? `${activeProfile.name || providerLabel(activeProfile.providerId)} · ${providerProfileModelLabel(activeProfile)}`
    : "未配置模型";
  $("#composer-ai-config-reasoning").textContent = `推理：${reasoning?.label || "默认"}`;
  trigger.disabled = state.running || phoneOwnsActiveSession() || profiles.length === 0;
  trigger.title = activeProfile
    ? `${activeProfile.name || providerLabel(activeProfile.providerId)} · ${providerProfileModelLabel(activeProfile)} · 推理 ${reasoning?.label || "默认"}`
    : "请先配置模型连接";
  renderComposerAIConfigMenu();
}

function toggleComposerAIConfigMenu(event) {
  const menu = $("#composer-ai-config-menu");
  if (!menu || $("#composer-ai-config-trigger").disabled) return;
  // 阻止点击事件继续冒泡到 document 的"外部点击关闭"处理器,以免展开后被误判为外部点击而立刻关闭。
  event?.stopPropagation?.();
  if (!menu.classList.contains("hidden")) {
    closeComposerAIConfigMenu();
    return;
  }
  state.composerAIConfigCategory = "";
  menu.classList.remove("hidden");
  $("#composer-ai-config-trigger").setAttribute("aria-expanded", "true");
  renderComposerAIConfigMenu();
}

function closeComposerAIConfigMenu() {
  const menu = $("#composer-ai-config-menu");
  if (!menu) return;
  menu.classList.add("hidden");
  $("#composer-ai-config-trigger")?.setAttribute("aria-expanded", "false");
  state.composerAIConfigCategory = "";
}

function closeComposerAIConfigFromOutside(event) {
  const container = $("#composer-ai-config");
  if (container && !container.contains(event.target)) closeComposerAIConfigMenu();
}

function showComposerAIConfigRoot() {
  state.composerAIConfigCategory = "";
  renderComposerAIConfigMenu();
}

function renderComposerAIConfigMenu() {
  const menu = $("#composer-ai-config-menu");
  const list = $("#composer-ai-config-list");
  if (!menu || !list || !state.config) return;
  const category = state.composerAIConfigCategory || "";
  const profile = activeProviderProfile();
  const back = $("#composer-ai-config-back");
  back.classList.toggle("hidden", !category);
  list.replaceChildren();
  if (!category) {
    $("#composer-ai-config-title").textContent = "模型与运行参数";
    $("#composer-ai-config-detail").textContent = "先选类别，再修改具体选项";
    const reasoningOptions = reasoningOptionsForProfile(profile, "默认");
    const reasoning = reasoningOptions.find((option) => option.value === (profile?.reasoningEffort || "")) || reasoningOptions[0];
    list.append(
      createComposerAIConfigRow({ category: "connection", title: "后端与连接", subtitle: composerConnectionLabel(profile) }),
      createComposerAIConfigRow({ category: "model", title: "模型", subtitle: providerProfileModelLabel(profile), enabled: Boolean(profile) }),
      createComposerAIConfigRow({
        category: "reasoning",
        title: "推理深度",
        subtitle: reasoningOptions.length > 1 ? reasoning?.label || "默认" : `${reasoning?.label || "默认"} · 暂无可选档位`,
        enabled: Boolean(profile) && reasoningOptions.length > 1,
      }),
    );
    return;
  }
  if (category === "connection") renderComposerConnectionChoices(list);
  else if (category === "model") renderComposerModelChoices(list, profile);
  else if (category === "reasoning") renderComposerReasoningChoices(list, profile);
}

function createComposerAIConfigRow({ category = "", action = "", title, subtitle = "", selected = false, enabled = true, data = {} }) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = `composer-ai-config-row${selected ? " selected" : ""}`;
  button.disabled = !enabled;
  if (category) button.dataset.aiConfigCategory = category;
  if (action) button.dataset.aiConfigAction = action;
  Object.entries(data).forEach(([key, value]) => { button.dataset[key] = String(value || ""); });
  const copy = document.createElement("span");
  copy.className = "composer-ai-config-row-copy";
  const titleElement = document.createElement("strong");
  titleElement.textContent = `${selected ? "✓ " : ""}${title}`;
  copy.append(titleElement);
  if (subtitle) {
    const subtitleElement = document.createElement("small");
    subtitleElement.textContent = subtitle;
    copy.append(subtitleElement);
  }
  const suffix = document.createElement("span");
  suffix.className = "composer-ai-config-row-suffix";
  suffix.textContent = category ? "›" : "";
  suffix.setAttribute("aria-hidden", "true");
  button.append(copy, suffix);
  return button;
}

function composerConnectionLabel(profile) {
  if (!profile) return "未选择";
  if (profile.providerId !== "codex-chatgpt") return profile.name || providerLabel(profile.providerId);
  const activeAccount = (state.codex?.accountPool?.accounts || []).find((account) => account.active);
  const identity = activeAccount?.email || activeAccount?.label || "官方 ChatGPT / Codex";
  return `ChatGPT · ${identity}`;
}

function renderComposerConnectionChoices(list) {
  $("#composer-ai-config-title").textContent = "后端与连接";
  $("#composer-ai-config-detail").textContent = "选择 ChatGPT 账号或已配置 API 连接";
  const activeProfileId = state.config?.activeProviderProfileId || "";
  (state.config?.providerProfiles || []).forEach((profile) => {
    if (profile.providerId === "codex-chatgpt") {
      const accounts = (state.codex?.accountPool?.accounts || []).filter((account) => account.loggedIn);
      if (accounts.length) {
        accounts.forEach((account) => {
          const selected = profile.id === activeProfileId && account.active;
          list.append(createComposerAIConfigRow({
            action: "connection",
            title: account.email || account.label || "Codex 账号",
            subtitle: codexAccountConnectionSubtitle(profile, account),
            selected,
            enabled: account.enabled !== false,
            data: { providerProfileId: profile.id, codexAccountId: account.id },
          }));
        });
        return;
      }
    }
    list.append(createComposerAIConfigRow({
      action: "connection",
      title: profile.name || providerLabel(profile.providerId),
      subtitle: `${providerLabel(profile.providerId)} · ${providerProfileModelLabel(profile)}`,
      selected: profile.id === activeProfileId,
      data: { providerProfileId: profile.id },
    }));
  });
}

function codexAccountConnectionSubtitle(profile, account) {
  const parts = [profile.name || "ChatGPT / Codex"];
  if (account.planType) parts.push(account.planType);
  const windows = (account.rateLimits || []).flatMap((limit) => [limit.primary, limit.secondary]).filter(Boolean);
  if (windows.length) {
    const used = Math.max(...windows.map((window) => Math.max(0, Math.min(100, Number(window.usedPercent) || 0))));
    parts.push(`剩余 ${Math.max(0, 100 - used).toFixed(used % 1 ? 1 : 0)}%`);
  } else if (account.lowQuota) parts.push("额度偏低");
  return parts.join(" · ");
}

function renderComposerModelChoices(list, profile) {
  $("#composer-ai-config-title").textContent = "模型";
  $("#composer-ai-config-detail").textContent = profile?.name || "当前连接";
  if (!profile) return;
  if (profile.providerId === "murong-local") {
    const installed = (state.builtinVision?.models || []).filter((model) => model.installed && model.available !== false);
    installed.forEach((model) => {
      const option = createComposerAIConfigRow({
        action: "model",
        title: model.displayName,
        subtitle: model.engine === "litert" ? "LiteRT-LM" : "MNN",
        selected: Boolean(model.active),
        data: { providerProfileId: profile.id, visionTier: model.tier },
      });
      option.dataset.visionTier = model.tier;
      list.append(option);
    });
    if (!installed.length) list.append(createComposerAIConfigRow({ title: "尚未安装本地模型", enabled: false }));
    return;
  }
  const models = providerModelsForProfile(profile);
  models.forEach((model) => {
    const info = codexModelInfo(model);
    list.append(createComposerAIConfigRow({
      action: "model",
      title: info?.displayName || model,
      subtitle: info?.displayName && info.displayName !== model ? model : "",
      selected: model === profile.model,
      data: { providerProfileId: profile.id, modelId: model },
    }));
  });
  if (!models.length) list.append(createComposerAIConfigRow({ title: "当前连接没有可选模型", enabled: false }));
}

function renderComposerReasoningChoices(list, profile) {
  $("#composer-ai-config-title").textContent = "推理深度";
  $("#composer-ai-config-detail").textContent = profile?.providerId === "codex-chatgpt"
    ? "仅显示当前官方模型支持的档位"
    : profile?.name || "当前连接";
  reasoningOptionsForProfile(profile, "默认").forEach((option) => list.append(createComposerAIConfigRow({
    action: "reasoning",
    title: option.label,
    selected: option.value === (profile?.reasoningEffort || ""),
    data: { providerProfileId: profile?.id || "", reasoningEffort: option.value },
  })));
}

async function handleComposerAIConfigAction(event) {
  const categoryButton = event.target.closest("[data-ai-config-category]");
  if (categoryButton) {
    // 类别导航会重建列表、把被点击节点从 DOM 移除;若不阻止冒泡,doc 级
    // "外部点击关闭" 会因 contains(target) 返回 false 而误关菜单。
    event.stopPropagation();
    state.composerAIConfigCategory = categoryButton.dataset.aiConfigCategory || "";
    renderComposerAIConfigMenu();
    return;
  }
  const actionButton = event.target.closest("[data-ai-config-action]");
  const action = actionButton?.dataset.aiConfigAction || "";
  const selectedLabel = actionButton?.querySelector("strong")?.textContent?.replace(/^✓\s*/, "") || "所选项";
  if (!actionButton || actionButton.disabled || !action || state.running || phoneOwnsActiveSession()) return;
  // 具体选项(connection/model/reasoning)命中后同样阻止冒泡到 doc 级外部关闭处理器。
  event.stopPropagation();
  const profileId = actionButton.dataset.providerProfileId || "";
  try {
    if (action === "connection") {
      if (profileId && profileId !== state.config.activeProviderProfileId) {
        state.config = await backend().ActivateProviderProfile(profileId);
      }
      const accountId = actionButton.dataset.codexAccountId || "";
      const activeAccountId = state.codex?.accountPool?.activeAccountId || "";
      if (accountId && accountId !== activeAccountId) {
        const profile = (state.config.providerProfiles || []).find((item) => item.id === profileId);
        state.codex = await backend().ActivateCodexAccount({ executablePath: profile?.executablePath || "", accountId });
      }
      showToast(`已切换连接：${selectedLabel}`);
    } else if (action === "model") {
      const visionTier = actionButton.dataset.visionTier || "";
      const modelId = actionButton.dataset.modelId || "";
      if (visionTier) {
        await backend().SelectBuiltinVisionModel(visionTier);
        await refreshBuiltinVisionStatus();
      } else if (profileId && modelId) {
        state.config = await backend().SetProviderModel({ providerProfileId: profileId, model: modelId });
      }
      showToast(`已切换模型：${selectedLabel}`);
    } else if (action === "reasoning") {
      state.config = await backend().SetProviderReasoningEffort({
        providerProfileId: profileId,
        reasoningEffort: actionButton.dataset.reasoningEffort || "",
      });
      showToast(`已切换推理深度：${selectedLabel}`);
    }
    closeComposerAIConfigMenu();
    renderSettings();
    renderCodexProviderStatus();
    renderHeaderMeta();
  } catch (error) {
    renderComposerModelSelect();
    showToast(errorText(error), true);
  }
}

function appendMessageTimeline(messages) {
  let pendingTools = [];
  const flushTools = () => {
    if (!pendingTools.length) return;
    const article = document.createElement("article");
    article.className = "tool-execution-group-row";
    article.append(createToolExecutionGroup(pendingTools));
    $("#messages").append(article);
    pendingTools = [];
  };
  messages.forEach((message) => {
    if (isProcessTimelineMessage(message)) {
      pendingTools.push(message);
      return;
    }
    flushTools();
    appendMessageElement(message);
  });
  flushTools();
}

function isProcessTimelineMessage(message) {
  return message.role === "tool";
}

function createToolExecutionGroup(messages) {
  const details = document.createElement("details");
  const stats = summarizeToolExecutions(messages);
  details.className = `tool-execution-group${stats.failed ? " failed" : ""}`;
  const summary = document.createElement("summary");
  const heading = document.createElement("span");
  heading.className = "tool-execution-group-title";
  heading.textContent = stats.labels[0];
  const labels = document.createElement("span");
  labels.className = "tool-execution-group-summary";
  labels.textContent = [...stats.labels.slice(1), ...(stats.failed ? ["存在失败"] : [])].join(" · ");
  const action = document.createElement("span");
  action.className = "tool-execution-group-action";
  action.textContent = "展开";
  summary.append(heading, labels, action);
  details.addEventListener("toggle", () => {
    action.textContent = details.open ? "收起" : "展开";
  });
  const content = document.createElement("div");
  content.className = "tool-execution-group-content";
  messages.forEach((message) => content.append(createToolExecutionDetails(message)));
  details.append(summary, content);
  return details;
}

function summarizeToolExecutions(messages) {
  const toolMessages = messages.filter((message) => message.role === "tool");
  const changedFiles = new Set();
  const readFiles = new Set();
  let changedWithoutPath = 0;
  let readWithoutPath = 0;
  let commandCount = 0;
  let searchCount = 0;
  let directoryCount = 0;
  let failed = false;
  toolMessages.forEach((message) => {
    const args = parseToolJSON(message.toolArguments);
    const payload = parseToolJSON(message.content);
    const kind = toolExecutionKind(message.toolName, args);
    const paths = collectToolPaths(args, payload);
    if (kind === "command") commandCount += 1;
    if (kind === "search") searchCount += 1;
    if (kind === "directory") directoryCount += 1;
    if (kind === "edit") {
      if (paths.length) paths.forEach((path) => changedFiles.add(path));
      else changedWithoutPath += 1;
    }
    if (kind === "read") {
      if (paths.length) paths.forEach((path) => readFiles.add(path));
      else readWithoutPath += 1;
    }
    if (message.toolStatus === "failed") failed = true;
  });
  const labels = [];
  const changedCount = changedFiles.size + changedWithoutPath;
  const readCount = readFiles.size + readWithoutPath;
  if (commandCount) labels.push(`已运行 ${commandCount} 条命令`);
  if (changedCount) labels.push(`已编辑 ${changedCount} 个文件`);
  if (readCount) labels.push(`已读取 ${readCount} 个文件`);
  if (searchCount) labels.push(`已搜索 ${searchCount} 次`);
  if (directoryCount) labels.push(`已查看 ${directoryCount} 个目录`);
  if (!labels.length && toolMessages.length) labels.push(`已执行 ${toolMessages.length} 个工具`);
  if (!labels.length) labels.push("执行过程");
  return { labels, failed };
}

function toolExecutionKind(name, args) {
  const normalized = String(name || "").toLowerCase();
  if (normalized === "code_edit" && String(args?.operation || "").toLowerCase() === "view") return "read";
  if (["run_terminal", "computer_terminal", "shell", "command", "commandexecution"].includes(normalized)) return "command";
  if (["write_file", "code_edit", "computer_workspace", "apply_patch", "delete_path", "chmod_path"].includes(normalized)) return "edit";
  if (["read_file", "github_read_file", "image_view"].includes(normalized)) return "read";
  if (["code_search", "web_search", "web_fetch", "github_repository"].includes(normalized)) return "search";
  if (["list_files", "github_list_branches", "github_list_issues"].includes(normalized)) return "directory";
  return "other";
}

function collectToolPaths(...values) {
  const paths = new Set();
  const visit = (value) => {
    if (!value || typeof value !== "object") return;
    if (Array.isArray(value)) {
      value.forEach(visit);
      return;
    }
    Object.entries(value).forEach(([key, item]) => {
      const normalized = key.replace(/[_-]/g, "").toLowerCase();
      if (["path", "filepath", "uri"].includes(normalized) && typeof item === "string" && item.trim()) {
        paths.add(item.trim());
      } else if (typeof item === "object") {
        visit(item);
      }
    });
  };
  values.forEach(visit);
  return [...paths];
}

function appendMessageElement(message) {
  const container = $("#messages");
  const article = document.createElement("article");
  if (message.kind === "workspace_review" && message.workspaceReview) {
    article.className = "workspace-review-row";
    if (message.id) article.dataset.messageId = message.id;
    article.append(createWorkspaceReviewCard(message.workspaceReview));
    container.append(article);
    return;
  }
  if (message.role === "tool") {
    article.className = "tool-execution-row";
    if (message.id) article.dataset.messageId = message.id;
    article.append(createToolExecutionDetails(message));
    container.append(article);
    return;
  }
  const kindClass = message.kind === "error" ? " error" : message.kind === "plan" ? " plan" : message.kind === "subagent_background" ? " subagent-background" : message.kind === "image_generation" ? " image-generation" : message.role === "tool" ? " tool" : "";
  article.className = `message ${message.role}${kindClass}`;
  if (message.id) article.dataset.messageId = message.id;
  const avatar = document.createElement("div");
  avatar.className = "message-avatar";
  avatar.textContent = message.role === "user" ? "你" : "M";
  const body = document.createElement("div");
  body.className = "message-body";
  {
    body.innerHTML = renderMarkdown(message.content || "");
    if (message.imageGeneration) appendImageGenerationCard(body, message);
    else if (message.imageAttachments?.length) appendMessageImages(body, message);
    let contextTags = null;
    if (message.role === "user" && (message.context?.length || message.mode)) {
      const tags = document.createElement("div");
      tags.className = "message-context-tags";
      contextTags = tags;
      const modeLabels = { plan: "计划模式", goal: "设置目标", goal_plan: "目标 + 计划" };
      if (modeLabels[message.mode]) {
        const modeTag = document.createElement("span");
        modeTag.textContent = modeLabels[message.mode];
        tags.append(modeTag);
      }
      (message.context || []).forEach((item) => {
        const tag = document.createElement("span");
        tag.textContent = contextDisplayLabel(item);
        tags.append(tag);
      });
      body.prepend(tags);
    }
    if (message.role === "user" && (message.workspaceChanges?.length || message.workspaceChangesOmitted)) {
      const note = createWorkspaceMessageNote(message.workspaceChanges || [], message.workspaceChangesOmitted || 0);
      if (contextTags) contextTags.after(note);
      else body.prepend(note);
    }
    if (message.role === "assistant" && message.kind === "plan" &&
        state.active?.workflowPlan?.sourceMessageId === message.id &&
        state.active.workflowPlan.status !== "completed") {
      const actions = document.createElement("div");
      actions.className = "plan-message-actions";
      const execute = document.createElement("button");
      execute.className = "plan-execute-button";
      execute.type = "button";
      execute.textContent = state.active.workflowPlan.status === "blocked" ? "继续执行" : "按计划执行";
      execute.disabled = state.running || state.active.workflowPlan.status === "executing" || phoneOwnsActiveSession();
      execute.addEventListener("click", executeWorkflowPlan);
      const modify = document.createElement("button");
      modify.className = "plan-modify-button";
      modify.type = "button";
      modify.textContent = "修改计划";
      modify.disabled = state.running || phoneOwnsActiveSession();
      const cancel = document.createElement("button");
      cancel.className = "plan-cancel-button";
      cancel.type = "button";
      cancel.textContent = "取消计划";
      cancel.disabled = state.running || phoneOwnsActiveSession();
      cancel.addEventListener("click", dismissWorkflowPlan);
      actions.append(execute, modify, cancel);

      const editor = document.createElement("form");
      editor.className = "plan-revision-editor hidden";
      const feedback = document.createElement("textarea");
      feedback.rows = 2;
      feedback.maxLength = 6000;
      feedback.placeholder = "告诉模型要调整哪些步骤或目标…";
      const submit = document.createElement("button");
      submit.type = "submit";
      submit.className = "primary";
      submit.textContent = "重新生成计划";
      editor.append(feedback, submit);
      modify.addEventListener("click", () => {
        editor.classList.toggle("hidden");
        if (!editor.classList.contains("hidden")) feedback.focus();
      });
      editor.addEventListener("submit", async (event) => {
        event.preventDefault();
        const note = feedback.value.trim();
        if (!note || state.running || phoneOwnsActiveSession()) return;
        try {
          submit.disabled = true;
          state.active = await backend().SetSessionPlanMode(state.active.id, true);
          setComposerInputValue(`请根据以下意见修改当前计划：\n${note}`);
          await sendMessage();
        } catch (error) {
          showToast(errorText(error), true);
          submit.disabled = false;
        }
      });
      body.append(actions, editor);
    }
  }
  if (message.id) {
    const historyActions = document.createElement("div");
    historyActions.className = "message-history-actions";
    const forkButton = document.createElement("button");
    forkButton.type = "button";
    forkButton.textContent = "从此处分叉";
    forkButton.disabled = state.running;
    forkButton.addEventListener("click", () => forkSessionFrom(message.id));
    const rollbackButton = document.createElement("button");
    rollbackButton.type = "button";
    rollbackButton.textContent = "回退到此处";
    rollbackButton.disabled = state.running || phoneOwnsActiveSession();
    rollbackButton.addEventListener("click", () => requestSessionRollback(message.id));
    const speakButton = document.createElement("button");
    speakButton.type = "button";
    speakButton.textContent = "朗读";
    speakButton.className = "speak-button";
    speakButton.disabled = !state.voice?.ttsModelInstalled;
    speakButton.addEventListener("click", () => toggleSpeakMessage(message, body, speakButton));
    historyActions.append(speakButton, forkButton, rollbackButton);
    body.append(historyActions);
  }
  if (message.role === "assistant") {
    const stack = document.createElement("div");
    stack.className = "message-content-stack";
    if (message.reasoning) stack.append(createReasoningDetails(message.reasoning, false));
    stack.append(body);
    article.append(avatar, stack);
  } else {
    article.append(avatar, body);
  }
  container.append(article);
}

function createWorkspaceReviewCard(review) {
  const card = document.createElement("section");
  card.className = `workspace-review-card${review.undone ? " undone" : ""}`;
  const header = document.createElement("header");
  const summary = document.createElement("div");
  summary.className = "workspace-review-summary";
  const title = document.createElement("strong");
  title.textContent = `已编辑 ${formatCount(review.files?.length || 0)} 个文件`;
  const totals = document.createElement("span");
  totals.className = "workspace-review-totals";
  totals.innerHTML = `<b>+${formatCount(review.additions || 0)}</b><i>-${formatCount(review.deletions || 0)}</i>`;
  if (review.binaryFiles) {
    const binary = document.createElement("small");
    binary.textContent = `${formatCount(review.binaryFiles)} 个二进制文件`;
    totals.append(binary);
  }
  summary.append(title, totals);
  const actions = document.createElement("div");
  actions.className = "workspace-review-actions";
  const undo = document.createElement("button");
  undo.type = "button";
  undo.className = "workspace-review-undo";
  undo.title = review.undone ? "本轮变更已经撤销" : review.undoAvailable ? "只撤销本轮模型产生的文件修改" : (review.statusMessage || "没有可用的安全撤销补丁");
  undo.disabled = !review.undoAvailable || review.undone || state.running || phoneOwnsActiveSession();
  undo.innerHTML = `<span aria-hidden="true">↶</span> 撤销`;
  undo.addEventListener("click", () => undoWorkspaceReview(review));
  const inspect = document.createElement("button");
  inspect.type = "button";
  inspect.className = "workspace-review-inspect";
  inspect.textContent = "审核";
  inspect.disabled = !review.files?.length;
  inspect.addEventListener("click", () => openWorkspaceReviewFile(review, review.files?.[0]?.path || ""));
  actions.append(undo, inspect);
  header.append(summary, actions);

  const list = document.createElement("div");
  list.className = "workspace-review-files";
  let expanded = false;
  const renderFiles = () => {
    list.replaceChildren();
    const files = expanded ? (review.files || []) : (review.files || []).slice(0, 3);
    files.forEach((file) => list.append(createWorkspaceReviewFileRow(review, file)));
    if ((review.files?.length || 0) > 3) {
      const expand = document.createElement("button");
      expand.type = "button";
      expand.className = "workspace-review-expand";
      expand.innerHTML = expanded
        ? `<span aria-hidden="true">⌃</span> 收起文件列表`
        : `<span aria-hidden="true">⌄</span> 完全展开 ${formatCount(review.files.length)} 个文件`;
      expand.addEventListener("click", () => {
        expanded = !expanded;
        renderFiles();
      });
      list.append(expand);
    }
  };
  renderFiles();
  card.append(header, list);
  if (review.statusMessage) {
    const status = document.createElement("p");
    status.className = "workspace-review-status";
    status.textContent = review.statusMessage;
    card.append(status);
  }
  return card;
}

function createWorkspaceReviewFileRow(review, file) {
  const row = document.createElement("button");
  row.type = "button";
  row.className = "workspace-review-file";
  row.title = `审核 ${file.path}`;
  const path = document.createElement("span");
  path.textContent = file.path;
  const stats = document.createElement("span");
  stats.className = "workspace-review-file-stats";
  if (file.binary) {
    stats.textContent = "二进制";
  } else {
    stats.innerHTML = `<b>+${formatCount(file.additions || 0)}</b><i>-${formatCount(file.deletions || 0)}</i>`;
  }
  row.append(path, stats);
  row.addEventListener("click", () => openWorkspaceReviewFile(review, file.path));
  return row;
}

async function undoWorkspaceReview(review) {
  if (!review?.undoAvailable || review.undone) return;
  if (!window.confirm(`撤销本轮对 ${formatCount(review.files?.length || 0)} 个文件的修改？\n\n只会反向应用这一轮的补丁；如果文件后来又被修改，操作会安全停止。`)) return;
  try {
    const updated = await backend().UndoWorkspaceReview(review.id);
    if (updated?.id === state.active?.id) {
      state.active = updated;
      renderActiveSession();
    }
    showToast("本轮文件变更已撤销");
  } catch (error) {
    showToast(errorText(error), true);
  }
}

function createToolExecutionDetails(message) {
  const details = document.createElement("details");
  details.className = `tool-execution ${message.toolStatus || ""}`;
  const args = parseToolJSON(message.toolArguments);
  const payload = parseToolJSON(message.content);
  const result = payload && typeof payload.result === "object" ? payload.result : payload;
  const command = firstToolValue(args?.command, args?.cmd, result?.command, result?.cmd);
  const presentation = describeToolExecution(message, args, payload, result, command);
  const summary = document.createElement("summary");
  const title = document.createElement("span");
  title.className = "tool-execution-title";
  title.textContent = presentation.title;
  const preview = document.createElement("code");
  preview.textContent = presentation.preview;
  const status = document.createElement("span");
  status.className = "tool-execution-status";
  status.textContent = message.toolStatus === "failed" ? "失败" : "完成";
  summary.append(title, preview, status);
  const content = document.createElement("div");
  content.className = "tool-execution-content";
  presentation.fields.forEach(([label, value]) => appendToolField(content, label, value));
  if (!content.childElementCount) appendToolField(content, "结果", message.toolStatus === "failed" ? "执行失败" : "执行完成");
  content.append(createToolProtocolDetails(message, args, payload));
  details.append(summary, content);
  return details;
}

function describeToolExecution(message, args, payload, result, command) {
  const name = String(message.toolName || "");
  const normalized = name.toLowerCase();
  const paths = collectToolPaths(args, payload);
  const path = firstToolValue(args?.path, args?.file_path, result?.path, result?.file?.path, paths[0]);
  const failure = firstToolValue(payload?.error, result?.stderr);
  const fields = [];
  const finish = (title, preview) => ({
    title,
    preview: truncateText(preview || (message.toolStatus === "failed" ? "执行失败" : "执行完成"), 110),
    fields,
  });
  if (["run_terminal", "computer_terminal", "shell", "command"].includes(normalized)) {
    fields.push(["命令", command], ["工作目录", firstToolValue(args?.workingDirectory, args?.cwd, args?.path, result?.workingDirectory, result?.cwd)]);
    fields.push(["终端", firstToolValue(result?.terminal, args?.terminal)]);
    if (result?.exitCode !== undefined && result?.exitCode !== null) fields.push(["退出码", String(result.exitCode)]);
    fields.push(["输出", firstToolValue(result?.stdout, typeof result === "string" ? result : "")]);
    fields.push(["错误输出", failure]);
    return finish("运行命令", command);
  }
  if (normalized === "list_files") {
    const entries = Array.isArray(payload?.entries) ? payload.entries : [];
    fields.push(["目录", path || "."], ["内容", formatDirectoryEntries(entries)], ["错误", failure]);
    return finish("查看目录", `${path || "."} · ${entries.length} 项`);
  }
  if (normalized === "code_search") {
    const matches = Array.isArray(payload?.matches) ? payload.matches : [];
    const pattern = firstToolValue(args?.pattern, args?.query, "");
    fields.push(["搜索内容", pattern], ["搜索范围", args?.path || "."], ["匹配结果", formatCodeSearchMatches(matches)], ["错误", failure]);
    return finish("搜索代码", `${pattern || "代码搜索"} · ${payload?.count ?? matches.length} 处结果`);
  }
  if (normalized === "read_file" || (normalized === "code_edit" && args?.operation === "view")) {
    const file = payload?.file || result?.file || {};
    fields.push(["文件", path], ["范围", args?.startLine ? `${args.startLine}-${args?.endLine || "末尾"} 行` : ""], ["文件内容", firstToolValue(file?.content, payload?.content)], ["错误", failure]);
    return finish("读取文件", path);
  }
  if (["write_file", "code_edit", "computer_workspace", "apply_patch", "delete_path", "chmod_path"].includes(normalized)) {
    const operationLabels = {
      create: "创建文件",
      search_replace: "替换代码",
      apply_patch: "应用补丁",
      view: "读取文件",
    };
    fields.push(["文件", paths.length ? paths.join("\n") : path]);
    if (args?.operation) fields.push(["操作", operationLabels[args.operation] || args.operation]);
    if (payload?.hunkCount) fields.push(["修改块", `${payload.hunkCount} 处`]);
    fields.push(["结果", message.toolStatus === "failed" ? failure || "修改失败" : paths.length > 1 ? `已更新 ${paths.length} 个文件` : "修改完成"]);
    return finish(toolDisplayName(name), paths.length > 1 ? `${paths.length} 个文件` : path || "项目文件");
  }
  if (normalized === "file_exists") {
    fields.push(["路径", path], ["结果", payload?.exists ? "存在" : "不存在"]);
    return finish("检查路径", `${path || "目标路径"} · ${payload?.exists ? "存在" : "不存在"}`);
  }
  if (normalized === "workspace_diff") {
    fields.push(["范围", path || "整个项目"], ["文件差异", payload?.diff], ["错误", failure]);
    return finish("查看文件变更", path || "整个项目");
  }
  if (normalized === "web_search") {
    const query = firstToolValue(args?.query, args?.q, args?.search_query);
    fields.push(["搜索内容", query], ["结果", toolResultPreview(result, message.content)]);
    return finish("联网搜索", query);
  }
  if (normalized === "web_fetch") {
    const url = firstToolValue(args?.url, result?.url);
    fields.push(["网页", url], ["结果", firstToolValue(result?.content, result?.excerpt, typeof result === "string" ? result : "")]);
    return finish("读取网页", url);
  }
  if (normalized.startsWith("mcp:")) {
    const [, server = "", tool = ""] = name.split(":");
    fields.push(["服务", server], ["工具", tool], ["结果", typeof result === "string" ? result : toolResultPreview(result, message.content)]);
    return finish(`MCP · ${server || "外部服务"}`, tool || "调用工具");
  }
  fields.push(["目标", firstToolValue(path, args?.query, args?.url)]);
  fields.push(["输出", typeof result === "string" ? result : firstToolValue(result?.message, result?.content)]);
  fields.push(["错误", failure]);
  return finish(toolDisplayName(name), firstToolValue(path, command, result?.message, "执行完成"));
}

function toolDisplayName(name) {
  const normalized = String(name || "").toLowerCase();
  const labels = {
    list_files: "查看目录",
    read_file: "读取文件",
    write_file: "写入文件",
    create_directory: "创建目录",
    file_exists: "检查路径",
    delete_path: "删除路径",
    chmod_path: "修改权限",
    run_terminal: "运行命令",
    computer_terminal: "运行命令",
    code_search: "搜索代码",
    code_edit: "编辑代码",
    computer_workspace: "修改文件",
    workspace_diff: "查看文件变更",
    web_search: "联网搜索",
    web_fetch: "读取网页",
    image_view: "查看图片",
    subagent: "运行子代理",
    ask_user: "向用户提问",
  };
  return labels[normalized] || name || "工具执行";
}

function formatDirectoryEntries(entries) {
  if (!entries?.length) return "目录为空";
  return entries.map((entry) => `${entry.directory ? "目录" : "文件"} · ${entry.path}${entry.directory || !entry.size ? "" : ` · ${entry.size} 字节`}`).join("\n");
}

function formatCodeSearchMatches(matches) {
  if (!matches?.length) return "未找到匹配内容";
  return matches.map((match) => `${match.path}:${match.line}  ${String(match.match || "").trim()}`).join("\n");
}

function createToolProtocolDetails(message, args, payload) {
  const details = document.createElement("details");
  details.className = "tool-protocol-details";
  const summary = document.createElement("summary");
  summary.textContent = "技术详情";
  const pre = document.createElement("pre");
  pre.textContent = prettyToolValue({
    tool: message.toolName || "",
    arguments: args ?? message.toolArguments ?? null,
    result: payload ?? message.content ?? null,
  });
  details.append(summary, pre);
  return details;
}

function parseToolJSON(value) {
  if (!value || typeof value !== "string") return value || null;
  try { return JSON.parse(value); } catch (_) { return null; }
}

function firstToolValue(...values) {
  return values.find((value) => value !== undefined && value !== null && String(value).trim() !== "");
}

function toolResultPreview(result, raw) {
  const value = firstToolValue(result?.path, result?.file, result?.url, result?.message, raw, "查看执行详情");
  return truncateText(typeof value === "string" ? value : prettyToolValue(value), 110);
}

function prettyToolValue(value) {
  if (typeof value === "string") return value;
  try { return JSON.stringify(value, null, 2); } catch (_) { return String(value ?? ""); }
}

function appendToolField(container, label, value) {
  if (value === undefined || value === null || String(value).trim() === "") return;
  const section = document.createElement("section");
  const heading = document.createElement("strong");
  heading.textContent = label;
  const pre = document.createElement("pre");
  pre.textContent = prettyToolValue(value);
  section.append(heading, pre);
  container.append(section);
}

function createReasoningDetails(reasoning, streaming) {
  const details = document.createElement("details");
  details.className = `message-reasoning${streaming ? " active" : ""}`;
  details.open = Boolean(streaming);
  const summary = document.createElement("summary");
  summary.textContent = streaming ? "正在思考…" : "思考过程";
  const body = document.createElement("div");
  body.className = "message-reasoning-body";
  body.dataset.raw = reasoning || "";
  if (streaming) body.textContent = reasoning || "模型正在生成思考过程…";
  else body.innerHTML = renderMarkdown(reasoning || "");
  details.append(summary, body);
  return details;
}

function renderSubagentJobs() {
  const container = $("#subagent-jobs");
  if (!container) return;
  const jobs = state.active?.backgroundSubagentJobs || [];
  container.replaceChildren();
  container.classList.toggle("hidden", jobs.length === 0);
  if (!jobs.length) return;
  const activeStatuses = new Set(["queued", "running", "cancelling"]);
  const activeCount = jobs.filter((job) => activeStatuses.has(job.status)).length;
  const header = document.createElement("div");
  header.className = "subagent-jobs-header";
  const title = document.createElement("strong");
  title.textContent = "后台子代理";
  const summary = document.createElement("span");
  summary.textContent = activeCount ? `${activeCount} 项运行中 · 满足依赖即并行` : `最近 ${Math.min(jobs.length, 8)} 项`;
  header.append(title, summary);
  container.append(header);
  [...jobs].reverse().slice(0, 8).forEach((job) => {
    const details = document.createElement("details");
    details.className = "subagent-job";
    details.open = activeStatuses.has(job.status);
    const row = document.createElement("summary");
    const copy = document.createElement("span");
    copy.className = "subagent-job-copy";
    const label = document.createElement("strong");
    label.textContent = job.label || "后台子代理";
    const description = document.createElement("small");
    const counts = job.taskCount > 1 ? `${job.completed || 0}/${job.taskCount} 完成 · ` : "";
    description.textContent = `${counts}${job.statusMessage || job.parentGoal || ""}`;
    copy.append(label, description);
    const status = document.createElement("span");
    status.className = `subagent-job-status ${job.status || ""}`;
    status.textContent = subagentJobStatusLabel(job.status);
    row.append(copy, status);
    if (activeStatuses.has(job.status)) {
      const cancel = document.createElement("button");
      cancel.type = "button";
      cancel.className = "subagent-job-cancel";
      cancel.textContent = job.status === "cancelling" ? "取消中" : "取消";
      cancel.disabled = job.status === "cancelling";
      cancel.addEventListener("click", async (event) => {
        event.preventDefault();
        event.stopPropagation();
        try {
          state.active = await backend().CancelSubagentJob({ sessionId: state.active.id, jobId: job.id });
          renderActiveSession();
        } catch (error) {
          showToast(errorText(error), true);
        }
      });
      row.append(cancel);
    }
    const detail = document.createElement("div");
    detail.className = "subagent-job-detail";
    const pre = document.createElement("pre");
    const resultText = (job.results || []).map((result) => {
      const heading = `${result.index}. [${result.label || "任务"}] ${result.status}`;
      return [heading, result.error || result.output || "尚无结果"].join("\n");
    }).join("\n\n");
    pre.textContent = resultText || job.parentGoal || job.statusMessage || "等待状态更新";
    detail.append(pre);
    details.append(row, detail);
    container.append(details);
  });
}

function subagentJobStatusLabel(status) {
  return ({ queued: "启动中", running: "运行中", cancelling: "取消中", completed: "已完成", failed: "有失败", cancelled: "已取消", interrupted: "已中断" })[status] || status || "未知";
}

function appendMessageImages(body, message) {
  const gallery = document.createElement("div");
  gallery.className = "message-image-gallery";
  (message.imageAttachments || []).forEach((attachment) => {
    const figure = document.createElement("figure");
    figure.className = "message-image-card loading";
    const image = document.createElement("img");
    image.alt = attachment.fileName || "图片附件";
    const caption = document.createElement("figcaption");
    caption.textContent = attachment.fileName || "图片附件";
    figure.append(image, caption);
    gallery.append(figure);
    backend().GetChatImageDataURL({ sessionId: state.active?.id || "", messageId: message.id, imageId: attachment.id })
      .then((url) => {
        image.src = url;
        figure.classList.remove("loading");
      })
      .catch(() => {
        figure.classList.remove("loading");
        figure.classList.add("unavailable");
        caption.textContent = `${attachment.fileName || "图片附件"} · 缓存不可用`;
      });
  });
  body.prepend(gallery);
}

function appendImageGenerationCard(body, message) {
  const generation = message.imageGeneration || {};
  const isUpscale = generation.operation === "upscale_4k";
  const card = document.createElement("section");
  card.className = `image-generation-card ${generation.status || "generating"}`;
  const header = document.createElement("header");
  const status = document.createElement("span");
  status.className = "image-generation-status";
  status.textContent = isUpscale
    ? ({ generating: "正在进行真实 4K 超分", completed: "真实 4K 超分完成", failed: "4K 超分失败", cancelled: "已取消 4K 超分" })[generation.status] || "真实 4K 超分"
    : ({ generating: "正在生成", completed: "已生成", failed: "生成失败", cancelled: "已取消" })[generation.status] || "图片生成";
  const detail = document.createElement("small");
  detail.textContent = generation.error || generation.stage || generation.model || "准备中";
  header.append(status, detail);
  const prompt = document.createElement("p");
  prompt.className = "image-generation-prompt";
  prompt.textContent = generation.prompt || "图片描述";
  card.append(header, prompt);
  if (message.imageAttachments?.length) {
    const gallery = document.createElement("div");
    gallery.className = "message-image-gallery generated-image-gallery";
    message.imageAttachments.forEach((attachment) => {
      const figure = document.createElement("figure");
      figure.className = "message-image-card loading";
      const image = document.createElement("img");
      image.alt = generation.prompt || attachment.fileName || "生成的图片";
      const caption = document.createElement("figcaption");
      caption.textContent = attachment.fileName || "生成的图片";
      figure.append(image, caption);
      gallery.append(figure);
      backend().GetChatImageDataURL({ sessionId: state.active?.id || "", messageId: message.id, imageId: attachment.id })
        .then((url) => { image.src = url; figure.classList.remove("loading"); })
        .catch(() => { figure.classList.remove("loading"); figure.classList.add("unavailable"); caption.textContent = "图片缓存不可用"; });
    });
    card.append(gallery);
  }
  const actions = document.createElement("div");
  actions.className = "image-generation-actions";
  if (["failed", "cancelled"].includes(generation.status)) {
    const retry = document.createElement("button");
    retry.type = "button";
    retry.textContent = "重试";
    retry.disabled = state.running || phoneOwnsActiveSession();
    retry.addEventListener("click", async () => {
      try {
        state.runningSessions[state.active.id] = true;
        state.running = true;
        updateComposerActionState();
        if (isUpscale) {
          await backend().UpscaleImage4K({ sessionId: state.active.id, messageId: generation.sourceMessageId || message.id });
        } else {
          await backend().GenerateImage({ sessionId: state.active.id, prompt: generation.prompt || "" });
        }
      } catch (error) {
        state.runningSessions[state.active?.id] = false;
        refreshRunningState();
        showToast(errorText(error), true);
      }
    });
    actions.append(retry);
  }
  if (generation.status === "generating") {
    const cancel = document.createElement("button");
    cancel.type = "button";
    cancel.textContent = "取消";
    cancel.addEventListener("click", () => backend().CancelImageGeneration(state.active?.id || ""));
    actions.append(cancel);
  }
  if (generation.status === "completed" && message.imageAttachments?.length) {
    const save = document.createElement("button");
    save.type = "button";
    save.textContent = "保存图片";
    save.addEventListener("click", async () => {
      const attachment = message.imageAttachments[0];
      try {
        const url = await backend().GetChatImageDataURL({ sessionId: state.active?.id || "", messageId: message.id, imageId: attachment.id });
        const link = document.createElement("a");
        link.href = url;
        link.download = attachment.fileName || "murong-generated-image";
        document.body.append(link);
        link.click();
        link.remove();
      } catch (error) {
        showToast(errorText(error), true);
      }
    });
    actions.append(save);
    if (!isUpscale) {
      const upscale = document.createElement("button");
      upscale.type = "button";
      upscale.textContent = "真实 4K 超分";
      upscale.disabled = state.running || phoneOwnsActiveSession();
      upscale.addEventListener("click", async () => {
        try {
          state.runningSessions[state.active.id] = true;
          state.running = true;
          updateComposerActionState();
          await backend().UpscaleImage4K({ sessionId: state.active.id, messageId: message.id });
        } catch (error) {
          state.runningSessions[state.active?.id] = false;
          refreshRunningState();
          showToast(errorText(error), true);
        }
      });
      actions.append(upscale);
    }
  }
  if (actions.childElementCount) card.append(actions);
  body.prepend(card);
}

function createWorkspaceMessageNote(changes, omitted) {
  const note = document.createElement("div");
  note.className = "workspace-message-note";
  const heading = document.createElement("strong");
  heading.textContent = `已自动附加 ${formatCount(changes.length + omitted)} 个项目外部变化`;
  const detail = document.createElement("small");
  detail.textContent = "来自终端、Git、格式化器或其他编辑器；Agent 会以磁盘现状为准。";
  const list = document.createElement("div");
  list.className = "workspace-message-paths";
  changes.slice(0, 12).forEach((change) => {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = `${workspaceChangeKindLabel(change.kind)} · ${change.path}`;
    button.addEventListener("click", () => openWorkspaceChanges(change.path));
    list.append(button);
  });
  if (omitted) {
    const more = document.createElement("span");
    more.textContent = `另有 ${formatCount(omitted)} 个未展开`;
    list.append(more);
  }
  note.append(heading, detail, list);
  return note;
}

async function openWorkspaceChanges(path = "") {
  closeComposerPicker();
  state.selectedWorkspacePath = path || state.selectedWorkspacePath;
  $("#workspace-changes-modal").classList.remove("hidden");
  await refreshWorkspaceChanges();
  await refreshProjectAudit(true);
}

function closeWorkspaceChanges() {
  $("#workspace-changes-modal").classList.add("hidden");
}

async function refreshWorkspaceChanges() {
  try {
    state.workspaceChanges = await backend().GetWorkspaceChanges();
    const recent = state.workspaceChanges?.recentChanges || [];
    if (!state.selectedWorkspacePath && recent.length) state.selectedWorkspacePath = recent[recent.length - 1].path;
    renderWorkspaceChangeChip();
    renderWorkspaceChangesModal();
    if (state.workspaceTimelineMode === "live" && state.selectedWorkspacePath) await loadWorkspaceDiff(state.selectedWorkspacePath);
  } catch (error) {
    $("#workspace-monitor-detail").textContent = errorText(error);
  }
}

function setWorkspaceTimelineMode(mode) {
  state.workspaceTimelineMode = mode === "audit" ? "audit" : "live";
  state.selectedWorkspacePath = "";
  state.selectedProjectAuditId = "";
  renderWorkspaceChangesModal();
  if (state.workspaceTimelineMode === "audit") refreshProjectAudit(true);
}

async function refreshProjectAudit(reset = true) {
  try {
    const query = {
      search: state.projectAuditQuery.search || "",
      source: state.projectAuditQuery.source || "all",
      limit: 100,
      beforeAt: reset ? 0 : (state.projectAudit.nextBeforeAt || 0),
      beforeId: reset ? "" : (state.projectAudit.nextBeforeId || ""),
    };
    const page = await backend().GetProjectAudit(query);
    if (!reset) page.entries = [...(state.projectAudit.entries || []), ...(page.entries || [])];
    state.projectAudit = page || { projectPath: "", entries: [], totalCount: 0, filteredCount: 0, hasMore: false, archiveVersion: 1 };
    $("#project-audit-count").textContent = formatCount(state.projectAudit.totalCount || 0);
    if (state.workspaceTimelineMode === "audit") renderWorkspaceChangesModal();
  } catch (error) {
    state.projectAudit.storageError = errorText(error);
    if (state.workspaceTimelineMode === "audit") renderWorkspaceChangesModal();
  }
}

async function exportProjectAudit(format) {
  try {
    const result = await backend().ExportProjectAudit(format);
    showToast(result?.message || "项目审计已导出", false);
  } catch (error) {
    showToast(errorText(error), true);
  }
}

async function clearWorkspaceChanges() {
  try {
    state.workspaceChanges = await backend().ClearWorkspaceChanges();
    state.selectedWorkspacePath = "";
    renderWorkspaceChangeChip();
    renderWorkspaceChangesModal();
    $("#workspace-diff-title").textContent = "选择文件查看 Diff";
    $("#workspace-diff-detail").textContent = "变化记录已清空；项目文件没有被修改。";
    $("#workspace-diff-content").textContent = "尚未选择文件。";
  } catch (error) {
    showToast(errorText(error), true);
  }
}

function renderWorkspaceChangesModal() {
  const auditMode = state.workspaceTimelineMode === "audit";
  $("#workspace-live-tab").classList.toggle("active", !auditMode);
  $("#workspace-live-tab").setAttribute("aria-selected", String(!auditMode));
  $("#project-audit-tab").classList.toggle("active", auditMode);
  $("#project-audit-tab").setAttribute("aria-selected", String(auditMode));
  $("#project-audit-filters").classList.toggle("hidden", !auditMode);
  $("#clear-workspace-changes").classList.toggle("hidden", auditMode);
  $("#project-audit-load-more").classList.add("hidden");
  if (auditMode) {
    renderProjectAuditModal();
    return;
  }
  const snapshot = state.workspaceChanges || {};
  const pending = snapshot.pendingChanges || [];
  const recent = snapshot.recentChanges || [];
  const omitted = snapshot.omittedCount || 0;
  $("#workspace-monitor-title").textContent = snapshot.projectPath ? projectName(snapshot.projectPath) : "尚未选择项目";
  $("#workspace-monitor-detail").textContent = snapshot.error
    ? `监听失败：${snapshot.error}`
    : snapshot.watching
		? `正在使用 ${state.platform?.label || "当前系统"} 原生目录通知；直接文件工具产生的重复事件会被过滤。`
      : "选择项目后开始监听终端、Git、格式化器和其他编辑器的变化。";
  $("#workspace-pending-count").textContent = `${formatCount(pending.length + omitted)} 个待提供给 Agent`;
  $("#workspace-list-title").textContent = "最近变化";
  $("#clear-workspace-changes").disabled = recent.length === 0 && pending.length === 0 && omitted === 0;
  const pendingPaths = new Set(pending.map((change) => change.path.toLowerCase()));
  const list = $("#workspace-change-list");
  list.replaceChildren();
  if (!recent.length) {
    const empty = document.createElement("div");
    empty.className = "workspace-change-empty";
    empty.textContent = snapshot.projectPath ? "还没有检测到项目外部变化。" : "先打开或新建一个项目。";
    list.append(empty);
    return;
  }
  [...recent].reverse().forEach((change) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `workspace-change-item${change.path === state.selectedWorkspacePath ? " selected" : ""}`;
    const kind = document.createElement("span");
    kind.className = `workspace-change-kind ${change.kind || "modified"}`;
    kind.textContent = workspaceChangeKindLabel(change.kind);
    const copy = document.createElement("span");
    copy.className = "workspace-change-copy";
    const path = document.createElement("strong");
    path.textContent = change.path;
    const meta = document.createElement("small");
    const pendingLabel = pendingPaths.has(change.path.toLowerCase()) ? " · 待注入下一轮" : "";
    meta.textContent = `${formatTime(change.changedAt)}${change.directory ? " · 文件夹" : change.size ? ` · ${formatFileSize(change.size)}` : ""}${pendingLabel}`;
    copy.append(path, meta);
    button.append(kind, copy);
    button.addEventListener("click", () => selectWorkspaceChange(change.path));
    list.append(button);
  });
}

function renderProjectAuditModal() {
  const page = state.projectAudit || {};
  const entries = page.entries || [];
  $("#workspace-monitor-title").textContent = page.projectPath ? projectName(page.projectPath) : "尚未选择项目";
  $("#workspace-monitor-detail").textContent = page.storageError
    ? `归档写入异常：${page.storageError}`
    : "按项目持久保存外部变化、Agent 文件操作和终端 / Git 执行结果；不记录命令正文、工具参数、文件内容或密钥。";
  $("#workspace-list-title").textContent = "跨任务审计归档";
  $("#workspace-pending-count").textContent = `${formatCount(page.filteredCount || 0)} 条匹配 · 共 ${formatCount(page.totalCount || 0)} 条`;
  $("#project-audit-count").textContent = formatCount(page.totalCount || 0);
  $("#project-audit-search").value = state.projectAuditQuery.search || "";
  $("#project-audit-source").value = state.projectAuditQuery.source || "all";
  $("#clear-project-audit").disabled = !page.totalCount;
  const loadMore = $("#project-audit-load-more");
  loadMore.classList.toggle("hidden", !page.hasMore);
  loadMore.disabled = !page.hasMore;

  const list = $("#workspace-change-list");
  list.replaceChildren();
  if (!entries.length) {
    const empty = document.createElement("div");
    empty.className = "workspace-change-empty";
    empty.textContent = page.projectPath
      ? (state.projectAuditQuery.search || state.projectAuditQuery.source !== "all" ? "没有符合当前筛选的审计记录。" : "还没有跨任务审计记录。")
      : "先打开或新建一个项目。";
    list.append(empty);
    $("#workspace-diff-title").textContent = "选择审计条目";
    $("#workspace-diff-detail").textContent = "可以查看当前工作树 Diff 或不含敏感参数的审计详情。";
    $("#workspace-diff-content").textContent = "尚未选择审计条目。";
    return;
  }
  entries.forEach((entry) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `workspace-change-item project-audit-item${entry.id === state.selectedProjectAuditId ? " selected" : ""}`;
    const source = document.createElement("span");
    source.className = `workspace-change-kind ${entry.source || "agent"}`;
    source.textContent = projectAuditSourceLabel(entry.source);
    const copy = document.createElement("span");
    copy.className = "workspace-change-copy";
    const summary = document.createElement("strong");
    summary.textContent = entry.summary || projectAuditActionLabel(entry.action);
    const paths = document.createElement("span");
    paths.className = "project-audit-paths";
    paths.textContent = entry.paths?.length ? entry.paths.join(" · ") : "未保存命令或工具参数";
    const meta = document.createElement("small");
    const session = entry.sessionTitle ? ` · ${entry.sessionTitle}` : "";
    const repeats = (entry.occurrences || 1) > 1 ? ` · ${formatCount(entry.occurrences)} 次` : "";
    meta.textContent = `${formatTime(entry.createdAt)} · ${projectAuditOutcomeLabel(entry.outcome)}${session}${repeats}`;
    copy.append(summary, paths, meta);
    button.append(source, copy);
    button.addEventListener("click", () => selectProjectAuditEntry(entry));
    list.append(button);
  });
}

async function selectProjectAuditEntry(entry) {
  state.selectedProjectAuditId = entry.id;
  state.selectedWorkspacePath = entry.paths?.[0] || "";
  renderWorkspaceChangesModal();
  if (state.selectedWorkspacePath) {
    await loadWorkspaceDiff(state.selectedWorkspacePath);
    return;
  }
  $("#workspace-diff-title").textContent = entry.summary || projectAuditActionLabel(entry.action);
  $("#workspace-diff-detail").textContent = `${projectAuditSourceLabel(entry.source)} · ${projectAuditOutcomeLabel(entry.outcome)} · ${formatTime(entry.createdAt)}`;
  $("#workspace-diff-content").textContent = [
    `动作：${projectAuditActionLabel(entry.action)}`,
    `工具：${entry.toolName || "无"}`,
    `会话：${entry.sessionTitle || entry.sessionId || "未关联会话"}`,
    "",
    "出于安全边界，审计归档不保存终端命令正文、工具参数、文件内容、模型思考或凭据。",
  ].join("\n");
}

function projectAuditSourceLabel(source) {
  return ({ external: "外部", agent: "Agent", codex: "Codex" })[source] || "Agent";
}

function projectAuditOutcomeLabel(outcome) {
  return ({ observed: "已观察", success: "成功", failed: "失败" })[outcome] || "已记录";
}

function projectAuditActionLabel(action) {
  return ({
    created: "新增路径", modified: "修改路径", deleted: "删除路径", renamed_from: "重命名前路径", renamed_to: "重命名后路径",
    file_write: "写入文件", directory_create: "创建目录", path_delete: "删除路径", permission_change: "修改权限",
    code_edit: "编辑代码", terminal: "执行终端", git: "执行 Git",
  })[action] || "项目动作";
}

async function selectWorkspaceChange(path) {
  state.selectedWorkspacePath = path;
  renderWorkspaceChangesModal();
  await loadWorkspaceDiff(path);
}

async function loadWorkspaceDiff(path) {
  $("#workspace-diff-title").textContent = path;
  $("#workspace-diff-detail").textContent = "正在读取 Diff…";
  $("#workspace-diff-content").textContent = "";
  try {
    const result = await backend().GetWorkspaceDiff(path);
    if (state.selectedWorkspacePath !== path) return;
    $("#workspace-diff-detail").textContent = result.message || (result.git ? "Git 工作树 Diff" : "当前文件文本预览");
    $("#workspace-diff-content").textContent = result.available ? result.diff : (result.message || "当前没有可显示的 Diff。");
  } catch (error) {
    $("#workspace-diff-detail").textContent = "无法读取 Diff";
    $("#workspace-diff-content").textContent = errorText(error);
  }
}

function workspaceChangeKindLabel(kind) {
  return ({ created: "新增", modified: "修改", deleted: "删除", renamed_from: "重命名前", renamed_to: "重命名后" })[kind] || "变化";
}

async function openSessionDetails() {
  closeComposerPicker();
  state.sessionStats = null;
  $("#session-details-status").textContent = "";
  $("#session-details-modal").classList.remove("hidden");
  renderSessionDetails();
  if (!state.active) return;
  try {
    state.sessionStats = await backend().GetSessionStats(state.active.id);
    renderSessionDetails();
    $("#session-title-input").focus();
    $("#session-title-input").select();
  } catch (error) {
    $("#session-details-status").textContent = errorText(error);
  }
}

function closeSessionDetails() {
  $("#session-details-modal").classList.add("hidden");
  $("#session-details-status").textContent = "";
}

function renderSessionDetails() {
  const active = state.active;
  const executionLocked = phoneOwnsActiveSession();
  $("#session-details-title").textContent = active ? "任务详情" : "任务管理";
  $("#session-title-input").value = active?.title || "";
  $("#session-title-input").disabled = !active || state.running || executionLocked;
  $("#rename-session").disabled = !active || state.running || executionLocked;
  const projectPath = activeProjectPath();
  const explicitlyBound = Boolean(active?.projectPath);
  const missingProject = Boolean(explicitlyBound && !state.sessionProjectAvailable);
  $("#session-project-name").textContent = projectPath ? projectName(projectPath) : "尚未绑定项目";
  $("#session-project-path").textContent = projectPath || "选择项目后，这项任务会固定使用该目录执行。";
  $("#session-project-status").textContent = missingProject
    ? `绑定路径不可用${state.sessionProjectError ? `：${state.sessionProjectError}` : ""}`
    : explicitlyBound ? "已固定绑定；切换其他任务不会改变此目录" : projectPath ? "旧任务将在首次发送时固定绑定当前项目" : "发送前必须选择项目";
  $("#session-project-binding").classList.toggle("error", missingProject);
  $("#session-project-action").textContent = projectPath ? "更换项目" : "选择项目";
  $("#session-project-action").disabled = !active || state.running || executionLocked;
  $("#fork-session").disabled = !active || state.running;
  const stats = state.sessionStats || {};
  const compressionBusy = state.running || state.sessionCompressionRunning || executionLocked;
  $("#compress-session").disabled = !active || compressionBusy || (stats.messageCount || 0) < 12;
  const hasCompression = Boolean(stats.compressionAvailable);
  $("#toggle-session-compression").classList.toggle("hidden", !hasCompression);
  $("#toggle-session-compression").disabled = !active || compressionBusy;
  $("#toggle-session-compression").textContent = stats.compressionActive ? "停用摘要" : "启用摘要";
  if (!active) {
    $("#session-compression-detail").textContent = "选择任务后可以整理较早历史。完整聊天记录不会被删除。";
  } else if (hasCompression) {
    const method = stats.compressionMethod === "provider" ? "模型整理" : "本地整理";
    const status = stats.compressionActive ? "已启用" : "已停用";
    $("#session-compression-detail").textContent = `${status} · 第 ${formatCount(stats.compressionVersion || 1)} 版 · ${method} · 覆盖较早 ${formatCount(stats.compressionMessages || 0)} 条消息${stats.compressionCreatedAt ? ` · ${formatTime(stats.compressionCreatedAt)}` : ""}。完整记录仍保留。`;
  } else if ((stats.messageCount || 0) < 12) {
    $("#session-compression-detail").textContent = `至少需要 12 条消息；当前 ${formatCount(stats.messageCount || 0)} 条。完整聊天记录不会被删除。`;
  } else {
    $("#session-compression-detail").textContent = "可以整理较早历史，减少后续发送给模型的上下文；完整聊天记录不会被删除。";
  }
  $("#export-session-json").disabled = !active;
  $("#export-session-portable").disabled = !active;
  $("#export-session-markdown").disabled = !active;
  $("#session-stat-messages").textContent = formatCount(stats.messageCount || 0);
  $("#session-stat-users").textContent = `${formatCount(stats.userMessages || 0)} / ${formatCount(stats.assistantMessages || 0)}`;
  $("#session-stat-tools").textContent = formatCount(stats.toolMessages || 0);
  const hasProviderUsage = Boolean(stats.providerUsageAvailable);
  $("#session-stat-tokens").textContent = hasProviderUsage
    ? formatCount(stats.providerTotalTokens || 0)
    : `约 ${formatCount(stats.estimatedTokens || 0)}`;
  $("#session-stat-token-label").textContent = hasProviderUsage ? "供应商 Token" : "Token 估算";
  if (!active) {
    $("#session-stat-detail").textContent = "可导入 JSON 创建任务；选择现有任务后可查看统计和导出。";
  } else if (hasProviderUsage) {
    const details = [
      `供应商返回 ${formatCount(stats.providerInputTokens || 0)} 输入 + ${formatCount(stats.providerOutputTokens || 0)} 输出`,
      `${formatCount(stats.reportedUsageRequests || 0)} / ${formatCount(stats.modelRequests || 0)} 次模型请求带用量`,
    ];
    if (stats.cachedInputTokens) details.push(`缓存输入 ${formatCount(stats.cachedInputTokens)}`);
    if (stats.reasoningOutputTokens) details.push(`其中推理 ${formatCount(stats.reasoningOutputTokens)}`);
    if (!stats.providerUsageComplete) details.push("未返回用量的调用不计入供应商总数");
    details.push(`本地文本估算约 ${formatCount(stats.estimatedTokens || 0)}`);
    if (stats.lastModel) details.push(`最近模型 ${stats.lastModel}`);
    if (stats.updatedAt) details.push(`最近更新 ${formatTime(stats.updatedAt)}`);
    $("#session-stat-detail").textContent = details.join(" · ");
  } else {
    $("#session-stat-detail").textContent = `${formatCount(stats.characterCount || 0)} 个字符 · Token 为本地估算，不代表供应商账单用量${stats.modelRequests ? ` · ${formatCount(stats.modelRequests)} 次调用未返回用量` : ""}${stats.updatedAt ? ` · 最近更新 ${formatTime(stats.updatedAt)}` : ""}`;
  }
}

async function compressActiveSession() {
  if (!state.active || state.running || state.sessionCompressionRunning || phoneOwnsActiveSession()) return;
  state.sessionCompressionRunning = true;
  $("#session-details-status").textContent = "正在整理较早历史…";
  renderSessionDetails();
  try {
    state.active = await backend().CompressSession(state.active.id);
    state.sessionStats = await backend().GetSessionStats(state.active.id);
    renderSessions();
    renderActiveSession();
    $("#session-details-status").textContent = "上下文摘要已生成并启用；完整聊天记录仍然保留";
  } catch (error) {
    $("#session-details-status").textContent = errorText(error);
  } finally {
    state.sessionCompressionRunning = false;
    renderSessionDetails();
  }
}

async function toggleActiveSessionCompression() {
  if (!state.active || state.running || state.sessionCompressionRunning || phoneOwnsActiveSession() || !state.sessionStats?.compressionAvailable) return;
  state.sessionCompressionRunning = true;
  renderSessionDetails();
  try {
    const active = !state.sessionStats.compressionActive;
    state.active = await backend().SetSessionCompressionActive({ sessionId: state.active.id, active });
    state.sessionStats = await backend().GetSessionStats(state.active.id);
    renderActiveSession();
    $("#session-details-status").textContent = active ? "后续模型调用将使用摘要和最近消息" : "后续模型调用将恢复使用完整历史";
  } catch (error) {
    $("#session-details-status").textContent = errorText(error);
  } finally {
    state.sessionCompressionRunning = false;
    renderSessionDetails();
  }
}

async function renameActiveSession(event) {
  event.preventDefault();
  if (!state.active || state.running || phoneOwnsActiveSession()) return;
  const title = $("#session-title-input").value.trim();
  try {
    state.active = await backend().RenameSession({ sessionId: state.active.id, title });
    renderSessions();
    renderActiveSession();
    renderSessionDetails();
    $("#session-details-status").textContent = "任务名称已保存";
  } catch (error) {
    $("#session-details-status").textContent = errorText(error);
  }
}

async function forkSessionFrom(messageId) {
  if (!state.active || state.running) return;
  try {
    state.active = await backend().ForkSession({ sessionId: state.active.id, messageId: messageId || "", confirmed: false });
    state.goalModeEnabled = false;
    state.sessionStats = await backend().GetSessionStats(state.active.id);
    closeSessionDetails();
    renderSessions();
    renderActiveSession();
    showToast(messageId ? "已从选中消息创建独立分叉" : "已分叉当前任务");
  } catch (error) {
    showToast(errorText(error), true);
  }
}

function requestSessionRollback(messageId) {
  if (!state.active || state.running || phoneOwnsActiveSession()) return;
  const index = (state.active.messages || []).findIndex((message) => message.id === messageId);
  if (index < 0) {
    showToast("消息不存在或已被移除", true);
    return;
  }
  const removed = state.active.messages.length - index - 1;
  state.pendingSessionPoint = { sessionId: state.active.id, messageId };
  $("#session-action-detail").textContent = removed
    ? `将保留选中消息，并永久移除它之后的 ${removed} 条记录。`
    : "选中消息已是最后一条记录，确认后只会刷新任务时间。";
  $("#session-action-modal").classList.remove("hidden");
}

function closeSessionActionConfirmation() {
  state.pendingSessionPoint = null;
  $("#session-action-modal").classList.add("hidden");
}

async function confirmSessionRollback() {
  const pending = state.pendingSessionPoint;
  if (!pending) return;
  $("#confirm-session-action").disabled = true;
  try {
    state.active = await backend().RollbackSession({ ...pending, confirmed: true });
    closeSessionActionConfirmation();
    state.sessionStats = await backend().GetSessionStats(state.active.id);
    renderSessions();
    renderActiveSession();
    renderSessionDetails();
    showToast("任务已回退到选中消息");
  } catch (error) {
    showToast(errorText(error), true);
  } finally {
    $("#confirm-session-action").disabled = false;
  }
}

async function exportActiveSession(format) {
  if (!state.active) return;
  $("#session-details-status").textContent = "请选择导出位置…";
  try {
    const path = await backend().ExportSession({ sessionId: state.active.id, format });
    $("#session-details-status").textContent = path ? `已导出到 ${path}` : "已取消导出";
  } catch (error) {
    $("#session-details-status").textContent = errorText(error);
  }
}

async function importSession() {
  $("#session-details-status").textContent = "请选择 JSON 会话文件…";
  try {
    const imported = await backend().ImportSession();
    if (!imported) {
      $("#session-details-status").textContent = "已取消导入";
      return;
    }
    state.active = imported;
    state.goalModeEnabled = false;
    closeSessionDetails();
    renderSessions();
    renderActiveSession();
    showToast("会话已作为独立任务导入");
  } catch (error) {
    $("#session-details-status").textContent = errorText(error);
  }
}

function formatCount(value) {
  return new Intl.NumberFormat("zh-CN").format(Number(value) || 0);
}

function createStreamingMessage(streamId) {
  const article = document.createElement("article");
  article.className = "message assistant";
  article.dataset.streamId = streamId;
  const avatar = document.createElement("div");
  avatar.className = "message-avatar";
  avatar.textContent = "M";
  const body = document.createElement("div");
  body.className = "message-body stream-cursor";
  body.dataset.raw = "";
  const stack = document.createElement("div");
  stack.className = "message-content-stack";
  stack.append(body);
  article.append(avatar, stack);
  $("#messages").append(article);
  scrollMessages();
}

async function chooseProject() {
  try {
    const config = await backend().OpenProject();
    if (!config?.projectPath) return;
    await applyProjectConfiguration(config);
    closeProjectModal();
    showToast(`已打开项目：${projectName(config.projectPath)}`);
  } catch (error) {
    showToast(errorText(error), true);
  }
}

function bindEmptyProjectActions(root) {
  root.querySelectorAll("[data-project-action]").forEach((button) => {
    button.addEventListener("click", () => openProjectModal(button.dataset.projectAction === "create"));
  });
}

function openProjectModal(create = false) {
  if (phoneOwnsActiveSession()) {
    showToast("这项任务正在手机上继续，归还后才能更换项目", true);
    return;
  }
  if (state.running) {
    showToast("当前任务仍在运行，停止后才能更换项目", true);
    return;
  }
  closeComposerPicker();
  renderProjectModal();
  $("#project-modal").classList.remove("hidden");
  if (create) showCreateProjectForm();
  else showProjectHome();
}

function closeProjectModal() {
  $("#project-modal").classList.add("hidden");
}

function showProjectHome() {
  $("#project-modal-home").classList.remove("hidden");
  $("#create-project-form").classList.add("hidden");
  renderProjectModal();
}

function showCreateProjectForm() {
  $("#project-modal-home").classList.add("hidden");
  $("#create-project-form").classList.remove("hidden");
  if (!$("#new-project-parent").value && activeProjectPath()) {
    $("#new-project-parent").value = parentProjectPath(activeProjectPath());
  }
  requestAnimationFrame(() => $("#new-project-name").focus());
}

async function chooseProjectParent() {
  try {
    const selected = await backend().SelectProjectParent($("#new-project-parent").value || parentProjectPath(activeProjectPath()));
    if (selected) $("#new-project-parent").value = selected;
  } catch (error) {
    showToast(errorText(error), true);
  }
}

async function createProject(event) {
  event.preventDefault();
  try {
    const config = await backend().CreateProject({
      parentPath: $("#new-project-parent").value.trim(),
      name: $("#new-project-name").value.trim(),
    });
    await applyProjectConfiguration(config);
    $("#new-project-name").value = "";
    closeProjectModal();
    showToast(`已创建项目：${projectName(config.projectPath)}`);
  } catch (error) {
    showToast(errorText(error), true);
  }
}

async function applyProjectConfiguration(config, bindActive = true) {
  const previousPath = activeProjectPath();
  state.config = config;
  if (bindActive && state.active) {
    state.active = await backend().BindSessionProject({
      sessionId: state.active.id,
      projectPath: config?.projectPath || "",
    });
    state.sessionProjectAvailable = Boolean(state.active.projectPath);
    state.sessionProjectError = "";
  } else if (!state.active) {
    state.sessionProjectAvailable = Boolean(config?.projectPath);
    state.sessionProjectError = "";
  }
  const changed = !sameProjectPath(previousPath, activeProjectPath());
  if (changed) {
    state.composerContext = [];
    state.toolPolicyScope = "global";
  }
  const [knowledge, tools] = await Promise.all([
    backend().GetProjectKnowledge(), backend().GetProjectToolPreferences(),
  ]);
  state.projectKnowledge = knowledge;
  state.projectTools = tools;
  await refreshComposerCatalog();
  renderComposerContext();
  renderSettings();
  renderKnowledge();
  renderSessions();
  renderActiveSession();
}

function renderProjectModal() {
  const current = $("#current-project-panel");
  const activePath = activeProjectPath();
  current.replaceChildren();
  current.classList.toggle("hidden", !activePath);
  if (activePath) {
    current.append(projectFolderIcon(), projectRowCopy(projectName(activePath), activePath));
    const close = document.createElement("button");
    close.className = "secondary danger";
    close.type = "button";
    close.textContent = "关闭项目";
    close.addEventListener("click", closeActiveProject);
    current.append(close);
  }

  const list = $("#recent-project-list");
  list.replaceChildren();
  const recent = state.config?.recentProjects || [];
  if (!recent.length) {
    const empty = document.createElement("div");
    empty.className = "recent-project-empty";
    empty.textContent = "还没有最近项目。打开或新建后会自动保存在这里。";
    list.append(empty);
    return;
  }
  recent.forEach((project) => {
    const row = document.createElement("div");
    row.className = `recent-project-row${project.exists ? "" : " missing"}`;
    row.append(projectFolderIcon(), projectRowCopy(project.name || projectName(project.path), project.exists ? project.path : `${project.path} · 已移动或删除`));
    const open = document.createElement("button");
    open.className = "secondary";
    open.type = "button";
		open.textContent = activePath && sameProjectPath(activePath, project.path) ? "当前" : "打开";
		open.disabled = !project.exists || sameProjectPath(activePath, project.path);
    open.addEventListener("click", () => activateRecentProject(project.path));
    const forget = document.createElement("button");
    forget.className = "icon-button danger";
    forget.type = "button";
    forget.title = "从最近项目移除";
    forget.textContent = "×";
    forget.addEventListener("click", () => forgetRecentProject(project.path));
    row.append(open, forget);
    list.append(row);
  });
}

function projectFolderIcon() {
  const icon = document.createElement("span");
  icon.className = "project-folder-icon";
  icon.textContent = "▰";
  return icon;
}

function projectRowCopy(name, path) {
  const copy = document.createElement("div");
  copy.className = "project-row-copy";
  const strong = document.createElement("strong");
  strong.textContent = name;
  const small = document.createElement("small");
  small.textContent = path;
  copy.append(strong, small);
  return copy;
}

async function activateRecentProject(path) {
  try {
    const config = await backend().ActivateProject(path);
    await applyProjectConfiguration(config);
    closeProjectModal();
    showToast(`已切换到：${projectName(path)}`);
  } catch (error) {
    showToast(errorText(error), true);
  }
}

async function closeActiveProject() {
  try {
    const config = await backend().CloseProject();
    await applyProjectConfiguration(config);
    closeProjectModal();
    showToast("已关闭当前项目，最近项目记录仍然保留");
  } catch (error) {
    showToast(errorText(error), true);
  }
}

async function forgetRecentProject(path) {
  try {
    state.config = await backend().ForgetRecentProject(path);
    renderProjectModal();
    renderSettings();
  } catch (error) {
    showToast(errorText(error), true);
  }
}

async function refreshComposerCatalog() {
  try {
    state.composerCatalog = await backend().GetComposerCatalog() || { skills: [], subagents: [], mcpTools: [] };
    if (!$("#composer-picker").classList.contains("hidden")) scheduleComposerPickerRender();
  } catch (error) {
    state.composerCatalog = { skills: [], subagents: [], mcpTools: [] };
  }
}

function openComposerPicker(mode) {
  if (phoneOwnsActiveSession()) {
    showToast("这项任务正在手机上继续", true);
    return;
  }
  if (mode === "files" && (!activeProjectPath() || !state.sessionProjectAvailable)) {
    showToast("请先打开或新建一个项目", true);
    openProjectModal();
    return;
  }
  state.composerPickerMode = mode;
  state.composerBrowserPath = "";
  const labels = {
    all: ["添加本轮上下文", "项目内容、Skill、子代理和 MCP 都会真实进入本轮请求"],
    files: ["@ 文件或文件夹", "从当前项目选择重点上下文"],
    skills: ["选择 Skills", "明确指定本轮要遵循的工作方法"],
    subagents: ["选择子代理", "指定本轮的委派执行方式"],
    mcp: ["选择 MCP 工具", "只显示已连接且允许使用的工具"],
  };
  $("#composer-picker-title").textContent = labels[mode]?.[0] || labels.all[0];
  $("#composer-picker-detail").textContent = labels[mode]?.[1] || labels.all[1];
  $("#composer-picker-search").value = "";
  $("#composer-picker").classList.remove("hidden");
  $("#composer-add").classList.toggle("active", mode === "all");
  scheduleComposerPickerRender();
  requestAnimationFrame(() => $("#composer-picker-search").focus());
}

function closeComposerPicker() {
  const picker = $("#composer-picker");
  if (!picker) return;
  picker.classList.add("hidden");
  state.composerPickerMode = "";
  $("#composer-add")?.classList.remove("active");
}

function scheduleComposerPickerRender() {
  clearTimeout(scheduleComposerPickerRender.timer);
  scheduleComposerPickerRender.timer = setTimeout(loadComposerProjectEntries, 90);
}

async function loadComposerProjectEntries() {
  const mode = state.composerPickerMode;
  if (!mode) return;
  const query = $("#composer-picker-search").value.trim();
  const token = ++state.composerSearchToken;
  state.composerProjectEntries = [];
  if ((mode === "all" || mode === "files") && activeProjectPath() && state.sessionProjectAvailable) {
    try {
      const entries = await backend().BrowseProjectEntries(state.composerBrowserPath || "", query);
      if (token !== state.composerSearchToken) return;
      state.composerProjectEntries = entries || [];
    } catch (error) {
      if (token !== state.composerSearchToken) return;
      showToast(errorText(error), true);
    }
  }
  renderComposerPicker();
}

function renderComposerPicker() {
  const list = $("#composer-picker-list");
  list.replaceChildren();
  const mode = state.composerPickerMode;
  const query = $("#composer-picker-search").value.trim().toLowerCase();
  const filterChoices = (values) => (values || []).filter((item) => !query || `${item.label} ${item.detail || ""} ${item.id || ""}`.toLowerCase().includes(query));
  if (mode === "all") {
    const multimodalEnabled = state.config?.enableMultimodalMessages !== false;
    appendComposerPickerSection(list, "图片", filterChoices([{
      kind: "image", id: "select-images", label: "从电脑选择图片",
      detail: multimodalEnabled
        ? `JPEG / PNG / WebP / GIF / BMP，每条消息最多 ${8 - state.composerImages.length} 张可选`
        : "图片消息已在 Agent 设置中关闭",
      disabled: !multimodalEnabled || state.composerImages.length >= 8,
    }]));
  }
  if (mode === "all") {
    const modeItems = [
      {
        kind: "mode", id: "plan", label: state.active?.planModeEnabled ? "计划模式：开" : "计划模式：关",
        detail: "只调查并生成步骤计划，等待你明确要求后再执行",
      },
      {
        kind: "mode", id: "goal", label: state.goalModeEnabled ? (state.active?.goal ? "更新目标：开" : "设置目标：开") : (state.active?.goal ? "更新目标：关" : "设置目标：关"),
        detail: "把下一条消息设为当前任务的长期目标",
      },
    ];
    appendComposerPickerSection(list, "运行模式", filterChoices(modeItems));
  }
  if (mode === "all" || mode === "files") {
    appendComposerBrowserNavigation(list, query);
    const files = state.composerProjectEntries.map((entry) => ({
      kind: entry.directory ? "folder" : "file", path: entry.path, label: entry.name || entry.path,
      detail: entry.directory ? entry.path : `${entry.path} · ${formatFileSize(entry.size)}`, scope: "project",
    }));
    appendComposerPickerSection(
      list,
      "项目文件与文件夹",
      files,
      query ? "没有匹配的项目文件或文件夹" : activeProjectPath() ? "当前项目中没有可选择的文件" : "请先打开或新建项目，再从这里选择文件或文件夹",
    );
  }
  if (mode === "all" || mode === "skills") {
    const skills = filterChoices(state.composerCatalog.skills);
    appendComposerPickerSection(
      list,
      "Skills",
      skills,
      query ? "没有匹配的 Skill" : "尚未创建可用 Skill，可在知识库中添加",
    );
  }
  if (mode === "all" || mode === "subagents") {
    appendComposerPickerSection(
      list,
      "子代理",
      filterChoices(state.composerCatalog.subagents),
      query ? "没有匹配的子代理" : "尚无可用子代理，请先在设置中启用子代理工具",
    );
  }
  if (mode === "all" || mode === "mcp") {
    const mcpTools = filterChoices(state.composerCatalog.mcpTools);
    appendComposerPickerSection(
      list,
      "MCP",
      mcpTools,
      query ? "没有匹配的 MCP 工具" : "尚无已连接的 MCP 工具，可在知识库下方配置",
    );
  }
  if (!list.querySelector(".composer-picker-item")) {
    const empty = document.createElement("div");
    empty.className = "composer-picker-empty";
    empty.textContent = mode === "mcp" || (mode === "all" && !state.composerCatalog.mcpTools?.length)
      ? "没有可用项。MCP 服务器连接成功后，其工具会显示在这里。"
      : "没有找到匹配的内容。";
    list.append(empty);
  }
}

function appendComposerBrowserNavigation(container, query) {
  if (!activeProjectPath() || !state.sessionProjectAvailable) return;
  const nav = document.createElement("div");
  nav.className = "composer-browser-nav";
  const up = document.createElement("button");
  up.type = "button";
  up.textContent = "← 上一级";
  up.disabled = !state.composerBrowserPath || Boolean(query);
  up.addEventListener("click", () => {
    const parts = state.composerBrowserPath.split("/").filter(Boolean);
    parts.pop();
    navigateComposerFolder(parts.join("/"));
  });
  window.runtime.EventsOn("provider-import:preview", (preview) => openProviderImportPreview(preview));
  window.runtime.EventsOn("provider-import:error", (payload) => showToast(payload?.message || "供应商导入失败", true));
  window.runtime.EventsOn("provider-usage:changed", (snapshot) => {
    state.providerUsage = snapshot || { items: [] };
    renderProviderImportIntegration();
  });
  const current = document.createElement("span");
  current.textContent = query
    ? `在 ${state.composerBrowserPath || "项目根目录"} 中搜索`
    : state.composerBrowserPath || "项目根目录";
  current.title = current.textContent;
  nav.append(up, current);
  container.append(nav);
}

function navigateComposerFolder(path) {
  state.composerBrowserPath = String(path || "").replace(/\\/g, "/").replace(/^\/+|\/+$/g, "");
  $("#composer-picker-search").value = "";
  scheduleComposerPickerRender();
}

function appendComposerPickerSection(container, title, items, emptyText = "") {
  if (!items?.length && !emptyText) return;
  const heading = document.createElement("div");
  heading.className = "composer-picker-section";
  heading.textContent = title;
  container.append(heading);
  if (!items?.length) {
    const empty = document.createElement("div");
    empty.className = "composer-picker-empty-row";
    empty.textContent = emptyText;
    container.append(empty);
    return;
  }
  items.forEach((item) => container.append(createComposerPickerItem(item)));
}

function createComposerPickerItem(item) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = `composer-picker-item${composerContextSelected(item) ? " selected" : ""}`;
  button.disabled = Boolean(item.disabled);
  const icon = document.createElement("span");
  icon.className = "composer-picker-icon";
  icon.textContent = contextKindIcon(item.kind);
  const copy = document.createElement("span");
  copy.className = "composer-picker-copy";
  const strong = document.createElement("strong");
  strong.textContent = item.label;
  const detail = document.createElement("small");
  detail.textContent = item.detail || contextKindLabel(item.kind);
  copy.append(strong, detail);
  const scope = document.createElement("span");
  scope.className = "composer-picker-scope";
  scope.textContent = composerContextSelected(item) ? "已选择" : (item.kind === "folder" ? "打开" : item.scope === "project" ? "项目" : item.scope === "global" ? "全局" : contextKindLabel(item.kind));
  button.append(icon, copy, scope);
  button.addEventListener("click", () => {
    if (item.kind === "image") selectComposerImages();
    else if (item.kind === "mode") toggleComposerMode(item.id);
    else if (item.kind === "folder") navigateComposerFolder(item.path);
    else toggleComposerContext(item);
  });
  if (item.kind === "folder") {
    const row = document.createElement("div");
    row.className = "composer-picker-folder-row";
    const select = document.createElement("button");
    select.type = "button";
    select.className = `composer-folder-select${composerContextSelected(item) ? " selected" : ""}`;
    select.textContent = composerContextSelected(item) ? "已选" : "选择文件夹";
    select.addEventListener("click", () => toggleComposerContext(item));
    row.append(button, select);
    return row;
  }
  return button;
}

async function selectComposerImages() {
  if (phoneOwnsActiveSession()) return;
  if (state.composerImages.length >= 8) {
    showToast("每条消息最多选择 8 张图片", true);
    return;
  }
  try {
    const selected = await backend().SelectChatImages();
    if (!selected?.length) return;
    const available = 8 - state.composerImages.length;
    if (selected.length > available) {
      await Promise.allSettled(selected.map((value) => backend().DiscardChatImage({ imageId: value.attachment.id, cacheFile: value.attachment.cacheFile })));
      showToast(`本轮还可添加 ${available} 张图片`, true);
      return;
    }
    state.composerImages.push(...selected);
    closeComposerPicker();
    renderComposerContext();
    $("#message-input").focus();
  } catch (error) {
    showToast(errorText(error), true);
  }
}

async function toggleComposerMode(mode) {
  if (phoneOwnsActiveSession()) return;
  try {
    if (mode === "goal") {
      state.goalModeEnabled = !state.goalModeEnabled;
    } else if (mode === "plan") {
      if (!state.active) state.active = await backend().CreateSession();
      state.active = await backend().SetSessionPlanMode(state.active.id, !state.active.planModeEnabled);
    }
    renderActiveSession();
    renderComposerPicker();
  } catch (error) {
    showToast(errorText(error), true);
  }
}

function toggleComposerContext(item) {
  const candidate = {
    kind: item.kind, id: item.id || "", label: item.label || item.path || "",
    path: item.path || "", scope: item.scope || "",
  };
  const key = composerContextKey(candidate);
  const index = state.composerContext.findIndex((value) => composerContextKey(value) === key);
  if (index >= 0) state.composerContext.splice(index, 1);
  else {
    if (state.composerContext.length >= 12) {
      showToast("每轮最多选择 12 个上下文", true);
      return;
    }
    state.composerContext.push(candidate);
  }
  renderComposerContext();
  renderComposerPicker();
}

function renderComposerContext() {
  const container = $("#composer-context-chips");
  if (!container) return;
  container.replaceChildren();
  const workflowPlan = state.active?.workflowPlan;
  if (workflowPlan && ["executing", "blocked"].includes(workflowPlan.status)) {
    appendWorkflowPlanProgressCapsule(container, workflowPlan);
  }
  if (state.active?.planModeEnabled) {
    appendComposerStateChip(container, "计划模式", () => toggleComposerMode("plan"));
  }
  if (state.imageGenerationMode) {
    appendComposerStateChip(container, "图片生成", () => toggleComposerImageGenerationMode(false));
  }
  if (state.goalModeEnabled) {
    appendComposerStateChip(container, state.active?.goal ? "更新目标" : "设置目标", () => {
      state.goalModeEnabled = false;
      renderComposerContext();
      if (!$("#composer-picker").classList.contains("hidden")) renderComposerPicker();
    });
  }
  if (state.active?.goal) {
    appendGoalControlRow(container);
  }
  state.composerContext.forEach((item) => {
    const chip = document.createElement("div");
    chip.className = "composer-context-chip";
    const label = document.createElement("span");
    label.textContent = contextDisplayLabel(item);
    const remove = document.createElement("button");
    remove.type = "button";
    remove.title = "移除";
    remove.textContent = "×";
    remove.addEventListener("click", () => {
      state.composerContext = state.composerContext.filter((value) => composerContextKey(value) !== composerContextKey(item));
      renderComposerContext();
      if (!$("#composer-picker").classList.contains("hidden")) renderComposerPicker();
    });
    chip.append(label, remove);
    container.append(chip);
  });
  state.composerImages.forEach((value) => {
    const chip = document.createElement("div");
    chip.className = "composer-context-chip image-chip";
    const preview = document.createElement("img");
    preview.src = value.previewDataUrl;
    preview.alt = "";
    const label = document.createElement("span");
    label.textContent = value.attachment.fileName || "图片";
    const remove = document.createElement("button");
    remove.type = "button";
    remove.title = "移除图片";
    remove.textContent = "×";
    remove.addEventListener("click", async () => {
      state.composerImages = state.composerImages.filter((candidate) => candidate.attachment.id !== value.attachment.id);
      renderComposerContext();
      try {
        await backend().DiscardChatImage({ imageId: value.attachment.id, cacheFile: value.attachment.cacheFile });
      } catch (error) {
        showToast(errorText(error), true);
      }
    });
    chip.append(preview, label, remove);
    container.append(chip);
  });
  container.classList.toggle("hidden", container.childElementCount === 0);
  const input = $("#message-input");
  const planMode = Boolean(state.active?.planModeEnabled);
  if (planMode && state.goalModeEnabled) input.placeholder = "描述当前目标，先生成计划再推进…";
  else if (planMode) input.placeholder = "描述你要达成的目标，先生成计划…";
  else if (state.goalModeEnabled && state.active?.goal) input.placeholder = "输入新的任务目标，发送后覆盖当前目标…";
  else if (state.goalModeEnabled) input.placeholder = "描述当前目标，后续回复会围绕它推进…";
  else if (state.imageGenerationMode) input.placeholder = "描述想要生成的图片…";
  else input.placeholder = "让 Murong 处理这个项目…";
  const imageButton = $("#composer-image-generate");
  imageButton.classList.toggle("active", state.imageGenerationMode);
  imageButton.setAttribute("aria-pressed", String(state.imageGenerationMode));
  imageButton.disabled = state.running || phoneOwnsActiveSession();
  updateComposerActionState();
}

function toggleComposerImageGenerationMode(force) {
  const next = typeof force === "boolean" ? force : !state.imageGenerationMode;
  if (next && state.composerImages.length) {
    showToast("图片生成暂不支持编辑已选图片，请先移除图片附件", true);
    return;
  }
  state.imageGenerationMode = next;
  renderComposerContext();
  $("#message-input").focus();
}

function imageGenerationSendProblem() {
  const config = state.config || {};
  const profileId = config.imageGenerationProviderProfileId || config.activeProviderProfileId;
  const profile = (config.providerProfiles || []).find((item) => item.id === profileId);
  if (!profile || profile.providerId !== "openai-compatible") return "请在设置中为图片生成选择 OpenAI-compatible 连接";
  const endpoint = String(config.imageGenerationCustomBaseUrl || profile.baseUrl || "");
  if (!config.imageGenerationHasApiKey && !profile.hasApiKey && !endpoint.startsWith("http://127.0.0.1")) {
    return "图片生成连接尚未配置 API Key";
  }
  return "";
}

function appendWorkflowPlanProgressCapsule(container, plan) {
  const total = plan.steps?.length || 0;
  const current = Math.max(0, Math.min(Number(plan.currentStepIndex) || 0, total));
  const capsule = document.createElement("div");
  capsule.className = `workflow-progress-capsule ${plan.status}`;
  capsule.tabIndex = 0;
  capsule.setAttribute("role", "button");
  capsule.setAttribute("aria-label", "查看完整计划进度");
  const dot = document.createElement("span");
  dot.className = `workflow-progress-dot ${plan.status}`;
  const label = document.createElement("span");
  const visibleStep = total === 0 ? 0 : Math.min(current + 1, total);
  label.textContent = `第 ${visibleStep} / ${total} 步`;
  const hover = document.createElement("div");
  hover.className = "workflow-plan-hover";
  const strong = document.createElement("strong");
  strong.textContent = plan.summary || "任务进度";
  const list = document.createElement("ol");
  list.className = "workflow-step-list";
  (plan.steps || []).forEach((step, index) => {
    const item = document.createElement("li");
    const stepState = index < current
      ? "completed"
      : index === current && plan.status === "blocked"
        ? "blocked"
        : index === current
          ? "active"
          : "pending";
    item.className = `workflow-step ${stepState}`;
    const marker = document.createElement("span");
    marker.className = "workflow-step-marker";
    marker.setAttribute("aria-hidden", "true");
    const text = document.createElement("span");
    text.textContent = step;
    item.append(marker, text);
    list.append(item);
  });
  hover.append(strong, list);
  if (plan.nextStepHint) {
    const hint = document.createElement("small");
    hint.className = "workflow-plan-hint";
    hint.textContent = plan.nextStepHint;
    hover.append(hint);
  }
  capsule.append(dot, label, hover);
  if (plan.status === "blocked") {
    const resume = document.createElement("button");
    resume.type = "button";
    resume.textContent = "继续";
    resume.disabled = state.running || phoneOwnsActiveSession();
    resume.addEventListener("click", executeWorkflowPlan);
    capsule.append(resume);
  }
  capsule.addEventListener("click", (event) => {
    if (event.target.closest("button")) return;
    capsule.classList.toggle("expanded");
  });
  container.append(capsule);
}

function appendGoalControlRow(container) {
  const goal = state.active?.goal?.trim();
  if (!goal) return;
  const paused = state.active.goalStatus === "paused";
  const row = document.createElement("div");
  row.className = `composer-goal-row ${paused ? "paused" : "active"}`;

  const copy = document.createElement("button");
  copy.type = "button";
  copy.className = "composer-goal-copy";
  copy.title = "展开目标详情";
  const eyebrow = document.createElement("span");
  eyebrow.className = "composer-goal-status";
  eyebrow.textContent = paused ? "已暂停的目标" : "进行中的目标";
  const title = document.createElement("strong");
  title.textContent = goal;
  copy.append(eyebrow, title);

  const actions = document.createElement("div");
  actions.className = "composer-goal-actions";
  const action = (symbol, titleText, handler, danger = false) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `composer-goal-action${danger ? " danger" : ""}`;
    button.title = titleText;
    button.setAttribute("aria-label", titleText);
    button.textContent = symbol;
    button.disabled = state.running || phoneOwnsActiveSession();
    button.addEventListener("click", handler);
    actions.append(button);
  };
  action("✎", "编辑目标", () => {
    const input = $("#message-input");
    input.value = goal;
    state.goalModeEnabled = true;
    renderComposerContext();
    input.focus();
  });
  action(paused ? "▶" : "Ⅱ", paused ? "继续目标" : "暂停目标", async () => {
    if (!state.active?.id || phoneOwnsActiveSession()) return;
    try {
      state.active = await backend().SetSessionGoalStatus({
        sessionId: state.active.id,
        status: paused ? "active" : "paused",
      });
      renderActiveSession();
    } catch (error) {
      showToast(errorText(error), true);
    }
  });
  action("🗑︎", "删除目标", clearActiveSessionGoal, true);
  action("›", "展开目标详情", () => row.classList.toggle("expanded"));

  const detail = document.createElement("div");
  detail.className = "composer-goal-detail";
  detail.textContent = goal;
  copy.addEventListener("click", () => row.classList.toggle("expanded"));
  row.append(copy, actions, detail);
  container.append(row);
}

function appendComposerStateChip(container, text, onRemove, onLabelClick) {
  const chip = document.createElement("div");
  chip.className = "composer-context-chip mode-chip";
  const label = document.createElement("span");
  label.textContent = text;
  if (onLabelClick) {
    label.style.cursor = "pointer";
    label.title = "点击编辑目标";
    label.addEventListener("click", onLabelClick);
  }
  const remove = document.createElement("button");
  remove.type = "button";
  remove.title = "关闭";
  remove.textContent = "×";
  remove.addEventListener("click", onRemove);
  chip.append(label, remove);
  container.append(chip);
}

function appendComposerActionChip(container, text, onClick) {
  const chip = document.createElement("div");
  chip.className = "composer-context-chip mode-chip";
  const label = document.createElement("button");
  label.type = "button";
  label.textContent = text;
  label.style.cssText = "width:auto;height:auto;padding:0;background:transparent;border:0;color:inherit;font:inherit;";
  label.addEventListener("click", onClick);
  chip.append(label);
  container.append(chip);
}

async function clearActiveSessionGoal() {
  if (!state.active?.id || phoneOwnsActiveSession()) return;
  try {
    state.active = await backend().ClearSessionGoal(state.active.id);
    renderActiveSession();
    if (!$("#composer-picker").classList.contains("hidden")) renderComposerPicker();
  } catch (error) {
    showToast(errorText(error), true);
  }
}

function composerContextSelected(item) {
  if (item.kind === "image") return false;
  if (item.kind === "mode") {
    return item.id === "plan" ? Boolean(state.active?.planModeEnabled) : item.id === "goal" ? state.goalModeEnabled : false;
  }
  return state.composerContext.some((value) => composerContextKey(value) === composerContextKey(item));
}

function composerContextKey(item) {
  return `${item.kind || ""}\u0000${item.scope || ""}\u0000${item.id || ""}\u0000${String(item.path || "").toLowerCase()}`;
}

function contextDisplayLabel(item) {
  if (item.kind === "file" || item.kind === "folder") return `@${item.path || item.label}`;
  if (item.kind === "skill") return `Skill · ${item.label}`;
  if (item.kind === "subagent") return `子代理 · ${item.label}`;
  if (item.kind === "mcp") return `MCP · ${item.label}`;
  return item.label || item.id || item.kind;
}

function contextKindIcon(kind) {
  return ({ file: "@", folder: "▰", image: "▧", skill: "S", subagent: "◇", mcp: "M", mode: "◎" })[kind] || "+";
}

function contextKindLabel(kind) {
  return ({ file: "文件", folder: "文件夹", image: "图片", skill: "Skill", subagent: "子代理", mcp: "MCP", mode: "模式" })[kind] || "上下文";
}

function projectName(path) {
  const parts = String(path || "").replace(/[\\/]+$/, "").split(/[\\/]/);
  return parts[parts.length - 1] || path || "未命名项目";
}

function activeProjectPath() {
  return state.active?.projectPath || state.config?.projectPath || "";
}

function parentProjectPath(path) {
  const value = String(path || "").replace(/[\\/]+$/, "");
  const index = Math.max(value.lastIndexOf("\\"), value.lastIndexOf("/"));
  return index > 2 ? value.slice(0, index) : value;
}

function sameProjectPath(left, right) {
	const normalize = (value) => String(value || "").replace(/[\\/]+$/, "");
	const normalizedLeft = normalize(left);
	const normalizedRight = normalize(right);
	return state.platform?.os === "windows"
		? normalizedLeft.toLowerCase() === normalizedRight.toLowerCase()
		: normalizedLeft === normalizedRight;
}

function formatFileSize(size) {
  const bytes = Number(size) || 0;
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GiB`;
}

function truncateText(value, limit) {
  const characters = Array.from(String(value || "").trim());
  return characters.length > limit ? `${characters.slice(0, limit).join("")}…` : characters.join("");
}

async function saveSettings() {
  const status = $("#settings-status");
  status.textContent = "正在保存…";
  try {
    commitProviderProfileForm();
    commitToolPolicyForm();
    const savedConfig = await backend().SaveSettings({
      projectPath: $("#project-path").value,
      baseUrl: $("#base-url").value,
      model: $("#model-name").value,
      activeProviderProfileId: state.config.activeProviderProfileId,
      providerProfiles: state.config.providerProfiles.map((profile) => ({
        id: profile.id,
        providerId: profile.providerId,
        name: profile.name,
        baseUrl: profile.baseUrl,
        model: profile.model,
        reasoningEffort: profile.reasoningEffort || "",
        apiMode: profile.apiMode || "auto",
        contextWindowTokens: Number(profile.contextWindowTokens) || 0,
        executablePath: profile.executablePath || "",
        apiKey: profile._apiKey || "",
        clearApiKey: Boolean(profile._clearApiKey),
      })),
      approvalMode: state.config.approvalMode,
      allowlist: state.config.allowlist || [],
      enabledBuiltinTools: state.config.enabledBuiltinTools || [],
      enabledFileOperations: state.config.enabledFileOperations || [],
      maxToolIterations: Number($("#max-iterations").value),
      systemPrompt: $("#system-prompt").value,
      responseVerbosity: $("#response-verbosity").value,
      temperature: Number($("#temperature").value),
      maxTokens: Number($("#max-tokens").value),
      enableMultimodalMessages: $("#enable-multimodal-messages").checked,
      guiInferenceMode: $("#gui-inference-mode").value,
      guiLocalBaseUrl: $("#gui-local-base-url").value.trim(),
      guiLocalModel: $("#gui-local-model").value.trim(),
      guiAllowRemoteSemanticTree: $("#gui-allow-remote-semantic").checked,
      guiAllowRemoteScreenshots: $("#gui-allow-remote-screenshots").checked,
      guiAllowRemoteFullScreen: $("#gui-allow-remote-fullscreen").checked,
      visionRoutingEnabled: $("#vision-routing-enabled").checked,
      visionProviderProfileId: $("#vision-provider-profile").value,
      visionModel: $("#vision-model").value.trim(),
      visionCustomBaseUrl: $("#vision-custom-base-url").value.trim(),
      visionApiKey: $("#vision-api-key").value,
      clearVisionApiKey: $("#clear-vision-api-key").checked,
      imageGenerationProviderProfileId: $("#image-generation-provider-profile").value,
      imageGenerationModel: $("#image-generation-model").value.trim(),
      imageGenerationCustomBaseUrl: $("#image-generation-custom-base-url").value.trim(),
      imageGenerationApiKey: $("#image-generation-api-key").value,
      clearImageGenerationApiKey: $("#clear-image-generation-api-key").checked,
      imageGenerationSize: $("#image-generation-size").value,
      imageGenerationQuality: $("#image-generation-quality").value,
      imageGenerationFormat: $("#image-generation-format").value,
      imageGenerationCompression: Number($("#image-generation-compression").value),
      imageGenerationPartialImages: Number($("#image-generation-partial-images").value),
      imageUpscaleBaseUrl: $("#image-upscale-base-url").value.trim(),
      imageUpscaleModel: $("#image-upscale-model").value.trim(),
      imageUpscaleApiKey: $("#image-upscale-api-key").value,
      clearImageUpscaleApiKey: $("#clear-image-upscale-api-key").checked,
      imageUpscaleScale: Number($("#image-upscale-scale").value),
      plannerProfileEnabled: $("#planner-profile-enabled").checked,
      plannerModel: $("#planner-model").value.trim(),
      plannerReasoningEffort: $("#planner-reasoning-effort").value,
      subagentDefaultProfileEnabled: $("#subagent-profile-enabled").checked,
      subagentDefaultModel: $("#subagent-default-model").value.trim(),
      subagentDefaultReasoningEffort: $("#subagent-default-reasoning-effort").value,
      saveProjectToolPreferences: Boolean(state.projectTools?.hasProject),
      useGlobalProjectTools: Boolean(state.projectTools?.usesGlobal),
      projectTools: state.projectTools?.preferences || {},
    });
    await applyProjectConfiguration(savedConfig, false);
    if (!state.projectKnowledge.hasProject && state.knowledgeScope === "project") state.knowledgeScope = "global";
    $("#api-key").value = "";
    $("#clear-api-key").checked = false;
    $("#vision-api-key").value = "";
    $("#clear-vision-api-key").checked = false;
    $("#image-generation-api-key").value = "";
    $("#clear-image-generation-api-key").checked = false;
    $("#image-upscale-api-key").value = "";
    $("#clear-image-upscale-api-key").checked = false;
    status.textContent = "已保存";
    renderSettings();
    renderHeaderMeta();
  } catch (error) {
    status.textContent = "";
    showToast(errorText(error), true);
  }
}

function renderSettings() {
  if (!state.config) return;
  $("#project-path").value = state.config.projectPath || "";
  $("#terminal-dock-setting").value = state.workbench.terminalDock;
  $("#sidebar-width-setting").value = state.layout.sidebarWidth;
  $("#content-padding-setting").value = state.layout.contentPadding;
  $("#workbench-width-setting").value = state.workbench.width;
  const defaultTerminal = $("#default-terminal-setting");
  defaultTerminal.replaceChildren();
  state.terminals.forEach((terminal) => {
    const option = document.createElement("option");
    option.value = terminal.id;
    option.textContent = terminal.label;
    defaultTerminal.append(option);
  });
  if (!state.terminals.some((terminal) => terminal.id === state.workbench.defaultTerminalId)) {
    state.workbench.defaultTerminalId = state.terminals[0]?.id || "";
    persistWorkbenchPreferences();
  }
  defaultTerminal.value = state.workbench.defaultTerminalId;
  defaultTerminal.disabled = !state.terminals.length;
  ensureProviderProfiles();
  renderProviderProfileEditor();
  renderMediaModelSettings();
  $("#max-iterations").value = state.config.maxToolIterations || 999;
  renderToolPolicyEditor();
  $("#system-prompt").value = state.config.systemPrompt || "";
  $("#response-verbosity").value = state.config.responseVerbosity || "BALANCED";
  $("#temperature").value = Number.isFinite(state.config.temperature) ? state.config.temperature : 0.7;
  $("#max-tokens").value = Number.isInteger(state.config.maxTokens) ? state.config.maxTokens : 8192;
  $("#enable-multimodal-messages").checked = state.config.enableMultimodalMessages !== false;
  $("#gui-inference-mode").value = state.config.guiInferenceMode || "local_first";
  $("#gui-local-base-url").value = state.config.guiLocalBaseUrl || "";
  $("#gui-local-model").value = state.config.guiLocalModel || "";
  $("#gui-allow-remote-semantic").checked = Boolean(state.config.guiAllowRemoteSemanticTree);
  $("#gui-allow-remote-screenshots").checked = Boolean(state.config.guiAllowRemoteScreenshots);
  $("#gui-allow-remote-fullscreen").checked = Boolean(state.config.guiAllowRemoteFullScreen);
  $("#gui-allow-remote-fullscreen").disabled = !$("#gui-allow-remote-screenshots").checked;
  renderBuiltinVisionModels();
  $("#planner-profile-enabled").checked = Boolean(state.config.plannerProfileEnabled);
  $("#planner-model").value = state.config.plannerModel || "";
  $("#planner-reasoning-effort").value = normalizedExecutionReasoning(state.config.plannerReasoningEffort);
  $("#subagent-profile-enabled").checked = Boolean(state.config.subagentDefaultProfileEnabled);
  $("#subagent-default-model").value = state.config.subagentDefaultModel || "";
  $("#subagent-default-reasoning-effort").value = normalizedExecutionReasoning(state.config.subagentDefaultReasoningEffort);
  renderExecutionProfileControls();
  const list = $("#terminal-list");
  list.replaceChildren();
  state.terminals.forEach((terminal) => {
    const card = document.createElement("div");
    card.className = "terminal-card";
    const name = document.createElement("strong");
    name.textContent = terminal.label;
    const detail = document.createElement("span");
    detail.textContent = `${terminal.id} · ${terminal.version || "已检测"}`;
    card.append(name, detail);
    list.append(card);
  });
  updateAllowlistVisibility();
  renderBackup();
  renderDesktopVersion();
  renderStatusBar();
}

function renderMediaModelSettings() {
  const config = state.config || {};
  const profiles = config.providerProfiles || [];
  const fillProfiles = (selector, selectedId, onlyOpenAI = false) => {
    const select = $(selector);
    const choices = onlyOpenAI ? profiles.filter((profile) => profile.providerId === "openai-compatible") : profiles;
    select.replaceChildren();
    choices.forEach((profile) => {
      const option = document.createElement("option");
      option.value = profile.id;
      option.textContent = `${profile.name || providerLabel(profile.providerId)} · ${providerProfileModelLabel(profile)}`;
      select.append(option);
    });
    const fallback = choices.find((profile) => profile.id === config.activeProviderProfileId)?.id || choices[0]?.id || "";
    select.value = choices.some((profile) => profile.id === selectedId) ? selectedId : fallback;
  };
  fillProfiles("#vision-provider-profile", config.visionProviderProfileId || config.activeProviderProfileId);
  fillProfiles("#image-generation-provider-profile", config.imageGenerationProviderProfileId || config.activeProviderProfileId, true);
  $("#vision-routing-enabled").checked = Boolean(config.visionRoutingEnabled);
  $("#vision-model").value = config.visionModel || "";
  $("#vision-custom-base-url").value = config.visionCustomBaseUrl || "";
  $("#vision-api-key-state").textContent = config.visionHasApiKey ? "已保存" : "";
  $("#image-generation-model").value = config.imageGenerationModel || "gpt-image-2";
  $("#image-generation-custom-base-url").value = config.imageGenerationCustomBaseUrl || "";
  $("#image-generation-api-key-state").textContent = config.imageGenerationHasApiKey ? "已保存" : "";
  $("#image-generation-size").value = config.imageGenerationSize || "1024x1024";
  $("#image-generation-quality").value = config.imageGenerationQuality || "auto";
  $("#image-generation-format").value = config.imageGenerationFormat || "png";
  $("#image-generation-compression").value = Number.isInteger(config.imageGenerationCompression) ? config.imageGenerationCompression : 90;
  $("#image-generation-partial-images").value = String(config.imageGenerationPartialImages || 2);
  $("#image-upscale-base-url").value = config.imageUpscaleBaseUrl || "https://api.replicate.com/v1";
  $("#image-upscale-model").value = config.imageUpscaleModel || "nightmareai/real-esrgan";
  $("#image-upscale-api-key-state").textContent = config.imageUpscaleHasApiKey ? "已保存" : "";
  $("#image-upscale-scale").value = String(config.imageUpscaleScale || 4);
}

function renderDesktopVersion() {
  const currentVersion = state.desktopUpdate.currentVersion || state.platform.version || "-";
  $("#desktop-current-version").textContent = currentVersion;
  $("#desktop-platform-version").textContent = `${state.platform.label || state.platform.os || "电脑端"} · ${state.platform.architecture || "未知架构"}`;
  $("#desktop-package-kind").textContent = state.platform.packageKind || "未知";
  $("#desktop-version-summary").textContent = `版本 ${currentVersion}`;
  const checkButton = $("#check-desktop-update");
  checkButton.disabled = state.desktopUpdate.checking;
  checkButton.textContent = state.desktopUpdate.checking ? "正在检查…" : "检查更新";
  const downloadButton = $("#download-desktop-update");
  downloadButton.classList.toggle("hidden", !state.desktopUpdate.updateAvailable);
  const status = $("#desktop-update-status");
  status.classList.toggle("error", Boolean(state.desktopUpdate.error));
  status.classList.toggle("available", Boolean(state.desktopUpdate.updateAvailable));
  if (state.desktopUpdate.checking) {
    status.textContent = "正在连接 GitHub 检查最新正式版…";
  } else if (state.desktopUpdate.error) {
    status.textContent = `暂时无法检查更新：${state.desktopUpdate.error}`;
  } else if (state.desktopUpdate.updateAvailable) {
    status.textContent = `发现新版本 ${state.desktopUpdate.latestVersion}，已匹配 ${state.desktopUpdate.packageName || "当前平台安装包"}。`;
  } else if (state.desktopUpdate.checked) {
    status.textContent = `当前已经是最新版本（${currentVersion}）。`;
  } else {
    status.textContent = "启动后会自动检查 GitHub 最新正式版。";
  }
}

async function checkDesktopUpdate(showResult) {
  if (state.desktopUpdate.checking) return;
  state.desktopUpdate.checking = true;
  state.desktopUpdate.error = "";
  renderDesktopVersion();
  try {
    const result = await backend().CheckDesktopUpdate();
    state.desktopUpdate = { ...state.desktopUpdate, ...result, checking: false, checked: true, error: "" };
    if (result.updateAvailable) {
      $("#desktop-update-banner-title").textContent = `发现 Murong 电脑版 ${result.latestVersion}`;
      $("#desktop-update-banner-detail").textContent = `当前版本 ${result.currentVersion}，可下载 ${result.packageName || "适用于当前平台的安装包"}。`;
      $("#desktop-update-banner").classList.remove("hidden");
    } else if (showResult) {
      showToast(`当前已经是最新版本（${result.currentVersion || state.platform.version}）`);
    }
  } catch (error) {
    state.desktopUpdate.checking = false;
    state.desktopUpdate.checked = true;
    state.desktopUpdate.error = errorText(error);
    if (showResult) showToast(`检查更新失败：${state.desktopUpdate.error}`, true);
  }
  renderDesktopVersion();
}

async function openDesktopUpdateDownload() {
  try {
    await backend().OpenDesktopUpdateDownload();
  } catch (error) {
    showToast(`无法打开更新：${errorText(error)}`, true);
  }
}

async function refreshBuiltinVisionStatus() {
  const action = $("#refresh-builtin-vision-models");
  if (!backend()?.GetBuiltinVisionModelStatus) return;
  if (action) action.disabled = true;
  try {
    applyBuiltinVisionStatus(await backend().GetBuiltinVisionModelStatus());
  } catch (error) {
    state.builtinVision.error = errorText(error);
    renderBuiltinVisionModels();
  } finally {
    if (action) action.disabled = false;
  }
}

function applyBuiltinVisionStatus(status) {
  state.builtinVision = {
    models: status?.models || [],
    installingTier: status?.installingTier || "",
    downloadedBytes: Number(status?.downloadedBytes) || 0,
    totalBytes: Number(status?.totalBytes) || 0,
    message: status?.message || "",
    error: status?.error || "",
    deviceRecommendation: status?.deviceRecommendation || "",
  };
  renderBuiltinVisionModels();
  renderComposerModelSelect();
  if (state.config && activeProviderProfile()?.providerId === "murong-local") {
    populateReasoningSelect($("#reasoning-effort"), activeProviderProfile(), "由模型决定");
    updateProviderAPIModeState();
  }
  scheduleBuiltinVisionPoll();
}

function scheduleBuiltinVisionPoll() {
  if (builtinVisionPollTimer) {
    clearTimeout(builtinVisionPollTimer);
    builtinVisionPollTimer = 0;
  }
  if (!state.builtinVision.installingTier) return;
  builtinVisionPollTimer = window.setTimeout(async () => {
    builtinVisionPollTimer = 0;
    await refreshBuiltinVisionStatus();
  }, 1000);
}

function renderBuiltinVisionModels() {
  const list = $("#builtin-vision-model-list");
  if (!list) return;
  const status = state.builtinVision || {};
  $("#builtin-vision-recommendation").textContent =
    status.deviceRecommendation || "可按内存选择 Qwen 或 Gemma；安装后可完全离线运行。";
  list.replaceChildren();
  (status.models || []).forEach((model) => {
    const card = document.createElement("article");
    card.className = `builtin-vision-model${model.active ? " active" : ""}`;

    const header = document.createElement("header");
    const title = document.createElement("strong");
    title.textContent = model.displayName;
    const engine = document.createElement("span");
    engine.className = "builtin-vision-model-badge";
    engine.textContent = model.engine === "litert" ? "LiteRT-LM" : "MNN";
    header.append(title, engine);
    const capability = document.createElement("span");
    capability.className = "builtin-vision-model-badge";
    capability.textContent = model.supportsVision ? "视觉 + 代码" : "纯文本代码";
    header.append(capability);
    if (model.reasoningModes?.length) {
      const thinking = document.createElement("span");
      thinking.className = "builtin-vision-model-badge";
      thinking.textContent = "可切换思考";
      header.append(thinking);
    }
    if (model.active) {
      const active = document.createElement("span");
      active.className = "builtin-vision-model-badge";
      active.textContent = "使用中";
      header.append(active);
    }

    const description = document.createElement("p");
    description.textContent = model.recommendation || "";
    const footer = document.createElement("footer");
    const size = document.createElement("span");
    size.textContent = model.available === false
      ? (model.unavailableReason || "当前平台不可用")
      : model.installed
        ? `约 ${formatFileSize(model.sizeBytes)} · 已安装${model.active ? "（当前）" : ""}`
        : `约 ${formatFileSize(model.sizeBytes)} · 未安装`;
    footer.append(size);

    if (!model.installed) {
      const install = document.createElement("button");
      install.type = "button";
      install.className = "primary";
      install.dataset.visionAction = "install";
      install.dataset.visionTier = model.tier;
      install.textContent = status.installingTier === model.tier ? "下载中" : "安装";
      install.disabled = Boolean(status.installingTier) || model.available === false;
      footer.append(install);
    } else {
      if (!model.active) {
        const select = document.createElement("button");
        select.type = "button";
        select.className = "primary";
        select.dataset.visionAction = "select";
        select.dataset.visionTier = model.tier;
        select.textContent = "使用";
        select.disabled = Boolean(status.installingTier);
        footer.append(select);
      }
      const remove = document.createElement("button");
      remove.type = "button";
      remove.className = "ghost danger";
      remove.dataset.visionAction = "delete";
      remove.dataset.visionTier = model.tier;
      remove.dataset.visionName = model.displayName;
      remove.textContent = "删除";
      remove.disabled = Boolean(status.installingTier);
      footer.append(remove);
    }
    card.append(header, description, footer);
    list.append(card);
  });

  const progress = $("#builtin-vision-progress");
  progress.classList.toggle("hidden", !status.installingTier);
  if (status.installingTier) {
    const total = Math.max(1, Number(status.totalBytes) || 1);
    const downloaded = Math.min(total, Number(status.downloadedBytes) || 0);
    $("#builtin-vision-status").textContent = status.message || "正在下载模型…";
    $("#builtin-vision-progress-label").textContent = `${formatFileSize(downloaded)} / ${formatFileSize(total)}`;
    $("#builtin-vision-progress-bar").max = total;
    $("#builtin-vision-progress-bar").value = downloaded;
  }
  if (status.error) {
    $("#builtin-vision-recommendation").textContent = `模型安装失败：${status.error}`;
  } else if (status.message && !status.installingTier) {
    $("#builtin-vision-recommendation").textContent =
      `${status.deviceRecommendation || ""}${status.deviceRecommendation ? " · " : ""}${status.message}`;
  }
}

async function handleBuiltinVisionModelAction(event) {
  const button = event.target.closest("[data-vision-action]");
  if (!button || button.disabled) return;
  const tier = button.dataset.visionTier || "";
  const action = button.dataset.visionAction;
  if (action === "delete" && !window.confirm(`确定删除 ${button.dataset.visionName || "这个模型"}？之后可重新下载。`)) return;
  button.disabled = true;
  try {
    if (action === "install") await backend().StartBuiltinVisionModelInstall(tier);
    else if (action === "select") await backend().SelectBuiltinVisionModel(tier);
    else if (action === "delete") await backend().DeleteBuiltinVisionModel(tier);
    await refreshBuiltinVisionStatus();
  } catch (error) {
    showToast(errorText(error), true);
    await refreshBuiltinVisionStatus();
  }
}

async function cancelBuiltinVisionInstall() {
  const button = $("#cancel-builtin-vision-install");
  button.disabled = true;
  try {
    await backend().CancelBuiltinVisionModelInstall();
    window.setTimeout(refreshBuiltinVisionStatus, 120);
  } catch (error) {
    showToast(errorText(error), true);
  } finally {
    button.disabled = false;
  }
}

function normalizedExecutionReasoning(value) {
  return ["", "low", "medium", "high", "xhigh", "max"].includes(value || "") ? value || "" : "";
}

function renderExecutionProfileControls() {
  if (!state.config) return;
  const active = state.config.providerProfiles?.find((item) => item.id === state.config.activeProviderProfileId) || {};
  const inheritedModel = active.model || "模型自动决定";
  const inheritedReasoning = active.reasoningEffort || "模型默认";
  const definitions = [
    { prefix: "planner", enabled: "#planner-profile-enabled", model: "#planner-model", reasoning: "#planner-reasoning-effort", card: "#planner-profile-card", summary: "#planner-profile-summary" },
    { prefix: "subagent", enabled: "#subagent-profile-enabled", model: "#subagent-default-model", reasoning: "#subagent-default-reasoning-effort", card: "#subagent-profile-card", summary: "#subagent-profile-summary" },
  ];
  definitions.forEach((item) => {
    const enabled = $(item.enabled).checked;
    $(item.model).disabled = !enabled;
    $(item.reasoning).disabled = !enabled;
    $(item.card).classList.toggle("active", enabled);
    const model = enabled && $(item.model).value.trim() ? $(item.model).value.trim() : inheritedModel;
    const effort = enabled && $(item.reasoning).value ? $(item.reasoning).value : inheritedReasoning;
    $(item.summary).textContent = enabled ? `实际默认：${model} · 推理 ${effort}` : `继承主聊天：${inheritedModel} · 推理 ${inheritedReasoning}`;
  });
}

function renderBackup() {
  const backup = state.backup || {};
  const settings = backup.settings || { dailyBackupEnabled: false, maxBackupCount: 7 };
  $("#backup-daily-enabled").checked = Boolean(settings.dailyBackupEnabled);
  $("#backup-max-count").value = Number(settings.maxBackupCount) || 7;
  $("#backup-summary-badge").textContent = settings.dailyBackupEnabled ? "每日备份已开启" : "手动备份";
  $("#backup-auto-count").textContent = String(backup.automaticBackupCount || 0);
  $("#backup-snapshot-count").textContent = String(backup.preRestoreSnapshotCount || 0);
  $("#backup-last-time").textContent = backup.lastBackupAt ? formatBackupTime(backup.lastBackupAt) : "尚未备份";
  $("#backup-last-result").textContent = backup.lastBackupMessage || "等待首次备份";
  $("#backup-last-result").title = backup.lastBackupMessage || "";
  $("#backup-schedule-detail").textContent = backup.scheduleDescription || "应用打开期间每天 03:00 后执行一次。";
  $("#backup-storage-location").textContent = backup.storageLocation || "";
  $("#backup-storage-location").title = backup.storageLocation || "";
  const operation = $("#backup-operation-status");
  if (!operation.dataset.busy) {
    operation.textContent = backup.lastBackupMessage || "";
    operation.classList.toggle("error", Boolean(backup.lastBackupFailed));
  }
}

async function saveBackupSettings() {
  const button = $("#save-backup-settings");
  const operation = $("#backup-operation-status");
  button.disabled = true;
  operation.dataset.busy = "true";
  operation.textContent = "正在保存备份设置…";
  operation.classList.remove("error");
  try {
    state.backup = await backend().UpdateBackupSettings({
      dailyBackupEnabled: $("#backup-daily-enabled").checked,
      maxBackupCount: Number($("#backup-max-count").value) || 7,
    });
    operation.textContent = "备份设置已保存";
    showToast("备份设置已保存");
  } catch (error) {
    operation.textContent = errorText(error);
    operation.classList.add("error");
    showToast(errorText(error), true);
  } finally {
    button.disabled = false;
    renderBackup();
    delete operation.dataset.busy;
  }
}

async function createManualBackup() {
  const button = $("#create-manual-backup");
  const operation = $("#backup-operation-status");
  button.disabled = true;
  operation.dataset.busy = "true";
  operation.textContent = "请选择保存位置…";
  operation.classList.remove("error");
  try {
    const result = await backend().CreateManualBackup();
    state.backup = result.status || state.backup;
    operation.textContent = result.message || "手动备份完成";
    if (!result.skipped) showToast("完整备份已创建");
  } catch (error) {
    operation.textContent = errorText(error);
    operation.classList.add("error");
    showToast(errorText(error), true);
  } finally {
    button.disabled = false;
    renderBackup();
    delete operation.dataset.busy;
  }
}

async function selectBackupForRestore() {
  const button = $("#select-backup-restore");
  button.disabled = true;
  try {
    const selection = await backend().SelectBackupForRestore();
    if (!selection?.path) return;
    state.pendingBackupRestore = selection;
    $("#backup-restore-file-name").textContent = selection.fileName || "Murong 备份包";
    $("#backup-restore-file-size").textContent = `${formatFileSize(selection.sizeBytes)} · ${selection.path}`;
    $("#backup-restore-file-size").title = selection.path;
    $("#backup-restore-confirm-text").value = "";
    $("#backup-restore-status").textContent = "";
    updateBackupRestoreConfirmation();
    $("#backup-restore-modal").classList.remove("hidden");
    requestAnimationFrame(() => $("#backup-restore-confirm-text").focus());
  } catch (error) {
    showToast(errorText(error), true);
  } finally {
    button.disabled = false;
  }
}

function updateBackupRestoreConfirmation() {
  const confirmed = $("#backup-restore-confirm-text").value.trim() === "恢复";
  $("#confirm-backup-restore").disabled = !confirmed || !state.pendingBackupRestore;
}

function closeBackupRestore() {
  if ($("#confirm-backup-restore").dataset.busy) return;
  state.pendingBackupRestore = null;
  $("#backup-restore-modal").classList.add("hidden");
  $("#backup-restore-confirm-text").value = "";
  $("#backup-restore-status").textContent = "";
  updateBackupRestoreConfirmation();
}

async function confirmBackupRestore() {
  const selection = state.pendingBackupRestore;
  if (!selection || $("#backup-restore-confirm-text").value.trim() !== "恢复") return;
  const button = $("#confirm-backup-restore");
  const status = $("#backup-restore-status");
  button.disabled = true;
  button.dataset.busy = "true";
  status.textContent = "正在校验备份并创建恢复前快照…";
  try {
    const result = await backend().RestoreBackup({ path: selection.path, confirmed: true });
    const bootstrap = await backend().Bootstrap();
    applyBootstrap(bootstrap);
    await refreshComposerCatalog();
    delete button.dataset.busy;
    closeBackupRestore();
    showToast(result.message || "完整备份已恢复");
  } catch (error) {
    status.textContent = errorText(error);
    showToast(errorText(error), true);
  } finally {
    delete button.dataset.busy;
    updateBackupRestoreConfirmation();
  }
}

function formatBackupTime(timestamp) {
  if (!timestamp) return "尚未备份";
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric", month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit",
  }).format(new Date(timestamp));
}

const defaultBuiltinTools = ["shell", "file", "gui", "code_edit", "code_search", "web_fetch", "web_search", "subagent_launch", "explore", "research", "review", "security_review", "github", "mcp"];
const defaultFileOperations = ["read", "list", "exists", "write", "delete", "chmod"];

function ensureToolPreferences() {
  if (!Array.isArray(state.config.enabledBuiltinTools)) state.config.enabledBuiltinTools = [...defaultBuiltinTools];
  if (!Array.isArray(state.config.enabledFileOperations)) state.config.enabledFileOperations = [...defaultFileOperations];
  if (!state.projectTools) state.projectTools = { projectPath: "", projectLabel: "", hasProject: false, usesGlobal: true, preferences: null };
  if (!state.projectTools.preferences) {
    state.projectTools.preferences = {
      approvalMode: state.config.approvalMode || "ask",
      allowlist: [...(state.config.allowlist || [])],
      enabledBuiltinTools: [...state.config.enabledBuiltinTools],
      enabledFileOperations: [...state.config.enabledFileOperations],
      subagentTemplates: [],
    };
  }
  if (!Array.isArray(state.projectTools.preferences.subagentTemplates)) state.projectTools.preferences.subagentTemplates = [];
}

function collectToolPolicyForm() {
  return {
    approvalMode: $("#approval-mode").value,
    allowlist: $("#allowlist").value.split(/\r?\n/).map((value) => value.trim()).filter(Boolean),
    enabledBuiltinTools: [...document.querySelectorAll("[data-builtin-tool]:checked")].map((input) => input.dataset.builtinTool),
    enabledFileOperations: [...document.querySelectorAll("[data-file-operation]:checked")].map((input) => input.dataset.fileOperation),
  };
}

function commitToolPolicyForm() {
  if (!state.config || !$("#approval-mode")) return;
  ensureToolPreferences();
  const preferences = collectToolPolicyForm();
  if (state.toolPolicyScope === "project" && state.projectTools.hasProject) {
    state.projectTools.usesGlobal = $("#project-tools-use-global").checked;
    preferences.subagentTemplates = collectProjectSubagentTemplates();
    if (!state.projectTools.usesGlobal) state.projectTools.preferences = preferences;
    else state.projectTools.preferences.subagentTemplates = preferences.subagentTemplates;
  } else {
    state.config.approvalMode = preferences.approvalMode;
    state.config.allowlist = preferences.allowlist;
    state.config.enabledBuiltinTools = preferences.enabledBuiltinTools;
    state.config.enabledFileOperations = preferences.enabledFileOperations;
  }
}

function switchToolPolicyScope(scope) {
  if (scope === state.toolPolicyScope) return;
  if (scope === "project" && !state.projectTools?.hasProject) {
    showToast("请先选择并保存一个项目", true);
    return;
  }
  commitToolPolicyForm();
  state.toolPolicyScope = scope;
  renderToolPolicyEditor();
}

function renderToolPolicyEditor() {
  if (!state.config || !$("#approval-mode")) return;
  ensureToolPreferences();
  if (state.toolPolicyScope === "project" && !state.projectTools.hasProject) state.toolPolicyScope = "global";
  document.querySelectorAll("[data-tool-scope]").forEach((button) => button.classList.toggle("active", button.dataset.toolScope === state.toolPolicyScope));
  const editingProject = state.toolPolicyScope === "project";
  $("#project-tools-inherit-row").classList.toggle("hidden", !editingProject);
  $("#max-iterations-row").classList.toggle("hidden", editingProject);
  $("#project-tools-use-global").checked = Boolean(state.projectTools.usesGlobal);
  const inherited = editingProject && state.projectTools.usesGlobal;
  $("#tool-policy-fields").classList.toggle("inherited", inherited);
  $("#project-subagent-template-panel").classList.toggle("hidden", !editingProject);
  const preferences = editingProject
    ? inherited ? {
        approvalMode: state.config.approvalMode,
        allowlist: state.config.allowlist,
        enabledBuiltinTools: state.config.enabledBuiltinTools,
        enabledFileOperations: state.config.enabledFileOperations,
      } : state.projectTools.preferences
    : {
        approvalMode: state.config.approvalMode,
        allowlist: state.config.allowlist,
        enabledBuiltinTools: state.config.enabledBuiltinTools,
        enabledFileOperations: state.config.enabledFileOperations,
      };
  $("#approval-mode").value = preferences.approvalMode || "ask";
  $("#allowlist").value = (preferences.allowlist || []).join("\n");
  const builtins = new Set(preferences.enabledBuiltinTools || []);
  document.querySelectorAll("[data-builtin-tool]").forEach((input) => { input.checked = builtins.has(input.dataset.builtinTool); });
  const fileOperations = new Set(preferences.enabledFileOperations || []);
  document.querySelectorAll("[data-file-operation]").forEach((input) => { input.checked = fileOperations.has(input.dataset.fileOperation); });
  if (editingProject) renderProjectSubagentTemplates();
  updateAllowlistVisibility();
}

function collectProjectSubagentTemplates() {
  const editors = [...document.querySelectorAll("[data-subagent-template-editor]")];
  if (!editors.length) return [...(state.projectTools?.preferences?.subagentTemplates || [])];
  return editors.map((editor) => ({
    id: editor.querySelector("[data-template-field='id']").value.trim(),
    title: editor.querySelector("[data-template-field='title']").value.trim(),
    description: editor.querySelector("[data-template-field='description']").value.trim(),
    goalMatchers: editor.querySelector("[data-template-field='goalMatchers']").value.split(/[\r\n,，]+/).map((value) => value.trim()).filter(Boolean),
    preferredModel: editor.querySelector("[data-template-field='preferredModel']").value.trim(),
    preferredReasoningEffort: editor.querySelector("[data-template-field='preferredReasoningEffort']").value,
    enableWebSearch: editor.querySelector("[data-template-field='enableWebSearch']").checked,
    allowWriteAccess: editor.querySelector("[data-template-field='allowWriteAccess']").checked,
    allowCodeEdits: editor.querySelector("[data-template-field='allowCodeEdits']").checked,
    allowShell: editor.querySelector("[data-template-field='allowShell']").checked,
    enabled: editor.querySelector("[data-template-field='enabled']").checked,
  }));
}

function addProjectSubagentTemplate() {
  ensureToolPreferences();
  const existing = collectProjectSubagentTemplates();
  if (existing.length >= 24) {
    showToast("每个项目最多配置 24 个子代理模板", true);
    return;
  }
  const suffix = `${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;
  existing.push({
    id: `subagent_${suffix}`, title: "新子代理模板", description: "", goalMatchers: [], preferredModel: "",
    preferredReasoningEffort: "", enableWebSearch: true, allowWriteAccess: false, allowCodeEdits: false, allowShell: false, enabled: true,
  });
  state.projectTools.preferences.subagentTemplates = existing;
  renderProjectSubagentTemplates();
  const editors = document.querySelectorAll("[data-subagent-template-editor]");
  editors[editors.length - 1]?.querySelector("[data-template-field='title']")?.select();
}

function renderProjectSubagentTemplates() {
  const list = $("#project-subagent-template-list");
  if (!list) return;
  list.replaceChildren();
  const templates = state.projectTools?.preferences?.subagentTemplates || [];
  if (!templates.length) {
    const empty = document.createElement("div");
    empty.className = "subagent-template-empty";
    empty.textContent = "当前项目还没有自定义子代理模板。";
    list.append(empty);
    return;
  }
  templates.forEach((template, index) => list.append(createProjectSubagentTemplateEditor(template, index)));
}

function createProjectSubagentTemplateEditor(template, index) {
  const editor = document.createElement("article");
  editor.className = "subagent-template-editor";
  editor.dataset.subagentTemplateEditor = String(index);

  const header = document.createElement("div");
  header.className = "subagent-template-editor-header";
  const enabledLabel = document.createElement("label");
  enabledLabel.className = "subagent-template-enabled";
  const enabled = projectSubagentTemplateCheckbox("enabled", template.enabled !== false);
  const enabledText = document.createElement("span");
  enabledText.textContent = template.title || "未命名模板";
  enabledLabel.append(enabled, enabledText);
  const remove = document.createElement("button");
  remove.type = "button";
  remove.className = "danger-text";
  remove.textContent = "删除模板";
  remove.addEventListener("click", () => {
    const values = collectProjectSubagentTemplates();
    values.splice(index, 1);
    state.projectTools.preferences.subagentTemplates = values;
    renderProjectSubagentTemplates();
  });
  header.append(enabledLabel, remove);

  const grid = document.createElement("div");
  grid.className = "subagent-template-grid";
  grid.append(
    projectSubagentTemplateField("模板名称", "title", template.title || "", "input"),
    projectSubagentTemplateField("模板 ID", "id", template.id || "", "input"),
    projectSubagentTemplateField("说明", "description", template.description || "", "textarea", "span-2"),
    projectSubagentTemplateField("目标匹配词", "goalMatchers", (template.goalMatchers || []).join("，"), "textarea"),
    projectSubagentTemplateField("优先模型（留空跟随当前）", "preferredModel", template.preferredModel || "", "input"),
  );
  const effortLabel = document.createElement("label");
  effortLabel.textContent = "优先推理强度";
  const effort = document.createElement("select");
  effort.dataset.templateField = "preferredReasoningEffort";
  [["", "跟随当前"], ["low", "低"], ["medium", "中"], ["high", "高"], ["xhigh", "极高"], ["max", "最大"]].forEach(([value, label]) => {
    const option = document.createElement("option");
    option.value = value; option.textContent = label; effort.append(option);
  });
  effort.value = template.preferredReasoningEffort || "";
  effortLabel.append(effort);
  grid.append(effortLabel);

  const permissions = document.createElement("div");
  permissions.className = "subagent-template-permissions span-2";
  [
    ["enableWebSearch", "网页搜索", template.enableWebSearch !== false],
    ["allowWriteAccess", "文件写入", Boolean(template.allowWriteAccess)],
    ["allowCodeEdits", "代码编辑", Boolean(template.allowCodeEdits)],
    ["allowShell", "终端 / Shell", Boolean(template.allowShell)],
  ].forEach(([field, label, checked]) => {
    const item = document.createElement("label");
    item.append(projectSubagentTemplateCheckbox(field, checked), document.createTextNode(label));
    permissions.append(item);
  });
  grid.append(permissions);
  editor.append(header, grid);
  return editor;
}

function projectSubagentTemplateField(label, field, value, kind, className = "") {
  const wrapper = document.createElement("label");
  wrapper.className = className;
  wrapper.append(document.createTextNode(label));
  const control = document.createElement(kind);
  control.dataset.templateField = field;
  control.value = value;
  if (kind === "textarea") control.rows = field === "description" ? 2 : 3;
  if (field === "goalMatchers") control.placeholder = "逗号或换行分隔，例如：审查，review";
  wrapper.append(control);
  return wrapper;
}

function projectSubagentTemplateCheckbox(field, checked) {
  const input = document.createElement("input");
  input.type = "checkbox";
  input.dataset.templateField = field;
  input.checked = checked;
  return input;
}

const providerDefaults = {
  "openai-compatible": { name: "OpenAI-compatible", baseUrl: "https://api.openai.com/v1", model: "gpt-5.6-sol", apiMode: "auto" },
  deepseek: { name: "DeepSeek", baseUrl: "https://api.deepseek.com/v1", model: "deepseek-v4-flash", apiMode: "chat-completions" },
  claude: { name: "Claude", baseUrl: "https://api.anthropic.com", model: "claude-fable-5", apiMode: "messages" },
  kimi: { name: "Kimi", baseUrl: "https://api.moonshot.cn/v1", model: "kimi-k3", apiMode: "chat-completions" },
  glm: { name: "智谱 GLM", baseUrl: "https://open.bigmodel.cn/api/paas/v4", model: "glm-5.2", apiMode: "chat-completions" },
  qwen: { name: "通义千问 Qwen", baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1", model: "qwen3.8-max-preview", apiMode: "chat-completions" },
  minimax: { name: "MiniMax", baseUrl: "https://api.minimaxi.com/v1", model: "MiniMax-M3", apiMode: "chat-completions" },
  grok: { name: "xAI Grok", baseUrl: "https://api.x.ai/v1", model: "grok-4.5", apiMode: "responses" },
  mimo: { name: "小米 MiMo", baseUrl: "https://api.xiaomimimo.com/v1", model: "mimo-v2.5-pro", apiMode: "responses" },
  hy3: { name: "腾讯混元 Hy3", baseUrl: "https://tokenhub.tencentmaas.com/v1", model: "hy3-preview", apiMode: "chat-completions" },
  gemini: { name: "Google Gemini", baseUrl: "https://generativelanguage.googleapis.com/v1beta", model: "gemini-3.6-flash", apiMode: "gemini" },
  "codex-chatgpt": { name: "Codex / ChatGPT", baseUrl: "", model: "", apiMode: "app-server" },
  "murong-local": { name: "内置本地模型", baseUrl: "", model: "builtin-selected", apiMode: "builtin" },
};

function ensureProviderProfiles() {
  if (Array.isArray(state.config.providerProfiles) && state.config.providerProfiles.length) return;
  state.config.providerProfiles = [{
    id: `provider_${crypto.randomUUID?.() || Date.now()}`,
    providerId: "openai-compatible",
    name: "原桌面连接",
    baseUrl: state.config.baseUrl || providerDefaults["openai-compatible"].baseUrl,
    model: state.config.model || providerDefaults["openai-compatible"].model,
    reasoningEffort: "high",
    apiMode: "auto",
    contextWindowTokens: 0,
    hasApiKey: Boolean(state.config.hasApiKey),
  }];
  state.config.activeProviderProfileId = state.config.providerProfiles[0].id;
}

function activeProviderProfile() {
  ensureProviderProfiles();
  return state.config.providerProfiles.find((profile) => profile.id === state.config.activeProviderProfileId) || state.config.providerProfiles[0];
}

function isLocalModelBaseUrl(value) {
  try {
    const parsed = new URL(String(value || "").includes("://") ? value : `http://${value}`);
    const host = parsed.hostname.replace(/^\[|\]$/g, "").toLowerCase();
    if (host === "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host === "::1") return true;
    if (host.includes(":") && (/^(fc|fd)/.test(host) || host.startsWith("fe80:"))) return true;
    const octets = host.split(".").map(Number);
    if (octets.length !== 4 || octets.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) return false;
    return octets[0] === 10 || octets[0] === 127 ||
      (octets[0] === 169 && octets[1] === 254) ||
      (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) ||
      (octets[0] === 192 && octets[1] === 168);
  } catch (_) {
    return false;
  }
}

function activeProviderSendProblem() {
  const profile = activeProviderProfile();
  if (!profile) return "请先配置模型连接";
  if (profile.providerId === "codex-chatgpt") return "";
  if (profile.providerId === "murong-local") {
    return (state.builtinVision?.models || []).some((model) => model.installed && model.available !== false)
      ? ""
      : "请先在“界面操作”的本地模型中心安装一个 Qwen 或 Gemma 模型";
  }
  if (!String(profile.model || "").trim()) return "当前模型连接尚未选择模型";
  if (!profile.hasApiKey && !isLocalModelBaseUrl(profile.baseUrl)) return "当前模型连接尚未配置 API Key";
  return "";
}

function commitProviderProfileForm() {
  if (!state.config || !$("#provider-profile-select")) return;
  const profile = activeProviderProfile();
  if (!profile) return;
  profile.providerId = $("#provider-kind").value;
  profile.name = $("#provider-name").value.trim();
  const builtinLocal = profile.providerId === "murong-local";
  profile.baseUrl = builtinLocal ? "" : $("#base-url").value.trim();
  profile.model = builtinLocal ? "builtin-selected" : $("#model-name").value.trim();
  profile.reasoningEffort = $("#reasoning-effort").value;
  profile.apiMode = builtinLocal ? "builtin" : profile.providerId === "codex-chatgpt" ? "app-server" : profile.providerId === "claude" ? "messages" : profile.providerId === "gemini" ? "gemini" : ["deepseek", "kimi", "glm", "qwen", "minimax", "hy3"].includes(profile.providerId) ? "chat-completions" : ["grok", "mimo"].includes(profile.providerId) ? "responses" : $("#provider-api-mode").value;
  profile.contextWindowTokens = builtinLocal ? 4096 : Number($("#context-window-tokens").value) || 0;
  profile.executablePath = $("#codex-executable-path").value.trim();
  profile._apiKey = $("#api-key").value;
  profile._clearApiKey = $("#clear-api-key").checked;
}

function renderProviderProfileEditor() {
  const profile = activeProviderProfile();
  state.config.activeProviderProfileId = profile.id;
  const select = $("#provider-profile-select");
  select.replaceChildren();
  state.config.providerProfiles.forEach((item) => {
    const option = document.createElement("option");
    option.value = item.id;
    option.textContent = `${item.name || "未命名连接"} · ${providerProfileModelLabel(item)}`;
    select.append(option);
  });
  select.value = profile.id;
  $("#provider-kind").value = providerDefaults[profile.providerId] ? profile.providerId : "openai-compatible";
  $("#provider-name").value = profile.name || "";
  $("#base-url").value = profile.baseUrl || "";
  $("#model-name").value = profile.providerId === "murong-local"
    ? activeBuiltinVisionModelName()
    : profile.model || "";
  populateReasoningSelect($("#reasoning-effort"), profile, "由供应商决定");
  $("#provider-api-mode").value = ["auto", "responses", "chat-completions"].includes(profile.apiMode) ? profile.apiMode : "auto";
  updateProviderAPIModeState();
  $("#context-window-tokens").value = profile.contextWindowTokens || "";
  $("#api-key").value = profile._apiKey || "";
  $("#clear-api-key").checked = Boolean(profile._clearApiKey);
  $("#codex-executable-path").value = profile.executablePath || "";
  const keyState = profile._clearApiKey ? "保存后清除" : profile._apiKey ? "待加密保存" : profile.hasApiKey ? "已加密保存" : "未配置";
  $("#api-key-state").textContent = keyState;
  $("#provider-profile-state").textContent = `${state.config.providerProfiles.length} 个连接 · 当前协议 ${providerLabel(profile.providerId)}`;
  $("#remove-provider-profile").disabled =
    state.config.providerProfiles.length <= 1 || profile.providerId === "murong-local";
  renderProviderModelCandidates();
  renderCodexProviderStatus();
  renderProviderImportIntegration();
}

// Kept as a non-visual compatibility entry point for older remote clients. The
// current UI intentionally ends goals through the model's complete_goal signal.
async function completeActiveSessionGoalFromModelSignal() {
  if (!state.active?.id || phoneOwnsActiveSession()) return;
  state.active = await backend().CompleteSessionGoal(state.active.id);
  renderActiveSession();
}

async function refreshProviderImportIntegration() {
  try {
    const [protocol, usage, pending] = await Promise.all([
      backend().GetProviderImportProtocolState(),
      backend().GetProviderUsageState(),
      backend().GetPendingProviderImport(),
    ]);
    state.providerImportProtocol = protocol || state.providerImportProtocol;
    state.providerUsage = usage || { items: [] };
    renderProviderImportIntegration();
    if (pending?.requestId) openProviderImportPreview(pending);
  } catch (error) {
    $("#provider-import-protocol-state").textContent = `导入集成检查失败：${errorText(error)}`;
  }
}

function renderProviderImportIntegration() {
  const protocol = state.providerImportProtocol || {};
  const toggle = $("#ccswitch-protocol-compatibility");
  if (!toggle) return;
  toggle.checked = Boolean(protocol.ccSwitchCompatibilityEnabled);
  toggle.disabled = !protocol.supported;
  const handler = protocol.ccSwitchCurrentHandlerLabel || "尚未注册";
  $("#provider-import-protocol-state").textContent = protocol.supported
    ? `Murong 协议${protocol.murongRegistered ? "已注册" : "未注册"} · CC Switch 当前由 ${handler} 处理`
    : "当前系统不支持在应用内切换 URL 协议处理器";

  const profile = activeProviderProfile();
  const usage = (state.providerUsage?.items || []).find((item) => item.providerProfileId === profile?.id);
  const refresh = $("#refresh-provider-usage");
  refresh.disabled = !usage || Boolean(usage.syncing);
  if (!usage) {
    $("#provider-usage-summary").textContent = "当前连接未启用自动余额查询";
    return;
  }
  if (usage.syncing) {
    $("#provider-usage-summary").textContent = "正在查询当前连接余额…";
    return;
  }
  if (usage.lastError) {
    $("#provider-usage-summary").textContent = `余额查询失败：${usage.lastError}`;
    return;
  }
  const remaining = Number(usage.remaining);
  const amount = Number.isFinite(remaining) ? remaining.toLocaleString("zh-CN", { maximumFractionDigits: 4 }) : "待首次同步";
  const synced = usage.lastSyncedAt ? ` · ${formatTime(usage.lastSyncedAt)}` : "";
  $("#provider-usage-summary").textContent = `剩余 ${amount}${usage.unit ? ` ${usage.unit}` : ""}${synced}`;
}

async function updateCCSwitchProtocolCompatibility(event) {
  const toggle = event.currentTarget;
  toggle.disabled = true;
  try {
    state.providerImportProtocol = await backend().SetCCSwitchProtocolCompatibility(Boolean(toggle.checked));
    renderProviderImportIntegration();
    showToast(toggle.checked ? "已由 Murong 接收 CC Switch 导入链接" : "已恢复此前的 CC Switch 协议处理器");
  } catch (error) {
    toggle.checked = !toggle.checked;
    showToast(errorText(error), true);
  } finally {
    toggle.disabled = !state.providerImportProtocol?.supported;
  }
}

function openProviderImportPreview(preview) {
  if (!preview?.requestId) return;
  state.pendingProviderImport = preview;
  $("#provider-import-app").textContent = preview.appLabel || "未知";
  $("#provider-import-name").textContent = preview.name || "未命名连接";
  $("#provider-import-endpoints").textContent = (preview.endpoints || []).join("\n") || "未提供";
  $("#provider-import-model").textContent = preview.model || "由供应商决定";
  $("#provider-import-key").textContent = preview.maskedApiKey || "未提供";
  const notes = $("#provider-import-notes");
  notes.textContent = preview.notes || "";
  notes.classList.toggle("hidden", !preview.notes);
  const scriptDetails = $("#provider-import-script-details");
  scriptDetails.classList.toggle("hidden", !preview.usageScript);
  scriptDetails.open = false;
  $("#provider-import-script").textContent = preview.usageScript || "";
  $("#provider-import-activate").checked = Boolean(preview.requestedActive);
  const usageToggle = $("#provider-import-enable-usage");
  usageToggle.disabled = !preview.usageRule;
  usageToggle.checked = Boolean(preview.requestedUsageEnabled && preview.usageRule);
  $("#provider-import-security").textContent = preview.usageRule
    ? `用量脚本已转换为 ${preview.usageRule.sourceLabel}，只访问 ${preview.usageRule.endpoint}，每 ${preview.usageRule.intervalMinutes} 分钟查询。原 JavaScript 不会执行。`
    : preview.usageScript
      ? "网站提供的 JavaScript 无法安全转换，因此不会执行，也不能启用自动查询。"
      : "本次导入不包含余额自动查询。API Key 仅在确认后交给本机凭据保护存储。";
  $("#provider-import-status").textContent = "";
  $("#provider-import-modal").classList.remove("hidden");
}

async function cancelProviderImport() {
  const requestId = state.pendingProviderImport?.requestId || "";
  state.pendingProviderImport = null;
  $("#provider-import-modal").classList.add("hidden");
  if (!requestId) return;
  try {
    await backend().CancelProviderImport(requestId);
  } catch (_) {}
}

async function confirmProviderImport() {
  const preview = state.pendingProviderImport;
  if (!preview?.requestId || state.providerImportBusy) return;
  state.providerImportBusy = true;
  $("#confirm-provider-import").disabled = true;
  $("#provider-import-status").textContent = "正在加密保存模型连接…";
  try {
    const result = await backend().ConfirmProviderImport({
      requestId: preview.requestId,
      activate: $("#provider-import-activate").checked,
      enableUsage: $("#provider-import-enable-usage").checked,
    });
    state.config = result.config || state.config;
    state.providerUsage = result.usage || state.providerUsage;
    state.pendingProviderImport = null;
    $("#provider-import-modal").classList.add("hidden");
    renderSettings();
    showToast(`已导入“${preview.name || "模型连接"}”`);
  } catch (error) {
    $("#provider-import-status").textContent = errorText(error);
  } finally {
    state.providerImportBusy = false;
    $("#confirm-provider-import").disabled = false;
  }
}

async function refreshActiveProviderUsage() {
  const profile = activeProviderProfile();
  if (!profile?.id) return;
  $("#refresh-provider-usage").disabled = true;
  try {
    state.providerUsage = await backend().RefreshProviderUsage(profile.id);
    renderProviderImportIntegration();
  } catch (error) {
    showToast(errorText(error), true);
  } finally {
    renderProviderImportIntegration();
  }
}

function providerModelsForProfile(profile) {
  if (!profile) return [];
  const fetched = state.providerModelCatalogs[profile.id]?.models || [];
  const recommendations = fetched.length ? [] : builtinProviderModels(profile.providerId);
  const codexModels = profile.providerId === "codex-chatgpt"
    ? (state.codex?.models || []).map((model) => model.model || model.id || "")
    : [];
  return [...new Set([profile.model, ...recommendations, ...fetched, ...codexModels]
    .map((value) => String(value || "").trim()).filter(Boolean))];
}

function codexModelInfo(modelId) {
  const normalized = String(modelId || "").trim();
  return (state.codex?.models || []).find((model) =>
    [model.model, model.id].some((value) => String(value || "").trim() === normalized)
  ) || null;
}

function builtinProviderModels(providerId) {
  return {
    "openai-compatible": ["gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.5"],
    deepseek: ["deepseek-v4-flash", "deepseek-v4-pro"],
    claude: ["claude-fable-5", "claude-opus-4-8"],
    kimi: ["kimi-k3"], glm: ["glm-5.2"], qwen: ["qwen3.8-max-preview"],
    minimax: ["MiniMax-M3"], grok: ["grok-4.5"],
    mimo: ["mimo-v2.5", "mimo-v2.5-pro"], hy3: ["hy3-preview"],
    gemini: ["gemini-3.6-flash"],
  }[providerId] || [];
}

async function refreshProviderModels() {
  commitProviderProfileForm();
  const profile = activeProviderProfile();
  if (!profile || ["codex-chatgpt", "murong-local"].includes(profile.providerId)) return;
  const button = $("#refresh-provider-models");
  button.disabled = true;
  $("#provider-model-state").textContent = "正在读取上游模型列表…";
  try {
    const result = await backend().FetchProviderModels({
      providerProfileId: profile.id,
      providerId: profile.providerId,
      baseUrl: profile.baseUrl,
      currentModel: profile.model,
      apiKey: profile._clearApiKey ? "" : profile._apiKey || "",
      clearApiKey: Boolean(profile._clearApiKey),
    });
    state.providerModelCatalogs[profile.id] = result;
    renderProviderModelCandidates();
    renderComposerModelSelect();
    showToast(`已从上游读取 ${result.models?.length || 0} 个模型`);
  } catch (error) {
    $("#provider-model-state").textContent = errorText(error);
    showToast(errorText(error), true);
  } finally {
    button.disabled = false;
  }
}

function renderProviderModelCandidates() {
  const profile = activeProviderProfile();
  const container = $("#provider-model-candidates");
  const list = $("#provider-model-options");
  const stateLabel = $("#provider-model-state");
  if (!container || !list || !profile) return;
  const models = providerModelsForProfile(profile);
  container.replaceChildren();
  list.replaceChildren();
  models.forEach((model) => {
    const option = document.createElement("option");
    option.value = model;
    option.dataset.modelId = model;
    list.append(option);
    const button = document.createElement("button");
    button.type = "button";
    button.className = "provider-model-candidate";
    button.classList.toggle("active", model === $("#model-name").value.trim());
    button.textContent = model;
    button.addEventListener("click", () => {
      $("#model-name").value = model;
      profile.model = model;
      renderProviderModelCandidates();
    });
    container.append(button);
  });
  const catalog = state.providerModelCatalogs[profile.id];
  stateLabel.textContent = catalog
    ? `${catalog.sourceLabel || "上游接口"} · ${catalog.models?.length || 0} 个模型`
    : models.length ? `当前与推荐模型 · ${models.length} 个` : "可直接输入自定义模型";
}

function addProviderProfile() {
  commitProviderProfileForm();
  const defaults = providerDefaults["openai-compatible"];
  const profile = {
    id: `provider_${crypto.randomUUID?.() || `${Date.now()}_${Math.random().toString(16).slice(2)}`}`,
    providerId: "openai-compatible",
    name: `新连接 ${state.config.providerProfiles.length + 1}`,
    baseUrl: defaults.baseUrl,
    model: defaults.model,
    reasoningEffort: "high",
    apiMode: defaults.apiMode,
    contextWindowTokens: 0,
    hasApiKey: false,
  };
  state.config.providerProfiles.push(profile);
  state.config.activeProviderProfileId = profile.id;
  renderProviderProfileEditor();
  $("#provider-name").select();
}

function removeProviderProfile() {
  if (state.config.providerProfiles.length <= 1) return;
  const profile = activeProviderProfile();
  if (profile.providerId === "murong-local") {
    showToast("内置本地模型连接会跟随本地模型中心，不能删除");
    return;
  }
  const index = state.config.providerProfiles.indexOf(profile);
  state.config.providerProfiles.splice(index, 1);
  state.config.activeProviderProfileId = state.config.providerProfiles[Math.max(0, index - 1)].id;
  renderProviderProfileEditor();
  showToast(`已移除“${profile.name || "未命名连接"}”，保存设置后生效`);
}

function providerLabel(providerId) {
  return { "openai-compatible": "OpenAI-compatible", deepseek: "DeepSeek", claude: "Claude", kimi: "Kimi", glm: "智谱 GLM", qwen: "通义千问 Qwen", minimax: "MiniMax", grok: "xAI Grok", mimo: "小米 MiMo", hy3: "腾讯混元 Hy3", gemini: "Google Gemini", "codex-chatgpt": "Codex / ChatGPT", "murong-local": "内置本地模型" }[providerId] || "OpenAI-compatible";
}

function activeBuiltinVisionModelName() {
  const models = state.builtinVision?.models || [];
  return models.find((model) => model.active)?.displayName ||
    models.find((model) => model.installed && model.available !== false)?.displayName ||
    "尚未安装本地模型";
}

function activeBuiltinVisionModel() {
  const models = state.builtinVision?.models || [];
  return models.find((model) => model.active) ||
    models.find((model) => model.installed && model.available !== false) ||
    null;
}

function populateReasoningSelect(select, profile, defaultLabel) {
  if (!select) return;
  select.replaceChildren();
  const options = reasoningOptionsForProfile(profile, defaultLabel);
  options.forEach(({ value, label }) => {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = label;
    select.append(option);
  });
  const requested = profile?.reasoningEffort || "";
  const values = options.map((option) => option.value);
  if (values.includes(requested)) select.value = requested;
  else if (profile?.providerId === "murong-local") {
    const fallback = activeBuiltinVisionModel()?.defaultReasoningMode || values[0] || "";
    select.value = values.includes(fallback) ? fallback : values[0] || "";
  } else {
    select.value = "";
  }
}

function reasoningOptionsForProfile(profile, defaultLabel) {
  let options;
  if (profile?.providerId === "murong-local") {
    const model = activeBuiltinVisionModel();
    options = (model?.reasoningModes || []).map((mode) => ({
      value: mode.id,
      label: mode.displayName,
    }));
    if (!options.length) {
      options = [{ value: "", label: model ? "此模型不支持思考" : "请先安装并选择模型" }];
    }
  } else if (profile?.providerId === "codex-chatgpt") {
    const modelInfo = codexModelInfo(profile.model) || (state.codex?.models || []).find((model) => model.isDefault);
    const supported = modelInfo?.supportedReasoningEfforts || [];
    const defaultEffort = modelInfo?.defaultReasoningEffort || "";
    const defaultText = defaultEffort
      ? `${defaultLabel || "默认"}（${reasoningEffortLabel(defaultEffort)}）`
      : defaultLabel || "默认";
    options = [
      { value: "", label: defaultText },
      ...supported.map((effort) => ({ value: effort, label: reasoningEffortLabel(effort) })),
    ];
  } else {
    const defaultOption = { value: "", label: defaultLabel || "由供应商决定" };
    switch (profile?.providerId) {
      case "kimi":
        options = [defaultOption, { value: "on", label: "开启思考" }, { value: "off", label: "关闭思考" }];
        break;
      case "glm":
        options = [defaultOption, { value: "low", label: "低（映射高）" }, { value: "medium", label: "中（映射高）" }, { value: "high", label: "高" }, { value: "max", label: "最大" }];
        break;
      case "qwen":
        options = [defaultOption, { value: "low", label: "低" }, { value: "medium", label: "中" }, { value: "xhigh", label: "超高" }];
        break;
      case "grok":
        options = [defaultOption, { value: "low", label: "低" }, { value: "medium", label: "中" }, { value: "high", label: "高" }];
        break;
      case "minimax":
      case "mimo":
      case "hy3":
      case "gemini":
        options = [defaultOption];
        break;
      default:
        options = [
          defaultOption,
          { value: "low", label: "低" },
          { value: "medium", label: "中" },
          { value: "high", label: "高" },
          { value: "xhigh", label: "超高" },
          { value: "max", label: "最大" },
        ];
    }
  }
  return options.filter((option, index, values) =>
    values.findIndex((candidate) => candidate.value === option.value) === index
  );
}

function reasoningEffortLabel(value) {
  return {
    none: "无", minimal: "最小", low: "低", medium: "中", high: "高",
    xhigh: "超高", max: "最大", on: "开启思考", off: "关闭思考",
  }[value] || value || "默认";
}

function providerProfileModelLabel(profile) {
  return profile?.providerId === "murong-local"
    ? activeBuiltinVisionModelName()
    : profile?.model || "未配置模型";
}

function changeProviderKind(event) {
  const profile = activeProviderProfile();
  const kind = event.currentTarget.value;
  const defaults = providerDefaults[kind] || providerDefaults["openai-compatible"];
  profile.providerId = kind;
  profile.name = defaults.name;
  profile.baseUrl = defaults.baseUrl;
  profile.model = defaults.model;
  profile.reasoningEffort = kind === "murong-local" ? "off" : "high";
  profile.apiMode = defaults.apiMode;
  profile.contextWindowTokens = 0;
  profile._apiKey = "";
  profile._clearApiKey = false;
  renderProviderProfileEditor();
}

function updateProviderAPIModeState() {
  const kind = $("#provider-kind").value;
  const openAI = kind === "openai-compatible";
  const codex = kind === "codex-chatgpt";
  const builtinLocal = kind === "murong-local";
  const gemini = kind === "gemini";
  $("#provider-kind").disabled = builtinLocal;
  $("#provider-api-mode").disabled = !openAI;
  if (!openAI) $("#provider-api-mode").value = "auto";
  ["#provider-base-url-row", "#provider-api-mode-row", "#provider-context-window-row", "#provider-api-key-row", "#provider-clear-api-key-row"].forEach((selector) => {
    $(selector).classList.toggle("hidden", codex || builtinLocal);
  });
  $("#provider-api-mode-row").classList.toggle("hidden", !openAI || gemini);
  $("#model-name").disabled = builtinLocal;
  $("#reasoning-effort").disabled = builtinLocal && $("#reasoning-effort").options.length <= 1;
  $("#codex-provider-panel").classList.toggle("hidden", !codex);
  $("#refresh-provider-models").classList.toggle("hidden", codex || builtinLocal);
  $("#provider-model-state").classList.toggle("hidden", builtinLocal);
  $("#provider-model-candidates").classList.toggle("hidden", builtinLocal);
  if (codex) $("#model-name").setAttribute("list", "codex-model-options");
  else if (!builtinLocal) $("#model-name").setAttribute("list", "provider-model-options");
  else $("#model-name").removeAttribute("list");
}

async function refreshCodexStatus() {
  const button = $("#refresh-codex-status");
  button.disabled = true;
  $("#codex-provider-status").textContent = "正在检查或按需安装 Codex 运行时，并读取登录状态与可用模型…";
  try {
    commitProviderProfileForm();
    state.codex = await backend().RefreshCodexStatus({ executablePath: activeProviderProfile()?.executablePath || "" });
    renderCodexProviderStatus();
  } catch (error) {
    showToast(errorText(error), true);
    $("#codex-provider-status").textContent = errorText(error);
  } finally {
    button.disabled = false;
  }
}

async function startCodexLogin() {
  const button = $("#start-codex-login");
  button.disabled = true;
  try {
    commitProviderProfileForm();
    state.codex = await backend().StartCodexDeviceLogin({ executablePath: activeProviderProfile()?.executablePath || "" });
    renderCodexProviderStatus();
    showToast("登录页面已打开，请输入界面中的设备代码");
  } catch (error) {
    showToast(errorText(error), true);
  } finally {
    button.disabled = false;
  }
}

function renderCodexProviderStatus() {
  if (!$("#codex-provider-status") || !state.config) return;
  const codexSelected = $("#provider-kind").value === "codex-chatgpt";
  const status = state.codex || {};
  const models = Array.isArray(status.models) ? status.models : [];
  const list = $("#codex-model-options");
  list.replaceChildren();
  models.forEach((model) => {
    const option = document.createElement("option");
    option.value = model.model || model.id || "";
    option.label = model.displayName || model.model || model.id || "";
    if (option.value) list.append(option);
  });
  renderCodexAccountPool();
  if (!codexSelected) {
    renderComposerModelSelect();
    return;
  }
	if (!$("#model-name").value.trim()) {
	  const defaultModel = models.find((model) => model.isDefault) || models[0];
	  if (defaultModel) {
		const value = defaultModel.model || defaultModel.id || "";
		$("#model-name").value = value;
		activeProviderProfile().model = value;
	  }
	}
  const source = status.builtin ? "Murong 内置" : status.executablePath ? "外部 CLI" : "运行时";
  const version = String(status.version || "").trim();
  const account = [status.email, status.planType, status.accountType].filter(Boolean).join(" · ");
  let text = `${source}${version ? ` · ${version}` : ""}`;
  if (status.loggedIn) text += ` · 已登录${account ? `（${account}）` : ""} · ${models.length} 个模型`;
  else if (status.available) text += " · 尚未登录 ChatGPT";
  else text = status.error || "当前构建没有可用的 Codex 运行时";
	const rateSummary = formatCodexRateLimits(status.rateLimits || []);
	if (rateSummary) text += `\n${rateSummary}`;
	else if (status.loggedIn && status.rateLimitError) text += `\n额度暂不可用：${status.rateLimitError}`;
  if (status.error && status.available) text += ` · ${status.error}`;
  if (status.accountSwitchMessage) text += `\n${status.accountSwitchMessage}`;
  const statusElement = $("#codex-provider-status");
  statusElement.textContent = text;
  statusElement.classList.toggle("ready", Boolean(status.loggedIn));
  $("#start-codex-login").textContent = status.loggedIn ? "重新登录 ChatGPT" : "登录 ChatGPT";
  const login = status.login || {};
  const code = $("#codex-login-code");
  code.classList.toggle("hidden", !login.waiting || !login.userCode);
  code.textContent = login.waiting && login.userCode
    ? `设备代码：${login.userCode}\n${login.verificationUrl || "请在已打开的浏览器中继续"}`
    : "";
  renderComposerModelSelect();
}

function formatCodexRateLimits(rateLimits) {
  const parts = [];
  rateLimits.forEach((limit) => {
	const name = limit.limitName || limit.limitId || "Codex";
	const windows = [limit.primary, limit.secondary].filter(Boolean).map((window) => {
	  const minutes = Number(window.windowDurationMins) || 0;
	  const duration = minutes >= 1440 && minutes % 1440 === 0
		? `${minutes / 1440} 天`
		: minutes >= 60 && minutes % 60 === 0
		  ? `${minutes / 60} 小时`
		  : minutes > 0 ? `${minutes} 分钟` : "额度";
	  const used = Math.max(0, Math.min(100, Number(window.usedPercent) || 0));
	  return `${duration}已用 ${used.toFixed(used % 1 ? 1 : 0)}%`;
	});
	if (windows.length) parts.push(`${name}：${windows.join("，")}`);
  });
  return parts.join("；");
}

function addKnowledgeItem(collection) {
  if (state.knowledgeScope === "project" && !state.projectKnowledge?.hasProject) {
    showToast("请先在设置中选择项目", true);
    return;
  }
  const prefixes = { rules: "rule", memories: "memory", skills: "skill" };
  const id = `${prefixes[collection]}_${crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}_${Math.random()}`}`;
  const item = { id, title: "", content: "", enabled: true };
  if (collection === "skills") Object.assign(item, { description: "", runAs: "INLINE", allowedTools: [], preferredModel: "" });
  currentKnowledgeLibrary()[collection].push(item);
  renderKnowledge();
  const editor = document.querySelector(`[data-knowledge-id="${cssEscape(id)}"]`);
  editor?.scrollIntoView({ behavior: "smooth", block: "center" });
  editor?.querySelector(".knowledge-title")?.focus();
}

function renderKnowledge() {
  if (!state.projectKnowledge?.hasProject && state.knowledgeScope === "project") state.knowledgeScope = "global";
  const knowledge = currentKnowledgeLibrary();
  document.querySelectorAll("[data-knowledge-scope]").forEach((button) => {
    button.classList.toggle("active", button.dataset.knowledgeScope === state.knowledgeScope);
  });
  $("#knowledge-scope-detail").textContent = state.knowledgeScope === "project"
    ? (state.projectKnowledge?.hasProject ? `仅应用到 ${state.projectKnowledge.projectLabel || state.projectKnowledge.projectPath}` : "请先选择项目")
    : "应用到所有桌面会话";
  const projectUnavailable = state.knowledgeScope === "project" && !state.projectKnowledge?.hasProject;
  ["#add-rule", "#add-memory", "#add-skill", "#save-knowledge"].forEach((selector) => { $(selector).disabled = projectUnavailable; });
  $("#rule-count").textContent = knowledge.rules?.length || 0;
  $("#memory-count").textContent = knowledge.memories?.length || 0;
  $("#skill-count").textContent = knowledge.skills?.length || 0;
  renderKnowledgeCollection("rules", knowledge.rules || [], "rule-list");
  renderKnowledgeCollection("memories", knowledge.memories || [], "memory-list");
  renderKnowledgeCollection("skills", knowledge.skills || [], "skill-list");
}

function renderKnowledgeCollection(collection, items, targetId) {
  const container = $(`#${targetId}`);
  container.replaceChildren();
  if (!items.length) {
    const empty = document.createElement("div");
    empty.className = "knowledge-empty";
    const scopeLabel = state.knowledgeScope === "project" ? "项目" : "全局";
    empty.textContent = collection === "rules" ? `还没有${scopeLabel}规则` : collection === "memories" ? `还没有${scopeLabel}记忆` : `还没有${scopeLabel} Skill`;
    container.append(empty);
    return;
  }
  items.forEach((item) => container.append(createKnowledgeEditor(collection, item)));
}

function createKnowledgeEditor(collection, item) {
  const article = document.createElement("article");
  article.className = "knowledge-editor";
  article.dataset.collection = collection;
  article.dataset.knowledgeId = item.id;

  const header = document.createElement("div");
  header.className = "knowledge-editor-header";
  const enabled = document.createElement("label");
  enabled.className = "knowledge-switch";
  const checkbox = document.createElement("input");
  checkbox.type = "checkbox";
  checkbox.className = "knowledge-enabled";
  checkbox.checked = !!item.enabled;
  const enabledText = document.createElement("span");
  enabledText.textContent = "启用";
  enabled.append(checkbox, enabledText);
  const remove = document.createElement("button");
  remove.className = "ghost danger knowledge-remove";
  remove.textContent = "删除";
  remove.addEventListener("click", () => {
    const library = currentKnowledgeLibrary();
    library[collection] = library[collection].filter((value) => value.id !== item.id);
    renderKnowledge();
  });
  header.append(enabled, remove);

  const titleLabel = fieldLabel("标题", "input", "knowledge-title", item.title || "");
  const contentLabel = fieldLabel(collection === "skills" ? "Skill 说明" : "内容", "textarea", "knowledge-content", item.content || "");
  contentLabel.querySelector("textarea").rows = collection === "skills" ? 8 : 5;
  article.append(header, titleLabel);

  if (collection === "skills") {
    article.append(fieldLabel("简介", "input", "knowledge-description", item.description || ""));
    const options = document.createElement("div");
    options.className = "knowledge-options";
    const runAsLabel = document.createElement("label");
    runAsLabel.textContent = "运行方式";
    const runAs = document.createElement("select");
    runAs.className = "knowledge-run-as";
    [["INLINE", "内联"], ["SUBAGENT", "子代理"]].forEach(([value, label]) => {
      const option = document.createElement("option");
      option.value = value;
      option.textContent = label;
      option.selected = (item.runAs || "INLINE") === value;
      runAs.append(option);
    });
    runAsLabel.append(runAs);
    options.append(runAsLabel, fieldLabel("偏好模型", "input", "knowledge-model", item.preferredModel || ""));
    article.append(options, fieldLabel("允许的工具（逗号分隔）", "input", "knowledge-tools", (item.allowedTools || []).join(", ")));
  }
  article.append(contentLabel);
  return article;
}

function fieldLabel(text, tagName, className, value) {
  const label = document.createElement("label");
  label.textContent = text;
  const field = document.createElement(tagName);
  field.className = className;
  field.value = value;
  label.append(field);
  return label;
}

function collectKnowledge() {
  const result = { rules: [], memories: [], skills: [] };
  document.querySelectorAll(".knowledge-editor").forEach((editor) => {
    const collection = editor.dataset.collection;
    const item = {
      id: editor.dataset.knowledgeId,
      title: editor.querySelector(".knowledge-title").value.trim(),
      content: editor.querySelector(".knowledge-content").value.trim(),
      enabled: editor.querySelector(".knowledge-enabled").checked,
    };
    if (collection === "skills") {
      item.description = editor.querySelector(".knowledge-description").value.trim();
      item.runAs = editor.querySelector(".knowledge-run-as").value;
      item.preferredModel = editor.querySelector(".knowledge-model").value.trim();
      item.allowedTools = editor.querySelector(".knowledge-tools").value.split(",").map((value) => value.trim()).filter(Boolean);
    }
    result[collection].push(item);
  });
  return result;
}

function currentKnowledgeLibrary() {
  if (state.knowledgeScope === "project") {
    state.projectKnowledge.library ||= { rules: [], memories: [], skills: [] };
    state.projectKnowledge.library.rules ||= [];
    state.projectKnowledge.library.memories ||= [];
    state.projectKnowledge.library.skills ||= [];
    return state.projectKnowledge.library;
  }
  state.knowledge ||= { rules: [], memories: [], skills: [] };
  state.knowledge.rules ||= [];
  state.knowledge.memories ||= [];
  state.knowledge.skills ||= [];
  return state.knowledge;
}

function syncKnowledgeDraft() {
  const draft = collectKnowledge();
  if (state.knowledgeScope === "project") state.projectKnowledge.library = draft;
  else state.knowledge = draft;
}

function switchKnowledgeScope(scope) {
  if (scope !== "global" && scope !== "project") return;
  if (scope === "project" && !state.projectKnowledge?.hasProject) {
    showToast("请先在设置中选择项目", true);
    return;
  }
  syncKnowledgeDraft();
  state.knowledgeScope = scope;
  $("#knowledge-status").textContent = "";
  renderKnowledge();
}

async function saveKnowledge() {
  const status = $("#knowledge-status");
  status.textContent = "正在保存…";
  try {
    const draft = collectKnowledge();
    if (state.knowledgeScope === "project") {
      state.projectKnowledge = await backend().SaveProjectKnowledgeLibrary(draft);
    } else {
      state.knowledge = await backend().SaveKnowledgeLibrary(draft);
    }
    renderKnowledge();
    status.textContent = "已保存";
  } catch (error) {
    status.textContent = "";
    showToast(errorText(error), true);
  }
}

function toggleMCPPanel() {
  state.mcpExpanded = !state.mcpExpanded;
  renderMCP();
}

function renderMCP() {
  const panel = $("#mcp-panel");
  if (!panel) return;
  panel.classList.toggle("hidden", !state.mcpExpanded);
  $("#toggle-mcp-panel").textContent = state.mcpExpanded ? "收起" : "展开";
  const servers = state.mcp?.servers || [];
  const statuses = state.mcp?.statuses || [];
  const connected = statuses.filter((item) => item.connected).length;
  const tools = state.mcp?.tools?.length || 0;
  $("#mcp-summary").textContent = servers.length
    ? `${servers.length} 个服务器 · ${connected} 个已连接 · ${tools} 个工具`
    : "尚未配置 MCP 服务器";
  const list = $("#mcp-server-list");
  list.replaceChildren();
  if (!servers.length) {
    const empty = document.createElement("div");
    empty.className = "knowledge-empty";
    empty.textContent = "还没有 MCP 服务器。添加后可连接本机 stdio 命令或远程 Streamable HTTP 端点。";
    list.append(empty);
    return;
  }
  servers.forEach((server) => list.append(createMCPServerCard(server, statuses.find((item) => item.id === server.id))));
}

function createMCPServerCard(server, status) {
  const card = document.createElement("article");
  card.className = "mcp-server-card";
  card.dataset.mcpId = server.id;
  const args = (server.args || []).join("\n");
  const trusted = (server.trustedReadOnlyTools || []).join(", ");
  const environmentHint = server.environmentKeys?.length ? `已加密保存：${server.environmentKeys.join(", ")}。留空保留原值` : "可选 JSON 对象，例如 {\"TOKEN\":\"...\"}";
  const headerHint = server.headerKeys?.length ? `已加密保存：${server.headerKeys.join(", ")}。留空保留原值` : "可选 JSON 对象，例如 {\"Authorization\":\"Bearer ...\"}";
  let stateLabel = "未连接";
  let stateClass = "offline";
  if (!server.enabled) stateLabel = "已关闭";
  else if (status?.connecting) { stateLabel = "连接中…"; stateClass = "connecting"; }
  else if (status?.connected) { stateLabel = `已连接 · ${status.toolCount || 0} 个工具`; stateClass = "connected"; }
  else if (status?.error) { stateLabel = `连接失败 · ${status.error}`; stateClass = "error"; }
  card.innerHTML = `
    <div class="mcp-card-header">
      <label class="knowledge-switch"><input class="mcp-enabled" type="checkbox" ${server.enabled ? "checked" : ""}> 启用</label>
      <span class="mcp-connection-state ${stateClass}" title="${escapeHtml(String(status?.error || ""))}">${escapeHtml(stateLabel)}</span>
      <button class="ghost danger mcp-remove" type="button">移除</button>
    </div>
    <div class="mcp-form-grid">
      <label>名称<input class="mcp-name" value="${escapeHtml(String(server.name || ""))}" placeholder="例如 GitHub、Filesystem"></label>
      <label>传输方式<select class="mcp-transport"><option value="stdio">stdio（本机进程）</option><option value="streamable_http">Streamable HTTP</option><option value="legacy_sse">HTTP + SSE（旧版兼容）</option></select></label>
      <div class="mcp-stdio-fields span-2">
        <label>启动命令<input class="mcp-command" value="${escapeHtml(String(server.command || ""))}" placeholder="例如 npx、node 或绝对路径"></label>
        <label>工作目录<input class="mcp-cwd" value="${escapeHtml(String(server.cwd || ""))}" placeholder="可选；留空使用程序目录"></label>
        <label class="span-2">参数（每行一个）<textarea class="mcp-args" rows="3" placeholder="-y&#10;@modelcontextprotocol/server-filesystem&#10;C:\\workspace">${escapeHtml(args)}</textarea></label>
      </div>
      <div class="mcp-http-fields span-2">
        <label class="span-2">MCP 端点 URL<input class="mcp-url" value="${escapeHtml(String(server.url || ""))}" placeholder="Streamable HTTP 如 /mcp；旧版 SSE 如 /sse"></label>
      </div>
      <label>请求超时（秒）<input class="mcp-timeout" type="number" min="1" max="600" value="${Number(server.requestTimeoutSeconds) || 60}"></label>
      <label class="mcp-autostart-label"><span>启动策略</span><span class="mcp-check"><input class="mcp-autostart" type="checkbox" ${server.autoStart ? "checked" : ""}> 软件启动时自动连接</span></label>
      <label class="span-2">可信只读工具（逗号分隔）<input class="mcp-trusted" value="${escapeHtml(trusted)}" placeholder="例如 search, read_file；* 表示信任该服务器全部工具为只读"><small>只有这里明确列出的工具可在只读和计划模式中执行。</small></label>
      <label class="span-2">环境变量 JSON<textarea class="mcp-environment" rows="2" placeholder="${escapeHtml(environmentHint)}"></textarea></label>
      <label class="mcp-clear-row"><input class="mcp-clear-environment" type="checkbox"> 清除已保存环境变量</label>
      <label class="span-2">HTTP 请求头 JSON<textarea class="mcp-headers" rows="2" placeholder="${escapeHtml(headerHint)}"></textarea></label>
      <label class="mcp-clear-row"><input class="mcp-clear-headers" type="checkbox"> 清除已保存请求头</label>
    </div>`;
  card.querySelector(".mcp-transport").value = ["streamable_http", "legacy_sse"].includes(server.transport) ? server.transport : "stdio";
  card.querySelector(".mcp-transport").addEventListener("change", () => updateMCPTransportFields(card));
  card.querySelector(".mcp-remove").addEventListener("click", () => {
    state.mcp.servers = (state.mcp.servers || []).filter((item) => item.id !== server.id);
    renderMCP();
    $("#mcp-status").textContent = "删除将在保存后生效";
  });
  updateMCPTransportFields(card);
  return card;
}

function updateMCPTransportFields(card) {
  const http = ["streamable_http", "legacy_sse"].includes(card.querySelector(".mcp-transport").value);
  card.querySelector(".mcp-stdio-fields").classList.toggle("hidden", http);
  card.querySelector(".mcp-http-fields").classList.toggle("hidden", !http);
}

function addMCPServer() {
  state.mcp ||= { servers: [], statuses: [], tools: [] };
  state.mcp.servers ||= [];
  state.mcp.servers.push({
    id: `mcp_${crypto.randomUUID?.() || `${Date.now()}_${Math.random().toString(16).slice(2)}`}`,
    name: `MCP Server ${state.mcp.servers.length + 1}`,
    transport: "stdio", command: "", args: [], cwd: "", url: "",
    requestTimeoutSeconds: 60, trustedReadOnlyTools: [], enabled: true, autoStart: true,
    environmentKeys: [], headerKeys: [],
  });
  state.mcpExpanded = true;
  renderMCP();
  const cards = document.querySelectorAll(".mcp-server-card");
  cards[cards.length - 1]?.querySelector(".mcp-name")?.select();
}

function renderCodexAccountPool() {
  const list = $("#codex-account-list");
  if (!list) return;
  const pool = state.codex?.accountPool || { activeAccountId: "", settings: {}, accounts: [] };
  const settings = pool.settings || {};
  $("#codex-auto-switch").checked = settings.autoSwitch !== false;
  $("#codex-reserve-percent").value = Number(settings.reservePercent) || 10;
  $("#codex-cooldown-minutes").value = Number(settings.cooldownMinutes) || 15;
  const pin = $("#pin-session-codex-account");
  pin.checked = Boolean(state.active?.codexAccountPinned);
  pin.disabled = !state.active || state.running;
  list.replaceChildren();
  const accounts = Array.isArray(pool.accounts) ? pool.accounts : [];
  if (!accounts.length) {
    list.innerHTML = '<div class="codex-account-empty">尚未建立账号资料；点击“添加账号”后完成设备码登录。</div>';
    return;
  }
  accounts.forEach((account) => {
    const card = document.createElement("article");
    card.className = `codex-account-card${account.active ? " active" : ""}${account.lowQuota ? " low" : ""}`;
    card.dataset.accountId = account.id;
    const quota = formatCodexRateLimits(account.rateLimits || []);
    const cooling = Number(account.cooldownUntil) > Date.now()
      ? `冷却至 ${new Date(account.cooldownUntil).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}`
      : "";
    const identity = account.email || account.label || "Codex 账号";
    const detail = [account.planType, quota, cooling, account.error].filter(Boolean).join(" · ");
    card.innerHTML = `
      <div class="codex-account-identity">
        <strong>${escapeHtml(identity)}</strong>
        <span>${escapeHtml(detail || (account.loggedIn ? "已保存登录" : "等待登录"))}</span>
      </div>
      <div class="codex-account-badges">
        ${account.active ? '<span class="codex-account-badge active">当前</span>' : ""}
        ${account.lowQuota ? '<span class="codex-account-badge low">额度低</span>' : ""}
      </div>
      <label class="codex-account-enabled"><input data-codex-enable type="checkbox" ${account.enabled ? "checked" : ""} ${account.active ? "disabled" : ""}>启用</label>
      <div class="codex-account-actions">
        ${account.active ? "" : '<button class="secondary" type="button" data-codex-action="activate">切换</button>'}
        ${account.active ? "" : '<button class="danger-text" type="button" data-codex-action="remove">删除</button>'}
      </div>`;
    list.append(card);
  });
}

async function createCodexAccount() {
  const button = $("#add-codex-account");
  button.disabled = true;
  try {
    const executablePath = activeProviderProfile()?.executablePath || "";
    state.codex = await backend().CreateCodexAccount({ executablePath, label: "" });
    renderCodexProviderStatus();
    state.codex = await backend().StartCodexDeviceLogin({ executablePath });
    renderCodexProviderStatus();
    showToast("已创建独立账号槽位，请在浏览器完成 ChatGPT 登录");
  } catch (error) {
    showToast(errorText(error), true);
  } finally {
    button.disabled = false;
  }
}

async function handleCodexAccountAction(event) {
  const button = event.target.closest("[data-codex-action]");
  const card = event.target.closest("[data-account-id]");
  if (!button || !card) return;
  const accountId = card.dataset.accountId || "";
  const executablePath = activeProviderProfile()?.executablePath || "";
  button.disabled = true;
  try {
    if (button.dataset.codexAction === "activate") {
      state.codex = await backend().ActivateCodexAccount({ executablePath, accountId });
      showToast("已在安全边界切换 Codex 账号");
    } else if (button.dataset.codexAction === "remove") {
      if (!window.confirm("删除这个账号资料及其私有 CODEX_HOME？此操作不会影响其他账号。")) return;
      state.codex = await backend().RemoveCodexAccount({ executablePath, accountId });
      showToast("账号资料已删除");
    }
    renderCodexProviderStatus();
  } catch (error) {
    showToast(errorText(error), true);
  } finally {
    button.disabled = false;
  }
}

async function handleCodexAccountToggle(event) {
  const input = event.target.closest("[data-codex-enable]");
  const card = event.target.closest("[data-account-id]");
  if (!input || !card) return;
  input.disabled = true;
  try {
    state.codex = await backend().UpdateCodexAccount({ accountId: card.dataset.accountId || "", enabled: input.checked });
    renderCodexProviderStatus();
  } catch (error) {
    input.checked = !input.checked;
    showToast(errorText(error), true);
  } finally {
    input.disabled = false;
  }
}

async function saveCodexAccountPoolSettings() {
  const button = $("#save-codex-pool-settings");
  button.disabled = true;
  try {
    state.codex = await backend().UpdateCodexAccountPoolSettings({
      autoSwitch: $("#codex-auto-switch").checked,
      reservePercent: Number($("#codex-reserve-percent").value),
      cooldownMinutes: Number($("#codex-cooldown-minutes").value),
    });
    renderCodexProviderStatus();
    showToast("Codex 无感切号策略已保存");
  } catch (error) {
    showToast(errorText(error), true);
  } finally {
    button.disabled = false;
  }
}

async function pinActiveSessionCodexAccount(event) {
  if (!state.active) return;
  const input = event.currentTarget;
  input.disabled = true;
  try {
    state.active = await backend().SetSessionCodexAccountPinned({ sessionId: state.active.id, pinned: input.checked });
    renderCodexAccountPool();
    showToast(input.checked ? "当前会话已固定账号，不再自动切号" : "当前会话已恢复账号池自动切换");
  } catch (error) {
    input.checked = !input.checked;
    showToast(errorText(error), true);
  } finally {
    input.disabled = false;
  }
}

function addNuphusMCPServer() {
  state.mcp ||= { servers: [], statuses: [], tools: [] };
  state.mcp.servers ||= [];
  const existing = state.mcp.servers.find((server) =>
    String(server.name || "").toLowerCase().includes("nuphus") ||
    (server.args || []).some((argument) => String(argument).includes("@nuphus/nuphus-mcp"))
  );
  if (existing) {
    state.mcpExpanded = true;
    renderMCP();
    document.querySelector(`[data-mcp-id="${CSS.escape(existing.id)}"]`)?.scrollIntoView({ behavior: "smooth", block: "center" });
    $("#mcp-status").textContent = "Nuphus 电脑控制预设已经存在";
    return;
  }
  state.mcp.servers.push({
    id: `mcp_${crypto.randomUUID?.() || `${Date.now()}_${Math.random().toString(16).slice(2)}`}`,
    name: "Nuphus 电脑控制",
    transport: "stdio",
    command: "npx",
    args: ["-y", "@nuphus/nuphus-mcp", "--confirm-write"],
    cwd: "",
    url: "",
    requestTimeoutSeconds: 120,
    trustedReadOnlyTools: [
      "desktop_screen_size", "desktop_windows_list", "desktop_window_info",
      "desktop_vision", "desktop_perceive", "browser_snapshot", "browser_extract",
      "browser_cookies_get", "browser_list_tabs", "browser_list_downloads", "browser_wait_for",
    ],
    enabled: true,
    autoStart: false,
    environmentKeys: [],
    headerKeys: [],
  });
  state.mcpExpanded = true;
  renderMCP();
  const cards = document.querySelectorAll(".mcp-server-card");
  cards[cards.length - 1]?.scrollIntoView({ behavior: "smooth", block: "center" });
  $("#mcp-status").textContent = "已添加安全预设；点击“保存并连接”后下载并启动 Nuphus";
}

function parseMCPSecretJSON(value, label) {
  const text = value.trim();
  if (!text) return undefined;
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    throw new Error(`${label}不是有效 JSON：${error.message}`);
  }
  if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") throw new Error(`${label}必须是 JSON 对象`);
  return Object.fromEntries(Object.entries(parsed).map(([key, entry]) => [String(key), String(entry ?? "")]));
}

function collectMCPServers() {
  return [...document.querySelectorAll(".mcp-server-card")].map((card) => {
    const environment = parseMCPSecretJSON(card.querySelector(".mcp-environment").value, "环境变量");
    const headers = parseMCPSecretJSON(card.querySelector(".mcp-headers").value, "请求头");
    return {
      id: card.dataset.mcpId,
      name: card.querySelector(".mcp-name").value.trim(),
      transport: card.querySelector(".mcp-transport").value,
      command: card.querySelector(".mcp-command").value.trim(),
      args: card.querySelector(".mcp-args").value.split(/\r?\n/).map((value) => value.trim()).filter(Boolean),
      cwd: card.querySelector(".mcp-cwd").value.trim(),
      url: card.querySelector(".mcp-url").value.trim(),
      requestTimeoutSeconds: Number(card.querySelector(".mcp-timeout").value) || 60,
      trustedReadOnlyTools: card.querySelector(".mcp-trusted").value.split(/[,;\n]/).map((value) => value.trim()).filter(Boolean),
      enabled: card.querySelector(".mcp-enabled").checked,
      autoStart: card.querySelector(".mcp-autostart").checked,
      ...(environment === undefined ? {} : { environment }),
      ...(headers === undefined ? {} : { headers }),
      clearEnvironment: environment === undefined && card.querySelector(".mcp-clear-environment").checked,
      clearHeaders: headers === undefined && card.querySelector(".mcp-clear-headers").checked,
    };
  });
}

async function saveMCPServers() {
  const status = $("#mcp-status");
  const button = $("#save-mcp-servers");
  status.textContent = "正在保存并连接…";
  button.disabled = true;
  try {
    state.mcp = await backend().SaveMCPServers({ servers: collectMCPServers() });
    renderMCP();
    await refreshComposerCatalog();
    status.textContent = "已保存";
  } catch (error) {
    status.textContent = "";
    showToast(errorText(error), true);
  } finally {
    button.disabled = false;
  }
}

async function connectMCPServers() {
  const status = $("#mcp-status");
  const button = $("#connect-mcp-servers");
  status.textContent = "正在重新连接…";
  button.disabled = true;
  try {
    state.mcp = await backend().ConnectMCPServers();
    renderMCP();
    await refreshComposerCatalog();
    const failures = (state.mcp.statuses || []).filter((item) => item.error).length;
    status.textContent = failures ? `${failures} 个服务器连接失败` : "连接完成";
  } catch (error) {
    status.textContent = "";
    showToast(errorText(error), true);
  } finally {
    button.disabled = false;
  }
}

const workflowTemplateLabels = {
  PROJECT_READ_DIAGNOSTIC: "项目只读诊断",
  DIRECTORY_CHANGE_SUMMARY: "目录变更摘要",
  GITHUB_ACTIONS_STATUS: "GitHub Actions 状态检查",
  SESSION_SUMMARY_EXPORT: "会话摘要导出",
};

const workflowRunLabels = {
  NEVER: "从未运行", QUEUED: "已排队", RUNNING: "运行中", SUCCEEDED: "成功",
  FAILED: "失败", BLOCKED: "已拦截", CANCELLED: "已取消",
};

function renderAutomation() {
  const snapshot = state.savedWorkflows || {};
  const github = snapshot.github || { apiBaseUrl: "https://api.github.com", hasToken: false, viewer: "" };
  const githubAccounts = snapshot.githubAccounts || [];
  const workflows = snapshot.workflows || [];
  const apiInput = $("#github-api-base");
  const tokenInput = $("#github-token");
  if (!apiInput) return;
  if (document.activeElement !== apiInput) apiInput.value = github.apiBaseUrl || "https://api.github.com";
  if (document.activeElement !== tokenInput) tokenInput.value = "";
  $("#github-token-state").textContent = github.hasToken ? "已加密保存" : "未保存";
  $("#github-connection-state").textContent = github.viewer
    ? `已连接 · ${github.viewer}`
    : github.hasToken ? "Token 已保存" : "未配置 Token";
  $("#github-connection-state").classList.toggle("ready", Boolean(github.viewer));
  $("#github-account-summary").textContent = `${githubAccounts.length} 个账号${github.viewer ? ` · 当前 ${github.viewer}` : ""}`;
  const accountList = $("#github-account-list");
  accountList.replaceChildren();
  githubAccounts.forEach((account) => accountList.append(createGitHubAccountCard(account)));
  $("#workflow-count").textContent = workflows.length;
  const enabled = workflows.filter((workflow) => workflow.enabled && workflowBackgroundSafeInUI(workflow)).length;
  const running = workflows.filter((workflow) => ["QUEUED", "RUNNING"].includes(workflow.lastRun?.status)).length;
  $("#workflow-enabled-count").textContent = enabled;
  $("#workflow-running-count").textContent = running;
  $("#workflow-dot").classList.toggle("online", enabled > 0 || running > 0);
  const list = $("#saved-workflow-list");
  list.replaceChildren();
  if (!workflows.length) {
    const empty = document.createElement("div");
    empty.className = "workflow-empty";
    empty.innerHTML = "<strong>还没有保存的工作流</strong><span>新建后可以立即运行，也可以按固定周期执行安全的只读模板。</span>";
    list.append(empty);
    return;
  }
  workflows.forEach((workflow) => list.append(createSavedWorkflowCard(workflow)));
}

function createGitHubAccountCard(account) {
  const card = document.createElement("article");
  card.className = `github-account-card${account.active ? " active" : ""}`;
  card.dataset.accountId = account.id || "";
  const identity = account.login ? `@${account.login.replace(/^@/, "")}` : account.label || "未命名账号";
  const detail = account.hasToken ? account.apiBaseUrl || "https://api.github.com" : "尚未保存访问令牌";
  card.innerHTML = `
    <div class="github-account-identity"><strong>${escapeHtml(identity)}</strong><span>${escapeHtml(detail)}</span></div>
    <div class="github-account-badges">${account.active ? '<span class="github-account-badge active">当前</span>' : ""}<span class="github-account-badge ${account.hasToken ? "ready" : "empty"}">${account.hasToken ? "已登录" : "待登录"}</span></div>
    <div class="github-account-actions"><button class="secondary github-account-activate" type="button" ${account.active || !account.hasToken ? "disabled" : ""}>切换</button><button class="icon-button danger github-account-remove" type="button" title="移除账号" aria-label="移除 ${escapeHtml(identity)}">×</button></div>`;
  return card;
}

async function createGitHubAccount() {
  const button = $("#add-github-account");
  button.disabled = true;
  try {
    const index = (state.savedWorkflows?.githubAccounts || []).length + 1;
    state.savedWorkflows = await backend().CreateGitHubAccount({ label: `GitHub 账号 ${index}` });
    renderAutomation();
    $("#github-token").focus();
    showToast("已创建账号，请保存 Token 后验证连接");
  } catch (error) {
    showToast(errorText(error), true);
  } finally {
    button.disabled = false;
  }
}

async function handleGitHubAccountAction(event) {
  const button = event.target.closest("button");
  const card = button?.closest("[data-account-id]");
  const accountId = card?.dataset.accountId || "";
  if (!button || !accountId) return;
  if (button.classList.contains("github-account-activate")) {
    button.disabled = true;
    try {
      state.savedWorkflows = await backend().ActivateGitHubAccount({ accountId });
      renderAutomation();
      showToast("GitHub 账号已切换");
    } catch (error) {
      showToast(errorText(error), true);
      button.disabled = false;
    }
    return;
  }
  if (button.classList.contains("github-account-remove")) requestGitHubAccountRemoval(accountId);
}

function requestGitHubAccountRemoval(accountId) {
  const account = (state.savedWorkflows?.githubAccounts || []).find((item) => item.id === accountId);
  if (!account) return;
  state.pendingGitHubAccountRemoval = account;
  const identity = account.login ? `@${account.login.replace(/^@/, "")}` : account.label || "该账号";
  $("#github-account-remove-detail").textContent = `移除“${identity}”后，该账号保存在本机的访问令牌也会一并删除。`;
  $("#github-account-remove-modal").classList.remove("hidden");
}

function closeGitHubAccountRemoval() {
  state.pendingGitHubAccountRemoval = null;
  $("#github-account-remove-modal").classList.add("hidden");
}

async function confirmGitHubAccountRemoval() {
  const accountId = state.pendingGitHubAccountRemoval?.id || "";
  if (!accountId) return;
  const button = $("#confirm-github-account-remove");
  button.disabled = true;
  try {
    state.savedWorkflows = await backend().RemoveGitHubAccount({ accountId });
    closeGitHubAccountRemoval();
    renderAutomation();
    showToast("GitHub 账号已移除");
  } catch (error) {
    showToast(errorText(error), true);
  } finally {
    button.disabled = false;
  }
}

function createSavedWorkflowCard(workflow) {
  const card = document.createElement("article");
  card.className = "saved-workflow-card";
  const status = workflow.lastRun?.status || "NEVER";
  const active = status === "QUEUED" || status === "RUNNING";
  const scheduled = workflow.enabled && workflowBackgroundSafeInUI(workflow);
  const target = workflow.template === "GITHUB_ACTIONS_STATUS"
    ? workflow.githubRepository
    : workflow.template === "SESSION_SUMMARY_EXPORT" ? "当前聊天任务" : projectName(workflow.projectPath || "");
  const lastRun = workflow.lastRun;
  card.innerHTML = `
    <div class="saved-workflow-head">
      <div class="workflow-title-block"><span class="workflow-template-icon">${workflowTemplateIcon(workflow.template)}</span><div><strong>${escapeHtml(workflow.name)}</strong><small>${escapeHtml(workflowTemplateLabels[workflow.template] || workflow.template)} · ${escapeHtml(target || "未指定范围")}</small></div></div>
      <span class="workflow-run-state status-${String(status).toLowerCase()}">${escapeHtml(workflowRunLabels[status] || status)}</span>
    </div>
    <div class="workflow-meta-row">
      <span>${scheduled ? `每 ${Number(workflow.intervalMinutes) || 60} 分钟自动运行` : workflow.enabled ? "已启用，但该模板必须前台确认" : "未启用后台调度"}</span>
      <span>${workflowBackgroundSafeInUI(workflow) ? "固定只读执行器" : "前台逐次确认"}</span>
    </div>
    ${lastRun ? `<div class="workflow-last-run"><strong>${escapeHtml(lastRun.summary || workflowRunLabels[status] || status)}</strong>${lastRun.failureReason ? `<small title="${escapeHtml(lastRun.failureReason)}">${escapeHtml(lastRun.failureReason)}</small>` : ""}${lastRun.finishedAt ? `<time>${escapeHtml(formatTime(lastRun.finishedAt))}</time>` : ""}</div>` : ""}
    <div class="workflow-card-actions">${workflowBackgroundSafeInUI(workflow) ? '<button class="secondary workflow-copy-command" type="button">复制外部命令</button>' : ''}<button class="secondary workflow-edit" type="button">编辑</button><button class="ghost danger workflow-delete" type="button">删除</button><button class="primary workflow-run" type="button" ${active ? "disabled" : ""}>${active ? "运行中…" : "立即运行"}</button></div>`;
  card.querySelector(".workflow-copy-command")?.addEventListener("click", async () => {
    try {
      await backend().CopySavedWorkflowExternalCommand(workflow.id);
      showToast("外部工作流命令已复制");
    } catch (error) {
      showToast(errorText(error), true);
    }
  });
  card.querySelector(".workflow-edit").addEventListener("click", () => openWorkflowEditor(workflow.id));
  let deleteArmedUntil = 0;
  card.querySelector(".workflow-delete").addEventListener("click", async (event) => {
    if (Date.now() > deleteArmedUntil) {
      deleteArmedUntil = Date.now() + 3000;
      event.currentTarget.textContent = "再次删除";
      return;
    }
    try {
      state.savedWorkflows = await backend().DeleteSavedWorkflow(workflow.id);
      renderAutomation();
      showToast("已删除保存的工作流");
    } catch (error) {
      showToast(errorText(error), true);
    }
  });
  card.querySelector(".workflow-run").addEventListener("click", () => requestSavedWorkflowRun(workflow));
  return card;
}

function workflowBackgroundSafeInUI(workflow) {
  const expected = ({ PROJECT_READ_DIAGNOSTIC: "PROJECT_READ", DIRECTORY_CHANGE_SUMMARY: "PROJECT_READ", GITHUB_ACTIONS_STATUS: "NETWORK_READ" })[workflow.template];
  return Boolean(expected) && Array.isArray(workflow.nodes) && workflow.nodes.length > 0
    && workflow.nodes.every((node) => node.requiredPermission === expected);
}

function workflowTemplateIcon(template) {
  return ({ PROJECT_READ_DIAGNOSTIC: "R", DIRECTORY_CHANGE_SUMMARY: "Δ", GITHUB_ACTIONS_STATUS: "GH", SESSION_SUMMARY_EXPORT: "MD" })[template] || "W";
}

function openWorkflowEditor(id = "") {
  const workflow = (state.savedWorkflows?.workflows || []).find((item) => item.id === id);
  $("#workflow-editor-title").textContent = workflow ? "编辑工作流" : "新建工作流";
  $("#workflow-id").value = workflow?.id || "";
  $("#workflow-name").value = workflow?.name || "项目只读诊断";
  $("#workflow-template").value = workflow?.template || "PROJECT_READ_DIAGNOSTIC";
  $("#workflow-project-path").value = workflow?.projectPath || activeProjectPath();
  $("#workflow-github-repository").value = workflow?.githubRepository || "";
  $("#workflow-interval").value = Number(workflow?.intervalMinutes) || 60;
  $("#workflow-enabled").checked = Boolean(workflow?.enabled);
  updateWorkflowEditorTemplate();
  $("#workflow-editor-modal").classList.remove("hidden");
  requestAnimationFrame(() => $("#workflow-name").focus());
}

function closeWorkflowEditor() {
  $("#workflow-editor-modal").classList.add("hidden");
}

function updateWorkflowEditorTemplate() {
  const template = $("#workflow-template").value;
  const projectTemplate = template === "PROJECT_READ_DIAGNOSTIC" || template === "DIRECTORY_CHANGE_SUMMARY";
  const githubTemplate = template === "GITHUB_ACTIONS_STATUS";
  $("#workflow-project-row").classList.toggle("hidden", !projectTemplate);
  $("#workflow-github-row").classList.toggle("hidden", !githubTemplate);
  const enabled = $("#workflow-enabled");
  const foregroundOnly = template === "SESSION_SUMMARY_EXPORT";
  enabled.disabled = foregroundOnly;
  if (foregroundOnly) enabled.checked = false;
  const details = {
    PROJECT_READ_DIAGNOSTIC: "只读取目录、可访问性和受限规模摘要，不读取文件正文。可安全后台运行。",
    DIRECTORY_CHANGE_SUMMARY: "收集最多 400 项、最多 3 层的受限目录快照，忽略构建缓存。可安全后台运行。",
    GITHUB_ACTIONS_STATUS: "固定 GET 请求读取最近 5 条 Actions 状态，不会修改仓库。可安全后台运行。",
    SESSION_SUMMARY_EXPORT: "把当前聊天任务写成 Markdown 到文档目录；必须在前台逐次确认，不能后台调度。",
  };
  $("#workflow-template-detail").textContent = details[template] || "";
  $("#workflow-template-detail").classList.toggle("foreground", foregroundOnly);
}

async function chooseWorkflowProject() {
  try {
    const selected = await backend().SelectProject();
    if (selected) $("#workflow-project-path").value = selected;
  } catch (error) {
    showToast(errorText(error), true);
  }
}

async function saveSavedWorkflow(event) {
  event.preventDefault();
  const submit = event.currentTarget.querySelector('button[type="submit"]');
  submit.disabled = true;
  try {
    state.savedWorkflows = await backend().SaveSavedWorkflow({
      id: $("#workflow-id").value,
      name: $("#workflow-name").value.trim(),
      template: $("#workflow-template").value,
      projectPath: $("#workflow-project-path").value.trim(),
      githubRepository: $("#workflow-github-repository").value.trim(),
      intervalMinutes: Number($("#workflow-interval").value) || 0,
      enabled: $("#workflow-enabled").checked,
    });
    closeWorkflowEditor();
    renderAutomation();
    showToast("工作流已保存");
  } catch (error) {
    showToast(errorText(error), true);
  } finally {
    submit.disabled = false;
  }
}

async function saveGitHubConfig(showSuccess = true) {
  const button = $("#save-github");
  button.disabled = true;
  try {
    state.savedWorkflows = await backend().SaveGitHubConfig({
      apiBaseUrl: $("#github-api-base").value.trim(),
      token: $("#github-token").value.trim(),
      clearToken: $("#clear-github-token").checked,
    });
    $("#clear-github-token").checked = false;
    renderAutomation();
    if (showSuccess) showToast("GitHub 配置已保存");
    return true;
  } catch (error) {
    showToast(errorText(error), true);
    return false;
  } finally {
    button.disabled = false;
  }
}

async function testGitHubConnection() {
  const button = $("#test-github");
  button.disabled = true;
  $("#github-status").textContent = "正在保存并验证连接…";
  try {
    if (!await saveGitHubConfig(false)) return;
    state.savedWorkflows = await backend().TestGitHubConnection();
    renderAutomation();
    $("#github-status").textContent = `连接成功：${state.savedWorkflows.github.viewer}`;
  } catch (error) {
    $("#github-status").textContent = "连接验证失败";
    showToast(errorText(error), true);
  } finally {
    button.disabled = false;
  }
}

function requestSavedWorkflowRun(workflow) {
  if (workflow.template !== "SESSION_SUMMARY_EXPORT") {
    runSavedWorkflow(workflow.id, false);
    return;
  }
  if (!state.active) {
    showToast("请先选择一个聊天任务后再导出", true);
    return;
  }
  state.pendingWorkflowRunId = workflow.id;
  $("#workflow-confirm-detail").textContent = `“${workflow.name}”会把当前聊天任务导出为 Markdown 到文档目录，不会上传到网络。`;
  $("#workflow-confirm-modal").classList.remove("hidden");
}

function closeWorkflowRunConfirmation() {
  state.pendingWorkflowRunId = "";
  $("#workflow-confirm-modal").classList.add("hidden");
}

async function confirmSavedWorkflowRun() {
  const id = state.pendingWorkflowRunId;
  closeWorkflowRunConfirmation();
  if (id) await runSavedWorkflow(id, true);
}

async function runSavedWorkflow(id, confirmed) {
  try {
    state.savedWorkflows = await backend().RunSavedWorkflowNow({ id, sessionId: state.active?.id || "", confirmed });
    renderAutomation();
    showToast("工作流已加入执行队列");
  } catch (error) {
    showToast(errorText(error), true);
  }
}

function updateAllowlistVisibility() {
  const label = $("#allowlist").closest("label");
  label.classList.toggle("hidden", $("#approval-mode").value !== "allowlist");
}

async function chooseRemoteWorkspace() {
  try {
    const selected = await backend().SelectRemoteWorkspace($("#remote-workspace").value || "");
    if (selected) $("#remote-workspace").value = selected;
  } catch (error) {
    showToast(errorText(error), true);
  }
}

async function shareRemoteDeviceID() {
  const deviceID = state.remoteNode?.config?.deviceDisplayId || "";
  if (!deviceID) return;
  const text = `我的 Murong 本机 ID：${deviceID}`;
  try {
    if (navigator.share) {
      await navigator.share({ title: "Murong 本机 ID", text });
    } else {
      await navigator.clipboard.writeText(text);
      showToast("本机 ID 已复制，可粘贴分享");
    }
  } catch (error) {
    if (error?.name !== "AbortError") showToast(errorText(error), true);
  }
}

function closeRemoteSecurityPassword() {
  $("#remote-security-password").value = "";
  $("#remote-password-modal").classList.add("hidden");
}

async function rotateRemoteTemporaryCode() {
  try {
    state.remoteNode = await backend().RotateRemoteTemporaryCode();
    renderRemoteNode();
    showToast("临时验证码已更换");
  } catch (error) {
    showToast(errorText(error), true);
  }
}

async function saveRemoteSecurityPassword() {
  const password = $("#remote-security-password").value;
  try {
    state.remoteNode = await backend().SetRemoteSecurityPassword(password);
    closeRemoteSecurityPassword();
    renderRemoteNode();
    showToast("安全密码已保存");
  } catch (error) {
    showToast(errorText(error), true);
  }
}

async function clearRemoteSecurityPassword() {
  try {
    state.remoteNode = await backend().ClearRemoteSecurityPassword();
    closeRemoteSecurityPassword();
    renderRemoteNode();
    showToast("安全密码已清除");
  } catch (error) {
    showToast(errorText(error), true);
  }
}

function openRemoteConnectDialog(mode = "device_id") {
  if (state.remoteNode?.status?.running) {
    showToast("请先停止当前远程连接", true);
    return;
  }
  const target = $("#remote-target-device-id").value.trim();
  if (mode !== "adb" && !target) {
    showToast("请输入设备码", true);
    return;
  }
  state.pendingRemoteConnectMode = mode;
  $("#remote-connect-target").textContent = mode === "direct"
    ? `连接同网设备 ${target}`
    : `连接设备 ${target}`;
  $("#remote-connect-secret").value = "";
  $("#remote-connect-modal").classList.remove("hidden");
  renderRemoteConnectDialog();
  $("#remote-connect-secret").focus();
}

function closeRemoteConnectDialog() {
  $("#remote-connect-modal").classList.add("hidden");
  $("#remote-connect-secret").value = "";
}

function renderRemoteConnectDialog() {
  const securityPassword = $("#remote-connect-auth-method").value === "security_password";
  $("#remote-connect-secret-label").textContent = securityPassword ? "安全密码" : "临时验证码";
  $("#remote-connect-secret").placeholder = securityPassword
    ? "请输入对方设备设置的安全密码"
    : "请输入对方设备显示的临时验证码";
}

async function decideRemoteIncoming(decision) {
  const request = state.remoteNode?.connectionRequests?.[0];
  if (!request) return;
  $("#approve-remote-incoming").disabled = true;
  $("#reject-remote-incoming").disabled = true;
  $("#block-remote-incoming").disabled = true;
  try {
    if (decision === true) {
      state.remoteNode = await backend().ApproveRemoteConnectionRequest(request.requestId);
    } else if (decision === "block") {
      state.remoteNode = await backend().BlockRemoteConnectionRequest(request.requestId);
    } else {
      state.remoteNode = await backend().RejectRemoteConnectionRequest(request.requestId);
    }
    renderRemoteNode();
  } catch (error) {
    showToast(errorText(error), true);
  } finally {
    $("#approve-remote-incoming").disabled = false;
    $("#reject-remote-incoming").disabled = false;
    $("#block-remote-incoming").disabled = false;
  }
}

async function updateRemoteDoNotDisturb(event) {
  const enabled = !!event.target.checked;
  event.target.disabled = true;
  try {
    state.remoteNode = await backend().SetRemoteDoNotDisturb(enabled);
    renderRemoteNode(false);
  } catch (error) {
    event.target.checked = !enabled;
    showToast(errorText(error), true);
  } finally {
    event.target.disabled = false;
  }
}

function renderRemoteBlockedPeers() {
  const container = $("#remote-blocked-peers");
  container.replaceChildren();
  const peers = state.remoteNode?.blockedPeers || [];
  if (!peers.length) {
    const empty = document.createElement("p");
    empty.className = "form-hint";
    empty.textContent = "暂无";
    container.append(empty);
    return;
  }
  peers.forEach((peer) => {
    const row = document.createElement("div");
    row.className = "remote-blocked-peer";
    const details = document.createElement("div");
    const name = document.createElement("strong");
    const id = document.createElement("span");
    const unblock = document.createElement("button");
    name.textContent = peer.deviceName || "Murong 设备";
    id.textContent = peer.deviceDisplayId || peer.deviceId || "";
    unblock.type = "button";
    unblock.className = "secondary";
    unblock.textContent = "移出";
    unblock.addEventListener("click", async () => {
      unblock.disabled = true;
      try {
        state.remoteNode = await backend().UnblockRemotePeer(peer.deviceId);
        renderRemoteNode(false);
      } catch (error) {
        showToast(errorText(error), true);
        unblock.disabled = false;
      }
    });
    details.append(name, id);
    row.append(details, unblock);
    container.append(row);
  });
}

function renderRemoteIncomingRequest() {
  const request = state.remoteNode?.connectionRequests?.[0];
  $("#remote-incoming-modal").classList.toggle("hidden", !request);
  if (!request) return;
  $("#remote-incoming-name").textContent = (request.deviceName || "Murong 手机") + " 请求连接这台电脑";
  $("#remote-incoming-device-id").textContent = request.deviceDisplayId || request.deviceId || "";
}

async function confirmRemoteConnect(requestOnly) {
  const secret = $("#remote-connect-secret").value.trim();
  if (!requestOnly && !secret) {
    showToast("请输入临时验证码或安全密码", true);
    return;
  }
  $("#remote-connection-mode").value = state.pendingRemoteConnectMode;
  $("#remote-pair-auth-method").value = $("#remote-connect-auth-method").value;
  $("#remote-pair-code").value = requestOnly ? "" : secret;
  closeRemoteConnectDialog();
  await startRemoteNode();
}

function collectRemoteNodeConfig() {
  const terminals = [...document.querySelectorAll("#remote-terminal-list input[type=checkbox]:checked")].map((input) => input.value);
  return {
    connectionMode: $("#remote-connection-mode").value,
    pairingAuthMethod: $("#remote-pair-auth-method").value,
    phoneUrl: $("#remote-phone").value.trim(),
    adbSerial: $("#remote-adb-serial").value.trim(),
    peerDeviceId: $("#remote-target-device-id").value.trim(),
    peerFingerprint: $("#remote-target-device-id").dataset.fingerprint || "",
    workspace: $("#remote-workspace").value.trim(),
    label: $("#remote-label").value.trim(),
    clientName: $("#remote-client-name").value.trim(),
    allowWrite: $("#remote-allow-write").checked,
    shareDesktopTasks: $("#remote-share-desktop-tasks").checked,
    allowAgentControl: $("#remote-share-desktop-tasks").checked && $("#remote-allow-agent-control").checked,
    terminalBackends: terminals,
    paired: !!state.remoteNode?.config?.paired,
  };
}

async function saveRemoteNodeConfig() {
  try {
    state.remoteNode = await backend().SaveRemoteNodeConfig(collectRemoteNodeConfig());
    renderRemoteNode();
    showToast("远程节点配置已保存");
  } catch (error) {
    showToast(errorText(error), true);
  }
}

async function startRemoteNode() {
  try {
    state.remoteNode = await backend().StartRemoteNode({ config: collectRemoteNodeConfig(), pairCode: $("#remote-pair-code").value.trim() });
    $("#remote-pair-code").value = "";
    renderRemoteNode();
  } catch (error) {
    showToast(errorText(error), true);
  }
}

async function stopRemoteNode() {
  try {
    state.remoteNode = await backend().StopRemoteNode();
    renderRemoteNode();
  } catch (error) {
    showToast(errorText(error), true);
  }
}

function selectedCredentialSyncRequest() {
  return {
    includeSessions: $("#sync-sessions").checked,
    includeProviderCredentials: $("#sync-provider-credentials").checked,
    includeCodexLogin: $("#sync-codex-login").checked,
    includeGitHubCredentials: $("#sync-github-credentials").checked,
    includeAgentSettings: $("#sync-agent-settings").checked,
    includeKnowledge: $("#sync-knowledge").checked,
    includeMcp: $("#sync-mcp").checked,
    includeMcpCredentials: $("#sync-mcp").checked && $("#sync-mcp-credentials").checked,
    includeSavedWorkflows: $("#sync-saved-workflows").checked,
  };
}

function openCredentialSyncConfirmation(direction) {
  const node = state.remoteNode || {};
  const config = node.config || {};
  const status = node.status || {};
  if (status.phase !== "connected") {
    showToast("请先启动远程节点并连接手机", true);
    return;
  }
  if (!config.secureSyncReady) {
    showToast("当前是旧版配对，请停止节点、清除配对并使用新版手机重新配对", true);
    return;
  }
  const request = selectedCredentialSyncRequest();
  if (!request.includeSessions && !request.includeProviderCredentials && !request.includeCodexLogin && !request.includeGitHubCredentials && !request.includeAgentSettings
    && !request.includeKnowledge && !request.includeMcp && !request.includeSavedWorkflows) {
    showToast("至少选择一项要同步的内容", true);
    return;
  }
  state.pendingCredentialSyncDirection = direction;
  const toPhone = direction === "to_phone";
  $("#credential-sync-title").textContent = toPhone ? "确认同步到手机" : "确认从手机同步";
  $("#credential-sync-confirm-summary").textContent = toPhone
    ? "电脑端将加密发送以下内容，手机端验证并保存。"
    : "手机端将加密发送以下内容，电脑端验证并保存。";
  const list = $("#credential-sync-confirm-list");
  list.replaceChildren();
  if (request.includeSessions) {
    const item = document.createElement("li");
    item.textContent = "聊天记录（重复内容跳过，分歧内容保留冲突副本）";
    list.append(item);
  }
  if (request.includeProviderCredentials) {
    const item = document.createElement("li");
    item.textContent = "模型连接、模型参数与非空 API Key";
    list.append(item);
  }
  if (request.includeCodexLogin) {
    const item = document.createElement("li");
    item.textContent = "ChatGPT / Codex 完整账号池（接收端校验后写入本机安全存储）";
    list.append(item);
  }
  if (request.includeGitHubCredentials) {
    const item = document.createElement("li");
    item.textContent = "GitHub 完整账号池、API 地址与访问令牌（接收后进入安全存储）";
    list.append(item);
  }
  if (request.includeAgentSettings) {
    const item = document.createElement("li");
    item.textContent = "审批模式、系统提示词、回复详细度、生成参数与图片消息开关";
    list.append(item);
  }
  if (request.includeKnowledge) {
    const item = document.createElement("li");
    item.textContent = "全局规则、记忆与 Skills";
    list.append(item);
  }
  if (request.includeMcp) {
    const item = document.createElement("li");
    item.textContent = request.includeMcpCredentials ? "MCP 配置及其环境变量/请求头凭据" : "MCP 配置（保留目标设备已有凭据）";
    list.append(item);
  }
  if (request.includeSavedWorkflows) {
    const item = document.createElement("li");
    item.textContent = "保存的工作流（导入后不自动启用）";
    list.append(item);
  }
  $("#credential-sync-modal").classList.remove("hidden");
}

function closeCredentialSyncConfirmation() {
  if (state.credentialSyncRunning) return;
  state.pendingCredentialSyncDirection = "";
  $("#credential-sync-modal").classList.add("hidden");
}

async function performCredentialSync() {
  const direction = state.pendingCredentialSyncDirection;
  if (!direction || state.credentialSyncRunning) return;
  state.credentialSyncRunning = true;
  $("#confirm-credential-sync").disabled = true;
  $("#cancel-credential-sync").disabled = true;
  $("#credential-sync-status").textContent = direction === "to_phone" ? "正在加密同步到手机并验证…" : "正在从手机接收并验证…";
  renderCredentialSyncState();
  try {
    const request = selectedCredentialSyncRequest();
    const result = direction === "to_phone"
      ? await backend().PushCredentialsToPhone(request)
      : await backend().PullCredentialsFromPhone(request);
    if (result.config) {
      state.config = result.config;
      renderSettings();
    }
    if (result.codex) {
      state.codex = result.codex;
      renderCodexProviderStatus();
    }
    state.savedWorkflows = await backend().GetSavedWorkflowState();
    renderAutomation();
    const parts = [];
    if (result.importedSessions) parts.push(`${result.importedSessions} 个聊天记录`);
    if (result.conflictSessions) parts.push(`${result.conflictSessions} 个聊天冲突副本`);
    if (result.skippedSessions) parts.push(`${result.skippedSessions} 个相同聊天已跳过`);
    if (result.importedProviders) parts.push(`${result.importedProviders} 个模型连接`);
    if (result.importedApiKeys) parts.push(`${result.importedApiKeys} 个 API Key`);
    if (result.importedCodexAccounts) parts.push(`${result.importedCodexAccounts} 个 Codex 账号${result.accountEmail ? `（当前 ${result.accountEmail}）` : ""}`);
    else if (result.importedCodexLogin) parts.push(`Codex 登录${result.accountEmail ? `（${result.accountEmail}）` : ""}`);
    if (result.importedGitHubAccounts) parts.push(`${result.importedGitHubAccounts} 个 GitHub 账号`);
    else if (result.importedGitHubToken) parts.push("GitHub Token");
    if (result.importedSettings) parts.push("Agent 通用设置");
    if (result.importedRules) parts.push(`${result.importedRules} 条规则`);
    if (result.importedMemories) parts.push(`${result.importedMemories} 条记忆`);
    if (result.importedSkills) parts.push(`${result.importedSkills} 个 Skill`);
    if (result.importedMcpServers) parts.push(`${result.importedMcpServers} 个 MCP${result.disabledMcpServers ? `（${result.disabledMcpServers} 个 stdio 已关闭）` : ""}`);
    if (result.importedWorkflows) parts.push(`${result.importedWorkflows} 个工作流`);
    if (result.skippedWorkflows) parts.push(`${result.skippedWorkflows} 个路径工作流已跳过`);
    const message = `同步完成：${parts.length ? parts.join("、") : "所选内容在源设备上为空"}`;
    $("#credential-sync-status").textContent = message;
    showToast(message);
    state.pendingCredentialSyncDirection = "";
    $("#credential-sync-modal").classList.add("hidden");
  } catch (error) {
    const message = errorText(error);
    $("#credential-sync-status").textContent = `同步失败：${message}`;
    showToast(message, true);
  } finally {
    state.credentialSyncRunning = false;
    $("#confirm-credential-sync").disabled = false;
    $("#cancel-credential-sync").disabled = false;
    renderCredentialSyncState();
  }
}

function renderCredentialSyncState() {
  const node = state.remoteNode || {};
  const config = node.config || {};
  const connected = node.status?.phase === "connected";
  const ready = connected && !!config.secureSyncReady;
  const badge = $("#credential-sync-badge");
  badge.classList.toggle("ready", ready);
  badge.textContent = ready ? "端到端加密已就绪" : config.paired && !config.secureSyncReady ? "旧配对需重配" : "等待安全配对";
  $("#push-credentials").disabled = !ready || state.credentialSyncRunning;
  $("#pull-credentials").disabled = !ready || state.credentialSyncRunning;
  $("#sync-sessions").disabled = state.credentialSyncRunning;
  $("#sync-provider-credentials").disabled = state.credentialSyncRunning;
  $("#sync-codex-login").disabled = state.credentialSyncRunning;
  $("#sync-github-credentials").disabled = state.credentialSyncRunning;
  $("#sync-agent-settings").disabled = state.credentialSyncRunning;
  $("#sync-knowledge").disabled = state.credentialSyncRunning;
  $("#sync-mcp").disabled = state.credentialSyncRunning;
  $("#sync-mcp-credentials").disabled = state.credentialSyncRunning || !$("#sync-mcp").checked;
  $("#sync-saved-workflows").disabled = state.credentialSyncRunning;
  if (!state.credentialSyncRunning && !ready) {
    $("#credential-sync-status").textContent = config.paired && !config.secureSyncReady
      ? "该配对来自旧协议：停止节点并清除配对后，用新版手机重新配对即可同步凭据。"
      : "连接手机后可选择同步内容；敏感项不会进入普通备份包。";
  }
}

function renderRemoteNode(updateFields = true) {
  const node = state.remoteNode || {};
  const config = node.config || {};
  const status = node.status || {};
  const running = !!status.running;
  $("#remote-status").textContent = status.message || "远程控制已停止";
  const connectionMode = config.connectionMode || "direct";
  $("#remote-status-detail").textContent = running
    ? "正在连接或已连接所选设备。"
    : "输入设备码，或从当前环境选择设备。";
  $("#remote-device-id").textContent = config.deviceDisplayId || "正在初始化";
  $("#copy-remote-device-id").disabled = !config.deviceDisplayId;
  $("#share-remote-device-id").disabled = !config.deviceDisplayId;
  $("#remote-temporary-code").textContent = node.temporaryCode || "正在生成";
  $("#copy-remote-temporary-code").disabled = !node.temporaryCode;
  $("#change-remote-security-password").textContent = node.securityPasswordConfigured ? "更改安全密码" : "更改为安全密码";
  $("#clear-remote-security-password").disabled = !node.securityPasswordConfigured;
  $("#remote-paired-device").textContent = config.paired
    ? config.peerDeviceDisplayId || "已配对设备"
    : "暂无";
  $("#remote-status-dot").classList.toggle("online", status.phase === "connected");
  renderRemoteIncomingRequest();
  $("#node-dot").classList.toggle("online", running);
  $("#stop-remote").disabled = !running;
  $("#reconnect-remote-paired").disabled = running || !config.paired;
  $("#clear-remote-pairing").disabled = running || !config.paired;
  $("#remote-do-not-disturb").checked = !!node.doNotDisturb;
  renderRemoteBlockedPeers();
  document.querySelectorAll(".remote-form-card input, .remote-form-card button, .remote-form-card select").forEach((element) => { element.disabled = running; });
  renderCredentialSyncState();
  if (!updateFields) return;
  $("#remote-connection-mode").value = connectionMode;
  $("#remote-pair-auth-method").value = config.pairingAuthMethod || "temporary_code";
  $("#remote-phone").value = config.phoneUrl || "";
  $("#remote-adb-serial").value = config.adbSerial || "";
  $("#remote-target-device-id").value = config.peerDeviceDisplayId || "";
  $("#remote-target-device-id").dataset.fingerprint = config.peerFingerprint || "";
  $("#remote-workspace").value = config.workspace || "";
  $("#remote-label").value = config.label || "";
  $("#remote-client-name").value = config.clientName || "";
  $("#remote-allow-write").checked = !!config.allowWrite;
  $("#remote-share-desktop-tasks").checked = !!config.shareDesktopTasks;
  $("#remote-allow-agent-control").checked = !!config.allowAgentControl;
  $("#remote-allow-agent-control").disabled = running || !config.shareDesktopTasks;
  renderRemoteTerminalOptions(node.terminals || [], config.terminalBackends || [], running);
  renderRemoteEnvironmentDevices();
}

async function refreshRemoteEnvironment(force) {
  if (state.remoteDiscoveryBusy || state.remoteAdbDiscoveryBusy) return;
  const now = Date.now();
  if (!force && now - Math.min(state.remoteDiscoveryLastAt, state.remoteAdbDiscoveryLastAt) < 8000) return;
  state.remoteDiscoveryBusy = true;
  state.remoteAdbDiscoveryBusy = true;
  state.remoteDiscoveryLastAt = now;
  state.remoteAdbDiscoveryLastAt = now;
  renderRemoteEnvironmentDevices();
  const [lan, adb] = await Promise.allSettled([
    backend().DiscoverRemoteDevices(),
    backend().DiscoverRemoteADBDevices(),
  ]);
  state.remoteDevices = lan.status === "fulfilled" ? lan.value || [] : [];
  state.remoteAdbDevices = adb.status === "fulfilled" ? adb.value || [] : [];
  state.remoteDiscoveryBusy = false;
  state.remoteAdbDiscoveryBusy = false;
  renderRemoteEnvironmentDevices();
  if (force && lan.status === "rejected" && adb.status === "rejected") {
    showToast("未能读取当前环境设备", true);
  }
}

function renderRemoteEnvironmentDevices() {
  const container = $("#remote-environment-devices");
  if (!container) return;
  const busy = state.remoteDiscoveryBusy || state.remoteAdbDiscoveryBusy;
  $("#refresh-remote-environment").disabled = busy || !!state.remoteNode?.status?.running;
  container.replaceChildren();
  if (busy) {
    const note = document.createElement("p");
    note.className = "form-hint";
    note.textContent = "正在查找当前环境中的设备…";
    container.append(note);
    return;
  }
  const devices = [
    ...state.remoteDevices.map((device) => ({
      kind: "direct",
      title: device.name || "Murong Android",
      detail: `${device.deviceDisplayId} · 同一网络`,
      device,
      available: true,
    })),
    ...state.remoteAdbDevices.map((device) => ({
      kind: "adb",
      title: device.model || device.product || device.serial,
      detail: `${device.serial} · ${device.authorized ? "ADB 已授权" : "等待 ADB 授权"}`,
      device,
      available: !!device.authorized,
    })),
  ];
  if (!devices.length) {
    const note = document.createElement("p");
    note.className = "form-hint";
    note.textContent = "暂未发现设备，也可以直接输入设备码连接。";
    container.append(note);
    return;
  }
  devices.forEach((item) => {
    const row = document.createElement("div");
    row.className = "remote-environment-device";
    const copy = document.createElement("div");
    const title = document.createElement("strong");
    const detail = document.createElement("span");
    const connect = document.createElement("button");
    title.textContent = item.title;
    detail.textContent = item.detail;
    connect.type = "button";
    connect.className = "secondary";
    connect.textContent = "立即连接";
    connect.disabled = !item.available;
    copy.append(title, detail);
    row.append(copy, connect);
    connect.addEventListener("click", async () => {
      if (item.kind === "adb") {
        $("#remote-connection-mode").value = "adb";
        $("#remote-adb-serial").value = item.device.serial;
        $("#remote-target-device-id").value = "";
        $("#remote-target-device-id").dataset.fingerprint = "";
        await startRemoteNode();
        return;
      }
      $("#remote-connection-mode").value = "direct";
      $("#remote-phone").value = item.device.url;
      $("#remote-target-device-id").value = item.device.deviceDisplayId;
      $("#remote-target-device-id").dataset.fingerprint = item.device.fingerprint || "";
      $("#remote-pair-code").value = "";
      await startRemoteNode();
    });
    container.append(row);
  });
}

const workbenchToolLabels = {
  terminal: "终端", browser: "浏览器", editor: "编辑器", git: "Git", subagents: "子代理", sidechat: "侧边聊天",
};
const defaultWorkbenchWidth = 620;
const minimumWorkbenchWidth = 340;
const minimumChatViewportWidth = 480;
const defaultContentPadding = 48;

function defaultSidebarWidth() {
  return window.innerWidth <= 1180 ? 244 : 280;
}

function clampInteger(value, minimum, maximum, fallback) {
  const parsed = value == null || String(value).trim() === "" ? fallback : Number(value);
  return Math.round(Math.min(maximum, Math.max(minimum, Number.isFinite(parsed) ? parsed : fallback)));
}

function workbenchWidthBounds() {
  const sidebarWidth = state.layout.sidebarWidth || defaultSidebarWidth();
  return {
    minimum: minimumWorkbenchWidth,
    // The old 320px reserve was smaller than the chat composer and message
    // layout.  At the largest allowed editor width the CSS grid therefore
    // overflowed into column two and appeared to cover the conversation.
    maximum: Math.max(minimumWorkbenchWidth, Math.min(960, window.innerWidth - sidebarWidth - minimumChatViewportWidth)),
  };
}

function clampWorkbenchWidth(value) {
  const bounds = workbenchWidthBounds();
  const parsed = value == null || String(value).trim() === "" ? defaultWorkbenchWidth : Number(value);
  return Math.round(Math.min(bounds.maximum, Math.max(bounds.minimum, Number.isFinite(parsed) ? parsed : defaultWorkbenchWidth)));
}

function applyWorkbenchWidth() {
  state.workbench.width = clampWorkbenchWidth(state.workbench.width);
  $("#app")?.style.setProperty("--workbench-width", `${state.workbench.width}px`);
  $("#workbench-resizer")?.setAttribute("aria-valuenow", String(state.workbench.width));
}

function applyLayoutPreferences() {
  state.layout.sidebarWidth = clampInteger(state.layout.sidebarWidth, 220, 360, defaultSidebarWidth());
  state.layout.contentPadding = clampInteger(state.layout.contentPadding, 16, 96, defaultContentPadding);
  document.documentElement.style.setProperty("--sidebar-width", `${state.layout.sidebarWidth}px`);
  document.documentElement.style.setProperty("--content-padding", `${state.layout.contentPadding}px`);
  applyWorkbenchWidth();
}

function restoreWorkbenchPreferences() {
  try {
    const dock = localStorage.getItem("murong.workbench.terminalDock");
    if (dock === "side" || dock === "bottom") state.workbench.terminalDock = dock;
    state.workbench.defaultTerminalId = localStorage.getItem("murong.workbench.defaultTerminalId") || "";
    state.workbench.width = clampWorkbenchWidth(localStorage.getItem("murong.workbench.width"));
    state.layout.sidebarWidth = clampInteger(localStorage.getItem("murong.layout.sidebarWidth"), 220, 360, defaultSidebarWidth());
    state.layout.contentPadding = clampInteger(localStorage.getItem("murong.layout.contentPadding"), 16, 96, defaultContentPadding);
  } catch (_) {
    state.workbench.terminalDock = "side";
    state.workbench.defaultTerminalId = "";
    state.workbench.width = clampWorkbenchWidth(defaultWorkbenchWidth);
    state.layout.sidebarWidth = defaultSidebarWidth();
    state.layout.contentPadding = defaultContentPadding;
  }
  applyLayoutPreferences();
}

function persistWorkbenchPreferences() {
  try {
    localStorage.setItem("murong.workbench.terminalDock", state.workbench.terminalDock);
    localStorage.setItem("murong.workbench.defaultTerminalId", state.workbench.defaultTerminalId || "");
    localStorage.setItem("murong.workbench.width", String(state.workbench.width));
    localStorage.setItem("murong.layout.sidebarWidth", String(state.layout.sidebarWidth));
    localStorage.setItem("murong.layout.contentPadding", String(state.layout.contentPadding));
  } catch (_) {}
}

function applyLayoutSettingsFromForm() {
  state.layout.sidebarWidth = clampInteger($("#sidebar-width-setting").value, 220, 360, defaultSidebarWidth());
  state.layout.contentPadding = clampInteger($("#content-padding-setting").value, 16, 96, defaultContentPadding);
  state.workbench.width = clampWorkbenchWidth($("#workbench-width-setting").value);
  applyLayoutPreferences();
  persistWorkbenchPreferences();
  renderSettings();
  showToast("界面布局已保存");
}

function resetLayoutSettings() {
  state.layout.sidebarWidth = defaultSidebarWidth();
  state.layout.contentPadding = defaultContentPadding;
  state.workbench.width = clampWorkbenchWidth(defaultWorkbenchWidth);
  applyLayoutPreferences();
  persistWorkbenchPreferences();
  renderSettings();
  showToast("界面布局已恢复默认");
}

function beginWorkbenchResize(event) {
  if (event.button !== 0 || state.workbench.terminalDock === "bottom" && activeWorkbenchTab()?.type === "terminal") return;
  event.preventDefault();
  state.workbench.resizing = { pointerId: event.pointerId, startX: event.clientX, startWidth: state.workbench.width };
  event.currentTarget.setPointerCapture?.(event.pointerId);
  document.body.classList.add("workbench-resizing");
}

function continueWorkbenchResize(event) {
  const resize = state.workbench.resizing;
  if (!resize || resize.pointerId !== event.pointerId) return;
  state.workbench.width = clampWorkbenchWidth(resize.startWidth + resize.startX - event.clientX);
  applyWorkbenchWidth();
}

function finishWorkbenchResize(event) {
  const resize = state.workbench.resizing;
  if (!resize || resize.pointerId !== event.pointerId) return;
  state.workbench.resizing = null;
  event.currentTarget.releasePointerCapture?.(event.pointerId);
  document.body.classList.remove("workbench-resizing");
  persistWorkbenchPreferences();
}

function resetWorkbenchWidth() {
  state.workbench.width = clampWorkbenchWidth(defaultWorkbenchWidth);
  applyWorkbenchWidth();
  persistWorkbenchPreferences();
}

function resizeWorkbenchFromKeyboard(event) {
  if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
  event.preventDefault();
  state.workbench.width = clampWorkbenchWidth(state.workbench.width + (event.key === "ArrowLeft" ? 24 : -24));
  applyWorkbenchWidth();
  persistWorkbenchPreferences();
}

function toggleWorkbench() {
  if (state.workbench.open) {
    closeWorkbench();
    return;
  }
  state.workbench.open = true;
  renderWorkbench();
}

function toggleSideWorkbench() {
  const current = activeWorkbenchTab();
  const showingSide = state.workbench.open && !(current?.type === "terminal" && state.workbench.terminalDock === "bottom");
  if (showingSide) {
    closeWorkbench();
    return;
  }
  const sideTab = state.workbench.tabs.find((tab) => tab.type !== "terminal");
  if (sideTab) {
    state.workbench.open = true;
    state.workbench.activeId = sideTab.id;
    renderWorkbench();
    return;
  }
  openWorkbenchTab("editor");
}

function toggleBottomTerminal() {
  const current = activeWorkbenchTab();
  const showingBottom = state.workbench.open && current?.type === "terminal" && state.workbench.terminalDock === "bottom";
  if (showingBottom) {
    closeWorkbench();
    return;
  }
  state.workbench.terminalDock = "bottom";
  persistWorkbenchPreferences();
  const terminalTab = state.workbench.tabs.find((tab) => tab.type === "terminal");
  if (terminalTab) {
    state.workbench.open = true;
    state.workbench.activeId = terminalTab.id;
    renderWorkbench();
    return;
  }
  openWorkbenchTab("terminal");
}

function closeWorkbench() {
  closeWorkbenchAddMenu();
  state.workbench.open = false;
  renderWorkbench();
}

function toggleWorkbenchAddMenu(event) {
  event?.stopPropagation();
  if (!state.workbench.open) {
    state.workbench.open = true;
    renderWorkbench();
  }
  const menu = $("#workbench-add-menu");
  const opening = menu.classList.contains("hidden");
  menu.classList.toggle("hidden", !opening);
  $("#workbench-add-tab").setAttribute("aria-expanded", String(opening));
}

function closeWorkbenchAddMenu() {
  const menu = $("#workbench-add-menu");
  if (!menu) return;
  menu.classList.add("hidden");
  $("#workbench-add-tab")?.setAttribute("aria-expanded", "false");
}

function closeWorkbenchAddMenuFromOutside(event) {
  const menu = $("#workbench-add-menu");
  if (!menu || menu.classList.contains("hidden")) return;
  if (menu.contains(event.target) || $("#workbench-add-tab").contains(event.target)) return;
  closeWorkbenchAddMenu();
}

function handleWorkbenchShortcut(event) {
  if (event.key === "Escape") {
    closeWorkbenchAddMenu();
    return;
  }
  if (!event.ctrlKey || event.shiftKey || event.metaKey) return;
  let tool = "";
  if (!event.altKey && event.key.toLowerCase() === "p") tool = "editor";
  else if (event.altKey && event.key.toLowerCase() === "s") tool = "sidechat";
  else if (!event.altKey && event.key.toLowerCase() === "t") tool = "browser";
  else if (!event.altKey && (event.key === "`" || event.code === "Backquote")) tool = "terminal";
  if (!tool) return;
  event.preventDefault();
  closeWorkbenchAddMenu();
  openWorkbenchTab(tool);
}

function openWorkbenchTab(type) {
  if (!workbenchToolLabels[type]) return;
  state.workbench.open = true;
  const number = ++state.workbench.sequence;
  const tab = {
    id: `workbench_${type}_${Date.now()}_${number}`,
    type,
    title: workbenchToolLabels[type],
  };
  if (type === "terminal") {
    const preferred = state.terminals.find((terminal) => terminal.id === state.workbench.defaultTerminalId) || state.terminals[0];
    tab.backendId = preferred?.id || "";
    tab.sessionId = "";
    tab.starting = true;
    tab.exited = false;
    tab.title = preferred?.label || "终端";
  } else if (type === "browser") {
    tab.url = "";
    tab.history = [];
    tab.historyIndex = -1;
    tab.title = "新标签页";
  } else if (type === "editor") {
    tab.directory = ".";
    tab.entries = [];
    tab.document = null;
    tab.openFiles = [];
    tab.activeFilePath = "";
    tab.loading = true;
  } else if (type === "git") {
    tab.loading = true;
    tab.snapshot = null;
  } else if (type === "sidechat") {
    tab.sessionId = state.active?.id || state.sessions[0]?.id || "";
    tab.session = tab.sessionId === state.active?.id ? state.active : null;
    tab.title = tab.session?.title || "侧边任务";
  }
  state.workbench.tabs.push(tab);
  state.workbench.activeId = tab.id;
  renderWorkbench();
  if (type === "terminal") startWorkbenchTerminal(tab.id);
  if (type === "editor") loadWorkbenchEditorDirectory(tab.id, ".");
  if (type === "git") loadWorkbenchGit(tab.id);
  if (type === "sidechat") refreshWorkbenchSideChat();
  return tab;
}

function activeWorkbenchTab(expectedType = "") {
  const tab = state.workbench.tabs.find((item) => item.id === state.workbench.activeId) || null;
  return tab && (!expectedType || tab.type === expectedType) ? tab : null;
}

function activateWorkbenchTab(id) {
  if (!state.workbench.tabs.some((tab) => tab.id === id)) return;
  state.workbench.activeId = id;
  renderWorkbench();
  const tab = activeWorkbenchTab();
  if (tab?.type === "sidechat" && !tab.session) refreshWorkbenchSideChat();
}

function closeWorkbenchTab(id) {
  const index = state.workbench.tabs.findIndex((tab) => tab.id === id);
  if (index < 0) return;
  disposeWorkbenchTerminal(state.workbench.tabs[index]);
  state.workbench.tabs.splice(index, 1);
  if (state.workbench.activeId === id) {
    state.workbench.activeId = state.workbench.tabs[Math.min(index, state.workbench.tabs.length - 1)]?.id || "";
  }
  renderWorkbench();
}

function updateTerminalDockPreference(event) {
  state.workbench.terminalDock = event.currentTarget.value === "bottom" ? "bottom" : "side";
  persistWorkbenchPreferences();
  renderWorkbench();
}

function updateDefaultTerminalPreference(event) {
  state.workbench.defaultTerminalId = event.currentTarget.value;
  persistWorkbenchPreferences();
}

function renderWorkbench() {
  const panel = $("#workbench-panel");
  const current = activeWorkbenchTab();
  const open = state.workbench.open;
  applyWorkbenchWidth();
  panel.classList.toggle("hidden", !open);
  const bottomTerminal = open && current?.type === "terminal" && state.workbench.terminalDock === "bottom";
  const sideWorkbench = open && !bottomTerminal;
  $("#toggle-workbench").classList.toggle("active", sideWorkbench);
  $("#toggle-workbench").setAttribute("aria-expanded", String(sideWorkbench));
  $("#toggle-workbench").title = sideWorkbench ? "收起电脑侧栏" : "展开电脑侧栏";
  $("#toggle-workbench").setAttribute("aria-label", $("#toggle-workbench").title);
  $("#toggle-bottom-terminal").classList.toggle("active", bottomTerminal);
  $("#toggle-bottom-terminal").setAttribute("aria-expanded", String(bottomTerminal));
  $("#toggle-bottom-terminal").title = bottomTerminal ? "收起底部终端" : "打开底部终端";
  $("#toggle-bottom-terminal").setAttribute("aria-label", $("#toggle-bottom-terminal").title);
  $("#app").classList.toggle("workbench-open", open && !bottomTerminal);
  $("#app").classList.toggle("workbench-terminal-bottom", bottomTerminal);
  if ($("#terminal-dock-setting")) $("#terminal-dock-setting").value = state.workbench.terminalDock;
  const strip = $("#workbench-tabstrip");
  strip.replaceChildren();
  state.workbench.tabs.forEach((tab) => {
    const tabElement = document.createElement("div");
    tabElement.className = `workbench-tab${tab.id === state.workbench.activeId ? " active" : ""}`;
    tabElement.tabIndex = 0;
    tabElement.setAttribute("role", "tab");
    tabElement.setAttribute("aria-selected", String(tab.id === state.workbench.activeId));
    const label = document.createElement("span");
    label.textContent = tab.title;
    const close = document.createElement("button");
    close.type = "button";
    close.title = "关闭标签";
    close.textContent = "×";
    close.addEventListener("click", (event) => { event.stopPropagation(); closeWorkbenchTab(tab.id); });
    tabElement.addEventListener("click", () => activateWorkbenchTab(tab.id));
    tabElement.addEventListener("keydown", (event) => {
      if (event.key !== "Enter" && event.key !== " ") return;
      event.preventDefault();
      activateWorkbenchTab(tab.id);
    });
    tabElement.append(label, close);
    strip.append(tabElement);
  });
  const sections = ["empty", "terminal", "browser", "editor", "git", "subagents", "sidechat"];
  sections.forEach((name) => $("#workbench-" + name).classList.toggle("hidden", name !== (current?.type || "empty")));
  if (!open || !current) return;
  if (current.type === "terminal") renderWorkbenchTerminal(current);
  else if (current.type === "browser") renderWorkbenchBrowser(current);
  else if (current.type === "editor") renderWorkbenchEditor(current);
  else if (current.type === "git") renderWorkbenchGit(current);
  else if (current.type === "subagents") renderWorkbenchSubagents();
  else if (current.type === "sidechat") renderWorkbenchSideChat(current);
}

function renderWorkbenchTerminal(tab) {
  ensureWorkbenchTerminalView(tab);
  const host = $("#workbench-terminal-host");
  host.querySelectorAll(".workbench-terminal-instance").forEach((element) => {
    element.classList.toggle("hidden", element.dataset.tabId !== tab.id);
  });
  const terminal = state.terminals.find((candidate) => candidate.id === tab.backendId);
  $("#workbench-terminal-label").textContent = terminal?.label || tab.title || "终端";
  $("#workbench-terminal-directory").textContent = tab.directory || state.config?.projectPath || "尚未选择项目";
  $("#workbench-terminal-placeholder").classList.toggle("hidden", Boolean(tab.terminalElement));
  requestAnimationFrame(() => fitWorkbenchTerminal(tab));
}

function clearWorkbenchTerminal() {
  const tab = activeWorkbenchTab("terminal");
  if (!tab) return;
  tab.terminalView?.clear();
}

function ensureWorkbenchTerminalView(tab) {
  if (tab.terminalView || !window.MurongWorkbenchVendor) return;
  const host = $("#workbench-terminal-host");
  const element = document.createElement("div");
  element.className = "workbench-terminal-instance";
  element.dataset.tabId = tab.id;
  host.append(element);
  const fitAddon = new window.MurongWorkbenchVendor.FitAddon();
  const terminalView = new window.MurongWorkbenchVendor.Terminal({
    cursorBlink: true,
    cursorStyle: "bar",
    fontFamily: '"Cascadia Mono", Consolas, "Microsoft YaHei UI", monospace',
    fontSize: 12,
    lineHeight: 1.18,
    scrollback: 12000,
    allowTransparency: false,
    theme: {
      background: "#ffffff", foreground: "#24272d", cursor: "#c5427e", cursorAccent: "#ffffff",
      selectionBackground: "#efc5d9", black: "#24272d", red: "#c43f54", green: "#317a4d",
      yellow: "#936b18", blue: "#356db0", magenta: "#a33d88", cyan: "#207c86", white: "#d7d9de",
      brightBlack: "#747b87", brightRed: "#e14e65", brightGreen: "#3e9a60", brightYellow: "#b78620",
      brightBlue: "#4c86ce", brightMagenta: "#c553a3", brightCyan: "#2d9ba6", brightWhite: "#282b31"
    }
  });
  terminalView.loadAddon(fitAddon);
  terminalView.open(element);
  tab.terminalElement = element;
  tab.terminalView = terminalView;
  tab.fitAddon = fitAddon;
  tab.terminalInput = terminalView.onData((data) => {
    if (!tab.sessionId) return;
    backend().WriteWorkbenchTerminalSession({ sessionId: tab.sessionId, data }).catch((error) => {
      terminalView.writeln(`\r\n[终端输入失败] ${errorText(error)}`);
    });
  });
  tab.terminalResize = terminalView.onResize(({ cols, rows }) => {
    if (tab.sessionId) backend().ResizeWorkbenchTerminalSession({ sessionId: tab.sessionId, columns: cols, rows }).catch(() => {});
  });
  tab.resizeObserver = new ResizeObserver(() => {
    if (activeWorkbenchTab()?.id === tab.id && state.workbench.open) fitWorkbenchTerminal(tab);
  });
  tab.resizeObserver.observe(element);
}

function fitWorkbenchTerminal(tab) {
  if (!tab?.fitAddon || !tab.terminalElement || tab.terminalElement.classList.contains("hidden")) return;
  try {
    tab.fitAddon.fit();
    tab.terminalView.focus();
  } catch (_) {}
}

async function startWorkbenchTerminal(tabID) {
  const tab = state.workbench.tabs.find((item) => item.id === tabID && item.type === "terminal");
  if (!tab) return;
  ensureWorkbenchTerminalView(tab);
  if (!tab.terminalView) {
    tab.starting = false;
    showToast("终端组件未能加载", true);
    return;
  }
  if (!state.config?.projectPath) {
    tab.starting = false;
    tab.exited = true;
    tab.terminalView.writeln("请先选择项目文件夹，再打开终端。");
    return;
  }
  fitWorkbenchTerminal(tab);
  try {
    const info = await backend().StartWorkbenchTerminalSession({
      clientId: tab.id,
      terminalId: tab.backendId,
      directory: ".",
      columns: tab.terminalView.cols || 80,
      rows: tab.terminalView.rows || 24
    });
    if (!state.workbench.tabs.includes(tab)) {
      await backend().CloseWorkbenchTerminalSession(info.sessionId);
      return;
    }
    tab.sessionId = info.sessionId;
    tab.directory = info.directory;
    tab.title = info.label || tab.title;
    tab.starting = false;
    tab.exited = false;
    if (activeWorkbenchTab()?.id === tab.id) renderWorkbench();
    tab.terminalView.focus();
  } catch (error) {
    tab.starting = false;
    tab.exited = true;
    tab.terminalView.writeln(`[终端启动失败] ${errorText(error)}`);
    showToast(errorText(error), true);
  }
}

function disposeWorkbenchTerminal(tab) {
  if (!tab || tab.type !== "terminal") return;
  if (tab.sessionId) backend().CloseWorkbenchTerminalSession(tab.sessionId).catch(() => {});
  tab.resizeObserver?.disconnect();
  tab.terminalInput?.dispose();
  tab.terminalResize?.dispose();
  tab.terminalView?.dispose();
  tab.terminalElement?.remove();
  tab.sessionId = "";
}

function workbenchTerminalTab(payload) {
  return state.workbench.tabs.find((tab) => tab.type === "terminal" && (
    (payload?.sessionId && tab.sessionId === payload.sessionId) || (payload?.clientId && tab.id === payload.clientId)
  ));
}

function decodeBase64Bytes(value) {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

function normalizeWorkbenchURL(value) {
  value = value.trim();
  if (!/^[a-z][a-z0-9+.-]*:/i.test(value)) value = "https://" + value;
  const parsed = new URL(value);
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") throw new Error("只允许 HTTP 或 HTTPS 地址");
  return parsed.href;
}

function renderWorkbenchBrowser(tab) {
  $("#workbench-browser-url").value = tab.url || "";
  $("#workbench-browser-back").disabled = tab.historyIndex <= 0;
  $("#workbench-browser-forward").disabled = tab.historyIndex < 0 || tab.historyIndex >= tab.history.length - 1;
  const frame = $("#workbench-browser-frame");
  const hasURL = Boolean(tab.url);
  frame.classList.toggle("hidden", !hasURL);
  $("#workbench-browser-placeholder").classList.toggle("hidden", hasURL);
  if (hasURL && frame.dataset.url !== tab.url) {
    frame.dataset.url = tab.url;
    frame.src = tab.url;
  }
}

function navigateWorkbenchBrowser(event) {
  event.preventDefault();
  const tab = activeWorkbenchTab("browser");
  if (!tab) return;
  try {
    const url = normalizeWorkbenchURL($("#workbench-browser-url").value);
    tab.history = tab.history.slice(0, tab.historyIndex + 1);
    tab.history.push(url);
    tab.historyIndex = tab.history.length - 1;
    tab.url = url;
    tab.title = new URL(url).hostname || "浏览器";
    renderWorkbench();
  } catch (error) {
    showToast(errorText(error), true);
  }
}

function navigateWorkbenchBrowserHistory(offset) {
  const tab = activeWorkbenchTab("browser");
  if (!tab) return;
  const next = tab.historyIndex + offset;
  if (next < 0 || next >= tab.history.length) return;
  tab.historyIndex = next;
  tab.url = tab.history[next];
  tab.title = new URL(tab.url).hostname || "浏览器";
  renderWorkbench();
}

function refreshWorkbenchBrowser() {
  const tab = activeWorkbenchTab("browser");
  if (!tab?.url) return;
  const frame = $("#workbench-browser-frame");
  frame.src = tab.url;
}

function openWorkbenchBrowserExternal() {
  const tab = activeWorkbenchTab("browser");
  if (tab?.url) window.runtime?.BrowserOpenURL(tab.url);
}

function syncActiveWorkbenchFile(tab) {
  if (!tab?.activeFilePath) return;
  const file = tab.openFiles?.find((candidate) => candidate.path === tab.activeFilePath);
  if (!file) return;
  file.document = tab.document || null;
  file.asset = tab.asset || null;
  file.dirty = Boolean(tab.dirty);
  file.markdownMode = tab.markdownMode || "";
  file.reviewDiff = tab.reviewDiff || null;
}

function activateWorkbenchFile(tab, path) {
  if (!tab) return false;
  syncActiveWorkbenchFile(tab);
  const file = tab.openFiles?.find((candidate) => candidate.path === path);
  if (!file) return false;
  tab.activeFilePath = file.path;
  tab.document = file.document || null;
  tab.asset = file.asset || null;
  tab.dirty = Boolean(file.dirty);
  tab.markdownMode = file.markdownMode || "";
  tab.reviewDiff = file.reviewDiff || null;
  const displayPath = file.displayPath || file.reviewDiff?.path || file.path;
  tab.title = `${displayPath.split("/").pop() || "编辑器"}${file.reviewDiff ? " · 对比" : ""}`;
  renderWorkbenchEditor(tab);
  if (tab.document) window.MurongWorkbenchVendor?.focusCodeEditor(workbenchEditorController);
  return true;
}

function upsertWorkbenchFile(tab, file) {
  syncActiveWorkbenchFile(tab);
  tab.openFiles = tab.openFiles || [];
  const index = tab.openFiles.findIndex((candidate) => candidate.path === file.path);
  if (index >= 0) tab.openFiles[index] = file;
  else tab.openFiles.push(file);
  tab.activeFilePath = file.path;
  tab.document = file.document || null;
  tab.asset = file.asset || null;
  tab.dirty = Boolean(file.dirty);
  tab.markdownMode = file.markdownMode || "";
  tab.reviewDiff = file.reviewDiff || null;
  const displayPath = file.displayPath || file.reviewDiff?.path || file.path;
  tab.title = `${displayPath.split("/").pop() || "编辑器"}${file.reviewDiff ? " · 对比" : ""}`;
}

function renderWorkbenchEditorFileTabs(tab) {
  const strip = $("#workbench-editor-tabstrip");
  strip.replaceChildren();
  (tab.openFiles || []).forEach((file) => {
    const item = document.createElement("div");
    item.className = `workbench-editor-file-tab${file.path === tab.activeFilePath ? " active" : ""}${file.dirty ? " dirty" : ""}`;
    item.setAttribute("role", "tab");
    item.setAttribute("aria-selected", String(file.path === tab.activeFilePath));
    const displayPath = file.displayPath || file.reviewDiff?.path || file.path;
    item.title = file.reviewDiff ? `${displayPath} · 本轮修改对比` : displayPath;
    const label = document.createElement("span");
    label.textContent = `${displayPath.split("/").pop() || displayPath}${file.reviewDiff ? " ↔" : ""}`;
    const close = document.createElement("button");
    close.type = "button";
    close.title = "关闭文件";
    close.setAttribute("aria-label", `关闭 ${displayPath}`);
    close.textContent = "×";
    close.addEventListener("click", (event) => { event.stopPropagation(); closeWorkbenchEditorFile(tab.id, file.path); });
    item.addEventListener("click", () => activateWorkbenchFile(tab, file.path));
    item.append(label, close);
    strip.append(item);
  });
}

function closeWorkbenchEditorFile(tabID, path) {
  const tab = state.workbench.tabs.find((item) => item.id === tabID && item.type === "editor");
  if (!tab) return;
  syncActiveWorkbenchFile(tab);
  const index = (tab.openFiles || []).findIndex((file) => file.path === path);
  if (index < 0) return;
  const file = tab.openFiles[index];
  if (file.dirty && !window.confirm(`${path} 尚未保存，确定关闭吗？`)) return;
  tab.openFiles.splice(index, 1);
  if (tab.activeFilePath === path) {
    const next = tab.openFiles[Math.min(index, tab.openFiles.length - 1)];
    tab.activeFilePath = "";
    tab.document = null;
    tab.asset = null;
    tab.dirty = false;
    tab.markdownMode = "";
    tab.reviewDiff = null;
    if (next) activateWorkbenchFile(tab, next.path);
  }
  renderWorkbenchEditor(tab);
}

function openWorkbenchFileContextMenu(event, tab, entry) {
  event.preventDefault();
  event.stopPropagation();
  const menu = $("#workbench-file-context-menu");
  menu.replaceChildren();
  const addAction = (label, action) => {
    const button = document.createElement("button");
    button.type = "button";
    button.setAttribute("role", "menuitem");
    button.textContent = label;
    button.addEventListener("click", () => { closeWorkbenchFileContextMenu(); action(); });
    menu.append(button);
  };
  if (entry.directory) addAction("打开目录", () => loadWorkbenchEditorDirectory(tab.id, entry.path));
  else {
    addAction("打开文件", () => loadWorkbenchEditorFile(tab.id, entry.path, entry.preview));
    addAction("在编辑标签中显示", () => loadWorkbenchEditorFile(tab.id, entry.path, entry.preview));
    if (entry.preview !== "image") {
      addAction("查看文件对比", () => {
        state.workspaceTimelineMode = "live";
        openWorkspaceChanges(entry.path);
      });
    }
  }
  addAction("在文件管理器中打开", async () => {
    try { await backend().RevealWorkbenchPath(entry.path); }
    catch (error) { showToast(errorText(error), true); }
  });
  addAction("复制相对路径", async () => {
    try { await navigator.clipboard?.writeText(entry.path); showToast("已复制文件路径"); }
    catch (_) { showToast("无法复制文件路径", true); }
  });
  menu.classList.remove("hidden");
  const rect = menu.getBoundingClientRect();
  const margin = 8;
  menu.style.left = `${Math.max(margin, Math.min(event.clientX, window.innerWidth - rect.width - margin))}px`;
  menu.style.top = `${Math.max(38, Math.min(event.clientY, window.innerHeight - rect.height - 32))}px`;
}

function closeWorkbenchFileContextMenu() {
  $("#workbench-file-context-menu")?.classList.add("hidden");
}

function closeWorkbenchFileContextMenuFromOutside(event) {
  const menu = $("#workbench-file-context-menu");
  if (!menu || menu.classList.contains("hidden") || menu.contains(event.target)) return;
  closeWorkbenchFileContextMenu();
}

function renderWorkbenchEditor(tab) {
  $("#workbench-editor-directory").textContent = tab.directory || ".";
  $("#workbench-editor-up").disabled = !tab.directory || tab.directory === "." || tab.loading;
  renderWorkbenchEditorFileTabs(tab);
  const list = $("#workbench-file-list");
  list.replaceChildren();
  if (tab.loading) {
    const note = document.createElement("p"); note.className = "form-hint"; note.textContent = "正在读取项目…"; list.append(note);
  } else if (!tab.entries?.length) {
    const note = document.createElement("p"); note.className = "form-hint"; note.textContent = "目录为空"; list.append(note);
  } else {
    tab.entries.forEach((entry) => {
      const button = document.createElement("button");
      button.type = "button";
      const selectedPath = tab.reviewDiff?.path || tab.document?.path || tab.asset?.path || "";
      button.className = `workbench-file-entry${selectedPath === entry.path ? " active" : ""}`;
      const icon = document.createElement("span"); icon.textContent = entry.directory ? "▸" : entry.preview === "image" ? "▧" : "·";
      const name = document.createElement("span"); name.textContent = entry.name;
      button.append(icon, name);
      button.addEventListener("click", () => {
        if (entry.directory) {
          loadWorkbenchEditorDirectory(tab.id, entry.path);
        } else if (entry.reviewOnly && tab.reviewDiff?.id) {
          openWorkspaceReviewFile({ id: tab.reviewDiff.id }, entry.path);
        } else {
          loadWorkbenchEditorFile(tab.id, entry.path, entry.preview);
        }
      });
      button.addEventListener("contextmenu", (event) => openWorkbenchFileContextMenu(event, tab, entry));
      list.append(button);
    });
  }
  const selected = tab.document || tab.asset;
  $("#workbench-editor-path").textContent = tab.reviewDiff?.path || selected?.path || "选择文件";
  $("#workbench-editor-state").textContent = tab.reviewDiff
    ? "本轮修改前后对比 · 只读"
    : tab.dirty
    ? "未保存"
    : tab.asset ? `${formatCount(tab.asset.size || 0)} B${tab.asset.width ? ` · ${tab.asset.width}×${tab.asset.height}` : ""}`
      : tab.document ? `${formatCount(tab.document.size || 0)} B` : "";
  const codeHost = $("#workbench-code-editor");
  const editorSurface = $("#workbench-editor-surface");
  const markdownModes = $("#workbench-markdown-modes");
  const markdownPreview = $("#workbench-markdown-preview");
  const imagePreview = $("#workbench-image-preview");
  const placeholder = $("#workbench-editor-placeholder");
  const hasDocument = Boolean(tab.document) && !tab.loading;
  const hasImage = Boolean(tab.asset) && !tab.loading;
  const hasMarkdown = hasDocument && !tab.reviewDiff && isMarkdownPath(tab.document.path);
  const markdownMode = hasMarkdown ? (tab.markdownMode || "split") : "edit";
  const showCodeEditor = hasDocument && markdownMode !== "preview";
  const showMarkdownPreview = hasMarkdown && markdownMode !== "edit";
  codeHost.classList.toggle("hidden", !showCodeEditor);
  markdownPreview.classList.toggle("hidden", !showMarkdownPreview);
  imagePreview.classList.toggle("hidden", !hasImage);
  placeholder.classList.toggle("hidden", hasDocument || hasImage);
  editorSurface.classList.toggle("markdown-split", hasMarkdown && markdownMode === "split");
  markdownModes.classList.toggle("hidden", !hasMarkdown);
  markdownModes.querySelectorAll("[data-markdown-mode]").forEach((button) => {
    const active = button.dataset.markdownMode === markdownMode;
    button.classList.toggle("active", active);
    button.setAttribute("aria-pressed", String(active));
  });
  placeholder.textContent = tab.loading ? "正在读取文件…" : "从右侧文件列表选择文本或图片";
  if (hasDocument) {
    ensureWorkbenchCodeEditor();
    const key = `${tab.id}:${tab.document.path}`;
    const current = workbenchEditorController?.view?.state.doc.toString() || "";
    if (workbenchEditorController && (workbenchEditorController.documentKey !== key || (!tab.dirty && current !== tab.document.content))) {
      window.MurongWorkbenchVendor.setCodeEditorDocument(workbenchEditorController, tab.document.content, tab.document.path, !tab.loading && !tab.reviewDiff);
      workbenchEditorController.documentKey = key;
    }
    if (hasMarkdown) markdownPreview.innerHTML = renderMarkdown(tab.document.content);
  } else {
    markdownPreview.replaceChildren();
  }
  const image = $("#workbench-image-preview-source");
  if (hasImage) {
    const source = `data:${tab.asset.mimeType};base64,${tab.asset.base64}`;
    if (image.src !== source) image.src = source;
    image.alt = tab.asset.path;
    $("#workbench-image-preview-detail").textContent = `${tab.asset.path} · ${formatCount(tab.asset.size || 0)} B${tab.asset.width ? ` · ${tab.asset.width}×${tab.asset.height}` : ""}`;
  } else {
    image.removeAttribute("src");
    image.alt = "";
  }
  $("#workbench-editor-save").classList.toggle("hidden", !hasDocument || Boolean(tab.reviewDiff));
  $("#workbench-editor-save").disabled = !tab.document || Boolean(tab.reviewDiff) || !tab.dirty || tab.saving;
}

function isMarkdownPath(path) {
  return /\.(?:md|markdown)$/i.test(String(path || ""));
}

function setWorkbenchMarkdownMode(event) {
  const tab = activeWorkbenchTab("editor");
  if (!tab?.document || !isMarkdownPath(tab.document.path)) return;
  const mode = event.currentTarget.dataset.markdownMode;
  if (!["edit", "preview", "split"].includes(mode)) return;
  tab.markdownMode = mode;
  renderWorkbenchEditor(tab);
  if (mode !== "preview") window.MurongWorkbenchVendor?.focusCodeEditor(workbenchEditorController);
}

function ensureWorkbenchCodeEditor() {
  if (workbenchEditorController || !window.MurongWorkbenchVendor) return;
  workbenchEditorController = window.MurongWorkbenchVendor.createCodeEditor($("#workbench-code-editor"), markWorkbenchEditorDirty);
}

async function loadWorkbenchEditorDirectory(tabID, directory) {
  const tab = state.workbench.tabs.find((item) => item.id === tabID && item.type === "editor");
  if (!tab) return;
  syncActiveWorkbenchFile(tab);
  const requestID = (tab.directoryRequestID || 0) + 1;
  tab.directoryRequestID = requestID;
  tab.loading = true;
  tab.directory = directory || ".";
  renderWorkbench();
  try {
    const entries = await backend().ListWorkbenchFiles(tab.directory);
    if (tab.directoryRequestID !== requestID) return;
    tab.entries = entries;
  } catch (error) {
    if (tab.directoryRequestID !== requestID) return;
    tab.entries = [];
    showToast(errorText(error), true);
  } finally {
    if (tab.directoryRequestID !== requestID) return;
    tab.loading = false;
    if (activeWorkbenchTab()?.id === tab.id) renderWorkbenchEditor(tab);
  }
}

async function loadWorkbenchEditorFile(tabID, path, preview = "") {
  const tab = state.workbench.tabs.find((item) => item.id === tabID && item.type === "editor");
  if (!tab) return;
  if (activateWorkbenchFile(tab, path)) return;
  syncActiveWorkbenchFile(tab);
  tab.loading = true;
  renderWorkbenchEditor(tab);
  try {
    if (preview === "image") {
      upsertWorkbenchFile(tab, { path, asset: await backend().ReadWorkbenchAsset(path), document: null, dirty: false, markdownMode: "" });
    } else {
      upsertWorkbenchFile(tab, {
        path,
        document: await backend().ReadWorkbenchFile(path),
        asset: null,
        dirty: false,
        markdownMode: isMarkdownPath(path) ? "split" : "",
      });
    }
  } catch (error) {
    showToast(errorText(error), true);
  } finally {
    tab.loading = false;
    if (activeWorkbenchTab()?.id === tab.id) renderWorkbench();
  }
}

async function openWorkspaceReviewFile(review, path) {
  if (!review?.id || !path) return;
  let tab = activeWorkbenchTab("editor");
  if (!tab) {
    tab = state.workbench.tabs.find((item) => item.type === "editor") || null;
    if (tab) activateWorkbenchTab(tab.id);
    else tab = openWorkbenchTab("editor");
  }
  if (!tab) return;
  const parts = path.split("/").filter(Boolean);
  parts.pop();
  const directory = parts.join("/") || ".";
  try {
    await loadWorkbenchEditorDirectory(tab.id, directory);
    const result = await backend().GetWorkspaceReviewDiff(review.id, path);
    const content = result.available ? result.diff : (result.message || "当前没有可显示的本轮 Diff。");
    const pseudoPath = `review:${review.id}:${path}`;
    if (!(tab.entries || []).some((entry) => entry.path === path)) {
      tab.entries = [...(tab.entries || []), {
        name: path.split("/").pop() || path,
        path,
        directory: false,
        preview: "text",
        reviewOnly: true,
      }];
    }
    upsertWorkbenchFile(tab, {
      path: pseudoPath,
      displayPath: path,
      document: { path: `${path}.diff`, content, sha256: "", size: content.length },
      asset: null,
      dirty: false,
      markdownMode: "",
      reviewDiff: { id: review.id, path },
    });
    renderWorkbench();
    window.MurongWorkbenchVendor?.focusCodeEditor(workbenchEditorController);
  } catch (error) {
    showToast(errorText(error), true);
  }
}

function workbenchEditorUp() {
  const tab = activeWorkbenchTab("editor");
  if (!tab || tab.directory === ".") return;
  const parts = tab.directory.split("/").filter(Boolean);
  parts.pop();
  loadWorkbenchEditorDirectory(tab.id, parts.join("/") || ".");
}

function refreshWorkbenchEditor() {
  const tab = activeWorkbenchTab("editor");
  if (tab) loadWorkbenchEditorDirectory(tab.id, tab.directory || ".");
}

function newWorkbenchEditorFile() {
  const tab = activeWorkbenchTab("editor");
  if (!tab) return;
  const suggested = tab.directory && tab.directory !== "." ? `${tab.directory}/` : "";
  const path = window.prompt("输入项目内的新文件路径", suggested);
  if (!path) return;
  const normalizedPath = path.trim().replaceAll("\\", "/");
  upsertWorkbenchFile(tab, {
    path: normalizedPath,
    document: { path: normalizedPath, content: "", sha256: "", size: 0 },
    asset: null,
    dirty: true,
    markdownMode: isMarkdownPath(normalizedPath) ? "split" : "",
  });
  renderWorkbench();
  window.MurongWorkbenchVendor?.focusCodeEditor(workbenchEditorController);
}

function markWorkbenchEditorDirty(content) {
  const tab = activeWorkbenchTab("editor");
  if (!tab?.document) return;
  tab.document.content = content;
  tab.dirty = true;
  syncActiveWorkbenchFile(tab);
  renderWorkbenchEditor(tab);
}

async function saveWorkbenchEditorFile() {
  const tab = activeWorkbenchTab("editor");
  if (!tab?.document || tab.saving) return;
  tab.saving = true;
  renderWorkbenchEditor(tab);
  try {
    tab.document = await backend().SaveWorkbenchFile({ path: tab.document.path, content: tab.document.content, expectedSha256: tab.document.sha256 || "" });
    tab.dirty = false;
    syncActiveWorkbenchFile(tab);
    showToast(`已保存 ${tab.document.path}`);
    tab.entries = await backend().ListWorkbenchFiles(tab.directory || ".");
  } catch (error) {
    showToast(errorText(error), true);
  } finally {
    tab.saving = false;
    if (activeWorkbenchTab()?.id === tab.id) renderWorkbench();
  }
}

async function loadWorkbenchGit(tabID) {
  const tab = state.workbench.tabs.find((item) => item.id === tabID && item.type === "git");
  if (!tab) return;
  tab.loading = true;
  if (activeWorkbenchTab()?.id === tab.id) renderWorkbenchGit(tab);
  try {
    tab.snapshot = await backend().GetWorkbenchGit();
  } catch (error) {
    tab.snapshot = { repository: false, message: errorText(error), status: "", diff: "" };
  } finally {
    tab.loading = false;
    if (activeWorkbenchTab()?.id === tab.id) renderWorkbenchGit(tab);
  }
}

function refreshWorkbenchGit() {
  const tab = activeWorkbenchTab("git");
  if (tab) loadWorkbenchGit(tab.id);
}

function renderWorkbenchGit(tab) {
  const snapshot = tab.snapshot || {};
  $("#workbench-git-branch").textContent = snapshot.branch || "Git";
  $("#workbench-git-message").textContent = tab.loading ? "正在读取…" : snapshot.message || "";
  $("#workbench-git-status").textContent = snapshot.repository ? snapshot.status || "工作树干净" : snapshot.message || "当前项目还不是 Git 仓库";
  $("#workbench-git-diff").textContent = snapshot.repository ? snapshot.diff || "当前没有未暂存 Diff" : "";
  $("#workbench-git-refresh").disabled = Boolean(tab.loading) || !state.config?.projectPath;
}

function renderWorkbenchSubagents() {
  const list = $("#workbench-subagent-list");
  list.replaceChildren();
  const jobs = state.active?.backgroundSubagentJobs || [];
  if (!jobs.length) {
    const empty = document.createElement("div"); empty.className = "workbench-empty"; empty.innerHTML = "<strong>当前没有子代理</strong><p>从聊天框的＋菜单启动子代理后，可在这里持续查看状态。</p>"; list.append(empty); return;
  }
  jobs.forEach((job) => {
    const card = document.createElement("article"); card.className = "workbench-subagent-card";
    const header = document.createElement("header");
    const title = document.createElement("strong"); title.textContent = job.label || "后台子代理";
    const status = document.createElement("span"); status.textContent = job.status || "等待";
    const detail = document.createElement("p"); detail.textContent = `${job.statusMessage || job.parentGoal || ""}${job.taskCount ? ` · ${job.completed || 0}/${job.taskCount}` : ""}`;
    header.append(title, status); card.append(header, detail); list.append(card);
  });
}

function renderWorkbenchSideChat(tab) {
  const select = $("#workbench-sidechat-session");
  select.replaceChildren();
  state.sessions.forEach((session) => {
    const option = document.createElement("option"); option.value = session.id; option.textContent = session.title; select.append(option);
  });
  if (!state.sessions.some((session) => session.id === tab.sessionId)) tab.sessionId = state.sessions[0]?.id || "";
  select.value = tab.sessionId;
  const list = $("#workbench-sidechat-messages");
  list.replaceChildren();
  const messages = tab.session?.messages || [];
  messages.slice(-30).forEach((message) => {
    if (message.role !== "user" && message.role !== "assistant") return;
    const item = document.createElement("article"); item.className = `workbench-sidechat-message ${message.role}`; item.textContent = message.content || ""; list.append(item);
  });
  if (!messages.length) {
    const empty = document.createElement("div"); empty.className = "workbench-empty"; empty.innerHTML = "<strong>选择侧边任务</strong><p>这里可并排查看或继续另一段聊天，不会替换主工作区。</p>"; list.append(empty);
  }
  $("#workbench-sidechat-input").disabled = !tab.sessionId;
  $("#workbench-sidechat-form").querySelector("button").disabled = !tab.sessionId;
  list.scrollTop = list.scrollHeight;
}

async function changeWorkbenchSideChatSession(event) {
  const tab = activeWorkbenchTab("sidechat");
  if (!tab) return;
  tab.sessionId = event.currentTarget.value;
  tab.session = null;
  tab.title = state.sessions.find((session) => session.id === tab.sessionId)?.title || "侧边任务";
  await refreshWorkbenchSideChat();
}

async function refreshWorkbenchSideChat() {
  const tab = activeWorkbenchTab("sidechat");
  if (!tab?.sessionId) { if (tab) renderWorkbenchSideChat(tab); return; }
  try {
    tab.session = await backend().GetSession(tab.sessionId);
    tab.title = tab.session?.title || "侧边任务";
  } catch (error) {
    showToast(errorText(error), true);
  }
  if (activeWorkbenchTab()?.id === tab.id) renderWorkbenchSideChat(tab);
}

async function sendWorkbenchSideChat(event) {
  event.preventDefault();
  const tab = activeWorkbenchTab("sidechat");
  const input = $("#workbench-sidechat-input");
  const content = input.value.trim();
  if (!tab?.sessionId || !content) return;
  input.disabled = true;
  try {
    await backend().SendMessage({ sessionId: tab.sessionId, content, context: [], images: [], mode: "" });
    input.value = "";
    setTimeout(refreshWorkbenchSideChat, 250);
  } catch (error) {
    showToast(errorText(error), true);
  } finally {
    input.disabled = false;
    input.focus();
  }
}

function refreshWorkbenchLiveViews() {
  const tab = activeWorkbenchTab();
  if (tab?.type === "subagents") renderWorkbenchSubagents();
  if (tab?.type === "sidechat" && tab.sessionId === state.active?.id) {
    tab.session = state.active;
    renderWorkbenchSideChat(tab);
  }
}

async function refreshStatusBar(force = false) {
  const sessionID = state.active?.id || "";
  if (!sessionID) {
    state.statusStats = null;
    renderStatusBar();
    return;
  }
  renderStatusBar();
  if (!force && state.statusStats?.sessionId === sessionID) return;
  const request = ++state.statusStatsRequest;
  try {
    const stats = await backend().GetSessionStats(sessionID);
    if (request !== state.statusStatsRequest || state.active?.id !== sessionID) return;
    state.statusStats = { ...stats, sessionId: sessionID };
    renderStatusBar();
  } catch (_) {}
}

function renderStatusBar() {
  const project = activeProjectPath();
  const profile = activeProviderProfile?.() || {};
  const stats = state.statusStats && state.statusStats.sessionId === state.active?.id ? state.statusStats : {};
  const usage = state.active?.usage || {};
  const tokens = Number(stats.providerTotalTokens || usage.totalTokens || stats.estimatedTokens || 0);
  const cached = Number(stats.cachedInputTokens || usage.cachedInputTokens || 0);
  const providerInput = Number(stats.providerInputTokens || usage.inputTokens || 0);
  const cachePercent = providerInput > 0 ? Math.min(100, Math.max(0, Math.round(cached / providerInput * 100))) : 0;
  const context = Number(stats.estimatedTokens || 0);
  const windowTokens = Number(profile.contextWindowTokens || 0);
  const contextPercent = windowTokens > 0 ? Math.min(100, Math.max(0, Math.round(context / windowTokens * 100))) : 0;
  $("#statusbar-project").textContent = project ? `项目 ${projectName(project)}` : "未选择项目";
  $("#statusbar-project").title = project || "";
  $("#statusbar-model").textContent = usage.lastModel || providerProfileModelLabel(profile) || "模型未配置";
  $("#statusbar-cache").textContent = `缓存 ${cachePercent}% · ${formatCount(cached)}`;
  $("#statusbar-cache").title = providerInput > 0
    ? `缓存输入 ${formatCount(cached)} / 总输入 ${formatCount(providerInput)}`
    : "模型尚未返回缓存用量";
  $("#statusbar-tokens").textContent = `Token ${formatCount(tokens)}`;
  $("#statusbar-context").textContent = windowTokens
    ? `上下文 ${formatCount(context)} / ${formatCount(windowTokens)} (${contextPercent}%)`
    : `上下文 ${formatCount(context)}`;
  const meter = $("#composer-context-meter");
  const ring = $("#composer-context-meter-ring");
  const value = $("#composer-context-meter-value");
  if (meter && ring && value) {
    ring.style.setProperty("--context-percent", `${contextPercent * 3.6}deg`);
    value.textContent = String(contextPercent);
    $("#composer-context-meter-title").textContent = `上下文 ${contextPercent}%`;
    $("#composer-context-meter-detail").textContent = windowTokens
      ? `${formatCount(context)} / ${formatCount(windowTokens)} Token · 缓存 ${cachePercent}% (${formatCount(cached)})`
      : `${formatCount(context)} Token（本地估算）· 缓存 ${cachePercent}% (${formatCount(cached)})`;
    meter.setAttribute("aria-label", `上下文已用 ${contextPercent}%`);
  }
  if (!state.running) $("#statusbar-run").textContent = "就绪";
}

function renderRemoteTerminalOptions(terminals, selected, disabled) {
  const container = $("#remote-terminal-list");
  container.replaceChildren();
  const selectedSet = new Set(selected);
  terminals.forEach((terminal) => {
    const label = document.createElement("label");
    label.className = "remote-terminal-option";
    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.value = terminal.id;
    checkbox.checked = selectedSet.has(terminal.id);
    checkbox.disabled = disabled;
    const text = document.createElement("span");
    const name = document.createElement("strong");
    name.textContent = terminal.label;
    const detail = document.createElement("small");
    detail.textContent = `${terminal.id}${terminal.version ? ` · ${terminal.version}` : ""}`;
    text.append(name, detail);
    label.append(checkbox, text);
    container.append(label);
  });
}

async function resolveApproval(approve) {
  if (!state.approval) return;
  const decision = { id: state.approval.id, approve };
  state.approval = null;
  $("#approval-card").classList.add("hidden");
  await backend().ResolveApproval(decision);
}

function renderMarkdown(source) {
  if (window.MurongWorkbenchVendor?.renderMarkdown) {
    return window.MurongWorkbenchVendor.renderMarkdown(source);
  }
  let value = escapeHtml(source || "");
  const blocks = [];
  value = value.replace(/```([^\n]*)\n([\s\S]*?)```/g, (_, language, code) => {
    const token = `@@CODEBLOCK_${blocks.length}@@`;
    blocks.push(`<pre><code data-language="${language.trim()}">${code}</code></pre>`);
    return token;
  });
  value = value
    .replace(/^### (.+)$/gm, "<h3>$1</h3>")
    .replace(/^## (.+)$/gm, "<h2>$1</h2>")
    .replace(/^# (.+)$/gm, "<h1>$1</h1>")
    .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/(?:^|\n)- (.+)(?=\n|$)/g, "<br>• $1")
    .split(/\n{2,}/).map((paragraph) => `<p>${paragraph.replace(/\n/g, "<br>")}</p>`).join("");
  blocks.forEach((block, index) => { value = value.replace(`<p>@@CODEBLOCK_${index}@@</p>`, block).replace(`@@CODEBLOCK_${index}@@`, block); });
  return value;
}

function escapeHtml(value) {
  return value.replace(/[&<>"']/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[character]));
}

function syncComposerInputHistoryFromActiveSession() {
  const sessionId = String(state.active?.id || "");
  if (state.composerInputHistorySessionId === sessionId) return;
  state.composerInputHistorySessionId = sessionId;
  resetComposerInputHistoryNavigation($("#message-input")?.value || "");
  if (!sessionId) return;

  const sessionInputs = (state.active?.messages || [])
    .filter((message) => message?.role === "user")
    .map((message) => String(message?.content || "").trim())
    .filter(Boolean);
  if (!sessionInputs.length) return;

  let merged = state.composerInputHistory.slice();
  sessionInputs.forEach((sentText) => {
    merged = merged.filter((value) => value !== sentText);
    merged.push(sentText);
  });
  merged = merged.slice(-composerInputHistoryLimit);
  if (JSON.stringify(merged) === JSON.stringify(state.composerInputHistory)) return;
  state.composerInputHistory = merged;
  persistComposerInputHistory();
}

function restoreComposerInputHistory() {
  try {
    const stored = JSON.parse(localStorage.getItem(composerInputHistoryStorageKey) || "[]");
    state.composerInputHistory = Array.isArray(stored)
      ? stored.filter((value) => typeof value === "string" && value.trim()).map((value) => value.trim()).slice(-composerInputHistoryLimit)
      : [];
  } catch (_) {
    state.composerInputHistory = [];
  }
  resetComposerInputHistoryNavigation("");
}

function persistComposerInputHistory() {
  try {
    localStorage.setItem(composerInputHistoryStorageKey, JSON.stringify(state.composerInputHistory.slice(-composerInputHistoryLimit)));
  } catch (_) {}
}

function rememberComposerInput(content) {
  const sentText = String(content || "").trim();
  if (!sentText) return;
  state.composerInputHistory = state.composerInputHistory.filter((value) => value !== sentText);
  state.composerInputHistory.push(sentText);
  if (state.composerInputHistory.length > composerInputHistoryLimit) {
    state.composerInputHistory = state.composerInputHistory.slice(-composerInputHistoryLimit);
  }
  persistComposerInputHistory();
}

function resetComposerInputHistoryNavigation(draft = "") {
  state.composerInputHistoryIndex = -1;
  state.composerInputDraftBeforeHistory = draft;
}

function setComposerInputValue(value) {
  const input = $("#message-input");
  input.value = String(value || "");
  resetComposerInputHistoryNavigation(input.value);
  autoSizeComposer();
  input.focus();
  input.setSelectionRange(input.value.length, input.value.length);
}

function applyRecalledComposerInput(input, value) {
  input.value = value;
  autoSizeComposer();
  input.focus();
  input.setSelectionRange(value.length, value.length);
}

function recallPreviousComposerInput(input) {
  if (!state.composerInputHistory.length) return false;
  if (state.composerInputHistoryIndex === -1) {
    state.composerInputDraftBeforeHistory = input.value;
    state.composerInputHistoryIndex = state.composerInputHistory.length - 1;
  } else if (state.composerInputHistoryIndex > 0) {
    state.composerInputHistoryIndex -= 1;
  }
  applyRecalledComposerInput(input, state.composerInputHistory[state.composerInputHistoryIndex]);
  return true;
}

function recallNextComposerInput(input) {
  if (state.composerInputHistoryIndex === -1) return false;
  if (state.composerInputHistoryIndex < state.composerInputHistory.length - 1) {
    state.composerInputHistoryIndex += 1;
    applyRecalledComposerInput(input, state.composerInputHistory[state.composerInputHistoryIndex]);
  } else {
    const draft = state.composerInputDraftBeforeHistory;
    resetComposerInputHistoryNavigation(draft);
    applyRecalledComposerInput(input, draft);
  }
  return true;
}

function composerCaretIsOnFirstLine(input) {
  if (input.selectionStart !== input.selectionEnd) return false;
  return !input.value.slice(0, input.selectionStart).includes("\n");
}

function handleComposerInputHistoryKeydown(event) {
  if (event.defaultPrevented || event.isComposing || event.ctrlKey || event.altKey || event.metaKey || event.shiftKey) return false;
  const input = event.currentTarget;
  if (event.key === "ArrowUp") {
    if (state.composerInputHistoryIndex === -1 && !composerCaretIsOnFirstLine(input)) return false;
    if (!recallPreviousComposerInput(input)) return false;
    event.preventDefault();
    return true;
  }
  if (event.key === "ArrowDown" && recallNextComposerInput(input)) {
    event.preventDefault();
    return true;
  }
  return false;
}

function handleComposerInputChange(event) {
  const updatedText = event.currentTarget.value;
  if (state.composerInputHistoryIndex >= 0) {
    const recalledText = state.composerInputHistory[state.composerInputHistoryIndex];
    if (updatedText !== recalledText) resetComposerInputHistoryNavigation(updatedText);
  } else {
    state.composerInputDraftBeforeHistory = updatedText;
  }
  autoSizeComposer();
  updateComposerActionState();
}

function autoSizeComposer() {
  const input = $("#message-input");
  input.style.height = "auto";
  input.style.height = `${Math.min(input.scrollHeight, 180)}px`;
}

function updateMessageAutoFollow() {
  const container = $("#messages");
  state.autoFollowMessages = container.scrollHeight - container.scrollTop - container.clientHeight <= 96;
}

function scrollMessages(force = false) {
  const container = $("#messages");
  if (!force && !state.autoFollowMessages) return;
  requestAnimationFrame(() => {
    container.scrollTop = container.scrollHeight;
    state.autoFollowMessages = true;
  });
}

function showToast(message, isError = false) {
  const toast = $("#toast");
  toast.textContent = message;
  toast.classList.toggle("error", isError);
  toast.classList.remove("hidden");
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => toast.classList.add("hidden"), 4200);
}

function errorText(error) {
  if (!error) return "未知错误";
  if (typeof error === "string") return error;
  return error.message || String(error);
}

function formatTime(timestamp) {
  if (!timestamp) return "刚刚";
  return new Intl.DateTimeFormat("zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(timestamp));
}

function cssEscape(value) {
  return String(value).replace(/[^a-zA-Z0-9_-]/g, "\\$&");
}

// ---- 离线朗读与语音输入（Windows） ----
const voiceDefaults = {
  runtimeInstalled: false,
  ttsModelInstalled: false,
  asrModelInstalled: false,
  busy: false,
  recording: false,
  listening: false,
  partialText: "",
  speaking: false,
  lastError: "",
};
state.voice = { ...voiceDefaults };

async function refreshVoiceStatus() {
  try {
    applyVoiceStatus(await backend().VoiceStatus());
  } catch (error) {
    console.error("voice status failed", error);
  }
}

function applyVoiceStatus(status) {
  state.voice = { ...voiceDefaults, ...(status || {}) };
  renderVoiceSettings();
  updateVoiceControls();
}

function renderVoiceSettings() {
  const voice = state.voice;
  const statusEl = $("#voice-runtime-status");
  const ttsStatus = $("#voice-tts-status");
  const asrStatus = $("#voice-asr-status");
  if (!statusEl) return;
  if (voice.busy) {
    statusEl.textContent = "正在下载并校验离线组件…请保持网络连接，此操作需要几分钟。";
  } else if (voice.runtimeInstalled) {
    statusEl.textContent = "离线语音引擎已就绪（Sherpa-onnx " + "1.13.2" + "，完全离线运行）";
  } else {
    statusEl.textContent = "首次使用朗读或语音输入时会自动下载语音引擎（约 20 MB）。";
  }
  if (ttsStatus) {
    ttsStatus.textContent = voice.ttsModelInstalled ? "已安装" : (voice.busy ? "安装中…" : "未安装");
    ttsStatus.className = "inline-state" + (voice.ttsModelInstalled ? " ok" : "");
  }
  if (asrStatus) {
    asrStatus.textContent = voice.asrModelInstalled ? "已安装" : (voice.busy ? "安装中…" : "未安装");
    asrStatus.className = "inline-state" + (voice.asrModelInstalled ? " ok" : "");
  }
  const installTts = $("#install-tts-model");
  const installAsr = $("#install-asr-model");
  if (installTts) {
    installTts.disabled = voice.busy || voice.ttsModelInstalled;
    installTts.textContent = voice.ttsModelInstalled ? "朗读模型已安装" : (voice.busy ? "安装中…" : "安装朗读模型");
  }
  if (installAsr) {
    installAsr.disabled = voice.busy || voice.asrModelInstalled;
    installAsr.textContent = voice.asrModelInstalled ? "识别模型已安装" : (voice.busy ? "安装中…" : "安装识别模型");
  }
  if (voice.lastError) {
    showToast(voice.lastError, true);
  }
}

function updateVoiceControls() {
  const voice = state.voice;
  const mic = $("#composer-mic");
  const partial = $("#voice-partial");
  if (mic) {
    mic.disabled = voice.busy;
    mic.classList.toggle("recording", voice.listening);
    mic.textContent = voice.listening ? "⏹" : "🎤";
    mic.title = voice.listening ? "点击结束并转文字" : (voice.asrModelInstalled ? "语音输入：点击开始" : "语音输入：需先安装识别模型（自动）");
  }
  if (partial) {
    partial.classList.toggle("hidden", !voice.listening);
    if (voice.listening) {
      partial.textContent = voice.partialText ? "识别中：" + voice.partialText : "正在听…请说话";
    }
  }
  document.querySelectorAll(".speak-button").forEach((button) => {
    button.disabled = !voice.ttsModelInstalled;
    if (button.dataset.speakingMessageId) {
      button.textContent = voice.speaking ? "停止朗读" : "朗读";
      if (!voice.speaking) delete button.dataset.speakingMessageId;
    }
  });
}

async function ensureVoiceRuntime() {
  const voice = state.voice;
  if (voice.runtimeInstalled) return true;
  const confirmed = window.confirm("首次使用需要下载 Murong 内置的离线语音引擎（约 20 MB，来自 Sherpa-onnx 官方发布，SHA-256 校验）。是否现在下载？");
  if (!confirmed) return false;
  try {
    await backend().VoiceInstallRuntime();
    await refreshVoiceStatus();
    return true;
  } catch (error) {
    showToast("语音引擎安装失败：" + errorText(error), true);
    return false;
  }
}

async function ensureTtsModel() {
  if (state.voice.ttsModelInstalled) return true;
  if (!(await ensureVoiceRuntime())) return false;
  const confirmed = window.confirm("还需要下载离线中文朗读模型（约 113 MB，下载后完全离线朗读）。是否继续？");
  if (!confirmed) return false;
  try {
    await backend().VoiceInstallTtsModel();
    await refreshVoiceStatus();
    return state.voice.ttsModelInstalled;
  } catch (error) {
    showToast("朗读模型安装失败：" + errorText(error), true);
    return false;
  }
}

async function ensureAsrModel() {
  if (state.voice.asrModelInstalled) return true;
  if (!(await ensureVoiceRuntime())) return false;
  const confirmed = window.confirm("还需要下载离线中英语音识别模型（约 128 MB，下载后完全离线听写）。是否继续？");
  if (!confirmed) return false;
  try {
    await backend().VoiceInstallAsrModel();
    await refreshVoiceStatus();
    return state.voice.asrModelInstalled;
  } catch (error) {
    showToast("识别模型安装失败：" + errorText(error), true);
    return false;
  }
}

function markdownToPlainText(markdown) {
  return String(markdown || "")
    .replace(/```[\s\S]*?```/g, " ")
    .replace(/`([^`]+)`/g, "$1")
    .replace(/!\[[^\]]*\]\([^)]*\)/g, " ")
    .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
    .replace(/^#{1,6}\s+/gm, "")
    .replace(/^>\s?/gm, "")
    .replace(/^[-*+]\s+/gm, "")
    .replace(/^\d+\.\s+/gm, "")
    .replace(/[*_~]/g, "")
    .replace(/\s+/g, " ")
    .trim();
}

async function toggleSpeakMessage(message, body, button) {
  const voice = state.voice;
  if (voice.speaking && button.dataset.speakingMessageId === String(message.id)) {
    try { await backend().VoiceStopSpeaking(); } catch (error) { console.error(error); }
    delete button.dataset.speakingMessageId;
    button.textContent = "朗读";
    return;
  }
  if (!(await ensureTtsModel())) return;
  let plain = "";
  if (body) {
    const bodyCopy = body.cloneNode(true);
    bodyCopy.querySelector(".message-history-actions")?.remove();
    plain = bodyCopy.textContent || "";
  }
  plain = markdownToPlainText(plain || message.content).slice(0, 3000);
  if (!plain) {
    showToast("这条消息没有可朗读的文本", true);
    return;
  }
  try {
    document.querySelectorAll(".speak-button").forEach((other) => delete other.dataset.speakingMessageId);
    button.dataset.speakingMessageId = String(message.id);
    button.textContent = "停止朗读";
    await backend().VoiceSpeak(plain, 1.0);
  } catch (error) {
    delete button.dataset.speakingMessageId;
    button.textContent = "朗读";
    showToast("朗读失败：" + errorText(error), true);
  }
}

async function startVoiceListening() {
  if (state.voice.listening) return;
  if (state.voice.busy) {
    showToast("语音组件正在安装中，请稍候…", true);
    return;
  }
  if (!(await ensureAsrModel())) return;
  try {
    await backend().VoiceStartListening();
    await refreshVoiceStatus();
  } catch (error) {
    showToast("麦克风启动失败：" + errorText(error), true);
  }
}

async function stopVoiceListening() {
  if (!state.voice.listening) return;
  try {
    const text = await backend().VoiceStopListeningAndGetFinal();
    await refreshVoiceStatus();
    if (text) {
      const base = ($("#message-input")?.value || "").trim();
      setComposerInputValue(base ? base + "\n" + text : text);
      $("#message-input")?.focus();
    } else {
      showToast("没有识别到语音，请靠近麦克风重试", true);
    }
  } catch (error) {
    await refreshVoiceStatus();
    showToast("语音识别失败：" + errorText(error), true);
  }
}

function bindVoiceEvents() {
  const mic = $("#composer-mic");
  if (mic) {
    mic.addEventListener("click", () => {
      if (mic.disabled) return;
      if (state.voice.listening) {
        void stopVoiceListening();
      } else {
        void startVoiceListening();
      }
    });
  }
  const installTts = $("#install-tts-model");
  if (installTts) {
    installTts.addEventListener("click", () => {
      if (state.voice.ttsModelInstalled || state.voice.busy) return;
      void ensureTtsModel();
    });
  }
  const installAsr = $("#install-asr-model");
  if (installAsr) {
    installAsr.addEventListener("click", () => {
      if (state.voice.asrModelInstalled || state.voice.busy) return;
      void ensureAsrModel();
    });
  }
}

if (window.runtime?.EventsOn) {
  window.runtime.EventsOn("voice_status", (status) => applyVoiceStatus(status));
  window.runtime.EventsOn("voice_final", (text) => {
    if (!text) return;
    const base = ($("#message-input")?.value || "").trim();
    setComposerInputValue(base ? base + "\n" + text : text);
    $("#message-input")?.focus();
  });
}

document.addEventListener("DOMContentLoaded", () => {
  bindVoiceEvents();
  void refreshVoiceStatus();
});

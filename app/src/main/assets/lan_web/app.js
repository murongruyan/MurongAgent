"use strict";

const {
  buildWorkspaceDiff,
  diffWorkspaceSnapshots,
  validateWorkspacePath,
  workspaceFailure
} = globalThis.MurongWorkspaceCore;

const state = {
  authenticated: false,
  selectedSessionId: null,
  currentApproval: null,
  lastEventId: null,
  eventAbort: null,
  reconnectDelay: 1000,
  currentSession: null,
  sessionListRefreshTimer: null,
  workspaceHandle: null,
  workspaceSessionId: null,
  workspaceWritable: false,
  workspaceHeartbeatTimer: null,
  workspaceScanTimer: null,
  workspaceSnapshot: null,
  workspaceScanBusy: false,
  processedWorkspaceRequests: new Set()
};

const el = id => document.getElementById(id);

document.addEventListener("DOMContentLoaded", () => {
  el("pair-button").addEventListener("click", pair);
  el("refresh-sessions").addEventListener("click", loadSessions);
  el("send-button").addEventListener("click", sendMessage);
  el("message-input").addEventListener("keydown", event => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      sendMessage();
    }
  });
  el("approve-button").addEventListener("click", () => decideApproval("approve"));
  el("reject-button").addEventListener("click", () => decideApproval("reject"));
  el("logout-button").addEventListener("click", revokeSelf);
  el("workspace-connect").addEventListener("click", connectWorkspace);
  el("workspace-disconnect").addEventListener("click", () => disconnectWorkspace(true));
  bootApp();
});

function showPair(message = "") {
  if (state.eventAbort) state.eventAbort.abort();
  state.eventAbort = null;
  el("app-view").classList.add("hidden");
  el("pair-view").classList.remove("hidden");
  el("pair-error").textContent = message;
}

function showApp() {
  el("pair-view").classList.add("hidden");
  el("app-view").classList.remove("hidden");
}

async function pair() {
  el("pair-error").textContent = "";
  const button = el("pair-button");
  button.disabled = true;
  try {
    const response = await fetch("/api/v1/pair", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        code: el("pair-code").value,
        clientName: el("client-name").value
      })
    });
    const body = await response.json();
    if (!response.ok) throw new Error(body.error || "配对失败");
    await bootApp();
  } catch (error) {
    el("pair-error").textContent = error.message || "配对失败";
  } finally {
    button.disabled = false;
  }
}

async function bootApp() {
  showApp();
  try {
    await loadSessions();
    state.authenticated = true;
    await initializeWorkspace();
    connectEvents();
  } catch (error) {
    handleAppError(error);
  }
}

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  const response = await fetch(path, { ...options, headers });
  let body = null;
  const text = await response.text();
  if (text) {
    try { body = JSON.parse(text); } catch (_) { body = { error: text }; }
  }
  if (response.status === 401) {
    clearSession();
    throw new Error("客户端凭据已失效，请重新配对");
  }
  if (!response.ok) throw new Error(body?.error || `请求失败 (${response.status})`);
  return body;
}

async function loadSessions() {
  const body = await api("/api/v1/sessions");
  renderSessions(body.sessions || []);
  if (!state.selectedSessionId) {
    state.selectedSessionId = body.activeSessionId || body.sessions?.[0]?.id || null;
  }
  if (state.selectedSessionId) await selectSession(state.selectedSessionId, false);
}

function renderSessions(sessions) {
  const list = el("session-list");
  list.replaceChildren();
  for (const session of sessions) {
    const button = document.createElement("button");
    button.className = `session-button${session.id === state.selectedSessionId ? " active" : ""}`;
    const title = document.createElement("span");
    title.className = "session-title";
    title.textContent = session.title || "新对话";
    const meta = document.createElement("span");
    meta.className = "session-meta";
    const flags = [session.projectLabel, `${session.messageCount} 条`];
    if (session.processing) flags.push("运行中");
    if (session.pendingApproval) flags.push("待审批");
    meta.textContent = flags.filter(Boolean).join(" · ");
    button.append(title, meta);
    button.addEventListener("click", () => selectSession(session.id));
    list.append(button);
  }
}

async function selectSession(sessionId, refreshList = true) {
  state.selectedSessionId = sessionId;
  const detail = await api(`/api/v1/sessions/${encodeURIComponent(sessionId)}`);
  renderSession(detail);
  if (refreshList) {
    const body = await api("/api/v1/sessions");
    renderSessions(body.sessions || []);
  }
}

function renderSession(session) {
  if (!session || session.id !== state.selectedSessionId) return;
  state.currentSession = session;
  el("chat-title").textContent = session.title || "新对话";
  el("chat-meta").textContent = `${session.active ? "当前会话" : "历史会话"}${session.processing ? " · 运行中" : ""}`;
  const messages = el("messages");
  const nearBottom = messages.scrollHeight - messages.scrollTop - messages.clientHeight < 120;
  messages.replaceChildren();
  if (!session.messages?.length) {
    const empty = document.createElement("div");
    empty.className = "empty-state";
    empty.textContent = "这个会话还没有消息。";
    messages.append(empty);
  } else {
    for (const message of session.messages) messages.append(renderMessage(message));
  }
  const canSend = !session.processing;
  el("message-input").disabled = !canSend;
  el("send-button").disabled = !canSend;
  if (nearBottom) requestAnimationFrame(() => { messages.scrollTop = messages.scrollHeight; });
}

function renderMessage(message) {
  const article = document.createElement("article");
  article.className = `message ${message.role || "unknown"}${message.streaming ? " streaming" : ""}`;
  const role = document.createElement("div");
  role.className = "message-role";
  role.textContent = roleLabel(message.role);
  const body = document.createElement("div");
  body.className = "message-body";
  body.textContent = message.content || "";
  article.append(role, body);
  return article;
}

function roleLabel(role) {
  if (role === "user") return "你";
  if (role === "assistant") return "Murong";
  if (role === "system") return "系统";
  return role || "消息";
}

async function sendMessage() {
  const input = el("message-input");
  const message = input.value.trim();
  if (!message || !state.selectedSessionId) return;
  el("send-button").disabled = true;
  el("app-error").textContent = "";
  try {
    await api("/api/v1/messages", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        sessionId: state.selectedSessionId,
        message,
        requestId: requestId()
      })
    });
    input.value = "";
  } catch (error) {
    handleAppError(error);
    el("send-button").disabled = false;
  }
}

function renderApproval(approval) {
  state.currentApproval = approval || null;
  const card = el("approval-card");
  if (!approval) {
    card.classList.add("hidden");
    return;
  }
  card.classList.remove("hidden");
  el("approval-summary").textContent = approval.summary || approval.toolName;
  el("approval-risk").textContent = approval.riskLevel || "UNKNOWN";
  el("approval-detail").textContent = [approval.detail, approval.explanationDetail].filter(Boolean).join("\n\n");
  el("approval-args").textContent = `${approval.toolName}\n${approval.arguments || ""}`;
  el("approve-button").disabled = !approval.approveEnabled;
}

async function decideApproval(decision) {
  const approval = state.currentApproval;
  if (!approval) return;
  el("approve-button").disabled = true;
  el("reject-button").disabled = true;
  try {
    await api("/api/v1/approval", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        sessionId: approval.sessionId,
        approvalId: approval.approvalId,
        decision,
        requestId: requestId()
      })
    });
  } catch (error) {
    handleAppError(error);
    el("approve-button").disabled = !approval.approveEnabled;
    el("reject-button").disabled = false;
  }
}

async function connectEvents() {
  if (state.eventAbort) state.eventAbort.abort();
  const controller = new AbortController();
  state.eventAbort = controller;
  try {
    const headers = { Accept: "text/event-stream" };
    if (state.lastEventId !== null) headers["Last-Event-ID"] = String(state.lastEventId);
    const response = await fetch("/api/v1/events", { headers, signal: controller.signal });
    if (response.status === 401) {
      clearSession();
      throw new Error("客户端凭据已失效，请重新配对");
    }
    if (!response.ok || !response.body) throw new Error(`事件连接失败 (${response.status})`);
    setConnection(true);
    state.reconnectDelay = 1000;
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true }).replaceAll("\r\n", "\n");
      let boundary;
      while ((boundary = buffer.indexOf("\n\n")) >= 0) {
        const block = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        handleSseBlock(block);
      }
    }
    throw new Error("事件连接已断开");
  } catch (error) {
    if (controller.signal.aborted) return;
    setConnection(false);
    el("app-error").textContent = error.message || "事件连接已断开";
    const wait = state.reconnectDelay;
    state.reconnectDelay = Math.min(wait * 2, 15000);
    setTimeout(() => { if (state.authenticated) connectEvents(); }, wait);
  }
}

function handleSseBlock(block) {
  if (!block || block.startsWith(":")) return;
  let id = null;
  let type = "message";
  const data = [];
  for (const line of block.split("\n")) {
    if (line.startsWith("id:")) id = Number(line.slice(3).trim());
    else if (line.startsWith("event:")) type = line.slice(6).trim();
    else if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
  }
  if (Number.isFinite(id)) state.lastEventId = id;
  if (!data.length) return;
  let payload;
  try { payload = JSON.parse(data.join("\n")); } catch (_) { return; }
  if (type === "snapshot" || type === "session_state") {
    const session = payload.session;
    renderApproval(session?.pendingApproval || null);
    if (session?.id === state.selectedSessionId) mergeLiveSession(session);
    loadSessionListQuietly();
  } else if (type === "workspace_request") {
    void handleWorkspaceRequest(payload);
  }
}

function mergeLiveSession(live) {
  const current = state.currentSession?.id === live.id
    ? state.currentSession
    : { id: live.id, title: live.title, active: true, processing: live.processing, messages: [] };
  const messages = Array.isArray(current.messages) ? [...current.messages] : [];
  if (live.lastMessage) {
    const index = messages.findIndex(message => message.id === live.lastMessage.id);
    if (index >= 0) messages[index] = live.lastMessage;
    else messages.push(live.lastMessage);
  }
  renderSession({
    ...current,
    title: live.title,
    active: true,
    processing: live.processing,
    error: live.error,
    messages
  });
}

function loadSessionListQuietly() {
  if (state.sessionListRefreshTimer) return;
  state.sessionListRefreshTimer = setTimeout(async () => {
    state.sessionListRefreshTimer = null;
    try {
      const body = await api("/api/v1/sessions");
      renderSessions(body.sessions || []);
    } catch (_) { /* reconnect path will report auth/network failures */ }
  }, 1000);
}

async function initializeWorkspace() {
  renderWorkspaceState();
  try {
    const status = await api("/api/v1/workspace/status");
    // Directory handles intentionally live only in this page. A reload cannot silently restore one.
    if (status?.connected && !state.workspaceHandle) {
      await api("/api/v1/workspace/disconnect", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ workspaceSessionId: status.workspaceSessionId })
      });
    }
  } catch (error) {
    el("workspace-error").textContent = error.message || "无法读取电脑工作区状态";
  }
}

function workspaceFeatureAvailable() {
  return window.isSecureContext && "showDirectoryPicker" in window && crypto?.subtle;
}

function renderWorkspaceState() {
  const connected = Boolean(state.workspaceHandle && state.workspaceSessionId);
  const badge = el("workspace-state");
  badge.textContent = connected ? (state.workspaceWritable ? "可读写" : "只读") : "未授权";
  badge.classList.toggle("online", connected);
  el("workspace-connect").classList.toggle("hidden", connected);
  el("workspace-disconnect").classList.toggle("hidden", !connected);
  if (connected) {
    el("workspace-label").textContent = `已授权：${state.workspaceHandle.name}。目录句柄只保存在当前 Chrome 页面内。`;
  } else if (!workspaceFeatureAvailable()) {
    el("workspace-label").textContent = "当前页面不是安全上下文。请通过 Murong Desktop Bridge 的 localhost 地址打开，普通手机局域网 HTTP 页面不能访问电脑目录。";
  } else {
    el("workspace-label").textContent = "选择后，手机 Agent 才能按审批规则读写这个目录。";
  }
}

async function connectWorkspace() {
  el("workspace-error").textContent = "";
  if (!workspaceFeatureAvailable()) {
    renderWorkspaceState();
    el("workspace-error").textContent = "Chrome 只允许安全页面调用目录 API；请运行 Murong Desktop Bridge 后从 localhost 打开。";
    return;
  }
  const button = el("workspace-connect");
  button.disabled = true;
  try {
    const handle = await window.showDirectoryPicker({ id: "murong-workspace", mode: "readwrite" });
    const readPermission = await ensureHandlePermission(handle, "read", true);
    if (!readPermission) throw new Error("没有获得目录读取权限");
    const writePermission = await ensureHandlePermission(handle, "readwrite", true);
    const workspaceSessionId = `workspace-${requestId()}`;
    const status = await api("/api/v1/workspace/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        workspaceSessionId,
        label: handle.name,
        readable: true,
        writable: writePermission,
        requestId: requestId()
      })
    });
    state.workspaceHandle = handle;
    state.workspaceSessionId = workspaceSessionId;
    state.workspaceWritable = Boolean(status?.writable);
    state.workspaceSnapshot = await buildWorkspaceSnapshot();
    state.processedWorkspaceRequests.clear();
    startWorkspaceTimers(status);
    renderWorkspaceState();
  } catch (error) {
    el("workspace-error").textContent = workspaceErrorMessage(error);
  } finally {
    button.disabled = false;
  }
}

async function disconnectWorkspace(notifyServer) {
  const sessionId = state.workspaceSessionId;
  if (notifyServer && sessionId) {
    try {
      await api("/api/v1/workspace/disconnect", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ workspaceSessionId: sessionId })
      });
    } catch (_) { /* expiry is equivalent to disconnect */ }
  }
  stopWorkspaceLocal();
}

function stopWorkspaceLocal() {
  if (state.workspaceHeartbeatTimer) clearInterval(state.workspaceHeartbeatTimer);
  if (state.workspaceScanTimer) clearInterval(state.workspaceScanTimer);
  state.workspaceHeartbeatTimer = null;
  state.workspaceScanTimer = null;
  state.workspaceHandle = null;
  state.workspaceSessionId = null;
  state.workspaceWritable = false;
  state.workspaceSnapshot = null;
  state.workspaceScanBusy = false;
  state.processedWorkspaceRequests.clear();
  renderWorkspaceState();
}

function startWorkspaceTimers(status) {
  if (state.workspaceHeartbeatTimer) clearInterval(state.workspaceHeartbeatTimer);
  if (state.workspaceScanTimer) clearInterval(state.workspaceScanTimer);
  const heartbeatEvery = Math.max(5000, Number(status?.heartbeatIntervalMillis) || 10000);
  state.workspaceHeartbeatTimer = setInterval(sendWorkspaceHeartbeat, heartbeatEvery);
  state.workspaceScanTimer = setInterval(scanAndReportWorkspaceChanges, 8000);
}

async function sendWorkspaceHeartbeat() {
  if (!state.workspaceHandle || !state.workspaceSessionId) return;
  try {
    if (!await ensureHandlePermission(state.workspaceHandle, "read", false)) {
      throw new Error("目录读取权限已撤销");
    }
    await api("/api/v1/workspace/heartbeat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ workspaceSessionId: state.workspaceSessionId })
    });
  } catch (error) {
    el("workspace-error").textContent = workspaceErrorMessage(error);
    stopWorkspaceLocal();
  }
}

async function handleWorkspaceRequest(request) {
  if (!request?.requestId || state.processedWorkspaceRequests.has(request.requestId)) return;
  state.processedWorkspaceRequests.add(request.requestId);
  while (state.processedWorkspaceRequests.size > 128) {
    state.processedWorkspaceRequests.delete(state.processedWorkspaceRequests.values().next().value);
  }
  let result;
  try {
    if (!state.workspaceHandle || request.workspaceSessionId !== state.workspaceSessionId) {
      throw workspaceFailure("workspace_session_stale", "电脑目录会话已经断开或被替换");
    }
    if (Date.now() > Number(request.expiresAt || 0)) {
      throw workspaceFailure("request_expired", "电脑文件请求已经过期");
    }
    switch (request.operation) {
      case "list": result = await listWorkspaceDirectory(request); break;
      case "read": result = await readWorkspaceFile(request); break;
      case "stat": result = await statWorkspaceEntry(request); break;
      case "write": result = await writeWorkspaceFile(request); break;
      case "mkdir": result = await createWorkspaceDirectory(request); break;
      default: throw workspaceFailure("unsupported_operation", "不支持的电脑工作区操作");
    }
  } catch (error) {
    result = {
      success: false,
      errorCode: error.workspaceCode || browserErrorCode(error),
      errorMessage: workspaceErrorMessage(error).slice(0, 500)
    };
  }
  try {
    await api("/api/v1/workspace/result", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        workspaceSessionId: request.workspaceSessionId,
        requestId: request.requestId,
        ...result
      })
    });
  } catch (error) {
    el("workspace-error").textContent = workspaceErrorMessage(error);
  }
}

async function listWorkspaceDirectory(request) {
  const path = validateWorkspacePath(request.path, true);
  const directory = await resolveWorkspaceDirectory(path);
  const entries = [];
  const maxEntries = Math.min(500, Math.max(1, Number(request.maxEntries) || 500));
  for await (const [name, handle] of directory.entries()) {
    if (entries.length >= maxEntries) {
      throw workspaceFailure("directory_too_large", `目录超过 ${maxEntries} 项，请读取更具体的子目录`);
    }
    const childPath = path === "." ? name : `${path}/${name}`;
    validateWorkspacePath(childPath, false);
    await verifyHandleInsideWorkspace(handle, childPath);
    if (handle.kind === "directory") {
      entries.push({ name, path: childPath, directory: true });
    } else {
      const file = await handle.getFile();
      entries.push({
        name,
        path: childPath,
        directory: false,
        size: file.size,
        lastModified: file.lastModified
      });
    }
  }
  entries.sort((left, right) => Number(right.directory) - Number(left.directory) || left.name.localeCompare(right.name));
  return { success: true, entries };
}

async function readWorkspaceFile(request) {
  const path = validateWorkspacePath(request.path, false);
  const handle = await resolveWorkspaceFile(path, false);
  const fileData = await readTextFileHandle(handle, Math.min(1024 * 1024, Number(request.maxBytes) || 1024 * 1024));
  return {
    success: true,
    content: fileData.content,
    sha256: fileData.sha256,
    size: fileData.size,
    lastModified: fileData.lastModified,
    directory: false
  };
}

async function statWorkspaceEntry(request) {
  const path = validateWorkspacePath(request.path, true);
  if (path === ".") return { success: true, directory: true };
  try {
    await resolveWorkspaceDirectory(path);
    return { success: true, directory: true };
  } catch (error) {
    if (!isMissingOrWrongKind(error)) throw error;
  }
  const handle = await resolveWorkspaceFile(path, false);
  const file = await handle.getFile();
  return {
    success: true,
    directory: false,
    size: file.size,
    lastModified: file.lastModified
  };
}

async function writeWorkspaceFile(request) {
  if (!state.workspaceWritable) {
    throw workspaceFailure("write_permission_missing", "这个目录只授予了读取权限");
  }
  const path = validateWorkspacePath(request.path, false);
  const content = String(request.content ?? "");
  const bytes = new TextEncoder().encode(content);
  const maxBytes = Math.min(1024 * 1024, Number(request.maxBytes) || 1024 * 1024);
  if (bytes.byteLength > maxBytes) throw workspaceFailure("file_too_large", "写入内容超过 1 MiB 上限");
  if (!await ensureHandlePermission(state.workspaceHandle, "readwrite", false)) {
    throw workspaceFailure("write_permission_revoked", "目录写入权限已撤销，请重新点击选择目录");
  }

  let handle = null;
  let before = "";
  let beforeSha = null;
  let created = false;
  try {
    handle = await resolveWorkspaceFile(path, false);
    const current = await readTextFileHandle(handle, maxBytes);
    before = current.content;
    beforeSha = current.sha256;
    if (!request.expectedSha256) {
      throw workspaceFailure("expected_hash_required", "覆盖已有文件必须先读取并提供 expected_sha256");
    }
    if (beforeSha !== String(request.expectedSha256).toLowerCase()) {
      throw workspaceFailure("file_conflict", "文件已被其他程序修改，SHA-256 与读取时不一致");
    }
  } catch (error) {
    if (!isNotFound(error)) throw error;
    if (request.expectedSha256) {
      throw workspaceFailure("file_conflict", "预期覆盖的文件已经不存在");
    }
    created = true;
  }

  const diffPreview = buildWorkspaceDiff(before, content, created);
  const approved = await confirmWorkspaceWrite(path, diffPreview, created);
  if (!approved) throw workspaceFailure("browser_rejected", "电脑浏览器拒绝了这次写入");
  if (Date.now() > Number(request.expiresAt || 0)) {
    throw workspaceFailure("request_expired", "确认完成时请求已经过期，未写入文件");
  }

  if (created) {
    try {
      await resolveWorkspaceFile(path, false);
      throw workspaceFailure("file_conflict", "确认期间目标文件已被其他程序创建");
    } catch (error) {
      if (!isNotFound(error)) throw error;
    }
    handle = await resolveWorkspaceFile(path, true);
    const justCreated = await handle.getFile();
    if (justCreated.size !== 0) {
      throw workspaceFailure("file_conflict", "创建文件时检测到并发写入，已取消覆盖");
    }
  } else {
    handle = await resolveWorkspaceFile(path, false);
    const latest = await readTextFileHandle(handle, maxBytes);
    if (latest.sha256 !== beforeSha) {
      throw workspaceFailure("file_conflict", "Diff 确认后文件又被修改，已取消写入");
    }
  }

  let writable;
  try {
    writable = await handle.createWritable({ mode: "exclusive", keepExistingData: false });
  } catch (error) {
    if (!(error instanceof TypeError)) throw error;
    writable = await handle.createWritable({ keepExistingData: false });
  }
  try {
    await writable.write(bytes);
    await writable.close();
  } catch (error) {
    try { await writable.abort(); } catch (_) { /* close/write already failed */ }
    throw error;
  }
  const written = await readTextFileHandle(handle, maxBytes);
  await refreshWorkspaceSnapshot();
  return {
    success: true,
    sha256: written.sha256,
    size: written.size,
    lastModified: written.lastModified,
    directory: false,
    created,
    diffPreview
  };
}

async function createWorkspaceDirectory(request) {
  if (!state.workspaceWritable) {
    throw workspaceFailure("write_permission_missing", "这个目录只授予了读取权限");
  }
  const path = validateWorkspacePath(request.path, false);
  if (!await ensureHandlePermission(state.workspaceHandle, "readwrite", false)) {
    throw workspaceFailure("write_permission_revoked", "目录写入权限已撤销，请重新点击选择目录");
  }
  const segments = path.split("/");
  const name = segments.pop();
  const parent = await resolveWorkspaceDirectory(segments.length ? segments.join("/") : ".");
  let created = false;
  try {
    const existing = await parent.getDirectoryHandle(name, { create: false });
    await verifyHandleInsideWorkspace(existing, path);
  } catch (error) {
    if (!isNotFound(error)) throw error;
    const directory = await parent.getDirectoryHandle(name, { create: true });
    await verifyHandleInsideWorkspace(directory, path);
    created = true;
  }
  await refreshWorkspaceSnapshot();
  return { success: true, directory: true, created };
}

async function confirmWorkspaceWrite(path, diffPreview, created) {
  const dialog = el("workspace-write-dialog");
  if (dialog.open) throw workspaceFailure("browser_busy", "已有另一个文件等待电脑确认");
  el("workspace-write-path").textContent = `${created ? "创建" : "修改"}：${state.workspaceHandle.name}/${path}`;
  el("workspace-write-diff").textContent = diffPreview;
  dialog.returnValue = "";
  return new Promise(resolve => {
    const finish = () => resolve(dialog.returnValue === "default");
    dialog.addEventListener("close", finish, { once: true });
    dialog.showModal();
  });
}

async function resolveWorkspaceDirectory(path) {
  const normalized = validateWorkspacePath(path, true);
  let handle = state.workspaceHandle;
  if (normalized !== ".") {
    for (const segment of normalized.split("/")) handle = await handle.getDirectoryHandle(segment, { create: false });
    await verifyHandleInsideWorkspace(handle, normalized);
  }
  return handle;
}

async function resolveWorkspaceFile(path, create) {
  const normalized = validateWorkspacePath(path, false);
  const segments = normalized.split("/");
  const name = segments.pop();
  const parentPath = segments.length ? segments.join("/") : ".";
  const parent = await resolveWorkspaceDirectory(parentPath);
  const handle = await parent.getFileHandle(name, { create: Boolean(create) });
  await verifyHandleInsideWorkspace(handle, normalized);
  return handle;
}

async function verifyHandleInsideWorkspace(handle, expectedPath) {
  const resolved = await state.workspaceHandle.resolve(handle);
  if (!resolved || resolved.join("/").normalize("NFC") !== expectedPath.normalize("NFC")) {
    throw workspaceFailure("path_outside_workspace", "浏览器解析结果不在授权目录内");
  }
}

async function readTextFileHandle(handle, maxBytes) {
  const file = await handle.getFile();
  if (file.size > maxBytes) throw workspaceFailure("file_too_large", `文本文件超过 ${maxBytes} bytes 上限`);
  const buffer = await file.arrayBuffer();
  const bytes = new Uint8Array(buffer);
  if (bytes.slice(0, Math.min(bytes.length, 4096)).includes(0)) {
    throw workspaceFailure("binary_file", "检测到二进制文件，首版只允许 UTF-8 文本");
  }
  let content;
  try {
    content = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch (_) {
    throw workspaceFailure("invalid_utf8", "文件不是有效的 UTF-8 文本");
  }
  return {
    content,
    sha256: await sha256Hex(buffer),
    size: file.size,
    lastModified: file.lastModified
  };
}

async function sha256Hex(buffer) {
  const digest = await crypto.subtle.digest("SHA-256", buffer);
  return [...new Uint8Array(digest)].map(value => value.toString(16).padStart(2, "0")).join("");
}

async function ensureHandlePermission(handle, mode, requestIfNeeded) {
  const options = { mode };
  if (await handle.queryPermission(options) === "granted") return true;
  if (!requestIfNeeded) return false;
  return await handle.requestPermission(options) === "granted";
}

async function buildWorkspaceSnapshot() {
  const snapshot = new Map();
  const scan = { count: 0, partial: false };
  await scanWorkspaceDirectory(state.workspaceHandle, "", snapshot, scan);
  return { entries: snapshot, partial: scan.partial };
}

async function scanWorkspaceDirectory(directory, prefix, snapshot, scan) {
  for await (const [name, handle] of directory.entries()) {
    if (scan.count >= 5000) {
      scan.partial = true;
      return;
    }
    const path = prefix ? `${prefix}/${name}` : name;
    try { validateWorkspacePath(path, false); } catch (_) { scan.partial = true; continue; }
    scan.count++;
    if (handle.kind === "directory") {
      snapshot.set(path, "directory");
      if (!WORKSPACE_SCAN_EXCLUDES.has(name.toLowerCase())) {
        await scanWorkspaceDirectory(handle, path, snapshot, scan);
      }
    } else {
      const file = await handle.getFile();
      snapshot.set(path, `file:${file.size}:${file.lastModified}`);
    }
  }
}

async function scanAndReportWorkspaceChanges() {
  if (!state.workspaceHandle || !state.workspaceSessionId || state.workspaceScanBusy) return;
  state.workspaceScanBusy = true;
  try {
    if (!await ensureHandlePermission(state.workspaceHandle, "read", false)) {
      throw workspaceFailure("read_permission_revoked", "目录读取权限已撤销");
    }
    const previous = state.workspaceSnapshot;
    const next = await buildWorkspaceSnapshot();
    if (!previous) {
      state.workspaceSnapshot = next;
      return;
    }
    const changes = diffWorkspaceSnapshots(previous, next);
    for (let offset = 0; offset < changes.length; offset += 200) {
      await api("/api/v1/workspace/changes", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          workspaceSessionId: state.workspaceSessionId,
          reportId: requestId(),
          changes: changes.slice(offset, offset + 200),
          partialScan: next.partial && offset === 0
        })
      });
    }
    if (!changes.length && next.partial && !previous.partial) {
      await api("/api/v1/workspace/changes", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          workspaceSessionId: state.workspaceSessionId,
          reportId: requestId(),
          changes: [],
          partialScan: true
        })
      });
    }
    state.workspaceSnapshot = next;
  } catch (error) {
    el("workspace-error").textContent = workspaceErrorMessage(error);
    if (error.workspaceCode?.includes("permission")) stopWorkspaceLocal();
  } finally {
    state.workspaceScanBusy = false;
  }
}

async function refreshWorkspaceSnapshot() {
  if (!state.workspaceHandle || state.workspaceScanBusy) return;
  state.workspaceScanBusy = true;
  try { state.workspaceSnapshot = await buildWorkspaceSnapshot(); }
  finally { state.workspaceScanBusy = false; }
}

function browserErrorCode(error) {
  const name = error?.name || "browser_error";
  if (name === "NotFoundError") return "file_not_found";
  if (name === "NotAllowedError" || name === "SecurityError") return "permission_denied";
  if (name === "InvalidStateError" || name === "NoModificationAllowedError") return "file_busy";
  if (name === "TypeMismatchError") return "entry_type_mismatch";
  return String(name).replace(/[^A-Za-z0-9_-]/g, "_").toLowerCase().slice(0, 80);
}

function workspaceErrorMessage(error) {
  if (error?.name === "AbortError") return "已取消选择或确认";
  if (error?.name === "NotAllowedError") return "浏览器没有授予所需的目录权限";
  if (error?.name === "NotFoundError") return "文件或目录不存在";
  return error?.message || "电脑工作区操作失败";
}

function isNotFound(error) {
  return error?.name === "NotFoundError" || error?.workspaceCode === "file_not_found";
}

function isMissingOrWrongKind(error) {
  return isNotFound(error) || error?.name === "TypeMismatchError";
}

const WORKSPACE_SCAN_EXCLUDES = new Set([
  ".git", ".gradle", ".idea", "build", "dist", "out", "node_modules", "vendor", "target"
]);

async function revokeSelf() {
  try { await api("/api/v1/unpair", { method: "POST" }); } catch (_) { /* clear locally anyway */ }
  clearSession();
  showPair("此浏览器已撤销，请使用新的配对码连接。 ");
}

function clearSession() {
  state.authenticated = false;
  if (state.eventAbort) state.eventAbort.abort();
  state.eventAbort = null;
  stopWorkspaceLocal();
}

function setConnection(online) {
  const status = el("connection-status");
  status.textContent = online ? "已连接 · SSE 实时同步" : "连接中断，正在重连…";
  status.classList.toggle("online", online);
  if (online) el("app-error").textContent = "";
}

function handleAppError(error) {
  const message = error?.message || "请求失败";
  if (!state.authenticated) showPair(message); else el("app-error").textContent = message;
}

function requestId() {
  if (crypto.randomUUID) return crypto.randomUUID();
  return `web-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

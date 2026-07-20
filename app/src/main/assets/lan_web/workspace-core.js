(function installMurongWorkspaceCore(root) {
  "use strict";

  function workspaceFailure(code, message) {
    const error = new Error(message);
    error.workspaceCode = code;
    return error;
  }

  function validateWorkspacePath(raw, allowRoot) {
    const value = String(raw ?? "");
    if (!value || value.length > 1024 || value !== value.trim()) {
      throw workspaceFailure("invalid_path", "相对路径无效");
    }
    if (value.includes("\\") || value.includes(":") || value.startsWith("/")) {
      throw workspaceFailure("invalid_path", "不允许绝对路径、盘符或反斜杠");
    }
    if ([...value].some(character => {
      const codePoint = character.codePointAt(0);
      return codePoint === 0 || codePoint < 0x20 || codePoint === 0x7f;
    })) {
      throw workspaceFailure("invalid_path", "路径包含控制字符");
    }
    if (value === ".") {
      if (!allowRoot) throw workspaceFailure("invalid_path", "该操作不能指向工作区根目录");
      return value;
    }
    const segments = value.split("/");
    if (segments.length > 64 || segments.some(segment =>
      !segment || segment === "." || segment === ".." || segment.length > 255
    )) {
      throw workspaceFailure("path_outside_workspace", "路径包含空段、.、.. 或层级过深");
    }
    return segments.join("/").normalize("NFC");
  }

  function buildWorkspaceDiff(before, after, created) {
    if (before === after) return "(内容没有变化)";
    const oldLines = before.replaceAll("\r\n", "\n").split("\n");
    const newLines = after.replaceAll("\r\n", "\n").split("\n");
    let prefix = 0;
    while (prefix < oldLines.length && prefix < newLines.length && oldLines[prefix] === newLines[prefix]) {
      prefix++;
    }
    let oldSuffix = oldLines.length - 1;
    let newSuffix = newLines.length - 1;
    while (oldSuffix >= prefix && newSuffix >= prefix && oldLines[oldSuffix] === newLines[newSuffix]) {
      oldSuffix--;
      newSuffix--;
    }
    const output = [`--- ${created ? "/dev/null" : "before"}`, "+++ after", `@@ line ${prefix + 1} @@`];
    const contextStart = Math.max(0, prefix - 3);
    for (let index = contextStart; index < prefix; index++) output.push(`  ${oldLines[index]}`);
    const oldChanged = oldLines.slice(prefix, oldSuffix + 1);
    const newChanged = newLines.slice(prefix, newSuffix + 1);
    oldChanged.slice(0, 180).forEach(line => output.push(`- ${line}`));
    newChanged.slice(0, 180).forEach(line => output.push(`+ ${line}`));
    if (oldChanged.length > 180 || newChanged.length > 180) {
      output.push("... (Diff 过长，已截断；完整内容不会在此预览重复显示)");
    }
    for (let index = newSuffix + 1; index < Math.min(newLines.length, newSuffix + 4); index++) {
      output.push(`  ${newLines[index]}`);
    }
    return output.join("\n").slice(0, 64 * 1024);
  }

  function diffWorkspaceSnapshots(previous, next) {
    const changes = [];
    for (const [path, signature] of next.entries) {
      if (!previous.entries.has(path)) {
        changes.push({ path, kind: "created", directory: signature === "directory" });
      } else if (previous.entries.get(path) !== signature) {
        changes.push({ path, kind: "modified", directory: signature === "directory" });
      }
    }
    for (const [path, signature] of previous.entries) {
      if (!next.entries.has(path)) {
        changes.push({ path, kind: "deleted", directory: signature === "directory" });
      }
    }
    return changes;
  }

  root.MurongWorkspaceCore = Object.freeze({
    buildWorkspaceDiff,
    diffWorkspaceSnapshots,
    validateWorkspacePath,
    workspaceFailure
  });
})(globalThis);

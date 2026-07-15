# MurongAgent 1.8

## 1.9 / 26071519 — 2026-07-15

### Fixed
- **扩展包检测失败**: 修复了 `ToolchainManager.isValidManifest()` 对 manifest 中绝对路径 symlink（Termux keyring GPG 文件）验证过于严格的问题。这些 link target 以 `/` 开头导致整个 manifest 被拒绝，扩展包环境不可用。现放行绝对路径链接，并在运行时跳过它们的创建。

### Changed
- **`ToolchainManager`**: `isValidManifest()` 的 links 验证允许 target 以 `/` 开头的条目通过检查。
- **`ToolchainManager`**: `ensureToolchainLinks()` 运行时跳过绝对路径 symlink 的创建，避免在沙箱中产生损坏链接。
- **`murong-terminal-extension build.gradle.kts`**: 构建时增加 `.filterNot { it.target.startsWith("/") }`，防止绝对路径链接进入 manifest。
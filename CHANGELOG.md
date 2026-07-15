# Changelog

## 1.8 / 26071518 — 2026-07-15

### Fixed
- **pkg bad interpreter**: 扩展工具链的 shell 脚本（pkg 等）安装到应用私有目录后自动重写 Termux 固定 prefix，不再出现 `bad interpreter` 错误（配合 `murong-terminal-extension` 使用）。

### Added
- **编辑页快速运行**: 项目编辑器工具栏新增 ▶ 运行按钮和终端按钮，支持 Python、Shell、JavaScript、TypeScript、Ruby、PHP、Perl、Lua、R、Tcl、C、C++、Go、Rust、Java、Kotlin 文件运行。
- **输出面板**: 运行结果显示在编辑器下方，实时流式输出（限 512 KiB），含停止、清空、退出码显示。
- **依赖安装确认**: 运行前检测缺工具时弹出对话框确认下载。
- **终端运行**: 复制转义命令并切换到终端标签。

### Changed
- **`ToolchainManager`**:
  - 安装工具链时对文本文件执行 Termux 路径重写（`/data/data/com.termux` → 应用私有目录）。
  - 新增 `findCommandPath()` 统一查找 bundle 命令和系统 PATH 命令。
  - 新增 `hasRelocatablePackageManager()`，暂时返回 false 阻止不可重定位的 APT 自动安装。
- **运行环境变量**：补齐 `PREFIX`、`TERMUX__PREFIX`、`TERMUX_APP_PACKAGE_MANAGER=apt`。
- **Java 运行**：使用 `javac -d` 编译后 `java -cp` 执行，支持 `package` 声明。
- **编译型语言**：C/C++/Go/Rust 的构建和运行分离，构建产物写入 `.murong-run/` 目录。

### Security
- 扩展加载仅接受同签名 APK。
- Manifest 中所有 files、links、commands 路径经 canonicalize 和越界校验。
- 安装 I/O 失败时自动清理半成品。

### Removed
- 废弃的 `isToolAvailable()`（单命令检查），替换为 `areToolsAvailable()`（多命令）。

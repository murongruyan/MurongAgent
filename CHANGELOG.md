# Changelog

## 1.10 / 26071620 — 2026-07-16

### Fixed
- **扩展环境仍被判定不可用**: 绝对目标 symlink 在运行时被跳过后，不再进入必需文件集合，避免 `requiredFiles.all(File::exists)` 永远返回 false。
- **兼容扩展包 1.4**: 即使旧扩展 manifest 仍包含 Termux 绝对 keyring 链接，主程序也能正确安装并启用其余工具链文件。
- **POSIX `[` 命令被误判**: 工具链命令白名单明确接受标准 `[`/`test` 入口，不再因此拒绝整个扩展 manifest。
- **脚本命令被替换为自指断链**: 当命令入口和已解包脚本是同一路径时保留原文件，仅为 native 命令创建 `bin/` symlink。
- **`pkg`/`apt` 只有入口却无法安装**: 扩展环境通过 PRoot 映射官方 Termux 编译期前缀，并继承正确的包管理器环境；`pkg install`、`apt`、`dpkg` 不再被诊断脚本禁用。
- **高 targetSdk 下 PRoot 终端循环退出**: 首个 Termux ELF 通过 Android system linker 加载，`PROOT_LOADER` 使用扩展 APK 内原生库，避免应用数据目录 W^X 限制导致 `Permission denied` 和 `code 1` 重建。
- **动态安装命令仍被 W^X 拒绝**: 启用 `termux-exec` linker 模式，使 Python、pip、clang 等由 `pkg` 安装到工具链目录后的命令可以直接执行。
- **APT 仓库公钥链接缺失**: 扩展提供的绝对 keyring 目标正规化为工具链内相对链接，恢复仓库签名校验。
- **包管理器覆盖的命令被还原**: 工具链完整性修复只重建缺失或断开的入口，保留 `apt/dpkg` 已安装或升级的有效文件。
- **升级主程序后 APT 后装包消失**: APT 可删除的 `.murong-keep` 目录占位文件不再参与工具链完整性判定，避免冷启动误删并重建整个工具链，保留 Python、pip、clang 等后装包。
- **系统终端放大后泄漏颜色码**: 系统环境使用 Android `sh` 时改用无 ANSI 的提示符，终端尺寸变化不再显示 `39;172m` 等残缺控制序列；系统 Bash 仍保留彩色提示符。

### Changed
- **模型命令工具支持双环境**: `shell` 新增 `system` 与 `extension` 选择；系统环境继续使用 Root `su`，扩展环境以应用 UID 进入 PRoot/Termux，`pkg`/`apt` 安装的文件可与页面终端共同维护。
- **模型命令工作目录稳定化**: 主代理的 shell 命令默认固定到当前项目作用域，不再受两个持久 Root 会话随机复用及历史 `cd` 状态影响，也可通过 `working_directory` 显式覆盖。
- **工具链链接处理一致化**: manifest 验证、安装、缓存校验和最终可用性判断统一只处理可在应用沙箱内解析的相对目标链接。
- **工具链安装指纹升级**: 安装语义更新为 `relocatable-v2`，现有缓存会自动重建并应用包数据库、keyring 与 PRoot 兼容层。
- **项目运行器接入扩展包环境**: 自动安装依赖及 Python/Node 等项目命令与交互终端使用同一前缀映射，动态安装的命令可直接运行。
- **扩展运行时要求升级**: 完整包管理器环境要求扩展 manifest 提供 APK 内 `proot-loader` 入口；不满足时不会误报为可用包管理器。

## 1.9 / 26071519 — 2026-07-15

### Fixed
- **扩展包检测失败**: 修复了 `ToolchainManager.isValidManifest()` 对 manifest 中绝对路径 symlink（Termux keyring GPG 文件）验证过于严格的问题。这些 link target 以 `/` 开头导致整个 manifest 被拒绝，扩展包环境不可用。现放行绝对路径链接，并在运行时跳过它们的创建。

### Changed
- **`ToolchainManager`**: `isValidManifest()` 的 links 验证允许 target 以 `/` 开头的条目通过检查。
- **`ToolchainManager`**: `ensureToolchainLinks()` 运行时跳过绝对路径 symlink 的创建，避免在沙箱中产生损坏链接。
- **`murong-terminal-extension build.gradle.kts`**: 构建时增加 `.filterNot { it.target.startsWith("/") }`，防止绝对路径链接进入 manifest。

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

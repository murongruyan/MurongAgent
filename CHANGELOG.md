# Changelog

## Unreleased — 2026-07-23

### Added

- **统一设备连接体系**: 手机与电脑均使用稳定的 16 位本机 ID，可通过同 GitHub 账号、同网签名发现、已授权 ADB、临时验证码、安全密码或接收端确认建立连接；已配对设备可免码重连。
- **双端连接治理**: Android 通知与 Desktop 系统通知均提供同意、拒绝和拉黑；黑名单、免打扰、申请限速、过期清理与双方撤销持久生效。
- **端到端安全链路**: 设备身份使用 P-256 签名，首次连接使用临时 P-256 ECDH、HKDF-SHA256 与 AES-256-GCM；验证码和安全密码改为带双 nonce 的 SCRAM-SHA-256，服务器与中继无法获得访问令牌或同步密钥。
- **公网稳定 ID Relay v2**: 新增签名设备登记、加密邀请和一次性隧道协议；旧 `MR1` 只保留兼容，不再作为普通用户的主要连接方式。
- **完整 Desktop Agent 工作台**: 单 EXE 提供多标签持久 PTY 终端、代码编辑器与语法高亮、图片和 Markdown 预览、浏览器、Git、子代理、侧边聊天、项目文件树、托盘和单实例运行。
- **跨端聊天同步**: 手机与电脑可在既有端到端加密设备通道中同步聊天记录，按页传输任意总量历史；重复同步幂等，双方同时修改时保留冲突副本而不覆盖原记录。

### Changed

- **远程控制界面**: 手机与电脑统一为“本机 ID、临时验证码、安全密码、已配对设备、输入设备码、当前环境设备”的直接操作结构，诊断项收进更多设置，不再暴露 WSS、HTTP 或旧云中继概念。
- **Desktop 导航与布局**: 设置改为分类居中弹窗；任务操作进入右键菜单；终端可选择底部或侧边停靠，工作台宽度、聊天边距和摘要轨道均可调整并持久化。
- **发布矩阵**: 统一工作流继续构建 Android 主程序、终端扩展及 Windows、macOS、Linux 的 amd64/arm64 桌面包，并移除已废弃的独立 Cloud Relay 二进制产物。

### Fixed

- **设备同步大历史截断**: 修复外层 HTTP 读取边界导致的 `unexpected EOF`，改为透明分页和逐页独立加密校验，不再用单个固定总大小限制聊天记录。
- **Codex 同步登录可用性**: Desktop 选择同步来的 ChatGPT/Codex 账号时不再错误要求 API Key；`thread/start.dynamicTools` 与 `experimentalApi` capability 保持一致。
- **Desktop 模型与输入交互**: 修复异步模型选择读取空 DOM 节点；聊天输入框支持跨重启的最近 50 条历史，首次按上键会提取当前任务最近一次用户输入。
- **Desktop 生命周期**: 关闭窗口默认收进系统托盘，重复启动只激活已有窗口；应用图标、托盘预览和窗口资源统一使用正式品牌资产。
- **撤销配对一致性**: Desktop 撤销会先通知手机删除信任，再清理本机令牌和同步密钥；远端不可达时保留本机凭据并明确报错，避免出现单边假撤销。

### Security

- API Key、ChatGPT/Codex 登录令牌和 GitHub Token 只在已配对设备的端到端加密同步包中传输，不进入 Relay；Android 使用 Keystore，Desktop 使用 DPAPI、Keychain 或系统凭据保护落盘秘密。
- 安全密码只持久化独立 salt、迭代次数、StoredKey 与 ServerKey；临时验证码成功后立即轮换，连续失败会轮换并进入冷却。
- Root、文件写入、终端和 GitHub 修改继续遵守原有审批模式；设备信任不会自动扩大 Agent 工具权限。

## 1.30 / 26071902 — 2026-07-19

### Added

- **完整跨平台发布套件**: 一份 `build-all.yml` 构建 Android 主应用、终端扩展、Windows/macOS/Linux amd64/arm64 桌面端，以及双架构自托管中继，共 10 个正式包并生成精确 SHA-256 清单。
- **完整桌面 Agent**: 桌面端可直接选择或新建项目，使用电脑文件与多终端，并提供模型、推理深度、Skill、MCP、子代理、目标和计划入口；手机可安全查看和指挥共享任务。
- **端到端加密异网协作**: 手机和电脑可通过官方或自托管 Relay 异网配对，服务器只转发有界密文。
- **Go 云后端**: 官网、OAuth、更新发布、APK 分发、统计、管理后台和密文中继迁移到兼容旧 URL 的 Go 服务，并接入 GitHub Actions 一键原子部署。

### Changed

- **发布与服务器同步**: 完整 GitHub Release 成为正式产物来源；主应用和终端扩展可在构建后同步到更新后端，后端部署不再依赖宝塔手工上传网页或 PHP 上传限制。
- **项目说明**: README 改为当前 Android、桌面、跨端和 Go 后端的真实架构，不再引用已删除的分离构建工作流。

## 1.27 / 26071704 — 2026-07-17

### Fixed
- **共享存储普通文件不可见**: 设置页新增“设备权限 → 文件访问”的全部文件访问入口和状态；授权后终端与 Agent 可读取 `/storage/emulated/0` 中的 ZIP、IMG、SH 等普通文件，不再只显示目录。
- **未授权时的误导性结果**: 终端启动和 Codex 上下文会明确提示共享存储受限，模型不会把空目录误报为“已检查文件”。

## 1.26 / 26071703 — 2026-07-17

### Fixed
- **Codex 工具与回答顺序**: 不再在 turn 开始时预插空助手消息；命令先执行时，最终回答会出现在命令/结果之后，不会在导出和聊天页中倒置顺序。
- **Codex 命令可审计性**: 本地聊天工具卡保留实际文件路径，仍会隐藏令牌、密码等敏感值；不再把每条路径都替换成 `[REDACTED_PATH]`。

## 1.25 / 26071702 — 2026-07-17

### Fixed
- **Codex 命令过程**: 官方 app-server 的 `commandExecution` 项现在会显示实际命令、工作目录、退出码、耗时和可用输出，不再只显示泛化的“命令操作”。
- **聊天到底部**: 执行过程卡完成并展开后的下一次布局会再次定位末项，最后一条工具输出不会被输入栏挡住或留在不可达位置。
- **二级选择菜单**: 后端、模型、推理、速度的二级菜单改为从被点按的一级条目旁展开；速度不会再错误地弹到一级菜单顶部。

### Changed
- **额度详情**: 点击输入栏旁的额度圆环会打开贴近圆环的小浮层，可点空白处或“关闭”收起，并保留刷新入口。

## 1.24 / 26071701 — 2026-07-17

### Fixed
- **响应速度默认值**: 速度菜单现在始终提供“默认（跟随官方模型）”；选择它会移除 `serviceTier` 覆盖，恢复由官方模型决定的默认速度。

### Changed
- **聊天页额度入口**: 移除顶部额度文字卡片，改为输入栏历史下键旁的剩余额度圆环。圆环随本周剩余额度缩短，点按才显示本周/短时额度、重置时间与刷新。

## 1.23 / 26071633 — 2026-07-16

### Fixed
- **模型总控二级菜单**: 选择“后端与连接 / 模型 / 推理深度 / 响应速度”会在一级菜单关闭后再打开对应二级菜单，避免部分 Android / 投屏环境将同一次点击一并消耗掉。

## 1.22 / 26071632 — 2026-07-16

### Fixed
- **模型总控的能力缺失状态**: 官方模型目录尚未返回时，推理深度与响应速度会保留在一级菜单中并明确标为“待读取官方模型目录确认”；不再无提示地消失，也不会捏造可用档位。

## 1.21 / 26071631 — 2026-07-16

### Changed
- **聊天模型控制重做**: 输入栏现在以“后端 · 模型 · 推理 · 速度”的分层方式组织选择，不再把不同概念挤成不可辨识的小标签。
- **官方模型目录**: ChatGPT / Codex 后端读取 app-server `model/list` 的实际可用模型、该模型支持的推理深度及速度档位；保留服务端给出的顺序，不臆造不属于当前账户的模型。
- **API 与官方后端并列**: 后端菜单同时提供 ChatGPT / Codex 和全部已配置 API / 中转连接，切换后模型、推理和实际请求路径同步变化。

## 1.20 / 26071630 — 2026-07-16

### Fixed
- **登录后仍显示旧 API 模型**: ChatGPT / Codex 官方登录确认后会自动切换到官方后端；聊天页实际发送请求不再继续走此前的 API Key / 中转配置。

### Added
- **聊天页后端快速切换**: 输入栏的配置选择同时列出“ChatGPT / Codex”和所有已配置 API / 中转连接，选择 API 连接会明确切回 API 后端，模型标签随之对应实际请求路径。
- **官方周额度显示**: 聊天页直接从官方 `account/rateLimits/read` 读取 ChatGPT / Codex 的短时及长期额度；显示本周剩余百分比、距离重置时间和手动刷新入口，不读取登录令牌。

## 1.19 / 26071629 — 2026-07-16

### Fixed
- **Clash / VPN 网络下的 ChatGPT 登录**: 现在优先读取 Android 当前网络公布的数值型 HTTP 代理（本机 Clash 端口），让官方静态 app-server 由 VPN 处理 DNS 与路由；没有可用系统代理时仍回退到受限回环 CONNECT 桥。

## 1.18 / 26071628 — 2026-07-16

### Fixed
- **浏览器已登录但设备码轮询断开**: 回环 CONNECT 代理只对未认证的请求头保留 15 秒超时；TLS 隧道建立后不再以 60 秒空闲超时切断官方设备码授权轮询。

## 1.17 / 26071627 — 2026-07-16

### Fixed
- **ChatGPT 授权完成后无法确认**: 设备码重试会重建旧的轮询会话；浏览器页面即使复用同一授权网址，也会按新的登录尝试重新打开。
- **设备码操作体验**: 设备码新增复制和手动打开授权页；“检查登录状态”与红色“取消本次登录”分离，取消前会二次确认，取消后会清空旧设备码。
- **登录状态提示**: 将请求错误明确标为状态刷新失败，不再错误提示为终端扩展安装问题。

## 1.16 / 26071626 — 2026-07-16

### Fixed
- **ChatGPT 设备码 CONNECT 隧道超时**: TLS 字节流不再在代理缓冲区中等待连接关闭才写出；ClientHello 与服务器响应会实时转发，恢复官方设备码请求。

## 1.15 / 26071625 — 2026-07-16

### Fixed
- **静态 Codex TLS 根证书**: Linux-musl `codex-app-server` 启动时显式使用终端扩展提供的 PEM 根证书包，并强制所有 OpenAI HTTPS 请求经受限本机桥接；不会关闭或绕过 TLS 校验。

## 1.14 / 26071624 — 2026-07-16

### Fixed
- **回环 HTTPS 隧道未接通**: Android 可将泛用 loopback 解析为 IPv6 `::1`，而官方 app-server 使用 IPv4 `127.0.0.1` 代理地址；现明确绑定 IPv4 回环地址，保证设备码请求能进入 Android 网络桥。

## 1.13 / 26071623 — 2026-07-16

### Fixed
- **ChatGPT 设备码网络连接**: 官方 Linux 静态 `codex-app-server` 的 HTTPS 请求通过仅限回环的 CONNECT 隧道接入 Android 网络栈，避免其 Linux DNS/路由环境在 Android 上请求设备码超时。

### Security
- **隧道最小权限**: 仅监听 `127.0.0.1` 的随机端口，只允许 `openai.com` / `chatgpt.com` 的 HTTPS CONNECT；全程保持端到端 TLS，不读取、解密或记录邮箱、密码、验证码和令牌。

## 1.12 / 26071622 — 2026-07-16

### Fixed
- **Codex app-server 启动 code 1**: 官方 ARM64 静态 `ET_EXEC` 不再经 PRoot 的 Android linker 包装启动，避免 `unexpected e_type: 2`；设备码登录可直接启动官方 app-server。

### Security
- **ChatGPT 邮箱验证码边界**: 邮箱、密码和验证码只在 OpenAI 官方授权页面输入；应用仅持有 app-server 返回的设备码和已登录状态，不收集或记录凭据。

## 1.11 / 26071621 — 2026-07-16

### Added
- **官方 Codex / ChatGPT 后端**: 终端扩展内置官方 `codex-app-server` 后，应用可使用 ChatGPT 设备码登录，不需要把 Plus / Pro 订阅转换成 API Key。
- **独立运行时边界**: ChatGPT / Codex 与原有 API Provider + AgentLoop 分离；Codex 负责自己的线程、工具循环和认证，原 Provider 工作流不受影响。
- **原生流式与审批桥接**: Codex 的消息、推理片段、工具进度、授权请求和停止请求会映射到现有聊天与审批界面。
- **可扩展应用上下文接口**: 全局/项目规则、记忆、技能和会话目标通过官方 `turn/start.additionalContext` 传入，并区分应用上下文与用户选择上下文。

### Changed
- **设置页后端选择**: 可在「API Key / 中转」和「ChatGPT / Codex」间切换，并显示登录账户、订阅计划、设备码登录与退出入口。
- **会话持久化**: Codex thread id 与后端标识随会话保存，重启后可恢复官方 Codex 线程。

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

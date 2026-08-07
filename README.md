# MurongAgent

慕容雪绒的 Android 与桌面端多模型 AI Agent。

Cross-platform multi-model AI Agent by Murong Xuerong. Inspired by deepseek-reasonix, extended to support OpenAI, Claude, Gemini, and custom API endpoints.

## 项目定位

- 应用名：Murong Agent
- 仓库名：MurongAgent
- 包名 / applicationId：`com.murong.agent`
- 产品方向：面向 Android、Windows、macOS 与 Linux 的代码、项目与多模型协作型 AI Agent

MurongAgent 不是单纯的聊天壳。Android 端强调随身使用、语音、Root 与远程控制；桌面端是可独立运行的完整 Agent，直接使用电脑项目、文件、终端、Diff、MCP、Skill 和 GitHub 工作流。两端可通过安全配对同步所选配置与凭据，并用版本化备份包迁移可移植数据。

## 核心能力

| 能力 | 说明 |
|---|---|
| 多模型接入 | 支持 DeepSeek、OpenAI Compatible、Claude、Gemini 和自定义 API 中转端点 |
| Agent 工作流 | 支持多轮工具调用、结构化输出、项目上下文协作 |
| 项目浏览 | 支持本地项目目录浏览、搜索、预览、Outline、诊断与 Git 相关操作 |
| 代码编辑 | 支持搜索/替换、行操作、冲突处理、AI 补全与语法高亮 |
| GitHub 集成 | 支持登录 GitHub、查看仓库、Issue、PR、Actions、Release 等能力 |
| Root 能力 | 通过持久化 Shell 与 Root 文件访问支撑系统级操作 |
| 内置本地模型 | Android 与 Windows 可选 Qwen3.5-9B/4B/2B、Qwen2.5-Coder-7B 或 Gemma 4 E4B/E2B；Windows 另有 Gemma 4 12B 实验档。支持真实思考开关、思考过程与逐 token 流式显示，模型私有落盘并做 SHA-256 校验 |
| 图片理解与生成 | 可将图片交给独立视觉模型并复用观察摘要；支持 OpenAI Images 兼容生图、原生 4K 尺寸、局部预览、取消、重试、保存 / 下载。GPT Image 2 支持到 `3840x2160` / `2160x3840`，实际清晰度仍取决于模型输出 |
| 数据本地化 | 配置、会话、缓存与项目状态保存在本地设备 |
| 跨平台桌面端 | 原生支持 Windows、macOS、Linux 的 amd64 与 arm64，不依赖手机在线 |
| 跨端协作 | 手机可通过稳定本机 ID、同网自动发现、ADB 或局域网地址连接电脑，异网链路端到端加密，并可显式同步配置与所选凭据 |
| 备份恢复 | `murong-backup` v2 支持同系统精确恢复和 Android/桌面跨系统安全合并 |
| Go 云后端 | OAuth、发布更新、使用统计、管理后台、APK 分发与密文中继统一由 Go 服务提供，并支持 GitHub Actions 一键原子部署 |

## 借鉴与参考项目

README 中保留借鉴说明，应用内不再展示这些来源。

### 主要借鉴

- [esengine/deepseek-reasonix](https://github.com/esengine/deepseek-reasonix)
  - MurongAgent 的整体产品方向、多模型 Agent 交互思路、部分能力边界与工作流设计主要参考自该项目。
- [Rosemoe/sora-editor](https://github.com/Rosemoe/sora-editor)
  - 项目编辑器能力基于 Sora Editor 生态接入，包括移动端代码编辑、TextMate/Monarch 高亮与编辑器交互能力。
- `借鉴源码/sora-editor-main`
  - 本仓库在 Project 编辑器的高亮接法、示例能力裁剪和稳定性收口上，参考了本地保存的 Sora Editor 示例源码。

### 复用/整合来源

- 慕容调度现有通用能力
  - Root Shell、文件系统访问、部分工具链和移动端工程经验来自既有项目整理与复用。

## 软件技术栈

### Android / 构建

| 项目 | 版本 / 说明 |
|---|---|
| Android Gradle Plugin | 9.2.1 |
| Gradle（CI） | 9.6.0-rc-1 |
| Kotlin | 2.3.21 |
| KSP | 2.3.7 |
| JDK（CI / 构建环境） | 26 |
| Java / Kotlin 目标字节码 | 25 |
| compileSdk / targetSdk | 37 / 37 |
| minSdk | 33 |
| Android Build Tools（CI） | 37.0.0 |
| Android API Level（CI） | 37 |

### UI

| 项目 | 版本 / 说明 |
|---|---|
| Jetpack Compose BOM | 2025.10.00 |
| Material 3 | 跟随 Compose BOM |
| Activity Compose | 1.12.0 |
| Navigation Compose | 2.7.7 |
| Lifecycle Compose | 2.6.2 |
| Haze | 1.6.10 |

### 状态 / 持久化 / 后台

| 项目 | 版本 / 说明 |
|---|---|
| Hilt | 2.59.2 |
| Hilt Navigation Compose | 1.4.0-beta01 |
| DataStore Preferences | 1.1.3 |
| WorkManager | 2.9.0 |
| Kotlin Coroutines | 1.7.3 |
| kotlinx-serialization-json | 1.7.3 |

### 网络 / 平台集成

| 项目 | 版本 / 说明 |
|---|---|
| OkHttp | 4.12.0 |
| GitHub REST API | v3 / 2022-11-28 版本头 |
| Android DownloadManager | 系统组件 |

### 代码与项目能力

| 项目 | 版本 / 说明 |
|---|---|
| JGit | 7.2.1.202505142326-r |
| Sora Editor BOM | 0.24.5 |
| Sora Editor | 跟随 BOM |
| Sora TextMate | 跟随 BOM |
| Sora Monarch | 跟随 BOM |
| monarch-language-pack | 1.0.2 |

### Agent 与系统能力

- 多 Provider 路由
- OpenAI Compatible 中转站接入
- Root Shell 持久会话
- Root 文件访问
- MCP 相关能力接入中

## 支持的模型与端点

- DeepSeek 官方接口
- OpenAI Compatible 兼容接口
- Claude
- Gemini
- 任意自定义 Base URL / API 中转站

### 中转站示例

| 用途 | Base URL | API Key | Model |
|---|---|---|---|
| OneAPI | `https://oneapi.example.com` | OneAPI Token | 任意模型 ID |
| NewAPI | `https://newapi.example.com` | NewAPI Key | 任意模型 ID |
| 自定义 | 你的地址 | 你的 Key | 你的模型 |

## 项目结构

```text
MurongAgent/
├── app/             # Android 应用层、Compose UI、备份与跨端桥接
├── core/            # Android Provider、Agent Loop、会话与工具调度核心
├── common/          # RootFile、KeepShell、共享工具与通用能力
├── desktop-agent/   # Windows/macOS/Linux 的 Wails/Go 桌面 Agent
├── desktop-bridge/  # 手机与桌面节点共享的 Go 协议和宿主能力
├── .github/         # 一份工作流构建 Android、扩展、六目标桌面端与双架构中继
└── gradle/          # Android 版本目录与构建配置
```

云 API、官网和加密中继位于独立的 [`murongagent-backend`](https://github.com/murongruyan/murongagent-backend) Go 仓库；它与 GitHub 回传使用的 `murongdiaodu-backend-go` 是两台服务器、两套安全边界。

## 构建

```bash
# 克隆项目
git clone <repo-url>
cd MurongAgent

# 设置 Android SDK/NDK（参考 local.properties）
# sdk.dir=C:\\Users\\...\\AppData\\Local\\Android\\Sdk
# ndk.dir=C:\\Users\\...\\AppData\\Local\\Android\\Sdk\\ndk\\30.0.14904198

# 推荐使用与 CI 一致的 JDK 26
# 当前 Kotlin 工具链下，产物目标为 Java 25 / JVM 25

# 调试构建
./gradlew :app:assembleDebug

# Release 构建
./gradlew :app:assembleRelease
```

桌面端不能在一台 Windows 电脑上可靠地伪装完成所有 GUI/WebView 原生发布，因此仓库使用 GitHub 原生 Runner 构建六个目标：

| 系统 | 架构 | 发布格式 |
|---|---|---|
| Windows | amd64 / arm64 | `.exe` |
| macOS | amd64 / arm64 | `.app.zip` |
| Linux | amd64 / arm64 | `.tar.gz` |

统一工作流位于 `.github/workflows/build-all.yml`。一次运行会构建并校验 10 个正式包：Android 主应用、终端扩展、Windows/macOS/Linux 的 amd64/arm64 桌面端，以及 amd64/arm64 自托管中继。只有包名、数量和 SHA-256 清单全部精确匹配时才允许发布完整 Release；不再维护相互割裂的 APK 与桌面端构建工作流。

后端由 [`murongagent-backend/.github/workflows/build-go-backend.yml`](https://github.com/murongruyan/murongagent-backend/actions) 独立构建。手动运行时默认部署到正式服务器：工作流生成 amd64/arm64 后端包、更新 `server-latest`，再通过受认证部署钩子完成备份、原子替换、健康检查和失败回滚。GitHub 仅保存高熵原始部署令牌，服务器仅保存其 SHA-256 摘要，首次引导无需把原始令牌粘贴进终端。

Windows 本机构建可使用：

```powershell
./desktop-agent/build-release.ps1 -Architecture amd64
./desktop-agent/build-release.ps1 -Architecture arm64
```

完整桌面功能矩阵与验证记录见 [`docs/desktop-agent-execution.md`](docs/desktop-agent-execution.md)。

## 配置说明

通过应用内设置页完成以下配置：

1. 选择模型提供商
2. 填写 API Key
3. 按需填写 Base URL / 自定义请求头
4. 选择模型名
5. 配置 GitHub 登录与项目相关能力

### 图片理解与生成

在“设置 → 看图与图片生成”中，视觉模型和生图模型可各自复用一条已保存的连接，也可设置专用地址与 Key。独立视觉开启后会先生成可复用的观察摘要；生图使用 OpenAI Images 兼容的 `POST /v1/images/generations`。`gpt-image-2` 支持 `2048x2048`、`2048x1152`、`3840x2160` 和 `2160x3840` 等原生尺寸，但两边必须是 16 的倍数、总像素和宽高比需符合官方限制；尺寸达到 4K 不等于文字、细节或构图一定完美。非 GPT Image 模型或需要额外放大时，生成结果卡片可进一步调用 Replicate Real-ESRGAN 做 2–4 倍真实超分，只有长边至少 `3840px` 才会标记为“真实 4K 超分完成”。视觉、生图和超分 Token 使用 Android Keystore 或 Windows DPAPI 保存，常规备份、导出与日志不会包含它们。

## Roadmap

- [x] 多模型 Provider 支持
- [x] Root Shell 与 Root 文件访问
- [x] 项目浏览、搜索与基础编辑
- [x] GitHub 登录与仓库工作流浏览
- [x] Sora 编辑器高亮接入
- [x] 包名 / applicationId 迁移到 `com.murong.agent`
- [x] Windows、macOS、Linux 的 amd64/arm64 原生发布矩阵
- [x] Android 与桌面端 `murong-backup` v2 跨系统备份互通
- [x] MCP stdio、Streamable HTTP 与旧版 HTTP+SSE 核心能力
- [x] 手机/电脑稳定本机 ID 异网连接、同网发现与 ADB 直连
- [x] 跨端实时单权威会话、离线显式接管/归还与冲突分支保护
- [x] PHP API 兼容迁移到 Go，并接入 GitHub Actions 一键原子部署
- [ ] 更完整的 Agent 工作流与后台能力

## 许可证

MurongAgent 的原创代码采用 [MIT License](LICENSE) 开源，并保持对借鉴项目的来源说明。

仓库中捆绑、复用或引用的第三方组件仍分别遵循其自身许可证；MIT 许可证不会覆盖这些第三方代码、模型、字体或二进制文件。具体归属以相邻的 `LICENSE`、`NOTICE`、`THIRD_PARTY_NOTICES.md` 和源码头为准。

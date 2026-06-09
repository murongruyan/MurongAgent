# MurongAgent

慕容雪绒的移动端多模型 AI Agent。

Mobile multi-model AI Agent by Murong Xuerong. Inspired by deepseek-reasonix, extended to support OpenAI, Claude, Gemini, and custom API endpoints.

## 项目定位

- 应用中文名：慕容AI
- 英文名：Murong Agent
- 仓库名：MurongAgent
- 规划包名：`com.murong.agent`
- 产品方向：面向移动端的代码、项目与多模型协作型 AI Agent

MurongAgent 不是单纯的聊天壳，而是强调移动端可用性的多模型 Agent 工具：既支持日常对话，也支持项目浏览、代码编辑、GitHub 工作流、仓库操作、Root Shell、文件系统访问和结构化工具调用。

## 核心能力

| 能力 | 说明 |
|---|---|
| 多模型接入 | 支持 DeepSeek、OpenAI Compatible、Claude、Gemini 和自定义 API 中转端点 |
| Agent 工作流 | 支持多轮工具调用、结构化输出、项目上下文协作 |
| 项目浏览 | 支持本地项目目录浏览、搜索、预览、Outline、诊断与 Git 相关操作 |
| 代码编辑 | 支持搜索/替换、行操作、冲突处理、AI 补全与语法高亮 |
| GitHub 集成 | 支持登录 GitHub、查看仓库、Issue、PR、Actions、Release 等能力 |
| Root 能力 | 通过持久化 Shell 与 Root 文件访问支撑系统级操作 |
| 数据本地化 | 配置、会话、缓存与项目状态保存在本地设备 |

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

- Kotlin
- Android Gradle Plugin
- Gradle
- Java 17
- KSP

### UI

- Jetpack Compose
- Material 3
- AndroidX Activity Compose
- Navigation Compose
- Chris Banes Haze

### 状态 / 持久化 / 后台

- Hilt
- DataStore Preferences
- WorkManager
- Kotlin Coroutines
- kotlinx-serialization

### 网络 / 平台集成

- OkHttp
- GitHub REST API
- Android DownloadManager

### 代码与项目能力

- JGit
- Sora Editor
- TextMate grammar support
- Monarch grammar support
- `monarch-language-pack`

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
├── app/      # Android 应用层、Compose UI、页面状态和交互壳层
├── core/     # Provider、Agent Loop、会话与工具调度核心
├── common/   # RootFile、KeepShell、共享工具与通用能力
└── gradle/   # 版本目录与构建配置
```

## 构建

```bash
# 克隆项目
git clone <repo-url>
cd MurongAgent

# 设置 Android SDK/NDK（参考 local.properties）
# sdk.dir=C:\\Users\\...\\AppData\\Local\\Android\\Sdk
# ndk.dir=C:\\Users\\...\\AppData\\Local\\Android\\Sdk\\ndk\\30.0.14904198

# 调试构建
./gradlew :app:assembleDebug

# Release 构建
./gradlew :app:assembleRelease
```

## 配置说明

通过应用内设置页完成以下配置：

1. 选择模型提供商
2. 填写 API Key
3. 按需填写 Base URL / 自定义请求头
4. 选择模型名
5. 配置 GitHub 登录与项目相关能力

## Roadmap

- [x] 多模型 Provider 支持
- [x] Root Shell 与 Root 文件访问
- [x] 项目浏览、搜索与基础编辑
- [x] GitHub 登录与仓库工作流浏览
- [x] Sora 编辑器高亮接入
- [ ] 包名 / applicationId 迁移到 `com.murong.agent`
- [ ] README 与仓库名完全迁移到 `MurongAgent`
- [ ] MCP 能力继续补全
- [ ] 更完整的 Agent 工作流与后台能力

## 许可证

本项目当前沿用 MIT 许可证，并保持对借鉴项目的来源说明。

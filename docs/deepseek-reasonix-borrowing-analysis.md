# DeepSeek-Reasonix → murongagent 可借鉴内容分析报告

> 生成时间：自动分析
> 源项目：`借鉴源码/DeepSeek-Reasonix-main-v2`（Go CLI AI Agent）
> 目标项目：murongagent（Android Kotlin AI Agent）

---

## 一、两个项目概览

| 维度 | DeepSeek-Reasonix (Go CLI) | murongagent (Android/Kotlin) |
|---|---|---|
| **定位** | 面向终端的 AI 编码 Agent，全平台 CLI | 移动端多模型 AI Agent，Android 原生 |
| **语言** | Go（单静态二进制） | Kotlin + Jetpack Compose |
| **核心架构** | `control.Controller`（传输无关）→ 所有前端共用 | `AgentLoop` + `ChatSessionManager` + Compose UI 三层 |
| **配置方式** | TOML 文件（flag > 项目 > 用户 > 默认） | DataStore + JSON 序列化 |
| **Provider 注册** | `init()` 自注册 + `provider.Register(kind, factory)` | `ProviderRegistry` 硬编码 Map |
| **工具系统** | `tool.Tool` 接口 + 可选接口（Previewer/SnipHinter 等） | `Tool` 接口 + `ToolRegistry`（enable/disable 门控） |
| **插件/MCP** | 成熟：`plugin.Host` 管理、3 种传输、StartPolicy | 雏形：`McpRegistry` + `McpTransport`（支持 stdio/SSE/HTTP） |
| **技能系统** | Markdown 文件（`.skills/SKILL.md`），inline/subagent | Kotlin 数据类 + JSON 持久化，inline/subagent |
| **内存/记忆** | 前贴文件 + MEMORY.md 索引，加载到系统提示前缀 | MemoryStore（global/project 作用域） |
| **工具门控** | Permission gate + Plan mode 策略 | Approval 模式（READ_ONLY / ALL_APPROVAL / ALL_AUTO） |
| **缓存意识** | 深度：前缀稳定、陈旧输出修剪、摘要压缩 | 基础：`ToolHistoryPruningPolicy`、`PromptCacheDiagnosticsPolicy` |

---

## 二、可借鉴内容清单（20 项）

### 🔴 高优先级（核心功能增强，4 项）

**1. Provider 自注册机制（Factory pattern）**
- **来源**: `internal/provider/provider.go` —— `Register()` 和 `NewFunc` 工厂模式
- **内容**: provider 通过 `init()` 中的 `provider.Register("openai", New)` 自注册，用 `map[string]NewFunc` 实现可插拔。新增 provider 只需写一个包 + 一个 init()，无需修改注册代码。
- **murongagent 现状**: `ProviderRegistry.kt` `init{}` 块中硬编码三个 provider。
- **建议**: 改造 `ProviderRegistry` 为 `register(id, factory)` 模式，provider 通过静态初始化自注册。

**2. Provider Presets 预设目录**
- **来源**: `internal/config/provider_presets.go` —— `CuratedProviderPresets()` 返回 40+ 预配置模板
- **内容**: 覆盖 Kimi CN/Global、LongCat、MiniMax M 系列、GLM/Z.AI、Qwen/DashScope、StepFun、NovitaAI、GMI、Vercel、HuggingFace、NVIDIA、Ollama Cloud 等。每个预设包含 ID、Label、Description、KeyEnv、Entries。
- **murongagent 现状**: `RelayConfig` 需要用户手动填写所有参数。
- **建议**: 直接复制预设数据到 Android 端，用户在设置页一键选择 provider 预设。

**3. MCP 传输层生产级增强**
- **来源**: `internal/plugin/transport_stdio.go`
- **内容**:
  - `proc.HideWindow(cmd)` / `proc.LowPriority(cmd)` — 进程管理
  - `mergeEnv(secrets.ProcessEnv(), s.Env)` — 环境变量合并与密钥过滤
  - `closeWaitBudget = 5s` — 进程关闭超时
  - `callMu sync.Mutex` — 串行化 JSON-RPC 请求/响应
  - `pending map[int]chan rpcResponse` — ID 关联的响应通道
  - `stderr *tailBuffer` — stderr 捕获用于故障诊断
  - `defaultStartConcurrency = 8` — 并行连接数限制
  - `PerPluginTimeout` — 单个插件启动超时
- **murongagent 现状**: `McpTransport.kt` 已实现基本的 stdio/SSE/Streamable-http 传输和 JSON-RPC。
- **建议**: 移植进程管理、pending 请求通道实现、stderr 捕获、启动策略（超时+并发控制）。

**4. 工具 Schema 缓存与规范化（Cache stability）**
- **来源**: `internal/tool/tool.go` + `internal/tool/registry_canon_test.go`
- **内容**: `Registry.Add()` 在注册时提取并缓存 Schema（一次性），`Schemas()` 返回缓存。Schema 被规范化（JSON key 按字典序排序），确保每次返回的 JSON 字节一致。
- **murongagent 现状**: `ToolRegistry.buildToolsJson()` 每次重建 JSON，顺序可能不同。
- **建议**: 将工具 schema 构建改为注册时一次性缓存，减少 token 消耗。

### 🟡 中优先级（架构质量提升，8 项）

**5. Previewer 变更预览机制**
- **来源**: `internal/tool/tool.go` —— `Previewer` 接口 + `PreviewChange()` 工具函数
- **内容**: 写作工具实现 `Preview(args)` 返回 `diff.Change`，在工具执行前展示文件变更 diff。
- **murongagent 现状**: `ToolFileChange` 已有变更记录，但缺少统一的预执行预览接口。
- **建议**: 定义 `Previewer` 接口，在执行文件编辑类工具前生成 diff 预览。

**6. SnipHinter 工具输出修剪策略**
- **来源**: `internal/tool/tool.go` —— `SnipHint` + `SnipHinter` 接口
- **内容**: 每个工具声明自己的输出截断策略（Head/Tail 行数或字符数）。`read_file` 保留行首，`bash` 保留头尾。
- **murongagent 现状**: `ToolHistoryPruningPolicy` 有全局修剪，缺少每个工具级别的策略。
- **建议**: 定义 `SnipHinter` 接口，让不同工具有各自的输出截断策略。

**7. 消息归一化（NormalizeMessages）**
- **来源**: `internal/provider/provider.go` —— `NormalizeMessages()` + `closeTruncatedJSON()`
- **内容**: 在发送请求前修复会话历史：tool_calls 配对、孤儿工具消息删除、空名称反填、截断 JSON 补全、快速路径零分配 pass-through。
- **murongagent 现状**: 各 provider 自己处理消息格式转换，缺少统一的防御性修复。
- **建议**: 在进入 provider 流式调用前添加 `normalizeMessages()` 步骤。

**8. 技能系统增强字段**
- **来源**: `internal/skill/skill.go` —— `Skill` 结构体
- **内容**: `AllowedTools`、`Requires`（能力依赖）、`Profiles`（配置过滤）、`AutoUse`、`Triggers`、`Invocation`（auto/manual）、`Cost` 等。
- **murongagent 现状**: `GlobalSkill` 只有基础字段。
- **建议**: 扩展 `GlobalSkill` 添加能力依赖、触发路由、手动/自动调用模式等字段。

**9. 技能索引的缓存稳定设计**
- **来源**: `internal/skill/index.go` —— `IndexBlock()` + `IndexMaxChars=4000`
- **内容**: 只渲染名称+描述（不加载正文），索引大小受限，正文通过 `run_skill` 按需加载。
- **murongagent 现状**: 技能存储在配置中，没有索引加载策略。
- **建议**: system prompt 中的技能列表限制为名称+描述，正文按需加载。

**10. 工具 Schema 命名空间化（MCP 工具隔离）**
- **来源**: `internal/tool/tool.go` —— `MCPMetadata` 接口
- **内容**: MCP 工具被命名为 `mcp__<server>__<tool>` 避免冲突。`StripRawPrefix` 配置项去除冗余。
- **murongagent 现状**: `McpToolAdapter` 直接暴露 MCP 工具。
- **建议**: MCP 工具添加 `mcp__` 前缀作为命名空间隔离。

**11. 配置层级解析与项目配置**
- **来源**: `internal/config/config.go` + `internal/config/paths.go`
- **内容**: flag > 项目 TOML > 用户 TOML > 默认。Windows 路径 `%AppData%/reasonix/config.toml`。
- **murongagent 现状**: `ConfigRepository` 使用 DataStore，无层级覆盖。
- **建议**: 支持项目级 `.murong.toml` 或类似配置覆盖。

**12. Checkpoint/Rewind 快照机制**
- **来源**: `internal/checkpoint/checkpoint.go`
- **内容**: 文件变更的快照和撤销功能。
- **murongagent 现状**: `CheckpointToolsPresentation` 有 UI 展示，底层待补充。
- **建议**: 在文件编辑前保存快照，提供撤销能力。

### 🟢 一般优先级（增强体验，8 项）

**13. ReasoningContent/ReasoningSignature 思维链回放**
- **来源**: `internal/provider/provider.go` —— `Message.ReasoningContent` + `ReasoningSignature`
- **建议**: 添加 `reasoningContent` 和 `reasoningSignature` 字段到 `ChatMessage`。

**14. Config Extra 动态扩展**
- **来源**: `internal/provider/openai/openai.go` —— `cfg.Extra` 动态配置
- **建议**: `RelayConfig` 添加 `extraHeaders` / `extraBody` 等动态字段。

**15. 环境探测与注入（Environment Probe）**
- **来源**: `internal/environment/probe.go`
- **建议**: 启动时探测 Android 设备信息，注入 system prompt。

**16. Billing/Balance 使用量追踪**
- **来源**: `internal/billing/balance.go`
- **建议**: 借鉴更细粒度的计费模型。

**17. 并行子任务（Parallel Tasks）**
- **来源**: `internal/agent/parallel_tasks.go`
- **建议**: 借鉴独立子代理循环 + 结果聚合实现。

**18. Doctor 诊断系统**
- **来源**: `internal/doctor/` 包
- **建议**: 添加更多诊断维度和会话导出功能。

**19. Secret 管理增强**
- **来源**: `internal/config/config.go` —— `SecretsConfig`
- **建议**: 添加子进程环境变量过滤和敏感文件保护。

**20. 多模型协作（Executor + Planner）**
- **来源**: `internal/agent/coordinator.go` + `planner_model` 配置
- **建议**: 用轻量模型做 planner + 强模型做 executor。

---

## 三、建议优先落地的 5 项

| 优先级 | 借鉴项 | 预估工作量 | 影响 |
|---|---|---|---|
| 1 | Provider 预设目录（#2） | 1-2 天 | 极大降低用户配置门槛 |
| 2 | Provider 自注册机制（#1） | 1 天 | 代码架构改进，方便扩展 |
| 3 | Schema 缓存与规范化（#4） | 0.5 天 | 减少 token 消耗，提高缓存命中 |
| 4 | MCP 传输层增强（#3） | 2-3 天 | 生产级 MCP 稳定性 |
| 5 | 消息归一化（#7） | 1 天 | 减少 API 调用错误 |

---

## 四、关键源码文件对照索引

| 功能领域 | DeepSeek-Reasonix (Go) | murongagent (Kotlin) |
|---|---|---|
| Provider 接口 | `internal/provider/provider.go:19-96` | `core/provider/ModelProvider.kt` |
| OpenAI 实现 | `internal/provider/openai/openai.go` | `core/provider/OpenAIProvider.kt` |
| Anthropic 实现 | `internal/provider/anthropic/anthropic.go` | `core/provider/ClaudeProvider.kt` |
| Provider 注册 | `internal/provider/provider.go:320+` | `core/provider/ProviderRegistry.kt` |
| Provider 预设 | `internal/config/provider_presets.go` | `core/config/RelayConfig` (需扩展) |
| 工具接口 | `internal/tool/tool.go:19-33` | `core/tool/Tool.kt:88-102` |
| 工具注册表 | `internal/tool/tool.go:138-170` | `core/tool/ToolRegistry.kt` |
| MCP/插件 | `internal/plugin/plugin.go` | `core/mcp/McpRegistry.kt` |
| MCP 传输 | `internal/plugin/transport_stdio.go` | `core/mcp/McpTransport.kt` |
| 技能系统 | `internal/skill/skill.go` | `core/skill/SkillStore.kt` |
| 技能索引 | `internal/skill/index.go` | (缺失) |
| 技能内建 | `internal/skill/builtins.go` | (缺失) |
| 配置系统 | `internal/config/config.go` | `core/config/ProviderConfig.kt` + `ConfigRepository.kt` |
| Agent 循环 | `internal/agent/agent.go` | `core/loop/AgentLoop.kt` |
| 会话管理 | `internal/agent/session.go` | `core/loop/ChatSessionManager.kt` |
| 内存/记忆 | `internal/memory/memory.go` | `core/memory/MemoryStore.kt` |
| 审批系统 | `internal/control/approval.go` | `core/loop/ApprovalRuntimePosturePolicy.kt` |
| 诊断 | `internal/doctor/` | `core/doctor/DoctorReportBuilder.kt` |
| 多模型协作 | `internal/agent/coordinator.go` | `core/loop/PlanModePolicy.kt` (待扩展) |

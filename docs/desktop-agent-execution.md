# Murong Desktop Agent 执行记录

## 当前目标

把桌面程序升级为与手机端核心能力对等的完整跨平台 Agent。Android 与 Windows、macOS、Linux 使用一致的会话、配置、记忆、Skill、MCP、工作流、审批和备份语义；设备相关能力更换宿主实现。桌面端额外强化本机项目、终端、Diff 和大屏操作，手机额外保留语音与随身远程控制。

## 产品边界

- Windows、macOS、Linux 桌面 Agent 都是可独立工作的第一方客户端，不依赖手机在线；首批正式支持 amd64 与 arm64，不发布伪装成多架构的同一二进制。
- 手机端继续是完整 Agent，也可在后续作为桌面会话的远程查看/控制端。
- 现有 Desktop Node 的配对、文件 RPC、多终端与变化扫描协议保持兼容；历史 `windows_to_android` 协议枚举继续保留，避免破坏已配对设备。
- “远程节点”作为可选功能运行，不再定义整个桌面软件。
- 桌面 UI 使用 Wails 和系统原生 WebView：Windows 使用 WebView2，macOS 使用系统 WebKit，Linux 使用 WebKitGTK；不打开外部浏览器。
- Desktop Node 核心作为 Go 库静态链接进主程序；每个平台只发布一个主应用，不要求第二个节点程序。

## 首版可运行闭环

1. 新建/切换本地会话并持久化聊天记录。
2. 打开、新建或从最近记录切换电脑项目目录；切换后立即生效。
3. 配置 OpenAI-compatible Base URL、模型和 API Key；Windows 使用 DPAPI，macOS/Linux 使用应用私有 AES-256-GCM 密钥和系统文件权限保护凭据。
4. 流式聊天并支持模型工具调用。
5. 本地文件读取/列目录、原子写入和多终端执行。
6. 提供只读、全审批、白名单、全自动四种审批模式；最大工具迭代可配置到 999。
7. 侧栏保留“远程节点”，直接在主程序内配置和启停，不再启动第二个程序。

## 手机 / 电脑功能对等矩阵

| 能力域 | 手机端现状 | Desktop 当前 | 对等要求 |
|---|---|---|---|
| 品牌与宿主 | Android 正式头像、单应用 | 正式头像、无边框单窗口；Windows 单 EXE、macOS `.app.zip`、Linux `.tar.gz` | 三系统 amd64/arm64 原生发布链已建立 |
| 会话 | 新建、重命名、导入/导出、分叉、回退、压缩摘要、用量、目标/计划模式 | 新建、切换、重命名、删除、持久化、分叉、消息级回退、完整/跨端 JSON 与 Markdown 导入导出、真实/估算用量、版本化摘要压缩、会话目标，以及可执行、可恢复、按真实工具收据逐步签收的规范计划 | 会话本机能力与跨端文件映射已对齐；三系统桌面任务可在 Android 持久镜像、离线查看并从手机续接。实时控制共用一个 Desktop 权威会话，离线执行通过显式接管/归还转移单写者权限；冲突保留独立分支，不把两端工具、审批和代码状态静默拼成伪线性历史 |
| 模型连接 | DeepSeek / OpenAI-compatible / Claude、多中转、Codex/ChatGPT 后端、推理、Temperature 与最大输出 Token、计划模型与子代理默认模型 | 多 Provider Profile、平台安全落盘、OpenAI Responses/Chat Completions、Anthropic Messages、内置对应系统/架构 Codex app-server、ChatGPT 设备登录、账号模型/订阅额度、逐连接推理强度、全局生成参数，以及计划/子代理默认执行 Profile | 核心后端、模型/推理、生成参数和高级执行 Profile 已对齐；API Key、Codex 登录令牌及这些跨平台设置可在安全配对设备间显式双向同步 |
| 系统行为 | 系统提示词、回答详细度、生成参数、全局规则 | 可编辑系统提示词、简洁/均衡/详细、Temperature、最大输出 Token、全局与项目规则 | 核心配置已对齐；审批模式、系统提示词、回复详细度、生成参数和全局规则可加密双向同步 |
| 多模态消息 | 相册/文件图片输入、私有缓存、Provider/Codex 视觉请求 | JPEG/PNG/WebP/GIF/BMP 选择、私有缩放缓存、消息预览、OpenAI/DeepSeek/Claude/Codex 视觉输入 | 核心图片消息已对齐；开关可跨端同步，完整备份包含会话图片，手机桌面任务镜像只显示附件名 |
| 记忆 | 持久记忆、搜索/读取/遗忘、项目记忆、旧记忆迁移 | 全局/项目库、按作用域列出/搜索/读取/保存/遗忘 | 已对齐现行存储；Android durable/legacy 全局记忆均可导出到跨端清单，桌面无 legacy 迁移负担 |
| Skills | 全局/项目 Skill、内联/子代理、工具预算、模型偏好 | 全局/项目编辑器、目录/读取、内联/五类隔离子代理、工具预算交集、全局默认与调用/项目模板模型及推理覆盖、批量编排、后台状态及项目自定义子代理模板 | 核心执行、默认 Profile 与项目模板已对齐 |
| MCP | stdio / SSE / HTTP、密钥分离、状态、工具白名单 | stdio / Streamable HTTP / 旧版 HTTP+SSE、DPAPI 密钥分离、连接状态、可信只读工具、动态 Agent/Composer 工具 | 三种传输、核心审批与跨端配置/可选凭据同步已对齐；跨平台 stdio 导入后安全关闭 |
| 本地工具 | 文件、代码编辑/搜索、Shell、Android、网页 | 读写/存在/删除/权限、代码搜索/补丁、平台原生多 Shell、网页搜索/抓取、外部变化监听、时间线与 Git/文本 Diff | Windows 使用原生目录通知，macOS/Linux 使用 fsnotify；宿主核心与桌面增强已对齐 |
| 子代理 | launch、explore、research、review、security_review，最多 6 项批量/依赖编排、后台状态与取消 | 五类隔离循环、自定义项目模板、模型/推理覆盖、父子工具预算交集、写入边界、最多 64 项批量计划、无人工并发槽位上限、持久后台状态/结果与取消 | 核心批量、后台与项目模板语义已对齐；桌面端不照搬手机并发槽位限制 |
| 审批 | 只读/全审批/白名单/全自动、项目覆盖、工具细粒度 | 四档模式、全局/项目覆盖、工具/文件操作开关、命令/路径/终端白名单 | 已对齐核心语义；全自动按用户要求不增加隐藏强制审批 |
| 工作流 | 自动分流、保存的自动化、调度、Tasker/Intent、运行记录 | 四类保存模板、手动运行、进程内调度、运行记录、中断恢复、单 EXE 外部命令入口 | 无本机路径的保存工作流可跨端同步且导入后保持关闭；路径绑定工作流不泄露路径并跳过，高级 DAG 编排后续扩展 |
| GitHub | 登录、仓库任务、Actions、分支/PR/提交工具 | DPAPI Token、Enterprise API Base、连接验证、Actions、仓库/文件/分支/Issue/PR 工具 | 核心仓库读写闭环已完成；API Base、Access Token 与用户标识可在安全配对设备间显式双向同步 |
| 项目上下文 | 项目规则/记忆/Skills、审计、修改时间线 | 打开/新建/最近项目、独立规则/记忆/Skills、`@` 文件/目录上下文、外部变化时间线与 Diff，以及按项目持久、跨任务检索/导出的脱敏审计归档 | 外部变化感知与跨任务审计归档已完成；命令正文、工具参数、文件内容和密钥不入档 |
| 备份恢复 | 版本清单、哈希校验、恢复前快照、自动保留 | Android/Desktop 统一 `murong-backup` v2 外层清单；同系统使用原生状态精确恢复，Android 与 Windows/macOS/Linux 之间按可移植会话、全局规则/记忆/Skills、安全 MCP、无路径工作流和普通设置合并 | 已完成跨系统互通、稳定冲突副本、幂等重导入和事务回滚；项目路径/审计/媒体/设备设置留在本机，密钥与登录不进入普通备份 |
| 远程节点 | 手机作为 Agent 调电脑工作区/终端，并可查看/控制已共享的桌面任务 | 已内置各平台主应用，可配置多终端和桌面任务共享/控制权限 | 同一配对连接已打通文件、终端、桌面任务快照、规范计划进度、发消息、停止、审批和结构化回答；手机会显示真实系统/架构，旧节点继续兼容 |
| 语音 | 离线实时识别、标点、悬浮朗读 | 未实现 | 作为设备能力阶段实现，不阻塞 Agent Core 对等 |
| 跨端同步 | 手机会话与节点桥接，可在私网连接中查看/控制桌面任务 | Desktop 可发布去设备上下文的实时任务快照并处理手机控制命令 | 已完成局域网/自有私网直连与异网端到端加密云中继、任务级实时控制、脱敏规范计划和离线镜像，以及 Provider/API Key、Codex 登录、GitHub 登录、Agent 设置（含生成参数、图片开关、计划与子代理执行 Profile）、规则/记忆/Skills、MCP/可选凭据、保存工作流的显式加密双向同步；Android 已接受 Windows/macOS/Linux 来源标签。独立并发双写不作为支持模式，改由实时单权威会话、离线显式交接和冲突分支保证历史可验证 |

## 共享边界

- 两端不强行共享 Kotlin/Go 运行时代码，先共享 JSON 领域模型和版本化清单：会话、规则、记忆、Skill、MCP、工作流、Provider Profile。
- 密钥永远与普通可移植配置和备份分离，但不是禁止在第一方 Murong 设备之间传输：Android 使用 Keystore/应用私有目录，Windows 使用 DPAPI/Murong 私有 Codex Home，macOS/Linux 使用应用私有 AES-256-GCM 密钥和用户目录权限；只有用户在已安全配对的两台 Murong 设备上逐类别选择并确认“设备同步”时，才通过独立端到端加密信道传输所选 API Key、Codex 登录、GitHub Access Token 或 MCP 凭据。
- Agent 工具名称、参数和审批风险在两端保持一致；文件、Shell、语音、Android/Windows 控制只替换宿主适配器。
- 项目级配置跟随项目根目录或项目 ID，全局配置跟随 Murong 用户数据目录。

## 架构

```text
Murong Desktop Agent（Windows / macOS / Linux）
  系统原生 WebView UI
    会话 / Chat / Diff / 审批 / 设置 / 远程节点
          |
  Go Desktop Agent Core
    会话存储 / Provider / Agent Loop / 审批 / 工具注册
          |
  Local Host Tools
    项目文件 / 原子写入 / PowerShell、CMD、WSL 或 POSIX Shell

内置 Murong Desktop Node
  作为“远程节点”功能保留，继续服务手机端 Agent
```

## 阶段

- [x] D0：确认产品重新定位；Windows Agent 为主，远程节点为功能。
- [x] D1：审计 Android AgentLoop、Go Windows Node、终端与工作区边界。
- [x] D2：建立 Wails/WebView2 桌面工程、现代主界面和内置远程节点。
- [x] D3：实现 DPAPI 配置、本地会话和 OpenAI-compatible 流式聊天。
- [x] D4：实现文件/多终端工具循环与四档审批。
- [x] D5：完成首轮视觉验收、单 EXE Release、说明和回归测试。
- [x] P1：补齐系统提示词、规则、记忆、Skill 和项目级配置。
- [x] P2：补齐 Provider Profiles、工具权限、代码/网页工具和子代理。
- [x] P2.5：补齐桌面项目入口与聊天框上下文栏。
- [x] P3a：补齐稳定版 MCP 配置、stdio / Streamable HTTP、动态工具和审批。
- [x] P3b.1：补齐 GitHub Actions 只读连接和保存的工作流/调度。
- [x] P3b.2：补齐 GitHub 仓库写入工具、外部工作流入口和旧版 MCP HTTP+SSE 导入兼容。
- [x] P4a：补齐 Windows 完整备份恢复、恢复前快照和自动保留。
- [x] P4b.1：补齐会话重命名、分叉、回退、导入导出和本地统计。
- [x] P4b.2a：补齐摘要压缩和供应商真实用量。
- [x] P4b.2c：监听终端、Git 和外部编辑器造成的项目变化，并在下一轮自动提供给 Agent。
- [x] P4b.2b：补齐 Android/Windows 跨端会话数据映射。
- [x] P5a：在现有配对私网连接中补齐手机查看、发消息、停止和审批 Windows 桌面任务。
- [x] P5a.1：内置官方稳定 Codex CLI，补齐 Windows ChatGPT 登录、模型/推理、流式会话和 Murong 审批桥接。
- [x] P5a.2：补齐 Android / Windows API Key、Provider 配置与 Codex/ChatGPT 登录的显式端到端加密双向同步。
- [x] P5a.3：补齐 Windows 单写者、Android 持久镜像的桌面任务会话同步、离线查看与重连收敛。
- [x] P5a.4：将凭据通道升级为版本化设备同步，补齐 Agent 设置、规则、记忆、Skills、MCP/可选凭据和保存工作流的事务式双向同步。
- [x] P5a.5：补齐 Temperature 与最大输出 Token 的 Windows 持久化、真实 Provider 请求、备份恢复和 Android/Windows 加密双向同步。
- [x] P5a.6：补齐 Windows 图片消息、私有媒体缓存、四类模型视觉输入、完整备份媒体及跨端开关/附件名映射。
- [x] P5a.7：将 GitHub API Base、Access Token 与登录用户纳入 Android/Windows 端到端加密设备同步，并补齐双端安全落盘和事务回滚。
- [x] P5b.1：补齐 Windows 子代理最多 6 项批量/静态依赖编排、统一审批、全量并发、失败跳过与取消传播。
- [x] P5b.2：补齐 Windows 子代理后台运行、持久状态/结果、列表/读取/取消、完成通知和重启中断语义，并移除手机端 2 槽位限制。
- [x] P5b.2c：将桌面宿主扩展到 Windows/macOS/Linux 的 amd64/arm64，补齐平台终端、文件监听、凭据保护、对应 Codex 运行时和 GitHub 六目标原生发布矩阵。
- [x] P5b.2d：将桌面系统/架构身份贯通工作区、任务快照、Android 离线镜像和手机 UI，并兼容严格 JSON 的旧手机协议。
- [x] P5b.4：补齐手机接管/归还桌面会话的显式单写者执行权交接、加密负载和冲突校验。
- [x] P5b.5：补齐桌面计划模式与子代理默认模型/推理 Profile，并贯通真实运行、备份恢复和 Android/Desktop 加密设备同步。
- [x] P5b.6：补齐任务级项目绑定、切换恢复、缺失目录修复入口和有界历史任务检索，并让普通 API Provider 与内置 Codex 共用同一检索能力。
- [x] P5b.7：补齐桌面结构化 `ask_user`，并允许手机查看和回答共享桌面任务的问题。
- [x] P5b.8：补齐普通 API 与内置 Codex 的记忆/Skill 动态工具和真实 `run_skill` 执行链。
- [x] P5b.9：把计划模式升级为持久化规范计划，以真实工具收据逐步签收，并贯通执行、继续、清除、分叉、回退和备份边界。
- [x] P5b.10：把桌面规范计划以脱敏只读投影同步到手机，补齐 v3→v2→v1 协商、离线镜像和移动端步骤/证据进度卡。
- [x] P5b.11：把实时项目变化升级为按项目持久的跨任务审计归档，贯通 Agent/Codex/外部变化、检索导出、Diff 与完整备份恢复。
- [x] P5b.12：把 Android/Desktop 完整备份升级为 `murong-backup` v2，补齐同系统精确恢复与 Android/Windows/macOS/Linux 跨系统安全合并。
- [x] P5b.13：补齐手机/电脑异网端到端加密云中继、自托管中继服务和八产物完整发布门禁。
- [x] P5b 跨端一致性边界：实时控制共用 Desktop 权威会话；离线通过显式接管/归还转移单写者权限并校验共同前缀，冲突保留分支。独立并发双写不会静默合并工具调用、审批、计划收据和代码状态，因此不作为可开启模式。
- [ ] P5b.14：将第一方 Go Relay 部署到 `murongagent.rl1.cc` 并完成真实 WSS 手机/Desktop 往返；本地实现与发布物已通过，线上部署需经明确授权提交、推送并修改 systemd/Nginx。

## 影响面

- 新增 `desktop-agent` 独立模块，避免在首阶段破坏已验收的 `desktop-bridge`。
- `desktop-bridge` 先作为伴随功能运行；待桌面 Agent 闭环稳定后再抽取共享 host 工具包，避免先重构后无可运行产品。
- Android AgentLoop 本身不分叉；LAN 协议在原有配对、Bearer、SSE 和节点会话边界内扩展桌面任务通道。

## 进度日志

### 2026-07-19 D0-D1

- 用户决定将 Windows 软件升级为桌面 Agent，当前手机控制 Windows Node 保留为一个功能。
- Android `AgentLoop` 依赖 DataStore/Hilt/协程和 Android 工具注册，直接移植到 Windows 会形成脆弱分叉；桌面端采用 Go 原生 Agent Core。
- 当前 Desktop Node 的工作区边界、原子写入、平台凭据保护、终端发现/执行和配对协议作为共享 host 层。
- 本机已安装 Microsoft Edge WebView2 Runtime。选择 Wails v2：Windows 无 CGO 要求、复用系统 WebView2、前端资源可嵌入单个 EXE，适合聊天、Diff 与审批等富 UI。

### 2026-07-19 D2-D5

- 新增 `desktop-agent` Wails 模块：会话、流式 Chat Completions、工具循环、项目边界、DPAPI、四档审批和 999 次最大工具迭代。
- 已验证 PowerShell 7、Windows PowerShell、CMD、WSL Ubuntu、WSL Ubuntu-26.04 五个后端真实执行；后台子进程全部使用无窗口模式。
- 把 `desktop-bridge` 从不可导入的 `package main` 重构为可复用库；桌面 Agent 静态链接 `RemoteNodeService`，远程页直接管理原有配对配置和多个终端。
- 正式发布目录只保留 `murong-desktop-agent-amd64.exe`；当前 SHA-256：`D02020B66C3CF4F4F2CC2CFF2FD3710125CB35BD80FFCD0707E7424CC9C41275`。
- 正式头像直接复用 Android `app_icon.jpg`，Windows ICO 包含 16/24/32/48/64/128/256 多尺寸；移除粉色 M 占位和重复原生标题栏。
- 自动化覆盖：配置/会话重载、DPAPI、Base URL、路径越界、SHA 防冲突原子写、SSE 分段工具调用、审批模式、节点配置与配对清除。

### 2026-07-19 对等目标启动

- 已创建持续目标：Windows 端与手机端核心功能对等，而不是停在五工具聊天壳。
- 已完成 Android 设置、工具注册和会话高级操作审计，建立上方功能矩阵；下一阶段从系统提示词、规则、记忆、Skill 和项目配置开始。

### 2026-07-19 P1

- 新增可编辑系统提示词和简洁/均衡/详细三档回答详细度；启用的规则进入系统上下文，记忆改为按需列出、检索和读取，避免整库占用提示词。
- 新增现代知识库页，统一编辑规则、长期记忆和 Skill；通过“全局 / 当前项目”切换相同字段，项目数据按规范化项目路径隔离。
- Agent 工具从 5 个增至 17 个，包含 `memory_list/search/read`、`remember_memory`、`forget_memory`、`read_skill`、三类全局创建工具及三类项目创建工具。
- 记忆和 Skill 参数对齐手机端的 `scope`、`memory_id`、`runAs`、`allowedTools`、`preferredModel` 语义；知识写入继续服从只读/全审批/白名单/全自动策略。
- 自动化新增全局知识库重载、项目知识库重载、项目作用域默认记忆落点、项目规则提示词注入和 17 工具清单测试；`go test`、`go vet`、前端语法检查全部通过。
- 已实际验收全局/项目作用域切换、未选择项目提示、系统提示词页、滚动布局和终端卡片；P1 单 EXE Release 大小 12,015,616 字节。

### 2026-07-19 P2（Provider Profiles）

- 配置 schema 升至 v2，把原单一 Base URL/模型/Key 迁移为 Provider Profile 清单；旧配置自动生成“原桌面连接”，原 Key 不丢失。
- 设置页可新增、切换、编辑和删除多个连接；支持 DeepSeek、OpenAI-compatible、Claude 三类协议，以及连接名、模型、推理强度、上下文窗口和每连接独立 API Key。
- 每个 API Key 继续由 Windows DPAPI 独立加密；前端只收到 `hasApiKey`，保存空值会保留旧 Key，显式勾选才清除。
- 官方 OpenAI 自动使用 Responses API，自定义 OpenAI-compatible 与 DeepSeek 默认使用 Chat Completions SSE，也可手动选择 Responses；Claude 走原生 Anthropic Messages SSE。三套协议均覆盖文本和工具参数增量。
- 修复 schema v1 反序列化时默认 Provider 残留、覆盖旧连接的迁移缺陷；Agent 启动时从同一配置快照读取 Provider 与 Key，避免切换连接造成凭据错配。
- 新增旧配置迁移、多 Profile DPAPI、重复 ID 拒绝、Claude 请求结构和 Claude 流式工具调用测试；`go test`、`go vet`、前端语法检查和单 EXE 构建通过。
- 真实窗口已验收模型连接卡片、新增与删除交互。

### 2026-07-19 P2（工具权限、代码/网页、子代理）

- 配置 schema 升至 v3，新增与 Android 同名的 `enabledBuiltinTools`、`enabledFileOperations` 和项目级工具偏好；当前项目可继承或覆盖全局审批、白名单与工具清单。
- UI 仍只保留只读、全审批、白名单、全自动四档审批；高级工具开关默认折叠。全自动下启用的写入、代码补丁和 Shell 会直接执行，不再出现额外确认。
- 工具从 17 个增至 29 个：新增文件存在/删除/权限、源码优先正则搜索、精确 SEARCH/REPLACE、单文件多 hunk 补丁、网页抓取、Bing RSS 搜索，以及通用/Explore/Research/Review/Security Review 五类子代理。
- 代码搜索默认排除 `.git`、`build`、`.gradle`、`out`、`target`、`intermediates`、`mapping`、`generated`、`node_modules`，并按 `src/main`、测试源码、其他源码、普通文件、生成物排序。
- 代码编辑继续使用项目相对路径、1 MiB UTF-8 上限、最近 SHA 和原子替换；递归删除必须显式声明，网页工具拒绝回环、私网、链路本地和云元数据地址。
- 子代理使用独立消息与模型循环，最多 32 次迭代，禁止递归派生；其可见工具与父级工具权限取交集，默认只读，写入、代码编辑和 Shell 必须显式申请并继续服从审批模式。
- 测试新增工具过滤、项目覆盖、源码排序、代码替换/补丁、文件删除边界、网页正文/RSS/SSRF、子代理预设预算、OpenAI Responses 流式工具调用；`go test`、`go vet`、前端语法检查通过。
- 真实窗口已验收 Provider API 模式、高级工具权限折叠、全部工具/文件操作开关和无项目提示；P2 单 EXE Release 大小 12,337,152 字节，SHA-256：`35C706244BD7B9F6FCF55EF080A6F76A9E86A4242E2EDF3B58A2B8279FB7EFD1`。

### 2026-07-19 P2.5（项目入口与 Composer 上下文）

- 修正首版把项目选择藏在“设置”且必须再次保存的断裂流程：聊天顶部项目标签、空状态和设置页现在都可直接打开项目切换器。
- 新增“打开项目文件夹 / 新建项目 / 最近项目 / 关闭当前项目 / 移除失效记录”；新建流程明确选择父目录与名称，只创建文件夹，不擅自生成模板或初始化 Git。
- 当前项目和最近 12 个项目写入 schema v4；路径解析符号链接、校验真实目录，Windows 保留名和非法字符会被拒绝。项目切换期间若仍有 Agent 任务运行会明确阻止，避免工作区混用。
- 切换后无需保存设置，文件工具、终端工作目录、项目规则、项目记忆、项目 Skill 与项目级审批/工具权限立即跟随，并同步刷新所有相关 UI 状态。
- 聊天框新增 `＋` 与 `@ 文件 / 文件夹` 上下文入口；Skill、子代理、MCP、目标模式和计划模式统一收进 `＋` 菜单，避免底栏堆满同级按钮。支持输入 `@` 唤出项目搜索，选择项以可移除标签显示并随本轮消息持久化。
- 文件/文件夹上下文在发送前重新验证项目边界、存在性和类型；Skill 会展开经过验证的真实说明，SUBAGENT Skill 与显式子代理选择会成为本轮强执行指令。MCP 入口已就位，真实工具清单随 P3 连接层接入。
- 目标模式是下一条消息的一次性动作，发送后成为会话长期目标；计划模式是会话级开关，只开放只读工具并强制先给方案，计划消息可一键切回执行。两种模式可组合使用，并在输入框上方显示明确状态。
- 聊天框右下新增当前 Provider/模型连接选择器，可直接切换活动模型而不重写其他设置或 DPAPI Key；聊天右上角无法辨识的删除图标改为“删除任务”文字按钮。
- 修复 Windows 图标资源：Wails 固定读取 `GROUP_ICON` 资源 ID 3，旧资源误生成在 ID 2，导致窗口、任务栏和悬停预览退化成灰白默认图标。现已使用显式 `.rc` 资源并增加 Win32 加载测试，正式 EXE 的图标组为 ID 3。
- 新增最近项目持久化、关闭保留、失效记录、Windows 名称、项目搜索/生成目录排除、上下文去重与类型校验、Skill/子代理物化、目标/计划持久化、计划工具隔离、模型连接切换和图标资源测试；`go test ./...`、`go vet ./...` 与前端语法检查通过。
- 真实窗口已验收主聊天页上下文栏、项目切换弹窗、新建项目表单和切换后顶部项目标签即时刷新；最新单 EXE SHA-256：`3DB1E4EA46433CD99F70791E48B665D2C3861E554E3D90CF91AFA02AB97400D9`。

### 2026-07-19 P3a（MCP 稳定协议闭环）

- 配置 schema 升至 v5，新增最多 32 个 MCP 服务器；支持启用、自动启动、请求超时、可信只读工具、stdio 命令/参数/工作目录，以及 Streamable HTTP URL。
- 环境变量与 HTTP 请求头的值不会下发前端或明文写盘，统一序列化后通过 Windows DPAPI 加密；编辑器只显示已保存键名，留空保留原值，也可明确清除。
- 按稳定 MCP 规范实现 `initialize → notifications/initialized → tools/list → tools/call`；stdio 使用 UTF-8 换行 JSON-RPC，Streamable HTTP 支持 JSON 或 SSE 响应、`Mcp-Session-Id`、协议版本头和会话关闭。
- 工具清单支持游标分页、输入 schema 校验、服务器/工具规范化命名与冲突隔离、工具数量/消息/结果大小限制；stdio 子进程无窗口运行，stderr 诊断会对已知密钥脱敏。
- 已连接工具动态加入模型工具表与聊天 `＋` 菜单；只有用户明确列入“可信只读工具”的 MCP 工具可进入只读/计划模式，其余视为高风险。全审批、白名单、只读、全自动继续使用统一审批器，全自动不会增加隐藏确认。
- MCP 管理区按用户要求折叠放在 Skills 下方，包含连接汇总、stdio/HTTP 编辑、加密变量/请求头、可信只读工具、添加/移除、重新连接和保存并连接。
- 自动化测试覆盖 DPAPI 密钥保存/保留/清除、配置校验、真实 stdio 子进程、Streamable HTTP JSON/SSE、会话 ID、工具发现/调用、Agent/Composer 注册和四档审批关键语义；`go test ./...`、`go vet ./...` 与前端语法检查通过。
- 真实发布版已验收 MCP 默认折叠、展开布局、服务器编辑卡片、聊天 `＋` 菜单和右下模型选择；未保存的验收草稿已丢弃。最新单 EXE SHA-256：`1419333AC3255EE17F3414D6ED8EC3073D9241CEDEF2A981F58CBDAC990D6477`。

### 2026-07-19 P3b.1（GitHub Actions 与保存工作流）

- 新增独立“自动化”页，包含 GitHub 连接、工作流统计、创建/编辑/删除、立即运行、后台调度、运行状态、失败原因与最近摘要；页面使用现有现代视觉体系，不把自动化设置重新塞进聊天底栏。
- GitHub API Base 与 Token 保存在独立 `desktop-agent-workflows.json` 文档中；Token 通过 Windows DPAPI 加密，前端只得到 `hasToken`，留空保留、显式勾选才清除，工作流定义只保存 `owner/repository`。
- 原生 GitHub Actions 查询固定为 GET `/repos/{owner}/{repo}/actions/runs?per_page=5`，带 GitHub 媒体类型、API 版本与 User-Agent；支持公开仓库匿名读取、私有/Enterprise Token，并阻止携带凭据跨站重定向。
- 与 Android 对齐四类模板：项目只读诊断、目录变更摘要、GitHub Actions 状态检查、会话摘要导出。前三类使用固定只读执行器，可按 15 到 10080 分钟调度；会话导出写入 `Documents/Murong Exports`，后台开关禁用且每次运行必须在自定义确认窗确认。
- 调度器与主 EXE 生命周期绑定，不另起常驻程序；关闭后停止，启动时恢复启用的安全模板，并把残留 `QUEUED/RUNNING` 明确恢复为 `CANCELLED`。同一工作流禁止重入，删除运行中的工作流会取消其活动网络请求或执行上下文。
- 目录模板不读取文件正文，最多扫描 3 层/400 项并跳过符号链接与常见构建缓存；运行记录限制长度，项目绝对路径不会写入失败原因。
- 自动化测试覆盖 Token 明文排除/保留/清除、URL/仓库/周期校验、循环和权限篡改、真实 TLS GitHub 请求头/最近五条/错误边界、只读调度白名单、前台确认导出、运行事件与删除取消。
- `go test ./...`、`go vet ./...` 与前端语法检查通过；真实窗口已验收自动化页、GitHub 卡片、工作流编辑器和前台模板安全提示，未保存草稿已丢弃。最新单 EXE SHA-256：`AEE59436B0B95DBE376F921833FCFBE9E019DE79EA57EB421DD2EEB7505F6E4E`。

### 2026-07-19 桌面聊天栏与 Windows 图标校正

- 再次以真实发布窗口核对聊天主界面：右上角固定使用“删除任务”文字按钮，不再显示难以辨认的删除字符；输入框右下固定显示当前 Provider/模型下拉框，尚未配置连接时也会明确显示“未配置模型”。
- `＋` 菜单统一承载计划模式、目标模式、Skills、子代理和 MCP；修复没有 Skill 或未连接 MCP 时整个分区被省略的问题，现在会保留分区并给出配置入口提示，避免用户误以为功能缺失。
- Windows 资源同时嵌入 Wails 窗口图标 ID `3` 与窗口类大图标 `IDI_APPLICATION (32512)`，任务栏和悬停预览不再因为只请求大图标而回退到灰白系统默认图标；两者都使用 Android 同款正式头像。
- 新增桌面聊天栏静态契约测试和双 Windows 图标资源加载测试；`node --check`、`go test ./...`、`go vet ./...`、最终单 EXE 构建与真实窗口验收全部通过。发布文件大小 12,916,736 字节，SHA-256：`C4DC0511ED9984161D16387FE89240C326C0295CA1EED9933E8D972C8C303B1A`。

### 2026-07-19 P4a（完整备份与恢复）

- 新增 `murong-backup` v1 ZIP 格式，`manifest.json` 固定为首项；会话、规则/记忆/Skills、Provider 非敏感设置、MCP 非敏感配置、保存的工作流、备份设置和普通 UI 设置使用独立 JSON 条目，每项记录精确字节数与 SHA-256。
- API Key、Codex/ChatGPT 与 GitHub 登录状态、MCP/GitHub 鉴权值、语音模型、终端扩展/历史、远程配对凭据、缓存和诊断默认排除；恢复 Provider、MCP 和 GitHub 配置时按 ID/语义匹配保留这台电脑现有的 DPAPI 凭据，不接受备份包覆盖。
- 导入先执行 ZIP 与语义双层校验：格式/版本、清单结构、条目数量、单项/总大小、压缩包大小、路径穿越、Windows 非法/保留名、大小、哈希、重复/额外/缺失条目和各领域数量边界均在任何写入前拒绝。
- 校验通过后先生成 `PRE_RESTORE` 恢复前快照，再暂停 MCP 和工作流，按配置/会话/工作流/备份设置顺序落盘；任一步失败会回滚已经写入的桌面存储，成功后重新连接 MCP、恢复安全调度并刷新全部界面状态。运行中的 Agent、审批或工作流会明确阻止恢复。
- 每日自动备份采用应用生命周期内调度：03:00 后执行一次，错过时间会在下次启动补做；自动备份与恢复前快照分别按 1–100 份保留。设置页按要求默认折叠，展示统计、自动开关、保留数、路径、手动备份、恢复入口和明确的排除/恢复说明。
- 恢复必须先在系统文件选择器选择包，再在应用确认窗输入“恢复”；未输入时执行按钮禁用。真实发布窗口已用隔离数据目录完成手动备份、7 条目清单检查、恢复选择与确认窗验收，实际恢复在确认前取消，未触碰现有用户数据。
- 测试覆盖归档往返、未知字段、首项约束、穿越/非法路径、重复大小写路径、篡改哈希、密钥明文排除、本机凭据保留、缺失项目降级、每日补偿/单日去重/保留数量和中途写入失败回滚；`node --check frontend/dist/app.js`、`go test ./...`、`go vet ./...` 与单 EXE Release 构建全部通过。发布文件大小 13,223,936 字节，SHA-256：`8C618B72FB9628ABF6BA982C524B6FC91C84896181D6BB1161643ECF79849DA1`。

### 2026-07-19 P4b.1（会话高级操作与聊天栏收口）

- 新增任务重命名、完整分叉、从指定消息分叉和消息级回退；分叉生成完全独立的新任务，回退前展示将删除的记录数并要求明确确认，运行中的源任务禁止分叉或回退。
- 新增 `murong-session` v1 严格 JSON 导入导出：校验格式/版本、未知字段、尾随数据、消息角色和数量/大小边界；导入时重建任务与消息 ID，避免覆盖现有任务。Markdown 仅用于阅读，并明确不可重新导入。
- 任务详情展示消息、用户/Murong、工具记录、字符数和 Token 本地估算，并明确说明估算不代表供应商账单；供应商真实用量和自动摘要仍留在 P4b.2，不作虚假对等声明。
- 聊天右上固定为“删除任务”文字；输入框右下固定显示模型连接选择；计划模式、目标模式、Skills、子代理和 MCP 全部进入 `＋` 菜单，未配置 Skill/MCP 时也保留分区和配置提示。
- Windows 图标除资源 ID `3` 与 `32512` 外，启动后还会显式向主窗口发布 `ICON_BIG`、`ICON_SMALL`、`ICON_SMALL2` 和窗口类大小图标，避免任务栏、Alt+Tab 或悬停预览继续读取灰白默认值。真实进程诊断确认五个图标句柄全部非零。
- 自动化覆盖会话重命名/分叉/回退/统计、JSON 往返与严格拒绝、Markdown 可读性、聊天栏静态契约、大小图标按当前系统尺寸加载；真实窗口已验收加号菜单、模型选择和文字删除入口。`node --check frontend/dist/app.js`、`go test ./...`、`go vet ./...` 与单 EXE Release 构建通过。发布文件大小 13,291,008 字节，SHA-256：`761836D7041F9C9E460E0B6A59E2AE24F1239B8C36964A6936DC99398C937640`。

### 2026-07-19 P4b.2a（真实用量与上下文压缩）

- 会话用量改为累计每次真实模型调用：OpenAI Responses 读取 `response.completed.response.usage`，DeepSeek/官方 OpenAI Chat Completions 请求流式 usage，Claude 合并 `message_start` 输入用量与 `message_delta` 累计输出用量；缓存输入和推理输出单独记录。未返回 usage 的自定义中转不会被强制附加不兼容参数，也不会被虚构进供应商总数。
- 任务详情优先展示供应商输入/输出/总 Token、带用量调用覆盖率、缓存与推理 Token；没有真实用量时才显示本地文本估算，并明确说明不代表账单。父 Agent、子代理和摘要模型调用统一累计，JSON 会话与完整备份保留这些值。
- 新增版本化上下文压缩：至少 12 条有效消息后可生成摘要，压缩较早至少 6 条并保留最近 8 条原文。完整聊天记录不会删除；启用时模型只接收摘要和截止点后的消息，停用后立即恢复完整历史，也可重新生成新版本。
- 摘要优先请求当前 Provider 进行结构化整理，请求不可用时退回确定性本地摘要；分叉和导入在重建消息 ID 后会重映射摘要截止点，回退到截止点之前会清除失效摘要，备份/导入对版本、长度、来源和截止消息执行语义校验。
- 自动化覆盖压缩计划、完整历史保留、模型上下文裁剪、启停、统计、分叉/导入重映射、较早回退失效和非法截止点；`node --check frontend/dist/app.js`、`go test ./...`、`go vet ./...` 与单 EXE Release 构建通过。
- 使用隔离数据目录对最终 EXE 做真实窗口验收：右上为“删除任务”，输入框右下始终可见模型连接；`＋` 菜单包含计划模式、目标模式、Skills、五类子代理和 MCP；任务详情可见上下文压缩区。验收中发现并修复任务详情先使用后声明统计对象导致刷新中断的问题，并增加声明顺序契约测试；进程级 Win32 检查确认 `ICON_BIG`、`ICON_SMALL`、`ICON_SMALL2` 与窗口类大小图标句柄全部非零。最终发布文件大小 13,357,568 字节，SHA-256：`A81861A7D32DB5C621CB7E8DD16C27927947C98D5A27F85C7891F971CC803B01`。

### 2026-07-19 P4b.2c（外部项目变化、时间线与 Diff）

- Windows 当前项目使用原生递归 `ReadDirectoryChangesW` 监听，不做高频全目录扫描；切换、关闭或恢复项目时会同步切换监听句柄，程序退出时取消挂起 I/O 并释放目录句柄。
- 终端命令、Git、格式化器和其他编辑器造成的新建、修改、删除与重命名会按路径合并，最多保留 200 个待注入变化和 300 条最近时间线；生成目录、IDE 缓存与 Murong 自身临时文件默认排除。
- Murong 直接文件/代码编辑会在原生通知到达前登记短时抑制，只过滤本次写入的重复回声，不删除同路径此前已经存在的真实外部变化；Shell 工具产生的修改故意不抑制。
- 下一条用户消息发送时会原子消费待处理变化，将结构化记录保存进会话并追加到模型上下文；会话追加失败会恢复待处理记录。分叉、导入导出、完整备份、Markdown 阅读副本和上下文压缩均保留或识别这些记录。
- 聊天顶部只增加紧凑的“项目变化 N”胶囊；点击后按需展开最近时间线和文件 Diff。Git 项目显示 `HEAD`、暂存区、工作区或未跟踪文本补丁，新仓库没有初始提交也可用；非 Git 项目显示当前 UTF-8 文本，路径、符号链接、大小与 256 KiB 截断边界均受校验。
- 新增只读 `workspace_diff` Agent 工具，计划模式可用；审批模式、Provider、MCP 与现有工具权限语义未改变。测试覆盖原生 Windows 通知、合并/消费/恢复、AI 直写过滤、生成目录排除、会话深拷贝与摘要、Git/未跟踪/无初始提交/非 Git Diff、中文安全截断、导入校验和 UI 静态契约。
- `node --check frontend/dist/app.js`、`go test ./...`、`go vet ./...` 和单 EXE Release 构建通过。隔离数据目录真实窗口验收确认外部编辑后顶部即时显示“项目变化 1”，弹层显示 1 个待注入变化、时间、大小与实际补丁。发布文件大小 13,442,560 字节，SHA-256：`3387B0C7C472489B6D3425A93F2B91BCC6DAE6433FDA7E8D9179A68D539A9B8D`。

### 2026-07-19 桌面聊天 Composer 与任务栏身份再次收口

- 聊天右上角删除入口最终统一为明确的“删除”文字，不再依赖任何删除字符或图标；仍保留二次点击防误删语义。
- 移除输入框底栏独立的 `@ 文件 / 文件夹` 按钮。底栏只保留 `＋`、输入提示、模型连接和发送；项目文件/文件夹、目标模式、计划模式、Skills、子代理和 MCP 全部只从 `＋` 菜单进入。
- `＋` 菜单为尚未打开项目、未创建 Skill、未启用子代理或未连接 MCP 的状态保留明确分区和下一步说明，不再因空数组直接隐藏能力入口。
- 输入框右下的模型连接改成带固定“模型”标签的复合控件；即使没有 Provider，也明确显示“未配置模型”，避免下拉框在浅色底栏中看起来像不存在。
- Windows 进程在创建 Wails 窗口前设置稳定的显式 AppUserModelID `Murong.DesktopAgent`，并在窗口显示后连续四次发布大小窗口图标和窗口类图标，降低任务栏或悬停预览继续命中旧灰白身份缓存的概率。
- 静态契约新增“文件入口不得位于 `＋` 旁边”、固定模型标签、五类 `＋` 菜单内容和各空状态检查；`node --check frontend/dist/app.js`、`go test ./...`、`go vet ./...` 与正式单 EXE 构建全部通过。
- 从最终 EXE 提取到 32×32 正式图标，1024 像素中 932 个为有色像素，确认发布资源不是 Windows 灰白默认图标。发布文件大小 13,542,400 字节，SHA-256：`DA16EA0A2BF89B0A2893446985C3F91836B6149DDEC853CDDA98CDD1B5098641`。

### 2026-07-19 P4b.2b（Android / Windows 跨端会话数据映射）

- 新增共同的 `murong-portable-session` v1 JSON schema；Windows 与 Android 使用完全相同的格式名、版本、平台、时间、会话、消息、用量和摘要字段，平台间导入不再依赖各自内部持久化模型碰运气解析。
- 两端继续保留原有完整 JSON：Windows 任务详情区区分“导出完整 JSON”和“导出跨端 JSON”，统一“导入会话”同时接受两种格式；Android 导出对话框新增“跨端 JSON”，现有“导入对话”会严格识别该格式。
- 跨端包只包含标题、时间、Provider/模型标识、会话目标、用户/助手/工具消息、子代理类型、工具名、Token 用量和可安全重映射的摘要。项目绝对路径、文件/文件夹上下文、外部变化、审批、检查点、API Key、登录态、图片缓存路径、推理文本和 system 指令均不进入该格式。
- Android `tool_exec` 与 Windows `tool` 双向映射，Android `subagent` 映射为 `assistant + kind=subagent`；导入时重新生成两端本机消息 ID。只有满足目标端压缩策略的摘要才恢复为活动摘要，短摘要会被安全忽略而不会阻止整份会话导入。
- 两端都限制文件 32 MiB、最多 50,000 条消息、单消息 4 MiB、摘要 1 MiB，并校验格式/版本、来源平台、时间、角色、计数、Token 非负、未知字段与尾随 JSON；跨端 schema 不接受借未知字段夹带项目路径。
- Windows 自动化测试覆盖本机往返、Android 文档导入、平台字段排除、摘要 ID 重映射、短摘要降级、未知字段和尾随数据；Android 测试覆盖路径/图片/推理/system 排除、角色与时间映射、Windows 文档导入、目标会话和未知字段拒绝。
- `node --check`、Windows `go test ./...` / `go vet ./...`、Android core 定向单测与 `:app:compileDebugKotlin` 全部通过。Android `:app:installRelease` 用时 7 分 54 秒，已安装到 `RMX5200`，包版本 `1.30 (26071902)`。
- Windows 单 EXE 大小 13,555,712 字节，SHA-256：`A39F5B3A2438A5C153928A833CC6402C92536461F00CDF687176A649AC2EDAFA`。Android Release APK 大小 29,151,965 字节，SHA-256：`0BA43325839BC6074D97890DBB15D01BCA5B4F6103415A7E588BDDF8BD907693`。
- 本阶段完成的是可靠的双向文件互导和共同数据模型，不宣称已经完成异网实时会话同步；手机实时查看、审批和接管桌面任务仍属于后续跨端传输协议工作。

### 2026-07-19 Composer 推理强度快捷选择

- 修复聊天底栏只有模型连接、没有推理强度的功能缺口；模型选择旁新增固定“推理”控件，提供默认、低、中、高、超高和最大六档，与设置页已有取值完全一致。
- 推理切换绑定当前活动 Provider；切换模型连接后控件立即显示该连接自己的推理强度，不把推理设置误做成所有模型共享的全局值。
- 新增独立 `SetProviderReasoningEffort` 后端入口，只复制并更新目标 Provider 的 `reasoningEffort` 后原子写盘，不提交设置页未保存草稿，也不会改写 API Key、审批模式、工具权限、模型或其他 Provider。
- Agent 运行时模型与推理控件同时禁用，避免用户误以为切换会影响已经发出的请求；空值明确显示“默认”，由供应商或模型决定。
- 自动化测试覆盖六档 UI、前端后端绑定、非法强度拒绝、持久化重载、其他 Provider 不变、全自动/999 迭代等普通设置不变和 DPAPI Key 可正常解密；`node --check`、`go test ./...`、`go vet ./...` 与单 EXE Release 构建全部通过。
- 最终 EXE 已确认内嵌 `composer-reasoning-select`、`SetProviderReasoningEffort` 和切换提示。发布文件大小 13,562,880 字节，SHA-256：`AF1BF0AB5CC22185089CFC5C578F4259CD241430331313B6342C3BC254D0E2BE`。

### 2026-07-19 P5a（手机查看与控制 Windows 桌面任务）

- 没有新增第三套服务或第二个 EXE；桌面任务复用现有 Windows→Android 配对连接、Bearer 凭据、SSE、心跳、重连和私网地址规则。手机与电脑同一 Wi-Fi 可直接连接，异网继续使用用户自备的 Tailscale/Headscale 可达地址，当前不宣称内建公网云中继。
- Windows “手机远程节点”新增两个默认关闭且持久化的权限：“在手机显示桌面任务”和“允许手机控制桌面任务”。前者只读同步，后者才允许发消息、停止运行和处理审批；已有文件写入/多终端授权不会隐式开启聊天共享。
- 桌面端快照最多包含 500 个任务摘要和当前打开任务最近 200 条消息；省略 API Key、登录状态、项目绝对路径、Composer 文件/目录上下文、外部变化、检查点及审批原始 `arguments`。审批只发送工具名、摘要、供用户判断的裁剪详情和风险级别。
- Android 新增内存态桌面任务桥接与全屏“电脑任务”界面：可横向切换 Windows 任务、查看用户/Murong/工具消息、看到运行和待审批状态；在 Windows 开启控制权限后可继续发送、停止及批准/拒绝。手机不把桌面聊天落盘，节点断开、超时、停止服务或撤销配对后立即清空快照。
- 协议新增注册、快照、命令结果、状态和断开端点，以及只投递给目标已配对客户端的 `desktop_agent_command` SSE 事件。命令绑定节点会话、一次性 request ID、20 秒过期和去重记录；控制命令在 Android 与 Windows 两端都检查持久权限。
- Windows 任务变化正常在 3 秒内同步，内容不变时只做 10 秒存活刷新；服务端 30 秒未收到快照即过期。手机选择的任务会成为该连接后续快照的活动任务，不会被定时刷新跳回最新任务。
- 自动化覆盖 Windows SSE 命令分发、任务配置持久化、敏感字段排除、4 MiB 传输上限与最新消息保留、桌面 UI 权限契约；Android 覆盖只读/控制权限、命令完成、过期清理、配对 HTTP 注册/快照/状态与撤销清理。`node --check`、Windows `go test ./...` / `go vet ./...`、Android `:app:compileDebugKotlin`、`:app:testDebugUnitTest` 和 LAN 定向单测全部通过。
- Windows 正式单 EXE 大小 13,605,376 字节，SHA-256：`2A122821C47CEFDDA37E1EAD7D79F6F3C9B2E3C4DD05F2BCDF9FA07F9A13A350`；已确认内嵌推理选择、桌面任务权限与 `desktop_agent_command` 协议标记。
- Android 首次 `installRelease` 在 8 分 37 秒后因 Windows 短时锁定 `dex-metadata-map.properties` 失败，停止残留 Gradle daemon 后使用原增量产物重试成功，并安装到 `RMX5200`（`192.168.2.4:5555`）。已安装版本 `1.30 (26071902)`；Release APK 大小 29,199,656 字节，SHA-256：`C96B475623EF542F12091F14E16F335181015F5403E9DDA74C5A5296064BB3E2`。

### 2026-07-19 P3b.2（GitHub 写入、外部工作流与旧版 MCP SSE 收口）

- 审计确认 GitHub 仓库工具已真实接入统一 Agent 工具分发：仓库信息、UTF-8 文件读取、分支与 Issue 列表为低风险；创建分支、带远端 SHA 防冲突的文件提交、创建 Issue 和创建 Pull Request 为高风险，继续经过只读/全审批/白名单/全自动四档审批。Token 只从 DPAPI 运行配置解密，所有写请求要求 Token，并阻止携带凭据跨站重定向。
- GitHub 文件更新在 PUT 前重新 GET 当前远端 SHA；已有文件未给 `expected_sha`、SHA 不匹配、文件不存在却给 SHA、路径穿越、非法 ref、非 UTF-8、NUL 和超过 1 MiB 均拒绝。Issue/PR 标题、正文、head/base 和仓库名均有独立边界。
- 旧版 MCP HTTP+SSE 已作为第三种传输出现在 Skills 下方的 MCP 编辑器。客户端先保持 SSE GET，严格解析同源 `endpoint` 事件，再把 JSON-RPC POST 到消息端点；支持 initialize、通知、工具分页/调用、并发/超时/大小限制、关闭取消和请求头密钥脱敏，跨源消息端点会被拒绝。
- 单一发布 EXE 新增本机外部入口：`"murong-desktop-agent-amd64.exe" --run-workflow <id>`。冷启动参数会在工作流管理器启动后处理；已运行时第二次调用通过 Wails 单实例通道送入现有窗口，不启动第二套 Agent。自动化页只为固定只读/网络只读模板显示“复制外部命令”，适合 PowerShell、Windows 任务计划程序或其他本机自动化。
- 外部入口只接受保存的工作流 ID，不接受临时项目路径、任务文字、Token 或任意 Shell 参数；调用仍走现有校验、去重队列、超时与运行记录。会话 Markdown 导出等写入型模板不会生成命令，即使伪造参数也在后端被拒绝并要求回到 Murong 前台逐次确认。
- 自动化新增命令解析、普通启动忽略、缺失/重复 ID 拒绝、只读模板真实执行、写入模板阻止、命令生成与 UI 契约测试；原有 GitHub TLS 请求、写入冲突、审批和 legacy SSE 真实服务器测试继续覆盖。`node --check`、`go test ./...` 与 `go vet ./...` 全部通过。
- 为避免用户继续启动旧发布文件，已在 Composer 推理快捷选择和本阶段功能全部合入后重新生成单一正式 EXE；构建脚本再次执行完整 Go 测试，并确认发布包包含“模型 + 推理”双下拉。文件大小 13,617,152 字节，SHA-256：`EFEF7BA8E2C0471B152EAD8C8F606B00B0E24773DD17B2BDA4941E23D90879E9`。

### 2026-07-19 P5a.1（Windows 内置 Codex / ChatGPT 后端）

- Windows Provider 新增 `Codex / ChatGPT`。普通用户不再安装或填写 CLI：正式构建把官方 `@openai/codex@0.144.6-win32-x64` 完整压缩包嵌入同一个 Murong EXE，首次使用时在 Murong 私有运行目录释放；外部 `codex.exe` 路径仅保留在高级折叠项中供诊断或版本覆盖。
- 构建固定官方 npm 归档 URL、版本和 SHA-256 `E04AFBE9841BE306455D075AD414993A946C94A399E55D7F9EC223F734CD4101`。运行前再次校验归档哈希；解包拒绝绝对/越界路径、链接、设备文件、重复覆盖以及异常文件数/单项/总大小，使用临时目录完成后原子启用。
- app-server 使用稳定的换行 JSON 协议完成 `initialize → initialized`，并实现 `account/read`、`account/rateLimits/read` 与滚动额度更新、ChatGPT 设备码登录、分页 `model/list`、`thread/start/resume`、`turn/start/interrupt`、流式消息、工具生命周期、错误、完成状态与真实 Token 用量。P5a.1 阶段默认不读取或复制 `auth.json`；P5a.2 仅在用户明确选择跨设备账号同步时受控读取 Murong 私有 Codex Home，并在接收端交给官方 app-server 验证。
- 设置页明确显示“正式版已内置 Codex”、运行时版本、登录账号/订阅类型、5 小时/7 天等官方额度窗口、模型数量、设备代码和模型候选；Base URL、API Key 与 API 模式在 Codex Provider 下隐藏。设置页和聊天框继续提供逐连接模型与默认/低/中/高/超高/最大推理强度，实际值直接传给 `turn/start`。
- Codex 会话 ID 只作为本机 Murong 会话的内部续接信息。新线程会携带可安全物化的现有 Murong 历史，续接只发送尚未同步的用户消息；回退、导入、分叉、完整/跨端会话导出和全局备份均清除 Codex thread/message ID，防止跨机器串会话。CLI 路径和 ChatGPT 登录状态同样不进入备份。
- 只读和计划模式映射为 `never + read-only`，全自动映射为 `never + danger-full-access`，不会出现隐藏确认；全审批和白名单使用 Codex 的用户审批请求并回到 Murong 现有审批卡，命令、工作目录与授权根继续参与命令/路径白名单。停止任务会发送 `turn/interrupt`。
- 自动化新增归档哈希/安全解包/高级覆盖、四档映射、未同步历史、事件/用量解析、机器关联排除、Codex 备份校验、前端内置运行时与登录绑定测试。普通 `go test ./...`、`go vet ./...` 和 `node --check frontend/dist/app.js` 通过。
- 使用这台电脑现有 ChatGPT 登录对即将内置的官方 0.144.6 原生包做了真实 app-server 验证：账号读取和模型分页通过，只读临时线程收到流式文本并完成。当前网络先多次重连 WebSocket、再降级 HTTPS，真实回合约 116 秒成功；证明链路可用，也说明慢网下等待一分钟以上不应误判为应用卡死，用户可随时停止。
- Release 构建新增独立门禁：在生成 EXE 前把正式归档按 release build tag 编入测试二进制，重新计算哈希、完整释放、核对 `codex-package.json` 版本，并实际执行释放后的 `codex.exe --version`；任一环节失败即停止发布。
- 最终仍为单个文件：[murong-desktop-agent-amd64.exe](../desktop-agent/dist/murong-desktop-agent-amd64.exe)，大小 158,988,288 字节（151.62 MiB），SHA-256：`353F3CAAC38765681C2995A139C2219482A73311E1CD738191CA101BE1DC39E3`。

### 2026-07-19 P5a.2（API Key 与 Codex / ChatGPT 登录双向同步）

- 按用户确认修正产品边界：Android 与 Windows 都是第一方 Murong，API Key 和 Codex/ChatGPT 登录不再被硬性排除出设备同步。Windows 远程节点页新增“模型连接与 API Key”“ChatGPT / Codex 登录”两个独立选项，以及“同步到手机 / 从手机同步到电脑”两个方向；每次执行都显示内容与目标设备并要求明确确认。
- 新配对码扩展为 16 位高熵字符。Windows 请求只发送配对码的 SHA-256 证明，不发送原配对码；手机仅在五分钟配对窗口的内存中保留原码。首次访问令牌与 32 字节设备同步密钥使用 PBKDF2-HMAC-SHA256（210,000 次）派生的 AES-256-GCM 密钥封装，HTTP 旁路监听者不能同时获得原码和解密材料。
- 每个客户端拥有独立设备同步密钥：Android 使用 Android Keystore AES-GCM 包装保存，Windows 使用当前用户 DPAPI 保存。后续 push/pull 使用 AES-256-GCM 独立加密，AAD 绑定协议版本、request ID、时间和方向；服务端限制两分钟时钟窗口、请求去重、8 MiB 上限和严格 JSON，密文或元数据被改动都会拒绝。
- Provider 同步覆盖 OpenAI-compatible、DeepSeek 与 Claude 的连接 ID、名称、Base URL、模型、推理强度、上下文窗口和非空 API Key。Android API Key 进入现有 Keystore 安全存储，Windows 进入 DPAPI；普通设置 JSON、前端状态、日志和普通备份包中不出现明文 Key。
- Windows Codex 运行时改用 `%LOCALAPPDATA%/Murong/runtime/codex-home` 私有目录；首次升级可从用户原 `~/.codex/auth.json` 做一次只读迁移，不修改源文件。Android 继续使用应用私有 `files/codex-home`。Codex 登录同步前校验 JSON 类型和 256 KiB 上限，替换后实际执行 `account/read(refreshToken=true)`；验证失败会恢复原登录并重启官方 app-server。
- 两端导入都按整包事务处理：模型连接与登录任一步失败会恢复原 Provider 配置和原 Codex 登录；Android 还会删除本次新建连接已写入的 Keystore 密文，Windows 会恢复原配置文件。旧配对没有设备同步密钥，仍可使用文件、终端和桌面任务，但 UI 会明确要求撤销后重新配对才能同步账号。
- 自动化覆盖原配对码不得出现在 HTTP 请求、配对响应解密与篡改、设备同步密文/AAD 篡改、重放/格式边界、同步密钥保存与撤销、Codex 登录文件校验、Windows DPAPI 明文排除、Provider 快照恢复和桌面双向确认 UI。`node --check`、desktop-bridge/desktop-agent `go test ./...`、`go vet ./...`、Android `:app:compileDebugKotlin` 与 LAN 定向单测全部通过。

### 2026-07-19 P5a.3（Windows 任务会话持久镜像）

- 修复“电脑端新增聊天只在电脑显示、手机端连接快照不更新且断线即丢失”的结构性问题。Windows 仍是桌面任务的唯一执行端，Android 只保存可查看/控制的应用私有镜像，不把 Windows 项目路径、工具运行态或远程任务伪装成本机 `ConversationStore` 会话，避免两端 Agent 同时续写造成冲突。
- 复用现有稳定 Windows 会话 ID 与消息 ID，快照新增总消息数和最多 6 个最近任务详情增量。当前任务保留较大历史窗口，其他最近变化任务使用独立有界窗口；Windows 按实际 JSON 编码字节而非原始 UTF-8 计预算，含引号、换行或控制字符的工具输出也不会突破 Android 4 MiB 请求上限。
- Windows 新建任务、在桌面显式打开任务或发送消息时会更新手机当前跟随任务；手机选择任务仍通过 `get_session` 切换同一 Windows 任务。手机发送消息后先持久化 Windows 返回的用户消息，再由 3 秒快照持续收敛流式助手/工具结果，任务和消息都按稳定 ID 覆盖，不按文本猜测合并。
- Android 新增 `desktop_agent_mirror_v1.json` 私有镜像，保存任务摘要和已同步详情；最多缓存 64 项详情、详情总预算 24 MiB、文件硬上限 32 MiB。服务停止、30 秒心跳过期、网络断开和应用进程重启后仍可从主聊天抽屉的独立“电脑任务”分组或设置页打开离线记录；运行中、待审批和控制权限属于易过期状态，落盘前会清除，离线不允许发送、停止或审批。
- 重新连接同一配对客户端后先显示本地副本，再以 Windows 权威快照覆盖；用户撤销单个配对或全部配对时同步删除对应手机镜像。不同 Windows 配对不会读取其他客户端的镜像，后台详情增量也不会改变手机当前正在阅读的任务。
- GitNexus 影响分析显示直接改 Android `ConversationStore` 会影响 92 个调用点、风险为 CRITICAL，因此本阶段采用独立镜像存储；`LanWebDesktopAgentBridge` 影响 7 个调用点、风险为 MEDIUM，并已补齐过期保留、进程重建、后台任务增量、离线切换和撤销删除测试。
- 验证通过：desktop-agent/desktop-bridge `go test ./...`、`go vet ./...`，Android `LanWebDesktopAgentBridgeTest`、`LanWebServerIntegrationTest`、主界面 Debug/Release Kotlin 编译与 Release 安装。单 EXE 正式包 159,102,976 字节，SHA-256：`F8E25C604FC538D5731CC60927EB0BD3AD19398B9B189FCD92044EC9A60330A9`；Android APK 29,250,809 字节，SHA-256：`D4045E3FF01A9892BBD87B37304610620BEC7F08C8210BB8DB6C2C6C6196F41A`，已安装到 RMX5200，版本 `1.30 (26071902)`。
- 最终 `gitnexus detect-changes --repo MurongAgent --scope all` 报告 43 个文件、546 个符号和 71 条流程，整体风险为 critical；这是包含此前 Android、终端、语音和整套未跟踪 Windows 模块的全工作区结果。本阶段直接修改前的专项影响为 `ConversationStore` CRITICAL（因此未改）、`LanWebDesktopAgentBridge` MEDIUM、`SessionDrawerContent`/`MainScreen` LOW；未创建提交。
- Android 最终 `:app:installRelease` 用时 9 分 23 秒，已覆盖安装到 `RMX5200`（`192.168.2.4:5555`），版本 `1.30 (26071902)`。Release APK 大小 29,231,904 字节，SHA-256：`075250E36AFDE0E14D9B34BC8C064A6C53C708D0AD56D55D0A4B7102B3681616`。
- Windows 最终仍为单文件 [murong-desktop-agent-amd64.exe](../desktop-agent/dist/murong-desktop-agent-amd64.exe)，内置官方 Codex 0.144.6，大小 159,100,928 字节（151.73 MiB），SHA-256：`29CA9104CDD90965044BB2682A63A27F79F83448F6443CD9FAA6322B9FEBEAF3`。

### 2026-07-19 P5a.4（统一设备同步与敏感凭据传输边界）

- 按用户纠正再次明确产品边界：手机与电脑是同一套第一方 Murong，API Key 和 ChatGPT/Codex 登录令牌必须能够传输，不能因为普通备份默认排除就硬性禁止设备同步。普通 ZIP 备份继续默认排除 API Key、登录和 MCP/GitHub 鉴权值；已安全配对的设备同步则允许用户逐类别选择并确认后传输。
- 原“账号与模型同步”升级为 schema v2 版本化“手机与电脑设备同步”。Windows 页面提供模型连接/API Key、ChatGPT/Codex 登录、Agent 通用设置、规则/记忆/Skills、MCP 配置、MCP 环境变量/请求头凭据、保存的工作流七类显式选项，以及“同步到手机 / 从手机同步到电脑”两个方向；确认弹窗逐项列出本次内容和目标设备。
- Provider 继续同步连接 ID、地址、模型、推理强度、上下文窗口和非空 API Key；Codex 登录继续在接收端用官方 `account/read(refreshToken=true)` 联网验证。Agent 通用设置只包含跨平台审批模式、系统提示词和回复详细度，不传项目路径、命令/路径白名单、终端后端或可执行文件路径。
- 规则、记忆和 Skill 按稳定 ID 更新并保留目标端其他条目；Android 导出时合并 legacy config 与 durable 全局记忆。MCP 按名称匹配，HTTP/SSE 定义可直接迁移；stdio 命令不携带 cwd，跨平台导入后强制关闭，避免把 Windows 命令直接在 Android 执行或反向执行。MCP 凭据选项关闭时保留目标端原凭据并合并非敏感配置，开启时环境变量/请求头进入同一 AES-256-GCM 密文；Windows 落盘使用 DPAPI，Android API Key/敏感请求头使用 Keystore，其他 MCP 环境变量仅写应用私有目录。
- 保存的工作流不传项目路径、运行记录或计划运行状态。GitHub Actions、会话摘要等无项目路径定义按 ID 导入且保持关闭；项目诊断/目录摘要等路径绑定工作流会被跳过并在结果中计数，避免泄露或误用另一台设备的目录。
- Android 导入前保存 Provider、MCP 与工作流快照，Windows 保存完整配置和工作流快照；任一类别或 Codex 登录验证失败都会回滚本次所有已应用类别。同步仍受 8 MiB 明文上限、AES-GCM AAD、时间窗、请求去重、严格 JSON、数量/字段/URL/凭据长度校验保护，敏感临时结构使用后主动清空。
- GitNexus 变更前影响分析将 `LanWebCredentialSyncBridge` 标为 CRITICAL（26 个下游符号、8 条受影响流程、4 个模块），Windows push/pull 链也连接配置存储、Codex、MCP 与工作流，因此本阶段保留旧 endpoint/方法名兼容绑定，只扩展 v2 载荷和事务层，没有另起明文配置接口。
- 验证通过：`node --check frontend/dist/app.js`；desktop-agent `go test ./...`、`go vet ./...`；desktop-bridge `go test ./...`、`go vet ./...`；Android `:app:compileDebugKotlin`、`LanWebDeviceSyncContractTest`、`LanWebDeviceSyncCryptoTest`。新增自动化覆盖 API Key/Codex 字段往返、Agent 设置/知识库导入、MCP 凭据 DPAPI 明文排除、跨平台 stdio 自动关闭、路径工作流跳过、目标凭据保留和桌面逐类别确认 UI。
- Windows 正式版仍为单文件 [murong-desktop-agent-amd64.exe](../desktop-agent/dist/murong-desktop-agent-amd64.exe)，大小 159,160,320 字节，SHA-256：`B57CB4007B6DA9FB298760EEBC0D41D99B9161630B51DC00688E718319412862`。
- Android 首次 `:app:installRelease` 在 8 分 30 秒后因 Windows 短时锁定 `compileReleaseArtProfile/dex-metadata-map.properties` 失败；停止残留 Gradle daemon 后复用增量产物 36 秒重试成功，已覆盖安装到 `RMX5200`（`192.168.2.4:5555`），版本 `1.30 (26071902)`。Release APK 大小 29,280,643 字节，SHA-256：`1C44EB290A17FE22FF529A09FFDE70B1DE5A1E0334890CFEEA1C0AA43E9858D4`。

### 2026-07-19 P5a.5（生成参数真实对齐）

- 审计确认 Android `ProviderConfig` 和 `AgentLoop` 已持久化并发送 `temperature`、`maxTokens`，而 Windows OpenAI Chat Completions / Responses 未发送、Claude 固定为 `0.7 / 8192`，导致同一模型跨端行为不一致。本阶段将 Windows 配置 schema 升至 v7，设置页新增 `Temperature (0..2)` 与 `最大输出 Token (1..128000)`；旧配置稳定迁移为 `0.7 / 8192`，非法值也回退到该默认值。
- OpenAI-compatible、DeepSeek 的 Chat Completions 请求真实携带 `temperature` 与 `max_tokens`；OpenAI Responses 请求真实携带 `temperature` 与 `max_output_tokens`；Claude Messages 请求使用相同的 `temperature` 与 `max_tokens`。主 Agent、会话摘要和五类子代理均从当前 Windows 配置构造模型客户端，不再各自使用隐藏默认值。
- 普通版本化 ZIP 备份保存这两项非敏感设置，并用可空字段兼容旧备份；恢复前完成范围校验，旧备份缺失字段时使用默认值。设备同步 schema v2 的 Agent 设置同样以可空字段扩展，Android/Windows 均可双向导出、导入和校验；旧端或旧载荷不提供字段时保留接收端现值。API Key、ChatGPT/Codex 登录令牌和可选 MCP 凭据继续只在用户逐项选择并确认后走既有 AES-256-GCM 通道，普通备份仍默认不含凭据。
- GitNexus 变更前专项分析中配置、Provider 请求和设备同步结构大多为 LOW；`newModelClient` 为 HIGH（主 Agent、摘要、子代理三条流程），因此保持原构造入口兼容并让三条调用链显式传入统一参数。新增回归覆盖三种供应商请求 JSON、旧 schema 迁移、配置重载、备份恢复、设备同步导入及 Android 序列化契约。
- 验证通过：`node --check frontend/dist/app.js`；desktop-agent `go test ./...`、`go vet ./...`；desktop-bridge `go test ./...`、`go vet ./...`；Android `:app:compileDebugKotlin`、`LanWebDeviceSyncContractTest`、`LanWebDeviceSyncCryptoTest`；Windows 单 EXE Release 构建和 Android `:app:installRelease`。
- Windows 正式版仍为单文件 [murong-desktop-agent-amd64.exe](../desktop-agent/dist/murong-desktop-agent-amd64.exe)，大小 159,158,272 字节，SHA-256：`7FF578DDFC895C862D442F7FF9D439BBA87C3E95E1FA965F4CE95E12809B11CC`。
- Android Release APK 大小 29,280,751 字节，SHA-256：`8445A339B9E1B48DCE04BFA1B308AC2A8E8C84447FBA11B0C2B5365B7318F7B1`；已覆盖安装到 `RMX5200`（`192.168.2.4:5555`），版本 `1.30 (26071902)`，设备记录的最后更新时间为 `2026-07-19 19:24:24`。

### 2026-07-19 P5a.6（Windows 多模态图片消息）

- Windows 新增真实图片消息链路，不是只做文件选择器：聊天可一次选择最多 8 张 JPEG、PNG、WebP、GIF 或 BMP；导入时严格解码、限制源文件 20 MiB/尺寸 32768、最长边缩到 2048、压缩后单张不超过 4 MiB，并以随机 ID 原子写入 Murong 私有 `conversation_media`，会话只保存校验过的元数据和相对缓存名。
- Composer 显示待发送缩略图、可逐张移除；历史消息按会话/消息/附件三重归属校验后读取私有缓存，缓存缺失会显示不可用状态而不是读取任意路径。设置新增“允许图片消息”，关闭后前端选择项和后端选择/发送入口同时拒绝；Windows 配置 schema 升至 v8，旧配置默认保持开启。
- OpenAI-compatible/DeepSeek Chat Completions 使用 `image_url` data URL，OpenAI Responses 使用 `input_image`，Claude Messages 使用 base64 image source，内置 Codex 使用经私有目录校验的 `localImage` 绝对路径。Codex 只给当前用户回合附图，历史图片只保留文件名提示，避免每轮重复发送全部二进制。
- 完整版本化 ZIP 备份把被会话引用的媒体作为带大小/SHA-256 的独立条目；恢复前校验清单、路径、附件元数据和引用完整性，再使用暂存目录/重命名事务替换媒体，失败时与配置、会话、工作流一起回滚。普通会话文件导出不携带本机缓存引用，避免跨机器产生悬空私有路径。
- 手机端桌面任务镜像仅同步附件文件名，不同步 Windows 缓存路径、data URL 或图片字节。图片消息开关进入现有 schema v2 Agent 设置同步；旧端缺字段时保留接收端现值。API Key 与 ChatGPT/Codex 登录令牌仍按用户确认默认允许在同一 Murong 的安全配对设备间选择并加密双向传输，普通 ZIP 备份则继续默认排除凭据。
- 新增回归覆盖图片导入/缩放/私有路径、会话持久化、关闭开关的双端拒绝、四类模型协议编码、Codex 当前回合边界、完整备份媒体恢复、远程任务附件脱敏、API Key/Codex 登录真实导出与临时秘密清空、设备设置协议和桌面 UI。验证通过：`node --check frontend/dist/app.js`；desktop-agent/desktop-bridge `go test ./...`、`go vet ./...`；Android `:app:compileDebugKotlin`、`LanWebDeviceSyncContractTest`、`LanWebDeviceSyncCryptoTest`、`LanWebDesktopAgentBridgeTest`。
- 最终 `gitnexus detect-changes --repo MurongAgent --scope all` 映射到 43 个文件、546 个符号和 71 条流程，整体为 critical；该结果覆盖工作区此前累计的 Android、终端、语音及整套未跟踪 Windows 模块，不等同于 P5a.6 专项风险。图片链路变更前对桌面 `ChatMessage`/克隆/追加/Codex 输入的影响为 HIGH，因此没有只改 UI，而是同步补齐存储、模型协议、备份、远程脱敏和回归测试。
- Windows 正式版仍为单文件 [murong-desktop-agent-amd64.exe](../desktop-agent/dist/murong-desktop-agent-amd64.exe)，大小 159,674,368 字节，SHA-256：`4899F2C9F9DAED749B065F320FD10F9B182A0C27D6C72948E9F10791464C8DFF`。
- Android `:app:installRelease` 用时 8 分 10 秒并成功覆盖安装到 `RMX5200`（`192.168.2.4:5555`），版本 `1.30 (26071902)`，最后更新时间 `2026-07-19 20:17:12`。Release APK 大小 29,281,440 字节，SHA-256：`FB9DA69C69C99285249A9E8C4AAECB2D1C747B9F95455298500B409A879A1633`。

### 2026-07-19 P5a.7（GitHub 登录与 Token 双向同步）

- 按用户纠正统一账号边界：Android 与 Windows 属于同一第一方 Murong，API Key 和可跨平台使用的登录令牌不应被普通备份的排除策略连带禁止。模型 API Key、ChatGPT/Codex 登录、MCP 可选凭据继续可传，本阶段再把 GitHub API Base、Access Token 与用户标识纳入同一设备同步；Windows 页面默认勾选“GitHub 登录与 Token”，同步前仍逐项显示并确认方向和目标设备。
- 设备同步载荷升级为 schema v3，GitHub 字段保持可选；Android 与 Windows 都继续接受 schema v1/v2。来源带非空 Token 时覆盖目标凭据，来源不带 Token 时只更新 API Base 并保留目标端原 Token 与登录用户，避免从未登录设备反向同步时清空已登录设备。
- GitHub Access Token 与其他敏感项一样只存在于已配对设备的 AES-256-GCM 密文中。Android 接收后复用 `ConfigRepository.saveConfig` 写入 Android Keystore，Windows 接收后先用当前用户 DPAPI 加密再原子写入工作流配置；前端、普通设置 JSON、日志和普通 ZIP 备份仍不出现明文。Android 的 OAuth 后端会话和客户端私钥属于设备/服务端专用状态，不作为跨平台运行凭据复制；同步后的 GitHub Access Token 本身即可驱动登录状态与仓库工具。
- Android 修复原先“必须同时存在后端会话和 GitHub Token 才算已登录”的限制；现在非空 Access Token 即可使用 GitHub 工具。Token 变化时清除旧头像/名称缓存，保留并导入来源用户名；Windows 仅在确实导入 Token 时更新内存用户名，缺失 Token 不会覆盖目标登录状态。
- 两端继续按整包事务导入。Android 在后续 MCP、工作流或 Codex 验证失败时通过原配置快照恢复 Keystore Token；Windows 将 GitHub 配置和保存工作流合并为同一个快照只写一次，后续失败时恢复原 DPAPI Token、API Base 与工作流。导入前还会校验协议版本、时间窗、HTTP(S) API 地址、用户名控制字符和 Token 长度，传输后的临时字符串引用会主动清空。
- 新增回归覆盖 schema v3 GitHub 字段往返、Access Token 单独登录、Android Token 合并/缺失保留、Windows DPAPI 导入解密、工作流合并不丢 Token、无来源 Token 保留目标凭据、强制失败回滚和桌面逐项确认/结果提示。验证通过：`node --check frontend/dist/app.js`；desktop-agent 与 desktop-bridge 的 `go test ./...`、`go vet ./...`；Android `ProviderConfigGitHubSignInTest`、`:app:compileDebugKotlin`、`LanWebDeviceSyncContractTest`、`LanWebDeviceSyncCryptoTest`、`LanWebGitHubCredentialMergeTest`。
- 最终 `gitnexus detect-changes --repo MurongAgent --scope all` 映射到 43 个文件、547 个符号和 71 条流程，整体为 critical；这是包含此前终端、语音、Android 和完整未跟踪 Windows 模块的全工作区累计结果。变更前专项影响将 `ProviderConfig.isGitHubSignedIn` 标为 HIGH、`ConfigRepository` 标为 CRITICAL，因此本阶段复用既有 Keystore 写入入口，没有重构全局配置仓库。
- Windows 正式版仍为单文件 [murong-desktop-agent-amd64.exe](../desktop-agent/dist/murong-desktop-agent-amd64.exe)，大小 159,681,024 字节，SHA-256：`75831912FE1C36BF9803DDAF35442B661F7AD2C89A6E464A7BD8F6DF9953DACA`。
- Android `:app:installRelease` 用时 9 分 16 秒并成功覆盖安装到 `RMX5200`（`192.168.2.4:5555`），版本 `1.30 (26071902)`，最后更新时间 `2026-07-19 20:53:41`。Release APK 大小 29,285,728 字节，SHA-256：`ED9669EBB0053B3103B8A53E54AD69A58419BCD2E2512881661BAA1D8CAFBABE`。

### 2026-07-19 P5b.1（Windows 子代理批量与依赖编排）

- 通用 `subagent_launch` 新增两种真实批量入口：`batchGoals` 用于 2 到 6 个独立目标，`parallelTasks` 用于带 `label`、`goal` 和 1-based `dependsOn` 的静态依赖图；两者互斥，后端严格拒绝空目标、未知字段、越界/自身/循环依赖和超过 6 项的请求，不能只靠模型遵守前端 schema。
- Windows 不照搬手机端最多 2 个执行槽位的性能约束：同一批次中所有已满足依赖的任务会立即并发启动，独立批次最多 6 项可一次全部运行。前置全部成功后才释放后继；前置失败、跳过或取消时自动跳过依赖项。父任务取消会通过同一 `context` 传给正在运行的子代理，并把尚未启动的任务标记为取消；最终结果始终按原任务序号稳定汇总，不按完成先后打乱。
- 批次在启动前解析每个子目标的模型、推理和工具预算，并只提交一次包含全部任务、依赖、并发方式及高风险能力的审批。批准后子代理在已经声明且与父级配置取交集的工具/工作区预算内运行，不再产生多个相互竞争的审批弹窗；只读模式仍拒绝声明写入/Shell 的批次，全自动继续没有隐藏确认。
- 单个子代理仍保留原执行入口和 32 次内部工具迭代保护；批量子代理不允许递归派发子代理，非成功 JSON 会被视作依赖失败，每项输出限制为 12,000 字符，避免六个结果同时撑爆父上下文。解密后的 Provider API Key 在每个子代理结束时主动清零。
- GitNexus 重建索引后，专项影响分析将 `executeSubagentTool` 标为 LOW（5 个上游影响、1 个直接调用、2 条流程），`toolDefinitions` 标为 MEDIUM（12 个上游影响、6 个直接调用、2 条流程），因此改动集中在桌面 Agent 的既有工具入口、schema 和契约测试，没有扩散重构 Android 或跨端协议。
- 验证通过：desktop-agent `go test ./...`、`go vet ./...`、`go test -count=1 -run "Test.*Subagent" ./...`，以及 `node --check frontend/dist/app.js`。专项调度测试覆盖显式并发预算、依赖成功释放、失败跳过、稳定顺序、取消传播、循环/越界/未知字段拒绝和工具 schema；产品入口会把预算设为当前批次任务数，不再额外限制。当前 Windows Go 环境未启用 CGO，因此 `go test -race` 不可用。
- P5b.1 的中间单 EXE 已由下方包含后台能力的 P5b.2 正式包取代；本阶段没有修改 Android 代码，因此没有重复构建或覆盖安装手机 Release。

### 2026-07-19 P5b.2（Windows 子代理后台运行与持久状态）

- `subagent_launch` 和 Explore/Research/Review/Security Review 预设新增 `background=true`。单项、独立批量或依赖图在完成统一审批后立即返回持久 `jobId`，父 Agent 可以继续当前回合；后台子代理使用提交时的项目、Provider、模型、推理和工具预算快照，不会因用户随后切换设置而越界。
- 按用户决定，Windows 不设置手机端的“最多 2 个子代理”槽位，也不新增并发选项：同一批次最多 6 项，所有已满足依赖的任务立即并行；不同后台任务也不进入人为等待槽。每个批次最多 6 项仍作为防止一次模型调用无限扩张的结构边界。
- 会话新增最多 100 条后台子代理状态记录，保存 queued/running/cancelling/completed/failed/cancelled/interrupted、分项结果和时间。达到保留数量时只淘汰最旧的已结束记录，绝不删除仍在运行的任务；单项输出继续限制 12,000 字符。完整会话与版本化完整备份保留这些状态和结果，恢复时沿用严格数量、状态、依赖、时间和大小校验。
- 桌面聊天框上方新增现代后台任务卡：显示运行/完成/失败/取消/中断、分项结果和取消按钮。后台结束后还会向原会话追加最多 6,000 字符的完成通知，使下一轮 Agent 能看到结果。新 `subagent_jobs` 工具支持摘要 `list`、按 ID `get` 完整结果和 `cancel`；列表不再一次把 100 项完整输出塞进模型上下文。
- 用户点击取消会把同一 `context` 传播给正在执行的模型和工具，未启动的依赖任务立即取消。删除会话会先取消其后台任务；完整恢复在仍有后台任务时被拒绝，避免旧任务在恢复后的数据上继续写入。Murong 重启后不会自动重放可能包含写入或 Shell 权限的旧执行，而是把 queued/running/cancelling 明确持久化为 interrupted，这与 Android 当前安全语义一致。
- 后台高风险任务在启动前仍只走一次可见审批，并明确提示后台持续运行、写入/Shell 能力及工具预算；批准后不再出现无人可处理的竞争弹窗。只读模式继续拒绝高风险批次，全自动继续不增加隐藏确认。后台记录不保存 API Key 或登录令牌；这不改变同一第一方 Murong 已配对设备之间可按类别选择并通过 AES-GCM 安全同步 API Key、ChatGPT/Codex 登录、GitHub Token 和可选 MCP 凭据的产品边界。
- 回归覆盖 6 项独立任务同时启动、依赖/失败/取消传播、状态原子落盘、应用重启转中断并再次持久化、直接取消 context、摘要列表不泄露完整结果、按 ID 读取、工具 schema、备份验证和桌面状态卡/按钮。验证通过：desktop-agent `go test -count=1 ./...`、`go vet ./...`、`node --check frontend/dist/app.js`、发布脚本的全量测试与内置 Codex 0.144.6 哈希校验；Windows 当前未启用 CGO，因此 race 模式仍不可用。
- 最终 `gitnexus detect-changes --repo MurongAgent --scope all` 映射到 43 个文件、541 个符号和 66 条流程，整体为 critical；这是包含此前 Android、终端、语音及整套未跟踪 Windows 模块的全工作区累计结果，不等同于 P5b.2 专项风险。变更前专项影响中 `ChatSession` 与 `DesktopAgentApp` 均为 LOW，`executeSubagentTool` 为 LOW 且连接 `runAgent`/远程控制两条流程，因此保留原入口并以会话附加字段和独立后台实现扩展，没有重构跨端执行协议。
- Windows 正式版仍为单文件 [murong-desktop-agent-amd64.exe](../desktop-agent/dist/murong-desktop-agent-amd64.exe)，大小 159,746,560 字节，SHA-256：`E34AF41F10742E57D18A9319D0A9C860F1737F30CDB19E70D383A0EB8E4AE670`。本阶段没有修改 Android 代码，因此没有重复构建或覆盖安装手机 Release。

### 2026-07-19 P5b.2c（跨系统、跨架构桌面发布）

- 桌面产品边界从 Windows 单平台修正为 Windows、macOS、Linux 三系统，首批同时覆盖 amd64 与 arm64。平台信息随 Bootstrap 返回前端；会话、凭据同步与备份来源使用 `windows`、`darwin`、`linux`，Android 已接受三种桌面来源。带项目绝对路径的完整备份只允许同系统恢复，纯会话跨端包仍可互导，避免把不同系统路径强行套用。
- Wails 主程序补齐 Windows、macOS、Linux 原生选项和统一 1024×1024 正式图标。Windows 使用 WebView2 和 DPAPI；macOS 使用系统 WebKit 与 `.app`；Linux 使用 WebKitGTK 4.1；macOS/Linux 凭据不再用开发期 Base64，而是由每个用户独立生成 32 字节私钥，以 AES-256-GCM 加密并依赖 `0700/0600` 用户目录权限。旧未发布 Base64 数据仅保留读取迁移兼容。
- 终端发现按宿主分流：Windows 保留 PowerShell 7、Windows PowerShell、CMD 与 WSL；macOS/Linux 发现登录 Shell、bash、zsh、fish、sh 和可选 PowerShell 7，均使用登录命令语义执行。Desktop Node 复用同一发现/执行逻辑，协议错误和手机提示改为 Desktop Node/电脑端，不再把非 Windows 主机显示成 Windows。
- 外部工作区变化监听在 Windows 继续使用 `ReadDirectoryChangesW`，macOS/Linux 使用递归 fsnotify，包含新目录动态加入、重命名/删除映射和原有 Agent 回声过滤。原先只在 Windows 跑的真实终端和原生文件监听集成测试改为各系统都执行。
- 内置 Codex 运行时按六个目标分别绑定 OpenAI `rust-v0.144.6` 官方 npm 归档、目标三元组、可执行文件名和 SHA-256：Windows x64/ARM64、macOS Intel/Apple Silicon、Linux x64/ARM64。打包前验证归档哈希，原生 runner 还会真正解压并运行内置 Codex 测试；不允许用同一 x64 二进制改名伪装 ARM64。
- 新增 [build-desktop.yml](../.github/workflows/build-desktop.yml) 六目标原生矩阵：`windows-2025`、`windows-11-arm`、`macos-15-intel`、`macos-15`、`ubuntu-24.04`、`ubuntu-24.04-arm`。每项运行 Desktop Agent/Desktop Bridge 的测试与 `go vet`，校验目标 Codex，执行原生冒烟测试，经 Wails 打包为 Windows `.exe`、macOS `.app.zip` 或 Linux `.tar.gz`，生成 SHA-256 并上传；手动触发可汇总发布 GitHub Release。Android 继续由独立 `build-apk.yml` 构建，两条工作流合计覆盖移动端和六个桌面目标。
- 当前构建没有商业代码签名秘密：Windows EXE 未做 Authenticode，macOS `.app` 未做 Developer ID 签名/公证；工作流不会伪造“已签名”。待仓库提供正式证书后再接入签名 Job。Linux 产物动态依赖 GTK3 与 WebKit2GTK 4.1 运行库。
- Windows 图标测试改为验证 Wails 正式图标源，并在打包完成后从最终 EXE 加载资源 ID 3 的大小图标；版本信息使用原生 `version.dll` 按实际翻译表查询 ProductName/FileDescription，避免 .NET 对多语言资源的误判。英文和简体中文版本资源均写入 `Murong` 产品身份。
- 保存工作流复制出的外部命令补齐 Windows/POSIX 路径引用，并限制外部工作流 ID 为安全字符，拒绝 Shell 元字符。桌面文案、远程节点和自动化说明改为平台中性；Windows 独立旧 Node 源码继续保留兼容，但不进入跨平台主应用发布物。
- 验证通过：desktop-agent `go test -count=1 ./...`、`go vet ./...`、`node --check frontend/dist/app.js`；desktop-bridge `go test -count=1 ./...`、`go vet ./...`；`actionlint` 和 Prettier。Android `PortableConversationCodecTest`、`LanWebDeviceSyncContractTest` 通过，`:app:installRelease` 用时 7 分 37 秒，成功覆盖安装到 `RMX5200`，版本 `1.30 (26071902)`，最后更新时间 `2026-07-19 23:25:41`。APK 大小 29,285,784 字节，SHA-256：`9578092EA8026033E451BDC486297D4E4E2786F674B53A29E87FB6DED870101A`。
- 本机真实生成两个 Windows 架构包：[windows-amd64.exe](../desktop-agent/dist/murong-desktop-agent-windows-amd64.exe) 159,626,240 字节，SHA-256 `079E278EB0D613F03EBD0A065C0DC7F17AF64F1AC21D7E6396731DE88B271E8B`；[windows-arm64.exe](../desktop-agent/dist/murong-desktop-agent-windows-arm64.exe) 149,545,472 字节，SHA-256 `A06FD3C2F04CB953F3D50159F74EE34BA4B83746F7D9B4D560746A5C075421E1`。ARM64 包已在 x64 本机完成 Codex 哈希、交叉编译、Wails 打包、图标和版本资源检查；ARM64 Codex 的运行级验证由工作流原生 ARM runner 执行。
- 最终 `gitnexus detect-changes --repo MurongAgent --scope all` 映射到 44 个文件、541 个符号和 66 条流程，整体为 critical；该结果包含工作区此前累计的 Android、终端、语音和整套尚未提交的桌面模块，不等同于 P5b.2c 专项风险。专项变更前影响分析中桌面平台/终端/Codex/备份/跨端来源大多为 LOW，`toolDefinitions` 为 MEDIUM；额外发现的 `validateSavedWorkflow` 为 HIGH，因此没有借跨平台改造重写其全局验证，只在 LOW 风险的外部命令入口增加安全 ID 与平台引用。

### 2026-07-19 P5b.3（项目自定义子代理模板）

- Windows/macOS/Linux 桌面端补齐 Android 已有的项目子代理模板语义。每个模板保存稳定 ID、名称、说明、目标匹配词、优先模型、优先推理强度、网页搜索、文件写入、代码编辑、Shell 和启用状态；配置 schema 升至 v9。项目即使继续“沿用全局”审批、白名单和工具开关，也能独立保存模板，不再因为继承全局设置而丢失项目模板。
- 保存入口和完整备份恢复都执行后端校验：每项目最多 24 个模板、每模板最多 24 个匹配词，并限制重复/非法 ID、空名称、字段长度和推理强度。配置加载会规范化空白、重复匹配词和大小写；切换项目、重启应用、完整 ZIP 备份和恢复均保留模板。普通备份继续排除 API Key、ChatGPT/Codex 登录和其他敏感凭据。
- `subagent_launch` 新增 `templateId`。显式 ID 优先；省略时按目标中命中的匹配词数量、模板匹配词数量和名称长度稳定选出项目模板。调用参数仍可覆盖模板的模型、推理和四类能力默认值，最终能力必须与父 Agent 当前启用工具及文件操作取交集，模板不能扩大父级权限，也不能递归派发子代理。
- 审批风险改为根据解析、模板默认值和父级交集后的“实际工具预算”判断，而不是只看原始 JSON 是否显式写了 `allowShell/allowCodeEdits/allowWriteAccess`。因此模板默认开启写入、代码编辑或 Shell 时会正确进入高风险审批；批量任务在统一审批中逐项显示所用模板，单项、依赖批次和后台结果都会保存 `templateId/templateTitle`。只读模式仍拒绝高风险任务，全自动模式仍不制造隐藏确认。
- 设置页“当前项目”下新增独立模板编辑器；聊天框“+ → 子代理”会列出启用的项目模板，选择后真实写入本轮模型上下文，要求调用 `subagent_launch` 并携带指定 `templateId`。保存后会立即刷新 Composer 目录。路径绑定模板不进入“Agent 通用设置”设备同步，避免把电脑项目路径覆盖到手机或另一台电脑；手机远程指挥电脑时由电脑当前项目的模板直接生效，完整备份仍包含模板。
- 按用户决定移除原来 6 项的手机式结构边界：桌面单批次安全上限提高到 64 项，所有满足依赖的任务仍立即并行，不新增手机端两槽位或额外并发选项。64 只作为单次模型载荷的防无限扩张边界，不是运行中的人工并发队列。
- GitNexus 变更前影响分析将 `materializeUserMessage` 标为 HIGH，因为它同时服务普通模型、内置 Codex/ChatGPT 和远程控制链；`resolvedToolConfig`、`toolDefinitions` 为 MEDIUM，其余模板存储、执行和备份入口为 LOW。实现保留三条模型入口和现有四档审批 API，只扩展共享上下文、项目配置和子代理策略，并新增配置继承、严格校验、自动匹配/显式覆盖、风险预算、Composer 和 UI 嵌入资源回归。
- 验证通过：`node --check frontend/dist/app.js`；desktop-agent `go test -count=1 ./...`、`go vet ./...`、生产态 `go build`、子代理/Composer/项目工具/备份专项测试；desktop-bridge `go test -count=1 ./...`、`go vet ./...`；Wails Release 构建、内置 Codex 0.144.6 校验和最终 Windows 图标/版本资源测试。内置浏览器拒绝加载临时 data URL，因此没有伪造截图结论；真实发布资源由 Wails 生产编译和嵌入资源测试验证。
- GitHub `build-desktop.yml` 继续以六个原生 runner 构建 `windows/amd64`、`windows/arm64`、`macOS/amd64`、`macOS/arm64`、`Linux/amd64`、`Linux/arm64`；本阶段平台无关的 Go/HTML/CSS/JS 改动会进入全部六个产物。本机正式生成 [windows-amd64.exe](../desktop-agent/dist/murong-desktop-agent-windows-amd64.exe)，159,659,008 字节，PE `0x8664`，SHA-256 `F20215AF9E41F0755501CD1CD56049206B787438BED2D0AFCC3531FC1DA7A1CB`；以及 [windows-arm64.exe](../desktop-agent/dist/murong-desktop-agent-windows-arm64.exe)，149,573,632 字节，PE `0xAA64`，SHA-256 `94B10D1E9DA919FF746A7ED1306371387B6C6F8FAA47FC9E14425A71268A5989`。ARM64 内置 Codex 的可执行级验证由工作流原生 ARM runner 完成，不在 x64 主机伪运行。
- 最终 `gitnexus detect-changes --repo MurongAgent --scope all` 映射到 44 个文件、541 个符号和 65 条流程，整体为 critical；这是包含此前 Android、终端、语音和整套未跟踪桌面模块的累计工作区结果，不等同于 P5b.3 的专项风险。
- 本阶段没有修改 Android 代码，因此没有重复执行耗时的 Android Release 构建或覆盖安装。

### 2026-07-20 六目标桌面发布完整性门禁

- 再次确认桌面产品不是“只做 Windows EXE”：Windows x64/ARM64 分别发布单文件 `.exe`，macOS Intel/Apple Silicon 分别发布 `.app.zip`，Linux x64/ARM64 分别发布 `.tar.gz`。首批覆盖六个现代 64 位目标；不把 x64 文件改名冒充 ARM64，也暂不宣称支持缺少 Wails 原生宿主或官方 Codex 运行时的 32 位 x86/ARMv7。
- GitHub 原生构建矩阵已逐项绑定 `windows-2025`、`windows-11-arm`、`macos-15-intel`、`macos-15`、`ubuntu-24.04`、`ubuntu-24.04-arm`。已通过 GitHub 官方 runner 清单确认这些标签可用；Windows/Linux ARM64 runner 当前属于 GitHub 公共预览，但它们是真实 ARM64 runner，不是 x64 交叉编译后跳过运行验证。
- OpenAI Codex `rust-v0.144.6` Release 中六个目标归档均已通过 GitHub API 核对名称、大小与 SHA-256，工作流固定的哈希全部一致。各矩阵项会在目标原生 runner 解压并实际运行对应 Codex 后再打包 Murong。
- 新增独立 `verify` 发布门禁：下载六个矩阵产物，严格核对六个预期文件及各自 `.sha256`，拒绝缺项和额外未声明包，逐份执行哈希校验并生成统一 `SHA256SUMS.txt`。GitHub Release 改为依赖该门禁，任何一个系统或架构失败时都不会发布只有五份的残缺版本。
- `actionlint v1.7.12` 无警告通过；本地静态契约确认矩阵恰好包含 Windows x64、Windows ARM64、macOS Intel、macOS Apple Silicon、Linux x64、Linux ARM64，并确认发布 Job 依赖完整性门禁。工作流当前仍在本地未提交工作区，进入 GitHub 默认分支后才能由 Actions 实际调度；Android 继续由独立 `build-apk.yml` 构建，两条工作流合计覆盖 Android 与六个桌面目标。
- 最终 `gitnexus detect-changes --repo MurongAgent --scope all` 映射到 44 个文件、541 个符号和 63 条流程，整体为 critical；这是包含此前 Android、终端、语音和整套未跟踪桌面模块的累计工作区结果。本次只改 GitHub 工作流、README 和唯一桌面进度文档，没有修改 Agent 运行时代码或 Android 安装包，因此没有重新执行耗时的 Android Release 构建。

### 2026-07-20 P5b.2d（跨平台远程节点身份与手机引导）

- 审计发现发布链已经支持 Windows、macOS、Linux 的 amd64/arm64，但 Android “电脑节点”和离线桌面任务界面仍硬编码“Windows Node”“运行 amd64.exe”“连接 Windows”，会直接误导 macOS/Linux 用户。本阶段不是只替换文案，而是让 Desktop Node 在工作区注册、桌面任务注册和每份任务快照中真实上报当前 `GOOS/GOARCH`。
- Android 工作区能力新增平台/架构元数据并贯通 `ComputerWorkspaceDescriptor`、HTTP 状态和 `LanWebServiceState`；桌面任务注册会把身份绑定到节点会话，后续快照若声称来自不同系统或架构会被拒绝。持久镜像保存来源身份，因此电脑离线后仍能显示“Windows · x64”“macOS · Apple Silicon”“Linux · ARM64”等真实标签。
- 手机设置页改为三系统共同引导：明确 Windows 使用 `.exe`、macOS 使用 `.app`、Linux 使用发布压缩包，不再要求所有用户运行 `murong-desktop-agent-amd64.exe`；终端说明改为当前系统实际发现的多个终端，不再把 PowerShell/CMD/WSL 当成所有平台的固定能力。前台服务通知、权限错误、任务断线/缓存提示和撤销配对提示也统一为平台中性的 Murong Desktop/电脑端。
- 兼容边界保持双向：旧电脑节点缺少新字段时，Android 仍按历史 Windows 节点解码；旧手机使用严格 JSON、会拒绝未知字段，因此新 Desktop Node 首次注册失败时会自动用省略平台字段的旧协议重试，并让后续快照同样省略新字段。用户无需同时升级两端，新手机+新电脑才启用精确平台显示。
- 影响分析中 `LanWebDesktopAgentStatusResponse` 为 HIGH（66 个间接影响、10 个直接引用、1 条流程），工作区/注册/快照模型大多为 MEDIUM，Go Desktop Node 注入点和 Compose 页面为 LOW。实现只追加带默认值的字段、保持原 endpoint/会话 ID/权限字段不变，并增加来源绑定和旧协议 HTTP 降级测试，没有改写配对、AES-GCM 凭据同步或 Agent 执行权。
- 验证通过：desktop-bridge `go test -count=1 ./...`、`go vet ./...`，包含真实 HTTP 新协议上报和严格旧手机二次注册；desktop-agent `go test -count=1 ./...`、`go vet ./...`、`node --check frontend/dist/app.js`；Android `:core:testDebugUnitTest` 与 `:app:testDebugUnitTest` 全量通过，覆盖旧字段默认、Linux ARM64 工作区、macOS Apple Silicon 离线镜像和注册/快照身份冲突拒绝。
- Android `:app:installRelease` 用时 8 分 55 秒，成功覆盖安装到 `RMX5200`（`192.168.2.4:5555`），版本 `1.30 (26071902)`，最后更新时间 `2026-07-20 01:04:33`。Release APK 为 29,292,569 字节，SHA-256：`DFED075C01EF5BB69F5F9ECDA806CFC8648FD5BED84B04BD58565E00C94AE13F`。
- 本机重新生成 [windows-amd64.exe](../desktop-agent/dist/murong-desktop-agent-windows-amd64.exe)，159,662,592 字节，PE `0x8664`，SHA-256 `15E6DD6462451B75829D188E93FFE73274128E973015F09F9FD41D1A1995DA59`；以及 [windows-arm64.exe](../desktop-agent/dist/murong-desktop-agent-windows-arm64.exe)，149,576,704 字节，PE `0xAA64`，SHA-256 `9C100DD916CF18415DA7BE103D453A97E5B2FFF7FF8FD6F7DB6F0337E8BEB520`。macOS/Linux 由六目标 GitHub 原生矩阵构建；任一目标失败时完整性门禁不会发布残缺 Release。
- 最终 `gitnexus detect-changes --repo MurongAgent --scope all` 映射到 44 个文件、541 个符号和 63 条流程，整体为 critical；这是包含此前 Android、终端、语音及整套未跟踪桌面模块的累计工作区结果，不等同于本阶段专项风险。未创建提交。

### 2026-07-20 P5b.4（手机接管电脑会话执行权）

- 跨端控制从“手机遥控电脑继续跑”扩展为显式单写者接管。每个桌面会话持久化 `desktop/android` 执行所有者、随机 256 位一次性接管令牌、原消息数量与前缀摘要；手机接管成功后，电脑端发送、模型/推理切换、回退、重命名、压缩、删除和目标修改都会被后端拒绝，Composer 同时显示“手机正在继续此任务”。分叉仍可用，因为它会创建一个独立的电脑会话，不会与手机写同一份历史。
- 开始接管要求桌面会话、审批和后台子代理全部空闲，并在同一应用锁内先冻结会话再导出 Android 便携包。图片二进制、绝对路径、工作区上下文、外部变更和 Codex thread ID 不跨设备；若消息包含图片，只保留附件文件名提示，避免用户误以为图片本体已经同步。
- Android 把接管记录和一次性令牌保存到应用私有、版本化且原子替换的状态文件；便携会话必须先同步落盘，才向 UI 宣告接管成功。手机可打开本地会话继续使用完整 Android Agent，也可把会话归还电脑或放弃接管；手机进程重启后映射仍在。电脑端强制收回会使旧令牌立即失效，手机保留未合并的本地副本并明确警告，不会静默丢弃。
- 归还时电脑验证令牌、原桌面消息数量和 SHA-256 前缀摘要，并验证手机没有修改接管前的任何消息；只把手机新增消息分配新的桌面 ID 后追加。标题、目标、用量和压缩边界做受限合并，设备关联的 Codex 续接 ID 清空。前缀被改写、桌面原历史异常变化、令牌错误或包超出 1.5 MiB 都拒绝归还，电脑仍保持冻结以便用户选择重试或强制收回。
- 接管能力没有沿用普通 HTTP 明文字段。新配对复用 P5a.2 的每设备 32 字节同步密钥，手机→电脑和电脑→手机使用独立 AES-256-GCM 方向，AAD 绑定协议版本、command request ID、时间和方向；令牌与便携会话正文只出现在两端内存/私有存储，SSE 命令和 HTTP 结果只传密文。旧配对没有同步密钥时明确要求撤销后重新配对，不允许降级到明文；任务快照和离线镜像从不包含令牌或便携正文。
- Android“电脑任务”界面按执行所有者显示“接管到手机继续”“在手机打开”“归还电脑”“放弃接管”；归还/放弃均经过串行协调和错误恢复。电脑侧新增醒目的接管横幅和双击确认的“强制收回”，运行状态、输入框、模型与推理控件会随执行权即时锁定。完整备份、便携恢复和跨平台导入会清除设备专属接管令牌；存在手机接管时禁止覆盖恢复整个桌面状态。
- 兼容旧手机时，Desktop Node 的旧协议降级会同时剥离新执行权摘要字段，避免严格 JSON 解码失败；旧端无法发起接管。新协议继续携带真实 `GOOS/GOARCH`，所以 Windows、macOS、Linux 的 amd64/arm64 桌面构建共用同一套接管语义，没有把能力绑死在 EXE 或 x86。
- 回归通过：desktop-agent 与 desktop-bridge 均执行 `go test ./...` 和 `go vet ./...`；Android `:core:testDebugUnitTest` 与 `:app:testDebugUnitTest` 全量通过；`node --check frontend/dist/app.js` 和 `git diff --check` 通过。覆盖内容包括冻结/重启持久化、错误令牌、前缀篡改、增量合并、放弃/强制收回、运行态冲突、传输大小、快照不泄漏、AES-GCM 方向/AAD、防明文协议、Android 映射重启和失效恢复。
- Android `:app:installRelease` 用时 9 分 15 秒，成功覆盖安装到 `RMX5200`（`192.168.2.4:5555`），版本 `1.30 (26071902)`，最后更新时间 `2026-07-20 02:12:10`。Release APK 为 29,287,001 字节，SHA-256：`D0F70D59306E29E598E2BCC13B54A663BA82F74857C3DFA52485E42D3B2BDAB2`。
- 本机正式生成 [windows-amd64.exe](../desktop-agent/dist/murong-desktop-agent-windows-amd64.exe)，159,713,792 字节，SHA-256 `02C31427975912438026BE925D56322FBDD341BA55480631F1C9D5D6B91675B9`；以及 [windows-arm64.exe](../desktop-agent/dist/murong-desktop-agent-windows-arm64.exe)，149,621,248 字节，SHA-256 `35C6E402C7A46293ECD04651CB574C436B0D14769638F694DE9B0755466A9AD4`。macOS Intel/Apple Silicon 与 Linux x64/ARM64 继续由 GitHub 六目标原生矩阵构建，六包完整性门禁保持不变，任一目标失败都不会发布残缺 Release。
- 最终 `gitnexus detect-changes --repo MurongAgent --scope all` 映射到 44 个文件、541 个符号和 63 条流程，整体为 critical；这是此前 Android、终端、语音及整套尚未提交桌面模块的累计工作区风险，不等同于 P5b.4 专项风险。变更前专项分析中 Android 通用 `SessionSummary` 为 HIGH、模型用量/Codex 状态入口分别为 CRITICAL/HIGH，因此本阶段没有改这些共享类型和入口，而是新增独立接管记录、在会话通用写入口加执行权门禁，并以可验证前缀做受限增量合并。未创建提交。

### 2026-07-20 P5b.5（计划与子代理执行 Profile）

- 补齐 Android 已有但 Desktop 缺失的两组高级执行配置：计划模式可独立覆盖模型与推理强度，子代理可设置全局默认模型与推理强度。两者都可单独关闭，模型或推理留空时继续继承当前 Provider Profile，不复制 API Key、Base URL 或审批模式。
- 计划 Profile 已接入真实父 Agent 运行入口，只有会话处于计划模式时才生效，并同时覆盖 OpenAI/Claude 类 API 与内置 Codex 路径。子代理选择优先级固定为“单次调用显式参数 > 当前项目子代理模板 > 全局子代理默认 Profile > explore/research/review/security_review 预设推理”，最终仍经过现有工具预算交集和四档审批。
- Desktop 配置 schema 升至 v10，完整 ZIP 备份、恢复前校验与事务恢复都包含这六个字段；设备同步协议升至 v4，Android 与 Desktop 均兼容读取 v1–v4，并通过原有 AES-256-GCM 配对信道显式双向同步。非法模型长度或不支持的推理值在导入前拒绝，不触碰现有配置。
- 设置页新增两张执行 Profile 卡片与开关、模型、推理选择器；计划模式生效时聊天顶部模型标识会明确显示“计划”。Agent 通用设置同步说明同步更新，避免用户误以为这类配置只保存在当前电脑。
- 再次审计桌面发行边界：GitHub `build-desktop.yml` 使用六个原生 runner 构建 Windows x64/ARM64、macOS Intel/Apple Silicon、Linux x64/ARM64，并由独立 verify Job 要求六个包及其 SHA-256 全部存在后才允许发布。GitHub 官方 runner 清单和 Wails 2.13 官方支持矩阵均确认这六个目标有效；Windows/Linux ARM64 runner 当前仍属 GitHub 公共预览。Android 由独立 `build-apk.yml` 构建，两条工作流合计覆盖 Android 与全部桌面目标。
- 回归通过：`node --check frontend/dist/app.js`；desktop-agent `go test -count=1 ./...`、`go vet ./...`；desktop-bridge `go test -count=1 ./...`、`go vet ./...`；Android 全模块 `gradlew test`。测试覆盖配置持久化、计划模式继承/覆盖、子代理优先级、完整备份、设备同步 v4 严格往返、Desktop 设置 UI 与同步说明。
- Android `:app:installRelease` 用时 7 分 17 秒，成功覆盖安装到 `RMX5200`（`192.168.2.4:5555`），版本 `1.30 (26071902)`，最后更新时间 `2026-07-20 02:42:32`。Release APK 为 29,288,815 字节，SHA-256：`8F8B6594F412CFD2396A93E4E5082315B189D97355E735C387404D3A1B63DF9F`。
- 本机正式生成 [windows-amd64.exe](../desktop-agent/dist/murong-desktop-agent-windows-amd64.exe)，159,736,832 字节，PE `0x8664`，SHA-256 `124062B71CE6544CABC813B418F5593EAEB6F4A94046B509B14C76586CD26B1F`；以及 [windows-arm64.exe](../desktop-agent/dist/murong-desktop-agent-windows-arm64.exe)，149,643,264 字节，PE `0xAA64`，SHA-256 `5ECFFE9840A204A02F89BC4C5024116275330C68A316FCCA2DE85FCCFE80CCA2`。ARM64 内置 Codex 的执行级验证由 GitHub 原生 ARM64 runner 完成，不在 x64 主机伪运行。
- `actionlint v1.7.12` 对六目标工作流无警告通过；`git diff --check` 通过。最终 `gitnexus detect-changes --repo MurongAgent --scope all` 仍为累计工作区 44 个文件、541 个符号、63 条流程和 critical 风险；本阶段变更前专项分析中桌面配置/设备同步为 MEDIUM，运行循环和子代理策略为 LOW，实现没有改写审批主链或放宽工具权限。未创建提交。

### 2026-07-20 P5b.6（任务项目绑定与历史检索）

- 会话新增独立项目绑定。新任务继承当前项目、分叉保留来源项目；切换任务会恢复它自己的项目配置和监听器，绑定目录丢失时明确显示错误并提供重新选择入口，不再静默借用另一个任务的全局目录。首次运行旧版无绑定任务时会把当前有效项目迁移为显式绑定。
- Agent 发送和执行入口都从会话解析工作目录，避免 UI 检查正确但后台仍使用可变全局路径。用户把任务改绑到另一个项目时会作废旧 Codex thread 关联，防止旧工作目录和工具上下文跨项目残留；完整桌面备份保留绝对绑定，跨端便携会话继续排除本机项目路径和 Codex 关联。
- 新增内部只读 `session_history_search`：支持关键词、当前项目过滤、稳定 `session_id#message_id` 引用、单段或多段有界摘录；默认排除当前任务，最多返回 20 个匹配、10 个引用和每段 12 条消息，且不输出图片缓存路径、Composer 上下文、API Key、登录令牌或设备接管令牌。
- 普通 API Provider 直接使用同一工具定义；内置 Codex 按校验过 SHA-256 的官方 Codex 0.144.6 app-server schema，在 `thread/start.dynamicTools` 注册函数并处理 `item/tool/call`/`DynamicToolCallResponse`。旧 Codex thread 只迁移一次到带动态工具的新 thread，后续正常 resume，不会每轮重建。
- 跨平台发布边界再次验证：GitHub Actions 矩阵是 Windows x64/ARM64、macOS Intel/Apple Silicon、Linux x64/ARM64 六个原生目标，分别输出 `.exe`、`.app.zip`、`.tar.gz`，六包和逐包 SHA-256 缺一不可才允许发布。当前 Windows 主机还以 `CGO_ENABLED=0` 对 darwin/linux 四个目标完成测试二进制编译；GUI、WebView 与内置 Codex 的真正执行验证仍由对应原生 runner 完成，不能由 Windows 交叉编译冒充。
- 回归通过：desktop-agent `go test ./...`、`go vet ./...`，desktop-bridge `go test ./...`、`go vet ./...`，`node --check frontend/dist/app.js`、`git diff --check`；覆盖任务绑定/旧任务迁移/缺失项目、改绑作废 Codex、备份与便携导出、检索过滤/边界/稳定引用、Codex 动态工具协议和未知工具失败响应。
- 本机正式生成 [windows-amd64.exe](../desktop-agent/dist/murong-desktop-agent-windows-amd64.exe)，159,801,856 字节，PE `0x8664`，SHA-256 `6AE8E7D32ED2CE8B917444DDF93B1CA73C97C9B0F098C474105CDEFD9ED9FFF0`；以及 [windows-arm64.exe](../desktop-agent/dist/murong-desktop-agent-windows-arm64.exe)，149,699,584 字节，PE `0xAA64`，SHA-256 `2BB38A3B46E3C7D00B4ED85FA7EA0D65184FD24211D789CC10EE492F81A81F73`。本机只执行 x64 内置 Codex 冒烟；ARM64 运行验证由 GitHub 原生 ARM64 runner 完成。
- 最终 `gitnexus detect-changes --repo MurongAgent --scope all` 映射到累计工作区 44 个文件、541 个符号和 67 条流程，整体为 critical；它包含此前 Android、终端、语音和整套未提交桌面模块，不等同于 P5b.6 的专项风险。本阶段没有修改 Android 源码，因此没有重复构建或安装 Android Release；未创建提交，工作流必须进入 GitHub 默认分支后才会真正调度六个原生构建。

### 2026-07-20 P5b.7（桌面结构化提问与手机回答）

- 补齐 Android 已有而 Desktop 缺失的 `ask_user` 主 Agent 工具。普通 API Provider 与内置 Codex 都可一次提交 1–4 个结构化问题，每题必须包含 2–4 个不重复选项，可带标题、说明和多选标记；后端使用严格 JSON 解码，拒绝未知字段、尾随对象、空值、重复项、超长内容和不完整答案，不依赖模型或前端自觉遵守 schema。
- `ask_user` 是“继续任务所需的用户决策”，不属于文件/终端工具审批，因此四档审批和 YOLO 语义保持不变；计划模式仍可提问。后台子代理明确移除该工具，避免多个隔离任务同时争抢用户。任务取消会清除待回答状态；用户可选预设答案、输入一个自定义答案，或明确跳过并让 Agent 按默认假设继续。
- 桌面聊天区新增现代问答卡，支持单选、多选、选项说明、自定义输入和完整性校验。切到其他任务不会错误显示当前问题，切回来会从后端恢复仍在等待的请求；无效提交不会解除等待，问题处理后 UI 与运行状态同步收起。Codex 动态工具协议升级到 v2，旧 thread 只进行一次带新工具的安全迁移，后续继续正常 resume。
- 桌面任务同步协议升级到 v2：快照只传结构化问题，不传原始 tool arguments、项目路径或凭据；手机“电脑任务”页面可以查看并回答单选/多选/自定义问题，也可以选择按默认假设继续。命令仍受“允许手机控制桌面任务”开关约束；断线持久镜像会剥离运行中问题，避免离线点击已经失效的请求。存在待回答问题时禁止把同一任务接管到手机本地 Agent，先回答后才能切换执行所有者。
- 新 Desktop 首次登记携带协议版本 2。严格旧手机拒绝未知字段时，电脑自动用旧登记格式重试，并在后续快照中同时剥离 `pendingQuestion`、`pendingAsk`、平台和接管字段；旧端继续获得原有会话同步，不会因这次升级断连。新手机继续接受缺少版本字段的旧电脑，并按协议 v1 处理。
- 回归覆盖严格参数、阻塞/恢复/取消、自定义答案边界、计划模式、子代理隔离、Codex app-server 动态工具格式、桌面任务快照脱敏、手机远程回答、旧协议降级和离线镜像清理。验证通过：`node --check frontend/dist/app.js`；desktop-agent 与 desktop-bridge 的 `go test ./...`、`go vet ./...`；Android `:app:testDebugUnitTest` 全量；Android `:app:installRelease`；`git diff --check`；`actionlint v1.7.12`。
- Android `:app:installRelease` 用时 7 分 22 秒，成功安装到 `RMX5200 - 16`，版本 `1.30 (26071902)`，最后更新时间 `2026-07-20 04:13:16`。Release APK 为 29,305,094 字节，SHA-256：`8D39CBA4D20B2DB171441631A9A45A2A4BBAAD37B6A45EE0EF8DF3918541EF22`。
- 本机正式生成 [windows-amd64.exe](../desktop-agent/dist/murong-desktop-agent-windows-amd64.exe)，159,853,056 字节，SHA-256 `9A1A02F3D4073ECF202FDEA4197EC2561EF8E88E85102598E10E4388BDEF3EAB`；以及 [windows-arm64.exe](../desktop-agent/dist/murong-desktop-agent-windows-arm64.exe)，149,747,200 字节，SHA-256 `6F314D24D3C401812C655708846D7870BE39B47FE21180B976AF091223DD9116`。当前 Windows 主机以 `CGO_ENABLED=0` 再次完成 macOS Intel/Apple Silicon、Linux x64/ARM64 四个测试二进制交叉编译；GUI、WebView 与内置 Codex 的运行级验证仍交给六目标原生 GitHub runner。
- GitHub `build-desktop.yml` 继续使用六个原生矩阵目标，并由独立 verify Job 强制要求 Windows x64/ARM64、macOS Intel/Apple Silicon、Linux x64/ARM64 六包及逐包 SHA-256 全部存在且集合精确匹配；`actionlint` 无警告通过，任一目标失败都不会发布残缺 Release。工作流进入 GitHub 默认分支后才会真正调度远端 runner。
- 最终 `gitnexus detect-changes --repo MurongAgent --scope all` 映射到累计工作区 44 个文件、541 个符号和 63 条流程，整体为 critical；这是此前 Android、终端、语音和整套未提交桌面模块的累计风险，不等同于 P5b.7 专项风险。变更前专项分析中桌面工具定义、执行分派、远程桥为 MEDIUM，Android Settings ViewModel 为 MEDIUM，手机任务弹窗为 LOW，因此保持既有入口、只追加版本化字段并以新旧协议测试约束兼容；未创建提交。

### 2026-07-20 P5b.8（可执行 Skill 与 Codex 记忆/Skill 对等）

- 对照 Android 已注册工具与 Desktop 实际模型工具后确认：桌面 UI 虽能创建、读取和选择 Skill，但普通 API 模型没有 `run_skill`，内置 Codex 更只能调用历史检索和结构化提问，无法查询 Murong 记忆或 Skill。这个缺口会让同一套知识库在不同 Provider 下表现成两套产品，因此本阶段补的是实际执行链，而不是再加一个设置入口。
- Desktop 新增严格 `run_skill`：按启用状态和 `any/project/global` 来源匹配，精确 ID 优先，其次精确标题和唯一关键词；匹配不唯一时返回候选而不猜，未知字段、尾随 JSON、非法来源/执行方式、过长查询与任务会直接拒绝。内联 Skill 返回完整指令与本次 task 供当前轮继续执行；超过 18,000 字符的 Skill 不静默截断，而是要求拆分。
- `SUBAGENT` Skill 真实复用现有 `subagent_launch` 执行器、模型覆盖、后台队列、四档审批和父项目权限交集。Android 风格的 `file/shell/web/github/memory/skill` 工具名会展开为桌面规范工具；`ask_user`、`run_skill` 和所有子代理派发工具被剥离，避免子代理递归执行 Skill 或争抢交互。声明工具预算后不会因归一化为空而退化为无限预算；YOLO 仍不弹审批，其他模式只出现一次子代理授权。
- 内置 Codex 动态工具协议升至 v3。新 thread 在 app-server 中注册 `memory_list/search/read`、`read_skill`、`run_skill`、记忆增删和全局/项目知识创建工具，并把现有 Responses 风格 schema 转为 Codex `inputSchema`；旧 v2 thread 只迁移一次。计划模式只注册历史、提问、记忆只读和 `read_skill`，服务端分派还会按同一白名单二次校验，不能通过伪造动态调用绕过计划模式。
- Composer 的 Skill 项现在明确显示“内联执行/子代理执行”、首选模型和允许工具数量。普通 API 与 Codex 都走同一份 Skill 存储、匹配和审批语义；Codex 登录目前仍不能给子代理提供 API Key，因此 Codex 下的内联 Skill 可直接执行，而要求旧 API 子代理执行器的 Skill 会明确报告模型连接不可用，不伪造执行成功。
- 回归覆盖 42 个桌面内置工具、严格参数、唯一/歧义匹配、超长拒绝、工具别名展开、递归能力剥离、Composer 执行信息、Codex 普通/计划动态工具集合、Codex 读取与内联执行 Skill。验证通过：desktop-agent `go test -count=1 ./...`、`go vet ./...`、生产态 `go build`；desktop-bridge `go test -count=1 ./...`、`go vet ./...`；`node --check frontend/dist/app.js`、`git diff --check`、`actionlint v1.7.12`。
- 跨平台发布边界保持完整：本机用 `CGO_ENABLED=0` 成功交叉编译 darwin/linux 的 amd64/arm64 四份测试二进制；GitHub `build-desktop.yml` 继续在六个原生 runner 构建 Windows x64/ARM64、macOS Intel/Apple Silicon、Linux x64/ARM64，并以六包与逐包 SHA-256 的精确集合门禁阻止残缺 Release。没有把非 Windows 包命名为 EXE，也没有用 Windows 交叉编译冒充 GUI/WebView 运行验证。
- 本机正式生成 [windows-amd64.exe](../desktop-agent/dist/murong-desktop-agent-windows-amd64.exe)，159,891,456 字节，PE `0x8664`，SHA-256 `22C34D06C72B795ACAA595A72B156BC51F28BF455DC0F4C16450CFC3B6217A4A`；以及 [windows-arm64.exe](../desktop-agent/dist/murong-desktop-agent-windows-arm64.exe)，149,779,968 字节，PE `0xAA64`，SHA-256 `8308A7D3E612DD53C8254E01ECB1E271446A3759D440373E4CA64DCD751CF1B9`。ARM64 内置 Codex 的执行级验证由 GitHub 原生 ARM runner 完成。
- 变更前 GitNexus 对 `knowledgeToolDefinitions`、`executeKnowledgeTool`、`toolDefinitions` 和 `executeCodexDynamicToolRequest` 的专项影响均为 LOW；最终全工作区 `detect-changes` 仍报告累计 44 个文件、541 个符号、63 条流程和 critical 风险，它主要反映此前 Android、终端、语音及整套未提交工作区，不等同于本阶段专项风险。本阶段没有修改 Android 源码，因此没有重复构建或安装 Android Release；未创建提交。

### 2026-07-20 P5b.9（规范计划与真实工具证据签收）

- 审计确认此前 Desktop “计划模式”只把模型回复保存成 `Kind: plan` 文本，点击后再发送一句“执行这个计划”，没有 Android 已有的持久步骤状态、执行/继续、逐步签收和完成阻断。本阶段新增会话内 `DesktopWorkflowPlan`：保留目标、摘要、最多 8 个规范步骤、当前索引、`ready/executing/blocked/completed` 状态、原始计划、来源消息、开始时间和每步签收记录；普通 API 与内置 Codex 的计划输出都进入同一数据模型。
- 主 Agent 新增严格 `complete_step`。每条 evidence 必须给出工具名、命令、路径、消息引用或会话 ID 中至少一个真实锚点，并只能匹配计划开始后持久化的工具消息 ID、参数和成功/失败状态；默认只接受成功收据，显式 `mustSucceed: false` 可用于“预期失败已验证”等步骤。同一收据只能签收一次，当前步骤不匹配、未知字段、尾随 JSON、伪造消息、过期收据或无锚点证据都会拒绝推进；未签收完时 Agent 本轮结束只会把计划标成待继续，不能宣称整体完成。
- 普通 Provider 和 Codex app-server 都会获得 `complete_step`，Codex 动态工具协议升至 v4；生成计划时两条路径都明确移除该执行工具，后台子代理也不能替父任务签收。执行提示会列出已签收、当前和待执行步骤，每次工具结果向模型返回下一步骤与总进度。
- 聊天区新增现代规范计划卡：显示目标、摘要、状态、进度条、步骤、签收结果/证据数量/工具名、下一步和可折叠原始计划；支持“按计划执行”“继续执行”“清除计划”，运行中或手机持有执行权时锁定。计划和签收随桌面会话、完整备份及恢复前快照持久化，严格恢复会核对来源计划消息、连续步骤、真实工具消息、时间边界、状态和工具名。
- 修复分叉计划引用旧消息 ID 的问题：分叉会显式重映射计划来源和工具收据 ID，缺失的签收由历史协调器撤销；回退到较早消息同样只保留仍有真实收据支撑的连续签收。跨端/普通便携会话主动剥离规范计划、tool call ID、原始参数和状态；手机协议尚未具备相同计划 schema 时，未完成计划禁止接管，用户需先完成或清除，避免两端显示不同执行事实。
- 新增专项回归覆盖大小写摘要解析、步骤上限/回退、重启与深拷贝、无签收不得完成、成功/预期失败证据、错误步骤、收据复用、严格 JSON、普通/Codex 工具暴露、Codex 计划与命令收据、计划卡静态契约、完整备份、便携脱敏、分叉 ID 映射、回退撤销、接管阻断和伪造签收拒绝。验证通过：desktop-agent `go test -count=1 ./...`、`go vet ./...`、`node --check frontend/dist/app.js`；desktop-bridge `go test -count=1 ./...`、`go vet ./...`；`actionlint`；darwin/linux amd64/arm64 四目标测试二进制编译。
- GitHub 六目标原生矩阵继续构建 Windows x64/ARM64、macOS Intel/Apple Silicon、Linux x64/ARM64，六包和逐包 SHA-256 必须精确齐全才允许发布；发布 Job 现在显式设置 `GH_REPO`，即使不 checkout 也不会因当前目录缺少 Git 仓库而无法执行 `gh release create`。工作流仍需进入默认分支后才能真正调度远端原生 Runner。
- 本机正式生成 [windows-amd64.exe](../desktop-agent/dist/murong-desktop-agent-windows-amd64.exe)，159,975,424 字节，SHA-256 `EB5C062E7CF5E2ED941C3D5C0BFC7DE745687BE56B6E89AEDF830426AB8B833F`；以及 [windows-arm64.exe](../desktop-agent/dist/murong-desktop-agent-windows-arm64.exe)，149,857,792 字节，SHA-256 `216CE1642DE091197088AF2C868CB2A0ED1E1CF63677FC75262F03B0F6161233`。x64 已实际运行内置 Codex 0.144.6 冒烟测试；ARM64 执行级验证继续由 GitHub 原生 ARM Runner 完成。本阶段没有修改 Android 源码，因此没有重复构建或安装 Android Release；未创建提交。
- 变更前专项影响中 `forkSession`、`persistCodexCompletedItem` 与备份严格校验均为 LOW；`cloneSession` 和消息持久化属于累计 HIGH/CRITICAL 扩散点，因此计划采用独立深拷贝、原子会话保存和完整边界测试，没有重构这些入口。最终 `gitnexus detect-changes --repo MurongAgent --scope all` 仍报告累计工作区 44 个文件、541 个符号、63 条流程和 critical 风险，主要来自此前 Android、终端、语音及整套未提交桌面模块，不等同于 P5b.9 专项风险。

### 2026-07-20 P5b.10（手机监控桌面规范计划）

- 补齐 P5b.9 后继续审计跨端任务快照，确认手机“电脑任务”仍只能看到粗粒度运行状态，无法知道电脑 Agent 正在执行哪一步、哪些步骤已有证据。本阶段在 Desktop Bridge 增加只读 `workflowPlan` 投影：只同步计划 ID、摘要、最多 8 个步骤、当前索引、状态、下一步、签收结果、证据数量、工具名和时间；明确不传目标原文、原始计划、tool call ID、工具参数、命令输出、项目路径或签收消息 ID。
- 桌面任务协议升至 v3。新电脑注册时按 v3 → v2 → v1 顺序协商：v3 手机获得规范计划；v2 手机继续保留系统/架构、结构化提问和接管能力，但快照及命令结果都会剥离 v3 计划字段；严格 v1 手机继续接收基础任务，并同时剥离身份、提问、接管和计划字段。不会因新字段让已安装旧手机完全断开，也不会把能用 v2 的手机无谓降成 v1。
- Android 为远程计划建立独立序列化模型和严格校验：验证状态集合、1–8 步、连续签收、进度/签收数量、证据数量、工具名、创建/开始/签收/更新时间关系和 Unicode 长度；v1/v2 注册明确拒绝携带 v3 计划。计划随现有应用私有离线镜像安全保存，电脑断线后保留最后一次事实进度，但运行中审批和提问仍按原规则清除。
- 手机“电脑任务”新增只读规范计划卡，显示摘要、状态、进度条、每个已完成/当前/待执行步骤、签收结果、证据数量、工具名和下一步；断线时明确标注为最后一次同步。手机不提供伪造 `complete_step` 的按钮，真实签收仍只能由拥有电脑工作区工具收据的 Desktop Agent 产生。
- 回归覆盖桌面投影脱敏、v3 原生快照、v2 协商保留旧能力并剥离计划、v1 严格降级、命令结果降级、Android v3 严格状态/伪造进度拒绝、v2 越权字段拒绝、离线镜像保留事实计划和 Compose 卡片编译。desktop-agent、desktop-bridge 的 `go test -count=1 ./...` 与 `go vet ./...`、Android 全模块 `gradlew test`、`node --check`、`actionlint` 和 darwin/linux amd64/arm64 四目标测试二进制编译全部通过。
- Android `:app:installRelease` 用时 9 分 53 秒，成功覆盖安装到 `RMX5200`（`192.168.2.4:5555`），版本 `1.30 (26071902)`，最后更新时间 `2026-07-20 10:20:59`。Release APK 为 29,317,348 字节，SHA-256 `C88AC1FEB397716ECE66A3F13ED3CA4C3DCC97F3821158848F578936C0535E4E`。
- 本机正式生成 [windows-amd64.exe](../desktop-agent/dist/murong-desktop-agent-windows-amd64.exe)，159,981,056 字节，SHA-256 `28523F602E6519ADA135FCF8BCA4726B3959513725D21177C18107277EDEAF10`；以及 [windows-arm64.exe](../desktop-agent/dist/murong-desktop-agent-windows-arm64.exe)，149,861,888 字节，SHA-256 `47A23C85961F45559251502BCE91D1F4674C60BDF26344E3251225BF0F45F3E9`。x64 内置 Codex 冒烟、两架构图标/版本资源校验通过；ARM64 运行验证仍由 GitHub 原生 ARM Runner 完成。未创建提交。
- 变更前专项影响中 Desktop Bridge 快照结构、投影、注册协商和降级入口均为 LOW；Android `LanWebDesktopAgentTaskDetail` 为 MEDIUM（3 个直接引用，主要是镜像与任务 UI），严格校验、镜像保存和 Compose 对话框为 LOW，因此使用新增可空字段和版本协商扩展，没有改写旧字段语义。最终全工作区 `gitnexus detect-changes` 仍为累计 44 个文件、541 个符号、63 条流程和 critical 风险，主要反映此前未提交 Android/终端/语音与整套桌面模块，不等同于 P5b.10 专项风险。

### 2026-07-20 P5b.11（跨任务项目审计归档）

- 审计确认 P4b.2c 的原生目录监听只保留当前项目进程内最近 300 条变化，并在下一轮用户消息消费待处理摘要；切换项目、重启应用或删除会话后，没有一个可按项目追溯“哪个 Agent/会话/外部程序在什么时候改过什么”的长期事实层。本阶段新增独立 `desktop-project-audit-v1.json`，不把审计塞进高风险会话主存储，也不改 Android `ConversationStore`。
- 归档接入三类真实来源：原生文件监听在完成路径校验和 Agent 重复事件抑制后记录外部新增/修改/删除/重命名；普通 Provider 与隔离子代理通过统一 `executeTool` 出口记录文件写入、目录创建、删除、权限、代码编辑及终端/Git 成败；内置 Codex 只在 `commandExecution` / `fileChange` 工具收据已经成功写入会话后记录。Agent/Codex 工具 call 使用稳定摘要 ID 去重，同一原生路径 1.5 秒内的重复通知合并次数，避免格式化器或构建器刷爆归档。
- 隐私边界不是只靠 UI 隐藏：持久模型根本没有命令正文、工具参数、文件内容、模型思考、输出或凭据字段。工具参数只在内存中提取项目内相对路径和 Git/终端分类后丢弃；最多保存 32 条相对路径、500 字摘要、10,000 个条目和 32 MiB 文件，严格校验 ID、平台、绝对项目根、相对路径、枚举、时间、次数、控制字符、分页游标和搜索边界。写入采用 320 ms 合并窗口加原子替换，正常关闭强制 flush，损坏或跨系统路径归档不会静默加载。
- 原“项目外部变化与 Diff”弹窗升级为实时变化/跨任务审计双视图。审计页支持路径/工具/会话搜索、Agent/Codex/外部来源筛选、稳定游标分页、条目来源/结果/会话/次数展示、相对路径跳转当前 Git/文本 Diff，以及 JSON/Markdown 显式导出；清空归档需要二次点击且只影响当前项目，不会修改项目文件或清除待注入下一轮的实时变化。终端类无路径条目只显示脱敏事实说明。
- 完整备份新增可选 `data/project_audit/archive.json` / `PROJECT_AUDIT` 条目，继续沿用清单路径、大小和 SHA-256 门禁。新备份把审计纳入恢复前快照、同系统校验、正式恢复与失败回滚；旧 v1 备份没有该可选条目时保留目标机现有审计，不因本次扩展失去兼容性。普通备份仍排除 API Key、Codex/GitHub 登录、MCP 凭据、终端日志和设备运行态。
- 专项回归覆盖持久化/重载、外部事件合并、项目隔离、搜索/来源筛选、稳定分页、工具 call 幂等、只读工具排除、命令/文件内容/Token 明文排除、真实普通工具执行、真实 Codex 收据、Agent 写入监听抑制、危险路径/枚举/游标拒绝、新备份恢复和旧备份保留现有归档；桌面 UI 静态契约也覆盖新增标签、筛选、导出、清理和运行时事件。
- 发布级验证通过：Desktop Agent `go test -count=1 ./...`、`go vet ./...`、`node --check frontend/dist/app.js`；Desktop Bridge `go test -count=1 ./...`、`go vet ./...`；固定 `actionlint v1.7.12`；`git diff --check`；darwin/linux 的 amd64/arm64 四目标 `CGO_ENABLED=0 go test -c`。正式 x64 单 EXE 还在 Windows 真实启动并完成主界面、实时变化页和审计页 1440×900 渲染验收，标签、搜索、筛选、双格式导出、清理和左右双栏均无溢出或遮挡。
- 本机正式生成 [windows-amd64.exe](../desktop-agent/dist/murong-desktop-agent-windows-amd64.exe)，160,077,824 字节，SHA-256 `70A7004FEB07C95244D3EA0FA92E623F6A6E5C7AD8843958D17921D3422DACC3`；以及 [windows-arm64.exe](../desktop-agent/dist/murong-desktop-agent-windows-arm64.exe)，149,947,392 字节，SHA-256 `B7AFB5EE21215D19F57622A9983498B7387A75034F8C920917797A80B468C538`。两包均通过图标/版本资源校验；x64 内置 Codex 0.144.6 冒烟通过，ARM64 执行级验证继续由 GitHub 原生 ARM Runner 负责。
- 变更前 GitNexus 将统一普通工具入口与备份构造器评为 LOW，将原生文件监听回调和 Codex 收据持久化评为 HIGH，因为它们贯穿项目切换/恢复和 Codex 主执行链；因此保持原有签名/执行顺序，只在真实结果之后旁路记录，并增加监听、Codex、项目隔离和备份事务测试。最终全工作区 `detect-changes` 仍报告累计 44 个文件、541 个符号、63 条流程和 critical 风险，主要来自此前未提交 Android/终端/语音与整套桌面模块，不等同于 P5b.11 专项风险。本阶段没有修改 Android 源码，因此未重复构建或安装 APK；没有创建提交，未跟踪的六目标 GitHub 工作流仍需进入默认分支后才会实际运行。

### 2026-07-20 P5b.12（Android/Desktop 跨系统完整备份互通）

- `murong-backup` 外层格式升至 v2，并继续接受旧 v1 同系统包。每个 v2 包除原生精确恢复数据外，必须包含 `state/portable-state.json`；其中的会话直接复用现有 `murong-portable-session` v1，不另造第三套会话协议。清单继续逐项限制路径、大小和 SHA-256，跨系统状态额外校验来源平台、数量、ID、时间和嵌套文档语义。
- 同系统恢复保持既有精确语义：Android→Android、Windows→Windows、macOS→macOS、Linux→Linux 可恢复各自原生会话、媒体、项目审计和备份设置。跨系统恢复改为安全合并：导入可移植会话、Provider 普通配置、全局规则/记忆/Skills、安全 MCP、无本机路径的工作流、GitHub API Base 和普通 Agent 设置，同时保留目标设备的项目路径、项目审计、媒体缓存、备份计划和设备运行态。
- 会话合并使用来源平台与来源会话 ID 生成稳定主 ID；目标机已有不同本地编辑时，按内容哈希保留稳定冲突副本。重复导入同一备份会跳过完全相同的主副本，不无限制造重复；本机删除墓碑继续生效。Android 专门新增独立便携会话备份桥，没有修改高扩散的 `ChatSessionManager` 主循环。
- 普通备份明确排除 API Key、Codex/ChatGPT 登录、GitHub Token/登录用户、MCP 凭据/敏感请求头、终端扩展与历史、语音模型、远程配对凭据和缓存；恢复前会先完成 ZIP、清单和领域语义全量校验并创建快照，任一步失败回滚。第一方设备之间显式选择的凭据同步仍走独立端到端加密配对通道，不与普通备份混装。
- 专项回归覆盖 Android 导出/合并/冲突/幂等/恶意字段拒绝，Desktop v2 脱敏、真实 Android 风格包导入、本地路径和凭据保留、稳定冲突与重复恢复，以及 v1 兼容/v3 拒绝。验证通过：Android 全模块 `gradlew test`；Desktop Agent 与 Desktop Bridge 的 `go test -count=1 ./...`、`go vet ./...`；`node --check`、固定 `actionlint v1.7.12`、`git diff --check`；darwin/linux amd64/arm64 四目标 `CGO_ENABLED=0 go test -c`。
- Android `:app:installRelease` 用时 8 分 23 秒，成功覆盖安装到 `RMX5200`（`192.168.2.4:5555`），版本 `1.30 (26071902)`，最后更新时间 `2026-07-20 11:53:33`。Release APK 为 29,334,674 字节，SHA-256 `3029F8FEFBB5AC122A57574653139D4CC014E91C197454E5BB3D443A64984B05`。
- 本机正式生成 [windows-amd64.exe](../desktop-agent/dist/murong-desktop-agent-windows-amd64.exe)，160,108,544 字节，SHA-256 `1B08ECF2E58878E18F56357E7BEFF33897C53BB38A6C21629E510F51FBE46191`；以及 [windows-arm64.exe](../desktop-agent/dist/murong-desktop-agent-windows-arm64.exe)，149,973,504 字节，SHA-256 `B71A8C03D3E2D18F06D12B3D1DC8CB1F657C5FBF9CFD590E744B5292DF511B7F`。GitHub 工作流继续使用六个原生 Runner 分别发布 Windows `.exe`、macOS `.app.zip` 与 Linux `.tar.gz` 的 amd64/arm64 包，并以六包加逐包 SHA-256 的精确集合门禁阻止残缺 Release；工作流仍需提交并推送后才能获得远端原生构建事实。
- 变更前专项影响中备份构造、严格校验和双端导入器为 LOW/MEDIUM；Android 设置区因处在设置页组合链被评为 HIGH，因此只更新说明文案，不改变状态或交互签名。跨系统恢复通过独立桥接层实现，没有重构高扩散的 Android 会话主存储。最终全工作区 `detect-changes` 报告累计 45 个文件、558 个符号、63 条流程和 critical 风险，主要来自此前尚未提交的 Android、终端、语音及整套桌面模块，不等同于 P5b.12 专项风险。本阶段没有创建提交或推送。

### 2026-07-20 P5b.13（手机/电脑异网端到端加密云中继）

- 在不复制业务协议的前提下补齐异网控制：Android 继续在应用内运行原有 `/api/v1/*`、SSE、一次性配对、Bearer、设备同步、工作区、终端、桌面任务、审批和提问协议；手机与电脑各自向云中继建立出站 WebSocket，隧道只封装现有 HTTP/SSE。局域网/Tailscale 直连仍是独立选项，云中继不是绕过权限的第二套后门。
- 新增 `murong-cloud-relay-v1`：手机生成 `MR1.<128-bit room>.<256-bit secret>` 连接码，双方以 AES-256-GCM 加密每个二进制帧，AAD 绑定版本、房间和发送角色；消息还校验时间窗、ID、API 路径、方法、头、分块、正文上限和重放。公开中继强制 `wss://`，`ws://` 只允许回环测试。连接码在 Android Keystore 或桌面本机凭据保护机制中保存，前端快照不返回密钥；更换连接码会清除旧配对令牌，要求重新完成手机一次性配对。
- Android 新增私有云中继配置、Keystore 密钥存储、OkHttp WebSocket 重连、并发/队列/正文限制、回环 HTTP 代理和前台服务状态；云中继可在没有局域网权限或 Wi-Fi 的移动网络上单独运行，CPU 唤醒锁继续维持后台连接，Wi-Fi 锁只在直连需要时强制。设置页可启用/停止时修改 WSS 地址、生成或更换连接码、复制到电脑，并明确显示连接/重连错误；仅云中继运行时不再伪造“复制局域网地址”。
- Desktop Bridge 配置 schema 升至 v2，旧 v1 自动迁移为 `direct`。桌面主程序“手机远程节点”新增“局域网/自有私网直连”与“异网端到端加密中继”选择、WSS 地址、一次性连接码和安全状态；连接码成功保存/启动后立即清空输入框。云中继实现可重连的 `http.RoundTripper`，支持并发请求、取消、流式 SSE 和连接中断清理，继续复用同一配对、凭据同步和工具权限链。
- 仓库新增可自托管 `cmd/murong-cloud-relay`。服务只按 128 位房间 ID 和 phone/desktop 角色配对、转发有界二进制密文，不持久化业务数据也不持有端到端密钥；它仍能看到房间标识、角色、连接时间和流量大小，因此文档没有宣称“零元数据”。随后确认已有独立 Agent 公网后端，第一方默认地址改为 `wss://murongagent.rl1.cc/relay/v1/connect`；自建地址与 Tailscale/Headscale 直连继续保留。
- GitHub `build-desktop.yml` 保持 Windows x64/ARM64、macOS Intel/Apple Silicon、Linux x64/ARM64 六个原生桌面目标，并新增 Linux x64/ARM64 两个无 CGO 中继包。独立发布门禁现在严格要求六个桌面包、两个中继包及八份 SHA-256 全部存在且集合精确匹配，才生成统一清单并允许 Release；Android 继续由 `build-apk.yml` 构建。固定 `actionlint v1.7.12` 对全部工作流无警告，但本地未跟踪工作流仍须提交推送后才会产生 GitHub 原生 runner 的远端事实。
- 专项验证通过：Android 云中继协议覆盖连接码、密文不可见、篡改、方向绑定、重放与 WSS 规则；Android 全模块 `gradlew test`；Desktop Agent/Desktop Bridge 的 `go test -count=1 ./...`、`go vet ./...` 和 `node --check`；真实 `httptest` 中继完成鉴权 POST 与 SSE 流式往返；Linux 中继 amd64/arm64 均交叉生成 ELF；Desktop 的 darwin/linux amd64/arm64 四份测试二进制均交叉编译。一次并行高负载验证中 WSL Ubuntu 探针超时，脱离交叉构建单独重跑后完整 Bridge 测试 15.632 秒通过，确认为本机资源竞争而非中继回归。
- Android `:app:installRelease` 用时 7 分 27 秒，成功覆盖安装到 `RMX5200`（`192.168.2.4:5555`），版本 `1.30 (26071902)`，最后更新时间 `2026-07-20 13:02:59`。Release APK 为 29,369,077 字节，SHA-256 `2C7D32DA7E2225436459856E293EDFFFF1492C7DCCBFA9E37E3DBE3BE3C92668`。
- 本机正式生成 [windows-amd64.exe](../desktop-agent/dist/murong-desktop-agent-windows-amd64.exe)，160,295,936 字节，PE `0x8664`，SHA-256 `BFD89AAE0F34BE9B2E19D8DF4BEAE11561575A679710BBBD8EF8EAADCB1740E5`；以及 [windows-arm64.exe](../desktop-agent/dist/murong-desktop-agent-windows-arm64.exe)，150,149,120 字节，PE `0xAA64`，SHA-256 `42F92620C1DC23A822BABFFAAA5EE63E8E958D42F01637B7F8C6925F41FDA9C6`。x64 内置 Codex 已实际运行，ARM64 及 macOS/Linux GUI/内置 Codex 的执行级验证继续由对应 GitHub 原生 runner 负责。
- 最终 `gitnexus detect-changes --repo MurongAgent --scope all` 映射到累计工作区 45 个文件、553 个符号和 63 条流程，整体为 critical；它包含此前尚未提交的 Android、终端、语音、备份和整套桌面模块，不等同于 P5b.13 专项风险。变更前专项影响分析中 Desktop `nodeConfig`/快照与 Android `LanWebRuntime`/前台服务为 MEDIUM，其余中继协议、传输和 UI 多为 LOW 或未索引静态前端；没有出现 HIGH/CRITICAL 专项入口，因此本阶段以新增旁路隧道复用原业务链，没有重构审批或放宽文件/终端权限。未创建提交或推送。

### 2026-07-20 P5b.14（第一方官方中继后端与默认地址，待线上部署）

- 用户补充了两个独立项目：`murongagent-backend` 是实际部署在 `https://murongagent.rl1.cc` 的 Agent 站点/API，`murongagent-admin-apk` 管理端也固定使用其 `github_auth.php` 与 `admin.php`。线上首页和 `/api/health.php` 实测返回 200。另一台 `murongdiaodu-backend-go` 服务器提供 `github_exchange_proxy.php`，因为 Agent 服务器本身无法可靠访问 GitHub；把 Agent 后端改为 Go 不会改变网络边界，因此 GitHub Token 交换代理继续保留，不能把代理 Token 下发给客户端或与 Relay 混用。
- `murongagent-backend` 新增 Go 1.25 兼容迁移骨架，首阶段只承接 `/healthz`、`/relay/healthz` 与 `/relay/v1/connect`，既有 PHP+MySQL 登录、统计、发版和管理 API 继续运行。Relay 复用 `murong-cloud-relay-v1`，只转发有界二进制密文，不持久化房间或业务内容；新增总连接数、单 IP 并发、严格房间/角色/版本、256 KiB 帧上限、慢消费者队列保护、可信回环代理 IP、同角色重连替换和优雅关闭。健康发现只公开计数、容量、协议和官方 URL，不公开房间 ID、连接码或密文。
- 新后端附带 systemd 单元、精确 Nginx WebSocket 路由、生产环境模板，以及 Linux amd64/arm64 GitHub 构建工作流；工作流在 Ubuntu 强制 `go test -race`、`go vet`、双架构静态构建和 SHA-256。Windows 本机没有 GCC，无法执行 `-race`，但普通全量测试、`go vet` 和两架构交叉编译均通过；amd64 二进制 9,029,223 字节，SHA-256 `E9E14802A41C54F21F0D7B8C5730AFA58E41A7AB1B80A2FCD4DD442E9317B68D`，arm64 8,364,246 字节，SHA-256 `43A71B91AFF5D138462CD4D244CAD013D5D3A5B07FF1B4D7C51435EEDBE49FD0`。
- Android 与 Desktop Bridge 统一内置 `wss://murongagent.rl1.cc/relay/v1/connect`：新配置预填官方地址，旧空地址自动迁移，用户清空后启用时恢复官方地址；Tailscale/Headscale 直连和自定义 WSS 仍保留。双端 UI 明确“普通用户保持官方地址、自托管可替换”，不再让用户猜地址来源。配置/启动影响分析中 Desktop `nodeConfig`、`loadNodeConfig` 与 `normalizedConfigLocked` 均为 LOW；Android 只增加默认值和说明，没有改变加密、配对、审批或前台服务接口。
- 验证通过：Android 全模块 `gradlew test`；Desktop Agent/Desktop Bridge `go test -count=1 ./...` 与 `go vet ./...`；前端 `node --check`；两仓库固定 `actionlint v1.7.12`；新后端 WebSocket 测试实际完成 phone→desktop 不透明帧往返、元数据不泄漏、错误元数据拒绝、单 IP 限制和代理 IP 防伪。Android `:app:installRelease` 用时 6 分 22 秒，成功覆盖安装到 `RMX5200`，版本 `1.30 (26071902)`，最后更新时间 `2026-07-20 14:27:11`；APK 29,369,651 字节，SHA-256 `839C58A1A90DBFD291BF0E18FC0E920840D5708839CB6B61B89EEB305AD1E081`。
- Windows 正式包重新构建：x64 160,297,472 字节、PE `0x8664`、SHA-256 `D0A09D40F28CA1B6572E85FDCFDD85FA2E28A4D96E3678C38AA8B14DEADF83F9`；ARM64 150,151,168 字节、PE `0xAA64`、SHA-256 `E482DC0324ACD363571516C2443B51F1D2635465DF475D58E31308E89E49D0DC`。x64 完成原生资源/运行验证，ARM64 运行级验证继续由对应 GitHub 原生 Runner 负责。
- 线上 `https://murongagent.rl1.cc/relay/healthz` 在本阶段结束时实测仍返回 404，证明 Go 服务和 Nginx 路由尚未部署；因此官方地址只完成了客户端/服务端代码和发布物，不能宣称已可用。`MurongAgent` GitHub 仓库已有服务器 SSH Secrets，`murongagent-backend` 仓库没有；实际提交、推送、安装 systemd 单元、修改 Nginx 和真实 WSS 双端联调属于外部生产变更，等待用户明确授权。未创建提交、推送或线上修改。

### 2026-07-20 P5b.15（官方中继交付链与桌面源码入库门禁）

- 用户已明确授权部署。`murongagent-backend` 的第一方 Relay 已以提交 `85e48a5`、`0ef04d5`、`8c03587` 推送到私有仓库；Linux `-race`、`go vet`、ShellCheck、PHP 语法和 OAuth 代理契约均通过。候选包来自源提交 `0ef04d5308caef79f1945bf5b07ded2d2c992212`，大小 9,825,011 字节，SHA-256 `99F829B027B18E2510A4BB909FFA9DE66B583B5803A8CD897D7F7224C2D647A7`，发布在 `agent-relay-candidate-0ef04d5`，Release 资产摘要与本地清单一致。
- 主仓库已以提交 `913a1cc` 加入经哈希、清单、架构、sudo、原子安装、健康检查和 WSS 回环约束的部署工作流。首次生产运行 `29728071635` 在 `ssh-keyscan` 前置步骤失败，候选包验证已通过且服务器尚未发生写入。对 `159.75.33.181` 的 22、2222、22022、2022、222、10022、1022、22222、62222、8022 只读探测均未发现监听；宝塔防火墙页面此前也显示 `tcp 22 未使用`，因此当前阻塞点是服务器没有可用 SSH 服务，不是 Relay 包或部署脚本失败。浏览器只读接管两次超时后已停止，未修改服务器安全配置。
- 重新以当前工作区为准完成提交前门禁：Desktop Agent `go test -count=1 ./...`、`go vet ./...`，Desktop Bridge `go test -count=1 ./...`、`go vet ./...`，前端 `node --check frontend/dist/app.js`，Android 全模块 `gradlew test --no-daemon`，以及 `git diff --check` 全部通过。按既定文档策略只保留本文件作为桌面总执行记录；分散的备份、节点、工作区桥、外部工作流、LAN Web、分享和变化追踪执行文档已被本记录吸收，不进入正式提交。
- 当前真正未闭环的线上事实仍是 `murongagent.rl1.cc` 的 Relay 404 与服务器 SSH 服务未恢复。桌面/Android 累积源码、单应用内置远程节点和六目标构建工作流进入正式提交后，下一步是在服务器恢复 SSH 入口后重跑已验证的原子部署工作流，再以公网 `/relay/healthz` 和 phone↔desktop WSS 加密往返作为完成证据。

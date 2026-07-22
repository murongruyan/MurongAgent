# Murong Desktop Agent

Murong Desktop 是可独立工作的跨平台 Agent。手机控制电脑的能力保留为左侧“远程节点”附加功能，不再承担整个桌面软件的定位。

## 支持平台

首批正式目标均为 64 位：

| 系统 | x64 / amd64 | ARM64 | 发布格式 |
|---|---:|---:|---|
| Windows | 支持 | 支持 | 单文件 `.exe` |
| macOS | 支持（Intel） | 支持（Apple Silicon） | `.app.zip` |
| Linux | 支持 | 支持 | `.tar.gz` |

每个产物内置与目标系统和架构匹配、经过 SHA-256 校验的官方 Codex CLI。当前不发布 32 位 x86、ARMv7 或其他架构；新增架构必须先具备 Wails 原生宿主和官方 Codex 运行时，不能只改文件名伪装产物。

## 首次使用

1. 解压并启动当前系统对应的 Murong Desktop 包。
2. 在聊天页打开已有项目文件夹，或选择父目录新建项目；最近项目可随时切换。
3. 新建或选择模型连接，填写供应商协议、Base URL、模型和 API Key；也可使用内置 Codex 完成 ChatGPT 登录。
4. 选择审批模式并保存：只读、全审批、白名单或全自动。
5. 返回“桌面 Agent”创建任务并发送要求。

配置与会话默认写入系统用户配置目录下的 `Murong` 文件夹，也可用 `MURONG_DESKTOP_DATA_DIR` 指定独立数据目录。Windows 凭据使用当前用户 DPAPI；macOS/Linux 使用应用生成的 AES-256-GCM 私钥并依赖用户配置目录的系统文件权限。普通备份仍排除 API Key 和登录令牌；同一 Murong 的已配对设备可以在用户逐项确认后通过端到端加密同步这些凭据。

## 主要能力

- 会话持久化、重命名、删除、分叉、消息回退、上下文摘要、真实 Token 用量，以及完整 JSON、跨端 JSON 和 Markdown 导入导出；每个任务独立绑定项目，切回任务会恢复它自己的工作目录。
- DeepSeek、OpenAI-compatible、OpenAI Responses、Anthropic Messages 与内置 Codex；支持模型、推理强度、Temperature、最大输出 Token 和图片输入。
- 四档审批、全局/项目工具权限、规则、记忆、Skills、MCP、保存的工作流、GitHub 工具、版本化备份恢复和跨端设备同步；普通 API 与内置 Codex 都能查询记忆并调用 `run_skill`，不再只有 UI 能选择 Skill。
- 打开/新建/最近项目；文件读取、搜索、原子编辑、补丁、删除、权限和目录工具；最大工具迭代默认并上限为 999。
- 当前系统原生 Shell：Windows 可发现 PowerShell 7、Windows PowerShell、CMD 和 WSL；macOS/Linux 可发现登录 Shell、bash、zsh、fish、sh 和可选 PowerShell 7。
- 原生工作区变化监听：识别终端、Git、格式化器或其他编辑器造成的变化，在下一轮附加摘要，并提供 Git/文本 Diff。项目级审计归档还会跨任务持久记录外部变化、Agent/Codex 文件操作和终端/Git 执行结果，支持搜索、来源筛选、分页、Diff 跳转及 JSON/Markdown 导出；命令正文、工具参数、文件内容和密钥不会进入归档。
- 内建有界历史任务检索：普通 API Provider 与内置 Codex 都可按关键词、项目或稳定消息引用读取必要片段，不把整份历史或本机凭据塞进上下文。
- 内建结构化 `ask_user` 决策工具：主 Agent 可在确实需要用户选择时提交 1–4 个单选/多选问题、选项说明和自定义答案；切换任务后待回答卡仍可恢复，取消任务会自动清理问题，后台子代理不会争抢弹窗。
- 计划模式会把普通 API 或内置 Codex 的输出保存为规范步骤卡；用户确认后可执行、继续或清除。每个步骤必须由本轮真实工具收据通过 `complete_step` 签收，未签收的计划不会被伪报为完成；分叉、回退和完整备份会协调计划进度。
- 五类隔离子代理、最多 6 项批量/依赖编排、桌面端不人为限制并行槽位、后台状态/取消/结果持久化。
- Skill 支持内联或隔离子代理执行、项目优先来源、明确的模型偏好和允许工具预算；子代理 Skill 继续受四档审批和父项目权限交集约束，计划模式不会暴露执行型 Skill。
- 远程节点静态整合进主程序；手机可在明确授权后调用电脑项目与终端，并查看规范计划的步骤/证据进度、继续、停止、审批或回答共享桌面任务的问题，不需要第二个程序。跨端计划只传脱敏状态，不传原始工具参数、项目路径或收据消息 ID。

## 手机远程控制

“手机远程节点”支持三种普通连接方式：

- 局域网 / 自有私网直连：填写手机的局域网或 Tailscale/Headscale 私网 IP，数据不经过 Murong 中继。
- 本机 ID 异网连接：输入手机稳定的 16 位 ID。中继只交换设备签名和加密邀请，双方用临时 ECDH 协商一次性隧道密钥；同一 GitHub 账号直接信任，否则由手机确认或使用临时验证码/安全密码。
- ADB：电脑已获得 Android 调试授权时直接复用 ADB 主机信任并建立回环连接。

自动发现只发现设备，不等于自动授权。首次信任必须来自同一 GitHub 账号、ADB、临时验证码/安全密码或接收端确认之一。旧 MR1 连接码、用户填写 WSS 地址和独立中继可执行程序已经删除；新设备 ID 协议内部仍使用短期端到端加密隧道，但不暴露房间或密钥。

## GitHub Actions 构建

[`build-desktop.yml`](../.github/workflows/build-desktop.yml) 使用目标系统的原生 GitHub runner 构建六个桌面产物：

- `windows/amd64`、`windows/arm64`
- `darwin/amd64`、`darwin/arm64`
- `linux/amd64`、`linux/arm64`

每个桌面矩阵项都会运行 Desktop Agent 与 Desktop Bridge 的测试和 `go vet`，下载并校验对应 Codex 运行时，在目标架构上执行内置 Codex 冒烟测试，再由 Wails 原生打包并生成 SHA-256 文件。矩阵结束后有独立汇总门禁：六个桌面包及其校验文件必须全部存在，哈希必须全部通过且不得混入未声明产物，随后与两个 Android APK 一起生成统一 `SHA256SUMS.txt`。手动触发时可选择把整套八个产物发布到 GitHub Release；任一系统或架构失败都不会发布残缺 Release。

当前仓库没有 Windows/macOS 商业签名证书，因此工作流生成的是可验证哈希但未签名的构建；macOS 公网分发所需的 Developer ID 签名与公证、Windows Authenticode 应在仓库配置正式证书秘密后单独接入，不能在源码里伪造。Linux 运行机器还需提供 GTK3 与 WebKit2GTK 4.1 运行库。

Android 仍可由独立的 [`build-apk.yml`](../.github/workflows/build-apk.yml) 构建；完整套件工作流覆盖主 APK、终端扩展 APK 和六个桌面目标。

## Windows 本地发布

在 PowerShell 中执行：

```powershell
.\build-release.ps1 -Architecture amd64
```

也可传入 `-Architecture arm64`。脚本测试完整桌面 Agent，校验目标 Codex 包，调用 Wails 打包，并验证最终 EXE 的图标和版本资源。macOS/Linux 正式产物由对应系统的 GitHub 原生 runner 构建，避免在 Windows 上产生未经运行验证的伪跨平台包。

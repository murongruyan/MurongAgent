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

## 手机异网控制与云中继

“手机远程节点”支持两种连接方式：

- 局域网 / 自有私网直连：填写手机的局域网或 Tailscale/Headscale 私网 IP，数据不经过 Murong 中继。
- 异网端到端加密中继：手机和电脑都只建立出站 WebSocket，适合移动网络与家庭/公司网络互相不可直达的情况。手机生成 `MR1.…` 连接码，其中包含随机房间 ID 和 256 位端到端密钥；中继只转发 AES-256-GCM 密文，无法读取配对令牌、会话、文件、命令或同步凭据。首次连接仍使用手机显示的一次性配对码，原有权限、审批和撤销逻辑不变。

客户端默认使用第一方地址 `wss://murongagent.rl1.cc/relay/v1/connect`。它由独立的 `murongagent-backend` Go 服务承接；Agent 站点原有 PHP API 与另一台 `murongdiaodu-backend-go` 上的 GitHub Token 交换代理保持独立。高级用户仍可使用仓库提供的自托管中继服务：

```bash
cd desktop-bridge
go build -trimpath -o murong-cloud-relay ./cmd/murong-cloud-relay
./murong-cloud-relay -listen 127.0.0.1:8787 -max-connections 1024
```

再由 Caddy、Nginx 等反向代理提供公网 TLS，把 `wss://你的域名/v1/connect` 转发到本机 `127.0.0.1:8787`；`GET /healthz` 可用于健康检查。公网客户端强制 WSS，明文 `ws://` 只允许回环地址测试。GitHub Release 还会提供 Linux amd64/arm64 的静态中继包。若官方中继尚未完成服务器部署或临时不可用，可使用 Tailscale/Headscale 直连或切换到自建地址。

## GitHub Actions 构建

[`build-desktop.yml`](../.github/workflows/build-desktop.yml) 使用目标系统的原生 GitHub runner 构建六个桌面产物：

- `windows/amd64`、`windows/arm64`
- `darwin/amd64`、`darwin/arm64`
- `linux/amd64`、`linux/arm64`

每个桌面矩阵项都会运行 Desktop Agent 与 Desktop Bridge 的测试和 `go vet`，下载并校验对应 Codex 运行时，在目标架构上执行内置 Codex 冒烟测试，再由 Wails 原生打包并生成 SHA-256 文件。工作流还在原生 Linux x64/ARM64 runner 上测试并构建两个无 CGO 的云中继服务包。矩阵结束后有独立汇总门禁：六个桌面包、两个中继包及其校验文件必须全部存在，哈希必须全部通过且不得混入未声明产物，随后生成统一 `SHA256SUMS.txt`。手动触发时可选择把整套产物发布到 GitHub Release；任一系统或架构失败都不会发布残缺 Release。

当前仓库没有 Windows/macOS 商业签名证书，因此工作流生成的是可验证哈希但未签名的构建；macOS 公网分发所需的 Developer ID 签名与公证、Windows Authenticode 应在仓库配置正式证书秘密后单独接入，不能在源码里伪造。Linux 运行机器还需提供 GTK3 与 WebKit2GTK 4.1 运行库。

Android 仍由独立的 [`build-apk.yml`](../.github/workflows/build-apk.yml) 构建；两条工作流合起来覆盖 Android、六个桌面目标和两个 Linux 中继服务目标，避免把完全不同的签名、依赖和发布流程塞进一个巨型 Job。

## Windows 本地发布

在 PowerShell 中执行：

```powershell
.\build-release.ps1 -Architecture amd64
```

也可传入 `-Architecture arm64`。脚本测试完整桌面 Agent，校验目标 Codex 包，调用 Wails 打包，并验证最终 EXE 的图标和版本资源。macOS/Linux 正式产物由对应系统的 GitHub 原生 runner 构建，避免在 Windows 上产生未经运行验证的伪跨平台包。

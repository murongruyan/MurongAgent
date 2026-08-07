# MurongAgent 未发布更新说明

## Unreleased — 2026-08-07

### Codex 多账号与无感切号

- Android 与 Desktop 新增 Codex 账号池，可添加、启停、切换和移除账号，并查看套餐、额度、低额度与失败冷却状态。
- 每个账号拥有独立 `CODEX_HOME`。会话默认绑定自己的账号，防止登录、线程与缓存串号；也可锁定会话，完全禁止自动换号。
- 自动切换只在新一轮任务开始前发生。系统先刷新当前额度；低于保留阈值时再验证候选账号的登录和额度，选择健康账号继续。正在执行的模型输出、工具调用和上传不会被半途切断。
- 若候选账号不可用，会进入可配置冷却期并继续检查下一账号；所有账号都不可用时给出明确原因，不会无限重试或静默丢失任务。
- 设置中可调整自动切换、保留额度百分比和失败冷却时间，并查看最近一次切换说明。
- 聊天输入框下方的“后端与连接”现在直接列出所有已登录 Codex 账号，可查看邮箱、套餐和剩余额度并立即切换；当前账号带勾，任务执行中不会允许切号。
- Codex 账号删除确认已换成应用一致的 Murong 主题弹层；弹层关闭时会短暂锁定底层账号按钮，避免确认点击穿透后误切账号。
- 修复设置首页“本地模型与推理”误进手机操作页的问题，Codex 账号池入口可从该页面正常到达。
- 设置搜索补全 ChatGPT、Codex、登录、账号、额度、切号等真实控件关键词；主应用与终端扩展签名不一致时会明确指出 Debug / Release 混装，不再笼统显示“缺少 Codex”。
- 修复设备码登录完成后强制查找不存在的 auth.json 导致 Android 闪退的问题；官方 app-server 的账户快照现在可以直接完成登录状态保存。
- Android 启动官方 app-server 前自动为每个隔离 CODEX_HOME 配置 cli_auth_credentials_store = "file"，绕过不可用的 Linux keyring，确保登录凭据跨进程和重启持久化。
- 刷新失败或服务暂时返回空账户时不再删除已保存的账号元数据；只有显式退出登录才会清除账号，界面会显示“已保存但待官方验证”。
- 登录错误会把 `account_deactivated`、登录失效和网络超时转换为明确的中文处理建议，不再展示底层 URL 请求错误。
- 新增不依赖真实账号的真机登录闭环回归测试，验证设备码登录、账户与额度读取、服务重启、添加账号后取消、旧账号恢复、额度候选选择、账号池重载和单账号退出。
- 桌面端同步修复空 `account/read` 刷新导致账号元数据丢失的问题，并为每个桌面 `CODEX_HOME` 固定 `cli_auth_credentials_store = "file"`。
- 修复 release 包中 Root 离屏辅助进程的 `main(String[])` 与 `HiddenApiBypass` 反射入口被 R8 裁剪/改名、启动时出现 `NoSuchMethodError` / `NoSuchMethodException` 并回退 Shizuku 的问题；正式包现在完整保留外部 `app_process` 链路。

### GitHub 多账号（Android / Desktop）

- Android 与 Desktop 的 GitHub 登录从单个全局 Token 改为加密账号池；可连续添加多个账号、查看当前账号、切换、退出当前账号或删除其他账号。桌面端切号后会立即刷新远程节点使用的 Token，Agent、子任务或保存工作流运行时会阻止切号。
- OAuth 回调按 GitHub 登录名去重，同一账号重新授权会刷新该槽位，不会覆盖另一个账号；每次授权 URL 都带新的 `client_state`，第二次登录能正常重新拉起浏览器。
- 旧版已保存的 GitHub Token 会自动迁移到默认账号。切换后项目 Git、GitHub MCP、Workflow 列表和手动触发继续使用当前账号，退出一个账号不会清除其他账号。
- Token 与后端会话 Token在 Android 使用 Keystore AES-GCM、在 Windows 使用当前用户 DPAPI 加密；账号池文件不保存明文。

### 完整账号池设备同步

- 设备同步协议新增完整 `codexAccounts`、`githubAccounts`、各自当前账号和 Codex 切号设置，同时兼容旧版单账号字段与 v1-v6 客户端。
- 用户确认后，账号池凭据只在现有 AES-256-GCM 配对通道中短暂解密传输；接收端按账号 ID、邮箱或 GitHub 登录名合并，并立即用目标设备 DPAPI / Android Keystore 重新加密。
- 同步不会清除目标设备上的其他账号；任一账号导入失败会恢复同步前状态。普通备份、跨端聊天导出和日志不会包含账号池或凭据。

### 图片理解与图片生成

- Android 与 Desktop 均可为上传图片单独选择视觉模型。启用后，视觉模型先输出持久化的中文观察摘要，主模型复用该摘要，不会在后续轮次重复上传历史图片。
- 新增 OpenAI Images 兼容生图：支持 `POST /v1/images/generations`、GPT Image 局部预览、取消、失败重试、会话结果卡片，以及 Android 保存相册 / Desktop 下载。
- 生图配置可独立于主聊天模型，支持复用已保存连接或自定义 Base URL / API Key，并提供尺寸、质量、PNG/JPEG/WebP、压缩和局部预览数量。
- `gpt-image-2` 的原生尺寸选项补齐到 `2048x2048`、`2048x1152`、`3840x2160` 和 `2160x3840`；官方约束为单边不超过 3840、两边为 16 的倍数、总像素不超过 8294400、宽高比不超过 3:1。原生 4K 只保证输出尺寸，不保证文字和细节绝对正确。
- 非 GPT Image 模型或需要额外放大时，结果卡片可调用 Replicate `nightmareai/real-esrgan` 做真实 2–4 倍超分；只有长边达到至少 `3840px` 才会显示“真实 4K 超分完成”，并支持进度、取消、重试及保存 / 下载。
- 真实 4K 超分 Token 与看图 / 生图 Key 一样进入 Android Keystore 或 Windows DPAPI；普通备份、聊天导出、审计和日志不包含 Token。
- 设备同步 v8 会同步非秘密的看图 / 生图路由；仅在用户同时勾选“同步设置”和“同步供应商凭据”时，自定义 Key 才会进入已配对的 AES-256-GCM 通道。普通备份、跨端聊天导出、审计与日志均不含这些 Key。

### CC Switch 供应商导入

- Desktop 注册 `murongagent://provider/import`，也可由用户选择接管 `ccswitch://v1/import`；关闭兼容开关时恢复此前的 CC Switch 协议处理器。
- 网站发来的连接地址、API Key、协议预设、余额查询规则和自动刷新周期会先进入 Murong 主题确认弹层，确认后才加密保存。
- JavaScript 提取器不会在应用中执行；只转换受支持的同源 GET、Bearer 认证与余额字段映射。连接、API Key 或余额规则任一步保存失败都会完整回滚，失败的导入仍可重新确认。

### 登录体验与稳定性

- ChatGPT 设备码生成后自动复制到剪贴板；授权页优先以系统相邻/自由窗口边界打开，设备不支持时自动回退普通浏览器。
- 修复 Root Shell 的私有 Termux `am` 抢占系统命令、忽略自由窗参数后回退全屏的问题；登录启动器现在显式使用 `/system/bin/am`，Via 等默认浏览器可按系统能力以自由小窗打开，不硬编码浏览器包名。
- 重新登录会先取消旧的设备码轮询并清除过期代码，失败时也不会继续显示旧设备码。
- 网页授权成功后的账户确认改为强制刷新和有限递增重试，请求等待上限从 30 秒增加到 90 秒，修复第二账号已授权却一直停在“正在读取账户信息”的问题。
- 本地 Codex HTTPS 代理只新增精确允许 `api.github.com:443`，让官方 curated plugin 目录同步不再阻塞初始化；其他 GitHub 地址仍保持禁止。

### 额度与供应商预设

- 额度显示区分“官方 API”“自定义余额接口”“本地预算估算”和“未知”；从未同步的 `0` 不再伪装成真实余额，剩余额度只在口径已知时计算。
- 修复余额同步、余额接口路径和本地预算只写旧配置字段、活动中转卡片仍读取旧值的问题。
- 官方 Provider 预设补齐协议类型、官网、API Key 页面和端点候选；新增连接时可以直接打开官方文档或 Key 页面。
- 信息架构参考 MIT 许可的 `farion1231/cc-switch`，实现完全按 MurongAgent 的 Kotlin/Compose 数据层重写，没有复制上游代码或资源；详见 `docs/design-references.md`。

### 登录凭据安全

- Windows 非活动账号凭据由当前用户 DPAPI 加密；Android 非活动账号凭据由 Android Keystore AES-GCM 加密。
- 只有当前活动账号在私有目录中保留官方 Codex app-server 运行所需的明文 `auth.json`。轮换时先原子写入加密副本，再移除旧账号明文。
- 局域网/ADB 设备同步可传输完整 Codex/GitHub 账号池，但只在用户明确确认后进入已配对的 AES-256-GCM 通道；接收端立即换成本机 DPAPI / Android Keystore 密文，普通备份与跨端聊天导出仍不携带凭据。

### 开源许可证

- MurongAgent 主仓库与终端扩展仓库新增标准 MIT `LICENSE`。
- README 明确：项目原创代码采用 MIT；捆绑或引用的 Codex app-server、Termux 工具链及其他第三方组件继续遵循各自许可证。

> 本文件记录尚未发布的变更；正式升版时再合并进 `release-notes/latest.md`，当前已发布的 1.37 Release 不受影响。

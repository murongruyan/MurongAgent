# GUI 自动化

Murong Agent 的 Android 与 Windows 端使用同一个 `gui` 动作协议。默认路径是读取系统提供的控件语义，不是持续截图：

1. `observe` 获取当前前台窗口的精简语义树和临时 `nodeId`。
2. `click`、`input`、`scroll` 优先通过 Android Accessibility 或 Windows UI Automation 执行。
3. 自绘控件、Canvas、游戏等没有可用语义时，才使用 `vision_query`。
4. 界面变化后重新 `observe`；`nodeId` 不能跨观察结果复用。

## Android 13+

在“工具 → 手机界面操作”中点“启用界面操作”即代表用户发起启用。Root 设备会直接写入并合并当前用户已有的无障碍服务，不再显示应用内二次确认弹窗；状态和失败原因原位显示在卡片中。非 Root 设备会直接打开系统详情页，返回应用后自动刷新连接状态。

在能够精确识别 Oplus 安全中心二次提示 Activity 的 ColorOS 版本上，Root 流程会先持久化一个 WorkManager 恢复任务，再确认目标组件确实停用，随后用有硬超时的独立 `su -c` 命令完成 Murong 启用并立即结束界面等待。授权流程不复用其他工具的持久 Root Shell，因此并发命令或标签协议异常不会让“正在启用”无限等待；整条流程超过 20 秒会返回可重试错误。由于已验证的 ColorOS 版本会延迟约 30 秒检查权限，这一单一组件会保护至少 45 秒后再恢复；恢复任务不依赖当前 App 进程，失败时由 WorkManager 重试。它不会停用整个安全中心；未知 ROM 或组件不匹配时不会执行这项兼容处理。

如果无障碍服务没有连接，已有 Root 用户仍可使用 UIAutomator、`input` 和 `screencap` 后备。密码控件的文本始终会被替换为 `[REDACTED]`。

### 手机操作 Agent

`run_task` 会让当前聊天中已经选择并配置好的模型执行连续手机任务，不再维护第二套 Phone Agent Base URL、模型名或 API Key，也不限定模型必须是 AutoGLM。切换聊天模型后自动同步；远程 API、代理地址、本地模型和账号登录只在统一模型设置中配置一次。ChatGPT/Codex 账号模式由当前对话直接逐步调用 `gui`，不会嵌套第二次登录。

控制器优先向支持函数调用的模型提供 `phone_action` / `phone_finish`，也接受严格 JSON 动作，并继续兼容 Open-AutoGLM 的 `do(...)` / `finish(...)`。每一步都会重新截取当前屏幕、读取前台包名、换算 0–1000 坐标并验证下一张截图。协议本身不限制模型，但可靠性仍取决于所选模型是否具备图像理解、UI 定位和连续规划能力；纯文本模型无法仅靠协议看懂截图。

连续相同截图与动作会被拦截并要求模型换策略，连续等待最多三次，单任务最多 100 步。登录、密码、验证码、滑块、人脸、指纹、支付和提交订单等动作在客户端侧强制转为人工接管，不依赖模型自觉。每步截图在上传前会在内存中缩放并压缩；原始屏幕尺寸仍单独用于坐标换算，图片不落盘。

外卖比价任务会按用户勾选的平台依次检查美团、饿了么、京东秒送和淘宝闪购，统一记录商品价、包装费、配送费、优惠、结算前到手价、规格和数量，只在可比报价中选择最低价。允许进入购物车查看结算前价格，但不会点击提交订单或付款。未安装、未登录、地址未设置或超出配送范围会作为不可用原因返回。

## Windows

桌面端先使用 Windows UI Automation 的 Invoke、Value、Selection、Expand/Collapse 和 Scroll Pattern；控件不支持相应 Pattern 时，再使用鼠标、Unicode 键盘和滚轮输入。节点路径、AutomationId 和 ClassName 会共同校验，过期节点不会被盲目点击。

截图通过 `System.Drawing.CopyFromScreen` 直接写入内存流，不创建临时图片文件。视觉模型在多显示器或裁剪截图中返回的相对坐标，会转换成 Windows 虚拟桌面的绝对坐标。

普通 API Agent 和内置 Codex/ChatGPT 后端都会注册同一套 `gui` 动态工具；计划模式只能执行 `observe` 与 `wait`，写操作仍由只读审批策略拒绝。

## 本地视觉与 API 回退

手机和电脑都支持三个模式：

- `local_only`：只调用所选内置视觉模型。
- `local_first`：先调用内置模型；失败后，仅在用户明确授权时使用当前或另一个已配置的用户 API 模型。
- `user_api`：直接使用已配置的用户 API 模型，仍受截图隐私开关限制。

内置模型不要求用户填写地址、模型名或 API Key。模型中心提供以下可独立下载安装、切换和删除的选项：

| 模型 | 运行时 | 适合场景 |
| --- | --- | --- |
| Qwen3.5-9B Ultra | MNN 3.5 | 16GB 旗舰手机的最高质量代码、视觉和 Agent 档 |
| Qwen2.5-Coder-7B | MNN 3.5 | 纯文本编程、重构和代码解释；不用于截图 |
| Qwen3.5-4B Pro | MNN 3.5 | 中文、代码和 GUI Agent 默认首选 |
| Qwen3.5-2B Lite | MNN 3.5 | 内存较小设备的中文 GUI 轻量档 |
| Gemma 4 E4B | LiteRT-LM 0.14 | Google 端侧优化、图像与音频能力 |
| Gemma 4 E2B | LiteRT-LM 0.14 | 更低运行内存的完整多模态档 |
| Gemma 4 12B（实验） | LiteRT-LM 0.14 | 约 6.55GB，当前仅在 Windows 开放；Android 等待官方移动包 |

本地聊天在 Android 和 Windows 都从 MNN/LiteRT-LM 运行时逐 token 回传；工具调用标签会在流式层隐藏，完成后再交给 Agent 执行。支持思考的模型只提供其真实的“关闭/开启”档位，思考内容与正文分流并显示在可折叠区域；Coder-7B 不显示思考选项。首次加载模型和长提示词的首 token 延迟仍取决于内存、存储和 CPU/GPU/NPU 能力，低性能设备建议选择 2B 档或改用用户自填 API。

DeepSpec 没有直接嵌入客户端：它是面向 8-GPU 训练环境的草稿模型训练与评测工具链，不是 Android/Windows 推理动态库，其默认 Qwen3-4B 数据准备缓存约 38TB。Gemma 4 自带的 MTP 草稿模型由 LiteRT-LM 运行时使用，更适合端侧无损投机解码；若 MNN 或 LiteRT-LM 后续公开稳定的双模型端侧接口，再为 Qwen 接入匹配的草稿模型。

模型不直接塞进安装包：首次选择时从官方 ModelScope 或 Hugging Face 仓库下载到应用私有目录，支持暂停和断点续传，并按固定大小及 SHA-256 逐文件校验。这样不会让 APK/EXE 固定增加数 GB，也允许用户只保留自己需要的模型。运行时随应用打包；用户不用另装 Python、Java、Ollama 或模型服务器。

已安装模型同时会出现在聊天模型列表中，可像 API Provider 一样直接用于文字、代码和 Agent 工具调用，不需要填写本地 URL、模型名或 API Key；带视觉能力的模型还可读取图片。聊天与 GUI 优先共用同一个持久运行时，选择纯文本模型时 GUI 会自动使用另一个已安装的视觉模型。

本地推理使用 4096 token 的运行上下文；GUI 定位最多生成 512 token，聊天最多生成 1024 token。模型卡标注的 128K/256K 是模型理论上限，不适合端侧直接占满。图片最长边缩放到 1280 像素，在内存中直接交给 MNN 或 LiteRT-LM。

高级设置仍可连接已有的本地 OpenAI-compatible 视觉服务。地址只接受 loopback、私有网段、链路本地地址或 `.local` 主机，本地连接允许 API Key 为空；未填写时完全不走这条兼容路径。

## 隐私默认值

- 远程主模型读取语义文本：关闭。
- 远程模型接收截图：关闭。
- 远程模型接收完整屏幕：关闭。
- 当前远程聊天模型接收 Phone Agent 完整截图：关闭，必须在手机操作设置中单独授权。
- 截图持久化：关闭，当前实现只保存在调用期间的内存中。
- 本地视觉结果返回远程主模型：默认删除摘要、OCR 和理由，只保留 `targetFound`、`x`、`y`、`confidence`。

即使开启远程截图，也必须单独开启“完整屏幕”权限；否则远程视觉调用需要同时提供 `cropLeft`、`cropTop`、`cropRight`、`cropBottom`。

## 主要动作

| action | 用途 |
| --- | --- |
| `observe` | 读取精简语义树 |
| `click` / `long_click` | 按最近的 `nodeId` 或明确坐标操作 |
| `input` | 通过语义 Value/SetText 优先输入；Root 回退可用 `selectorText` 定位后输入 |
| `scroll` | 语义滚动优先，滚轮或手势后备 |
| `tap` / `swipe` | 明确坐标手势 |
| `key` | Android 全局键或 Windows 组合键 |
| `launch` | 启动 Android 包或 Windows 应用 |
| `wait` | 等待后重新观察 |
| `screenshot` | 返回尺寸和 SHA-256，不返回像素 |
| `vision_query` | 本地优先的按需视觉定位 |
| `run_task` | 复用当前聊天模型完成 Android 连续观察、动作、纠错与人工接管 |

# MurongAgent 1.35

## 1.35 / 26072803 — 2026-07-28

本文件只记录当前待发布版本 1.35 的更新说明，不包含已经发布版本的历史变更。

### Added
- Android 设置首页新增“本地模型与推理”直达入口；搜索“本地模型、CPU、GPU、线程、LiteRT、MNN、性能模式”等关键词均可找到本地推理配置。
- Desktop 新增独立运行时包安装器：首次使用 Codex、MNN、llama.cpp 或 LiteRT 时，从与当前客户端版本一致的 GitHub Release 下载对应平台组件，并校验发布清单中的文件大小与 SHA-256。
- 完整发布产物新增 6 个 Codex 平台包，以及 Windows x64 的 MNN、llama.cpp、LiteRT 包和 Windows ARM64 的 MNN 包；运行时包在 Release 清单中明确标注组件、系统、架构和版本。

### Changed
- Android 滚动性能提示改为交互期间统一申请窗口高刷新率，并通过引用计数覆盖分页、页面触摸和列表惯性滚动；手指离开后保留短暂缓冲，避免自适应刷新率反复切档。
- Windows Desktop 主程序改为轻量安装包，不再把约 138 MB 的 Codex 与约 227 MB 的本地模型运行时直接嵌入 EXE；本地构建脚本和 GitHub Actions 均生成主程序与独立运行时包。
- Desktop 的 Codex 设置文案改为“运行时按需安装”，仍允许高级用户直接指定系统已有的 Codex CLI；已存在的受管运行时或外部 CLI 会优先复用。
- 主应用与 Desktop 默认版本升级到 `1.35`，Android `versionCode` 升级到 `26072803`；终端扩展保持 `1.12 / 26072802`。

### Fixed
- 修复部分 ColorOS / 高刷新率设备在长聊天、工具记录与设置页之间切换后降到 1/10/30Hz，重新滚动仍不能稳定恢复的问题。
- 移除原先以显示线程优先级持续执行无意义计算的“提帧”忙等线程，并停止把整帧间隔错误上报为 ADPF 实际 CPU 工作时长，避免长列表渲染时额外争抢 CPU。
- 修复本地模型 CPU / GPU 配置虽然仍存在于手机操作页，但设置搜索索引没有收录、用户无法定位的问题。
- 修复终端扩展已经安装且包含 `codex-app-server`，但完整 Termux 工具链中任一无关文件异常时登录入口仍误报“扩展包缺少 Codex”的问题；现在会在同签名校验和安全 manifest 校验通过后，直接从扩展 APK 的只读 native 目录解析 dedicated app-server。
- 运行时解压拒绝绝对路径、目录穿越、符号链接与设备文件，并限制归档大小、文件数和解压总量；下载不完整或清单校验失败时不会替换已安装运行时。

### Verification
- Android 设置搜索、终端扩展原生命令解析、Core 与 App 定向单元测试通过，Debug Kotlin 编译通过。
- Desktop Go 单元测试、运行时解压安全测试、`go vet`、前端契约与完整发布工作流契约测试通过。
- GitHub Actions 将重新构建并校验 Android 主程序、终端扩展、六个平台的轻量 Desktop 安装包及全部独立运行时包，并将本文件同步为 GitHub Release 正文和移动端后端更新说明。

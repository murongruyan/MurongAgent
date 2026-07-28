# MurongAgent 1.36

## 1.36 / 26072804 — 2026-07-28

本文件只记录当前待发布版本 1.36 的更新说明，不包含已经发布版本的历史变更。

### Changed
- Android 的 Codex dedicated app-server 现在无论普通网络还是 VPN 环境，都会先连接应用内的 Java HTTPS 回环桥；桥接层优先使用 Android 当前网络，VPN 发布的数字代理仅作为代理模式网络的后备路径。
- 移动安装包同步改为主程序与终端扩展两个独立 GitHub Actions matrix job，互不阻塞；单个大文件上传最长允许 6 小时，并对连接中断、超时和临时 HTTP 错误自动重试。
- 主应用与 Desktop 默认版本升级到 `1.36`，Android `versionCode` 升级到 `26072804`；终端扩展保持 `1.12 / 26072802`。

### Fixed
- 修复开启 VPN 或系统代理时，ChatGPT / Codex 设备码已经生成，但应用随后显示 `error sending request for url`、始终无法完成登录的问题。
- 修复约 120 MB 的终端扩展上传速度较慢时，后端同步 job 在 150 分钟整被取消，导致 APK 可能已经上传但版本元数据没有发布的问题。
- Android 当前网络无法建立连接时，Java 回环桥会在向 Codex 返回失败前尝试 VPN 发布的数字代理，兼容 TUN 与仅代理两种网络模式。

### Verification
- Core Codex 代理目标策略、VPN 数字代理规范化、App/Core 单元测试与 Android Debug 构建通过。
- 完整发布工作流 YAML、移动端双 job 上传契约、超时与重试参数测试通过。
- GitHub Actions 将重新生成 1.36 全平台产物，把本文件同步到 GitHub Release 和后端更新说明，并分别上传 Android 主程序与终端扩展。

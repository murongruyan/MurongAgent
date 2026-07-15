# MurongAgent 1.8

版本：1.8
版本号：26071518

## 更新摘要

本次更新重点修复终端的 Termux 脚本兼容性问题，为编辑页新增文件快速运行能力，并全面加固外部扩展加载的安全性。

## 新增

- **编辑页快速运行**：项目编辑器工具栏新增 ▶ 快速运行按钮和终端运行按钮，支持直接运行当前编辑的文件。
- **输出面板**：编辑器下方显示实时运行输出（限 512 KiB），含停止、清空和退出码显示。
- **语言支持**：Python、Shell、JavaScript、TypeScript、Ruby、PHP、Perl、Lua、R、Tcl、C、C++、Go、Rust、Java、Kotlin 共 16 种语言。
- **依赖自动安装**：运行前检测到缺少解释器/编译器时弹出确认对话框，可通过终端扩展包下载安装。
- **终端运行**：复制经过 shell 转义的运行命令并自动切换到终端标签。

## 修复

- **pkg bad interpreter**：扩展包的 `pkg` 等脚本硬编码 Termux 路径，在主应用私有 toolchain 目录下不再导致 `bad interpreter`；安装时自动重写所有 Termux 固定 prefix。

## 变更

- **ToolchainManager**：安装工具链时对文本文件执行 Termux → 应用私有目录路径重写。
- **findCommandPath()**：新增统一查找 bundle 命令和系统 PATH 命令的接口。
- **Java 运行**：使用 `javac -d` 编译后 `java -cp` 执行，支持 `package` 声明。
- **编译型语言**：C/C++/Go/Rust 构建产物写入 `.murong-run/` 目录。
- **终端环境变量**：补齐 `PREFIX`、`TERMUX__PREFIX`、`TERMUX_APP_PACKAGE_MANAGER=apt`。

## 安全

- **扩展签名校验**：仅接受与主应用同签名的扩展 APK。
- **Manifest 路径约束**：所有 files、links、commands 路径经 canonicalize 和越界校验。
- **安装失败回滚**：toolchain 安装 I/O 失败时自动清理半成品。

# Murong Windows Node

Windows Node 是手机 Murong 的原生电脑工具端。它不提供聊天、模型或第二套 Agent，只把一个明确授权的电脑目录和用户勾选的终端后端交给手机端调用。

## 首次运行

1. 在手机 Murong 的“电脑节点”设置中启动服务并生成一次性配对码。
2. 双击 `murong-windows-node-amd64.exe`。
3. 在原生窗口中填写手机地址、选择允许访问的电脑工作区并输入一次性配对码。
4. 按需要打开“文件写入”，在“电脑终端”中同时勾选 PowerShell 7、Windows PowerShell、CMD 或一个/多个 WSL 发行版，然后点击“启动节点”。

配对成功后，节点会把手机地址、工作区和能力开关保存到 `%LOCALAPPDATA%\Murong\computer-node.json`。访问令牌由当前 Windows 用户的 DPAPI 加密，不以明文保存。以后双击 EXE，点击“启动节点”即可；也可以在界面中选择登录 Windows 后自动启动。

窗口关闭或最小化后节点驻留系统托盘。托盘菜单可以重新打开窗口、启动/停止节点或彻底退出。

异网使用时，让手机和电脑加入同一个 Tailscale/Headscale 私网，并在“手机地址”中填写手机的 Tailnet IP，例如 `http://100.x.y.z:8765`。

## 命令行版本（可选）

不需要图形界面时，可以运行同目录下的 `murong-windows-node-cli-amd64.exe`：

```powershell
.\murong-windows-node-cli-amd64.exe --phone http://192.168.1.20:8765 --workspace C:\project --allow-write
```

首次运行会提示输入手机上的一次性配对码。Tailnet 示例：

```powershell
.\murong-windows-node-cli-amd64.exe --phone http://100.x.y.z:8765 --workspace C:\project --allow-write
```

## 能力开关

- 默认只读，不传 `--allow-write` 就不能创建或修改电脑文件。
- 电脑终端默认关闭；使用 GUI 可同时勾选多个 Shell，CLI 使用 `--terminals powershell7,wsl:Ubuntu`。
- 可选 ID 会按本机实际安装情况显示：`powershell7`、`windows-powershell`、`cmd` 与 `wsl:<发行版名称>`。
- Windows Terminal 只是窗口宿主，节点会显示其版本，但不会把它当作可捕获 stdout/stderr 的 Shell。
- 写入、建目录和终端命令是否弹手机审批由 Murong 当前审批模式决定；Windows Node 不再弹第二个电脑审批框。
- PowerShell/CMD 以运行节点的 Windows 用户权限执行，WSL 以所选发行版的当前 WSL 用户权限执行，均可能访问工作区之外。

CLI 多终端示例：

```powershell
.\murong-windows-node-cli-amd64.exe --terminals powershell7,wsl:Ubuntu,wsl:Ubuntu-26.04
```

旧的 `--allow-terminal` 仅为已有配置兼容，等价于选择 `windows-powershell`。

关闭已经保存的能力：

```powershell
.\murong-windows-node-cli-amd64.exe --allow-write=false --terminals ""
```

手机撤销节点后，需要生成新配对码并运行：

```powershell
.\murong-windows-node-cli-amd64.exe --pair-code ABCD-EFGH
```

## 构建

```powershell
.\build-release.ps1
```

输出：

- `dist\murong-windows-node-amd64.exe`：默认的原生 GUI 版本。
- `dist\murong-windows-node-cli-amd64.exe`：可选命令行版本。

# Reasonix Mobile

**DeepSeek 原生 AI 编程助手 · Android 版**

Reasonix Mobile 是 [DeepSeek-Reasonix](https://github.com/esengine/deepseek-reasonix) 的手机端实现，在保持核心 Agent 能力的基础上，增加了**多 Provider 支持**和**原生 Android 体验**。

## ✨ 特性

| 特性 | 说明 |
|------|------|
| **多 Provider** | DeepSeek 官方 + OpenAI 兼容 + Claude + 任意中转站 |
| **Root Shell** | 通过 KeepShell 持久化 root 会话执行命令 |
| **代码编辑** | SEARCH/REPLACE 精确修改文件 |
| **文件操作** | Root 权限访问整个文件系统 |
| **流式响应** | 实时显示模型思考过程和输出 |
| **中转站兼容** | 设置 Base URL 即可连接任意 OpenAI 兼容中转站 |
| **数据本地化** | 所有配置和对话记录存于设备本地 |

## 🏗 技术栈

| 层 | 技术 |
|---|------|
| UI | Jetpack Compose + Material3 |
| DI | Hilt |
| 网络 | OkHttp4 |
| 数据持久化 | DataStore Preferences |
| Root Shell | KeepShell（移植自慕容调度） |
| 序列化 | kotlinx-serialization |

## 🔧 构建

```bash
# 克隆项目
git clone <repo-url>
cd reasonix-mobile

# 设置 Android SDK/NDK（参考 local.properties）
# sdk.dir=C:\\Users\\...\\AppData\\Local\\Android\\Sdk
# ndk.dir=C:\\Users\\...\\AppData\\Local\\Android\\Sdk\\ndk\\30.0.14904198

# 构建
./gradlew :app:assembleDebug
```

## ⚙️ 配置

通过 App 内设置页配置：

1. **选择 Provider**: DeepSeek / OpenAI 兼容 / Claude
2. **填写 API Key**: 对应 Provider 的密钥
3. **中转站 Base URL (可选)**: 留空则使用官方 API

### 中转站示例

| 用途 | Base URL | API Key | Model |
|------|----------|---------|-------|
| OneAPI | `https://oneapi.example.com` | OneAPI Token | 任意模型 ID |
| NewAPI | `https://newapi.example.com` | NewAPI Key | 任意模型 ID |
| 自定义 | 你的地址 | 你的 Key | 你的模型 |

## 📁 项目结构

```
reasonix-mobile/
├── app/          # Compose UI (聊天 + 设置 + 启动页)
├── core/         # 核心引擎 (Provider + Agent Loop + Tools)
├── common/       # 共享工具 (KeepShell + RootFile)
└── gradle/       # 构建配置
```

## 🚀 Roadmap

- [x] 多 Provider 支持 (DeepSeek / OpenAI / Claude)
- [x] Root Shell 执行
- [x] 代码编辑 (SEARCH/REPLACE)
- [ ] MCP 服务器支持
- [ ] 思维链 (Thinking) 可视化
- [ ] Web UI 仪表盘
- [ ] 模块打包 (Magisk Module)
- [ ] 后台 Agent 服务

## 📜 许可证

MIT — 和 Reasonix 主项目一致

[English](./murongagent.md) | [简体中文](./murongagent.zh-CN.md)

# Integrate DeepSeek with MurongAgent

MurongAgent is a mobile multi-model AI agent for Android. It supports local project browsing, code editing, GitHub repository workflows, tool calling, and multi-turn agent sessions on-device.

This guide shows how to install MurongAgent, configure DeepSeek as the active model provider, and start your first agent session.

## Install MurongAgent

The easiest way to install MurongAgent is to download the Android package directly.

### Option 1. Install from the official website

Visit:

```text
https://murongagent.rl1.cc/
```

Download the latest Android package from the website and install it on your device.

### Option 2. Install from GitHub Releases

If you prefer GitHub-based distribution, you can also install the app from the project's GitHub Releases page.

```text
https://github.com/murongruyan/MurongAgent/releases
```

### Option 3. Build from source

Building from source is mainly for contributors or users who want to modify the app.

Prerequisites:

- Android Studio
- JDK 26
- Android SDK / Build Tools 37
- A device running Android 13+ (`minSdk 33`)

Then build the app:

```bash
git clone https://github.com/murongruyan/MurongAgent.git
cd murongagent
./gradlew :app:assembleRelease
```

If you want to install directly onto a connected device:

```bash
./gradlew :app:installRelease
```

If your local environment does not have release signing materials, use a debug build instead:

```bash
./gradlew :app:assembleDebug
```

## Configure DeepSeek in MurongAgent

Open MurongAgent, then go to the Settings page.

### 1. Select the provider

In `Settings` → `AI Model Provider`:

- Set **Provider** to `DeepSeek`

MurongAgent also supports `OpenAI Compatible` and `Claude`, but for the official DeepSeek API you should choose `DeepSeek`.

### 2. Enter your API Key

Fill in:

- `API Key`: your DeepSeek API key from [DeepSeek Platform](https://platform.deepseek.com/api_keys)

### 3. Set the Base URL

For the official DeepSeek API, keep:

```text
https://api.deepseek.com
```

If you are routing through a compatible proxy or gateway, you can change the Base URL accordingly.

### 4. Choose the model

Use current DeepSeek V4 model names:

- `deepseek-v4-pro`
- `deepseek-v4-flash`

Do not use deprecated V3-era names such as:

- `deepseek-chat`
- `deepseek-reasoner`
- `deepseek-coder`

### 5. Set reasoning effort

MurongAgent exposes reasoning effort directly for the DeepSeek provider.

Supported values in the app are:

- `low`
- `medium`
- `high`
- `max`

For coding and agent workflows, `deepseek-v4-pro` with `max` is the recommended high-end setup.

## Recommended DeepSeek setup

For general coding and agent use:

```text
Provider: DeepSeek
Base URL: https://api.deepseek.com
Model: deepseek-v4-pro
Reasoning effort: max
```

For faster and cheaper daily use:

```text
Provider: DeepSeek
Base URL: https://api.deepseek.com
Model: deepseek-v4-flash
Reasoning effort: high
```

## 1M context note

DeepSeek V4 models support up to **1M tokens** of context.

MurongAgent does not currently expose a separate `context_window` field in the UI. Instead, it forwards the selected DeepSeek model and reasoning settings to the provider. If you select a current V4 model, you are using the current DeepSeek model family that supports 1M context.

## First run

After saving your settings:

1. Open `Chat`
2. Start a new session
3. Enter a request such as:

```text
Read this Android project and tell me how the chat workflow is organized.
```

If you already attached a local project, you can also ask:

```text
Find the ChatScreen implementation and summarize the message sending flow.
```

MurongAgent can then:

- browse the local project
- search files
- inspect code
- run tool-assisted agent workflows
- combine local project context with GitHub repository context

## Image Understanding and Generation

In `Settings -> Vision and Image Generation`, you can select an independent vision model for image understanding. When independent routing is enabled, that model produces a reusable Chinese observation summary first, so the main model does not receive the same historical image repeatedly.

Image generation uses the OpenAI Images-compatible `POST /v1/images/generations` endpoint, with partial previews, cancellation, retry, and image saving. `gpt-image-2` supports native `2048x2048`, `2048x1152`, `3840x2160`, and `2160x3840` sizes. The official constraints are a maximum edge of 3840px, dimensions divisible by 16, no more than 8,294,400 total pixels, and a maximum 3:1 aspect ratio. Native 4K guarantees the output dimensions, not perfect text, detail, or composition. Non-GPT Image models or additional enlargement can use Replicate `nightmareai/real-esrgan` for a real 2–4x upscale; only output whose long edge reaches at least `3840px` is labeled “real 4K upscale complete”, with progress, cancellation, retry, and download support.

Custom vision, image-generation, and real 4K upscaling API keys are stored in Android Keystore or Windows DPAPI. Normal backups, exports, conversation history, and logs exclude them; device sync requires explicit confirmation for both settings and provider credentials.

## GitHub integration

MurongAgent also supports GitHub-linked workflows.

In `Settings` → `GitHub`, sign in or configure a GitHub token. After that, the app can use GitHub-backed repository browsing and remote file workflows in the project and chat flows.

This is separate from the DeepSeek API key. You normally need both if you want:

- DeepSeek as the model provider
- GitHub repositories as remote project context

## Notes

- The app is Android-first and designed around mobile interaction patterns.
- Tool calling, project context, GitHub context, and session state are tightly connected, so DeepSeek works best when the provider is configured before starting your session.
- MurongAgent supports DeepSeek-specific reasoning controls and forwards `reasoning_effort` to the DeepSeek provider.

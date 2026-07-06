版本：1.5
版本号：26070715

更新摘要：本次版本重点把 `murongagent` 的 Android 侧能力补成更可用的最小闭环，围绕 `看见问题 -> 定位对象 -> 触发动作 -> 观察结果` 持续增强，同时修复聊天流式输出时列表被强制拉回的滚动问题。

## 新增

- 新增并补齐 Android 侧高频能力闭环，围绕 `app / process / logcat / dumpsys / ui` 提供更完整的观察与动作链路。
- 新增常用 `dumpsys` 预设动作，覆盖 `activity / window / package / battery / meminfo / input / display` 等常见诊断场景。
- 新增更多 UI 复合动作能力，补齐 `wait / wait_gone / scroll / scroll_wait` 家族的点击、输入、长按、滑动与百分比滚动场景。

## 优化

- `android` 单工具多 action 架构继续保留，但高频返回已大面积改成结构化 JSON 字符串，agent 不再依赖长文本二次解析。
- 应用、进程、日志、系统状态、通知与特殊权限等高频 action 统一输出更清晰的 `summary / matches / items / outputLines` 等结构字段。
- UI 包装动作统一补齐 `attempts / cycles / scrollsInCycle / matchedNode / percentScroll / innerResult`，更容易做后续自动决策和闭环验证。
- `app_list / app_path / app_install / app_uninstall / settings_get / settings_list / network_summary` 等非 UI 高频 action 已同步结构化。
- `notification summary / channels / permission status` 以及通知监听、无障碍、使用情况访问、特殊权限状态也已统一到结构化输出风格。

## 修复

- 修复聊天流式输出时列表被锁在某个位置的问题：手动上滑或下滑时不再被自动跟随逻辑立刻抢回。
- 修复消息列表“接近底部”判断过宽的问题，避免用户只是略微离开底部就仍被判定为自动跟随状态。
- 修复聊天滚动副作用过于频繁的问题，流式输出期间自动跟随会避开用户正在手动滚动的时刻。

## 稳定性

- 本轮核心改动已反复通过 `:core:compileDebugKotlin` 与 `:app:compileDebugKotlin` 编译验证。
- `installRelease` 构建链路已跑通到 release 打包阶段，设备连接状态正常，可继续用于真机安装验证。

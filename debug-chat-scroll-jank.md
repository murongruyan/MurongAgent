# Debug Session: chat-scroll-jank

- Status: OPEN
- Symptom: 聊天页滑动卡顿，感觉帧率偏低
- Scope: Android debug build, chat screen scrolling
- Notes:
  - 尚未修改业务逻辑
  - 先收集运行时证据，再决定是否修复

## Hypotheses

1. 顶部 hint/status 区在滚动期间频繁重组，导致每帧额外布局/绘制成本升高。
2. 消息列表中的部分气泡或玻璃 surface 过重，造成 GPU/RenderThread 压力，拖慢 fling。
3. 首屏或滚动过程中有主线程同步工作夹在 Compose 重组里，导致 janky frames。
4. 图片、Markdown 或富文本消息在滚动时触发了额外测量/绘制，导致长列表掉帧。
5. 某些状态流更新过于频繁，滚动时伴随无关 recomposition，导致帧时间不稳定。

## Plan

1. 启动调试日志服务器。
2. 在聊天页滚动链路加入最小化 instrumentation。
3. 在真机复现滑动，收集日志与 gfx/frame 证据。
4. 基于证据判断根因，再做最小修复。

## Evidence

- `gfxinfo` pre-fix:
  - Total frames rendered: `468`
  - Janky frames: `189 (40.38%)`
  - 50th percentile: `19ms`
  - 90th percentile: `200ms`
  - 95th percentile: `300ms`
  - 99th percentile: `850ms`
  - Missed Vsync: `155`
- Chat instrumentation pre-fix:
  - 顶部 hint 栈在滚动前后基本稳定，主要停留在 `2` 或 `6` 个 hint，没有出现滚动期间持续抖动。
  - 可见消息在滚动中多次出现 `longestMessageLength = 2925~3258`，且伴随 markdown-like 内容。
- GPU evidence pre-fix:
  - `dumpsys gfxinfo` 中出现大量 `ConvolveGaussian` / `RescaledSurfaceDrawContext` scratch surfaces。
  - Total GPU memory usage 约 `98.10 MB`。

## Hypothesis Status

1. 顶部 hint/status 频繁重组：`Rejected`
2. 玻璃卡片/气泡过重：`Confirmed`
3. 主线程同步工作：`Unconfirmed`
4. 长 markdown/富文本放大滚动成本：`Contributing`
5. 无关状态流频繁更新：`Rejected`

## Fix

- 下调滚动卡片的 blur budget：`cardBlurRadius = sharedBlurRadius.coerceIn(0, 10)`
- 移除 `MurongGlassSurface` 在 blur 未配置时的隐式 `24` 半径兜底

## Next

1. 安装 post-fix debug 包
2. 重新复现聊天/设置/工具页滑动
3. 对比 pre-fix / post-fix `gfxinfo`

## Post-Fix Evidence

- `gfxinfo` post-fix:
  - Total frames rendered: `1611`
  - Janky frames: `794 (49.29%)`
  - 50th percentile: `28ms`
  - 90th percentile: `73ms`
  - 95th percentile: `117ms`
  - 99th percentile: `250ms`
  - Missed Vsync: `531`
  - Slow UI thread: `763`
  - Slow issue draw commands: `785`
- Chat instrumentation post-fix:
  - 顶部 hint 栈仍然稳定，主要在 `2` 或 `6` 个 hint。
  - 长文本/markdown 仍在滚动窗口中反复出现，最高 `longestMessageLength = 7049`。

## Comparison

- Confirmed improvement:
  - `p90`: `200ms -> 73ms`
  - `p95`: `300ms -> 117ms`
  - `p99`: `850ms -> 250ms`
- Not fully resolved:
  - 中位帧时长仍偏高：`19ms -> 28ms`
  - janky ratio 仍高，说明除了 blur 之外，长消息/markdown 绘制成本仍然显著

## Current Conclusion

- 根因并不是顶部状态提示抖动。
- 主要根因之一是全局玻璃卡片模糊成本过高，这一项已被部分缓解。
- 仍存在第二层根因：聊天页长文本/markdown 富内容在滚动中的测量与绘制成本偏高。

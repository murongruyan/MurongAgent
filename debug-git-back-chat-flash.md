# Debug Session: git-back-chat-flash
- **Status**: [OPEN]
- **Issue**: Git 的 Release/工作流页面返回直接退到一级界面且没有预测性返回；从其他页切到聊天时会短暂露出带三横杠的聊天壳层。
- **Debug Server**: pending
- **Log File**: .dbg/trae-debug-log-git-back-chat-flash.ndjson

## Reproduction Steps
1. 进入 `项目 -> Git 工作区 -> 仓库工作台 -> Release` 或 `Workflow`。
2. 触发系统返回手势，观察是否先回到概览并显示预测性返回。
3. 从 `项目/工具/设置` 点击底栏 `聊天`。
4. 观察是否短暂出现聊天页右上角三横杠壳层再稳定到聊天记录页。

## Hypotheses & Verification
| ID | Hypothesis | Likelihood | Effort | Evidence |
|----|------------|------------|--------|----------|
| A | Git 返回链实际命中了错误的外层关闭链，而不是工作台 tab 层 | High | Med | Pending |
| B | `Release / Workflow` 的 detail 状态在手势开始前已被重置，导致没有正确的预测性返回预览 | High | Med | Pending |
| C | 聊天闪动来自顶栏/抽屉壳层先于内容层切换到 Chat | High | Low | Pending |
| D | 聊天页某个抽屉/菜单保留状态参与了切页瞬间渲染 | Med | Med | Pending |

## Log Evidence
- Pending

## Verification Conclusion
- Pending

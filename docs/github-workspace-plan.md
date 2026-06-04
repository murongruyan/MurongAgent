# GitHub 工作区增强规划

## 1. 背景

当前 `Git` 页已经具备本地仓库状态、基础 Git 操作、GitHub Actions / Releases / Issues / Pull Requests / 远端文件浏览等能力，但整体仍偏向“把若干 GitHub 功能塞进一个页面”，距离“比 GitHub 网页版更强的移动端 GitHub 工作区”还有明显差距。

现阶段最突出的体验问题有两个：

1. Git / GitHub 状态识别与“本地已选择项目”强耦合。
2. 工作流、PR、Issue、仓库文件、远端状态等能力分散，缺少统一的信息架构与操作流。

其中第一个问题在当前实现里是显式存在的：

- `ProjectScreen.kt` 中 `Git` 页在 `activeProjectPath.isNullOrBlank()` 时直接显示“先选择一个项目目录，Git 页才能识别仓库状态”。
- GitHub 刷新逻辑主要依赖当前 `gitState`，而 `gitState` 又依赖当前项目/仓库上下文。

这会导致一个反直觉结果：

- 用户明明已经打开了一个工作区，甚至扫描到了多个仓库。
- 但如果没有先把某个项目切成“当前项目”，Git 页就无法成为真正的“工作区 GitHub 控制台”。

这与 GitHub 网页版相比并没有优势，甚至在“多仓库工作区”和“自动识别”场景下更弱。

## 2. 规划目标

本规划的目标不是简单继续堆功能，而是把当前 `Git` 页升级为真正的 `GitHub 工作区`，做到以下几点：

1. 不依赖用户先手动选择项目，也能自动识别当前工作区内的 Git 仓库与 GitHub 仓库绑定状态。
2. 从“单仓库详情页”升级为“工作区总览 + 仓库工作台 + 深度详情”的三层结构。
3. 让本地 Git 状态、GitHub 远端状态、工作流、PR、Issue、Review、文件、产物、下载历史形成统一工作流。
4. 在移动端场景下，提供比网页版更强的聚合能力、筛选能力、自动关联能力、快捷操作能力。
5. 所有高频操作优先围绕“待处理事项”和“异常定位”展开，而不是围绕 GitHub 页面对象本身展开。

## 3. 产品定位

新的 GitHub 工作区不应只是“GitHub API 的移动端壳”，而应是一个面向开发执行流的工作台：

- 它应该先回答“现在这个工作区里，哪些仓库出了问题、哪些仓库需要我处理”。
- 再回答“我要在哪个仓库里处理什么”。
- 最后才是“打开某个 workflow / PR / issue / 文件的详细内容”。

一句话定义：

> GitHub 工作区 = 工作区级别的 Git / GitHub 聚合控制台 + 仓库级执行面板 + 异常与协作任务中心。

## 4. 当前问题拆解

### 4.1 识别链路不够独立

当前状态大致是：

1. 工作区检测到多个项目根目录。
2. 用户选择某个项目。
3. 基于该项目刷新 `gitState`。
4. 再基于 `gitState.remoteUrl` 识别 GitHub 仓库。
5. 再加载 GitHub Actions / 远端目录 / Issues / PR 等。

问题在于：

- “工作区仓库扫描”和“当前仓库 GitHub 能力加载”是割裂的。
- 扫描结果没有自然升级成一个“工作区仓库索引”。
- GitHub 页的数据源不是“工作区所有 GitHub 仓库”，而是“当前选中的本地仓库”。

### 4.2 信息架构仍然偏单页堆叠

当前 Git 页里已经有很多能力，但入口形式仍然偏线性堆叠：

- Git 状态
- Git 操作
- 仓库初始化
- 远端绑定
- GitHub Actions
- 远端文件
- Issues / PR / Reviews / 评论

这会带来几个问题：

- 信息密度高，但优先级不清晰。
- 用户不容易快速知道“现在最重要的事是什么”。
- 多仓库场景下不容易横向比较。
- 当仓库较多时，页面会越来越长，搜索和聚焦成本越来越高。

### 4.3 缺少“比网页版更强”的聚合视角

网页版 GitHub 的优势是对象完整、详情清晰，但弱点也很明显：

- 很难工作区级聚合多个仓库的异常状态。
- 本地修改与远端状态不是同屏联动。
- Workflow、PR、Issue、文件、评审上下文关联不够紧。
- 移动端上来回切页面成本更高。

如果我们的移动端工作区不能在“聚合、联动、自动关联”上更强，就没有必要单独做一套更大的 GitHub 工作区。

## 5. 核心设计原则

### 5.1 工作区优先

默认先展示整个工作区，而不是先要求用户进入一个仓库。

### 5.2 自动识别优先

优先自动识别仓库、远端、默认分支、GitHub repo 绑定、异常状态、未提交改动、待处理 workflow/PR/issue。

### 5.3 待办驱动优先

优先展示“需要处理的事”，其次才是“可浏览的信息”。

### 5.4 本地与远端同屏

一个仓库卡片内应同时看到：

- 本地工作区是否脏
- 当前分支 / ahead / behind
- 最近失败 workflow
- 待 review PR
- 未处理 issue
- 可执行快捷动作

### 5.5 聚焦异常优先

任何视图里都应该优先识别：

- workflow 失败
- PR review 阻塞
- merge 冲突风险
- 本地改动未提交
- 本地分支落后远端
- 远端默认分支红灯

## 6. 目标信息架构

建议将现有 Git 页升级为四层结构：

### 6.1 第一层：工作区总览 Workspace Overview

这是进入 GitHub 工作区后的默认首页，用来回答“整个工作区现在发生了什么”。

建议包含以下模块：

1. 工作区健康概览
   - 仓库总数
   - Git 仓库数
   - 已绑定 GitHub 仓库数
   - 脏工作区仓库数
   - 失败 workflow 仓库数
   - 待 review PR 数
   - 打开 issue 数

2. 异常聚合面板
   - 最近失败 workflow
   - 最近被 request changes 的 PR
   - ahead/behind 异常仓库
   - 本地未提交改动最多的仓库

3. 今日待处理面板
   - 我发起的 PR
   - 指派给我的 issue
   - 我需要 review 的 PR
   - 失败但最近仍在运行的 workflow

4. 仓库列表
   - 支持按状态、名称、异常等级、最近更新时间排序
   - 支持只看 GitHub 仓库 / 只看脏工作区 / 只看失败 workflow / 只看有待办

### 6.2 第二层：仓库工作台 Repository Workspace

点击某个仓库后进入仓库工作台，而不是直接跳进单个功能区块。

建议包含以下标签页：

1. 概览
   - 本地状态
   - 远端状态
   - 最近提交
   - 默认分支状态
   - 风险摘要

2. 工作流
   - 工作流列表
   - 最近运行
   - 失败聚焦
   - 产物 / 日志 / 下载历史

3. 协作
   - Issues
   - Pull Requests
   - Reviews
   - Review comments

4. 代码
   - 远端目录浏览
   - 文件预览
   - 与本地文件的快速对照

5. 操作
   - fetch / pull / push
   - stage / commit
   - branch 管理
   - 创建 issue / PR / release / workflow dispatch

### 6.3 第三层：任务上下文详情 Task Context Detail

这里对应具体对象详情页，但要强调“上下文联动”：

- workflow 详情页
  - 关联 commit
  - 关联 PR
  - 关联失败 job / step
  - 关联产物与日志

- PR 详情页
  - review 状态
  - changed files
  - review comments 线程
  - 对应 workflow 状态
  - 对应本地分支状态

- issue 详情页
  - 标签
  - assignees
  - 关联 PR / commit / workflow

### 6.4 第四层：工作区命令面板 Workspace Command Center

单独提供一个全局命令中心，快速执行跨仓库操作：

- 搜索仓库 / PR / Issue / Workflow / 文件
- 批量刷新状态
- 批量拉取
- 批量触发工作流
- 批量下载产物
- 一键打开异常仓库

## 7. 最重要的能力增强

## 7.1 仓库自动识别解耦

这是第一优先级。

### 现状

GitHub 能力刷新主要依赖当前 `gitState`，而当前 `gitState` 强依赖当前项目路径。

### 目标

建立独立的 `WorkspaceRepoIndex`，在工作区层就完成以下识别：

- 本地仓库根目录
- `.git` 是否存在
- 当前分支
- 远端 `origin`
- 是否可解析为 GitHub repo
- owner / repo
- 默认分支缓存
- 最近状态刷新时间

### 改造建议

新增工作区级数据模型：

```kotlin
data class WorkspaceRepoIndexItemUi(
    val rootPath: String,
    val displayName: String,
    val isGitRepository: Boolean,
    val remoteUrl: String?,
    val githubRepo: ProjectGitHubRepoRef?,
    val currentBranch: String?,
    val defaultBranch: String?,
    val statusSummary: ProjectGitStatusUi?,
    val githubSummary: WorkspaceGitHubRepoSummaryUi?,
    val lastRefreshedAt: Long
)
```

这样 GitHub 工作区页面加载时，不需要先等待“当前项目”切换，而是直接：

1. 扫描工作区。
2. 识别全部仓库。
3. 为能识别出 GitHub 远端的仓库并发加载摘要。
4. 生成工作区总览。

### 预期收益

- 解决“必须先选项目 Git 页才能识别仓库”的根问题。
- 支持真正的多仓库工作区。
- 为后续聚合视图打基础。

## 7.2 工作区级 GitHub 摘要缓存

要比网页版更强，必须先把信息聚起来。

建议对每个仓库维护一个轻量摘要对象：

```kotlin
data class WorkspaceGitHubRepoSummaryUi(
    val repo: ProjectGitHubRepoRef,
    val repoHtmlUrl: String?,
    val defaultBranch: String?,
    val openIssueCount: Int,
    val openPullRequestCount: Int,
    val pendingReviewCount: Int,
    val failedWorkflowCount: Int,
    val runningWorkflowCount: Int,
    val latestFailedRun: ProjectGitHubWorkflowRunUi?,
    val latestRelease: ProjectGitHubReleaseUi?,
    val hasAuthError: Boolean,
    val errorMessage: String?
)
```

这个摘要对象只负责总览，不直接承载全部详情。详情在用户进入仓库时按需加载。

### 这样做的意义

- 页面秒开更容易。
- 刷新成本更可控。
- 多仓库视图不会因为加载所有详情而过重。
- 用户先看到“哪里需要处理”，再钻取详情。

## 7.3 任务驱动视图

网页版 GitHub 是按对象组织的，我们可以按任务组织。

建议在工作区总览新增以下系统生成任务：

1. 失败工作流待处理
2. 本地改动未提交
3. 分支落后远端
4. 待我 Review 的 PR
5. 我创建但 CI 失败的 PR
6. 高优先级 Issue
7. 下载失败或过期产物需要重试

每项任务都应能一键跳到对应仓库和对象详情。

## 7.4 本地 Git 与 GitHub 远端联动

这是移动端工作台的关键差异化能力。

一个仓库卡片里应能够同屏展示：

- 本地 branch / ahead / behind
- 本地 staged / modified / untracked / conflicted
- 当前是否存在可提交改动
- 远端默认分支状态
- 最近 workflow 运行状态
- 关联 PR 状态
- 推荐动作

推荐动作示例：

- “先提交本地改动”
- “先拉取远端 main”
- “当前 PR 需要重新跑 CI”
- “存在失败 workflow，建议先查看日志”
- “有产物可下载”

## 7.5 比网页版更强的 Workflow 工作台

当前 workflow 区已经有不少基础，但还能继续做成明显强于网页端的体验。

建议增强点：

1. 工作区级 workflow 异常列表
   - 按仓库聚合失败运行
   - 按失败原因关键词聚类

2. 失败原因智能聚合
   - 例如 `timeout`、`test failed`、`lint`、`gradle`、`signing`
   - 形成可点击的异常标签

3. 跨运行对比
   - 同一个 workflow 最近 5 次运行结果对比
   - 哪一步从绿变红
   - 哪个 job 波动最大

4. 日志工作台继续增强
   - 命中行定位到具体上下文块
   - 失败步骤与日志文件自动映射增强
   - 日志异常摘要提炼
   - 常见错误模式聚类
   - 下载日志后自动建立本地索引

5. 批量操作
   - 批量下载产物
   - 批量重试 / rerun（若 API 支持）
   - 批量标记关注

## 7.6 比网页版更强的 PR 工作台

建议重点做“Review 效率”而不是只做“PR 浏览”。

增强方向：

1. PR 概览卡片
   - review 状态
   - CI 状态
   - merge 风险
   - 评论数
   - 文件变更规模

2. Review 导航
   - 按文件 / 评论线程 / unresolved 状态快速跳转
   - 只看有评论的 diff
   - 只看失败检查相关文件

3. 评论线程增强
   - 树状回复
   - 回复对象高亮
   - “谁在等我回复”聚合

4. 与工作流联动
   - 在 PR 详情中直接看关联 workflow
   - 失败检查一键跳到日志命中点

5. 与本地分支联动
   - 显示本地是否已 checkout 对应分支
   - 是否有未推送提交
   - 是否已落后 base branch

## 7.7 比网页版更强的 Issue 工作台

Issue 页建议不只做列表浏览，而是更偏向“处理中心”：

- 按 `assigned to me` / `created by me` / `recently updated` / `high priority` 聚合
- 自动关联 PR、commit、workflow
- 对 issue 评论、状态、标签变更做简洁时间线
- 支持一键创建 issue 模板

## 7.8 全局搜索与统一筛选

新的 GitHub 工作区必须有统一搜索，而不是每个模块各搜各的。

建议支持统一搜索目标：

- 仓库
- 分支
- workflow
- run
- artifact
- issue
- PR
- review comment
- 文件路径

统一筛选维度：

- 仓库
- 状态
- 更新时间
- 作者 / assignee
- 是否与我相关
- 是否失败 / 阻塞

## 8. 推荐页面结构

建议把现有 Git 页面改造成如下导航结构：

### 8.1 顶部一级标签

1. 工作区
2. 仓库
3. 工作流
4. 协作
5. 文件
6. 操作

### 8.2 顶部全局工具栏

建议保留以下全局动作：

- 全局刷新
- 搜索
- 只看异常
- 只看与我相关
- 排序方式切换
- 工作区筛选

### 8.3 仓库切换器

要保留，但不再是前置条件，而只是聚焦器：

- 全部仓库
- 当前仓库
- 最近访问
- 仅 GitHub 仓库
- 仅异常仓库

## 9. 数据层改造建议

## 9.1 状态分层

当前 `ProjectScreen.kt` 内状态已经很多，继续叠加会快速失控。

建议把 GitHub 工作区状态从“大量页面内 `remember` 状态”拆成三层：

1. `WorkspaceGitHubState`
   - 工作区级仓库索引
   - 聚合摘要
   - 全局筛选

2. `RepositoryGitHubState`
   - 单仓库概览
   - workflow / issue / pr / remote files 的摘要与详情缓存

3. `TaskDetailState`
   - 当前打开的 workflow / issue / pr / artifact / remote file 等详情对象

## 9.2 缓存策略

建议采用“摘要短缓存 + 详情按需缓存”：

- 工作区摘要：30~90 秒缓存
- 仓库工作流摘要：30 秒缓存
- workflow 运行详情：按需刷新
- issue / PR 列表：按 tab 刷新
- 远端文件内容：按 ref + path 缓存

## 9.3 并发加载策略

多仓库工作区下，不能一次把所有 API 全部打满。

建议规则：

- 首屏只加载仓库索引 + 摘要
- 用户进入仓库后再加载二级详情
- 用户进入对象详情后再加载评论、文件、日志
- 对失败 workflow 和与我相关 PR 提高刷新优先级

## 10. 交互细节建议

### 10.1 状态颜色统一

统一定义状态语义：

- 绿色：正常 / 成功
- 黄色：需关注 / 进行中
- 红色：失败 / 阻塞
- 蓝色：可操作 / 当前焦点
- 灰色：无数据 / 未配置

### 10.2 卡片应支持“摘要 + 动作”

每个核心卡片至少应该有：

- 当前状态
- 一行摘要
- 最近更新时间
- 1~3 个推荐动作

### 10.3 一切详情都应可回跳

例如：

- 从失败 workflow 跳回仓库卡片
- 从 review comment 跳回 PR 文件 diff
- 从 artifact 跳回对应 run

### 10.4 下载历史统一管理

当前已有下载记录雏形，建议升级为工作区级下载中心：

- 日志 ZIP
- artifacts
- release assets
- 远端文件

支持：

- 最近下载
- 来源对象
- 状态
- 重试
- 重新打开

## 11. 分阶段实施建议

## 第一阶段：解耦与总览

目标：先把“必须先选项目”这个根问题解决。

范围：

1. 抽离工作区仓库索引
2. 自动识别 GitHub 仓库绑定
3. 新增工作区总览页
4. 新增仓库聚合摘要卡片
5. 保留现有 Git/GitHub 详情能力作为二级页

验收标准：

- 不选择具体项目时，也能看到工作区内全部仓库状态。
- 能直接识别哪些仓库绑定了 GitHub。
- 能直接从工作区总览进入某个仓库工作台。

## 第二阶段：仓库工作台

目标：从“堆叠页面”升级为“仓库工作台”。

范围：

1. 拆分概览 / 工作流 / 协作 / 文件 / 操作标签
2. 建立仓库级状态缓存
3. 统一仓库级刷新逻辑
4. 统一错误与空状态反馈

验收标准：

- 用户能在一个仓库工作台内完成大多数常见动作。
- 页面结构清晰，不再依赖很长的单页滚动。

## 第三阶段：任务驱动与异常中心

目标：做到明显优于网页版。

范围：

1. 失败 workflow 聚合
2. 待 review PR 聚合
3. 本地脏工作区聚合
4. 推荐动作系统
5. 全局命令中心

验收标准：

- 用户进入工作区后能在 10 秒内知道“现在最该处理什么”。
- 至少 3 类异常支持一键直达。

## 第四阶段：深度联动

目标：把 workflow / PR / issue / 本地 Git 真正串起来。

范围：

1. PR 与 workflow 联动
2. PR 与本地分支联动
3. issue 与 PR / commit / workflow 自动关联
4. 下载中心与日志索引中心

验收标准：

- 从一个失败 workflow 可以快速跳到相关 PR、相关文件、相关本地分支动作。
- 从 PR 页面可直达失败检查和评论线程焦点。

## 12. 推荐优先级

如果只按投入产出比排序，推荐优先顺序如下：

1. 仓库自动识别解耦
2. 工作区总览页
3. 仓库工作台分层
4. 任务驱动视图
5. Workflow 异常聚合
6. PR 工作台增强
7. 全局命令中心
8. Issue 工作台增强

## 13. 风险与注意事项

### 13.1 单文件继续膨胀风险

当前 `ProjectScreen.kt` 体量已经很大，新的 GitHub 工作区如果继续往里面堆，维护成本会迅速恶化。

建议从第一阶段开始就同步拆分：

- `GitWorkspaceOverviewSection.kt`
- `RepositoryWorkspaceSection.kt`
- `GitHubWorkflowWorkspaceSection.kt`
- `GitHubCollaborationSection.kt`
- `GitHubWorkspaceModels.kt`
- `GitHubWorkspaceLoader.kt`

### 13.2 API 频率控制

多仓库聚合会明显增加 GitHub API 调用量，需要做：

- 分层缓存
- 惰性加载
- 并发数限制
- 错误退避

### 13.3 Token 能力差异

不同 token 权限可能导致：

- 私有仓库可见性不同
- workflow / artifact / review 数据不完整
- 部分写操作失败

因此需要在 UI 上明确区分：

- 未登录
- 已登录但权限不足
- API 错误
- 仓库不存在或不可访问

## 14. 最终落地建议

这项改造建议不要再按“再补一个 GitHub 功能”来推进，而应按“重新定义 Git 页”为 GitHub 工作区来推进。

最合理的落地顺序是：

1. 先解耦仓库识别链路。
2. 再做工作区总览。
3. 再拆仓库工作台。
4. 再把 workflow / PR / issue / 文件 / 下载能力逐步接进去。

## 15. 本轮建议的下一步

写完这份规划后，建议紧接着做下面三件事：

1. 把当前 Git 页现状拆成“工作区级状态”和“仓库级状态”两张状态图。
2. 先落第一阶段原型：工作区总览页 + 自动识别 GitHub 仓库摘要。
3. 再开始把现有 `GitHub Actions` 能力从当前单页结构搬到“仓库工作台 -> 工作流”标签中。

如果这三步推进顺利，后面再增强 workflow / PR / issue 体验时，就不会再陷入“功能越来越多，但页面越来越乱”的问题。

## 16. 页面草图与信息布局

这一节把前面的信息架构进一步细化成可以直接做 UI 原型的页面布局。

### 16.1 GitHub 工作区首页草图

建议首页结构如下：

```text
+--------------------------------------------------+
| GitHub 工作区                                    |
| [全局刷新] [搜索] [只看异常] [与我相关] [筛选]    |
+--------------------------------------------------+
| 工作区健康概览                                   |
| 仓库 8 | GitHub 6 | 脏仓库 3 | 失败 CI 2 | PR 5  |
+--------------------------------------------------+
| 今日待处理                                       |
| 1. repo-a / PR #24 需要 review                   |
| 2. repo-b / workflow build failed                |
| 3. repo-c / 本地分支落后 4 commits               |
+--------------------------------------------------+
| 异常聚合                                         |
| [CI 失败] [Review 阻塞] [未提交改动] [分支落后]   |
+--------------------------------------------------+
| 仓库列表                                         |
| repo-a  main  ahead 1  CI red  PR 2  Issue 5     |
| repo-b  dev   clean    CI green PR 0  Issue 1    |
| repo-c  feat  dirty    no remote                 |
+--------------------------------------------------+
```

首页必须先满足两个目标：

1. 让用户 3 秒内看懂工作区状态。
2. 让用户 1 次点击内进入最该处理的仓库或任务。

### 16.2 仓库工作台草图

```text
+--------------------------------------------------+
| repo-a / owner/repo-a                            |
| [返回工作区] [网页] [刷新] [命令]                |
+--------------------------------------------------+
| 本地 main ↑1 ↓2 | dirty | 最近提交 xxx           |
| 默认分支 main | Actions 失败 1 | 待 review 2     |
+--------------------------------------------------+
| [概览] [工作流] [协作] [文件] [操作]             |
+--------------------------------------------------+
| 标签页内容区域                                   |
| - 概览: 风险摘要 / 推荐动作 / 关键指标           |
| - 工作流: runs / logs / artifacts                |
| - 协作: issues / prs / reviews / comments        |
| - 文件: remote tree / remote file / local diff   |
| - 操作: fetch pull push stage commit branch      |
+--------------------------------------------------+
```

这里最重要的不是“tab 数量”，而是把原本线性堆叠的超长页面改成可记忆、可切换、可聚焦的工作台。

### 16.3 任务详情页草图

```text
+--------------------------------------------------+
| workflow / PR / issue / artifact 详情            |
| [返回仓库] [返回工作区] [网页] [复制链接]         |
+--------------------------------------------------+
| 核心状态摘要                                     |
| 关联对象卡片                                     |
| 推荐动作                                          |
| 详细内容                                          |
+--------------------------------------------------+
```

核心要求：

- 任何详情页都不是死胡同。
- 任何详情页都能快速回到“仓库”和“工作区”两个层级。

## 17. 页面状态模型设计

为了避免继续把状态全塞进 `ProjectScreen.kt` 的局部 `remember` 中，建议从一开始就定义清晰的状态模型。

### 17.1 工作区级状态

```kotlin
data class WorkspaceGitHubState(
    val workspacePath: String?,
    val repos: List<WorkspaceRepoIndexItemUi>,
    val selectedRepoRoot: String?,
    val overview: WorkspaceOverviewUi,
    val filters: WorkspaceGitHubFiltersUi,
    val loading: WorkspaceGitHubLoadingState,
    val errorMessage: String?
)
```

职责：

- 维护工作区所有仓库索引
- 维护工作区总览摘要
- 维护全局筛选和排序
- 维护当前聚焦仓库

### 17.2 仓库级状态

```kotlin
data class RepositoryWorkspaceState(
    val repoRoot: String,
    val gitStatus: ProjectGitStatusUi,
    val githubSummary: WorkspaceGitHubRepoSummaryUi?,
    val workflowState: RepositoryWorkflowTabState,
    val collaborationState: RepositoryCollaborationTabState,
    val fileState: RepositoryRemoteFileTabState,
    val operationState: RepositoryOperationTabState,
    val loadingSections: Set<RepositoryWorkspaceSection>,
    val errorBySection: Map<RepositoryWorkspaceSection, String>
)
```

职责：

- 只负责一个仓库的工作台数据
- 不直接关心工作区全局筛选
- 可以独立缓存和刷新

### 17.3 详情级状态

```kotlin
data class WorkspaceDetailPaneState(
    val activeType: WorkspaceDetailType?,
    val workflowRunDetail: ProjectGitHubWorkflowRunDetailUi?,
    val issueDetail: ProjectGitHubIssueUi?,
    val pullRequestDetail: ProjectGitHubPullRequestUi?,
    val artifactDialog: ProjectGitHubArtifactDialogUi?,
    val remoteFile: ProjectGitHubRemoteFileUi?,
    val loading: Boolean,
    val errorMessage: String?
)
```

职责：

- 统一管理当前正在查看的对象详情
- 保持详情容器一致，避免每个对象都用不同弹窗策略

## 18. 数据流与刷新机制

### 18.1 首屏数据流

建议首屏加载顺序如下：

1. 读取工作区路径
2. 扫描项目根目录和 Git 仓库
3. 生成 `WorkspaceRepoIndexItemUi`
4. 对可识别 GitHub 远端的仓库并发加载摘要
5. 汇总工作区统计与异常列表
6. 渲染首页

### 18.2 仓库进入数据流

1. 用户从工作区点击某个仓库
2. 复用工作区缓存中的基础摘要
3. 懒加载该仓库工作流、协作、文件等 tab 数据
4. 用户进入某个 tab 后再细加载该 tab 所需详情

### 18.3 刷新策略

刷新要分层，而不是一个“全刷新”把所有内容都重新打一遍。

建议提供以下刷新粒度：

1. 工作区刷新
   - 更新仓库索引和摘要
2. 仓库刷新
   - 更新一个仓库的概览与轻量摘要
3. Tab 刷新
   - 更新某个标签页列表
4. 详情刷新
   - 更新当前打开对象的详细信息

### 18.4 自动刷新策略

建议默认策略：

- 进入 GitHub 工作区时自动刷新工作区摘要
- 切回前台超过一定时间后轻量刷新
- 工作流运行中仓库提高刷新频率
- 手动下拉刷新永远优先

建议时间窗口：

- 工作区摘要：60 秒
- 活跃仓库摘要：30 秒
- 运行中 workflow：15~20 秒
- 详情页：仅用户手动刷新或对象明显变化时刷新

## 19. 模块拆分建议

如果要真正开工，建议先按文件边界拆。

### 19.1 UI 层拆分

建议新增：

- `GitHubWorkspaceScreen.kt`
- `GitHubWorkspaceOverviewSection.kt`
- `GitHubWorkspaceRepoCard.kt`
- `RepositoryWorkspaceScreen.kt`
- `RepositoryWorkspaceOverviewTab.kt`
- `RepositoryWorkflowTab.kt`
- `RepositoryCollaborationTab.kt`
- `RepositoryFilesTab.kt`
- `RepositoryOperationsTab.kt`
- `WorkspaceDetailPane.kt`

### 19.2 模型层拆分

建议新增：

- `GitHubWorkspaceModels.kt`
- `GitHubWorkspaceFilters.kt`
- `GitHubWorkspaceTaskModels.kt`

### 19.3 数据加载层拆分

建议新增：

- `GitHubWorkspaceScanner.kt`
- `GitHubWorkspaceSummaryLoader.kt`
- `RepositoryWorkspaceLoader.kt`
- `GitHubWorkspaceCache.kt`

### 19.4 辅助逻辑拆分

建议新增：

- `GitHubWorkspaceSorting.kt`
- `GitHubWorkspaceTaskBuilder.kt`
- `GitHubWorkspaceNavigation.kt`

## 20. API 与能力映射

这一节用于确认“规划中的能力要依赖哪些已有接口，哪些缺口需要补”。

### 20.1 已有能力可直接复用

从当前代码看，以下能力已经具备基础实现，可在新工作区中复用：

- 本地 Git 状态读取
- fetch / pull / push / stage / commit / branch
- GitHub Actions 摘要与运行详情
- workflow dispatch
- 日志 ZIP 下载
- artifact 下载
- releases 列表与部分编辑
- remote repo tree / remote file
- issues / pull requests / reviews / review comments

### 20.2 需要补的聚合接口

为了做工作区级页面，需要补一些“摘要化加载器”：

1. `loadWorkspaceGitHubRepoSummary()`
   - 输入：仓库 root + token
   - 输出：轻量摘要

2. `loadWorkspaceRepoIndex()`
   - 输入：工作区路径
   - 输出：工作区仓库索引

3. `buildWorkspaceTaskList()`
   - 输入：全部仓库摘要 + 本地状态
   - 输出：待处理任务列表

4. `buildWorkspaceOverviewUi()`
   - 输入：全部仓库索引与摘要
   - 输出：首页概览数据

### 20.3 未来可补的扩展接口

后续增强可以考虑：

- 批量 artifact 下载调度器
- workflow rerun / cancel
- PR mergeability 快速检查
- review unresolved 线程聚合
- issue / PR / workflow 的自动关联器

## 21. 核心交互流程

### 21.1 第一次进入 GitHub 工作区

目标：零前置理解成本。

理想流程：

1. 用户打开 GitHub 工作区
2. 页面自动扫描工作区内仓库
3. 页面展示工作区总览和仓库列表
4. 若存在 GitHub 远端绑定，则自动展示远端摘要
5. 若 token 缺失，则明确提示哪些功能受限

### 21.2 处理失败工作流

理想流程：

1. 用户在首页看到“失败 workflow 2 个”
2. 点进异常任务卡片
3. 自动进入对应仓库工作流 tab
4. 直接打开失败 run
5. 日志工作台自动聚焦失败步骤和异常关键词
6. 用户可进一步下载日志或产物

### 21.3 处理待 review PR

理想流程：

1. 首页显示“待我 review 3 个 PR”
2. 用户点进某个 PR
3. 进入仓库协作 tab 的 PR 详情
4. 自动展示 review 状态、CI 状态、评论线程、变更文件
5. 如 CI 失败，可一键跳到关联 workflow 日志

### 21.4 处理本地脏工作区

理想流程：

1. 首页显示“脏工作区 3 个”
2. 用户点进某个仓库
3. 概览 tab 展示本地改动摘要和推荐动作
4. 可直接 stage / commit / push
5. 若与 PR / workflow 有关联，则给出下一步建议

## 22. 统一筛选与排序设计

### 22.1 工作区筛选

建议支持以下筛选项：

- 仅 Git 仓库
- 仅 GitHub 仓库
- 仅异常
- 仅与我相关
- 仅脏工作区
- 仅 workflow 失败
- 仅有待 review PR

### 22.2 工作区排序

建议支持以下排序：

- 最近更新时间
- 异常优先级
- 仓库名称
- 本地改动数量
- 待办数量
- workflow 失败权重

### 22.3 仓库内列表筛选

在仓库工作台内部，各 tab 也应有统一筛选体验：

- workflow：状态 / 分支 / actor / 时间
- PR：状态 / reviewer / CI / merge 风险
- issue：状态 / assignee / label / 更新时间
- 文件：文件类型 / 路径 / 是否有评论

## 23. 推荐动作系统设计

要比网页版更强，一个关键点是不能只展示状态，还要能推荐动作。

### 23.1 推荐动作输入信号

推荐动作可以基于以下信号生成：

- `gitStatus.canCommit`
- `gitStatus.canPush`
- `gitStatus.behindCount > 0`
- `failedWorkflowCount > 0`
- `pendingReviewCount > 0`
- `openIssueCount > 0`
- 当前是否存在运行中的 workflow
- 当前 token 是否具备写权限

### 23.2 推荐动作输出示例

- 先提交本地修改
- 当前分支落后远端，建议先拉取
- 存在失败 CI，建议先看日志
- 有待 review PR，建议先查看未解决评论
- 已有产物可下载
- 当前仓库未绑定 GitHub，建议先创建或绑定远端

### 23.3 推荐动作呈现方式

建议每个仓库卡片最多展示 3 个动作，避免操作噪音。

动作分级：

- 主动作：当前最推荐
- 次动作：高频但次要
- 更多动作：放到仓库工作台或命令中心

## 24. 异常与空状态设计

工作区型产品最怕“信息不全但用户不知道为什么”，所以异常与空状态要清楚区分。

### 24.1 典型空状态

- 当前工作区没有识别到任何项目
- 识别到项目但没有 `.git`
- 有 `.git` 但没有 `origin`
- 有 `origin` 但不是 GitHub 地址
- 有 GitHub 地址但未配置 token
- token 已配置但权限不足

### 24.2 典型错误状态

- 本地 Git 读取失败
- GitHub API 限流
- GitHub API 401 / 403
- 仓库不存在
- workflow / artifact 已过期
- 网络超时

### 24.3 反馈原则

每个错误提示至少包含：

- 发生在哪一层
- 为什么失败
- 当前还能做什么
- 建议下一步动作

例如：

- “当前仓库已识别为 GitHub 仓库，但未配置 token，因此只能查看本地 Git 状态。”
- “该 workflow 日志已过期，建议改为下载本地日志缓存或查看最近一次运行。”

## 25. 性能与可维护性要求

### 25.1 性能目标

建议设定以下内部目标：

- 工作区首页首屏 1~2 秒内有骨架屏
- 3 秒内出现首批仓库摘要
- 切换仓库 tab 尽量复用缓存
- 大列表滚动保持流畅

### 25.2 可维护性目标

- 禁止继续把大量业务状态直接堆在 `ProjectScreen.kt`
- 新增模型、加载器、UI section 要按职责拆分
- 所有工作区聚合逻辑都应能单独测试

### 25.3 可测试性目标

至少应能独立测试：

- 工作区仓库识别
- GitHub repo 绑定解析
- 工作区概览统计
- 任务列表构建
- 推荐动作生成
- 排序与筛选逻辑

## 26. 开工任务拆解

这一节按真实开发顺序拆成可执行任务。

### 26.1 第一批任务

1. 新建工作区级模型文件
2. 抽离仓库索引扫描逻辑
3. 抽离 GitHub 仓库摘要加载逻辑
4. 新建工作区首页 section
5. 用当前 `detectedRepos` 和 `repoStatusSummaries` 先驱动一个最小原型

### 26.2 第二批任务

1. 搭建仓库工作台外壳
2. 把现有 GitHub Actions 区块迁入工作流 tab
3. 把现有 issues / PR / reviews 区块迁入协作 tab
4. 把现有 remote repo tree / remote file 迁入文件 tab
5. 把现有 Git 操作迁入操作 tab

### 26.3 第三批任务

1. 增加工作区级任务列表
2. 增加推荐动作系统
3. 增加统一搜索与筛选
4. 增加异常聚合面板

### 26.4 第四批任务

1. 增加 PR 与 workflow 联动
2. 增加本地分支与 PR 联动
3. 增加下载中心
4. 增加日志索引与异常聚类

## 27. 第一阶段的最小实现范围

为了避免一上来做得过大，第一阶段建议控制在“能跑通主链路”的最小范围。

### 包含

- 工作区首页
- 自动识别仓库与 GitHub 绑定
- 仓库摘要卡片
- 从首页进入仓库工作台
- 仓库工作台只先接入“概览”和“工作流”两个 tab

### 暂不包含

- 全局搜索
- 下载中心
- 批量操作
- issue / PR 高级联动
- 智能异常聚类

### 原因

第一阶段目标是建立正确架构，而不是一次做完所有能力。

## 28. 第一阶段验收清单

开发完成后，至少要逐条确认：

1. 不选择项目时，GitHub 工作区仍能展示工作区仓库列表
2. 非 Git 项目能正确显示受限状态
3. 非 GitHub 远端仓库能正确显示“仅本地 Git 能力”
4. GitHub 仓库能展示基础远端摘要
5. 能从工作区首页进入仓库工作台
6. 工作流 tab 能复用现有刷新、运行、日志、产物能力
7. 页面不再要求“先选项目后识别”

## 29. 第二阶段之后的文档维护建议

这份规划不是一次性文档，建议后续继续维护：

- 每完成一个阶段，就在文档里补“已实现”
- 每发现一个架构偏差，就在风险节里补“实际问题”
- 每新增一个 API 能力，就更新“API 与能力映射”

这样它才能从“规划文档”升级成“长期演进文档”。

## 30. 当前实现进度

### 30.1 已落地

第一阶段已经开始进入代码实现，当前已完成以下基础改造：

1. 项目页二级页面 chrome 状态已从“仅编辑器专用”扩展为“项目页通用二级页状态”。
2. `MainActivity.kt` 已让项目页二级页与设置页二级页一样接入统一返回链路。
3. 项目页二级页已接入预测性返回进度的外层位移与透明度反馈，方向与设置页保持一致。
4. `ProjectScreen.kt` 已改为通过统一的 `ProjectSecondaryChromeState` 上报标题、副标题和二级页激活状态。
5. `Git` 页已新增 `GitHub 工作区` 二级页入口。
6. `GitHub 工作区` 首页骨架已落地，当前可展示：
   - 工作区健康概览
   - 今日待处理
   - 仓库摘要列表
   - GitHub 绑定识别结果
7. 工作区首页中的统计、任务生成和仓库摘要卡片已开始抽成独立 helper / model / composable，为后续接入并发远端摘要加载器预留了稳定的数据消费层。
8. 工作区页打开后，已开始对可识别 GitHub 远端的仓库并发拉取轻量远端摘要，当前摘要已接入：
   - 默认分支
   - 最近工作流状态
   - 运行中工作流数量
   - 开放 Issue 数
   - 开放 Pull Request 数
   - 单仓库摘要错误提示
9. 工作区首页已支持“仓库摘要 -> 仓库工作台”的导航层，进入工作台时会同步切换当前仓库上下文。
10. 仓库工作台第一版标签页骨架已落地，当前已接入：
   - 概览
   - 工作流
   - 远端
   - Issue
   - PR
   - Release
11. `工作流 / 远端 / Issue / PR / Release` 标签已开始复用现有 section，后续从主 `Git` 页迁移时可以逐步收敛为统一实现。
12. 主 `Git` 页已经开始收口，重复的 GitHub 区块已被替换为“GitHub 主操作台”入口卡片，页面重心回到本地 Git 控制与工作区入口。
13. 下载记录归属已经确定：主 `Git` 页只保留“下载中心”摘要入口，工作区内已新增独立下载中心页，仓库工作台概览也同步保留详细记录，方便按“工作区入口 -> 仓库上下文”两层回看。

### 30.2 当前实现边界

当前这版仍然属于第一阶段原型，重点是先把架构和交互壳搭对，因此暂时仍有以下边界：

1. 当前远端摘要仍是轻量版，只覆盖工作流、Issue、PR 等高价值信息。
2. 工作区下载中心虽然已经独立出来，但目前仍以统一历史列表为主，尚未加入按仓库 / 类型 / 状态筛选。
3. 目前还没有工作区级全局搜索、批量操作和任务中心。
4. 远端摘要现在按“打开工作区后触发”执行，后续还可以继续补缓存、失效时间和增量刷新。

### 30.3 下一步代码任务

紧接着建议继续按这个顺序推进：

1. 把 `ProjectGitSection` 中工作区首页和仓库工作台相关模型 / UI 继续从大函数里抽出到更清晰的 section / helper。
2. 给工作区下载中心补按仓库 / 类型 / 时间的筛选和跳转能力。
3. 给远端摘要补缓存、过期时间和手动/自动刷新策略。
4. 开始设计工作区级搜索、批量动作和任务中心入口。

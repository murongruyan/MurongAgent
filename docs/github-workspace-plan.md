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
14. 工作区下载中心已补第一版筛选能力，当前支持按关键字、下载类型、仓库来源、状态维度和时间顺序过滤记录，已能更快定位日志 ZIP、工作流产物和 Release 资产。
15. 下载记录写入链路已开始补结构化仓库元数据，当前工作流日志、工作流产物和 Release 资产在记录时会直接写入 `owner/repo`，下载中心展示与筛选改为“结构化字段优先、来源 URL 兜底”。
16. 下载中心相关页面、筛选枚举与 metadata helper 已从 `ProjectScreen.kt` 独立到单独文件，开始把工作区扩展从超大单文件里拆出来，降低后续继续堆叠的维护风险。
17. GitHub 工作区首页已从 `ProjectScreen.kt` 拆到 `ProjectGitHubWorkspaceOverview.kt`，页面本身只消费轻量 UI model，仓库状态、远端摘要和任务构造仍由原文件内 helper 负责，避免一次性暴露复杂私有状态模型。
18. 首页拆分后已确认 `ProjectScreen.kt` 与 `ProjectGitHubWorkspaceOverview.kt` IDE 诊断为空，当前拆分保持既有预测性返回动画、Reasonix 二级页 chrome 和下载中心入口行为不变。
19. 仓库工作台已开始继续拆分，当前先把“概览”tab 抽到 `ProjectGitHubWorkspaceWorkbench.kt`，通过轻量 `ProjectGitHubWorkspaceWorkbenchOverviewUi` 承接远端摘要、最近工作流、本地远端状态和下载历史，工作流 / 远端 / Issue / PR / Release tab 暂不改行为。
20. 工作台概览拆分后已确认 `ProjectScreen.kt`、`ProjectGitHubWorkspaceOverview.kt`、`ProjectGitHubWorkspaceWorkbench.kt` IDE 诊断为空。
21. 仓库工作台顶部信息卡已继续从 `ProjectScreen.kt` 抽到 `ProjectGitHubWorkspaceWorkbench.kt`，通过轻量 `ProjectGitHubWorkspaceWorkbenchHeaderUi` 承接仓库标题、分支摘要、本地改动摘要和刷新入口，工作台外壳开始进一步瘦身。
22. 仓库工作台 tab 枚举与 tab row 已迁入 `ProjectGitHubWorkspaceWorkbench.kt`，`ProjectScreen.kt` 只保留当前 tab 内容分发，工作台导航结构已和页面内业务区块进一步解耦。
23. `ProjectGitHubActionsSection` 已从 `ProjectScreen.kt` 拆到 `ProjectGitHubActionsSection.kt`，工作流列表、最近运行、日志 ZIP、控制台和产物入口行为保持不变；相关 Actions UI model 已放开为包内可复用类型，为后续拆工作流控制台/日志详情继续铺路。
24. `ProjectGitHubRemoteRepositorySection` 已从 `ProjectScreen.kt` 拆到 `ProjectGitHubRemoteRepositorySection.kt`，远端引用切换、目录浏览、返回上级、打开网页和文件/目录入口行为保持不变；远端浏览 UI model 已放开为包内可复用类型。
25. `ProjectGitHubIssueSection`、`ProjectGitHubPullRequestSection`、`ProjectGitHubReleaseSection` 已统一拆到 `ProjectGitHubCollaborationSections.kt`，Issue/PR/Release 列表、详情入口、状态切换、合并、Release 发布/草稿/预发布/资产入口行为保持不变；协作区块 UI model 已放开为包内可复用类型。
26. 已把跨文件复用的 GitHub 公共 UI model 进一步收敛到 `ProjectGitHubModels.kt`，包括 repo ref、Actions/远端浏览状态、Issue/PR/Release、工作流运行、产物与资产弹窗承载模型，`ProjectScreen.kt` 不再继续堆叠这批公共类型定义。
27. `工作流产物` 弹窗与 `Release 资产` 弹窗已从 `ProjectScreen.kt` 拆到 `ProjectGitHubAssetDialogs.kt`，下载入口和记录写入行为保持不变。
28. `Issue` 详情弹窗已从 `ProjectScreen.kt` 拆到 `ProjectGitHubDetailDialogs.kt`，正文、标签、讨论线程和关闭/重开操作行为保持不变。
29. `Pull Request` 详情弹窗已继续从 `ProjectScreen.kt` 拆到 `ProjectGitHubDetailDialogs.kt`，讨论、评审、代码评审评论、合并、关闭/重开等流程仍由主文件传入动作，先完成结构迁移，再继续收缩行为编排。
30. `工作流运行详情` 弹窗已从 `ProjectScreen.kt` 拆到 `ProjectGitHubWorkflowDetailDialog.kt`，日志搜索、异常步骤聚焦、日志 ZIP / 产物下载入口与运行详情刷新仍由主文件传入动作，先完成 UI 壳与日志工作台迁移，再继续收缩行为编排。
31. `远端文件编辑` 弹窗已从 `ProjectScreen.kt` 拆到 `ProjectGitHubRemoteFileDialog.kt`，文件内容编辑、提交说明和远端提交动作仍由主文件传入状态与保存闭包，先完成弹窗壳迁移，再继续收缩远端仓库行为编排。
32. 远端仓库的 `目录刷新 / 文件打开 / 文件保存` 编排已继续从 `ProjectScreen.kt` 收口到 `ProjectGitHubRemoteRepositoryHelpers.kt`，repo 解析、token 校验、fallback ref 和错误兜底改为 helper 统一处理，为后续继续拆远端 loader 和缓存策略铺路。
33. 工作区级 `远端摘要批量刷新` 已从 `ProjectScreen.kt` 收口到 `ProjectGitHubWorkspaceRemoteSummaryHelpers.kt`，目标仓库构造和并发摘要拉取改为 helper 统一处理，主文件只保留 loading 状态和结果落地。
34. 工作区级 `远端摘要缓存策略` 已接入：`ProjectScreen.kt` 新增按 `rootPath` 记录的 `lastFetchedAt` 与缓存身份，`ProjectGitHubWorkspaceRemoteSummaryHelpers.kt` 统一处理 TTL、配置变更、目标仓库集合变化与缺失条目的自动刷新判断，主文件只按 `plan.shouldRefresh` 触发刷新。
35. 工作区级 `远端摘要刷新` 已进一步改成按计划增量执行：helper 会给出 `targetsToRefresh` 和 `rootsToRemove`，`ProjectScreen.kt` 不再全量清空摘要缓存，而是只移除失效仓库并覆盖过期/缺失仓库的摘要，为后续继续把缓存状态整体移出主文件打基础。
36. 工作区级 `远端摘要缓存落地状态` 已继续收口：`ProjectScreen.kt` 里的摘要 map、抓取时间 map 与缓存身份已合并成单一 `ProjectGitHubWorkspaceRemoteSummaryStore`，清空与增量合并逻辑统一落在 `ProjectGitHubWorkspaceRemoteSummaryHelpers.kt`，主文件只保留 store 状态和调用入口。
37. 工作区级 `远端摘要自动刷新策略` 已继续细化：当前在工作区页可见时会监听 `ON_RESUME`，并按最小检查间隔做前台恢复检查，再结合现有 TTL/增量刷新计划决定是否真正刷新，避免每次回前台都无条件全量拉取。
38. 工作区级 `远端摘要 loading/error 状态` 已收口：`ProjectGitHubWorkspaceRemoteSummaryStore` 已承接 `isLoading` 和 `globalErrorMessage`，刷新状态转换逻辑已移至 helper，主文件只消费 store 状态。
39. 工作区 `概览与仓库卡片构建逻辑` 已抽离：`buildProjectGitHubWorkspaceRepoCards` 和 `buildProjectGitHubWorkspaceOverview` 已移至 helper，相关 UI model 已统一收拢到 `ProjectGitHubModels.kt`，`ProjectScreen.kt` 进一步瘦身。
40. `ProjectDetectedRepoUi` 基础模型已从 `ProjectScreen.kt` 移至 `ProjectGitHubModels.kt`，提高模型在工作区模块内的复用性。
41. `下载记录状态与逻辑` 已抽离：新增 `ProjectGitHubDownloadHelpers.kt` 统一管理 `ProjectGitHubDownloadStore` 和记录逻辑，`ProjectScreen.kt` 不再直接操作下载历史 list，进一步简化了主界面的状态管理。
42. `工作区页导航状态` 已收口：新增 `ProjectGitHubWorkspaceNavigationHelpers.kt` 统一管理工作区首页、下载中心、仓库工作台三种页面状态与返回层级，`ProjectScreen.kt` 不再分别维护多个散落的页面开关。
43. `Issue / PR 详情加载链` 已整段抽离：新增 `ProjectGitHubCollaborationDetailHelpers.kt`，统一承接 Issue 评论、PR 评论、评审、变更文件、代码评审评论的 store、bootstrap 并发加载和分段刷新逻辑，`ProjectScreen.kt` 已从十余个零散状态收缩为两个详情 store。
44. `Issue / PR 详情提交动作与校验` 已继续整段收口：评论提交、评审提交、代码评审评论、回复评论、合并 PR、切换 Issue/PR 状态等逻辑已统一进入 `ProjectGitHubCollaborationDetailHelpers.kt`，主文件只保留结果回写和必要的刷新桥接。
45. `工作流运行详情 / Artifact 下载动作` 已整段收口：新增 `ProjectGitHubWorkflowActionHelpers.kt`，统一承接运行详情刷新、工作流日志下载、Artifact 下载及下载记录元数据回传，`ProjectScreen.kt` 已移除对应下载实现和重复 token 校验逻辑。
46. `Release 资产下载 / 远端文件保存动作` 已继续收口：新增 `ProjectGitHubRemoteReleaseActionHelpers.kt`，统一承接 Release 资产下载、下载记录元数据回传和远端文件保存结果封装，`ProjectScreen.kt` 已移除对应下载实现并进一步压缩远端文件弹窗动作逻辑。
47. `创建 / 编辑 Release、创建 Issue / PR、Release 状态切换与删除动作` 已继续整段收口：新增 `ProjectGitHubMutationActionHelpers.kt`，统一承接三类草稿 state、默认值/互斥切换规则、表单校验、提交反馈和刷新意图回传，`ProjectScreen.kt` 已改为只维护弹窗可见性与结果回写，不再内联这组创建/编辑提交流程。
48. `P0 / M1 第一轮：首页异常聚合、推荐动作与远端摘要状态文案` 已开始落地：工作区首页现已按异常强度重排仓库卡片，增加推荐动作文案、任务详情补充和远端摘要状态说明；`ProjectGitHubWorkspaceRemoteSummaryHelpers.kt` 已承接仓库严重度计算、推荐动作生成和远端摘要状态文案构造，`ProjectGitHubWorkspaceOverview.kt` 已同步改成更偏工作台的首页信息层级。
49. `P0 / M1 第二轮：首页任务与推荐动作直达具体 tab` 已继续落地：工作区导航状态现已记录仓库工作台目标 tab，首页任务卡和仓库推荐动作可直接把用户带到 `工作流 / 远端 / Issue / PR / Release / 概览` 对应上下文，不再只停留在“打开仓库工作台”的粗粒度跳转。
50. `P0 / M1 第三轮：首页失败工作流任务直达运行详情` 已继续落地：工作区任务模型现已支持携带目标 workflow run，首页点击失败工作流任务时会先切到目标仓库的 `工作流` tab，再直接拉取并打开对应运行详情弹窗，开始从 tab 级直达推进到对象级直达。
51. `P0 / M1 第四轮：首页开放事项直达最新 Issue / PR 详情` 已继续落地：工作区远端摘要现已额外缓存最近一个开放 Issue 与最近一个开放 PR 的轻量对象，首页“开放事项”任务会优先直达最新 PR 详情，其次直达最新 Issue 详情；同时新增统一的待执行详情目标状态，保证跨仓库切换后再稳定打开对应详情，不依赖组合重建时序。
52. `P0 / M1 第五轮：仓库卡推荐动作直达对象详情` 已继续落地：仓库卡 quick action 现已支持携带 workflow run / Issue / PR 目标对象，`失败工作流` 和 `PR / Issue 详情` 推荐按钮会复用首页任务的对象级跳转链路，优先直接打开详情，只有无对象可用时才回退到 tab 级导航。
53. `P0 / M1 第六轮：远端摘要改成分层拉取与重点仓库协作预览` 已继续落地：工作区远端摘要现已把开放 Issue / PR 的读取从“每仓库列表拉 20 条”改成“搜索接口取精确数量 + 只拿最新一条预览”，并基于当前选中仓库、已有异常缓存和本地状态优先级，限制只有重点仓库才保留 Issue / PR 对象预览；这样保住首页对象级直达的同时，显著压缩了每轮刷新时的协作摘要负载。

### 30.2 当前实现边界

当前这版仍然属于第一阶段原型，重点是先把架构和交互壳搭对，因此暂时仍有以下边界：

1. 当前远端摘要仍是轻量版，只覆盖工作流、Issue、PR 等高价值信息。
2. 工作区下载中心虽然已经改成优先读取结构化仓库元数据，但历史记录或未接入结构化写入的来源仍会回退到 URL 推断，后续还可以继续统一更多下载来源。
3. 工作区首页、仓库工作台顶部信息卡、tab row、概览 tab、Actions/远端仓库/Issue/PR/Release section，以及部分弹窗、远端仓库 helper、工作区远端摘要 helper、远端摘要缓存判断、增量刷新计划、远端摘要 store、前台恢复检查与公共模型已独立；虽然仍有部分 GitHub 行为编排集中在 `ProjectScreen.kt`，但现阶段先不把继续拆分作为优先目标，只在后续功能开发明确受阻时再局部处理。
4. 目前还没有工作区级全局搜索、批量操作和任务中心。
5. 远端摘要状态说明、首页异常聚合和仓库卡片推荐动作已经有六轮落地，当前首页任务与仓库卡推荐动作都已支持失败工作流、最新 Issue / PR 的对象级直达，且协作预览已改成只对重点仓库保留；后续还可以继续补更多对象级落点和更细的推荐动作。
6. 远端摘要现在按“打开工作区后触发”执行，后续还可以继续补缓存、失效时间和增量刷新。

### 30.3 下一步代码任务

结合当前实现成熟度与继续细拆的收益判断，后续不再把“继续拆 `ProjectScreen.kt`”作为最高优先级，而改成：

1. 先把 GitHub 工作区规划彻底定稿，冻结架构边界、模块职责和阶段目标。
2. 优先继续推进 `P0 / M1`，把首页异常聚合、推荐动作和远端摘要刷新策略真正打磨到稳定可用。
3. 再按功能价值推进工作区级搜索、任务中心、批量动作、下载中心持久化等明确增量能力。
4. 只有当某个剩余内联逻辑明显阻碍功能开发、测试或稳定性时，才再局部补拆 helper，而不是继续为了拆分而拆分。

## 31. 当前模块职责图

这一节用于把“现在代码已经分成了什么”说清楚，避免后续继续开发时又回到“看一眼主文件才能知道逻辑在哪”的状态。

### 31.1 主入口

- `ProjectScreen.kt`
  - 仍然是项目页 Git / GitHub 工作区的总装配入口。
  - 负责主页面状态汇总、页面切换、回调桥接、弹窗显示和结果回写。
  - 现阶段保留它作为编排层，而不是继续强行把每一条回调都拆成单独文件。

### 31.2 工作区层

- `ProjectGitHubWorkspaceOverview.kt`
  - GitHub 工作区首页 UI。
  - 消费概览统计、任务列表、仓库卡片等轻量 UI model。

- `ProjectGitHubWorkspaceWorkbench.kt`
  - 仓库工作台外壳、tab row、顶部信息卡、概览页。
  - 负责承接“工作区 -> 仓库工作台”的二级导航壳。

- `ProjectGitHubWorkspaceRemoteSummaryHelpers.kt`
  - 工作区远端摘要 store、TTL、缓存身份、增量刷新计划、前台恢复判断。
  - 当前是工作区聚合能力最关键的数据层 helper。

- `ProjectGitHubWorkspaceNavigationHelpers.kt`
  - 工作区首页、下载中心、仓库工作台三层导航状态。
  - 统一处理二级页可见性与返回层级。

### 31.3 仓库工作台内容层

- `ProjectGitHubActionsSection.kt`
  - 工作流列表、运行状态、日志/产物入口。

- `ProjectGitHubRemoteRepositorySection.kt`
  - 远端仓库树、ref 输入、文件/目录入口。

- `ProjectGitHubCollaborationSections.kt`
  - Issue、PR、Release 列表及其列表级快捷操作入口。

### 31.4 详情与弹窗层

- `ProjectGitHubDetailDialogs.kt`
  - Issue / PR 详情弹窗 UI。

- `ProjectGitHubWorkflowDetailDialog.kt`
  - 工作流运行详情弹窗 UI。

- `ProjectGitHubAssetDialogs.kt`
  - Artifact / Release 资产弹窗 UI。

- `ProjectGitHubRemoteFileDialog.kt`
  - 远端文件编辑弹窗 UI。

### 31.5 状态与动作 helper 层

- `ProjectGitHubDownloadHelpers.kt`
  - 下载记录 store 与追加逻辑。

- `ProjectGitHubRemoteRepositoryHelpers.kt`
  - 远端仓库目录刷新、远端文件读取/保存的底层编排。

- `ProjectGitHubCollaborationDetailHelpers.kt`
  - Issue / PR 详情 store、bootstrap 并发加载、评论/评审/合并/状态切换动作。

- `ProjectGitHubWorkflowActionHelpers.kt`
  - 工作流运行详情刷新、日志下载、Artifact 下载动作。

- `ProjectGitHubRemoteReleaseActionHelpers.kt`
  - Release 资产下载、远端文件保存动作。

- `ProjectGitHubMutationActionHelpers.kt`
  - 创建 / 编辑 Release、创建 Issue / PR、Release 草稿和预发布切换规则、表单校验、提交结果模型。

### 31.6 公共模型与 API 层

- `ProjectGitHubModels.kt`
  - 跨页面共享的 GitHub UI model。

- `ProjectGitHubApi.kt`
  - GitHub REST API 请求、解析与命令结果封装。

这一层原则上继续保持稳定，后续优先在“helper 组合”和“UI 消费”层推进新能力，不轻易把产品策略又下沉到 API 解析层。

## 32. 架构冻结原则

从这一版开始，GitHub 工作区进入“规划优先、功能优先”的阶段，不再默认继续做细粒度拆分。冻结原则如下：

1. `ProjectScreen.kt` 允许继续作为总装配入口存在，不把“主文件还不够短”当作单独目标。
2. 只有出现以下情况之一，才允许继续新增 helper 或继续拆已有逻辑：
   - 新功能接入时明显需要复用同一组校验 / 提交 / 刷新规则。
   - 当前逻辑已经造成重复修改、容易引入回归或明显降低可读性。
   - 某块代码已经阻碍测试、诊断或稳定性修复。
3. 不再为了“每轮继续拆 100 行左右”而继续拆分；拆分必须服务于功能推进。
4. 已经抽出的 helper 文件尽量保持职责稳定，后续新增能力优先复用既有文件边界。
5. 后续更重要的是：
   - 把工作区级能力补全。
   - 把任务中心、搜索、批量动作做出来。
   - 把下载中心和远端摘要的产品闭环补齐。

## 33. 后续开发总路线图

这里不再按“还能拆哪里”组织，而改成按“下一阶段要交付什么能力”组织。

### 33.1 第一优先级：把工作区真正做成工作台

目标：

- 用户一进入工作区就能知道当前最该处理什么。
- 用户不需要先选项目、先切仓库、先理解页面结构，才知道问题在哪里。

范围：

1. 细化工作区远端摘要刷新策略。
2. 增强工作区总览中的异常聚合和待处理列表。
3. 明确仓库卡片的推荐动作生成规则。
4. 把“工作区首页 -> 仓库工作台 -> 对象详情”的路径彻底打顺。

建议先做：

1. 工作区摘要主动刷新入口。
2. 工作区异常聚合卡片。
3. 推荐动作生成器。
4. 仓库卡片排序与筛选。

### 33.2 第二优先级：工作区级搜索与任务中心

目标：

- 不再按对象列表挨个翻，而是能按“我现在要处理什么”快速定位。

范围：

1. 全局搜索入口。
2. 统一搜索目标：
   - 仓库
   - Workflow
   - Run
   - Issue
   - PR
   - Release
   - 文件路径
3. 任务中心：
   - 失败工作流
   - 待 review PR
   - 本地脏仓库
   - 分支落后
   - 下载失败或待重试

建议先做：

1. 搜索入口壳与统一筛选状态。
2. 任务中心入口卡片。
3. 工作区任务构造器。

### 33.3 第三优先级：下载中心与持久化

目标：

- 下载记录不只是当前会话里看一眼，而是逐渐成为工作区级资料中心。

范围：

1. 下载历史本地持久化。
2. 记录来源对象和仓库元数据的统一格式。
3. 支持重试、重新打开、按来源对象筛选。
4. 为后续日志索引和离线回看预留数据结构。

建议优先评估：

1. 直接用本地文件存储还是引入数据库。
2. 历史记录 schema 如何兼容旧记录。
3. 日志 ZIP、Artifact、Release 资产、远端文件四类来源的统一元数据。

### 33.4 第四优先级：更强的联动与批量动作

目标：

- 做出比 GitHub 网页版更强的移动端聚合效率。

范围：

1. PR 与 workflow 联动。
2. PR 与本地分支联动。
3. Issue 与 PR / commit / workflow 自动关联。
4. 批量刷新、批量下载、批量打开异常仓库。

这一阶段不建议太早做，前提是前面的工作区首页、任务中心和下载中心边界已经稳定。

## 34. 分阶段验收矩阵

为了避免后面继续开发时又变成“想到什么做什么”，这里把每一阶段的验收标准写成能直接对照检查的矩阵。

### 34.1 阶段 A：工作区核心闭环

验收标准：

1. 打开 GitHub 工作区后，能在不手动选择项目的情况下看到工作区仓库和基础远端摘要。
2. 工作区首页能明确区分：
   - 正常仓库
   - 非 Git 项目
   - 非 GitHub 远端
   - 缺少 Token
   - 远端摘要加载失败
3. 用户能从工作区首页直接进入仓库工作台。
4. 工作区首页能显示至少一类“待处理事项”和一类“异常事项”。

### 34.2 阶段 B：仓库工作台稳定可用

验收标准：

1. 工作流、协作、远端仓库、Release、下载记录在仓库工作台中能稳定联动。
2. 常见操作链可在一个仓库工作台内完成：
   - 看 workflow
   - 看 PR / Issue
   - 看远端文件
   - 发起创建动作
   - 下载日志 / Artifact / Release 资产
3. 二级页 chrome、预测性返回、下载中心入口行为保持一致。

### 34.3 阶段 C：任务中心与搜索

验收标准：

1. 首页能提供统一入口快速筛选异常仓库。
2. 用户可通过统一搜索定位仓库、工作流、Issue、PR 或文件。
3. 至少支持 3 类任务卡片一键进入对应上下文。

### 34.4 阶段 D：下载中心与离线回看

验收标准：

1. 下载记录支持跨会话保留。
2. 每条记录都能尽量带上来源仓库和来源对象。
3. 用户能按下载类型、仓库、状态、关键字筛选。
4. 至少支持“重新打开”或“重新发起下载”中的一种。

## 35. 当前建议冻结点

为避免后续开发时又被“是否还要继续拆”反复打断，这里明确当前建议冻结点：

1. 暂停继续拆 `ProjectScreen.kt`。
2. 暂停继续为“剩余一点点 inline 逻辑”单独开新 helper。
3. 暂停继续把已稳定的 UI 壳层再次改目录或改命名。
4. 优先完成规划、明确路线，再按路线做真正提升产品能力的功能。

换句话说，接下来应该从“架构收口期”切到“产品能力建设期”。

## 36. 后续执行建议

如果按当前判断继续推进，建议后面的实际工作顺序固定为：

1. 先把这份规划文档作为基线版本冻结。
2. 下一轮先做工作区首页和远端摘要策略，而不是继续拆结构。
3. 再做任务中心和统一搜索入口。
4. 再做下载中心持久化。
5. 最后再评估是否需要补剩余的局部 helper 化。

只要按这个顺序推进，后面的每一轮工作都会更容易衡量收益，也更不容易再次陷入“代码拆了不少，但产品能力前进不明显”的状态。

## 37. 非目标与暂缓项

为了避免后续范围再次膨胀，这里明确当前阶段不作为优先目标的内容。

### 37.1 当前非目标

1. 不以继续拆 `ProjectScreen.kt` 为阶段目标。
2. 不以“把所有 GitHub 操作都收口成单独 helper 文件”为阶段目标。
3. 不优先做重度智能分析能力，例如复杂日志聚类、错误原因自动归因、自动生成修复建议。
4. 不优先做复杂的跨仓库批处理编排，例如大规模批量 workflow 调度、批量代码修改、批量仓库写操作。
5. 不优先追求“完整替代 GitHub 网页版所有对象页”，而是优先把工作区聚合、异常定位和移动端执行效率做强。

### 37.2 暂缓项

以下内容可以保留在规划里，但不进入最近几轮开发的主线：

1. 复杂日志索引与错误模式聚类。
2. Workflow rerun / cancel 等更深写操作扩展。
3. PR unresolved 线程聚合和高级 review 导航。
4. Issue 与 commit / workflow 的自动语义关联。
5. 更完整的离线资料管理与下载文件本地索引。

## 38. 依赖与前置条件

为了让后续执行时更少踩坑，这里把工作区能力推进所依赖的前置条件列出来。

### 38.1 账号与权限依赖

1. GitHub Token 已配置且具备访问目标仓库的最小权限。
2. 若要支持 workflow、artifact、review、release 等写操作，需要确认 token 权限覆盖对应 API。
3. 私有仓库场景下，需要在 UI 上继续明确区分：
   - 未配置 Token
   - Token 无权限
   - 仓库不存在或不可访问

### 38.2 本地环境依赖

1. 工作区内项目目录扫描结果稳定可复用。
2. 本地 Git 状态读取足够可靠，至少能稳定拿到：
   - 仓库根路径
   - 当前分支
   - upstream 信息
   - ahead / behind
   - staged / modified / untracked / conflicted
3. 下载能力继续基于现有 Android 下载链路，不在当前阶段引入新的下载实现。

### 38.3 数据层前置条件

1. 工作区远端摘要 store 继续作为工作区首页的核心聚合来源。
2. 下载记录若进入持久化阶段，需要先冻结 metadata schema。
3. 工作区搜索与任务中心开始前，需要先确定统一的对象索引字段，至少覆盖：
   - 仓库标识
   - 对象类型
   - 标题 / 名称
   - 状态
   - 更新时间
   - 关联 URL 或导航目标

## 39. 功能优先级矩阵

这一节把后续功能按投入产出比、用户感知收益和实施前置条件进行排序，避免后面开发顺序再被打乱。

### 39.1 P0：必须先做

这些功能决定 GitHub 工作区是否真正从“已有功能集合”变成“可用工作台”。

1. 工作区远端摘要刷新策略完善。
2. 工作区异常聚合与待处理事项。
3. 仓库卡片推荐动作与排序/筛选。
4. 工作区首页到仓库工作台的使用路径稳定化。

### 39.2 P1：高价值增量

这些功能会明显提升使用效率，但前提是 P0 已经稳定。

1. 工作区级搜索入口。
2. 任务中心入口与任务构造器。
3. 下载中心持久化。
4. 下载记录重试 / 重新打开能力。

### 39.3 P2：增强体验

这些功能适合在主链路稳定后逐步补充。

1. PR 与 workflow 联动增强。
2. PR 与本地分支联动增强。
3. 更细粒度的异常聚合标签。
4. 更丰富的下载来源索引与对象回跳。

### 39.4 P3：中长期探索

这些功能有价值，但不适合在近期主线中占据优先级。

1. 高级日志分析与错误模式聚类。
2. 更完整的批量命令中心。
3. 深度离线资料中心。
4. 更复杂的对象自动关联与智能推荐。

## 40. 里程碑定义

为了方便后续按阶段推进，这里把每个里程碑的完成口径写清楚。

### 40.1 M1：工作区首页与仓库工作台主链路可用

完成标准：

1. 首页能展示工作区概览、仓库列表、基础远端摘要。
2. 首页具备异常聚合、推荐动作、过滤与搜索等首屏聚焦能力。
3. 首页可直接进入仓库工作台，并支持从任务或推荐动作直达 tab / 对象详情。
4. 缺少 token、权限不足、非 GitHub 仓库等状态有明确反馈。

交付物：

1. 稳定的工作区首页 UI。
2. 可解释的远端摘要刷新策略。
3. 首页级异常与待处理视图。
4. 可回跳的仓库工作台主链路。

### 40.2 M2：全局搜索与任务中心可用

完成标准：

1. 用户可通过统一入口跨仓库搜索 Issue、PR 和文件等核心对象。
2. 任务中心能够聚合失败工作流、Git 冲突、落后提交和开放事项。
3. 从搜索结果和任务结果都能快速跳入对应仓库、tab 或对象详情。

交付物：

1. 搜索入口与搜索结果页。
2. 任务中心入口与任务聚合结果。
3. 统一的导航落点与对象级直达。

### 40.3 M3：下载中心持久化可用

完成标准：

1. 下载记录支持跨会话保留。
2. 用户可以删除单条记录并一键清空历史。
3. 下载记录能尽量保留来源仓库和来源对象上下文。

交付物：

1. 下载记录本地存储方案。
2. 统一 metadata schema。
3. 下载中心历史回看能力。

### 40.4 M4：工作区批量操作可用

完成标准：

1. 工作区首页支持多选模式。
2. 用户可对多仓库执行批量 Fetch / Pull / 刷新状态 / 刷新远端摘要。
3. 批量动作执行后，相关状态和摘要能回写到当前工作区。

交付物：

1. 多选模式与批量动作入口。
2. 批量 Git 动作执行链路。
3. 批量远端摘要刷新链路。

### 40.5 M5：离线缓存与摘要持久化可用

完成标准：

1. 工作区进入时可优先加载历史远端摘要缓存。
2. 首页可展示最近一次摘要更新时间。
3. 远端摘要刷新后会自动落盘，弱网或离线时可继续展示已有结果。

交付物：

1. 工作区远端摘要本地缓存文件。
2. 最后更新时间展示。
3. 基于 TTL 与增量刷新的缓存策略。

### 40.6 当前验收结论

截至当前版本，GitHub 工作区本轮主线已经按 `M1 -> M5` 连续落地：

1. `M1` 已完成：工作区首页、仓库工作台主链路、异常聚合、推荐动作、过滤搜索与对象级直达已形成闭环。
2. `M2` 已完成：全局搜索与任务中心已接入，并支持跨仓库聚合和对象级跳转。
3. `M3` 已完成：下载中心已支持本地持久化、单条删除与清空历史。
4. `M4` 已完成：工作区已支持多选与批量 Fetch / Pull / 刷新状态 / 刷新远端摘要。
5. `M5` 已完成：远端摘要已支持离线缓存、最后更新时间展示与刷新后自动落盘。

同时需要明确：

1. 以上“已完成”指的是本轮工作区主线闭环已经完成，不代表整份长期规划中的所有增强项都已结束。
2. 文档中 `P2 / P3` 的增强方向，例如更强的 PR / workflow 深度联动、更完整的批量命令中心、高级日志分析、深度离线资料中心，仍属于后续增量路线，而不是当前版本的未收尾 Bug。
3. 后续继续开发时，应优先把剩余增强项单独归档为“后续阶段”，不要再与本轮 `M1 -> M5` 验收口径混用。

## 41. 每轮开发的落地准则

这一节用于约束后续每一轮实际开发，避免又回到“做了很多改动，但规划没有向前”的状态。

1. 每一轮只围绕一个明确能力目标推进，例如“首页异常聚合”或“下载持久化”，而不是同时改多个方向。
2. 每一轮结束后都要回写这份规划文档，记录：
   - 做了什么
   - 解决了什么问题
   - 剩余什么边界
3. 若某轮开发没有显著提升产品能力，只做了结构性整理，则应重新评估是否值得继续。
4. 若某个新功能需要先补局部 helper，允许补，但应明确写明它服务的功能目标，而不是只写“进一步拆分”。
5. 后续评估收益时，优先看：
   - 工作区层是否更快发现问题
   - 仓库层是否更快完成动作
   - 对象层是否更容易回跳与联动
   - 下载和日志是否更容易复用与回看

- 进度 49: 工作区首页支持过滤切换（全部/异常优先/本地有改动/有开放事项/仅 GitHub），提升大列表下的聚焦效率。
- 进度 50: 首页对象级直达与推荐动作收口，覆盖 Workflow Run / Issue / PR 详情。
- 进度 51: 工作区首页支持仓库搜索功能，可按名称、路径或分支关键字快速定位仓库。
- 进度 52: 实现工作区全局搜索功能 (M2 阶段)，支持跨仓库搜索 Issue、PR 和文件，并支持对象级直达跳转。
- 进度 53: 实现工作区全局任务中心 (M2 阶段)，聚合所有仓库的失败工作流、Git 冲突、落后提交和开放事项。
- 进度 54: 实现工作区下载中心持久化 (M3 阶段)，支持本地记录存储、单条删除与一键清空历史。
- 进度 55: 实现工作区批量操作功能 (M4 阶段)，支持跨仓库多选并执行批量 Fetch/Pull/刷新状态。
- 进度 56: 实现工作区离线缓存与摘要持久化 (M5 阶段)，支持本地加载历史摘要并展示最后更新时间，提升离线及弱网体验。

## 42. P0 / M1 具体实施清单

这一节把“下一轮直接要做什么”收敛成可执行清单，避免再次从宏观目标跳回零散讨论。

### 42.1 任务包 A：远端摘要策略定稿

目标：

- 让工作区首页的远端摘要既足够新鲜，又不会无意义频繁刷新。

本任务包需要完成：

1. 明确工作区远端摘要的触发来源：
   - 首次进入工作区
   - 手动刷新
   - 回到前台后的轻量检查
   - 仓库集合变化
   - token / API 地址变化
2. 明确哪些情况只做增量刷新，哪些情况需要整体失效。
3. 明确刷新失败后的 UI 反馈口径：
   - 全局错误
   - 单仓库错误
   - 权限错误
   - 网络错误
4. 明确“正在加载”和“已有缓存但后台刷新中”两种状态的展示差异。

完成标志：

1. 工作区页存在明确的手动刷新入口。
2. 恢复前台时不会无条件全量刷新。
3. 仓库卡片能稳定显示最近一次已知摘要，而不是频繁抖动成空状态。

### 42.2 任务包 B：工作区首页异常聚合

目标：

- 用户进入页面后，优先看到“哪里有问题”和“先处理什么”。

本任务包需要完成：

1. 定义至少 3 类首页异常来源：
   - 失败 workflow
   - 本地脏工作区
   - 分支落后远端
2. 定义至少 2 类待处理来源：
   - 打开的 PR / Issue
   - 待 review PR
3. 明确异常与待处理卡片的排序优先级。
4. 明确点击后跳转到哪里：
   - 工作区仓库工作台
   - 对应 tab
   - 对应详情对象

完成标志：

1. 首页可以在首屏直接呈现异常摘要。
2. 至少有一类异常支持“一次点击进入对应仓库上下文”。
3. 异常和待处理卡片的文案足够短，不把首页重新做成长列表。

### 42.3 任务包 C：仓库卡片推荐动作

目标：

- 仓库卡片不只显示状态，还能告诉用户下一步应该做什么。

本任务包需要完成：

1. 定义推荐动作输入信号：
   - 本地是否脏
   - ahead / behind
   - 是否存在失败 workflow
   - 是否有待 review PR
   - 是否有开放 Issue / PR
2. 为每张仓库卡片最多生成 3 个动作。
3. 区分主动作和次动作，避免动作过多。
4. 明确动作落点是：
   - 进入工作台
   - 打开工作流
   - 打开协作详情
   - 执行刷新

完成标志：

1. 至少一种仓库异常状态会触发推荐动作。
2. 推荐动作不会和仓库卡片摘要信息互相打架。
3. 推荐动作优先帮助定位问题，而不是堆叠所有可操作项。

### 42.4 任务包 D：工作区首页交互收口

目标：

- 把“打开工作区 -> 看首页 -> 进入仓库工作台”的体验打磨到稳定可用。

本任务包需要完成：

1. 明确首页骨架、空状态、错误状态、正常状态四种展示层级。
2. 明确仓库卡片点击后的默认落点和回退路径。
3. 明确首页与下载中心、仓库工作台的导航关系。
4. 明确顶部全局操作的最小保留集：
   - 刷新
   - 搜索入口占位或按钮
   - 筛选入口占位或按钮

完成标志：

1. 用户可以在 1 次点击内从首页进入目标仓库工作台。
2. 用户可以在仓库工作台稳定返回首页。
3. 页面层级不会因为异常、空状态或刷新过程而迷失。

## 43. P0 / M1 验收场景样例

这一节把后续完成验证时要跑的典型用户场景写清楚，避免只看代码结构或只看单点 UI。

### 43.1 场景一：工作区里同时存在多种仓库状态

前置条件：

- 工作区内至少有 4 类项目：
  - 正常 GitHub 仓库
  - 非 Git 项目
  - Git 仓库但远端不是 GitHub
  - GitHub 仓库但 token 缺失或无权限

预期：

1. 首页能区分这些仓库状态，而不是全部落成统一错误。
2. 正常 GitHub 仓库能显示基础远端摘要。
3. 非 Git 或非 GitHub 远端仓库能展示受限说明，但仍保留可理解的本地信息。

### 43.2 场景二：存在失败 workflow

前置条件：

- 至少一个仓库存在最近失败的 workflow。

预期：

1. 首页异常区域能够突出这类仓库。
2. 点击后能直接进入该仓库工作台或对应 workflow 上下文。
3. 用户不需要再次手动搜索该仓库或再翻长列表。

### 43.3 场景三：本地脏工作区与远端异常同时存在

前置条件：

- 某个仓库本地有未提交修改，同时远端存在失败 workflow 或分支落后。

预期：

1. 仓库卡片不会只显示单一问题而吞掉另一个关键状态。
2. 推荐动作会优先给出最值得先做的动作。
3. 用户进入工作台后，仍能看见本地与远端的双重状态。

### 43.4 场景四：切回前台后的轻量刷新

前置条件：

- 用户进入工作区页后切到后台，再返回前台。

预期：

1. 不应每次都出现明显的全量清空再加载。
2. 若缓存仍有效，应优先展示旧摘要并在必要时后台更新。
3. 若存在变更或缓存过期，应只刷新需要变更的仓库摘要。

## 44. 下一轮开发决策口径

为了减少“开工前还要再讨论一遍”的情况，这里提前写明下一轮开发时的取舍原则。

### 44.1 应优先接受的改动

1. 能直接提升首页问题发现效率的改动。
2. 能稳定首页到仓库工作台路径的改动。
3. 能让远端摘要刷新更可解释、更稳定的改动。
4. 为搜索、任务中心预留必要状态入口的轻量改动。

### 44.2 应尽量避免的改动

1. 只让文件数量变多、但没有明显产品收益的结构性拆分。
2. 同时改首页、工作台、下载中心三条主线的大杂烩提交。
3. 为未来功能过度预抽象、过早引入复杂状态层。
4. 把首页异常视图做成新的长列表，重新造成信息堆叠。

### 44.3 如果必须补结构整理

仅在以下情况允许顺手补：

1. 某个功能实现需要复用明显重复的逻辑。
2. 某个主文件片段已经阻碍修改和验证。
3. 整理范围能严格限制在当前目标功能周边。

补整理时的要求：

1. 必须说明它服务的是哪个具体功能目标。
2. 不能把本轮重点从产品能力推进变成结构重构。

## 45. 规划基线结论

到这一版为止，这份规划已经可以作为后续 GitHub 工作区推进的基线文档使用。

基线结论如下：

1. 当前阶段正式停止把“继续拆分主文件”作为主目标。
2. 后续主线按 `P0 -> P1 -> P2 -> P3` 推进。
3. 下一轮默认直接进入 `P0 / M1`，先做工作区首页与远端摘要策略。
4. 每一轮开发都要以“是否明显提升工作区产品能力”为首要评估标准。

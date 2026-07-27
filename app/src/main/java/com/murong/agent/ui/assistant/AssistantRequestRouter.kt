package com.murong.agent.ui.assistant

/**
 * A small, deterministic first-pass router for assistant speech.
 *
 * This runs before any LLM and, critically, before screen text or a screenshot is attached.
 * It is intentionally conservative: uncertain requests fall back to the normal configured model.
 */
internal enum class AssistantTaskKind {
    INSTANT_LOCAL,
    FAST_LOCAL_CHAT,
    FAST_LOCAL_WEB,
    BACKGROUND_WEB_RESEARCH,
    BACKGROUND_CODE,
    PHONE_FOREGROUND,
    SCREEN_AWARE,
    MAIN_MODEL,
}

internal data class AssistantRequestRoute(
    val kind: AssistantTaskKind,
    val requiresScreenContext: Boolean = false,
    val runWithNotification: Boolean = false,
    val mayTakeOverScreen: Boolean = false,
    val label: String,
)

internal object AssistantRequestRouter {
    private val screenReferences = listOf(
        "看屏幕", "看看屏幕", "当前屏幕", "屏幕上", "这个页面", "当前页面", "这个界面",
        "当前界面", "截图里", "截图中", "图片里", "图里", "眼前", "我正在看的",
        "这个按钮", "这里显示", "页面上的", "识别屏幕", "读取屏幕", "识别文字",
        "文字识别", "图片识别", "识图", "圈选", "这个区域", "选中这块",
    )
    private val foodComparisonTerms = listOf(
        "外卖比价", "比价外卖", "哪家外卖便宜", "最低外卖", "外卖最低价",
        "美团和饿了么", "饿了么和美团", "比较外卖", "比一比外卖",
    )
    private val codeAndProcessTerms = listOf(
        "写代码", "改代码", "修代码", "修复代码", "编译", "构建项目", "运行测试",
        "项目源码", "代码仓库", "git ", "github", "进程管理", "管理进程", "杀进程",
        "查进程", "终端执行", "shell", "脚本", "python", "kotlin", "java",
    )
    private val phoneActionTerms = listOf(
        "打开抖音", "打开微信", "打开支付宝", "打开淘宝", "打开京东", "打开美团",
        "打开饿了么", "帮我打开", "替我打开", "点击", "滑动", "往下翻", "输入到",
        "在手机上", "操作手机", "操作屏幕", "自动操作", "帮我点", "替我点",
    )
    private val webFreshnessTerms = listOf(
        "最新资讯", "最新消息", "最新新闻", "今天新闻", "今日新闻", "搜一下", "搜索一下",
        "查一下", "查询一下", "查新闻", "查资讯", "新闻", "资讯", "时事", "头条",
        "今日热点", "天气", "气温", "会不会下雨", "空气质量", "热搜",
    )
    private val alarmTerms = listOf("闹钟", "叫醒我", "提醒我起床")
    private val timerTerms = listOf(
        "计时", "倒计时", "定时器", "秒后", "分钟后", "小时后",
    )
    private val calendarTerms = listOf(
        "日程", "日历事件", "添加事件", "创建事件", "新建事件", "安排会议", "行程",
    )
    private val dateTimeTerms = listOf(
        "几点了", "现在几点", "当前时间", "现在时间", "今天时间", "今天几号",
        "今天日期", "当前日期", "今天星期", "星期几", "日期和时间", "查时间",
        "查询时间", "时间是多少", "什么时间",
    )
    private val greetingTerms = setOf(
        "你好", "您好", "嗨", "hi", "hello", "哈喽", "在吗", "慕容你好", "你好慕容",
    )
    private val complexTerms = listOf(
        "深入分析", "详细分析", "研究一下", "制定方案", "完整方案", "长文", "论文",
        "法律", "诊断", "投资建议", "架构设计", "复杂推理", "咨询", "请教",
    )
    private val creativeWritingTerms = listOf(
        "写小说", "短篇小说", "短片小说", "写故事", "编故事", "写文案", "写剧本",
        "续写", "创作一篇", "创作一个",
    )

    fun classify(rawText: String): AssistantRequestRoute {
        val text = rawText.trim()
        val normalized = text.lowercase()
        return when {
            foodComparisonTerms.any(normalized::contains) -> AssistantRequestRoute(
                kind = AssistantTaskKind.BACKGROUND_WEB_RESEARCH,
                runWithNotification = true,
                label = "后台公开信息比价",
            )

            codeAndProcessTerms.any(normalized::contains) -> AssistantRequestRoute(
                kind = AssistantTaskKind.BACKGROUND_CODE,
                runWithNotification = true,
                label = "后台代码/系统任务",
            )

            phoneActionTerms.any(normalized::contains) ||
                normalized.startsWith("打开") ||
                normalized.startsWith("启动") -> AssistantRequestRoute(
                kind = AssistantTaskKind.PHONE_FOREGROUND,
                runWithNotification = true,
                mayTakeOverScreen = true,
                label = "前台手机操作",
            )

            screenReferences.any(normalized::contains) -> AssistantRequestRoute(
                kind = AssistantTaskKind.SCREEN_AWARE,
                requiresScreenContext = true,
                label = "按需读取屏幕",
            )

            calendarTerms.any(normalized::contains) -> AssistantRequestRoute(
                kind = AssistantTaskKind.INSTANT_LOCAL,
                label = "本地日历",
            )

            alarmTerms.any(normalized::contains) ||
                timerTerms.any(normalized::contains) ||
                dateTimeTerms.any(normalized::contains) ->
                AssistantRequestRoute(
                    kind = AssistantTaskKind.INSTANT_LOCAL,
                    label = "本地系统指令",
                )

            normalized.removePunctuationForIntent() in greetingTerms -> AssistantRequestRoute(
                kind = AssistantTaskKind.INSTANT_LOCAL,
                label = "本地即时响应",
            )

            webFreshnessTerms.any(normalized::contains) -> AssistantRequestRoute(
                kind = AssistantTaskKind.FAST_LOCAL_WEB,
                label = "联网检索 + 轻量本地总结",
            )

            creativeWritingTerms.any(normalized::contains) -> AssistantRequestRoute(
                kind = AssistantTaskKind.MAIN_MODEL,
                label = "主模型创作",
            )

            text.length <= FAST_CHAT_MAX_CHARS && complexTerms.none(normalized::contains) ->
                AssistantRequestRoute(
                    kind = AssistantTaskKind.FAST_LOCAL_CHAT,
                    label = "轻量本地对话",
                )

            else -> AssistantRequestRoute(
                kind = AssistantTaskKind.MAIN_MODEL,
                label = "主模型",
            )
        }
    }

    fun backgroundModelInput(userText: String, route: AssistantRequestRoute): String = when (
        route.kind
    ) {
        AssistantTaskKind.BACKGROUND_WEB_RESEARCH -> """
            请把下面的需求作为后台公开信息检索任务执行。
            只使用网页搜索、公开网页或公开 API，不要启动、点击或控制任何手机 App。
            如果登录态、会员券、收货地址或 App 内实时价格不可从公开信息取得，请明确说明，
            并把需要用户允许前台接管后才能继续的步骤列出来，不得臆造最低价。

            用户需求：${userText.trim()}
        """.trimIndent()

        else -> userText.trim()
    }

    private fun String.removePunctuationForIntent(): String =
        filter { it.isLetterOrDigit() }

    private const val FAST_CHAT_MAX_CHARS = 120
}

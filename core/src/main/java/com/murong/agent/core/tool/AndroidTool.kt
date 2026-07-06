package com.murong.agent.core.tool

import com.murong.agent.common.shell.KeepShellPublic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Android 设备管理工具。
 *
 * 目标是把常见的应用/进程/日志/系统查询动作结构化，避免每次都手写长 shell。
 */
class AndroidTool : Tool {

    override val name: String = "android"
    override val description: String =
        "Android 设备管理工具。优先用它做高频安卓操作：读取 logcat、查询应用信息、安装或启动应用、查看进程、读取 dumpsys、检查通知状态、发送 intent，以及做基础 UI 自动化（查看当前焦点、导出界面树、截图、点击、滑动、输入和按键）。只在这个工具覆盖不了的场景下再退回 shell。"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "action" to mapOf(
                "type" to "string",
                "enum" to SUPPORTED_ACTIONS,
                "description" to "要执行的 Android 动作"
            ),
            "packageName" to mapOf(
                "type" to "string",
                "description" to "应用包名，如 com.example.app"
            ),
            "activity" to mapOf(
                "type" to "string",
                "description" to "Activity 名称。可传完整组件，或只传 .MainActivity 这种相对类名"
            ),
            "component" to mapOf(
                "type" to "string",
                "description" to "完整组件名 package/class，如 com.example/.MainActivity"
            ),
            "permission" to mapOf(
                "type" to "string",
                "description" to "Android 权限名，如 android.permission.POST_NOTIFICATIONS"
            ),
            "apkPath" to mapOf(
                "type" to "string",
                "description" to "设备上的 APK 文件路径，如 /sdcard/Download/app-debug.apk"
            ),
            "pid" to mapOf(
                "type" to "integer",
                "description" to "目标进程 PID"
            ),
            "signal" to mapOf(
                "type" to "string",
                "description" to "kill 信号，默认 TERM，可选 KILL/INT/HUP/QUIT"
            ),
            "scope" to mapOf(
                "type" to "string",
                "enum" to listOf("all", "user", "system"),
                "description" to "app_list 的范围，默认 all"
            ),
            "keyword" to mapOf(
                "type" to "string",
                "description" to "按关键词过滤应用名、进程行或输出内容"
            ),
            "service" to mapOf(
                "type" to "string",
                "description" to "dumpsys 服务名，如 activity/package/window/battery/meminfo"
            ),
            "target" to mapOf(
                "type" to "string",
                "description" to "dumpsys 的目标对象，如包名、PID 或额外目标标识"
            ),
            "lines" to mapOf(
                "type" to "integer",
                "description" to "最多返回多少行。logcat 默认 200，process/dumpsys 默认 120；follow 模式下表示最多保留多少条匹配日志"
            ),
            "offset" to mapOf(
                "type" to "integer",
                "description" to "app_list 的起始偏移，默认 0"
            ),
            "tag" to mapOf(
                "type" to "string",
                "description" to "logcat 标签过滤，如 ActivityManager"
            ),
            "priority" to mapOf(
                "type" to "string",
                "enum" to listOf("V", "D", "I", "W", "E", "F"),
                "description" to "logcat 最低优先级，默认 V"
            ),
            "since" to mapOf(
                "type" to "string",
                "description" to "logcat 起始时间，透传给 logcat -T，如 '09-30 12:34:56.000'、'5m' 或最近条数"
            ),
            "follow" to mapOf(
                "type" to "boolean",
                "description" to "logcat_read 是否进入限时跟随模式；true 时会持续观察直到 timeout"
            ),
            "grep" to mapOf(
                "type" to "string",
                "description" to "日志、dumpsys 或进程输出中的关键词过滤"
            ),
            "actionName" to mapOf(
                "type" to "string",
                "description" to "Intent action，如 android.intent.action.VIEW"
            ),
            "dataUri" to mapOf(
                "type" to "string",
                "description" to "Intent data URI"
            ),
            "mimeType" to mapOf(
                "type" to "string",
                "description" to "Intent MIME Type"
            ),
            "categories" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string"),
                "description" to "Intent categories"
            ),
            "extras" to mapOf(
                "type" to "object",
                "additionalProperties" to mapOf("type" to "string"),
                "description" to "Intent string extras，键值都按字符串处理"
            ),
            "timeout" to mapOf(
                "type" to "integer",
                "description" to "命令超时时间（秒），默认 12"
            ),
            "x" to mapOf(
                "type" to "integer",
                "description" to "UI 坐标 X，tap / swipe 需要"
            ),
            "y" to mapOf(
                "type" to "integer",
                "description" to "UI 坐标 Y，tap / swipe 需要"
            ),
            "xPercent" to mapOf(
                "type" to "number",
                "description" to "百分比坐标 X，支持 0.0-1.0 或 0-100"
            ),
            "yPercent" to mapOf(
                "type" to "number",
                "description" to "百分比坐标 Y，支持 0.0-1.0 或 0-100"
            ),
            "x2Percent" to mapOf(
                "type" to "number",
                "description" to "百分比终点坐标 X，支持 0.0-1.0 或 0-100"
            ),
            "y2Percent" to mapOf(
                "type" to "number",
                "description" to "百分比终点坐标 Y，支持 0.0-1.0 或 0-100"
            ),
            "x2" to mapOf(
                "type" to "integer",
                "description" to "swipe 终点坐标 X"
            ),
            "y2" to mapOf(
                "type" to "integer",
                "description" to "swipe 终点坐标 Y"
            ),
            "durationMs" to mapOf(
                "type" to "integer",
                "description" to "swipe 时长，单位毫秒，默认 300"
            ),
            "text" to mapOf(
                "type" to "string",
                "description" to "要输入的文本，ui_text 需要"
            ),
            "nextText" to mapOf(
                "type" to "string",
                "description" to "ui_wait_gone_then_click_first 后续点击目标的 text 过滤"
            ),
            "keyCode" to mapOf(
                "type" to "string",
                "description" to "按键名或 keycode，如 BACK、ENTER、3、KEYCODE_HOME"
            ),
            "outputPath" to mapOf(
                "type" to "string",
                "description" to "ui_dump 或 ui_screenshot 的输出路径；默认写到 /data/local/tmp"
            ),
            "resourceId" to mapOf(
                "type" to "string",
                "description" to "节点的 resource-id 过滤，如 com.example:id/login"
            ),
            "nextResourceId" to mapOf(
                "type" to "string",
                "description" to "ui_wait_gone_then_click_first 后续点击目标的 resource-id 过滤"
            ),
            "contentDesc" to mapOf(
                "type" to "string",
                "description" to "节点的 content-desc 过滤"
            ),
            "nextContentDesc" to mapOf(
                "type" to "string",
                "description" to "ui_wait_gone_then_click_first 后续点击目标的 content-desc 过滤"
            ),
            "className" to mapOf(
                "type" to "string",
                "description" to "节点 class 名过滤，如 android.widget.Button"
            ),
            "nextClassName" to mapOf(
                "type" to "string",
                "description" to "ui_wait_gone_then_click_first 后续点击目标的 class 名过滤"
            ),
            "nextKeyword" to mapOf(
                "type" to "string",
                "description" to "ui_wait_gone_then_click_first 后续点击目标的关键词过滤"
            ),
            "clickable" to mapOf(
                "type" to "boolean",
                "description" to "是否只匹配 clickable 节点"
            ),
            "nextClickable" to mapOf(
                "type" to "boolean",
                "description" to "ui_wait_gone_then_click_first 后续点击目标是否只匹配 clickable 节点"
            ),
            "enabled" to mapOf(
                "type" to "boolean",
                "description" to "是否只匹配 enabled 节点"
            ),
            "nextEnabled" to mapOf(
                "type" to "boolean",
                "description" to "ui_wait_gone_then_click_first 后续点击目标是否只匹配 enabled 节点"
            ),
            "index" to mapOf(
                "type" to "integer",
                "description" to "从匹配结果中选择第几个节点，默认 0"
            ),
            "namespace" to mapOf(
                "type" to "string",
                "enum" to listOf("system", "secure", "global"),
                "description" to "settings 命名空间"
            ),
            "name" to mapOf(
                "type" to "string",
                "description" to "settings 键名"
            ),
            "value" to mapOf(
                "type" to "string",
                "description" to "settings_put 要写入的值"
            ),
            "reinstall" to mapOf(
                "type" to "boolean",
                "description" to "app_install 是否允许覆盖安装，默认 true"
            ),
            "grantRuntimePermissions" to mapOf(
                "type" to "boolean",
                "description" to "app_install 是否在安装时自动授予运行时权限，默认 false"
            ),
            "allowDowngrade" to mapOf(
                "type" to "boolean",
                "description" to "app_install 是否允许降级安装，默认 false"
            ),
            "keepData" to mapOf(
                "type" to "boolean",
                "description" to "app_uninstall 是否保留应用数据与缓存，默认 false"
            ),
            "direction" to mapOf(
                "type" to "string",
                "enum" to listOf("down", "up"),
                "description" to "ui_scroll_find 的滚动方向，默认 down"
            ),
            "maxScrolls" to mapOf(
                "type" to "integer",
                "description" to "ui_scroll_find 最多滚动次数，默认 6"
            ),
            "clearSteps" to mapOf(
                "type" to "integer",
                "description" to "ui_set_text_clear_node 额外执行多少次删除键；节点文本为空时可显式指定"
            ),
            "longPressDurationMs" to mapOf(
                "type" to "integer",
                "description" to "ui_long_press_node 的长按时长，默认 800ms"
            )
        ),
        "required" to listOf("action")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            when (val action = obj.string("action")) {
                "logcat_clear" -> approval(
                    summary = "清空 logcat 缓冲区",
                    detail = "logcat -c",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "app_start" -> approval(
                    summary = "启动应用",
                    detail = obj.string("component")
                        ?: obj.string("packageName")
                        ?: obj.string("activity")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "app_force_stop" -> approval(
                    summary = "强制停止应用",
                    detail = obj.string("packageName") ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "app_clear_data" -> approval(
                    summary = "清除应用数据",
                    detail = obj.string("packageName") ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "app_install" -> approval(
                    summary = "安装 APK",
                    detail = obj.string("apkPath") ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "app_uninstall" -> approval(
                    summary = "卸载应用",
                    detail = obj.string("packageName") ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "app_enable" -> approval(
                    summary = "启用应用",
                    detail = obj.string("packageName") ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "app_disable" -> approval(
                    summary = "停用应用",
                    detail = obj.string("packageName") ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "notification_open_settings" -> approval(
                    summary = "打开应用通知设置",
                    detail = obj.string("packageName") ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "app_open_settings" -> approval(
                    summary = "打开应用设置页",
                    detail = obj.string("packageName") ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "app_open_permission_settings" -> approval(
                    summary = "打开应用权限页",
                    detail = buildString {
                        append(obj.string("packageName") ?: action)
                        obj.string("permission")?.let {
                            append(' ')
                            append(it)
                        }
                    },
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "app_open_battery_settings" -> approval(
                    summary = "打开电池优化设置",
                    detail = obj.string("packageName") ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "app_open_overlay_settings" -> approval(
                    summary = "打开悬浮窗设置",
                    detail = obj.string("packageName") ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "app_open_unknown_sources_settings" -> approval(
                    summary = "打开未知来源安装设置",
                    detail = obj.string("packageName") ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "app_open_notification_listener_settings" -> approval(
                    summary = "打开通知监听设置",
                    detail = obj.string("packageName") ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "app_open_accessibility_settings" -> approval(
                    summary = "打开无障碍设置",
                    detail = obj.string("packageName") ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "app_open_usage_access_settings" -> approval(
                    summary = "打开使用情况访问设置",
                    detail = obj.string("packageName") ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "app_grant" -> approval(
                    summary = "授予应用权限",
                    detail = "${obj.string("packageName").orEmpty()} ${obj.string("permission").orEmpty()}".trim(),
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "app_revoke" -> approval(
                    summary = "撤销应用权限",
                    detail = "${obj.string("packageName").orEmpty()} ${obj.string("permission").orEmpty()}".trim(),
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "process_kill" -> approval(
                    summary = "结束进程",
                    detail = obj.string("pid")
                        ?: obj.string("packageName")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "dumpsys_activity" -> approval(
                    summary = "读取 activity 摘要",
                    detail = obj.string("target")
                        ?: obj.string("packageName")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "dumpsys_window" -> approval(
                    summary = "读取 window 摘要",
                    detail = obj.string("target") ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "dumpsys_package" -> approval(
                    summary = "读取 package 摘要",
                    detail = obj.string("packageName")
                        ?: obj.string("target")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "dumpsys_battery" -> approval(
                    summary = "读取 battery 摘要",
                    detail = action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "dumpsys_meminfo" -> approval(
                    summary = "读取 meminfo 摘要",
                    detail = obj.string("packageName")
                        ?: obj.string("pid")
                        ?: obj.string("target")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "dumpsys_input" -> approval(
                    summary = "读取 input 摘要",
                    detail = action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "dumpsys_display" -> approval(
                    summary = "读取 display 摘要",
                    detail = action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "settings_put" -> approval(
                    summary = "修改系统设置",
                    detail = buildString {
                        append(obj.string("namespace") ?: "?")
                        append(' ')
                        append(obj.string("name") ?: "?")
                        append('=')
                        append(obj.string("value") ?: "?")
                    },
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "intent_start" -> approval(
                    summary = "启动 Intent",
                    detail = obj.string("component")
                        ?: obj.string("packageName")
                        ?: obj.string("actionName")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "intent_broadcast" -> approval(
                    summary = "发送广播",
                    detail = obj.string("component")
                        ?: obj.string("packageName")
                        ?: obj.string("actionName")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_tap" -> approval(
                    summary = "点击屏幕坐标",
                    detail = "${obj.int("x") ?: "?"}, ${obj.int("y") ?: "?"}",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_tap_percent" -> approval(
                    summary = "按百分比点击屏幕",
                    detail = "${obj.double("xPercent") ?: "?"}, ${obj.double("yPercent") ?: "?"}",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_swipe" -> approval(
                    summary = "执行屏幕滑动",
                    detail = "${obj.int("x") ?: "?"},${obj.int("y") ?: "?"} -> ${obj.int("x2") ?: "?"},${obj.int("y2") ?: "?"}",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_swipe_percent" -> approval(
                    summary = "按百分比执行屏幕滑动",
                    detail = "${obj.double("xPercent") ?: "?"},${obj.double("yPercent") ?: "?"} -> ${obj.double("x2Percent") ?: "?"},${obj.double("y2Percent") ?: "?"}",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_percent" -> approval(
                    summary = "按百分比执行屏幕滚动",
                    detail = "${obj.double("xPercent") ?: "?"},${obj.double("yPercent") ?: "?"} -> ${obj.double("x2Percent") ?: "?"},${obj.double("y2Percent") ?: "?"}",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_text" -> approval(
                    summary = "输入文本",
                    detail = obj.string("text")?.take(80) ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_keyevent" -> approval(
                    summary = "发送按键事件",
                    detail = obj.string("keyCode") ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_click_node" -> approval(
                    summary = "点击匹配到的界面节点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_click_first" -> approval(
                    summary = "点击第一个匹配节点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_long_press_node" -> approval(
                    summary = "长按匹配节点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_long_press" -> approval(
                    summary = "长按坐标",
                    detail = "${obj.int("x") ?: "?"},${obj.int("y") ?: "?"} @ ${obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS}ms",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_long_press_percent" -> approval(
                    summary = "按百分比长按",
                    detail = "${obj.double("xPercent") ?: "?"},${obj.double("yPercent") ?: "?"} @ ${obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS}ms",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_find" -> approval(
                    summary = "滚动查找界面节点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_find_percent" -> approval(
                    summary = "按百分比滚动查找界面节点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_click_first" -> approval(
                    summary = "滚动后点击首个节点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_click_first_percent" -> approval(
                    summary = "按百分比滚动后点击首个节点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_long_press_first" -> approval(
                    summary = "滚动后长按首个节点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_long_press_first_percent" -> approval(
                    summary = "按百分比滚动后长按首个节点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_set_text_node" -> approval(
                    summary = "聚焦节点后输入文本",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_set_text_clear_node" -> approval(
                    summary = "清空节点后输入文本",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_select_all_and_replace_node" -> approval(
                    summary = "全选节点文本并替换",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_scroll_select_all_and_replace_first" -> approval(
                    summary = "滚动后全选替换首个节点",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_scroll_select_all_and_replace_first_percent" -> approval(
                    summary = "按百分比滚动后全选替换首个节点",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_scroll_wait_and_click_first" -> approval(
                    summary = "滚动等待后点击首个节点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_wait_and_click_first_percent" -> approval(
                    summary = "按百分比滚动等待后点击首个节点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_wait_and_replace_first" -> approval(
                    summary = "滚动等待后全选替换首个节点",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_scroll_wait_and_replace_first_percent" -> approval(
                    summary = "按百分比滚动等待后全选替换首个节点",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_wait_and_click_first" -> approval(
                    summary = "等待后点击首个节点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_and_replace_first" -> approval(
                    summary = "等待后全选替换首个节点",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_wait_and_long_press_first" -> approval(
                    summary = "等待后长按首个节点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_wait_and_long_press_first" -> approval(
                    summary = "滚动等待后长按首个节点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_wait_and_long_press_first_percent" -> approval(
                    summary = "按百分比滚动等待后长按首个节点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_and_set_text_clear_first" -> approval(
                    summary = "等待后清空并输入首个节点",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_scroll_wait_and_set_text_clear_first" -> approval(
                    summary = "滚动等待后清空并输入首个节点",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_scroll_wait_and_set_text_clear_first_percent" -> approval(
                    summary = "按百分比滚动等待后清空并输入首个节点",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_wait_and_set_text_first" -> approval(
                    summary = "等待后输入首个节点",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_wait_and_tap_percent" -> approval(
                    summary = "等待后按百分比点击",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_and_swipe_percent" -> approval(
                    summary = "等待后按百分比滑动",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_and_scroll_percent" -> approval(
                    summary = "等待后按百分比滚动",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_and_find" -> approval(
                    summary = "等待后查找节点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "ui_wait_and_dump" -> approval(
                    summary = "等待后导出界面树",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "ui_wait_and_dump_summary" -> approval(
                    summary = "等待后读取界面摘要",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "ui_wait_and_current_focus" -> approval(
                    summary = "等待后读取当前焦点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "ui_wait_and_backstack_summary" -> approval(
                    summary = "等待后读取 Activity 栈摘要",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "ui_wait_and_screenshot" -> approval(
                    summary = "等待后截图",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "ui_wait_and_text" -> approval(
                    summary = "等待后输入文本",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_wait_and_keyevent" -> approval(
                    summary = "等待后发送按键事件",
                    detail = obj.string("keyCode")
                        ?: obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_and_tap" -> approval(
                    summary = "等待后点击坐标",
                    detail = "${obj.int("x") ?: "?"},${obj.int("y") ?: "?"}",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_and_swipe" -> approval(
                    summary = "等待后滑动坐标",
                    detail = "${obj.int("x") ?: "?"},${obj.int("y") ?: "?"} -> ${obj.int("x2") ?: "?"},${obj.int("y2") ?: "?"}",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_and_back" -> approval(
                    summary = "等待后返回",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_and_long_press" -> approval(
                    summary = "等待后长按坐标",
                    detail = "${obj.int("x") ?: "?"},${obj.int("y") ?: "?"} @ ${obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS}ms",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_and_long_press_percent" -> approval(
                    summary = "等待后按百分比长按",
                    detail = "${obj.double("xPercent") ?: "?"},${obj.double("yPercent") ?: "?"} @ ${obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS}ms",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_wait_and_tap_percent" -> approval(
                    summary = "滚动等待后按百分比点击",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_wait_and_swipe_percent" -> approval(
                    summary = "滚动等待后按百分比滑动",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_wait_and_scroll_percent" -> approval(
                    summary = "滚动等待后按百分比滚动",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_wait_and_set_text_first" -> approval(
                    summary = "滚动等待后输入首个节点",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_scroll_wait_and_set_text_first_percent" -> approval(
                    summary = "按百分比滚动等待后输入首个节点",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_scroll_wait_and_find" -> approval(
                    summary = "滚动等待后查找节点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "ui_scroll_wait_and_find_percent" -> approval(
                    summary = "按百分比滚动等待后查找节点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "ui_scroll_wait_and_dump_percent" -> approval(
                    summary = "按百分比滚动等待后导出界面树",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "ui_scroll_wait_and_dump" -> approval(
                    summary = "滚动等待后导出界面树",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "ui_scroll_wait_and_dump_summary" -> approval(
                    summary = "滚动等待后读取界面摘要",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "ui_scroll_wait_and_dump_summary_percent" -> approval(
                    summary = "按百分比滚动等待后读取界面摘要",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "ui_scroll_wait_and_current_focus" -> approval(
                    summary = "滚动等待后读取当前焦点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "ui_scroll_wait_and_current_focus_percent" -> approval(
                    summary = "按百分比滚动等待后读取当前焦点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "ui_scroll_wait_and_backstack_summary" -> approval(
                    summary = "滚动等待后读取 Activity 栈摘要",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "ui_scroll_wait_and_backstack_summary_percent" -> approval(
                    summary = "按百分比滚动等待后读取 Activity 栈摘要",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "ui_scroll_wait_and_screenshot" -> approval(
                    summary = "滚动等待后截图",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "ui_scroll_wait_and_screenshot_percent" -> approval(
                    summary = "按百分比滚动等待后截图",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.LOW
                )
                "ui_scroll_wait_and_text" -> approval(
                    summary = "滚动等待后输入文本",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_scroll_wait_and_text_percent" -> approval(
                    summary = "按百分比滚动等待后输入文本",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_scroll_wait_and_keyevent" -> approval(
                    summary = "滚动等待后发送按键事件",
                    detail = obj.string("keyCode")
                        ?: obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_wait_and_keyevent_percent" -> approval(
                    summary = "按百分比滚动等待后发送按键事件",
                    detail = obj.string("keyCode")
                        ?: obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_wait_and_tap" -> approval(
                    summary = "滚动等待后点击坐标",
                    detail = "${obj.int("x") ?: "?"},${obj.int("y") ?: "?"}",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_wait_and_swipe" -> approval(
                    summary = "滚动等待后滑动坐标",
                    detail = "${obj.int("x") ?: "?"},${obj.int("y") ?: "?"} -> ${obj.int("x2") ?: "?"},${obj.int("y2") ?: "?"}",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_wait_and_back" -> approval(
                    summary = "滚动等待后返回",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_wait_and_back_percent" -> approval(
                    summary = "按百分比滚动等待后返回",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_wait_and_long_press" -> approval(
                    summary = "滚动等待后长按坐标",
                    detail = "${obj.int("x") ?: "?"},${obj.int("y") ?: "?"} @ ${obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS}ms",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_wait_and_long_press_percent" -> approval(
                    summary = "滚动等待后按百分比长按",
                    detail = "${obj.double("xPercent") ?: "?"},${obj.double("yPercent") ?: "?"} @ ${obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS}ms",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_scroll_set_text_clear_first" -> approval(
                    summary = "滚动后清空并输入首个节点",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_scroll_set_text_clear_first_percent" -> approval(
                    summary = "按百分比滚动后清空并输入首个节点",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_scroll_set_text_first" -> approval(
                    summary = "滚动后输入首个节点",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_scroll_set_text_first_percent" -> approval(
                    summary = "按百分比滚动后输入首个节点",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_wait_gone_then_click_first" -> approval(
                    summary = "等待目标消失后点击节点",
                    detail = obj.string("nextResourceId")
                        ?: obj.string("nextText")
                        ?: obj.string("nextContentDesc")
                        ?: obj.string("nextClassName")
                        ?: obj.string("nextKeyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_gone_then_back" -> approval(
                    summary = "等待目标消失后返回",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_gone_then_set_text_first" -> approval(
                    summary = "等待目标消失后输入节点",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("nextResourceId")
                        ?: obj.string("nextText")
                        ?: obj.string("nextContentDesc")
                        ?: obj.string("nextClassName")
                        ?: obj.string("nextKeyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_wait_gone_then_set_text_clear_first" -> approval(
                    summary = "等待目标消失后清空并输入节点",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("nextResourceId")
                        ?: obj.string("nextText")
                        ?: obj.string("nextContentDesc")
                        ?: obj.string("nextClassName")
                        ?: obj.string("nextKeyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_wait_gone_then_long_press_first" -> approval(
                    summary = "等待目标消失后长按节点",
                    detail = obj.string("nextResourceId")
                        ?: obj.string("nextText")
                        ?: obj.string("nextContentDesc")
                        ?: obj.string("nextClassName")
                        ?: obj.string("nextKeyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_gone_then_replace_first" -> approval(
                    summary = "等待目标消失后全选替换节点",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("nextResourceId")
                        ?: obj.string("nextText")
                        ?: obj.string("nextContentDesc")
                        ?: obj.string("nextClassName")
                        ?: obj.string("nextKeyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_wait_gone_then_keyevent" -> approval(
                    summary = "等待目标消失后发送按键",
                    detail = obj.string("keyCode")
                        ?: obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_gone_then_swipe" -> approval(
                    summary = "等待目标消失后执行滑动",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_gone_then_tap" -> approval(
                    summary = "等待目标消失后点击坐标",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.int("x")}, ${obj.int("y")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_gone_then_long_press" -> approval(
                    summary = "等待目标消失后长按坐标",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.int("x")}, ${obj.int("y")}) @ ${obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS}ms",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_gone_then_tap_percent" -> approval(
                    summary = "等待目标消失后按百分比点击",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_gone_then_swipe_percent" -> approval(
                    summary = "等待目标消失后按百分比滑动",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_gone_then_scroll_percent" -> approval(
                    summary = "等待目标消失后按百分比滚动",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) -> (${obj.double("x2Percent")}, ${obj.double("y2Percent")})",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_gone_then_long_press_percent" -> approval(
                    summary = "等待目标消失后按百分比长按",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: "(${obj.double("xPercent")}, ${obj.double("yPercent")}) @ ${obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS}ms",
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_gone_then_text" -> approval(
                    summary = "等待目标消失后输入文本",
                    detail = obj.string("text")?.take(80)
                        ?: obj.string("resourceId")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.HIGH
                )
                "ui_wait_gone_then_screenshot" -> approval(
                    summary = "等待目标消失后截图",
                    detail = obj.string("outputPath")
                        ?: obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_gone_then_dump" -> approval(
                    summary = "等待目标消失后导出界面树",
                    detail = obj.string("outputPath")
                        ?: obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_gone_then_dump_summary" -> approval(
                    summary = "等待目标消失后读取界面摘要",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_gone_then_current_focus" -> approval(
                    summary = "等待目标消失后读取当前焦点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_gone_then_backstack_summary" -> approval(
                    summary = "等待目标消失后读取 Activity 栈摘要",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                "ui_wait_gone_then_find" -> approval(
                    summary = "等待目标消失后查找节点",
                    detail = obj.string("resourceId")
                        ?: obj.string("text")
                        ?: obj.string("contentDesc")
                        ?: obj.string("className")
                        ?: obj.string("keyword")
                        ?: action,
                    rawArgs = args,
                    riskLevel = ApprovalRiskLevel.MEDIUM
                )
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun execute(args: String): String {
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val action = obj.string("action") ?: return "Error: 'action' parameter is required"
            if (!KeepShellPublic.checkRoot()) {
                return "Error: Root shell is not available. Please check root permissions."
            }
            val timeout = obj.int("timeout") ?: DEFAULT_TIMEOUT_SECONDS
            when (action) {
                "logcat_read" -> handleLogcatRead(obj, timeout)
                "logcat_clear" -> runAndFormat("已清空 logcat 缓冲区", "logcat -c", timeout)
                "app_list" -> handleAppList(obj, timeout)
                "app_path" -> handleAppPath(obj, timeout)
                "app_info" -> handleAppInfo(obj, timeout)
                "app_start" -> handleAppStart(obj, timeout)
                "app_force_stop" -> handleAppForceStop(obj, timeout)
                "app_clear_data" -> handleAppClearData(obj, timeout)
                "app_install" -> handleAppInstall(obj, timeout)
                "app_uninstall" -> handleAppUninstall(obj, timeout)
                "app_enable" -> handleAppEnableDisable(obj, timeout, disable = false)
                "app_disable" -> handleAppEnableDisable(obj, timeout, disable = true)
                "app_current_top" -> handleAppCurrentTop(timeout)
                "app_grant" -> handlePermissionMutation(obj, timeout, revoke = false)
                "app_revoke" -> handlePermissionMutation(obj, timeout, revoke = true)
                "process_list" -> handleProcessList(obj, timeout)
                "process_by_package" -> handleProcessByPackage(obj, timeout)
                "process_meminfo" -> handleProcessMeminfo(obj, timeout)
                "process_thread_summary" -> handleProcessThreadSummary(obj, timeout)
                "process_kill" -> handleProcessKill(obj, timeout)
                "dumpsys" -> handleDumpsys(obj, timeout)
                "dumpsys_activity" -> handleDumpsysActivity(obj, timeout)
                "dumpsys_window" -> handleDumpsysWindow(obj, timeout)
                "dumpsys_package" -> handleDumpsysPackage(obj, timeout)
                "dumpsys_battery" -> handleDumpsysBattery(obj, timeout)
                "dumpsys_meminfo" -> handleDumpsysMeminfo(obj, timeout)
                "dumpsys_input" -> handleDumpsysInput(obj, timeout)
                "dumpsys_display" -> handleDumpsysDisplay(obj, timeout)
                "settings_get" -> handleSettingsGet(obj, timeout)
                "settings_put" -> handleSettingsPut(obj, timeout)
                "settings_list" -> handleSettingsList(obj, timeout)
                "network_summary" -> handleNetworkSummary(obj, timeout)
                "notification_summary" -> handleNotificationSummary(obj, timeout)
                "notification_channels" -> handleNotificationChannels(obj, timeout)
                "notification_permission_status" -> handleNotificationPermissionStatus(obj, timeout)
                "app_special_access_status" -> handleAppSpecialAccessStatus(obj, timeout)
                "app_notification_listener_status" -> handleAppNotificationListenerStatus(obj, timeout)
                "app_accessibility_status" -> handleAppAccessibilityStatus(obj, timeout)
                "app_usage_access_status" -> handleAppUsageAccessStatus(obj, timeout)
                "notification_open_settings" -> handleNotificationOpenSettings(obj, timeout)
                "app_open_settings" -> handleAppOpenSettings(obj, timeout)
                "app_open_permission_settings" -> handleAppOpenPermissionSettings(obj, timeout)
                "app_open_battery_settings" -> handleAppOpenBatterySettings(obj, timeout)
                "app_open_overlay_settings" -> handleAppOpenOverlaySettings(obj, timeout)
                "app_open_unknown_sources_settings" -> handleAppOpenUnknownSourcesSettings(obj, timeout)
                "app_open_notification_listener_settings" -> handleAppOpenNotificationListenerSettings(obj, timeout)
                "app_open_accessibility_settings" -> handleAppOpenAccessibilitySettings(timeout)
                "app_open_usage_access_settings" -> handleAppOpenUsageAccessSettings(timeout)
                "app_install_sessions" -> handleAppInstallSessions(obj, timeout)
                "intent_start" -> handleIntent(obj, timeout, broadcast = false)
                "intent_broadcast" -> handleIntent(obj, timeout, broadcast = true)
                "ui_current_focus" -> handleUiCurrentFocus(timeout)
                "ui_dump" -> handleUiDump(obj, timeout)
                "ui_screenshot" -> handleUiScreenshot(obj, timeout)
                "ui_tap" -> handleUiTap(obj, timeout)
                "ui_tap_percent" -> handleUiTapPercent(obj, timeout)
                "ui_swipe" -> handleUiSwipe(obj, timeout)
                "ui_swipe_percent" -> handleUiSwipePercent(obj, timeout)
                "ui_scroll_percent" -> handleUiScrollPercent(obj, timeout)
                "ui_text" -> handleUiText(obj, timeout)
                "ui_keyevent" -> handleUiKeyevent(obj, timeout)
                "ui_dump_summary" -> handleUiDumpSummary(obj, timeout)
                "ui_find" -> handleUiFind(obj, timeout)
                "ui_click_node" -> handleUiClickNode(obj, timeout)
                "ui_click_first" -> handleUiClickFirst(obj, timeout)
                "ui_long_press_node" -> handleUiLongPressNode(obj, timeout)
                "ui_long_press" -> handleUiLongPress(obj, timeout)
                "ui_long_press_percent" -> handleUiLongPressPercent(obj, timeout)
                "ui_set_text_node" -> handleUiSetTextNode(obj, timeout)
                "ui_set_text_clear_node" -> handleUiSetTextClearNode(obj, timeout)
                "ui_select_all_and_replace_node" -> handleUiSelectAllAndReplaceNode(obj, timeout)
                "ui_wait_text" -> handleUiWaitText(obj, timeout)
                "ui_wait_gone" -> handleUiWaitGone(obj, timeout)
                "ui_wait_and_click_first" -> handleUiWaitAndClickFirst(obj, timeout)
                "ui_wait_and_replace_first" -> handleUiWaitAndReplaceFirst(obj, timeout)
                "ui_wait_and_long_press_first" -> handleUiWaitAndLongPressFirst(obj, timeout)
                "ui_wait_and_set_text_clear_first" -> handleUiWaitAndSetTextClearFirst(obj, timeout)
                "ui_wait_and_set_text_first" -> handleUiWaitAndSetTextFirst(obj, timeout)
                "ui_wait_and_tap_percent" -> handleUiWaitAndTapPercent(obj, timeout)
                "ui_wait_and_swipe_percent" -> handleUiWaitAndSwipePercent(obj, timeout)
                "ui_wait_and_scroll_percent" -> handleUiWaitAndScrollPercent(obj, timeout)
                "ui_wait_and_find" -> handleUiWaitAndFind(obj, timeout)
                "ui_wait_and_dump" -> handleUiWaitAndDump(obj, timeout)
                "ui_wait_and_dump_summary" -> handleUiWaitAndDumpSummary(obj, timeout)
                "ui_wait_and_current_focus" -> handleUiWaitAndCurrentFocus(obj, timeout)
                "ui_wait_and_backstack_summary" -> handleUiWaitAndBackstackSummary(obj, timeout)
                "ui_wait_and_screenshot" -> handleUiWaitAndScreenshot(obj, timeout)
                "ui_wait_and_text" -> handleUiWaitAndText(obj, timeout)
                "ui_wait_and_keyevent" -> handleUiWaitAndKeyevent(obj, timeout)
                "ui_wait_and_tap" -> handleUiWaitAndTap(obj, timeout)
                "ui_wait_and_swipe" -> handleUiWaitAndSwipe(obj, timeout)
                "ui_wait_and_back" -> handleUiWaitAndBack(obj, timeout)
                "ui_wait_and_long_press" -> handleUiWaitAndLongPress(obj, timeout)
                "ui_wait_and_long_press_percent" -> handleUiWaitAndLongPressPercent(obj, timeout)
                "ui_wait_gone_then_click_first" -> handleUiWaitGoneThenClickFirst(obj, timeout)
                "ui_wait_gone_then_back" -> handleUiWaitGoneThenBack(obj, timeout)
                "ui_wait_gone_then_set_text_first" -> handleUiWaitGoneThenSetTextFirst(obj, timeout)
                "ui_wait_gone_then_set_text_clear_first" -> handleUiWaitGoneThenSetTextClearFirst(obj, timeout)
                "ui_wait_gone_then_long_press_first" -> handleUiWaitGoneThenLongPressFirst(obj, timeout)
                "ui_wait_gone_then_replace_first" -> handleUiWaitGoneThenReplaceFirst(obj, timeout)
                "ui_wait_gone_then_keyevent" -> handleUiWaitGoneThenKeyevent(obj, timeout)
                "ui_wait_gone_then_swipe" -> handleUiWaitGoneThenSwipe(obj, timeout)
                "ui_wait_gone_then_tap" -> handleUiWaitGoneThenTap(obj, timeout)
                "ui_wait_gone_then_long_press" -> handleUiWaitGoneThenLongPress(obj, timeout)
                "ui_wait_gone_then_tap_percent" -> handleUiWaitGoneThenTapPercent(obj, timeout)
                "ui_wait_gone_then_swipe_percent" -> handleUiWaitGoneThenSwipePercent(obj, timeout)
                "ui_wait_gone_then_scroll_percent" -> handleUiWaitGoneThenScrollPercent(obj, timeout)
                "ui_wait_gone_then_long_press_percent" -> handleUiWaitGoneThenLongPressPercent(obj, timeout)
                "ui_wait_gone_then_text" -> handleUiWaitGoneThenText(obj, timeout)
                "ui_wait_gone_then_screenshot" -> handleUiWaitGoneThenScreenshot(obj, timeout)
                "ui_wait_gone_then_dump" -> handleUiWaitGoneThenDump(obj, timeout)
                "ui_wait_gone_then_dump_summary" -> handleUiWaitGoneThenDumpSummary(obj, timeout)
                "ui_wait_gone_then_current_focus" -> handleUiWaitGoneThenCurrentFocus(obj, timeout)
                "ui_wait_gone_then_backstack_summary" -> handleUiWaitGoneThenBackstackSummary(obj, timeout)
                "ui_wait_gone_then_find" -> handleUiWaitGoneThenFind(obj, timeout)
                "ui_scroll_find" -> handleUiScrollFind(obj, timeout)
                "ui_scroll_find_percent" -> handleUiScrollFindPercent(obj, timeout)
                "ui_scroll_click_first" -> handleUiScrollClickFirst(obj, timeout)
                "ui_scroll_click_first_percent" -> handleUiScrollClickFirstPercent(obj, timeout)
                "ui_scroll_long_press_first" -> handleUiScrollLongPressFirst(obj, timeout)
                "ui_scroll_long_press_first_percent" -> handleUiScrollLongPressFirstPercent(obj, timeout)
                "ui_scroll_select_all_and_replace_first" -> handleUiScrollSelectAllAndReplaceFirst(obj, timeout)
                "ui_scroll_select_all_and_replace_first_percent" -> handleUiScrollSelectAllAndReplaceFirstPercent(obj, timeout)
                "ui_scroll_wait_and_click_first" -> handleUiScrollWaitAndClickFirst(obj, timeout)
                "ui_scroll_wait_and_click_first_percent" -> handleUiScrollWaitAndClickFirstPercent(obj, timeout)
                "ui_scroll_wait_and_tap_percent" -> handleUiScrollWaitAndTapPercent(obj, timeout)
                "ui_scroll_wait_and_swipe_percent" -> handleUiScrollWaitAndSwipePercent(obj, timeout)
                "ui_scroll_wait_and_scroll_percent" -> handleUiScrollWaitAndScrollPercent(obj, timeout)
                "ui_scroll_wait_and_replace_first" -> handleUiScrollWaitAndReplaceFirst(obj, timeout)
                "ui_scroll_wait_and_replace_first_percent" -> handleUiScrollWaitAndReplaceFirstPercent(obj, timeout)
                "ui_scroll_wait_and_long_press_first" -> handleUiScrollWaitAndLongPressFirst(obj, timeout)
                "ui_scroll_wait_and_long_press_first_percent" -> handleUiScrollWaitAndLongPressFirstPercent(obj, timeout)
                "ui_scroll_wait_and_set_text_clear_first" -> handleUiScrollWaitAndSetTextClearFirst(obj, timeout)
                "ui_scroll_wait_and_set_text_clear_first_percent" -> handleUiScrollWaitAndSetTextClearFirstPercent(obj, timeout)
                "ui_scroll_wait_and_set_text_first" -> handleUiScrollWaitAndSetTextFirst(obj, timeout)
                "ui_scroll_wait_and_set_text_first_percent" -> handleUiScrollWaitAndSetTextFirstPercent(obj, timeout)
                "ui_scroll_wait_and_find" -> handleUiScrollWaitAndFind(obj, timeout)
                "ui_scroll_wait_and_find_percent" -> handleUiScrollWaitAndFindPercent(obj, timeout)
                "ui_scroll_wait_and_dump_percent" -> handleUiScrollWaitAndDumpPercent(obj, timeout)
                "ui_scroll_wait_and_dump" -> handleUiScrollWaitAndDump(obj, timeout)
                "ui_scroll_wait_and_dump_summary_percent" -> handleUiScrollWaitAndDumpSummaryPercent(obj, timeout)
                "ui_scroll_wait_and_dump_summary" -> handleUiScrollWaitAndDumpSummary(obj, timeout)
                "ui_scroll_wait_and_current_focus_percent" -> handleUiScrollWaitAndCurrentFocusPercent(obj, timeout)
                "ui_scroll_wait_and_current_focus" -> handleUiScrollWaitAndCurrentFocus(obj, timeout)
                "ui_scroll_wait_and_backstack_summary_percent" -> handleUiScrollWaitAndBackstackSummaryPercent(obj, timeout)
                "ui_scroll_wait_and_backstack_summary" -> handleUiScrollWaitAndBackstackSummary(obj, timeout)
                "ui_scroll_wait_and_screenshot_percent" -> handleUiScrollWaitAndScreenshotPercent(obj, timeout)
                "ui_scroll_wait_and_screenshot" -> handleUiScrollWaitAndScreenshot(obj, timeout)
                "ui_scroll_wait_and_text" -> handleUiScrollWaitAndText(obj, timeout)
                "ui_scroll_wait_and_text_percent" -> handleUiScrollWaitAndTextPercent(obj, timeout)
                "ui_scroll_wait_and_keyevent" -> handleUiScrollWaitAndKeyevent(obj, timeout)
                "ui_scroll_wait_and_keyevent_percent" -> handleUiScrollWaitAndKeyeventPercent(obj, timeout)
                "ui_scroll_wait_and_tap" -> handleUiScrollWaitAndTap(obj, timeout)
                "ui_scroll_wait_and_swipe" -> handleUiScrollWaitAndSwipe(obj, timeout)
                "ui_scroll_wait_and_back" -> handleUiScrollWaitAndBack(obj, timeout)
                "ui_scroll_wait_and_back_percent" -> handleUiScrollWaitAndBackPercent(obj, timeout)
                "ui_scroll_wait_and_long_press" -> handleUiScrollWaitAndLongPress(obj, timeout)
                "ui_scroll_wait_and_long_press_percent" -> handleUiScrollWaitAndLongPressPercent(obj, timeout)
                "ui_scroll_set_text_clear_first" -> handleUiScrollSetTextClearFirst(obj, timeout)
                "ui_scroll_set_text_clear_first_percent" -> handleUiScrollSetTextClearFirstPercent(obj, timeout)
                "ui_scroll_set_text_first" -> handleUiScrollSetTextFirst(obj, timeout)
                "ui_scroll_set_text_first_percent" -> handleUiScrollSetTextFirstPercent(obj, timeout)
                "ui_backstack_summary" -> handleUiBackstackSummary(obj, timeout)
                else -> "Error: Unsupported action '$action'. Supported: ${SUPPORTED_ACTIONS.joinToString(", ")}"
            }
        } catch (e: Exception) {
            "Error in android tool: ${e.message}"
        }
    }

    private fun handleAppList(obj: JsonObject, timeout: Int): String {
        val scope = obj.string("scope") ?: "all"
        val keyword = obj.string("keyword").orEmpty()
        val offset = (obj.int("offset") ?: 0).coerceAtLeast(0)
        val limit = (obj.int("lines") ?: DEFAULT_LIST_LIMIT).coerceIn(1, 500)
        val command = when (scope) {
            "user" -> "pm list packages -3"
            "system" -> "pm list packages -s"
            else -> "pm list packages"
        }
        val output = runCommand(command, timeout)
        if (isFailureOutput(output)) return output
        val packages = output.lineSequence()
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotBlank() }
            .filter { keyword.isBlank() || it.contains(keyword, ignoreCase = true) }
            .toList()
        val slice = packages.drop(offset).take(limit)
        val start = if (slice.isEmpty()) 0 else offset + 1
        val end = offset + slice.size
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "app_list",
                "scope" to scope,
                "keyword" to keyword.ifBlank { null },
                "total" to packages.size,
                "offset" to offset,
                "limit" to limit,
                "showing" to slice.size,
                "range" to if (slice.isEmpty()) null else linkedMapOf<String, Any?>(
                    "start" to start,
                    "end" to end
                ),
                "hasMore" to (offset + slice.size < packages.size),
                "nextOffset" to (offset + slice.size).takeIf { it < packages.size },
                "packages" to slice
            )
        )
    }

    private fun handleAppInfo(obj: JsonObject, timeout: Int): String {
        val packageName = obj.string("packageName") ?: return "Error: 'packageName' is required for app_info"
        val pathOutput = runCommand("pm path ${shQuote(packageName)}", timeout)
        val pidOutput = runCommand("pidof ${shQuote(packageName)} 2>/dev/null || true", timeout)
        val launcherOutput = runCommand("cmd package resolve-activity --brief ${shQuote(packageName)} 2>/dev/null || true", timeout)
        val dumpOutput = runCommand("dumpsys package ${shQuote(packageName)}", timeout)
        if (isFailureOutput(dumpOutput)) return dumpOutput

        val dumpLines = dumpOutput.lines()
        val versionName = findFirstGroup(dumpOutput, Regex("""versionName=(.+)"""))
        val versionCode = findFirstGroup(dumpOutput, Regex("""versionCode=([^\s]+)"""))
        val targetSdk = findFirstGroup(dumpOutput, Regex("""targetSdk=(\d+)"""))
        val userId = findFirstGroup(dumpOutput, Regex("""userId=(\d+)"""))
        val enabled = findFirstGroup(dumpOutput, Regex("""enabled=(true|false)"""))?.toBooleanStrictOrNull()
        val firstInstallTime = findFirstGroup(dumpOutput, Regex("""firstInstallTime=(.+)"""))
        val lastUpdateTime = findFirstGroup(dumpOutput, Regex("""lastUpdateTime=(.+)"""))
        val paths = pathOutput.lineSequence()
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotBlank() }
            .toList()
        val pids = pidOutput.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        val launcherComponent = launcherOutput.lines()
            .map { it.trim() }
            .firstOrNull { it.contains("/") }
        val requestedPermissions = collectIndentedBlock(
            lines = dumpLines,
            header = "requested permissions:",
            nextHeaders = listOf(
                "install permissions:",
                "runtime permissions:",
                "pkgFlags=",
                "User 0:"
            ),
            maxItems = 15
        )
        val installPermissions = collectIndentedBlock(
            lines = dumpLines,
            header = "install permissions:",
            nextHeaders = listOf(
                "runtime permissions:",
                "pkgFlags=",
                "User 0:"
            ),
            maxItems = 15
        )
        val runtimePermissions = collectIndentedBlock(
            lines = dumpLines,
            header = "runtime permissions:",
            nextHeaders = listOf(
                "pkgFlags=",
                "User 0:",
                "sharedUser="
            ),
            maxItems = 20
        )
        val grantedPermissions = buildList {
            addAll(installPermissions.map { it.substringBefore(':').trim() })
            addAll(runtimePermissions.map { it.substringBefore(':').trim() })
        }.filter { it.isNotBlank() }.distinct()
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "app_info",
                "packageName" to packageName,
                "installed" to paths.isNotEmpty(),
                "versionName" to versionName,
                "versionCode" to versionCode,
                "enabled" to enabled,
                "uid" to userId,
                "sourceDir" to paths.firstOrNull(),
                "splitSourceDirs" to paths.drop(1),
                "targetSdk" to targetSdk?.toIntOrNull(),
                "firstInstallTime" to firstInstallTime,
                "lastUpdateTime" to lastUpdateTime,
                "launcherActivity" to launcherComponent,
                "requestedPermissions" to requestedPermissions,
                "grantedPermissions" to grantedPermissions,
                "runtimePermissions" to runtimePermissions,
                "runningProcesses" to pids.map { linkedMapOf("pid" to it, "processName" to packageName) }
            )
        )
    }

    private fun handleAppPath(obj: JsonObject, timeout: Int): String {
        val packageName = obj.string("packageName") ?: return "Error: 'packageName' is required for app_path"
        val pathOutput = runCommand("pm path ${shQuote(packageName)} 2>/dev/null || true", timeout)
        if (isFailureOutput(pathOutput)) return pathOutput
        val paths = pathOutput.lineSequence()
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotBlank() }
            .toList()
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "app_path",
                "packageName" to packageName,
                "installed" to paths.isNotEmpty(),
                "count" to paths.size,
                "paths" to paths
            )
        )
    }

    private fun handleAppStart(obj: JsonObject, timeout: Int): String {
        val component = resolveComponent(obj) ?: obj.string("component")
        val packageName = obj.string("packageName")
        val command = when {
            !component.isNullOrBlank() -> "am start -W -n ${shQuote(component)}"
            !packageName.isNullOrBlank() ->
                "monkey -p ${shQuote(packageName)} -c android.intent.category.LAUNCHER 1"
            else -> return "Error: 'packageName' or 'component' is required for app_start"
        }
        val output = runCommand(command, timeout)
        if (isFailureOutput(output)) return output
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "app_start",
                "packageName" to packageName,
                "component" to component,
                "command" to command,
                "success" to !output.contains("Error:", ignoreCase = true),
                "summary" to linkedMapOf<String, Any?>(
                    "resolvedComponent" to (findFirstGroup(output, Regex("""cmp=([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)""")) ?: component),
                    "status" to findFirstMatchingLine(output, listOf("Status:", "Activity:", "Warning:")),
                    "thisTimeMs" to findFirstGroup(output, Regex("""ThisTime:\s*(\d+)"""))?.toIntOrNull(),
                    "totalTimeMs" to findFirstGroup(output, Regex("""TotalTime:\s*(\d+)"""))?.toIntOrNull(),
                    "waitTimeMs" to findFirstGroup(output, Regex("""WaitTime:\s*(\d+)"""))?.toIntOrNull()
                ),
                "outputLines" to output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT)
            )
        )
    }

    private fun handleAppForceStop(obj: JsonObject, timeout: Int): String {
        val packageName = obj.string("packageName") ?: return "Error: 'packageName' is required for app_force_stop"
        val command = "am force-stop ${shQuote(packageName)}"
        val beforePids = listPidsForPackage(packageName, timeout)
        val output = runCommand(command, timeout)
        if (isFailureOutput(output)) return output
        val afterPids = listPidsForPackage(packageName, timeout)
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "app_force_stop",
                "packageName" to packageName,
                "command" to command,
                "success" to afterPids.isEmpty(),
                "summary" to linkedMapOf<String, Any?>(
                    "runningBefore" to beforePids.isNotEmpty(),
                    "runningAfter" to afterPids.isNotEmpty(),
                    "stopped" to afterPids.isEmpty()
                ),
                "beforePids" to beforePids,
                "afterPids" to afterPids,
                "outputLines" to output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT)
            )
        )
    }

    private fun handleAppClearData(obj: JsonObject, timeout: Int): String {
        val packageName = obj.string("packageName") ?: return "Error: 'packageName' is required for app_clear_data"
        val command = "pm clear ${shQuote(packageName)}"
        val pathBefore = runCommand("pm path ${shQuote(packageName)} 2>/dev/null || true", timeout)
        if (isFailureOutput(pathBefore)) return pathBefore
        val output = runCommand(command, timeout)
        if (isFailureOutput(output)) return output
        val pathAfter = runCommand("pm path ${shQuote(packageName)} 2>/dev/null || true", timeout)
        if (isFailureOutput(pathAfter)) return pathAfter
        val cleared = output.lines().any { it.trim().equals("Success", ignoreCase = true) }
        val stillInstalled = pathAfter.lineSequence().any { it.trim().startsWith("package:") }
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "app_clear_data",
                "packageName" to packageName,
                "command" to command,
                "success" to (cleared && stillInstalled),
                "summary" to linkedMapOf<String, Any?>(
                    "cleared" to cleared,
                    "stillInstalled" to stillInstalled
                ),
                "installedPathsBefore" to pathBefore.lines().map { it.removePrefix("package:").trim() }.filter { it.isNotBlank() },
                "installedPathsAfter" to pathAfter.lines().map { it.removePrefix("package:").trim() }.filter { it.isNotBlank() },
                "outputLines" to output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT)
            )
        )
    }

    private fun handleAppInstall(obj: JsonObject, timeout: Int): String {
        val apkPath = obj.string("apkPath") ?: return "Error: 'apkPath' is required for app_install"
        val reinstall = obj.bool("reinstall") ?: true
        val grantRuntimePermissions = obj.bool("grantRuntimePermissions") ?: false
        val allowDowngrade = obj.bool("allowDowngrade") ?: false
        val packageName = obj.string("packageName")
        val safeTimeout = timeout.coerceAtLeast(DEFAULT_INSTALL_TIMEOUT_SECONDS)
        val fileCheck = runCommand(
            "if [ -f ${shQuote(apkPath)} ]; then ls -l ${shQuote(apkPath)}; else echo '$FILE_MISSING_MARKER'; fi",
            safeTimeout
        )
        if (isFailureOutput(fileCheck)) return fileCheck
        if (fileCheck.contains(FILE_MISSING_MARKER)) {
            return "Error: APK file not found: $apkPath"
        }
        val command = buildString {
            append("pm install")
            if (reinstall) append(" -r")
            if (grantRuntimePermissions) append(" -g")
            if (allowDowngrade) append(" -d")
            append(' ')
            append(shQuote(apkPath))
        }
        val output = runCommand(command, safeTimeout)
        if (isFailureOutput(output)) return output
        val installSucceeded = output.lines().any { it.trim().equals("Success", ignoreCase = true) }
        val installedPath = packageName?.let {
            runCommand("pm path ${shQuote(it)} 2>/dev/null || true", safeTimeout)
                .lineSequence()
                .map { line -> line.removePrefix("package:").trim() }
                .firstOrNull { it.isNotBlank() }
        }
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "app_install",
                "apkPath" to apkPath,
                "packageName" to packageName,
                "reinstall" to reinstall,
                "grantRuntimePermissions" to grantRuntimePermissions,
                "allowDowngrade" to allowDowngrade,
                "timeoutSeconds" to safeTimeout,
                "success" to installSucceeded,
                "summary" to linkedMapOf<String, Any?>(
                    "apkExists" to !fileCheck.contains(FILE_MISSING_MARKER),
                    "installedPath" to installedPath
                ),
                "fileCheckLines" to fileCheck.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT),
                "outputLines" to output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT)
            )
        )
    }

    private fun handleAppUninstall(obj: JsonObject, timeout: Int): String {
        val packageName = obj.string("packageName") ?: return "Error: 'packageName' is required for app_uninstall"
        val keepData = obj.bool("keepData") ?: false
        val safeTimeout = timeout.coerceAtLeast(DEFAULT_INSTALL_TIMEOUT_SECONDS)
        val pathBefore = runCommand("pm path ${shQuote(packageName)} 2>/dev/null || true", safeTimeout)
        val command = buildString {
            append("pm uninstall")
            if (keepData) append(" -k")
            append(' ')
            append(shQuote(packageName))
        }
        val output = runCommand(command, safeTimeout)
        if (isFailureOutput(output)) return output
        val pathAfter = runCommand("pm path ${shQuote(packageName)} 2>/dev/null || true", safeTimeout)
        val uninstallSucceeded = output.lines().any { it.trim().equals("Success", ignoreCase = true) }
        val stillInstalled = pathAfter.lineSequence().any { it.trim().startsWith("package:") }
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "app_uninstall",
                "packageName" to packageName,
                "keepData" to keepData,
                "timeoutSeconds" to safeTimeout,
                "command" to command,
                "success" to (uninstallSucceeded && !stillInstalled),
                "summary" to linkedMapOf<String, Any?>(
                    "uninstallSucceeded" to uninstallSucceeded,
                    "stillInstalled" to stillInstalled
                ),
                "installedPathsBefore" to pathBefore.lines().map { it.removePrefix("package:").trim() }.filter { it.isNotBlank() },
                "installedPathsAfter" to pathAfter.lines().map { it.removePrefix("package:").trim() }.filter { it.isNotBlank() },
                "outputLines" to output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT)
            )
        )
    }

    private fun handleAppEnableDisable(obj: JsonObject, timeout: Int, disable: Boolean): String {
        val packageName = obj.string("packageName") ?: return "Error: 'packageName' is required"
        val command = if (disable) {
            "pm disable-user --user 0 ${shQuote(packageName)}"
        } else {
            "pm enable ${shQuote(packageName)}"
        }
        val output = runCommand(command, timeout)
        if (isFailureOutput(output)) return output
        val verifyCommand = if (disable) {
            "pm list packages -d | grep -Fx ${shQuote("package:$packageName")} || true"
        } else {
            "pm list packages -e | grep -Fx ${shQuote("package:$packageName")} || true"
        }
        val verifyOutput = runCommand(verifyCommand, timeout)
        if (isFailureOutput(verifyOutput)) return verifyOutput
        val verified = verifyOutput.isNotBlank()
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to if (disable) "app_disable" else "app_enable",
                "packageName" to packageName,
                "command" to command,
                "success" to verified,
                "summary" to linkedMapOf<String, Any?>(
                    "enabled" to !disable,
                    "verified" to verified
                ),
                "verification" to verifyOutput.lines().map { it.trimEnd() }.filter { it.isNotBlank() },
                "outputLines" to output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT)
            )
        )
    }

    private fun handleAppCurrentTop(timeout: Int): String {
        val windowOutput = runCommand(
            "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' || true",
            timeout
        )
        if (isFailureOutput(windowOutput)) return windowOutput
        val activityOutput = runCommand(
            "dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity|mFocusedApp' || true",
            timeout
        )
        if (isFailureOutput(activityOutput)) return activityOutput
        val merged = listOf(windowOutput, activityOutput).joinToString("\n")
        val component = findFirstGroup(merged, Regex("""([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)"""))
        val packageName = component?.substringBefore("/")
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "app_current_top",
                "packageName" to packageName,
                "component" to component,
                "focusedWindow" to findFirstMatchingLine(windowOutput, listOf("mCurrentFocus")),
                "focusedApp" to findFirstMatchingLine(merged, listOf("mFocusedApp")),
                "topResumedActivity" to findFirstMatchingLine(activityOutput, listOf("topResumedActivity", "mResumedActivity", "ResumedActivity")),
                "window" to linkedMapOf(
                    "lines" to windowOutput.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
                ),
                "activity" to linkedMapOf(
                    "lines" to activityOutput.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
                )
            )
        )
    }

    private fun handlePermissionMutation(obj: JsonObject, timeout: Int, revoke: Boolean): String {
        val packageName = obj.string("packageName") ?: return "Error: 'packageName' is required"
        val permission = obj.string("permission") ?: return "Error: 'permission' is required"
        val actionWord = if (revoke) "revoke" else "grant"
        val command = "pm $actionWord ${shQuote(packageName)} ${shQuote(permission)}"
        val output = runCommand(command, timeout)
        if (isFailureOutput(output)) return output
        val verifyDump = runCommand("dumpsys package ${shQuote(packageName)} 2>/dev/null || true", timeout)
        if (isFailureOutput(verifyDump)) return verifyDump
        val permissionLine = verifyDump.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains(permission, ignoreCase = true) }
        val granted = permissionLine?.contains("granted=true", ignoreCase = true) == true
        val success = if (revoke) !granted else granted
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to if (revoke) "app_revoke" else "app_grant",
                "packageName" to packageName,
                "permission" to permission,
                "command" to command,
                "success" to success,
                "summary" to linkedMapOf<String, Any?>(
                    "granted" to granted,
                    "verified" to (permissionLine != null)
                ),
                "verificationLine" to permissionLine,
                "outputLines" to output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT)
            )
        )
    }

    private fun handleProcessList(obj: JsonObject, timeout: Int): String {
        val keyword = obj.string("keyword").orEmpty()
        val grep = obj.string("grep").orEmpty()
        val limit = (obj.int("lines") ?: DEFAULT_OUTPUT_LIMIT).coerceIn(1, 300)
        val output = runCommand("ps -A", timeout)
        if (isFailureOutput(output)) return output
        val filtered = output.lines()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .filter { line ->
                val matchesKeyword = keyword.isBlank() || line.contains(keyword, ignoreCase = true)
                val matchesGrep = grep.isBlank() || line.contains(grep, ignoreCase = true)
                matchesKeyword && matchesGrep
            }
            .toList()
        if (filtered.isEmpty()) {
            return "未找到匹配的进程。"
        }
        val headerLine = filtered.firstOrNull { it.contains("PID") && it.contains("NAME") }
        val processLines = if (headerLine != null) filtered.drop(1) else filtered
        val items = processLines
            .mapNotNull(::parsePsProcessLine)
            .take(limit)
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "process_list",
                "filters" to linkedMapOf<String, Any?>(
                    "keyword" to keyword.ifBlank { null },
                    "grep" to grep.ifBlank { null }
                ),
                "showing" to items.size,
                "header" to headerLine,
                "processes" to items,
                "rawLines" to processLines.take(limit)
            )
        )
    }

    private fun handleProcessByPackage(obj: JsonObject, timeout: Int): String {
        val packageName = obj.string("packageName") ?: return "Error: 'packageName' is required for process_by_package"
        val pidOutput = runCommand("pidof ${shQuote(packageName)} 2>/dev/null || true", timeout).trim()
        val psOutput = runCommand(
            "ps -A | grep -F -- ${shQuote(packageName)} | grep -v 'grep -F' || true",
            timeout
        )
        val pids = pidOutput.split(Regex("""\s+""")).filter { it.isNotBlank() }
        val psLines = psOutput.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        val processes = pids.map { pid ->
            val statusOutput = runCommand(
                "cat /proc/${shQuote(pid)}/status 2>/dev/null | grep -E '^(Name|State|Pid|PPid|Threads|VmRSS|VmSize|Uid):' || true",
                timeout
            )
            val oomAdjOutput = runCommand("cat /proc/${shQuote(pid)}/oom_score_adj 2>/dev/null || true", timeout)
            val meminfoOutput = runCommand("dumpsys meminfo ${shQuote(pid)} 2>/dev/null || true", timeout)
            val psLine = psLines.firstOrNull { Regex("""\s$pid\s""").containsMatchIn(it) || it.endsWith(" $packageName") }
            val status = parseKeyValueLines(statusOutput)
            linkedMapOf<String, Any?>(
                "pid" to pid,
                "processName" to (status["Name"] ?: psLine?.substringAfterLast(' ') ?: packageName),
                "user" to psLine?.substringBefore(' ')?.trim(),
                "state" to status["State"],
                "ppid" to status["PPid"]?.toIntOrNull(),
                "threads" to status["Threads"]?.toIntOrNull(),
                "rssKb" to parseKbValue(status["VmRSS"]),
                "vmSizeKb" to parseKbValue(status["VmSize"]),
                "oomAdj" to oomAdjOutput.trim().toIntOrNull(),
                "totalPssKb" to parseMeminfoTotalPssKb(meminfoOutput),
                "psLine" to psLine,
                "meminfoSummary" to extractInterestingLines(
                    meminfoOutput,
                    keywords = listOf("TOTAL PSS:", "TOTAL SWAP PSS:", "Native Heap", "Dalvik Heap", "App Summary"),
                    limit = 12
                )
            )
        }
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "process_by_package",
                "packageName" to packageName,
                "running" to pids.isNotEmpty(),
                "pids" to pids,
                "processCount" to processes.size,
                "processes" to processes,
                "matchedPsLines" to psLines.take(20)
            )
        )
    }

    private fun handleProcessMeminfo(obj: JsonObject, timeout: Int): String {
        val pid = obj.int("pid")?.toString()
        val packageName = obj.string("packageName")
        val target = when {
            pid != null -> pid
            !packageName.isNullOrBlank() -> runCommand("pidof ${shQuote(packageName)} 2>/dev/null || true", timeout)
                .split(Regex("""\s+"""))
                .firstOrNull { it.isNotBlank() }
            else -> null
        } ?: return "Error: 'pid' or 'packageName' is required for process_meminfo"
        val limit = (obj.int("lines") ?: DEFAULT_OUTPUT_LIMIT).coerceIn(1, 300)
        val output = runCommand("dumpsys meminfo ${shQuote(target)} 2>/dev/null || true", timeout)
        if (isFailureOutput(output)) return output
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "process_meminfo",
                "packageName" to packageName,
                "target" to target,
                "summary" to linkedMapOf<String, Any?>(
                    "totalPssKb" to parseMeminfoTotalPssKb(output),
                    "nativeHeapKb" to parseMeminfoNamedTotalKb(output, "Native Heap"),
                    "dalvikHeapKb" to parseMeminfoNamedTotalKb(output, "Dalvik Heap"),
                    "appSummary" to findFirstMatchingLine(output, listOf("App Summary"))
                ),
                "matches" to extractInterestingLines(
                    output,
                    keywords = listOf(
                        "Applications Memory Usage",
                        "MEMINFO in pid",
                        "TOTAL PSS:",
                        "TOTAL SWAP PSS:",
                        "Native Heap",
                        "Dalvik Heap",
                        "App Summary",
                        "TOTAL"
                    ),
                    limit = limit
                )
            )
        )
    }

    private fun handleProcessThreadSummary(obj: JsonObject, timeout: Int): String {
        val pid = obj.int("pid")?.toString()
        val packageName = obj.string("packageName")
        val targetPid = when {
            pid != null -> pid
            !packageName.isNullOrBlank() -> runCommand("pidof ${shQuote(packageName)} 2>/dev/null || true", timeout)
                .split(Regex("""\s+"""))
                .firstOrNull { it.isNotBlank() }
            else -> null
        } ?: return "Error: 'pid' or 'packageName' is required for process_thread_summary"
        val limit = (obj.int("lines") ?: DEFAULT_OUTPUT_LIMIT).coerceIn(1, 300)
        val statusOutput = runCommand(
            "cat /proc/${shQuote(targetPid)}/status 2>/dev/null | grep -E '^(Name|State|Tgid|Pid|PPid|Threads|VmRSS|VmSize):' || true",
            timeout
        )
        if (isFailureOutput(statusOutput)) return statusOutput
        val threadOutput = runCommand("ps -T -p ${shQuote(targetPid)} 2>/dev/null || true", timeout)
        if (isFailureOutput(threadOutput)) return threadOutput
        val status = parseKeyValueLines(statusOutput)
        val threadLines = threadOutput.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        val headerLine = threadLines.firstOrNull { it.contains("PID") && it.contains("TID") || it.contains("PID") && it.contains("NAME") }
        val threads = threadLines
            .drop(if (headerLine != null) 1 else 0)
            .mapNotNull(::parsePsProcessLine)
            .take(limit)
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "process_thread_summary",
                "packageName" to packageName,
                "pid" to targetPid.toIntOrNull(),
                "summary" to linkedMapOf<String, Any?>(
                    "name" to status["Name"],
                    "state" to status["State"],
                    "tgid" to status["Tgid"]?.toIntOrNull(),
                    "ppid" to status["PPid"]?.toIntOrNull(),
                    "threads" to status["Threads"]?.toIntOrNull(),
                    "rssKb" to parseKbValue(status["VmRSS"]),
                    "vmSizeKb" to parseKbValue(status["VmSize"])
                ),
                "header" to headerLine,
                "threadCount" to threads.size,
                "threads" to threads,
                "rawStatus" to statusOutput.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            )
        )
    }

    private fun handleProcessKill(obj: JsonObject, timeout: Int): String {
        val signal = (obj.string("signal") ?: "TERM").uppercase()
        val allowedSignal = if (signal in setOf("TERM", "KILL", "INT", "HUP", "QUIT")) signal else "TERM"
        val pid = obj.int("pid")?.toString()
        val packageName = obj.string("packageName")
        val targetPids = when {
            pid != null -> listOf(pid)
            !packageName.isNullOrBlank() -> runCommand("pidof ${shQuote(packageName)} 2>/dev/null || true", timeout)
                .split(Regex("""\s+"""))
                .filter { it.isNotBlank() }
            else -> return "Error: 'pid' or 'packageName' is required for process_kill"
        }
        if (targetPids.isEmpty()) {
            return "未找到可结束的目标进程。"
        }
        val command = "kill -$allowedSignal ${targetPids.joinToString(" ")}"
        val output = runCommand(command, timeout)
        if (isFailureOutput(output)) return output
        val verificationOutput = runCommand(
            buildString {
                append("for p in ${targetPids.joinToString(" ")}; do ")
                append("if kill -0 ${'$'}p 2>/dev/null; then echo \"${'$'}p:alive\"; else echo \"${'$'}p:dead\"; fi; ")
                append("done")
            },
            timeout
        )
        if (isFailureOutput(verificationOutput)) return verificationOutput
        val verification = verificationOutput.lines()
            .mapNotNull { line ->
                val parts = line.trim().split(":")
                if (parts.size == 2) {
                    linkedMapOf<String, Any?>(
                        "pid" to parts[0].toIntOrNull(),
                        "alive" to (parts[1] == "alive")
                    )
                } else {
                    null
                }
            }
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "process_kill",
                "packageName" to packageName,
                "signal" to allowedSignal,
                "targets" to targetPids.map { it.toIntOrNull() ?: it },
                "command" to command,
                "success" to (verification.any { (it["alive"] as? Boolean) == false }),
                "verification" to verification,
                "outputLines" to output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT)
            )
        )
    }

    private fun handleDumpsys(obj: JsonObject, timeout: Int): String {
        val service = obj.string("service") ?: return "Error: 'service' is required for dumpsys"
        if (!TOKEN_REGEX.matches(service)) {
            return "Error: Invalid dumpsys service '$service'"
        }
        return handleDumpsysPreset(
            action = "dumpsys",
            title = "dumpsys 结果",
            service = service,
            subcommand = null,
            target = obj.string("target"),
            obj = obj,
            timeout = timeout
        )
    }

    private fun handleDumpsysActivity(obj: JsonObject, timeout: Int): String {
        val grep = obj.string("grep").orEmpty()
        val limit = (obj.int("lines") ?: DEFAULT_OUTPUT_LIMIT).coerceIn(1, 300)
        val output = runCommand(buildDumpsysCommand("activity", "activities", null), timeout)
        if (isFailureOutput(output)) return output
        val topResumedLine = findFirstMatchingLine(output, listOf("topResumedActivity", "mResumedActivity", "ResumedActivity"))
        val focusedAppLine = findFirstMatchingLine(output, listOf("mFocusedApp"))
        val focusedTaskLine = findFirstMatchingLine(output, listOf("realActivity=", "taskId="))
        val historyLines = extractInterestingLines(
            output,
            keywords = listOf(
                "topResumedActivity",
                "mResumedActivity",
                "ResumedActivity",
                "mFocusedApp",
                "realActivity=",
                "taskId=",
                "state=",
                "Hist #"
            ),
            limit = limit
        )
        val filteredHistory = if (grep.isBlank()) {
            historyLines
        } else {
            historyLines.filter { it.contains(grep, ignoreCase = true) }
        }
        val mergedKeyLines = listOfNotNull(topResumedLine, focusedAppLine, focusedTaskLine).joinToString("\n")
        val component = findFirstGroup(mergedKeyLines, Regex("""([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)"""))
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "dumpsys_activity",
                "service" to "activity",
                "subcommand" to "activities",
                "filter" to grep.ifBlank { null },
                "summary" to linkedMapOf<String, Any?>(
                    "topResumedActivity" to topResumedLine,
                    "focusedApp" to focusedAppLine,
                    "focusedTask" to focusedTaskLine,
                    "component" to component,
                    "packageName" to component?.substringBefore("/"),
                    "taskId" to findFirstGroup(mergedKeyLines, Regex("""taskId=(\d+)"""))?.toIntOrNull(),
                    "realActivity" to findFirstGroup(mergedKeyLines, Regex("""realActivity=([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)""")),
                    "state" to findFirstGroup(mergedKeyLines, Regex("""state=([A-Za-z0-9_.$/-]+)"""))
                ),
                "matchCount" to filteredHistory.size,
                "truncated" to (filteredHistory.size > limit),
                "matches" to filteredHistory.take(limit)
            )
        )
    }

    private fun handleDumpsysWindow(obj: JsonObject, timeout: Int): String {
        val grep = obj.string("grep").orEmpty()
        val limit = (obj.int("lines") ?: DEFAULT_OUTPUT_LIMIT).coerceIn(1, 300)
        val output = runCommand(buildDumpsysCommand("window", "windows", null), timeout)
        if (isFailureOutput(output)) return output
        val currentFocusLine = findFirstMatchingLine(output, listOf("mCurrentFocus"))
        val focusedAppLine = findFirstMatchingLine(output, listOf("mFocusedApp"))
        val topFullscreenLine = findFirstMatchingLine(output, listOf("mTopFullscreenOpaqueWindowState"))
        val matchedLines = extractInterestingLines(
            output,
            keywords = listOf(
                "mCurrentFocus",
                "mFocusedApp",
                "mTopFullscreenOpaqueWindowState",
                "isOnScreen=",
                "package=",
                "Window #"
            ),
            limit = limit
        ).let { lines ->
            if (grep.isBlank()) lines else lines.filter { it.contains(grep, ignoreCase = true) }
        }
        val merged = listOfNotNull(currentFocusLine, focusedAppLine, topFullscreenLine).joinToString("\n")
        val component = findFirstGroup(merged, Regex("""([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)"""))
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "dumpsys_window",
                "service" to "window",
                "subcommand" to "windows",
                "filter" to grep.ifBlank { null },
                "summary" to linkedMapOf<String, Any?>(
                    "currentFocus" to currentFocusLine,
                    "focusedApp" to focusedAppLine,
                    "topFullscreen" to topFullscreenLine,
                    "component" to component,
                    "packageName" to component?.substringBefore("/")
                ),
                "matchCount" to matchedLines.size,
                "truncated" to (matchedLines.size > limit),
                "matches" to matchedLines.take(limit)
            )
        )
    }

    private fun handleDumpsysPackage(obj: JsonObject, timeout: Int): String {
        val target = resolveDumpsysTarget(obj)
            ?: return "Error: 'packageName' or 'target' is required for dumpsys_package"
        val output = runCommand(buildDumpsysCommand("package", null, target), timeout)
        if (isFailureOutput(output)) return output
        val lines = output.lines()
        val summary = linkedMapOf<String, Any?>(
            "packageName" to (findFirstGroup(output, Regex("""Package \[([^\]]+)]""")) ?: target.takeIf { '.' in it }),
            "target" to target,
            "versionName" to findFirstGroup(output, Regex("""versionName=(.+)""")),
            "versionCode" to findFirstGroup(output, Regex("""versionCode=([^\s]+)""")),
            "enabled" to findFirstGroup(output, Regex("""enabled=(true|false)"""))?.toBooleanStrictOrNull(),
            "uid" to findFirstGroup(output, Regex("""userId=(\d+)"""))?.toIntOrNull(),
            "codePath" to findFirstGroup(output, Regex("""codePath=(.+)""")),
            "resourcePath" to findFirstGroup(output, Regex("""resourcePath=(.+)""")),
            "targetSdk" to findFirstGroup(output, Regex("""targetSdk=(\d+)"""))?.toIntOrNull(),
            "firstInstallTime" to findFirstGroup(output, Regex("""firstInstallTime=(.+)""")),
            "lastUpdateTime" to findFirstGroup(output, Regex("""lastUpdateTime=(.+)"""))
        )
        val requestedPermissions = collectIndentedBlock(
            lines = lines,
            header = "requested permissions:",
            nextHeaders = listOf("install permissions:", "runtime permissions:", "pkgFlags=", "User 0:"),
            maxItems = 20
        )
        val runtimePermissions = collectIndentedBlock(
            lines = lines,
            header = "runtime permissions:",
            nextHeaders = listOf("pkgFlags=", "User 0:", "sharedUser="),
            maxItems = 20
        )
        val interestingLines = extractInterestingLines(
            output,
            keywords = listOf(
                "Package [",
                "versionName=",
                "versionCode=",
                "enabled=",
                "codePath=",
                "targetSdk=",
                "firstInstallTime=",
                "lastUpdateTime=",
                "grantedPermissions:"
            ),
            limit = (obj.int("lines") ?: DEFAULT_OUTPUT_LIMIT).coerceIn(1, 300)
        )
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "dumpsys_package",
                "service" to "package",
                "target" to target,
                "summary" to summary,
                "requestedPermissions" to requestedPermissions,
                "runtimePermissions" to runtimePermissions,
                "matches" to interestingLines
            )
        )
    }

    private fun handleDumpsysBattery(obj: JsonObject, timeout: Int): String {
        return handleDumpsysPreset(
            action = "dumpsys_battery",
            title = "Battery 摘要",
            service = "battery",
            subcommand = null,
            target = null,
            obj = obj,
            timeout = timeout,
            summaryLines = listOf(
                "Status" to listOf("status:"),
                "Health" to listOf("health:"),
                "Level" to listOf("level:"),
                "Temperature" to listOf("temperature:"),
                "Powered" to listOf("AC powered:", "USB powered:", "Wireless powered:")
            )
        )
    }

    private fun handleDumpsysMeminfo(obj: JsonObject, timeout: Int): String {
        return handleDumpsysPreset(
            action = "dumpsys_meminfo",
            title = "Meminfo 摘要",
            service = "meminfo",
            subcommand = null,
            target = resolveDumpsysTarget(obj),
            obj = obj,
            timeout = timeout,
            summaryLines = listOf(
                "Target" to listOf("Applications Memory Usage", "MEMINFO in pid", "Total PSS by process:"),
                "TotalPss" to listOf("TOTAL PSS:", "TOTAL", "TOTAL SWAP PSS:"),
                "AppSummary" to listOf("App Summary")
            ),
            defaultKeywords = listOf(
                "Applications Memory Usage",
                "MEMINFO in pid",
                "TOTAL PSS:",
                "TOTAL SWAP PSS:",
                "Native Heap",
                "Dalvik Heap",
                "App Summary",
                "TOTAL"
            )
        )
    }

    private fun handleDumpsysInput(obj: JsonObject, timeout: Int): String {
        return handleDumpsysPreset(
            action = "dumpsys_input",
            title = "Input 摘要",
            service = "input",
            subcommand = null,
            target = null,
            obj = obj,
            timeout = timeout,
            summaryLines = listOf(
                "FocusedApplications" to listOf("FocusedApplications:"),
                "FocusedWindows" to listOf("FocusedWindows:"),
                "DispatchEnabled" to listOf("DispatchEnabled:"),
                "DispatchFrozen" to listOf("DispatchFrozen:")
            ),
            defaultKeywords = listOf(
                "FocusedApplications:",
                "FocusedWindows:",
                "DispatchEnabled:",
                "DispatchFrozen:",
                "Input Reader State",
                "Input Dispatcher State"
            )
        )
    }

    private fun handleDumpsysDisplay(obj: JsonObject, timeout: Int): String {
        return handleDumpsysPreset(
            action = "dumpsys_display",
            title = "Display 摘要",
            service = "display",
            subcommand = null,
            target = null,
            obj = obj,
            timeout = timeout,
            summaryLines = listOf(
                "Display0" to listOf("Display 0"),
                "OverrideInfo" to listOf("mOverrideDisplayInfo="),
                "Viewport" to listOf("DisplayViewport{"),
                "PowerState" to listOf("mGlobalDisplayState=")
            ),
            defaultKeywords = listOf(
                "Display 0",
                "mOverrideDisplayInfo=",
                "DisplayViewport{",
                "mGlobalDisplayState=",
                "StableDisplaySize",
                "DisplayDeviceInfo"
            )
        )
    }

    private fun handleDumpsysPreset(
        action: String,
        title: String,
        service: String,
        subcommand: String?,
        target: String?,
        obj: JsonObject,
        timeout: Int,
        summaryLines: List<Pair<String, List<String>>> = emptyList(),
        defaultKeywords: List<String> = emptyList()
    ): String {
        val grep = obj.string("grep").orEmpty()
        val limit = (obj.int("lines") ?: DEFAULT_OUTPUT_LIMIT).coerceIn(1, 300)
        val output = runCommand(buildDumpsysCommand(service, subcommand, target), timeout)
        if (isFailureOutput(output)) return output
        val lines = output.lines().map { it.trimEnd() }
        val matchedLines = lines.filter { line ->
            when {
                grep.isNotBlank() -> line.contains(grep, ignoreCase = true)
                defaultKeywords.isNotEmpty() -> defaultKeywords.any { keyword ->
                    line.contains(keyword, ignoreCase = true)
                }
                else -> line.isNotBlank()
            }
        }
        val summary = linkedMapOf<String, Any?>()
        summaryLines.forEach { (label, patterns) ->
            findFirstMatchingLine(output, patterns)?.let { summary[label] = it }
        }
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to action,
                "title" to title,
                "service" to service,
                "subcommand" to subcommand,
                "target" to target,
                "filter" to grep.ifBlank { null },
                "summary" to summary,
                "matchCount" to matchedLines.size,
                "truncated" to (matchedLines.size > limit),
                "matches" to matchedLines.take(limit)
            )
        )
    }

    private fun buildDumpsysCommand(service: String, subcommand: String?, target: String?): String {
        return buildString {
            append("dumpsys ")
            append(service)
            subcommand?.takeIf { it.isNotBlank() }?.let {
                append(' ')
                append(it)
            }
            target?.takeIf { it.isNotBlank() }?.let {
                append(' ')
                append(shQuote(it))
            }
        }
    }

    private fun resolveDumpsysTarget(obj: JsonObject): String? {
        return obj.string("target")?.takeIf { it.isNotBlank() }
            ?: obj.string("packageName")?.takeIf { it.isNotBlank() }
            ?: obj.int("pid")?.toString()
    }

    private fun handleSettingsGet(obj: JsonObject, timeout: Int): String {
        val namespace = obj.string("namespace") ?: return "Error: 'namespace' is required for settings_get"
        val name = obj.string("name") ?: return "Error: 'name' is required for settings_get"
        val output = runCommand("settings get $namespace ${shQuote(name)}", timeout)
        if (isFailureOutput(output)) return output
        val value = output.trim().ifBlank { null }
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "settings_get",
                "namespace" to namespace,
                "name" to name,
                "value" to value,
                "empty" to (value == null),
                "rawOutput" to output
            )
        )
    }

    private fun handleSettingsPut(obj: JsonObject, timeout: Int): String {
        val namespace = obj.string("namespace") ?: return "Error: 'namespace' is required for settings_put"
        val name = obj.string("name") ?: return "Error: 'name' is required for settings_put"
        val value = obj.string("value") ?: return "Error: 'value' is required for settings_put"
        return runAndFormat(
            summary = "系统设置写入命令已执行",
            command = "settings put $namespace ${shQuote(name)} ${shQuote(value)}",
            timeout = timeout
        )
    }

    private fun handleSettingsList(obj: JsonObject, timeout: Int): String {
        val namespace = obj.string("namespace") ?: return "Error: 'namespace' is required for settings_list"
        val grep = obj.string("grep").orEmpty()
        val limit = (obj.int("lines") ?: DEFAULT_SETTINGS_LIST_LIMIT).coerceIn(1, 300)
        val output = runCommand("settings list $namespace", timeout)
        if (isFailureOutput(output)) return output
        val lines = output.lines()
            .filter { line -> grep.isBlank() || line.contains(grep, ignoreCase = true) }
            .take(limit)
            .toList()
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "settings_list",
                "namespace" to namespace,
                "filter" to grep.ifBlank { null },
                "showing" to lines.size,
                "items" to lines.map { line ->
                    linkedMapOf<String, Any?>(
                        "raw" to line,
                        "name" to line.substringBefore("=", "").ifBlank { null },
                        "value" to line.substringAfter("=", "").takeIf { line.contains("=") }
                    )
                }
            )
        )
    }

    private fun handleNetworkSummary(obj: JsonObject, timeout: Int): String {
        val limit = (obj.int("lines") ?: DEFAULT_NETWORK_SUMMARY_LIMIT).coerceIn(1, 200)
        val routeOutput = runCommand("ip route 2>/dev/null || true", timeout)
        val addrOutput = runCommand("ip addr 2>/dev/null || true", timeout)
        val dnsOutput = runCommand("getprop | grep -E '\\[net\\.|\\[dhcp\\.|\\[wifi\\.|\\[gsm\\.|\\[ril\\.' || true", timeout)
        val wifiOutput = runCommand("dumpsys wifi | grep -E 'Wi-Fi is|mNetworkInfo|SSID|BSSID|RSSI|Link speed' || true", timeout)
        val mobileOutput = runCommand("dumpsys telephony.registry | grep -E 'mServiceState|mSignalStrength|mDataConnectionState' || true", timeout)
        val routeLines = routeOutput.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(limit.coerceAtMost(40))
        val addrLines = addrOutput.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(limit.coerceAtMost(60))
        val dnsLines = dnsOutput.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(limit.coerceAtMost(50))
        val wifiLines = wifiOutput.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(limit.coerceAtMost(30))
        val mobileLines = mobileOutput.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(limit.coerceAtMost(30))
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "network_summary",
                "limit" to limit,
                "summary" to linkedMapOf<String, Any?>(
                    "routeCount" to routeLines.size,
                    "interfaceLineCount" to addrLines.size,
                    "hasDnsProperties" to dnsLines.isNotEmpty(),
                    "hasWifiInfo" to wifiLines.isNotEmpty(),
                    "hasMobileInfo" to mobileLines.isNotEmpty()
                ),
                "routes" to routeLines,
                "interfaces" to addrLines,
                "properties" to dnsLines,
                "wifi" to wifiLines,
                "mobile" to mobileLines
            )
        )
    }

    private fun handleNotificationSummary(obj: JsonObject, timeout: Int): String {
        val packageName = obj.string("packageName")
        val grep = obj.string("grep").orEmpty()
        val limit = (obj.int("lines") ?: DEFAULT_NOTIFICATION_SUMMARY_LIMIT).coerceIn(1, 200)
        val dumpOutput = runCommand(
            "dumpsys notification --noredact 2>/dev/null || dumpsys notification 2>/dev/null || true",
            timeout
        )
        if (isFailureOutput(dumpOutput)) return dumpOutput
        val listenersOutput = runCommand("settings get secure enabled_notification_listeners 2>/dev/null || true", timeout)
        val assistantOutput = runCommand("settings get secure enabled_notification_assistant 2>/dev/null || true", timeout)
        val badgingOutput = runCommand("settings get secure notification_badging 2>/dev/null || true", timeout)
        val lockscreenOutput = runCommand("settings get secure lock_screen_show_notifications 2>/dev/null || true", timeout)
        val headsUpOutput = runCommand("settings get global heads_up_notifications_enabled 2>/dev/null || true", timeout)
        val matchedLines = dumpOutput.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .filter { line ->
                val trimmed = line.trim()
                val matchesPackage = packageName.isNullOrBlank() || trimmed.contains(packageName, ignoreCase = true)
                val matchesKeyword = grep.isBlank() || trimmed.contains(grep, ignoreCase = true)
                val interesting = trimmed.contains("NotificationRecord(") ||
                    trimmed.contains("StatusBarNotification(") ||
                    trimmed.contains("pkg=") ||
                    trimmed.contains("channelId=") ||
                    trimmed.contains("android.title=") ||
                    trimmed.contains("android.text=") ||
                    trimmed.contains("tickerText=") ||
                    trimmed.contains("mNotificationList") ||
                    trimmed.contains("mEnqueuedNotifications")
                (matchesPackage && matchesKeyword && interesting) ||
                    (packageName.isNullOrBlank() && grep.isBlank() && interesting)
            }
            .take(limit)
            .toList()
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "notification_summary",
                "packageName" to packageName,
                "filter" to grep.ifBlank { null },
                "summary" to linkedMapOf<String, Any?>(
                    "enabledListeners" to listenersOutput.ifBlank { null },
                    "notificationAssistant" to assistantOutput.ifBlank { null },
                    "notificationBadging" to badgingOutput.ifBlank { null },
                    "lockScreenShowNotifications" to lockscreenOutput.ifBlank { null },
                    "headsUpEnabled" to headsUpOutput.ifBlank { null }
                ),
                "matchCount" to matchedLines.size,
                "truncated" to (matchedLines.size >= limit),
                "matches" to matchedLines
            )
        )
    }

    private fun handleNotificationChannels(obj: JsonObject, timeout: Int): String {
        val packageName = obj.string("packageName") ?: return "Error: 'packageName' is required for notification_channels"
        val grep = obj.string("grep").orEmpty()
        val limit = (obj.int("lines") ?: DEFAULT_NOTIFICATION_SUMMARY_LIMIT).coerceIn(1, 200)
        val dumpOutput = runCommand(
            "dumpsys notification --noredact 2>/dev/null || dumpsys notification 2>/dev/null || true",
            timeout
        )
        if (isFailureOutput(dumpOutput)) return dumpOutput
        val rawLines = dumpOutput.lines()
        val packageIndexes = rawLines.mapIndexedNotNull { index, line ->
            if (line.contains(packageName, ignoreCase = true)) index else null
        }
        val selectedIndexes = linkedSetOf<Int>()
        packageIndexes.forEach { index ->
            for (cursor in (index - 4).coerceAtLeast(0)..(index + 8).coerceAtMost(rawLines.lastIndex)) {
                val line = rawLines[cursor]
                val normalized = line.trim()
                val matchesKeyword = grep.isBlank() || normalized.contains(grep, ignoreCase = true)
                val channelRelated = normalized.contains("NotificationChannel", ignoreCase = true) ||
                    normalized.contains("channelId=", ignoreCase = true) ||
                    normalized.contains("importance=", ignoreCase = true) ||
                    normalized.contains("group=", ignoreCase = true) ||
                    normalized.contains(packageName, ignoreCase = true)
                if (matchesKeyword && channelRelated) {
                    selectedIndexes += cursor
                }
            }
        }
        val matchedLines = selectedIndexes.toList()
            .sorted()
            .map { rawLines[it].trimEnd() }
            .filter { it.isNotBlank() }
            .take(limit)
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "notification_channels",
                "packageName" to packageName,
                "filter" to grep.ifBlank { null },
                "packageMentionCount" to packageIndexes.size,
                "matchCount" to matchedLines.size,
                "truncated" to (selectedIndexes.size > matchedLines.size),
                "matches" to matchedLines
            )
        )
    }

    private fun handleNotificationPermissionStatus(obj: JsonObject, timeout: Int): String {
        val packageName = obj.string("packageName") ?: return "Error: 'packageName' is required for notification_permission_status"
        val limit = (obj.int("lines") ?: DEFAULT_NOTIFICATION_SUMMARY_LIMIT).coerceIn(1, 120)
        val packageDump = runCommand("dumpsys package ${shQuote(packageName)} 2>/dev/null || true", timeout)
        if (isFailureOutput(packageDump)) return packageDump
        val permissionCheck = runCommand(
            "pm check-permission ${shQuote(packageName)} android.permission.POST_NOTIFICATIONS 2>/dev/null || true",
            timeout
        )
        val appOpsOutput = runCommand(
            "cmd appops get ${shQuote(packageName)} POST_NOTIFICATION 2>/dev/null || appops get ${shQuote(packageName)} POST_NOTIFICATION 2>/dev/null || true",
            timeout
        )
        val appOpsFallback = if (appOpsOutput.isBlank() || appOpsOutput == "(command completed, no output)") {
            runCommand(
                "cmd appops get ${shQuote(packageName)} 2>/dev/null || appops get ${shQuote(packageName)} 2>/dev/null || true",
                timeout
            )
        } else {
            ""
        }
        val notificationDump = runCommand(
            "dumpsys notification --noredact 2>/dev/null || dumpsys notification 2>/dev/null || true",
            timeout
        )
        if (isFailureOutput(notificationDump)) return notificationDump
        val globalBadging = runCommand("settings get secure notification_badging 2>/dev/null || true", timeout)
        val globalHeadsUp = runCommand("settings get global heads_up_notifications_enabled 2>/dev/null || true", timeout)
        val postPermissionLines = packageDump.lines()
            .filter { line ->
                val trimmed = line.trim()
                trimmed.contains("POST_NOTIFICATIONS", ignoreCase = true) ||
                    trimmed.contains("runtime permissions:", ignoreCase = true) ||
                    trimmed.contains("install permissions:", ignoreCase = true)
            }
            .take(limit)
            .toList()
        val notificationServiceLines = notificationDump.lines()
            .filter { line ->
                val trimmed = line.trim()
                trimmed.contains(packageName, ignoreCase = true) &&
                    (
                        trimmed.contains("PackagePreferences", ignoreCase = true) ||
                            trimmed.contains("importance=", ignoreCase = true) ||
                            trimmed.contains("userSetImportance", ignoreCase = true) ||
                            trimmed.contains("banned=", ignoreCase = true) ||
                            trimmed.contains("showBadge", ignoreCase = true)
                        )
            }
            .take(limit)
            .toList()
        val appOpsBody = listOf(appOpsOutput, appOpsFallback)
            .filter { it.isNotBlank() && it != "(command completed, no output)" }
            .joinToString("\n")
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "notification_permission_status",
                "packageName" to packageName,
                "summary" to linkedMapOf<String, Any?>(
                    "permissionCheck" to permissionCheck.ifBlank { null },
                    "notificationBadging" to globalBadging.ifBlank { null },
                    "headsUpEnabled" to globalHeadsUp.ifBlank { null }
                ),
                "postNotificationLines" to postPermissionLines,
                "appOpsLines" to appOpsBody.lines().map { it.trimEnd() }.filter { it.isNotBlank() },
                "notificationServiceLines" to notificationServiceLines
            )
        )
    }

    private fun handleAppSpecialAccessStatus(obj: JsonObject, timeout: Int): String {
        val packageName = obj.string("packageName") ?: return "Error: 'packageName' is required for app_special_access_status"
        val limit = (obj.int("lines") ?: DEFAULT_NOTIFICATION_SUMMARY_LIMIT).coerceIn(1, 120)
        val packageDump = runCommand("dumpsys package ${shQuote(packageName)} 2>/dev/null || true", timeout)
        if (isFailureOutput(packageDump)) return packageDump
        val batteryWhitelist = runCommand("dumpsys deviceidle whitelist 2>/dev/null || true", timeout)
        val batteryTempWhitelist = runCommand("dumpsys deviceidle tempwhitelist 2>/dev/null || true", timeout)
        val overlayAppOps = runCommand(
            "cmd appops get ${shQuote(packageName)} SYSTEM_ALERT_WINDOW 2>/dev/null || appops get ${shQuote(packageName)} SYSTEM_ALERT_WINDOW 2>/dev/null || true",
            timeout
        )
        val installUnknownAppOps = runCommand(
            "cmd appops get ${shQuote(packageName)} REQUEST_INSTALL_PACKAGES 2>/dev/null || appops get ${shQuote(packageName)} REQUEST_INSTALL_PACKAGES 2>/dev/null || true",
            timeout
        )
        val packageRelevantLines = packageDump.lines()
            .filter { line ->
                val trimmed = line.trim()
                trimmed.contains("SYSTEM_ALERT_WINDOW", ignoreCase = true) ||
                    trimmed.contains("REQUEST_INSTALL_PACKAGES", ignoreCase = true) ||
                    trimmed.contains("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", ignoreCase = true) ||
                    trimmed.contains("runtime permissions:", ignoreCase = true) ||
                    trimmed.contains("install permissions:", ignoreCase = true)
            }
            .take(limit)
            .toList()
        val inBatteryWhitelist = batteryWhitelist.contains(packageName, ignoreCase = true)
        val inBatteryTempWhitelist = batteryTempWhitelist.contains(packageName, ignoreCase = true)
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "app_special_access_status",
                "packageName" to packageName,
                "summary" to linkedMapOf<String, Any?>(
                    "batteryWhitelist" to inBatteryWhitelist,
                    "batteryTempWhitelist" to inBatteryTempWhitelist
                ),
                "overlayAppOpsLines" to overlayAppOps.lines().map { it.trimEnd() }.filter { it.isNotBlank() },
                "unknownSourcesAppOpsLines" to installUnknownAppOps.lines().map { it.trimEnd() }.filter { it.isNotBlank() },
                "packageRelevantLines" to packageRelevantLines
            )
        )
    }

    private fun handleAppNotificationListenerStatus(obj: JsonObject, timeout: Int): String {
        val packageName = obj.string("packageName") ?: return "Error: 'packageName' is required for app_notification_listener_status"
        val enabledListeners = runCommand("settings get secure enabled_notification_listeners 2>/dev/null || true", timeout)
        val notificationDump = runCommand("dumpsys notification 2>/dev/null || true", timeout)
        if (isFailureOutput(notificationDump)) return notificationDump
        val matchedLines = notificationDump.lines()
            .filter { line ->
                val trimmed = line.trim()
                trimmed.contains(packageName, ignoreCase = true) &&
                    (
                        trimmed.contains("listener", ignoreCase = true) ||
                            trimmed.contains("Notification listener", ignoreCase = true) ||
                            trimmed.contains("service", ignoreCase = true)
                        )
            }
            .take(DEFAULT_NOTIFICATION_SUMMARY_LIMIT)
            .toList()
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "app_notification_listener_status",
                "packageName" to packageName,
                "enabledInSettings" to enabledListeners.contains(packageName, ignoreCase = true),
                "enabledListenersRaw" to enabledListeners.ifBlank { null },
                "notificationServiceLines" to matchedLines
            )
        )
    }

    private fun handleAppAccessibilityStatus(obj: JsonObject, timeout: Int): String {
        val packageName = obj.string("packageName") ?: return "Error: 'packageName' is required for app_accessibility_status"
        val enabledServices = runCommand("settings get secure enabled_accessibility_services 2>/dev/null || true", timeout)
        val accessibilityEnabled = runCommand("settings get secure accessibility_enabled 2>/dev/null || true", timeout)
        val accessibilityDump = runCommand("dumpsys accessibility 2>/dev/null || true", timeout)
        if (isFailureOutput(accessibilityDump)) return accessibilityDump
        val matchedLines = accessibilityDump.lines()
            .filter { line ->
                val trimmed = line.trim()
                trimmed.contains(packageName, ignoreCase = true) ||
                    trimmed.contains("enabled services", ignoreCase = true) ||
                    trimmed.contains("bound services", ignoreCase = true)
            }
            .take(DEFAULT_NOTIFICATION_SUMMARY_LIMIT)
            .toList()
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "app_accessibility_status",
                "packageName" to packageName,
                "accessibilityEnabled" to accessibilityEnabled.ifBlank { null },
                "enabledInSettings" to enabledServices.contains(packageName, ignoreCase = true),
                "enabledServicesRaw" to enabledServices.ifBlank { null },
                "accessibilityDumpLines" to matchedLines
            )
        )
    }

    private fun handleAppUsageAccessStatus(obj: JsonObject, timeout: Int): String {
        val packageName = obj.string("packageName") ?: return "Error: 'packageName' is required for app_usage_access_status"
        val appOpsOutput = runCommand(
            "cmd appops get ${shQuote(packageName)} GET_USAGE_STATS 2>/dev/null || appops get ${shQuote(packageName)} GET_USAGE_STATS 2>/dev/null || true",
            timeout
        )
        val usagestatsDump = runCommand("dumpsys usagestats 2>/dev/null || true", timeout)
        if (isFailureOutput(usagestatsDump)) return usagestatsDump
        val matchedLines = usagestatsDump.lines()
            .filter { line ->
                val trimmed = line.trim()
                trimmed.contains(packageName, ignoreCase = true) ||
                    trimmed.contains("UsageStats", ignoreCase = true) ||
                    trimmed.contains("AppStandby", ignoreCase = true)
            }
            .take(DEFAULT_NOTIFICATION_SUMMARY_LIMIT)
            .toList()
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "app_usage_access_status",
                "packageName" to packageName,
                "appOpsLines" to appOpsOutput.lines().map { it.trimEnd() }.filter { it.isNotBlank() },
                "usageStatsDumpLines" to matchedLines
            )
        )
    }

    private fun handleNotificationOpenSettings(obj: JsonObject, timeout: Int): String {
        val packageName = obj.string("packageName") ?: return "Error: 'packageName' is required for notification_open_settings"
        val packageDump = runCommand("dumpsys package ${shQuote(packageName)} 2>/dev/null || true", timeout)
        if (isFailureOutput(packageDump)) return packageDump
        val uid = findFirstGroup(packageDump, Regex("""userId=(\d+)"""))
        val command = buildString {
            append("am start -W")
            append(" -a android.settings.APP_NOTIFICATION_SETTINGS")
            append(" --es android.provider.extra.APP_PACKAGE ${shQuote(packageName)}")
            append(" --es app_package ${shQuote(packageName)}")
            uid?.let { append(" --ei app_uid $it") }
        }
        return runAndFormat("应用通知设置页启动命令已执行", command, timeout)
    }

    private fun handleAppOpenSettings(obj: JsonObject, timeout: Int): String {
        val packageName = obj.string("packageName") ?: return "Error: 'packageName' is required for app_open_settings"
        val command = buildString {
            append("am start -W")
            append(" -a android.settings.APPLICATION_DETAILS_SETTINGS")
            append(" -d ")
            append(shQuote("package:$packageName"))
        }
        return runAndFormat("应用详情设置页启动命令已执行", command, timeout)
    }

    private fun handleAppOpenPermissionSettings(obj: JsonObject, timeout: Int): String {
        val packageName = obj.string("packageName") ?: return "Error: 'packageName' is required for app_open_permission_settings"
        val permission = obj.string("permission")
        val command = buildString {
            append("am start -W")
            append(" -a android.intent.action.MANAGE_APP_PERMISSIONS")
            append(" --es android.intent.extra.PACKAGE_NAME ${shQuote(packageName)}")
            append(" --es package_name ${shQuote(packageName)}")
            permission?.takeIf { it.isNotBlank() }?.let {
                append(" --es android.intent.extra.PERMISSION_NAME ${shQuote(it)}")
                append(" --es permission_name ${shQuote(it)}")
            }
        }
        return runAndFormat("应用权限页启动命令已执行", command, timeout)
    }

    private fun handleAppOpenBatterySettings(obj: JsonObject, timeout: Int): String {
        val packageName = obj.string("packageName") ?: return "Error: 'packageName' is required for app_open_battery_settings"
        val command = buildString {
            append("am start -W")
            append(" -a android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
            append(" -d ")
            append(shQuote("package:$packageName"))
        }
        return runAndFormat("电池优化设置页启动命令已执行", command, timeout)
    }

    private fun handleAppOpenOverlaySettings(obj: JsonObject, timeout: Int): String {
        val packageName = obj.string("packageName") ?: return "Error: 'packageName' is required for app_open_overlay_settings"
        val command = buildString {
            append("am start -W")
            append(" -a android.settings.action.MANAGE_OVERLAY_PERMISSION")
            append(" -d ")
            append(shQuote("package:$packageName"))
        }
        return runAndFormat("悬浮窗设置页启动命令已执行", command, timeout)
    }

    private fun handleAppOpenUnknownSourcesSettings(obj: JsonObject, timeout: Int): String {
        val packageName = obj.string("packageName") ?: return "Error: 'packageName' is required for app_open_unknown_sources_settings"
        val command = buildString {
            append("am start -W")
            append(" -a android.settings.MANAGE_UNKNOWN_APP_SOURCES")
            append(" -d ")
            append(shQuote("package:$packageName"))
        }
        return runAndFormat("未知来源安装设置页启动命令已执行", command, timeout)
    }

    private fun handleAppOpenNotificationListenerSettings(obj: JsonObject, timeout: Int): String {
        val packageName = obj.string("packageName")
        val command = buildString {
            append("am start -W")
            append(" -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            if (!packageName.isNullOrBlank()) {
                append(" --es android.provider.extra.APP_PACKAGE ${shQuote(packageName)}")
                append(" --es app_package ${shQuote(packageName)}")
            }
        }
        return runAndFormat("通知监听设置页启动命令已执行", command, timeout)
    }

    private fun handleAppOpenAccessibilitySettings(timeout: Int): String {
        return runAndFormat(
            "无障碍设置页启动命令已执行",
            "am start -W -a android.settings.ACCESSIBILITY_SETTINGS",
            timeout
        )
    }

    private fun handleAppOpenUsageAccessSettings(timeout: Int): String {
        return runAndFormat(
            "使用情况访问设置页启动命令已执行",
            "am start -W -a android.settings.USAGE_ACCESS_SETTINGS",
            timeout
        )
    }

    private fun handleAppInstallSessions(obj: JsonObject, timeout: Int): String {
        val packageName = obj.string("packageName")
        val grep = obj.string("grep").orEmpty()
        val limit = (obj.int("lines") ?: DEFAULT_NOTIFICATION_SUMMARY_LIMIT).coerceIn(1, 200)
        val installLocation = runCommand("pm get-install-location 2>/dev/null || true", timeout)
        val stagedSessions = runCommand(
            "cmd package list staged-sessions 2>/dev/null || pm list staged-sessions 2>/dev/null || true",
            timeout
        )
        val installerDump = runCommand(
            "dumpsys package 2>/dev/null | grep -iE 'PackageInstallerSession|staged session|sessionId=|installerPackageName=|createdMillis=|updatedMillis=' || true",
            timeout
        )
        if (isFailureOutput(installerDump)) return installerDump
        val filteredDumpLines = installerDump.lines()
            .filter { line ->
                val trimmed = line.trim()
                val matchesPackage = packageName.isNullOrBlank() || trimmed.contains(packageName, ignoreCase = true)
                val matchesKeyword = grep.isBlank() || trimmed.contains(grep, ignoreCase = true)
                matchesPackage && matchesKeyword
            }
            .take(limit)
            .toList()
        return buildString {
            append("安装会话摘要\n")
            packageName?.let { append("Package: $it\n") }
            if (grep.isNotBlank()) append("Filter: $grep\n")
            append("InstallLocation: ${installLocation.ifBlank { "(empty)" }}\n")
            append("\nStaged Sessions:\n")
            append(
                stagedSessions.ifBlank { "(没有 staged session 输出)" }
                    .let { truncateLines(it, limit.coerceAtMost(60)) }
            )
            append("\n\nInstaller Session Details:\n")
            append(
                filteredDumpLines.joinToString("\n").ifBlank {
                    if (packageName.isNullOrBlank() && grep.isBlank()) "(未提取到安装会话细节)"
                    else "未找到匹配的安装会话记录。"
                }
            )
        }
    }

    private fun handleIntent(obj: JsonObject, timeout: Int, broadcast: Boolean): String {
        val actionName = obj.string("actionName")
        val dataUri = obj.string("dataUri")
        val mimeType = obj.string("mimeType")
        val component = resolveComponent(obj) ?: obj.string("component")
        val packageName = obj.string("packageName")
        val categories = obj.stringList("categories")
        val extras = obj["extras"]?.jsonObject.orEmpty()
        if (actionName.isNullOrBlank() && dataUri.isNullOrBlank() && component.isNullOrBlank() && packageName.isNullOrBlank()) {
            return "Error: intent_start / intent_broadcast 至少需要 actionName、dataUri、component 或 packageName 之一"
        }
        val command = buildString {
            append(if (broadcast) "am broadcast" else "am start -W")
            actionName?.takeIf { it.isNotBlank() }?.let { append(" -a ${shQuote(it)}") }
            dataUri?.takeIf { it.isNotBlank() }?.let { append(" -d ${shQuote(it)}") }
            mimeType?.takeIf { it.isNotBlank() }?.let { append(" -t ${shQuote(it)}") }
            if (!component.isNullOrBlank()) {
                append(" -n ${shQuote(component)}")
            } else {
                packageName?.takeIf { it.isNotBlank() }?.let { append(" -p ${shQuote(it)}") }
            }
            categories.forEach { append(" -c ${shQuote(it)}") }
            extras.forEach { (key, value) ->
                append(" --es ${shQuote(key)} ${shQuote(value.jsonPrimitive.content)}")
            }
        }
        val output = runCommand(command, timeout)
        if (isFailureOutput(output)) return output
        val extrasMap = linkedMapOf<String, Any?>().apply {
            extras.forEach { (key, value) -> this[key] = value.jsonPrimitive.content }
        }
        val action = if (broadcast) "intent_broadcast" else "intent_start"
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to action,
                "broadcast" to broadcast,
                "actionName" to actionName,
                "dataUri" to dataUri,
                "mimeType" to mimeType,
                "component" to component,
                "packageName" to packageName,
                "categories" to categories,
                "extras" to extrasMap,
                "command" to command,
                "success" to (!output.contains("Exception", ignoreCase = true) &&
                    !output.contains("Error:", ignoreCase = true)),
                "summary" to linkedMapOf<String, Any?>(
                    "resolvedComponent" to (findFirstGroup(output, Regex("""cmp=([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)""")) ?: component),
                    "intent" to findFirstGroup(output, Regex("""intent=\{([^}]*)\}""")),
                    "broadcastResult" to findFirstMatchingLine(output, listOf("Broadcast completed:", "Broadcasting:")),
                    "status" to findFirstMatchingLine(output, listOf("Status:", "Activity:", "Starting:"))
                ),
                "outputLines" to output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT)
            )
        )
    }

    private fun handleUiCurrentFocus(timeout: Int): String {
        val output = runCommand(
            "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' || true",
            timeout
        )
        if (isFailureOutput(output)) return output
        val cleaned = output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        val component = findFirstGroup(output, Regex("""([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)"""))
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "ui_current_focus",
                "component" to component,
                "packageName" to component?.substringBefore("/"),
                "focusedWindow" to findFirstMatchingLine(output, listOf("mCurrentFocus")),
                "focusedApp" to findFirstMatchingLine(output, listOf("mFocusedApp")),
                "hasFocus" to cleaned.isNotEmpty(),
                "lines" to cleaned
            )
        )
    }

    private fun handleUiDump(obj: JsonObject, timeout: Int): String {
        val outputPath = obj.string("outputPath") ?: "$TMP_DIR/murongagent-ui.xml"
        val dumpCommand = "uiautomator dump ${shQuote(outputPath)} >/dev/null 2>&1 && cat ${shQuote(outputPath)}"
        val output = runCommand(dumpCommand, timeout)
        if (isFailureOutput(output)) return output
        val limit = (obj.int("lines") ?: DEFAULT_UI_DUMP_LINES).coerceIn(1, 400)
        return buildString {
            append("UI 层级已导出\n")
            append("Path: $outputPath\n\n")
            append(truncateLines(output, limit))
        }
    }

    private fun handleUiScreenshot(obj: JsonObject, timeout: Int): String {
        val outputPath = obj.string("outputPath") ?: "$TMP_DIR/murongagent-screenshot-${System.currentTimeMillis()}.png"
        val command = "screencap -p ${shQuote(outputPath)} && ls -l ${shQuote(outputPath)}"
        val output = runCommand(command, timeout)
        if (isFailureOutput(output)) return output
        return buildString {
            append("截图已保存\n")
            append("Path: $outputPath")
            if (output.isNotBlank() && output != "(command completed, no output)") {
                append("\n\n")
                append(output)
            }
        }
    }

    private fun handleUiDumpSummary(obj: JsonObject, timeout: Int): String {
        val xml = readUiHierarchyXml(obj, timeout) ?: return "Error: 无法导出 UI 层级"
        val nodes = parseUiNodes(xml)
        if (nodes.isEmpty()) {
            return "未在 UI 层级中解析到任何节点。"
        }
        val limit = (obj.int("lines") ?: DEFAULT_UI_SUMMARY_LIMIT).coerceIn(1, 120)
        val preview = nodes.asSequence()
            .filter { node -> node.text.isNotBlank() || node.contentDesc.isNotBlank() || node.resourceId.isNotBlank() }
            .take(limit)
            .mapIndexed { index, node -> renderUiNodeSummary(index, node) }
            .toList()
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "ui_dump_summary",
                "nodeCount" to nodes.size,
                "showing" to preview.size,
                "nodes" to preview
            )
        )
    }

    private fun handleUiFind(obj: JsonObject, timeout: Int): String {
        val matches = findUiNodes(obj, timeout) ?: return "Error: 无法读取 UI 层级"
        if (matches.isEmpty()) {
            return "未找到匹配节点。"
        }
        val limit = (obj.int("lines") ?: DEFAULT_UI_FIND_LIMIT).coerceIn(1, 80)
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "ui_find",
                "matchCount" to matches.size,
                "showing" to matches.take(limit).size,
                "truncated" to (matches.size > limit),
                "nodes" to matches.take(limit).mapIndexed(::renderUiNodeSummary)
            )
        )
    }

    private fun handleUiClickNode(obj: JsonObject, timeout: Int): String {
        val matches = findUiNodes(obj, timeout) ?: return "Error: 无法读取 UI 层级"
        if (matches.isEmpty()) {
            return "未找到可点击的匹配节点。"
        }
        val index = (obj.int("index") ?: 0).coerceAtLeast(0)
        val node = matches.getOrNull(index) ?: return "Error: 匹配节点只有 ${matches.size} 个，index=$index 超出范围"
        val command = "input tap ${node.centerX} ${node.centerY}"
        val output = runCommand(command, timeout)
        if (isFailureOutput(output)) return output
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "ui_click_node",
                "index" to index,
                "matchCount" to matches.size,
                "command" to command,
                "success" to true,
                "node" to renderUiNodeSummary(index, node),
                "outputLines" to output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT)
            )
        )
    }

    private fun handleUiClickFirst(obj: JsonObject, timeout: Int): String {
        val rewrittenArgs = buildJsonObjectString(obj, "index" to "0")
        return handleUiClickNode(
            obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
            timeout = timeout
        )
    }

    private fun handleUiLongPressNode(obj: JsonObject, timeout: Int): String {
        val matches = findUiNodes(obj, timeout) ?: return "Error: 无法读取 UI 层级"
        if (matches.isEmpty()) {
            return "未找到可长按的匹配节点。"
        }
        val index = (obj.int("index") ?: 0).coerceAtLeast(0)
        val durationMs = (obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS).coerceIn(100, 60_000)
        val node = matches.getOrNull(index) ?: return "Error: 匹配节点只有 ${matches.size} 个，index=$index 超出范围"
        val output = runCommand(
            "input swipe ${node.centerX} ${node.centerY} ${node.centerX} ${node.centerY} $durationMs",
            timeout
        )
        if (isFailureOutput(output)) return output
        return buildString {
            append("已长按匹配节点\n")
            append("Index: $index\n")
            append("DurationMs: $durationMs\n")
            append("Class: ${node.className.ifBlank { "(unknown)" }}\n")
            if (node.resourceId.isNotBlank()) append("ResourceId: ${node.resourceId}\n")
            if (node.text.isNotBlank()) append("Text: ${node.text}\n")
            if (node.contentDesc.isNotBlank()) append("ContentDesc: ${node.contentDesc}\n")
            append("Bounds: ${node.boundsRaw}\n")
            append("Center: (${node.centerX}, ${node.centerY})")
        }
    }

    private fun handleUiLongPress(obj: JsonObject, timeout: Int): String {
        val x = obj.int("x") ?: return "Error: 'x' is required for ui_long_press"
        val y = obj.int("y") ?: return "Error: 'y' is required for ui_long_press"
        val durationMs = (obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS).coerceIn(100, 60_000)
        return runAndFormat("长按命令已执行", "input swipe $x $y $x $y $durationMs", timeout)
    }

    private fun handleUiLongPressPercent(obj: JsonObject, timeout: Int): String {
        val rawXPercent = obj.double("xPercent") ?: return "Error: 'xPercent' is required for ui_long_press_percent"
        val rawYPercent = obj.double("yPercent") ?: return "Error: 'yPercent' is required for ui_long_press_percent"
        val xPercent = normalizePercentValue(rawXPercent)
            ?: return "Error: 'xPercent' must be in 0.0-1.0 or 0-100 range for ui_long_press_percent"
        val yPercent = normalizePercentValue(rawYPercent)
            ?: return "Error: 'yPercent' must be in 0.0-1.0 or 0-100 range for ui_long_press_percent"
        val displaySize = getDisplaySize(timeout)
        val width = displaySize?.first ?: DEFAULT_UI_SCROLL_WIDTH
        val height = displaySize?.second ?: DEFAULT_UI_SCROLL_HEIGHT
        val maxX = (width - 1).coerceAtLeast(0)
        val maxY = (height - 1).coerceAtLeast(0)
        val x = (maxX * xPercent).toInt().coerceIn(0, maxX)
        val y = (maxY * yPercent).toInt().coerceIn(0, maxY)
        val durationMs = (obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS).coerceIn(100, 60_000)
        val rewrittenArgs = buildJsonObjectString(
            obj,
            "x" to x.toString(),
            "y" to y.toString(),
            "longPressDurationMs" to durationMs.toString()
        )
        val result = handleUiLongPress(
            obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
            timeout = timeout
        )
        if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) return result
        return buildString {
            append("百分比长按已执行\n")
            append("Display: ${width}x${height}\n")
            append("Percent: ($rawXPercent, $rawYPercent)\n")
            append("Point: ($x, $y)\n")
            append("DurationMs: $durationMs\n\n")
            append(result)
        }
    }

    private fun handleUiSetTextNode(obj: JsonObject, timeout: Int): String {
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_set_text_node"
        val matches = findUiNodes(obj, timeout) ?: return "Error: 无法读取 UI 层级"
        if (matches.isEmpty()) {
            return "未找到可输入的匹配节点。"
        }
        val index = (obj.int("index") ?: 0).coerceAtLeast(0)
        val node = matches.getOrNull(index) ?: return "Error: 匹配节点只有 ${matches.size} 个，index=$index 超出范围"
        val encodedText = encodeInputText(text)
        if (encodedText.isBlank()) {
            return "Error: 输入文本为空或无法编码"
        }
        val command = "input tap ${node.centerX} ${node.centerY}; sleep 0.2; input text ${shQuote(encodedText)}"
        val output = runCommand(command, timeout)
        if (isFailureOutput(output)) return output
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "ui_set_text_node",
                "index" to index,
                "matchCount" to matches.size,
                "command" to command,
                "success" to true,
                "inputText" to text.take(120),
                "clearedBeforeInput" to false,
                "note" to "当前实现会先点击再输入，不会主动清空已有文本。",
                "node" to renderUiNodeSummary(index, node),
                "outputLines" to output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT)
            )
        )
    }

    private fun handleUiSetTextClearNode(obj: JsonObject, timeout: Int): String {
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_set_text_clear_node"
        val matches = findUiNodes(obj, timeout) ?: return "Error: 无法读取 UI 层级"
        if (matches.isEmpty()) {
            return "未找到可输入的匹配节点。"
        }
        val index = (obj.int("index") ?: 0).coerceAtLeast(0)
        val node = matches.getOrNull(index) ?: return "Error: 匹配节点只有 ${matches.size} 个，index=$index 超出范围"
        val encodedText = encodeInputText(text)
        if (encodedText.isBlank()) {
            return "Error: 输入文本为空或无法编码"
        }
        val nodeTextLength = node.text.length
        val clearSteps = (obj.int("clearSteps") ?: nodeTextLength).coerceAtLeast(0)
        val clearCommands = if (clearSteps > 0) {
            List(clearSteps) { "input keyevent KEYCODE_DEL" }.joinToString("; ")
        } else {
            ""
        }
        val command = buildString {
            append("input tap ${node.centerX} ${node.centerY}; sleep 0.2; input keyevent KEYCODE_MOVE_END")
            if (clearCommands.isNotBlank()) {
                append("; sleep 0.1; ")
                append(clearCommands)
            }
            append("; sleep 0.2; input text ${shQuote(encodedText)}")
        }
        val output = runCommand(command, timeout)
        if (isFailureOutput(output)) return output
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "ui_set_text_clear_node",
                "index" to index,
                "matchCount" to matches.size,
                "command" to command,
                "success" to true,
                "inputText" to text.take(120),
                "clearSteps" to clearSteps,
                "clearedBeforeInput" to true,
                "note" to if (clearSteps == 0) {
                    "当前节点原文本为空，且未显式传 clearSteps，因此没有执行删除键。"
                } else {
                    null
                },
                "node" to renderUiNodeSummary(index, node),
                "outputLines" to output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT)
            )
        )
    }

    private fun handleUiSelectAllAndReplaceNode(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_select_all_and_replace_node 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_select_all_and_replace_node"
        val matches = findUiNodes(obj, timeout) ?: return "Error: 无法读取 UI 层级"
        if (matches.isEmpty()) {
            return "未找到可输入的匹配节点。"
        }
        val index = (obj.int("index") ?: 0).coerceAtLeast(0)
        val durationMs = (obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS).coerceIn(100, 60_000)
        val node = matches.getOrNull(index) ?: return "Error: 匹配节点只有 ${matches.size} 个，index=$index 超出范围"
        val encodedText = encodeInputText(text)
        if (encodedText.isBlank()) {
            return "Error: 输入文本为空或无法编码"
        }
        val focusAndPressOutput = runCommand(
            "input tap ${node.centerX} ${node.centerY}; sleep 0.2; input swipe ${node.centerX} ${node.centerY} ${node.centerX} ${node.centerY} $durationMs; sleep 0.5",
            timeout
        )
        if (isFailureOutput(focusAndPressOutput)) return focusAndPressOutput
        val selectAllNode = findSelectAllUiNode(timeout = 5)
        val clearSteps = (obj.int("clearSteps") ?: node.text.length).coerceAtLeast(0)
        val replaceCommand = buildString {
            if (selectAllNode != null) {
                append("input tap ${selectAllNode.centerX} ${selectAllNode.centerY}; sleep 0.3; input keyevent KEYCODE_DEL; sleep 0.2; ")
            } else {
                append("input keyevent KEYCODE_MOVE_END")
                if (clearSteps > 0) {
                    append("; sleep 0.1; ")
                    append(List(clearSteps) { "input keyevent KEYCODE_DEL" }.joinToString("; "))
                }
                append("; sleep 0.2; ")
            }
            append("input text ${shQuote(encodedText)}")
        }
        val replaceOutput = runCommand(replaceCommand, timeout)
        if (isFailureOutput(replaceOutput)) return replaceOutput
        return buildString {
            append("已尝试全选节点文本并替换为新文本\n")
            append("Index: $index\n")
            append("DurationMs: $durationMs\n")
            append("Class: ${node.className.ifBlank { "(unknown)" }}\n")
            if (node.resourceId.isNotBlank()) append("ResourceId: ${node.resourceId}\n")
            if (node.text.isNotBlank()) append("OriginalText: ${node.text}\n")
            if (node.contentDesc.isNotBlank()) append("ContentDesc: ${node.contentDesc}\n")
            append("Bounds: ${node.boundsRaw}\n")
            append("Center: (${node.centerX}, ${node.centerY})\n")
            append("InputText: ${text.take(120)}\n")
            append("SelectAllMenu: ")
            append(selectAllNode?.text?.ifBlank { selectAllNode.contentDesc.ifBlank { "(matched)" } } ?: "(not found)")
            if (selectAllNode == null) {
                append("\nFallbackClearSteps: $clearSteps")
                if (clearSteps == 0) {
                    append("\n\n注意：没有找到“全选”菜单项，且未执行删除回退；如果目标输入框未清空，可显式传 clearSteps 或先调用 ui_set_text_clear_node。")
                }
            }
        }
    }

    private fun handleUiWaitText(obj: JsonObject, timeout: Int): String {
        val waitText = obj.string("text") ?: obj.string("keyword")
            ?: return "Error: 'text' or 'keyword' is required for ui_wait_text"
        val deadline = System.currentTimeMillis() + timeout.coerceAtLeast(1) * 1000L
        var attempts = 0
        while (System.currentTimeMillis() <= deadline) {
            attempts++
            val matches = findUiNodes(obj, timeout = 5)
            if (!matches.isNullOrEmpty()) {
                val first = matches.first()
                return buildString {
                    append("等待成功，已找到目标文本节点\n")
                    append("Attempts: $attempts\n")
                    append("Matches: ${matches.size}\n")
                    append("Class: ${first.className.ifBlank { "(unknown)" }}\n")
                    if (first.resourceId.isNotBlank()) append("ResourceId: ${first.resourceId}\n")
                    if (first.text.isNotBlank()) append("Text: ${first.text}\n")
                    if (first.contentDesc.isNotBlank()) append("ContentDesc: ${first.contentDesc}\n")
                    append("Bounds: ${first.boundsRaw}")
                }
            }
            Thread.sleep(1000)
        }
        return "等待超时：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配文本 '$waitText'。"
    }

    private fun handleUiWaitGone(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_gone 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val deadline = System.currentTimeMillis() + timeout.coerceAtLeast(1) * 1000L
        var attempts = 0
        while (System.currentTimeMillis() <= deadline) {
            attempts++
            val matches = findUiNodes(obj, timeout = 5)
            if (matches.isNullOrEmpty()) {
                return buildString {
                    append("等待成功，目标节点已消失\n")
                    append("Attempts: $attempts")
                }
            }
            Thread.sleep(1000)
        }
        return "等待超时：在 ${timeout.coerceAtLeast(1)} 秒内目标节点仍存在。"
    }

    private fun handleUiWaitAndClickFirst(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_and_click_first 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        return runUiWaitUntilFoundLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待点击失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, attempts ->
            val first = matches.first()
            val tapOutput = runCommand("input tap ${first.centerX} ${first.centerY}", timeout)
            if (isFailureOutput(tapOutput)) return@runUiWaitUntilFoundLoop tapOutput
            buildString {
                append("等待后点击成功\n")
                append("Attempts: $attempts\n")
                append("Matches: ${matches.size}\n")
                append("Class: ${first.className.ifBlank { "(unknown)" }}\n")
                if (first.resourceId.isNotBlank()) append("ResourceId: ${first.resourceId}\n")
                if (first.text.isNotBlank()) append("Text: ${first.text}\n")
                if (first.contentDesc.isNotBlank()) append("ContentDesc: ${first.contentDesc}\n")
                append("Bounds: ${first.boundsRaw}\n")
                append("Center: (${first.centerX}, ${first.centerY})")
            }
        }
    }

    private fun handleUiWaitAndFind(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_and_find 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        return runUiWaitUntilFoundLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待查找失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, attempts ->
            val result = handleUiFind(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilFoundLoop result
            }
            buildString {
                append("等待后查找成功\n")
                append("Attempts: $attempts\n")
                append("Matches: ${matches.size}\n\n")
                append(result)
            }
        }
    }

    private fun handleUiWaitAndDump(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_and_dump 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val outputPath = obj.string("outputPath") ?: "$TMP_DIR/murongagent-ui.xml"
        return runUiWaitUntilFoundLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待导出界面树失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, attempts ->
            val result = handleUiDump(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilFoundLoop result
            }
            buildString {
                append("等待后已导出界面树\n")
                append("Attempts: $attempts\n")
                append("Matches: ${matches.size}\n")
                append("Path: $outputPath\n\n")
                append(result)
            }
        }
    }

    private fun handleUiWaitAndDumpSummary(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_and_dump_summary 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        return runUiWaitUntilFoundLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待读取界面摘要失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, attempts ->
            val result = handleUiDumpSummary(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilFoundLoop result
            }
            buildString {
                append("等待后已读取界面摘要\n")
                append("Attempts: $attempts\n")
                append("Matches: ${matches.size}\n\n")
                append(result)
            }
        }
    }

    private fun handleUiWaitAndCurrentFocus(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_and_current_focus 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        return runUiWaitUntilFoundLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待读取当前焦点失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, attempts ->
            val result = handleUiCurrentFocus(timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilFoundLoop result
            }
            buildString {
                append("等待后已读取当前焦点\n")
                append("Attempts: $attempts\n")
                append("Matches: ${matches.size}\n\n")
                append(result)
            }
        }
    }

    private fun handleUiWaitAndBackstackSummary(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_and_backstack_summary 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        return runUiWaitUntilFoundLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待读取 Activity 栈摘要失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, attempts ->
            val result = handleUiBackstackSummary(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilFoundLoop result
            }
            buildString {
                append("等待后已读取 Activity 栈摘要\n")
                append("Attempts: $attempts\n")
                append("Matches: ${matches.size}\n\n")
                append(result)
            }
        }
    }

    private fun handleUiWaitAndScreenshot(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_and_screenshot 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val outputPath = obj.string("outputPath") ?: "$TMP_DIR/murongagent-screenshot-${System.currentTimeMillis()}.png"
        return runUiWaitUntilFoundLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待截图失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, attempts ->
            val result = handleUiScreenshot(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilFoundLoop result
            }
            buildString {
                append("等待后已截图\n")
                append("Attempts: $attempts\n")
                append("Matches: ${matches.size}\n")
                append("Path: $outputPath\n\n")
                append(result)
            }
        }
    }

    private fun handleUiWaitAndText(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_and_text 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_wait_and_text"
        return runUiWaitUntilFoundLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待输入文本失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, attempts ->
            val result = handleUiText(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilFoundLoop result
            }
            buildString {
                append("等待后已输入文本\n")
                append("Attempts: $attempts\n")
                append("Matches: ${matches.size}\n")
                append("Text: ${text.take(80)}\n\n")
                append(result)
            }
        }
    }

    private fun handleUiWaitAndKeyevent(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_and_keyevent 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val keyCode = obj.string("keyCode") ?: return "Error: 'keyCode' is required for ui_wait_and_keyevent"
        val normalized = normalizeKeyCode(keyCode)
        return runUiWaitUntilFoundLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待发送按键失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, attempts ->
            val result = handleUiKeyevent(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilFoundLoop result
            }
            buildString {
                append("等待后已发送按键事件\n")
                append("Attempts: $attempts\n")
                append("Matches: ${matches.size}\n")
                append("KeyCode: $normalized\n\n")
                append(result)
            }
        }
    }

    private fun handleUiWaitAndTap(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_and_tap 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val x = obj.int("x") ?: return "Error: 'x' is required for ui_wait_and_tap"
        val y = obj.int("y") ?: return "Error: 'y' is required for ui_wait_and_tap"
        return runUiWaitUntilFoundLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待点击坐标失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, attempts ->
            val result = handleUiTap(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilFoundLoop result
            }
            buildString {
                append("等待后点击坐标成功\n")
                append("Attempts: $attempts\n")
                append("Matches: ${matches.size}\n")
                append("Point: ($x, $y)\n\n")
                append(result)
            }
        }
    }

    private fun handleUiWaitAndSwipe(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_and_swipe 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val x = obj.int("x") ?: return "Error: 'x' is required for ui_wait_and_swipe"
        val y = obj.int("y") ?: return "Error: 'y' is required for ui_wait_and_swipe"
        val x2 = obj.int("x2") ?: return "Error: 'x2' is required for ui_wait_and_swipe"
        val y2 = obj.int("y2") ?: return "Error: 'y2' is required for ui_wait_and_swipe"
        return runUiWaitUntilFoundLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待滑动坐标失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, attempts ->
            val result = handleUiSwipe(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilFoundLoop result
            }
            buildString {
                append("等待后滑动坐标成功\n")
                append("Attempts: $attempts\n")
                append("Matches: ${matches.size}\n")
                append("FromPoint: ($x, $y)\n")
                append("ToPoint: ($x2, $y2)\n\n")
                append(result)
            }
        }
    }

    private fun handleUiWaitAndBack(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_and_back 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        return runUiWaitUntilFoundLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待返回失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, attempts ->
            val output = runCommand("input keyevent KEYCODE_BACK", timeout)
            if (isFailureOutput(output)) {
                return@runUiWaitUntilFoundLoop output
            }
            buildString {
                append("等待后已返回\n")
                append("Attempts: $attempts\n")
                append("Matches: ${matches.size}")
            }
        }
    }

    private fun handleUiWaitAndLongPress(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_and_long_press 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val x = obj.int("x") ?: return "Error: 'x' is required for ui_wait_and_long_press"
        val y = obj.int("y") ?: return "Error: 'y' is required for ui_wait_and_long_press"
        val durationMs = (obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS).coerceIn(100, 60_000)
        return runUiWaitUntilFoundLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待长按坐标失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, attempts ->
            val result = handleUiLongPress(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilFoundLoop result
            }
            buildString {
                append("等待后长按坐标成功\n")
                append("Attempts: $attempts\n")
                append("Matches: ${matches.size}\n")
                append("Point: ($x, $y)\n")
                append("DurationMs: $durationMs\n\n")
                append(result)
            }
        }
    }

    private fun handleUiWaitAndLongPressPercent(obj: JsonObject, timeout: Int): String {
        val point = requirePercentPointSpec(obj, "ui_wait_and_long_press_percent") { return it }
        val durationMs = (obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS).coerceIn(100, 60_000)
        return runUiWaitUntilFoundLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待按百分比长按失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, attempts ->
            val result = handleUiLongPressPercent(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilFoundLoop result
            }
            buildString {
                append("等待后按百分比长按成功\n")
                append("Attempts: $attempts\n")
                append("Matches: ${matches.size}\n")
                appendPercentPoint(point)
                append("DurationMs: $durationMs\n\n")
                append(result)
            }
        }
    }

    private fun handleUiWaitAndTapPercent(obj: JsonObject, timeout: Int): String {
        val point = requirePercentPointSpec(obj, "ui_wait_and_tap_percent") { return it }
        return runUiWaitUntilFoundLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待后按百分比点击失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, attempts ->
            val first = matches.first()
            val result = handleUiTapPercent(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilFoundLoop result
            }
            buildString {
                append("等待后按百分比点击成功\n")
                append("Attempts: $attempts\n")
                append("Matches: ${matches.size}\n")
                appendPercentPoint(point)
                append("Class: ${first.className.ifBlank { "(unknown)" }}\n")
                if (first.resourceId.isNotBlank()) append("ResourceId: ${first.resourceId}\n")
                if (first.text.isNotBlank()) append("Text: ${first.text}\n")
                if (first.contentDesc.isNotBlank()) append("ContentDesc: ${first.contentDesc}\n")
                append("Bounds: ${first.boundsRaw}\n")
                append("Center: (${first.centerX}, ${first.centerY})\n\n")
                append(result)
            }
        }
    }

    private fun handleUiWaitAndSwipePercent(obj: JsonObject, timeout: Int): String {
        val percent = requirePercentScrollSpec(obj, "ui_wait_and_swipe_percent") { return it }
        return runUiWaitUntilFoundLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待后按百分比滑动失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, attempts ->
            val first = matches.first()
            val result = handleUiSwipePercent(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilFoundLoop result
            }
            buildString {
                append("等待后按百分比滑动成功\n")
                append("Attempts: $attempts\n")
                append("Matches: ${matches.size}\n")
                appendPercentScrollRange(percent)
                append("Class: ${first.className.ifBlank { "(unknown)" }}\n")
                if (first.resourceId.isNotBlank()) append("ResourceId: ${first.resourceId}\n")
                if (first.text.isNotBlank()) append("Text: ${first.text}\n")
                if (first.contentDesc.isNotBlank()) append("ContentDesc: ${first.contentDesc}\n")
                append("Bounds: ${first.boundsRaw}\n")
                append("Center: (${first.centerX}, ${first.centerY})\n\n")
                append(result)
            }
        }
    }

    private fun handleUiWaitAndScrollPercent(obj: JsonObject, timeout: Int): String {
        val percent = requirePercentScrollSpec(obj, "ui_wait_and_scroll_percent") { return it }
        return runUiWaitUntilFoundLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待后按百分比滚动失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, attempts ->
            val first = matches.first()
            val result = handleUiScrollPercent(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilFoundLoop result
            }
            buildString {
                append("等待后按百分比滚动成功\n")
                append("Attempts: $attempts\n")
                append("Matches: ${matches.size}\n")
                appendPercentScrollRange(percent)
                append("Class: ${first.className.ifBlank { "(unknown)" }}\n")
                if (first.resourceId.isNotBlank()) append("ResourceId: ${first.resourceId}\n")
                if (first.text.isNotBlank()) append("Text: ${first.text}\n")
                if (first.contentDesc.isNotBlank()) append("ContentDesc: ${first.contentDesc}\n")
                append("Bounds: ${first.boundsRaw}\n")
                append("Center: (${first.centerX}, ${first.centerY})\n\n")
                append(result)
            }
        }
    }

    private fun handleUiWaitAndReplaceFirst(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_and_replace_first 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_wait_and_replace_first"
        return runUiWaitUntilFoundLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待替换失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, attempts ->
            val first = matches.first()
            val rewrittenArgs = buildJsonObjectString(
                obj,
                "text" to text,
                "index" to "0"
            )
            val result = handleUiSelectAllAndReplaceNode(
                obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
                timeout = timeout
            )
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilFoundLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_and_replace_first",
                    "attempts" to attempts,
                    "success" to true,
                    "inputText" to text.take(120),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiWaitAndLongPressFirst(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_and_long_press_first 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val durationMs = (obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS).coerceIn(100, 60_000)
        return runUiWaitUntilFoundLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待长按失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, attempts ->
            val first = matches.first()
            val rewrittenArgs = buildJsonObjectString(
                obj,
                "index" to "0",
                "longPressDurationMs" to durationMs.toString()
            )
            val result = handleUiLongPressNode(
                obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
                timeout
            )
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilFoundLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_and_long_press_first",
                    "attempts" to attempts,
                    "success" to true,
                    "durationMs" to durationMs,
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiWaitAndSetTextClearFirst(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_and_set_text_clear_first 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_wait_and_set_text_clear_first"
        return runUiWaitUntilFoundLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待输入失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, attempts ->
            val first = matches.first()
            val rewrittenArgs = buildJsonObjectString(
                obj,
                "text" to text,
                "index" to "0"
            )
            val result = handleUiSetTextClearNode(
                obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
                timeout = timeout
            )
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilFoundLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_and_set_text_clear_first",
                    "attempts" to attempts,
                    "success" to true,
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiWaitAndSetTextFirst(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_and_set_text_first 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_wait_and_set_text_first"
        return runUiWaitUntilFoundLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待直接输入失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, attempts ->
            val first = matches.first()
            val rewrittenArgs = buildJsonObjectString(
                obj,
                "text" to text,
                "index" to "0"
            )
            val result = handleUiSetTextNode(
                obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
                timeout = timeout
            )
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilFoundLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_and_set_text_first",
                    "attempts" to attempts,
                    "success" to true,
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiWaitGoneThenClickFirst(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_gone_then_click_first 至少需要一组等待消失选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val nextSelector = buildPrefixedSelectorObject(obj, "next")
        if (!hasUiSelector(nextSelector)) {
            return "Error: ui_wait_gone_then_click_first 还需要一组 next 选择器，如 nextText/nextResourceId/nextContentDesc/nextClassName/nextKeyword/nextClickable/nextEnabled"
        }
        return runUiWaitGoneThenNextFoundLoop(
            currentSelector = obj,
            nextSelector = nextSelector,
            timeout = timeout,
            failureMessage = "等待消失后点击失败：在 ${timeout.coerceAtLeast(1)} 秒内未完成“先消失后点击”。"
        ) { nextMatches, attempts, goneAfterAttempt ->
            val first = nextMatches.first()
            val tapOutput = runCommand("input tap ${first.centerX} ${first.centerY}", timeout)
            if (isFailureOutput(tapOutput)) return@runUiWaitGoneThenNextFoundLoop tapOutput
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_gone_then_click_first",
                    "attempts" to attempts,
                    "goneAfterAttempt" to goneAfterAttempt,
                    "success" to true,
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "outputLines" to tapOutput.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT)
                )
            )
        }
    }

    private fun handleUiWaitGoneThenBack(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_gone_then_back 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        return runUiWaitUntilGoneLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待消失后返回失败：在 ${timeout.coerceAtLeast(1)} 秒内目标节点仍存在。"
        ) { attempts ->
            val output = runCommand("input keyevent KEYCODE_BACK", timeout)
            if (isFailureOutput(output)) return@runUiWaitUntilGoneLoop output
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_gone_then_back",
                    "attempts" to attempts,
                    "success" to true,
                    "outputLines" to output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT)
                )
            )
        }
    }

    private fun handleUiWaitGoneThenSetTextFirst(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_gone_then_set_text_first 至少需要一组等待消失选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_wait_gone_then_set_text_first"
        val nextSelector = buildPrefixedSelectorObject(obj, "next")
        if (!hasUiSelector(nextSelector)) {
            return "Error: ui_wait_gone_then_set_text_first 还需要一组 next 选择器，如 nextText/nextResourceId/nextContentDesc/nextClassName/nextKeyword/nextClickable/nextEnabled"
        }
        return runUiWaitGoneThenNextFoundLoop(
            currentSelector = obj,
            nextSelector = nextSelector,
            timeout = timeout,
            failureMessage = "等待消失后直接输入失败：在 ${timeout.coerceAtLeast(1)} 秒内未完成“先消失后输入”。"
        ) { nextMatches, attempts, goneAfterAttempt ->
            val first = nextMatches.first()
            val rewrittenArgs = buildJsonObjectString(
                nextSelector,
                "text" to text,
                "index" to "0"
            )
            val result = handleUiSetTextNode(
                obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
                timeout = timeout
            )
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitGoneThenNextFoundLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_gone_then_set_text_first",
                    "attempts" to attempts,
                    "goneAfterAttempt" to goneAfterAttempt,
                    "success" to true,
                    "inputText" to text.take(120),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiWaitGoneThenSetTextClearFirst(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_gone_then_set_text_clear_first 至少需要一组等待消失选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_wait_gone_then_set_text_clear_first"
        val nextSelector = buildPrefixedSelectorObject(obj, "next")
        if (!hasUiSelector(nextSelector)) {
            return "Error: ui_wait_gone_then_set_text_clear_first 还需要一组 next 选择器，如 nextText/nextResourceId/nextContentDesc/nextClassName/nextKeyword/nextClickable/nextEnabled"
        }
        return runUiWaitGoneThenNextFoundLoop(
            currentSelector = obj,
            nextSelector = nextSelector,
            timeout = timeout,
            failureMessage = "等待消失后清空输入失败：在 ${timeout.coerceAtLeast(1)} 秒内未完成“先消失后清空输入”。"
        ) { nextMatches, attempts, goneAfterAttempt ->
            val first = nextMatches.first()
            val rewrittenArgs = buildJsonObjectString(
                nextSelector,
                "text" to text,
                "index" to "0"
            )
            val result = handleUiSetTextClearNode(
                obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
                timeout = timeout
            )
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitGoneThenNextFoundLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_gone_then_set_text_clear_first",
                    "attempts" to attempts,
                    "goneAfterAttempt" to goneAfterAttempt,
                    "success" to true,
                    "inputText" to text.take(120),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiWaitGoneThenLongPressFirst(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_gone_then_long_press_first 至少需要一组等待消失选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val nextSelector = buildPrefixedSelectorObject(obj, "next")
        if (!hasUiSelector(nextSelector)) {
            return "Error: ui_wait_gone_then_long_press_first 还需要一组 next 选择器，如 nextText/nextResourceId/nextContentDesc/nextClassName/nextKeyword/nextClickable/nextEnabled"
        }
        val durationMs = (obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS).coerceIn(100, 60_000)
        return runUiWaitGoneThenNextFoundLoop(
            currentSelector = obj,
            nextSelector = nextSelector,
            timeout = timeout,
            failureMessage = "等待消失后长按失败：在 ${timeout.coerceAtLeast(1)} 秒内未完成“先消失后长按”。"
        ) { nextMatches, attempts, goneAfterAttempt ->
            val first = nextMatches.first()
            val rewrittenArgs = buildJsonObjectString(
                nextSelector,
                "index" to "0",
                "longPressDurationMs" to durationMs.toString()
            )
            val result = handleUiLongPressNode(
                obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
                timeout = timeout
            )
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitGoneThenNextFoundLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_gone_then_long_press_first",
                    "attempts" to attempts,
                    "goneAfterAttempt" to goneAfterAttempt,
                    "success" to true,
                    "durationMs" to durationMs,
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiWaitGoneThenReplaceFirst(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_gone_then_replace_first 至少需要一组等待消失选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_wait_gone_then_replace_first"
        val nextSelector = buildPrefixedSelectorObject(obj, "next")
        if (!hasUiSelector(nextSelector)) {
            return "Error: ui_wait_gone_then_replace_first 还需要一组 next 选择器，如 nextText/nextResourceId/nextContentDesc/nextClassName/nextKeyword/nextClickable/nextEnabled"
        }
        return runUiWaitGoneThenNextFoundLoop(
            currentSelector = obj,
            nextSelector = nextSelector,
            timeout = timeout,
            failureMessage = "等待消失后全选替换失败：在 ${timeout.coerceAtLeast(1)} 秒内未完成“先消失后替换”。"
        ) { nextMatches, attempts, goneAfterAttempt ->
            val first = nextMatches.first()
            val rewrittenArgs = buildJsonObjectString(
                nextSelector,
                "text" to text,
                "index" to "0"
            )
            val result = handleUiSelectAllAndReplaceNode(
                obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
                timeout = timeout
            )
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitGoneThenNextFoundLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_gone_then_replace_first",
                    "attempts" to attempts,
                    "goneAfterAttempt" to goneAfterAttempt,
                    "success" to true,
                    "inputText" to text.take(120),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiWaitGoneThenKeyevent(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_gone_then_keyevent 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val keyCode = obj.string("keyCode") ?: return "Error: 'keyCode' is required for ui_wait_gone_then_keyevent"
        val normalized = normalizeKeyCode(keyCode)
        return runUiWaitUntilGoneLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待消失后发送按键失败：在 ${timeout.coerceAtLeast(1)} 秒内目标节点仍存在。"
        ) { attempts ->
            val output = runCommand("input keyevent $normalized", timeout)
            if (isFailureOutput(output)) return@runUiWaitUntilGoneLoop output
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_gone_then_keyevent",
                    "attempts" to attempts,
                    "success" to true,
                    "keyCode" to normalized,
                    "outputLines" to output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT)
                )
            )
        }
    }

    private fun handleUiWaitGoneThenSwipe(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_gone_then_swipe 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val x = obj.int("x") ?: return "Error: 'x' is required for ui_wait_gone_then_swipe"
        val y = obj.int("y") ?: return "Error: 'y' is required for ui_wait_gone_then_swipe"
        val x2 = obj.int("x2") ?: return "Error: 'x2' is required for ui_wait_gone_then_swipe"
        val y2 = obj.int("y2") ?: return "Error: 'y2' is required for ui_wait_gone_then_swipe"
        val durationMs = (obj.int("durationMs") ?: 300).coerceIn(1, 60_000)
        return runUiWaitUntilGoneLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待消失后执行滑动失败：在 ${timeout.coerceAtLeast(1)} 秒内目标节点仍存在。"
        ) { attempts ->
            val result = handleUiSwipe(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilGoneLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_gone_then_swipe",
                    "attempts" to attempts,
                    "success" to true,
                    "fromPoint" to listOf(x, y),
                    "toPoint" to listOf(x2, y2),
                    "durationMs" to durationMs,
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiWaitGoneThenTap(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_gone_then_tap 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val x = obj.int("x") ?: return "Error: 'x' is required for ui_wait_gone_then_tap"
        val y = obj.int("y") ?: return "Error: 'y' is required for ui_wait_gone_then_tap"
        return runUiWaitUntilGoneLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待消失后点击坐标失败：在 ${timeout.coerceAtLeast(1)} 秒内目标节点仍存在。"
        ) { attempts ->
            val result = handleUiTap(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilGoneLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_gone_then_tap",
                    "attempts" to attempts,
                    "success" to true,
                    "point" to listOf(x, y),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiWaitGoneThenLongPress(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_gone_then_long_press 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val x = obj.int("x") ?: return "Error: 'x' is required for ui_wait_gone_then_long_press"
        val y = obj.int("y") ?: return "Error: 'y' is required for ui_wait_gone_then_long_press"
        val durationMs = (obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS).coerceIn(100, 60_000)
        return runUiWaitUntilGoneLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待消失后长按坐标失败：在 ${timeout.coerceAtLeast(1)} 秒内目标节点仍存在。"
        ) { attempts ->
            val result = handleUiLongPress(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilGoneLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_gone_then_long_press",
                    "attempts" to attempts,
                    "success" to true,
                    "point" to listOf(x, y),
                    "durationMs" to durationMs,
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiWaitGoneThenTapPercent(obj: JsonObject, timeout: Int): String {
        val point = requirePercentPointSpec(obj, "ui_wait_gone_then_tap_percent") { return it }
        return runUiWaitUntilGoneLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待消失后按百分比点击失败：在 ${timeout.coerceAtLeast(1)} 秒内目标节点仍存在。"
        ) { attempts ->
            val result = handleUiTapPercent(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilGoneLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_gone_then_tap_percent",
                    "attempts" to attempts,
                    "success" to true,
                    "percentPoint" to listOf(point.rawXPercent, point.rawYPercent),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiWaitGoneThenLongPressPercent(obj: JsonObject, timeout: Int): String {
        val point = requirePercentPointSpec(obj, "ui_wait_gone_then_long_press_percent") { return it }
        val durationMs = (obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS).coerceIn(100, 60_000)
        return runUiWaitUntilGoneLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待消失后按百分比长按失败：在 ${timeout.coerceAtLeast(1)} 秒内目标节点仍存在。"
        ) { attempts ->
            val result = handleUiLongPressPercent(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilGoneLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_gone_then_long_press_percent",
                    "attempts" to attempts,
                    "success" to true,
                    "percentPoint" to listOf(point.rawXPercent, point.rawYPercent),
                    "durationMs" to durationMs,
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiWaitGoneThenSwipePercent(obj: JsonObject, timeout: Int): String {
        val percent = requirePercentScrollSpec(obj, "ui_wait_gone_then_swipe_percent") { return it }
        return runUiWaitUntilGoneLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待消失后按百分比滑动失败：在 ${timeout.coerceAtLeast(1)} 秒内目标节点仍存在。"
        ) { attempts ->
            val result = handleUiSwipePercent(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilGoneLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_gone_then_swipe_percent",
                    "attempts" to attempts,
                    "success" to true,
                    "percentScroll" to linkedMapOf<String, Any?>(
                        "from" to listOf(percent.rawXPercent, percent.rawYPercent),
                        "to" to listOf(percent.rawX2Percent, percent.rawY2Percent),
                        "maxScrolls" to percent.maxScrolls
                    ),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiWaitGoneThenScrollPercent(obj: JsonObject, timeout: Int): String {
        val percent = requirePercentScrollSpec(obj, "ui_wait_gone_then_scroll_percent") { return it }
        return runUiWaitUntilGoneLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待消失后按百分比滚动失败：在 ${timeout.coerceAtLeast(1)} 秒内目标节点仍存在。"
        ) { attempts ->
            val result = handleUiScrollPercent(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilGoneLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_gone_then_scroll_percent",
                    "attempts" to attempts,
                    "success" to true,
                    "percentScroll" to linkedMapOf<String, Any?>(
                        "from" to listOf(percent.rawXPercent, percent.rawYPercent),
                        "to" to listOf(percent.rawX2Percent, percent.rawY2Percent),
                        "maxScrolls" to percent.maxScrolls
                    ),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiWaitGoneThenText(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_gone_then_text 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_wait_gone_then_text"
        return runUiWaitUntilGoneLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待消失后输入文本失败：在 ${timeout.coerceAtLeast(1)} 秒内目标节点仍存在。"
        ) { attempts ->
            val result = handleUiText(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilGoneLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_gone_then_text",
                    "attempts" to attempts,
                    "success" to true,
                    "inputText" to text.take(80),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiWaitGoneThenScreenshot(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_gone_then_screenshot 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val outputPath = obj.string("outputPath") ?: "$TMP_DIR/murongagent-screenshot-${System.currentTimeMillis()}.png"
        return runUiWaitUntilGoneLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待消失后截图失败：在 ${timeout.coerceAtLeast(1)} 秒内目标节点仍存在。"
        ) { attempts ->
            val result = handleUiScreenshot(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilGoneLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_gone_then_screenshot",
                    "attempts" to attempts,
                    "success" to true,
                    "outputPath" to outputPath,
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiWaitGoneThenDump(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_gone_then_dump 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val outputPath = obj.string("outputPath") ?: "$TMP_DIR/murongagent-ui.xml"
        return runUiWaitUntilGoneLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待消失后导出界面树失败：在 ${timeout.coerceAtLeast(1)} 秒内目标节点仍存在。"
        ) { attempts ->
            val result = handleUiDump(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilGoneLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_gone_then_dump",
                    "attempts" to attempts,
                    "success" to true,
                    "outputPath" to outputPath,
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiWaitGoneThenDumpSummary(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_gone_then_dump_summary 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        return runUiWaitUntilGoneLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待消失后读取界面摘要失败：在 ${timeout.coerceAtLeast(1)} 秒内目标节点仍存在。"
        ) { attempts ->
            val result = handleUiDumpSummary(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilGoneLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_gone_then_dump_summary",
                    "attempts" to attempts,
                    "success" to true,
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiWaitGoneThenCurrentFocus(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_gone_then_current_focus 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        return runUiWaitUntilGoneLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待消失后读取当前焦点失败：在 ${timeout.coerceAtLeast(1)} 秒内目标节点仍存在。"
        ) { attempts ->
            val result = handleUiCurrentFocus(timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilGoneLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_gone_then_current_focus",
                    "attempts" to attempts,
                    "success" to true,
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiWaitGoneThenBackstackSummary(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_gone_then_backstack_summary 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        return runUiWaitUntilGoneLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待消失后读取 Activity 栈摘要失败：在 ${timeout.coerceAtLeast(1)} 秒内目标节点仍存在。"
        ) { attempts ->
            val result = handleUiBackstackSummary(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilGoneLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_gone_then_backstack_summary",
                    "attempts" to attempts,
                    "success" to true,
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiWaitGoneThenFind(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_wait_gone_then_find 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        return runUiWaitUntilGoneLoop(
            obj = obj,
            timeout = timeout,
            failureMessage = "等待消失后查找节点失败：在 ${timeout.coerceAtLeast(1)} 秒内目标节点仍存在。"
        ) { attempts ->
            val result = handleUiFind(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiWaitUntilGoneLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_wait_gone_then_find",
                    "attempts" to attempts,
                    "success" to true,
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiScrollFind(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_find 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollFindLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动查找失败：滚动 ${maxScrolls} 次后仍未找到匹配节点。"
        ) { matches, attempt ->
            val first = matches.first()
            buildString {
                append("滚动查找成功\n")
                append("Scrolls: $attempt\n")
                append("Matches: ${matches.size}\n")
                append("Class: ${first.className.ifBlank { "(unknown)" }}\n")
                if (first.resourceId.isNotBlank()) append("ResourceId: ${first.resourceId}\n")
                if (first.text.isNotBlank()) append("Text: ${first.text}\n")
                if (first.contentDesc.isNotBlank()) append("ContentDesc: ${first.contentDesc}\n")
                append("Bounds: ${first.boundsRaw}")
            }
        }
    }

    private fun handleUiScrollFindPercent(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_find_percent 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val rawXPercent = obj.double("xPercent") ?: return "Error: 'xPercent' is required for ui_scroll_find_percent"
        val rawYPercent = obj.double("yPercent") ?: return "Error: 'yPercent' is required for ui_scroll_find_percent"
        val rawX2Percent = obj.double("x2Percent") ?: return "Error: 'x2Percent' is required for ui_scroll_find_percent"
        val rawY2Percent = obj.double("y2Percent") ?: return "Error: 'y2Percent' is required for ui_scroll_find_percent"
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        for (attempt in 0..maxScrolls) {
            val matches = findUiNodes(obj, timeout = 5)
            if (!matches.isNullOrEmpty()) {
                val first = matches.first()
                return buildString {
                    append("按百分比滚动查找成功\n")
                    append("Scrolls: $attempt\n")
                    append("Matches: ${matches.size}\n")
                    append("FromPercent: ($rawXPercent, $rawYPercent)\n")
                    append("ToPercent: ($rawX2Percent, $rawY2Percent)\n")
                    append("Class: ${first.className.ifBlank { "(unknown)" }}\n")
                    if (first.resourceId.isNotBlank()) append("ResourceId: ${first.resourceId}\n")
                    if (first.text.isNotBlank()) append("Text: ${first.text}\n")
                    if (first.contentDesc.isNotBlank()) append("ContentDesc: ${first.contentDesc}\n")
                    append("Bounds: ${first.boundsRaw}")
                }
            }
            if (attempt >= maxScrolls) break
            val swipeResult = handleUiSwipePercent(obj, timeout)
            if (isFailureOutput(swipeResult) || swipeResult.startsWith("Error:", ignoreCase = true)) return swipeResult
        }
        return "按百分比滚动查找失败：滚动 ${maxScrolls} 次后仍未找到匹配节点。"
    }

    private fun handleUiScrollClickFirst(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_click_first 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollFindLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动点击失败：滚动 ${maxScrolls} 次后仍未找到匹配节点。"
        ) { matches, attempt ->
            val first = matches.first()
            val tapOutput = runCommand("input tap ${first.centerX} ${first.centerY}", timeout)
            if (isFailureOutput(tapOutput)) {
                return@runUiScrollFindLoop tapOutput
            }
            buildString {
                append("滚动查找并点击成功\n")
                append("Scrolls: $attempt\n")
                append("Matches: ${matches.size}\n")
                append("Class: ${first.className.ifBlank { "(unknown)" }}\n")
                if (first.resourceId.isNotBlank()) append("ResourceId: ${first.resourceId}\n")
                if (first.text.isNotBlank()) append("Text: ${first.text}\n")
                if (first.contentDesc.isNotBlank()) append("ContentDesc: ${first.contentDesc}\n")
                append("Bounds: ${first.boundsRaw}\n")
                append("Center: (${first.centerX}, ${first.centerY})")
            }
        }
    }

    private fun handleUiScrollClickFirstPercent(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_click_first_percent 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val rawXPercent = obj.double("xPercent") ?: return "Error: 'xPercent' is required for ui_scroll_click_first_percent"
        val rawYPercent = obj.double("yPercent") ?: return "Error: 'yPercent' is required for ui_scroll_click_first_percent"
        val rawX2Percent = obj.double("x2Percent") ?: return "Error: 'x2Percent' is required for ui_scroll_click_first_percent"
        val rawY2Percent = obj.double("y2Percent") ?: return "Error: 'y2Percent' is required for ui_scroll_click_first_percent"
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        for (attempt in 0..maxScrolls) {
            val matches = findUiNodes(obj, timeout = 5)
            if (!matches.isNullOrEmpty()) {
                val first = matches.first()
                val tapOutput = runCommand("input tap ${first.centerX} ${first.centerY}", timeout)
                if (isFailureOutput(tapOutput)) return tapOutput
                return buildString {
                    append("按百分比滚动查找并点击成功\n")
                    append("Scrolls: $attempt\n")
                    append("Matches: ${matches.size}\n")
                    append("FromPercent: ($rawXPercent, $rawYPercent)\n")
                    append("ToPercent: ($rawX2Percent, $rawY2Percent)\n")
                    append("Class: ${first.className.ifBlank { "(unknown)" }}\n")
                    if (first.resourceId.isNotBlank()) append("ResourceId: ${first.resourceId}\n")
                    if (first.text.isNotBlank()) append("Text: ${first.text}\n")
                    if (first.contentDesc.isNotBlank()) append("ContentDesc: ${first.contentDesc}\n")
                    append("Bounds: ${first.boundsRaw}\n")
                    append("Center: (${first.centerX}, ${first.centerY})")
                }
            }
            if (attempt >= maxScrolls) break
            val swipeResult = handleUiScrollPercent(obj, timeout)
            if (isFailureOutput(swipeResult) || swipeResult.startsWith("Error:", ignoreCase = true)) return swipeResult
        }
        return "按百分比滚动点击失败：滚动 ${maxScrolls} 次后仍未找到匹配节点。"
    }

    private fun handleUiScrollLongPressFirst(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_long_press_first 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        val longPressDurationMs = (obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS).coerceIn(100, 60_000)
        return runUiScrollFindLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动长按失败：滚动 ${maxScrolls} 次后仍未找到匹配节点。"
        ) { matches, attempt ->
            val first = matches.first()
            val output = runCommand(
                "input swipe ${first.centerX} ${first.centerY} ${first.centerX} ${first.centerY} $longPressDurationMs",
                timeout
            )
            if (isFailureOutput(output)) {
                return@runUiScrollFindLoop output
            }
            buildString {
                append("滚动查找并长按成功\n")
                append("Scrolls: $attempt\n")
                append("Matches: ${matches.size}\n")
                append("DurationMs: $longPressDurationMs\n")
                append("Class: ${first.className.ifBlank { "(unknown)" }}\n")
                if (first.resourceId.isNotBlank()) append("ResourceId: ${first.resourceId}\n")
                if (first.text.isNotBlank()) append("Text: ${first.text}\n")
                if (first.contentDesc.isNotBlank()) append("ContentDesc: ${first.contentDesc}\n")
                append("Bounds: ${first.boundsRaw}\n")
                append("Center: (${first.centerX}, ${first.centerY})")
            }
        }
    }

    private fun handleUiScrollLongPressFirstPercent(obj: JsonObject, timeout: Int): String {
        val percent = requirePercentScrollSpec(obj, "ui_scroll_long_press_first_percent") { return it }
        val longPressDurationMs = (obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS).coerceIn(100, 60_000)
        return runPercentScrollFindLoop(
            obj = obj,
            timeout = timeout,
            percent = percent,
            failureMessage = "按百分比滚动长按失败：滚动 ${percent.maxScrolls} 次后仍未找到匹配节点。"
        ) { matches, attempt, scrollPercent ->
            val first = matches.first()
            val output = runCommand(
                "input swipe ${first.centerX} ${first.centerY} ${first.centerX} ${first.centerY} $longPressDurationMs",
                timeout
            )
            if (isFailureOutput(output)) return@runPercentScrollFindLoop output
            buildString {
                append("按百分比滚动查找并长按成功\n")
                append("Scrolls: $attempt\n")
                append("Matches: ${matches.size}\n")
                appendPercentScrollRange(scrollPercent)
                append("DurationMs: $longPressDurationMs\n")
                append("Class: ${first.className.ifBlank { "(unknown)" }}\n")
                if (first.resourceId.isNotBlank()) append("ResourceId: ${first.resourceId}\n")
                if (first.text.isNotBlank()) append("Text: ${first.text}\n")
                if (first.contentDesc.isNotBlank()) append("ContentDesc: ${first.contentDesc}\n")
                append("Bounds: ${first.boundsRaw}\n")
                append("Center: (${first.centerX}, ${first.centerY})")
            }
        }
    }

    private fun handleUiScrollSelectAllAndReplaceFirst(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_select_all_and_replace_first 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_scroll_select_all_and_replace_first"
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollFindLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动全选替换失败：滚动 ${maxScrolls} 次后仍未找到匹配节点。"
        ) { matches, attempt ->
            val first = matches.first()
            val rewrittenArgs = buildJsonObjectString(
                obj,
                "text" to text,
                "index" to "0"
            )
            val result = handleUiSelectAllAndReplaceNode(
                obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
                timeout = timeout
            )
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiScrollFindLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_scroll_select_all_and_replace_first",
                    "scrolls" to attempt,
                    "success" to true,
                    "inputText" to text.take(120),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiScrollSelectAllAndReplaceFirstPercent(obj: JsonObject, timeout: Int): String {
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_scroll_select_all_and_replace_first_percent"
        val percent = requirePercentScrollSpec(obj, "ui_scroll_select_all_and_replace_first_percent") { return it }
        return runPercentScrollFindLoop(
            obj = obj,
            timeout = timeout,
            percent = percent,
            failureMessage = "按百分比滚动全选替换失败：滚动 ${percent.maxScrolls} 次后仍未找到匹配节点。"
        ) { matches, attempt, scrollPercent ->
            val first = matches.first()
            val rewrittenArgs = buildJsonObjectString(
                obj,
                "text" to text,
                "index" to "0"
            )
            val result = handleUiSelectAllAndReplaceNode(
                obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
                timeout = timeout
            )
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runPercentScrollFindLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_scroll_select_all_and_replace_first_percent",
                    "scrolls" to attempt,
                    "success" to true,
                    "inputText" to text.take(120),
                    "percentScroll" to linkedMapOf<String, Any?>(
                        "from" to listOf(scrollPercent.rawXPercent, scrollPercent.rawYPercent),
                        "to" to listOf(scrollPercent.rawX2Percent, scrollPercent.rawY2Percent),
                        "maxScrolls" to scrollPercent.maxScrolls
                    ),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiScrollWaitAndClickFirst(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_wait_and_click_first 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动等待点击失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollAction ->
            if (scrollAction) {
                runDirectionalUiScrollStep(obj, timeout)
            } else {
                val first = matches.first()
                val tapOutput = runCommand("input tap ${first.centerX} ${first.centerY}", timeout)
                if (isFailureOutput(tapOutput)) return@runUiScrollWaitLoop tapOutput
                renderStructured(
                    linkedMapOf<String, Any?>(
                        "action" to "ui_scroll_wait_and_click_first",
                        "cycles" to cycles,
                        "scrollsInCycle" to scrolls,
                        "success" to true,
                        "matchedNode" to renderUiNodeSummary(0, first),
                        "outputLines" to tapOutput.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT)
                    )
                )
            }
        }
    }

    private fun handleUiScrollWaitAndClickFirstPercent(obj: JsonObject, timeout: Int): String {
        val percent = requirePercentScrollSpec(obj, "ui_scroll_wait_and_click_first_percent") { return it }
        return runPercentScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            percent = percent,
            failureMessage = "按百分比滚动等待点击失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollPercent ->
            val first = matches.first()
            val tapOutput = runCommand("input tap ${first.centerX} ${first.centerY}", timeout)
            if (isFailureOutput(tapOutput)) return@runPercentScrollWaitLoop tapOutput
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_scroll_wait_and_click_first_percent",
                    "cycles" to cycles,
                    "scrollsInCycle" to scrolls,
                    "success" to true,
                    "percentScroll" to linkedMapOf<String, Any?>(
                        "from" to listOf(scrollPercent.rawXPercent, scrollPercent.rawYPercent),
                        "to" to listOf(scrollPercent.rawX2Percent, scrollPercent.rawY2Percent),
                        "maxScrolls" to scrollPercent.maxScrolls
                    ),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "outputLines" to tapOutput.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT)
                )
            )
        }
    }

    private fun handleUiScrollWaitAndFind(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_wait_and_find 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动等待查找失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollAction ->
            if (scrollAction) {
                runDirectionalUiScrollStep(obj, timeout)
            } else {
                val first = matches.first()
                val result = handleUiFind(obj, timeout)
                if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                    return@runUiScrollWaitLoop result
                }
                renderStructured(
                    linkedMapOf<String, Any?>(
                        "action" to "ui_scroll_wait_and_find",
                        "cycles" to cycles,
                        "scrollsInCycle" to scrolls,
                        "matchCount" to matches.size,
                        "success" to true,
                        "matchedNode" to renderUiNodeSummary(0, first),
                        "innerResult" to result
                    )
                )
            }
        }
    }

    private fun handleUiScrollWaitAndFindPercent(obj: JsonObject, timeout: Int): String {
        val percent = requirePercentScrollSpec(obj, "ui_scroll_wait_and_find_percent") { return it }
        return runPercentScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            percent = percent,
            failureMessage = "按百分比滚动等待查找失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollPercent ->
            val first = matches.first()
            val result = handleUiFind(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runPercentScrollWaitLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_scroll_wait_and_find_percent",
                    "cycles" to cycles,
                    "scrollsInCycle" to scrolls,
                    "matchCount" to matches.size,
                    "success" to true,
                    "percentScroll" to linkedMapOf<String, Any?>(
                        "from" to listOf(scrollPercent.rawXPercent, scrollPercent.rawYPercent),
                        "to" to listOf(scrollPercent.rawX2Percent, scrollPercent.rawY2Percent),
                        "maxScrolls" to scrollPercent.maxScrolls
                    ),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiScrollWaitAndDumpPercent(obj: JsonObject, timeout: Int): String {
        val outputPath = obj.string("outputPath") ?: "$TMP_DIR/murongagent-ui.xml"
        val percent = requirePercentScrollSpec(obj, "ui_scroll_wait_and_dump_percent") { return it }
        return runPercentScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            percent = percent,
            failureMessage = "按百分比滚动等待导出界面树失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollPercent ->
            val first = matches.first()
            val result = handleUiDump(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runPercentScrollWaitLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_scroll_wait_and_dump_percent",
                    "cycles" to cycles,
                    "scrollsInCycle" to scrolls,
                    "matchCount" to matches.size,
                    "success" to true,
                    "outputPath" to outputPath,
                    "percentScroll" to linkedMapOf<String, Any?>(
                        "from" to listOf(scrollPercent.rawXPercent, scrollPercent.rawYPercent),
                        "to" to listOf(scrollPercent.rawX2Percent, scrollPercent.rawY2Percent),
                        "maxScrolls" to scrollPercent.maxScrolls
                    ),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiScrollWaitAndDump(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_wait_and_dump 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val outputPath = obj.string("outputPath") ?: "$TMP_DIR/murongagent-ui.xml"
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动等待导出界面树失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollAction ->
            if (scrollAction) {
                runDirectionalUiScrollStep(obj, timeout)
            } else {
                val first = matches.first()
                val result = handleUiDump(obj, timeout)
                if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                    return@runUiScrollWaitLoop result
                }
                renderStructured(
                    linkedMapOf<String, Any?>(
                        "action" to "ui_scroll_wait_and_dump",
                        "cycles" to cycles,
                        "scrollsInCycle" to scrolls,
                        "matchCount" to matches.size,
                        "success" to true,
                        "outputPath" to outputPath,
                        "matchedNode" to renderUiNodeSummary(0, first),
                        "innerResult" to result
                    )
                )
            }
        }
    }

    private fun handleUiScrollWaitAndDumpSummaryPercent(obj: JsonObject, timeout: Int): String {
        val percent = requirePercentScrollSpec(obj, "ui_scroll_wait_and_dump_summary_percent") { return it }
        return runPercentScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            percent = percent,
            failureMessage = "按百分比滚动等待读取界面摘要失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollPercent ->
            val first = matches.first()
            val result = handleUiDumpSummary(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runPercentScrollWaitLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_scroll_wait_and_dump_summary_percent",
                    "cycles" to cycles,
                    "scrollsInCycle" to scrolls,
                    "matchCount" to matches.size,
                    "success" to true,
                    "percentScroll" to linkedMapOf<String, Any?>(
                        "from" to listOf(scrollPercent.rawXPercent, scrollPercent.rawYPercent),
                        "to" to listOf(scrollPercent.rawX2Percent, scrollPercent.rawY2Percent),
                        "maxScrolls" to scrollPercent.maxScrolls
                    ),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiScrollWaitAndDumpSummary(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_wait_and_dump_summary 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动等待读取界面摘要失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollAction ->
            if (scrollAction) {
                runDirectionalUiScrollStep(obj, timeout)
            } else {
                val first = matches.first()
                val result = handleUiDumpSummary(obj, timeout)
                if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                    return@runUiScrollWaitLoop result
                }
                renderStructured(
                    linkedMapOf<String, Any?>(
                        "action" to "ui_scroll_wait_and_dump_summary",
                        "cycles" to cycles,
                        "scrollsInCycle" to scrolls,
                        "matchCount" to matches.size,
                        "success" to true,
                        "matchedNode" to renderUiNodeSummary(0, first),
                        "innerResult" to result
                    )
                )
            }
        }
    }

    private fun handleUiScrollWaitAndCurrentFocusPercent(obj: JsonObject, timeout: Int): String {
        val percent = requirePercentScrollSpec(obj, "ui_scroll_wait_and_current_focus_percent") { return it }
        return runPercentScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            percent = percent,
            failureMessage = "按百分比滚动等待读取当前焦点失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollPercent ->
            val first = matches.first()
            val result = handleUiCurrentFocus(timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runPercentScrollWaitLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_scroll_wait_and_current_focus_percent",
                    "cycles" to cycles,
                    "scrollsInCycle" to scrolls,
                    "matchCount" to matches.size,
                    "success" to true,
                    "percentScroll" to linkedMapOf<String, Any?>(
                        "from" to listOf(scrollPercent.rawXPercent, scrollPercent.rawYPercent),
                        "to" to listOf(scrollPercent.rawX2Percent, scrollPercent.rawY2Percent),
                        "maxScrolls" to scrollPercent.maxScrolls
                    ),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiScrollWaitAndCurrentFocus(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_wait_and_current_focus 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动等待读取当前焦点失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollAction ->
            if (scrollAction) {
                runDirectionalUiScrollStep(obj, timeout)
            } else {
                val first = matches.first()
                val result = handleUiCurrentFocus(timeout)
                if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                    return@runUiScrollWaitLoop result
                }
                renderStructured(
                    linkedMapOf<String, Any?>(
                        "action" to "ui_scroll_wait_and_current_focus",
                        "cycles" to cycles,
                        "scrollsInCycle" to scrolls,
                        "matchCount" to matches.size,
                        "success" to true,
                        "matchedNode" to renderUiNodeSummary(0, first),
                        "innerResult" to result
                    )
                )
            }
        }
    }

    private fun handleUiScrollWaitAndBackstackSummaryPercent(obj: JsonObject, timeout: Int): String {
        val percent = requirePercentScrollSpec(obj, "ui_scroll_wait_and_backstack_summary_percent") { return it }
        return runPercentScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            percent = percent,
            failureMessage = "按百分比滚动等待读取 Activity 栈摘要失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollPercent ->
            val first = matches.first()
            val result = handleUiBackstackSummary(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runPercentScrollWaitLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_scroll_wait_and_backstack_summary_percent",
                    "cycles" to cycles,
                    "scrollsInCycle" to scrolls,
                    "matchCount" to matches.size,
                    "success" to true,
                    "percentScroll" to linkedMapOf<String, Any?>(
                        "from" to listOf(scrollPercent.rawXPercent, scrollPercent.rawYPercent),
                        "to" to listOf(scrollPercent.rawX2Percent, scrollPercent.rawY2Percent),
                        "maxScrolls" to scrollPercent.maxScrolls
                    ),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiScrollWaitAndBackstackSummary(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_wait_and_backstack_summary 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动等待读取 Activity 栈摘要失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollAction ->
            if (scrollAction) {
                runDirectionalUiScrollStep(obj, timeout)
            } else {
                val first = matches.first()
                val result = handleUiBackstackSummary(obj, timeout)
                if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                    return@runUiScrollWaitLoop result
                }
                renderStructured(
                    linkedMapOf<String, Any?>(
                        "action" to "ui_scroll_wait_and_backstack_summary",
                        "cycles" to cycles,
                        "scrollsInCycle" to scrolls,
                        "matchCount" to matches.size,
                        "success" to true,
                        "matchedNode" to renderUiNodeSummary(0, first),
                        "innerResult" to result
                    )
                )
            }
        }
    }

    private fun handleUiScrollWaitAndScreenshotPercent(obj: JsonObject, timeout: Int): String {
        val outputPath = obj.string("outputPath") ?: "$TMP_DIR/murongagent-screenshot-${System.currentTimeMillis()}.png"
        val percent = requirePercentScrollSpec(obj, "ui_scroll_wait_and_screenshot_percent") { return it }
        return runPercentScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            percent = percent,
            failureMessage = "按百分比滚动等待截图失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollPercent ->
            val first = matches.first()
            val result = handleUiScreenshot(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runPercentScrollWaitLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_scroll_wait_and_screenshot_percent",
                    "cycles" to cycles,
                    "scrollsInCycle" to scrolls,
                    "matchCount" to matches.size,
                    "success" to true,
                    "outputPath" to outputPath,
                    "percentScroll" to linkedMapOf<String, Any?>(
                        "from" to listOf(scrollPercent.rawXPercent, scrollPercent.rawYPercent),
                        "to" to listOf(scrollPercent.rawX2Percent, scrollPercent.rawY2Percent),
                        "maxScrolls" to scrollPercent.maxScrolls
                    ),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiScrollWaitAndScreenshot(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_wait_and_screenshot 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val outputPath = obj.string("outputPath") ?: "$TMP_DIR/murongagent-screenshot-${System.currentTimeMillis()}.png"
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动等待截图失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollAction ->
            if (scrollAction) {
                runDirectionalUiScrollStep(obj, timeout)
            } else {
                val first = matches.first()
                val result = handleUiScreenshot(obj, timeout)
                if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                    return@runUiScrollWaitLoop result
                }
                renderStructured(
                    linkedMapOf<String, Any?>(
                        "action" to "ui_scroll_wait_and_screenshot",
                        "cycles" to cycles,
                        "scrollsInCycle" to scrolls,
                        "matchCount" to matches.size,
                        "success" to true,
                        "outputPath" to outputPath,
                        "matchedNode" to renderUiNodeSummary(0, first),
                        "innerResult" to result
                    )
                )
            }
        }
    }

    private fun handleUiScrollWaitAndText(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_wait_and_text 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_scroll_wait_and_text"
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动等待输入文本失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollAction ->
            if (scrollAction) {
                runDirectionalUiScrollStep(obj, timeout)
            } else {
                val first = matches.first()
                val result = handleUiText(obj, timeout)
                if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                    return@runUiScrollWaitLoop result
                }
                renderStructured(
                    linkedMapOf<String, Any?>(
                        "action" to "ui_scroll_wait_and_text",
                        "cycles" to cycles,
                        "scrollsInCycle" to scrolls,
                        "matchCount" to matches.size,
                        "success" to true,
                        "inputText" to text.take(80),
                        "matchedNode" to renderUiNodeSummary(0, first),
                        "innerResult" to result
                    )
                )
            }
        }
    }

    private fun handleUiScrollWaitAndTextPercent(obj: JsonObject, timeout: Int): String {
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_scroll_wait_and_text_percent"
        val percent = requirePercentScrollSpec(obj, "ui_scroll_wait_and_text_percent") { return it }
        return runPercentScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            percent = percent,
            failureMessage = "按百分比滚动等待输入文本失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollPercent ->
            val first = matches.first()
            val result = handleUiText(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runPercentScrollWaitLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_scroll_wait_and_text_percent",
                    "cycles" to cycles,
                    "scrollsInCycle" to scrolls,
                    "matchCount" to matches.size,
                    "success" to true,
                    "inputText" to text.take(80),
                    "percentScroll" to linkedMapOf<String, Any?>(
                        "from" to listOf(scrollPercent.rawXPercent, scrollPercent.rawYPercent),
                        "to" to listOf(scrollPercent.rawX2Percent, scrollPercent.rawY2Percent),
                        "maxScrolls" to scrollPercent.maxScrolls
                    ),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiScrollWaitAndKeyevent(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_wait_and_keyevent 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val keyCode = obj.string("keyCode") ?: return "Error: 'keyCode' is required for ui_scroll_wait_and_keyevent"
        val normalized = normalizeKeyCode(keyCode)
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动等待发送按键失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollAction ->
            if (scrollAction) {
                runDirectionalUiScrollStep(obj, timeout)
            } else {
                val first = matches.first()
                val result = handleUiKeyevent(obj, timeout)
                if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                    return@runUiScrollWaitLoop result
                }
                renderStructured(
                    linkedMapOf<String, Any?>(
                        "action" to "ui_scroll_wait_and_keyevent",
                        "cycles" to cycles,
                        "scrollsInCycle" to scrolls,
                        "matchCount" to matches.size,
                        "success" to true,
                        "keyCode" to normalized,
                        "matchedNode" to renderUiNodeSummary(0, first),
                        "innerResult" to result
                    )
                )
            }
        }
    }

    private fun handleUiScrollWaitAndKeyeventPercent(obj: JsonObject, timeout: Int): String {
        val keyCode = obj.string("keyCode") ?: return "Error: 'keyCode' is required for ui_scroll_wait_and_keyevent_percent"
        val normalized = normalizeKeyCode(keyCode)
        val percent = requirePercentScrollSpec(obj, "ui_scroll_wait_and_keyevent_percent") { return it }
        return runPercentScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            percent = percent,
            failureMessage = "按百分比滚动等待发送按键失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollPercent ->
            val first = matches.first()
            val result = handleUiKeyevent(obj, timeout)
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runPercentScrollWaitLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_scroll_wait_and_keyevent_percent",
                    "cycles" to cycles,
                    "scrollsInCycle" to scrolls,
                    "matchCount" to matches.size,
                    "success" to true,
                    "keyCode" to normalized,
                    "percentScroll" to linkedMapOf<String, Any?>(
                        "from" to listOf(scrollPercent.rawXPercent, scrollPercent.rawYPercent),
                        "to" to listOf(scrollPercent.rawX2Percent, scrollPercent.rawY2Percent),
                        "maxScrolls" to scrollPercent.maxScrolls
                    ),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiScrollWaitAndTap(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_wait_and_tap 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val x = obj.int("x") ?: return "Error: 'x' is required for ui_scroll_wait_and_tap"
        val y = obj.int("y") ?: return "Error: 'y' is required for ui_scroll_wait_and_tap"
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动等待点击坐标失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollAction ->
            if (scrollAction) {
                runDirectionalUiScrollStep(obj, timeout)
            } else {
                val first = matches.first()
                val result = handleUiTap(obj, timeout)
                if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                    return@runUiScrollWaitLoop result
                }
                renderStructured(
                    linkedMapOf<String, Any?>(
                        "action" to "ui_scroll_wait_and_tap",
                        "cycles" to cycles,
                        "scrollsInCycle" to scrolls,
                        "matchCount" to matches.size,
                        "success" to true,
                        "point" to listOf(x, y),
                        "matchedNode" to renderUiNodeSummary(0, first),
                        "innerResult" to result
                    )
                )
            }
        }
    }

    private fun handleUiScrollWaitAndSwipe(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_wait_and_swipe 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val x = obj.int("x") ?: return "Error: 'x' is required for ui_scroll_wait_and_swipe"
        val y = obj.int("y") ?: return "Error: 'y' is required for ui_scroll_wait_and_swipe"
        val x2 = obj.int("x2") ?: return "Error: 'x2' is required for ui_scroll_wait_and_swipe"
        val y2 = obj.int("y2") ?: return "Error: 'y2' is required for ui_scroll_wait_and_swipe"
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动等待滑动坐标失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollAction ->
            if (scrollAction) {
                runDirectionalUiScrollStep(obj, timeout)
            } else {
                val first = matches.first()
                val result = handleUiSwipe(obj, timeout)
                if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                    return@runUiScrollWaitLoop result
                }
                renderStructured(
                    linkedMapOf<String, Any?>(
                        "action" to "ui_scroll_wait_and_swipe",
                        "cycles" to cycles,
                        "scrollsInCycle" to scrolls,
                        "matchCount" to matches.size,
                        "success" to true,
                        "fromPoint" to listOf(x, y),
                        "toPoint" to listOf(x2, y2),
                        "matchedNode" to renderUiNodeSummary(0, first),
                        "innerResult" to result
                    )
                )
            }
        }
    }

    private fun handleUiScrollWaitAndBack(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_wait_and_back 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动等待返回失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollAction ->
            if (scrollAction) {
                runDirectionalUiScrollStep(obj, timeout)
            } else {
                val output = runCommand("input keyevent KEYCODE_BACK", timeout)
                if (isFailureOutput(output)) return@runUiScrollWaitLoop output
                renderStructured(
                    linkedMapOf<String, Any?>(
                        "action" to "ui_scroll_wait_and_back",
                        "cycles" to cycles,
                        "scrollsInCycle" to scrolls,
                        "success" to true,
                        "outputLines" to output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT)
                    )
                )
            }
        }
    }

    private fun handleUiScrollWaitAndBackPercent(obj: JsonObject, timeout: Int): String {
        val percent = requirePercentScrollSpec(obj, "ui_scroll_wait_and_back_percent") { return it }
        return runPercentScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            percent = percent,
            failureMessage = "按百分比滚动等待返回失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollPercent ->
            val output = runCommand("input keyevent KEYCODE_BACK", timeout)
            if (isFailureOutput(output)) return@runPercentScrollWaitLoop output
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_scroll_wait_and_back_percent",
                    "cycles" to cycles,
                    "scrollsInCycle" to scrolls,
                    "success" to true,
                    "percentScroll" to linkedMapOf<String, Any?>(
                        "from" to listOf(scrollPercent.rawXPercent, scrollPercent.rawYPercent),
                        "to" to listOf(scrollPercent.rawX2Percent, scrollPercent.rawY2Percent),
                        "maxScrolls" to scrollPercent.maxScrolls
                    ),
                    "outputLines" to output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT)
                )
            )
        }
    }

    private fun handleUiScrollWaitAndLongPress(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_wait_and_long_press 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val x = obj.int("x") ?: return "Error: 'x' is required for ui_scroll_wait_and_long_press"
        val y = obj.int("y") ?: return "Error: 'y' is required for ui_scroll_wait_and_long_press"
        val durationMs = (obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS).coerceIn(100, 60_000)
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动等待长按坐标失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollAction ->
            if (scrollAction) {
                runDirectionalUiScrollStep(obj, timeout)
            } else {
                val first = matches.first()
                val result = handleUiLongPress(obj, timeout)
                if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                    return@runUiScrollWaitLoop result
                }
                renderStructured(
                    linkedMapOf<String, Any?>(
                        "action" to "ui_scroll_wait_and_long_press",
                        "cycles" to cycles,
                        "scrollsInCycle" to scrolls,
                        "matchCount" to matches.size,
                        "success" to true,
                        "point" to listOf(x, y),
                        "durationMs" to durationMs,
                        "matchedNode" to renderUiNodeSummary(0, first),
                        "innerResult" to result
                    )
                )
            }
        }
    }

    private fun handleUiScrollWaitAndLongPressPercent(obj: JsonObject, timeout: Int): String {
        val point = requirePercentPointSpec(obj, "ui_scroll_wait_and_long_press_percent") { return it }
        val durationMs = (obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS).coerceIn(100, 60_000)
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动等待按百分比长按失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollAction ->
            if (scrollAction) {
                runDirectionalUiScrollStep(obj, timeout)
            } else {
                val first = matches.first()
                val result = handleUiLongPressPercent(obj, timeout)
                if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                    return@runUiScrollWaitLoop result
                }
                renderStructured(
                    linkedMapOf<String, Any?>(
                        "action" to "ui_scroll_wait_and_long_press_percent",
                        "cycles" to cycles,
                        "scrollsInCycle" to scrolls,
                        "matchCount" to matches.size,
                        "success" to true,
                        "percentPoint" to listOf(point.rawXPercent, point.rawYPercent),
                        "durationMs" to durationMs,
                        "matchedNode" to renderUiNodeSummary(0, first),
                        "innerResult" to result
                    )
                )
            }
        }
    }

    private fun handleUiScrollWaitAndTapPercent(obj: JsonObject, timeout: Int): String {
        val point = requirePercentPointSpec(obj, "ui_scroll_wait_and_tap_percent") { return it }
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动等待后按百分比点击失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollAction ->
            if (scrollAction) {
                runDirectionalUiScrollStep(obj, timeout)
            } else {
                val first = matches.first()
                val result = handleUiTapPercent(obj, timeout)
                if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                    return@runUiScrollWaitLoop result
                }
                renderStructured(
                    linkedMapOf<String, Any?>(
                        "action" to "ui_scroll_wait_and_tap_percent",
                        "cycles" to cycles,
                        "scrollsInCycle" to scrolls,
                        "matchCount" to matches.size,
                        "success" to true,
                        "percentPoint" to listOf(point.rawXPercent, point.rawYPercent),
                        "matchedNode" to renderUiNodeSummary(0, first),
                        "innerResult" to result
                    )
                )
            }
        }
    }

    private fun handleUiScrollWaitAndSwipePercent(obj: JsonObject, timeout: Int): String {
        val percent = requirePercentScrollSpec(obj, "ui_scroll_wait_and_swipe_percent") { return it }
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动等待后按百分比滑动失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollAction ->
            if (scrollAction) {
                runDirectionalUiScrollStep(obj, timeout)
            } else {
                val first = matches.first()
                val result = handleUiSwipePercent(obj, timeout)
                if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                    return@runUiScrollWaitLoop result
                }
                renderStructured(
                    linkedMapOf<String, Any?>(
                        "action" to "ui_scroll_wait_and_swipe_percent",
                        "cycles" to cycles,
                        "scrollsInCycle" to scrolls,
                        "matchCount" to matches.size,
                        "success" to true,
                        "percentScroll" to linkedMapOf<String, Any?>(
                            "from" to listOf(percent.rawXPercent, percent.rawYPercent),
                            "to" to listOf(percent.rawX2Percent, percent.rawY2Percent),
                            "maxScrolls" to percent.maxScrolls
                        ),
                        "matchedNode" to renderUiNodeSummary(0, first),
                        "innerResult" to result
                    )
                )
            }
        }
    }

    private fun handleUiScrollWaitAndScrollPercent(obj: JsonObject, timeout: Int): String {
        val percent = requirePercentScrollSpec(obj, "ui_scroll_wait_and_scroll_percent") { return it }
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动等待后按百分比滚动失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollAction ->
            if (scrollAction) {
                runDirectionalUiScrollStep(obj, timeout)
            } else {
                val first = matches.first()
                val result = handleUiScrollPercent(obj, timeout)
                if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                    return@runUiScrollWaitLoop result
                }
                renderStructured(
                    linkedMapOf<String, Any?>(
                        "action" to "ui_scroll_wait_and_scroll_percent",
                        "cycles" to cycles,
                        "scrollsInCycle" to scrolls,
                        "matchCount" to matches.size,
                        "success" to true,
                        "percentScroll" to linkedMapOf<String, Any?>(
                            "from" to listOf(percent.rawXPercent, percent.rawYPercent),
                            "to" to listOf(percent.rawX2Percent, percent.rawY2Percent),
                            "maxScrolls" to percent.maxScrolls
                        ),
                        "matchedNode" to renderUiNodeSummary(0, first),
                        "innerResult" to result
                    )
                )
            }
        }
    }

    private fun handleUiScrollWaitAndReplaceFirst(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_wait_and_replace_first 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_scroll_wait_and_replace_first"
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动等待替换失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollAction ->
            if (scrollAction) {
                runDirectionalUiScrollStep(obj, timeout)
            } else {
                val first = matches.first()
                val rewrittenArgs = buildJsonObjectString(
                    obj,
                    "text" to text,
                    "index" to "0"
                )
                val result = handleUiSelectAllAndReplaceNode(
                    obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
                    timeout = timeout
                )
                if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                    return@runUiScrollWaitLoop result
                }
                renderStructured(
                    linkedMapOf<String, Any?>(
                        "action" to "ui_scroll_wait_and_replace_first",
                        "cycles" to cycles,
                        "scrollsInCycle" to scrolls,
                        "success" to true,
                        "inputText" to text.take(120),
                        "matchedNode" to renderUiNodeSummary(0, first),
                        "innerResult" to result
                    )
                )
            }
        }
    }

    private fun handleUiScrollWaitAndReplaceFirstPercent(obj: JsonObject, timeout: Int): String {
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_scroll_wait_and_replace_first_percent"
        val percent = requirePercentScrollSpec(obj, "ui_scroll_wait_and_replace_first_percent") { return it }
        return runPercentScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            percent = percent,
            failureMessage = "按百分比滚动等待替换失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollPercent ->
            val first = matches.first()
            val rewrittenArgs = buildJsonObjectString(
                obj,
                "text" to text,
                "index" to "0"
            )
            val result = handleUiSelectAllAndReplaceNode(
                obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
                timeout = timeout
            )
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runPercentScrollWaitLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_scroll_wait_and_replace_first_percent",
                    "cycles" to cycles,
                    "scrollsInCycle" to scrolls,
                    "success" to true,
                    "inputText" to text.take(120),
                    "percentScroll" to linkedMapOf<String, Any?>(
                        "from" to listOf(scrollPercent.rawXPercent, scrollPercent.rawYPercent),
                        "to" to listOf(scrollPercent.rawX2Percent, scrollPercent.rawY2Percent),
                        "maxScrolls" to scrollPercent.maxScrolls
                    ),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiScrollWaitAndLongPressFirst(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_wait_and_long_press_first 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        val longPressDurationMs = (obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS).coerceIn(100, 60_000)
        return runUiScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动等待长按失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollAction ->
            if (scrollAction) {
                runDirectionalUiScrollStep(obj, timeout)
            } else {
                val first = matches.first()
                val rewrittenArgs = buildJsonObjectString(
                    obj,
                    "index" to "0",
                    "longPressDurationMs" to longPressDurationMs.toString()
                )
                val result = handleUiLongPressNode(
                    obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
                    timeout
                )
                if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                    return@runUiScrollWaitLoop result
                }
                renderStructured(
                    linkedMapOf<String, Any?>(
                        "action" to "ui_scroll_wait_and_long_press_first",
                        "cycles" to cycles,
                        "scrollsInCycle" to scrolls,
                        "success" to true,
                        "durationMs" to longPressDurationMs,
                        "matchedNode" to renderUiNodeSummary(0, first),
                        "innerResult" to result
                    )
                )
            }
        }
    }

    private fun handleUiScrollWaitAndLongPressFirstPercent(obj: JsonObject, timeout: Int): String {
        val longPressDurationMs = (obj.int("longPressDurationMs") ?: DEFAULT_UI_LONG_PRESS_DURATION_MS).coerceIn(100, 60_000)
        val percent = requirePercentScrollSpec(obj, "ui_scroll_wait_and_long_press_first_percent") { return it }
        return runPercentScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            percent = percent,
            failureMessage = "按百分比滚动等待长按失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollPercent ->
            val first = matches.first()
            val rewrittenArgs = buildJsonObjectString(
                obj,
                "index" to "0",
                "longPressDurationMs" to longPressDurationMs.toString()
            )
            val result = handleUiLongPressNode(
                obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
                timeout
            )
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runPercentScrollWaitLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_scroll_wait_and_long_press_first_percent",
                    "cycles" to cycles,
                    "scrollsInCycle" to scrolls,
                    "success" to true,
                    "durationMs" to longPressDurationMs,
                    "percentScroll" to linkedMapOf<String, Any?>(
                        "from" to listOf(scrollPercent.rawXPercent, scrollPercent.rawYPercent),
                        "to" to listOf(scrollPercent.rawX2Percent, scrollPercent.rawY2Percent),
                        "maxScrolls" to scrollPercent.maxScrolls
                    ),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiScrollWaitAndSetTextClearFirst(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_wait_and_set_text_clear_first 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_scroll_wait_and_set_text_clear_first"
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动等待输入失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollAction ->
            if (scrollAction) {
                runDirectionalUiScrollStep(obj, timeout)
            } else {
                val first = matches.first()
                val rewrittenArgs = buildJsonObjectString(
                    obj,
                    "text" to text,
                    "index" to "0"
                )
                val result = handleUiSetTextClearNode(
                    obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
                    timeout = timeout
                )
                if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                    return@runUiScrollWaitLoop result
                }
                renderStructured(
                    linkedMapOf<String, Any?>(
                        "action" to "ui_scroll_wait_and_set_text_clear_first",
                        "cycles" to cycles,
                        "scrollsInCycle" to scrolls,
                        "success" to true,
                        "inputText" to text.take(120),
                        "matchedNode" to renderUiNodeSummary(0, first),
                        "innerResult" to result
                    )
                )
            }
        }
    }

    private fun handleUiScrollWaitAndSetTextClearFirstPercent(obj: JsonObject, timeout: Int): String {
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_scroll_wait_and_set_text_clear_first_percent"
        val percent = requirePercentScrollSpec(obj, "ui_scroll_wait_and_set_text_clear_first_percent") { return it }
        return runPercentScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            percent = percent,
            failureMessage = "按百分比滚动等待输入失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollPercent ->
            val first = matches.first()
            val rewrittenArgs = buildJsonObjectString(
                obj,
                "text" to text,
                "index" to "0"
            )
            val result = handleUiSetTextClearNode(
                obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
                timeout = timeout
            )
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runPercentScrollWaitLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_scroll_wait_and_set_text_clear_first_percent",
                    "cycles" to cycles,
                    "scrollsInCycle" to scrolls,
                    "success" to true,
                    "inputText" to text.take(120),
                    "percentScroll" to linkedMapOf<String, Any?>(
                        "from" to listOf(scrollPercent.rawXPercent, scrollPercent.rawYPercent),
                        "to" to listOf(scrollPercent.rawX2Percent, scrollPercent.rawY2Percent),
                        "maxScrolls" to scrollPercent.maxScrolls
                    ),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiScrollWaitAndSetTextFirstPercent(obj: JsonObject, timeout: Int): String {
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_scroll_wait_and_set_text_first_percent"
        val percent = requirePercentScrollSpec(obj, "ui_scroll_wait_and_set_text_first_percent") { return it }
        return runPercentScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            percent = percent,
            failureMessage = "按百分比滚动等待输入失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollPercent ->
            val first = matches.first()
            val rewrittenArgs = buildJsonObjectString(
                obj,
                "text" to text,
                "index" to "0"
            )
            val result = handleUiSetTextNode(
                obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
                timeout = timeout
            )
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runPercentScrollWaitLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_scroll_wait_and_set_text_first_percent",
                    "cycles" to cycles,
                    "scrollsInCycle" to scrolls,
                    "success" to true,
                    "inputText" to text.take(120),
                    "percentScroll" to linkedMapOf<String, Any?>(
                        "from" to listOf(scrollPercent.rawXPercent, scrollPercent.rawYPercent),
                        "to" to listOf(scrollPercent.rawX2Percent, scrollPercent.rawY2Percent),
                        "maxScrolls" to scrollPercent.maxScrolls
                    ),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiScrollWaitAndSetTextFirst(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_wait_and_set_text_first 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_scroll_wait_and_set_text_first"
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollWaitLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动等待直接输入失败：在 ${timeout.coerceAtLeast(1)} 秒内未找到匹配节点。"
        ) { matches, scrolls, cycles, scrollAction ->
            if (scrollAction) {
                runDirectionalUiScrollStep(obj, timeout)
            } else {
                val first = matches.first()
                val rewrittenArgs = buildJsonObjectString(
                    obj,
                    "text" to text,
                    "index" to "0"
                )
                val result = handleUiSetTextNode(
                    obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
                    timeout = timeout
                )
                if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                    return@runUiScrollWaitLoop result
                }
                renderStructured(
                    linkedMapOf<String, Any?>(
                        "action" to "ui_scroll_wait_and_set_text_first",
                        "cycles" to cycles,
                        "scrollsInCycle" to scrolls,
                        "success" to true,
                        "inputText" to text.take(120),
                        "matchedNode" to renderUiNodeSummary(0, first),
                        "innerResult" to result
                    )
                )
            }
        }
    }

    private fun handleUiScrollSetTextClearFirst(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_set_text_clear_first 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_scroll_set_text_clear_first"
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollFindLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动输入失败：滚动 ${maxScrolls} 次后仍未找到匹配节点。"
        ) { matches, attempt ->
            val first = matches.first()
            val encodedText = encodeInputText(text)
            if (encodedText.isBlank()) {
                return@runUiScrollFindLoop "Error: 输入文本为空或无法编码"
            }
            val clearSteps = (obj.int("clearSteps") ?: first.text.length).coerceAtLeast(0)
            val clearCommands = if (clearSteps > 0) {
                List(clearSteps) { "input keyevent KEYCODE_DEL" }.joinToString("; ")
            } else {
                ""
            }
            val command = buildString {
                append("input tap ${first.centerX} ${first.centerY}; sleep 0.2; input keyevent KEYCODE_MOVE_END")
                if (clearCommands.isNotBlank()) {
                    append("; sleep 0.1; ")
                    append(clearCommands)
                }
                append("; sleep 0.2; input text ${shQuote(encodedText)}")
            }
            val output = runCommand(command, timeout)
            if (isFailureOutput(output)) {
                return@runUiScrollFindLoop output
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_scroll_set_text_clear_first",
                    "scrolls" to attempt,
                    "success" to true,
                    "clearSteps" to clearSteps,
                    "inputText" to text.take(120),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "outputLines" to output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT)
                )
            )
        }
    }

    private fun handleUiScrollSetTextClearFirstPercent(obj: JsonObject, timeout: Int): String {
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_scroll_set_text_clear_first_percent"
        val percent = requirePercentScrollSpec(obj, "ui_scroll_set_text_clear_first_percent") { return it }
        return runPercentScrollFindLoop(
            obj = obj,
            timeout = timeout,
            percent = percent,
            failureMessage = "按百分比滚动输入失败：滚动 ${percent.maxScrolls} 次后仍未找到匹配节点。"
        ) { matches, attempt, scrollPercent ->
            val first = matches.first()
            val encodedText = encodeInputText(text)
            if (encodedText.isBlank()) {
                return@runPercentScrollFindLoop "Error: 输入文本为空或无法编码"
            }
            val clearSteps = (obj.int("clearSteps") ?: first.text.length).coerceAtLeast(0)
            val clearCommands = if (clearSteps > 0) {
                List(clearSteps) { "input keyevent KEYCODE_DEL" }.joinToString("; ")
            } else {
                ""
            }
            val command = buildString {
                append("input tap ${first.centerX} ${first.centerY}; sleep 0.2; input keyevent KEYCODE_MOVE_END")
                if (clearCommands.isNotBlank()) {
                    append("; sleep 0.1; ")
                    append(clearCommands)
                }
                append("; sleep 0.2; input text ${shQuote(encodedText)}")
            }
            val output = runCommand(command, timeout)
            if (isFailureOutput(output)) return@runPercentScrollFindLoop output
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_scroll_set_text_clear_first_percent",
                    "scrolls" to attempt,
                    "success" to true,
                    "clearSteps" to clearSteps,
                    "inputText" to text.take(120),
                    "percentScroll" to linkedMapOf<String, Any?>(
                        "from" to listOf(scrollPercent.rawXPercent, scrollPercent.rawYPercent),
                        "to" to listOf(scrollPercent.rawX2Percent, scrollPercent.rawY2Percent),
                        "maxScrolls" to scrollPercent.maxScrolls
                    ),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "outputLines" to output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(DEFAULT_OUTPUT_LIMIT)
                )
            )
        }
    }

    private fun handleUiScrollSetTextFirst(obj: JsonObject, timeout: Int): String {
        if (!hasUiSelector(obj)) {
            return "Error: ui_scroll_set_text_first 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled"
        }
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_scroll_set_text_first"
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return runUiScrollFindLoop(
            obj = obj,
            timeout = timeout,
            maxScrolls = maxScrolls,
            failureMessage = "滚动直接输入失败：滚动 ${maxScrolls} 次后仍未找到匹配节点。"
        ) { matches, attempt ->
            val first = matches.first()
            val rewrittenArgs = buildJsonObjectString(
                obj,
                "text" to text,
                "index" to "0"
            )
            val result = handleUiSetTextNode(
                obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
                timeout = timeout
            )
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runUiScrollFindLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_scroll_set_text_first",
                    "scrolls" to attempt,
                    "success" to true,
                    "inputText" to text.take(120),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiScrollSetTextFirstPercent(obj: JsonObject, timeout: Int): String {
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_scroll_set_text_first_percent"
        val percent = requirePercentScrollSpec(obj, "ui_scroll_set_text_first_percent") { return it }
        return runPercentScrollFindLoop(
            obj = obj,
            timeout = timeout,
            percent = percent,
            failureMessage = "按百分比滚动直接输入失败：滚动 ${percent.maxScrolls} 次后仍未找到匹配节点。"
        ) { matches, attempt, scrollPercent ->
            val first = matches.first()
            val rewrittenArgs = buildJsonObjectString(
                obj,
                "text" to text,
                "index" to "0"
            )
            val result = handleUiSetTextNode(
                obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
                timeout = timeout
            )
            if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) {
                return@runPercentScrollFindLoop result
            }
            renderStructured(
                linkedMapOf<String, Any?>(
                    "action" to "ui_scroll_set_text_first_percent",
                    "scrolls" to attempt,
                    "success" to true,
                    "inputText" to text.take(120),
                    "percentScroll" to linkedMapOf<String, Any?>(
                        "from" to listOf(scrollPercent.rawXPercent, scrollPercent.rawYPercent),
                        "to" to listOf(scrollPercent.rawX2Percent, scrollPercent.rawY2Percent),
                        "maxScrolls" to scrollPercent.maxScrolls
                    ),
                    "matchedNode" to renderUiNodeSummary(0, first),
                    "innerResult" to result
                )
            )
        }
    }

    private fun handleUiBackstackSummary(obj: JsonObject, timeout: Int): String {
        val limit = (obj.int("lines") ?: DEFAULT_UI_BACKSTACK_LIMIT).coerceIn(1, 120)
        val output = runCommand(
            "dumpsys activity activities | grep -E 'ResumedActivity|topResumedActivity|mFocusedApp|Hist #' || true",
            timeout
        )
        if (isFailureOutput(output)) return output
        val lines = output.lines().filter { it.isNotBlank() }.take(limit)
        return if (lines.isEmpty()) {
            "未获取到 Activity 栈摘要。"
        } else {
            buildString {
                append("Activity 栈摘要\n\n")
                append(lines.joinToString("\n"))
            }
        }
    }

    private fun handleUiTap(obj: JsonObject, timeout: Int): String {
        val x = obj.int("x") ?: return "Error: 'x' is required for ui_tap"
        val y = obj.int("y") ?: return "Error: 'y' is required for ui_tap"
        return runAndFormat("点击命令已执行", "input tap $x $y", timeout)
    }

    private fun handleUiTapPercent(obj: JsonObject, timeout: Int): String {
        val rawXPercent = obj.double("xPercent") ?: return "Error: 'xPercent' is required for ui_tap_percent"
        val rawYPercent = obj.double("yPercent") ?: return "Error: 'yPercent' is required for ui_tap_percent"
        val xPercent = normalizePercentValue(rawXPercent)
            ?: return "Error: 'xPercent' must be in 0.0-1.0 or 0-100 range for ui_tap_percent"
        val yPercent = normalizePercentValue(rawYPercent)
            ?: return "Error: 'yPercent' must be in 0.0-1.0 or 0-100 range for ui_tap_percent"
        val displaySize = getDisplaySize(timeout)
        val width = displaySize?.first ?: DEFAULT_UI_SCROLL_WIDTH
        val height = displaySize?.second ?: DEFAULT_UI_SCROLL_HEIGHT
        val maxX = (width - 1).coerceAtLeast(0)
        val maxY = (height - 1).coerceAtLeast(0)
        val x = (maxX * xPercent).toInt().coerceIn(0, maxX)
        val y = (maxY * yPercent).toInt().coerceIn(0, maxY)
        val rewrittenArgs = buildJsonObjectString(
            obj,
            "x" to x.toString(),
            "y" to y.toString()
        )
        val result = handleUiTap(
            obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
            timeout = timeout
        )
        if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) return result
        return buildString {
            append("百分比点击已执行\n")
            append("Display: ${width}x${height}\n")
            append("Percent: ($rawXPercent, $rawYPercent)\n")
            append("Point: ($x, $y)\n\n")
            append(result)
        }
    }

    private fun handleUiSwipe(obj: JsonObject, timeout: Int): String {
        val x = obj.int("x") ?: return "Error: 'x' is required for ui_swipe"
        val y = obj.int("y") ?: return "Error: 'y' is required for ui_swipe"
        val x2 = obj.int("x2") ?: return "Error: 'x2' is required for ui_swipe"
        val y2 = obj.int("y2") ?: return "Error: 'y2' is required for ui_swipe"
        val durationMs = (obj.int("durationMs") ?: 300).coerceIn(1, 60_000)
        return runAndFormat("滑动命令已执行", "input swipe $x $y $x2 $y2 $durationMs", timeout)
    }

    private fun handleUiSwipePercent(obj: JsonObject, timeout: Int): String {
        val rawXPercent = obj.double("xPercent") ?: return "Error: 'xPercent' is required for ui_swipe_percent"
        val rawYPercent = obj.double("yPercent") ?: return "Error: 'yPercent' is required for ui_swipe_percent"
        val rawX2Percent = obj.double("x2Percent") ?: return "Error: 'x2Percent' is required for ui_swipe_percent"
        val rawY2Percent = obj.double("y2Percent") ?: return "Error: 'y2Percent' is required for ui_swipe_percent"
        val xPercent = normalizePercentValue(rawXPercent)
            ?: return "Error: 'xPercent' must be in 0.0-1.0 or 0-100 range for ui_swipe_percent"
        val yPercent = normalizePercentValue(rawYPercent)
            ?: return "Error: 'yPercent' must be in 0.0-1.0 or 0-100 range for ui_swipe_percent"
        val x2Percent = normalizePercentValue(rawX2Percent)
            ?: return "Error: 'x2Percent' must be in 0.0-1.0 or 0-100 range for ui_swipe_percent"
        val y2Percent = normalizePercentValue(rawY2Percent)
            ?: return "Error: 'y2Percent' must be in 0.0-1.0 or 0-100 range for ui_swipe_percent"
        val displaySize = getDisplaySize(timeout)
        val width = displaySize?.first ?: DEFAULT_UI_SCROLL_WIDTH
        val height = displaySize?.second ?: DEFAULT_UI_SCROLL_HEIGHT
        val maxX = (width - 1).coerceAtLeast(0)
        val maxY = (height - 1).coerceAtLeast(0)
        val x = (maxX * xPercent).toInt().coerceIn(0, maxX)
        val y = (maxY * yPercent).toInt().coerceIn(0, maxY)
        val x2 = (maxX * x2Percent).toInt().coerceIn(0, maxX)
        val y2 = (maxY * y2Percent).toInt().coerceIn(0, maxY)
        val durationMs = (obj.int("durationMs") ?: 300).coerceIn(1, 60_000)
        val rewrittenArgs = buildJsonObjectString(
            obj,
            "x" to x.toString(),
            "y" to y.toString(),
            "x2" to x2.toString(),
            "y2" to y2.toString(),
            "durationMs" to durationMs.toString()
        )
        val result = handleUiSwipe(
            obj = json.parseToJsonElement(rewrittenArgs).jsonObject,
            timeout = timeout
        )
        if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) return result
        return buildString {
            append("百分比滑动已执行\n")
            append("Display: ${width}x${height}\n")
            append("FromPercent: ($rawXPercent, $rawYPercent)\n")
            append("ToPercent: ($rawX2Percent, $rawY2Percent)\n")
            append("FromPoint: ($x, $y)\n")
            append("ToPoint: ($x2, $y2)\n")
            append("DurationMs: $durationMs\n\n")
            append(result)
        }
    }

    private fun handleUiScrollPercent(obj: JsonObject, timeout: Int): String {
        val rawXPercent = obj.double("xPercent") ?: return "Error: 'xPercent' is required for ui_scroll_percent"
        val rawYPercent = obj.double("yPercent") ?: return "Error: 'yPercent' is required for ui_scroll_percent"
        val rawX2Percent = obj.double("x2Percent") ?: return "Error: 'x2Percent' is required for ui_scroll_percent"
        val rawY2Percent = obj.double("y2Percent") ?: return "Error: 'y2Percent' is required for ui_scroll_percent"
        val result = handleUiSwipePercent(obj, timeout)
        if (isFailureOutput(result) || result.startsWith("Error:", ignoreCase = true)) return result
        return buildString {
            append("百分比滚动已执行\n")
            append("FromPercent: ($rawXPercent, $rawYPercent)\n")
            append("ToPercent: ($rawX2Percent, $rawY2Percent)\n\n")
            append(result)
        }
    }

    private fun handleUiText(obj: JsonObject, timeout: Int): String {
        val text = obj.string("text") ?: return "Error: 'text' is required for ui_text"
        val encodedText = encodeInputText(text)
        if (encodedText.isBlank()) {
            return "Error: 输入文本为空或无法编码"
        }
        return runAndFormat("文本输入命令已执行", "input text ${shQuote(encodedText)}", timeout)
    }

    private fun handleUiKeyevent(obj: JsonObject, timeout: Int): String {
        val keyCode = obj.string("keyCode") ?: return "Error: 'keyCode' is required for ui_keyevent"
        val normalized = normalizeKeyCode(keyCode)
        return runAndFormat("按键事件已发送", "input keyevent $normalized", timeout)
    }

    private fun readUiHierarchyXml(obj: JsonObject, timeout: Int): String? {
        val outputPath = obj.string("outputPath") ?: "$TMP_DIR/murongagent-ui.xml"
        val output = runCommand(
            "uiautomator dump ${shQuote(outputPath)} >/dev/null 2>&1 && cat ${shQuote(outputPath)}",
            timeout
        )
        if (isFailureOutput(output)) return null
        return output
    }

    private fun findUiNodes(obj: JsonObject, timeout: Int): List<UiNode>? {
        val xml = readUiHierarchyXml(obj, timeout) ?: return null
        val allNodes = parseUiNodes(xml)
        val text = obj.string("text")
        val resourceId = obj.string("resourceId")
        val contentDesc = obj.string("contentDesc")
        val className = obj.string("className")
        val keyword = obj.string("keyword")
        val clickable = obj.bool("clickable")
        val enabled = obj.bool("enabled")
        return allNodes.filter { node ->
            val matchesText = text == null || node.text.contains(text, ignoreCase = true)
            val matchesId = resourceId == null || node.resourceId.contains(resourceId, ignoreCase = true)
            val matchesDesc = contentDesc == null || node.contentDesc.contains(contentDesc, ignoreCase = true)
            val matchesClass = className == null || node.className.contains(className, ignoreCase = true)
            val matchesKeyword = keyword == null || listOf(node.text, node.resourceId, node.contentDesc, node.className)
                .any { field -> field.contains(keyword, ignoreCase = true) }
            val matchesClickable = clickable == null || node.clickable == clickable
            val matchesEnabled = enabled == null || node.enabled == enabled
            matchesText && matchesId && matchesDesc && matchesClass && matchesKeyword && matchesClickable && matchesEnabled
        }
    }

    private fun buildPrefixedSelectorObject(source: JsonObject, prefix: String): JsonObject {
        val entries = linkedMapOf<String, String>()
        listOf(
            "${prefix}Text" to "text",
            "${prefix}ResourceId" to "resourceId",
            "${prefix}ContentDesc" to "contentDesc",
            "${prefix}ClassName" to "className",
            "${prefix}Keyword" to "keyword",
            "${prefix}Clickable" to "clickable",
            "${prefix}Enabled" to "enabled"
        ).forEach { (sourceKey, targetKey) ->
            source[sourceKey]?.let { entries[targetKey] = it.toString() }
        }
        if (entries.isEmpty()) {
            return json.parseToJsonElement("{}").jsonObject
        }
        val raw = entries.entries.joinToString(",") { (key, value) -> "\"$key\":$value" }
        return json.parseToJsonElement("{$raw}").jsonObject
    }

    private fun findSelectAllUiNode(timeout: Int): UiNode? {
        val emptyObj = json.parseToJsonElement("{}").jsonObject
        val xml = readUiHierarchyXml(emptyObj, timeout) ?: return null
        return parseUiNodes(xml)
            .filter { it.enabled }
            .firstOrNull { node ->
                listOf(node.text, node.contentDesc).any { value ->
                    value.equals("全选", ignoreCase = true) ||
                        value.equals("Select all", ignoreCase = true) ||
                        value.contains("全选") ||
                        value.contains("Select all", ignoreCase = true)
                }
            }
    }

    private fun hasUiSelector(obj: JsonObject): Boolean {
        return obj.string("text") != null ||
            obj.string("resourceId") != null ||
            obj.string("contentDesc") != null ||
            obj.string("className") != null ||
            obj.string("keyword") != null ||
            obj.bool("clickable") != null ||
            obj.bool("enabled") != null
    }

    private fun buildJsonObjectString(
        source: JsonObject,
        vararg overrides: Pair<String, String>
    ): String {
        val normalized = overrides.toMap()
        return buildString {
            append("{")
            val entries = linkedMapOf<String, String>()
            source.forEach { (key, value) ->
                entries[key] = value.toString()
            }
            normalized.forEach { (key, value) ->
                entries[key] = "\"$value\""
            }
            append(entries.entries.joinToString(",") { (key, value) -> "\"$key\":$value" })
            append("}")
        }
    }

    private inline fun requirePercentScrollSpec(
        obj: JsonObject,
        action: String,
        onError: (String) -> Nothing
    ): PercentScrollSpec {
        if (!hasUiSelector(obj)) {
            onError("Error: $action 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled")
        }
        val rawXPercent = obj.double("xPercent") ?: onError("Error: 'xPercent' is required for $action")
        val rawYPercent = obj.double("yPercent") ?: onError("Error: 'yPercent' is required for $action")
        val rawX2Percent = obj.double("x2Percent") ?: onError("Error: 'x2Percent' is required for $action")
        val rawY2Percent = obj.double("y2Percent") ?: onError("Error: 'y2Percent' is required for $action")
        val maxScrolls = (obj.int("maxScrolls") ?: DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS).coerceIn(0, 30)
        return PercentScrollSpec(
            rawXPercent = rawXPercent,
            rawYPercent = rawYPercent,
            rawX2Percent = rawX2Percent,
            rawY2Percent = rawY2Percent,
            maxScrolls = maxScrolls
        )
    }

    private inline fun requirePercentPointSpec(
        obj: JsonObject,
        action: String,
        onError: (String) -> Nothing
    ): PercentPointSpec {
        if (!hasUiSelector(obj)) {
            onError("Error: $action 至少需要一个选择器，如 text/resourceId/contentDesc/className/keyword/clickable/enabled")
        }
        val rawXPercent = obj.double("xPercent") ?: onError("Error: 'xPercent' is required for $action")
        val rawYPercent = obj.double("yPercent") ?: onError("Error: 'yPercent' is required for $action")
        return PercentPointSpec(
            rawXPercent = rawXPercent,
            rawYPercent = rawYPercent
        )
    }

    private fun runDirectionalUiScrollStep(obj: JsonObject, timeout: Int): String {
        val direction = (obj.string("direction") ?: "down").lowercase()
        val swipeDurationMs = (obj.int("durationMs") ?: DEFAULT_UI_SCROLL_DURATION_MS).coerceIn(50, 60_000)
        val displaySize = getDisplaySize(timeout)
        val width = displaySize?.first ?: DEFAULT_UI_SCROLL_WIDTH
        val height = displaySize?.second ?: DEFAULT_UI_SCROLL_HEIGHT
        val centerX = width / 2
        val startY = if (direction == "up") (height * 35) / 100 else (height * 75) / 100
        val endY = if (direction == "up") (height * 75) / 100 else (height * 35) / 100
        return runCommand(
            "input swipe $centerX $startY $centerX $endY $swipeDurationMs; sleep 1",
            timeout
        )
    }

    private fun runUiWaitUntilFoundLoop(
        obj: JsonObject,
        timeout: Int,
        failureMessage: String,
        onFound: (matches: List<UiNode>, attempts: Int) -> String
    ): String {
        val deadline = System.currentTimeMillis() + timeout.coerceAtLeast(1) * 1000L
        var attempts = 0
        while (System.currentTimeMillis() <= deadline) {
            attempts++
            val matches = findUiNodes(obj, timeout = 5)
            if (!matches.isNullOrEmpty()) {
                return onFound(matches, attempts)
            }
            Thread.sleep(1000)
        }
        return failureMessage
    }

    private fun runUiWaitUntilGoneLoop(
        obj: JsonObject,
        timeout: Int,
        failureMessage: String,
        onGone: (attempts: Int) -> String
    ): String {
        val deadline = System.currentTimeMillis() + timeout.coerceAtLeast(1) * 1000L
        var attempts = 0
        while (System.currentTimeMillis() <= deadline) {
            attempts++
            val matches = findUiNodes(obj, timeout = 5)
            if (matches.isNullOrEmpty()) {
                return onGone(attempts)
            }
            Thread.sleep(1000)
        }
        return failureMessage
    }

    private fun runUiWaitGoneThenNextFoundLoop(
        currentSelector: JsonObject,
        nextSelector: JsonObject,
        timeout: Int,
        failureMessage: String,
        onNextFound: (matches: List<UiNode>, attempts: Int, goneAfterAttempt: Int) -> String
    ): String {
        val deadline = System.currentTimeMillis() + timeout.coerceAtLeast(1) * 1000L
        var attempts = 0
        var goneAfterAttempt: Int? = null
        while (System.currentTimeMillis() <= deadline) {
            attempts++
            if (goneAfterAttempt == null) {
                val currentMatches = findUiNodes(currentSelector, timeout = 5)
                if (currentMatches.isNullOrEmpty()) {
                    goneAfterAttempt = attempts
                } else {
                    Thread.sleep(1000)
                    continue
                }
            }
            val nextMatches = findUiNodes(nextSelector, timeout = 5)
            if (!nextMatches.isNullOrEmpty()) {
                return onNextFound(nextMatches, attempts, goneAfterAttempt)
            }
            Thread.sleep(1000)
        }
        return failureMessage
    }

    private fun runUiScrollWaitLoop(
        obj: JsonObject,
        timeout: Int,
        maxScrolls: Int,
        failureMessage: String,
        block: (matches: List<UiNode>, scrolls: Int, cycles: Int, scrollAction: Boolean) -> String
    ): String {
        val deadline = System.currentTimeMillis() + timeout.coerceAtLeast(1) * 1000L
        var scrolls = 0
        var cycles = 0
        while (System.currentTimeMillis() <= deadline) {
            val matches = findUiNodes(obj, timeout = 5)
            if (!matches.isNullOrEmpty()) {
                return block(matches, scrolls, cycles, false)
            }
            if (scrolls < maxScrolls) {
                val swipeOutput = block(emptyList(), scrolls, cycles, true)
                if (isFailureOutput(swipeOutput) || swipeOutput.startsWith("Error:", ignoreCase = true)) {
                    return swipeOutput
                }
                scrolls++
            } else {
                cycles++
                scrolls = 0
                Thread.sleep(1000)
            }
        }
        return failureMessage
    }

    private fun runPercentScrollWaitLoop(
        obj: JsonObject,
        timeout: Int,
        percent: PercentScrollSpec,
        failureMessage: String,
        onFound: (matches: List<UiNode>, scrolls: Int, cycles: Int, percent: PercentScrollSpec) -> String
    ): String {
        val deadline = System.currentTimeMillis() + timeout.coerceAtLeast(1) * 1000L
        var scrolls = 0
        var cycles = 0
        while (System.currentTimeMillis() <= deadline) {
            val matches = findUiNodes(obj, timeout = 5)
            if (!matches.isNullOrEmpty()) {
                return onFound(matches, scrolls, cycles, percent)
            }
            if (scrolls < percent.maxScrolls) {
                val swipeOutput = handleUiScrollPercent(obj, timeout)
                if (isFailureOutput(swipeOutput) || swipeOutput.startsWith("Error:", ignoreCase = true)) {
                    return swipeOutput
                }
                scrolls++
            } else {
                cycles++
                scrolls = 0
                Thread.sleep(1000)
            }
        }
        return failureMessage
    }

    private fun runPercentScrollFindLoop(
        obj: JsonObject,
        timeout: Int,
        percent: PercentScrollSpec,
        failureMessage: String,
        onFound: (matches: List<UiNode>, attempt: Int, percent: PercentScrollSpec) -> String
    ): String {
        for (attempt in 0..percent.maxScrolls) {
            val matches = findUiNodes(obj, timeout = 5)
            if (!matches.isNullOrEmpty()) {
                return onFound(matches, attempt, percent)
            }
            if (attempt >= percent.maxScrolls) break
            val swipeOutput = handleUiScrollPercent(obj, timeout)
            if (isFailureOutput(swipeOutput) || swipeOutput.startsWith("Error:", ignoreCase = true)) {
                return swipeOutput
            }
        }
        return failureMessage
    }

    private fun runUiScrollFindLoop(
        obj: JsonObject,
        timeout: Int,
        maxScrolls: Int,
        failureMessage: String,
        onFound: (matches: List<UiNode>, attempt: Int) -> String
    ): String {
        for (attempt in 0..maxScrolls) {
            val matches = findUiNodes(obj, timeout = 5)
            if (!matches.isNullOrEmpty()) {
                return onFound(matches, attempt)
            }
            if (attempt >= maxScrolls) break
            val swipeOutput = runDirectionalUiScrollStep(obj, timeout)
            if (isFailureOutput(swipeOutput) || swipeOutput.startsWith("Error:", ignoreCase = true)) {
                return swipeOutput
            }
        }
        return failureMessage
    }

    private fun StringBuilder.appendPercentScrollRange(percent: PercentScrollSpec) {
        append("FromPercent: (${percent.rawXPercent}, ${percent.rawYPercent})\n")
        append("ToPercent: (${percent.rawX2Percent}, ${percent.rawY2Percent})\n")
    }

    private fun StringBuilder.appendPercentPoint(point: PercentPointSpec) {
        append("Percent: (${point.rawXPercent}, ${point.rawYPercent})\n")
    }

    private fun handleLogcatRead(obj: JsonObject, timeout: Int): String {
        val requestedLines = (obj.int("lines") ?: DEFAULT_LOGCAT_LINES).coerceIn(1, 500)
        val tag = obj.string("tag").orEmpty()
        val priority = (obj.string("priority") ?: "V").uppercase()
        val since = obj.string("since").orEmpty()
        val follow = obj.bool("follow") ?: false
        val grep = obj.string("grep").orEmpty()
        val packageName = obj.string("packageName").orEmpty()
        val directPid = obj.int("pid")?.toString()
        val targetPids = when {
            directPid != null -> setOf(directPid)
            packageName.isNotBlank() -> runCommand("pidof ${shQuote(packageName)} 2>/dev/null || true", timeout)
                .split(Regex("""\s+"""))
                .filter { it.isNotBlank() }
                .toSet()
            else -> emptySet()
        }
        val fetchLines = (requestedLines * 4).coerceAtLeast(200)
        val command = buildString {
            append("logcat ")
            if (!follow) append("-d ")
            append("-v threadtime ")
            if (since.isNotBlank()) {
                append("-T ")
                append(shQuote(since))
                append(' ')
            } else if (follow) {
                append("-T $fetchLines ")
            } else {
                append("-t $fetchLines ")
            }
        }.trim()
        val output = runCommand(command, timeout)
        if (isFailureOutput(output)) return output
        val filtered = output.lines()
            .mapNotNull(::parseThreadTimeLogLine)
            .filter { entry ->
                val matchesTag = tag.isBlank() || entry.tag == tag
                val matchesPriority = priorityValue(entry.priority) >= priorityValue(priority)
                val matchesKeyword = grep.isBlank() || entry.message.contains(grep, ignoreCase = true) || entry.raw.contains(grep, ignoreCase = true)
                val matchesPid = targetPids.isEmpty() || entry.pid in targetPids
                matchesTag && matchesPriority && matchesKeyword && matchesPid
            }
            .takeLast(requestedLines)
        return renderStructured(
            linkedMapOf<String, Any?>(
                "action" to "logcat_read",
                "mode" to if (follow) "follow" else "snapshot",
                "filters" to linkedMapOf<String, Any?>(
                    "packageName" to packageName.ifBlank { null },
                    "resolvedPids" to targetPids.toList(),
                    "pid" to directPid,
                    "tag" to tag.ifBlank { null },
                    "priority" to priority,
                    "since" to since.ifBlank { null },
                    "grep" to grep.ifBlank { null }
                ),
                "showing" to filtered.size,
                "entries" to filtered.map { entry ->
                    linkedMapOf<String, Any?>(
                        "timestamp" to "${entry.date} ${entry.time}",
                        "level" to entry.priority,
                        "tag" to entry.tag,
                        "pid" to entry.pid.toIntOrNull(),
                        "tid" to entry.tid.toIntOrNull(),
                        "message" to entry.message,
                        "raw" to entry.raw
                    )
                }
            )
        )
    }

    private fun runAndFormat(summary: String, command: String, timeout: Int): String {
        val output = runCommand(command, timeout)
        return if (isFailureOutput(output)) {
            output
        } else {
            buildString {
                append(summary)
                append("\nCommand: ")
                append(command)
                if (output.isNotBlank() && output != "(command completed, no output)") {
                    append("\n\n")
                    append(truncateLines(output, DEFAULT_OUTPUT_LIMIT))
                }
            }
        }
    }

    private fun runCommand(command: String, timeout: Int): String {
        val safeTimeout = timeout.coerceAtLeast(1)
        val result = KeepShellPublic.doCmdSync(wrapCommandWithTimeout(command, safeTimeout))
        val timedOut = result.contains(TIMEOUT_MARKER)
        val normalized = result.replace(TIMEOUT_MARKER, "").trim()
        return when {
            normalized.startsWith("error:") -> "Command execution error: $normalized"
            timedOut && normalized.isBlank() -> "Command timed out after ${safeTimeout}s with no output."
            timedOut -> "$normalized\n\n...(命令执行超时，已中止，timeout=${safeTimeout}s)"
            else -> normalized.ifBlank { "(command completed, no output)" }
        }
    }

    private fun wrapCommandWithTimeout(command: String, timeoutSeconds: Int): String {
        return """
            (
                (
                    $command
                ) 2>&1 &
                cmd_pid=${'$'}!
                (
                    sleep $timeoutSeconds
                    if kill -0 ${'$'}cmd_pid 2>/dev/null; then
                        echo "$TIMEOUT_MARKER"
                        kill -TERM ${'$'}cmd_pid 2>/dev/null
                        sleep 1
                        kill -KILL ${'$'}cmd_pid 2>/dev/null
                    fi
                ) &
                watchdog_pid=${'$'}!
                wait ${'$'}cmd_pid
                status=${'$'}?
                kill ${'$'}watchdog_pid 2>/dev/null
                wait ${'$'}watchdog_pid 2>/dev/null
                exit ${'$'}status
            )
        """.trimIndent()
    }

    private fun resolveComponent(obj: JsonObject): String? {
        val explicit = obj.string("component")
        if (!explicit.isNullOrBlank()) return explicit
        val packageName = obj.string("packageName")
        val activity = obj.string("activity")
        if (packageName.isNullOrBlank() || activity.isNullOrBlank()) return null
        return when {
            '/' in activity -> activity
            activity.startsWith(".") -> "$packageName/$packageName$activity"
            else -> "$packageName/$activity"
        }
    }

    private fun listPidsForPackage(packageName: String, timeout: Int): List<String> {
        return runCommand("pidof ${shQuote(packageName)} 2>/dev/null || true", timeout)
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
    }

    private fun getDisplaySize(timeout: Int): Pair<Int, Int>? {
        val output = runCommand("wm size 2>/dev/null || true", timeout)
        if (isFailureOutput(output)) return null
        val match = Regex("""(?:Physical|Override) size:\s*(\d+)x(\d+)""").find(output) ?: return null
        val width = match.groupValues[1].toIntOrNull() ?: return null
        val height = match.groupValues[2].toIntOrNull() ?: return null
        return width to height
    }

    private fun normalizePercentValue(value: Double): Double? {
        return when {
            value in 0.0..1.0 -> value
            value in 0.0..100.0 -> value / 100.0
            else -> null
        }
    }

    private fun truncateLines(content: String, maxLines: Int): String {
        val lines = content.lines()
        return if (lines.size <= maxLines) {
            content
        } else {
            lines.take(maxLines).joinToString("\n") + "\n...(输出已截断)"
        }
    }

    private fun isFailureOutput(output: String): Boolean {
        return output.startsWith("Error:", ignoreCase = true) ||
            output.startsWith("Command execution error:", ignoreCase = true)
    }

    private fun findFirstGroup(content: String, regex: Regex): String? {
        return regex.find(content)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun findFirstMatchingLine(content: String, patterns: List<String>): String? {
        return content.lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.isNotBlank() && patterns.any { pattern ->
                    line.contains(pattern, ignoreCase = true)
                }
            }
    }

    private fun parseKeyValueLines(content: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && ':' in it }
            .forEach { line ->
                val key = line.substringBefore(':').trim()
                val value = line.substringAfter(':').trim()
                if (key.isNotBlank() && value.isNotBlank()) {
                    values[key] = value
                }
            }
        return values
    }

    private fun parsePsProcessLine(line: String): Map<String, Any?>? {
        val tokens = line.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        if (tokens.first().equals("USER", ignoreCase = true) || line.contains(" PID ") && line.contains(" NAME")) {
            return null
        }
        val pidIndex = tokens.indexOfFirst { it.toIntOrNull() != null }
        if (pidIndex < 0) {
            return linkedMapOf(
                "name" to tokens.lastOrNull(),
                "rawLine" to line
            )
        }
        val numericIndices = tokens.mapIndexedNotNull { index, token ->
            index.takeIf { token.toIntOrNull() != null }
        }
        val pid = tokens.getOrNull(pidIndex)?.toIntOrNull()
        val ppid = numericIndices.dropWhile { it != pidIndex }.drop(1)
            .firstOrNull()
            ?.let { tokens[it].toIntOrNull() }
        return linkedMapOf<String, Any?>(
            "user" to tokens.firstOrNull(),
            "pid" to pid,
            "ppid" to ppid,
            "name" to tokens.lastOrNull(),
            "state" to tokens.firstOrNull { it.length == 1 && it.all(Char::isLetter) },
            "rawLine" to line
        )
    }

    private fun renderUiNodeSummary(index: Int, node: UiNode): Map<String, Any?> {
        return linkedMapOf<String, Any?>(
            "index" to index,
            "className" to node.className.ifBlank { null },
            "packageName" to node.packageName.ifBlank { null },
            "resourceId" to node.resourceId.ifBlank { null },
            "text" to node.text.ifBlank { null },
            "contentDesc" to node.contentDesc.ifBlank { null },
            "clickable" to node.clickable,
            "enabled" to node.enabled,
            "bounds" to node.boundsRaw,
            "centerX" to node.centerX,
            "centerY" to node.centerY
        )
    }

    private fun parseKbValue(value: String?): Int? {
        return value
            ?.let { Regex("""(\d+)""").find(it)?.groupValues?.getOrNull(1) }
            ?.toIntOrNull()
    }

    private fun parseMeminfoTotalPssKb(content: String): Int? {
        val direct = findFirstGroup(content, Regex("""TOTAL PSS:\s*(\d+)"""))?.toIntOrNull()
        if (direct != null) return direct
        return content.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("TOTAL") }
            ?.split(Regex("""\s+"""))
            ?.drop(1)
            ?.firstOrNull { it.toIntOrNull() != null }
            ?.toIntOrNull()
    }

    private fun parseMeminfoNamedTotalKb(content: String, label: String): Int? {
        return content.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(label) }
            ?.split(Regex("""\s+"""))
            ?.drop(1)
            ?.firstOrNull { it.toIntOrNull() != null }
            ?.toIntOrNull()
    }

    private fun extractInterestingLines(content: String, keywords: List<String>, limit: Int): List<String> {
        return content.lineSequence()
            .map { it.trimEnd() }
            .filter { line ->
                line.isNotBlank() && keywords.any { keyword ->
                    line.contains(keyword, ignoreCase = true)
                }
            }
            .take(limit)
            .toList()
    }

    private fun renderStructured(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\"${escapeJson(value)}\""
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> value.entries.joinToString(
                prefix = "{",
                postfix = "}"
            ) { (key, item) ->
                "\"${escapeJson(key.toString())}\":${renderStructured(item)}"
            }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { item ->
                renderStructured(item)
            }
            else -> "\"${escapeJson(value.toString())}\""
        }
    }

    private fun escapeJson(value: String): String {
        return buildString(value.length + 8) {
            value.forEach { ch ->
                append(
                    when (ch) {
                        '\\' -> "\\\\"
                        '"' -> "\\\""
                        '\b' -> "\\b"
                        '\u000C' -> "\\f"
                        '\n' -> "\\n"
                        '\r' -> "\\r"
                        '\t' -> "\\t"
                        else -> if (ch.code < 0x20) {
                            "\\u" + ch.code.toString(16).padStart(4, '0')
                        } else {
                            ch.toString()
                        }
                    }
                )
            }
        }
    }

    private fun collectIndentedBlock(
        lines: List<String>,
        header: String,
        nextHeaders: List<String>,
        maxItems: Int
    ): List<String> {
        val startIndex = lines.indexOfFirst { it.trim() == header }
        if (startIndex < 0) return emptyList()
        val items = mutableListOf<String>()
        for (index in startIndex + 1 until lines.size) {
            val raw = lines[index]
            val trimmed = raw.trim()
            if (trimmed.isBlank()) continue
            if (nextHeaders.any { trimmed.startsWith(it) }) break
            if (!raw.startsWith(" ") && !raw.startsWith("\t")) break
            items += trimmed
            if (items.size >= maxItems) break
        }
        return items
    }

    private fun shQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun encodeInputText(value: String): String {
        val trimmed = value.replace("\r\n", "\n").replace('\n', ' ').trim()
        if (trimmed.isBlank()) return ""
        return buildString {
            trimmed.forEach { ch ->
                append(
                    when (ch) {
                        ' ' -> "%s"
                        '%' -> "\\%"
                        '"' -> "\\\""
                        '\'' -> "\\'"
                        '&' -> "\\&"
                        '<' -> "\\<"
                        '>' -> "\\>"
                        '(' -> "\\("
                        ')' -> "\\)"
                        ';' -> "\\;"
                        '|' -> "\\|"
                        '*' -> "\\*"
                        '?' -> "\\?"
                        '[' -> "\\["
                        ']' -> "\\]"
                        '{' -> "\\{"
                        '}' -> "\\}"
                        '$' -> "\\$"
                        '!' -> "\\!"
                        '#' -> "\\#"
                        else -> ch
                    }
                )
            }
        }
    }

    private fun normalizeKeyCode(value: String): String {
        val trimmed = value.trim()
        if (trimmed.all { it.isDigit() }) return trimmed
        val upper = trimmed.uppercase()
        return if (upper.startsWith("KEYCODE_")) upper else "KEYCODE_$upper"
    }

    private fun parseUiNodes(xml: String): List<UiNode> {
        return NODE_TAG_REGEX.findAll(xml).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val attrMap = ATTRIBUTE_REGEX.findAll(attrs)
                .associate { attr ->
                    attr.groupValues[1] to decodeXml(attr.groupValues[2])
                }
            val boundsRaw = attrMap["bounds"].orEmpty()
            val bounds = parseBounds(boundsRaw) ?: return@mapNotNull null
            UiNode(
                text = attrMap["text"].orEmpty(),
                resourceId = attrMap["resource-id"].orEmpty(),
                contentDesc = attrMap["content-desc"].orEmpty(),
                className = attrMap["class"].orEmpty(),
                packageName = attrMap["package"].orEmpty(),
                clickable = attrMap["clickable"].orEmpty().equals("true", ignoreCase = true),
                enabled = !attrMap["enabled"].orEmpty().equals("false", ignoreCase = true),
                boundsRaw = boundsRaw,
                left = bounds.first.first,
                top = bounds.first.second,
                right = bounds.second.first,
                bottom = bounds.second.second
            )
        }.toList()
    }

    private fun parseBounds(raw: String): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val match = BOUNDS_REGEX.matchEntire(raw) ?: return null
        val left = match.groupValues[1].toIntOrNull() ?: return null
        val top = match.groupValues[2].toIntOrNull() ?: return null
        val right = match.groupValues[3].toIntOrNull() ?: return null
        val bottom = match.groupValues[4].toIntOrNull() ?: return null
        return (left to top) to (right to bottom)
    }

    private fun decodeXml(value: String): String {
        return value
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&apos;", "'")
    }

    private fun parseThreadTimeLogLine(line: String): ParsedLogLine? {
        val match = THREADTIME_REGEX.matchEntire(line) ?: return null
        return ParsedLogLine(
            raw = line,
            date = match.groupValues[1],
            time = match.groupValues[2],
            pid = match.groupValues[3],
            tid = match.groupValues[4],
            priority = match.groupValues[5],
            tag = match.groupValues[6].trim(),
            message = match.groupValues[7]
        )
    }

    private fun priorityValue(priority: String): Int {
        return when (priority.uppercase()) {
            "V" -> 0
            "D" -> 1
            "I" -> 2
            "W" -> 3
            "E" -> 4
            "F" -> 5
            else -> 0
        }
    }

    private fun approval(
        summary: String,
        detail: String,
        rawArgs: String,
        riskLevel: ApprovalRiskLevel
    ): ToolApprovalRequest {
        return ToolApprovalRequest(
            toolName = name,
            summary = summary,
            detail = detail,
            riskLevel = riskLevel,
            rawArgs = rawArgs
        )
    }

    private data class ParsedLogLine(
        val raw: String,
        val date: String,
        val time: String,
        val pid: String,
        val tid: String,
        val priority: String,
        val tag: String,
        val message: String
    )

    private data class PercentScrollSpec(
        val rawXPercent: Double,
        val rawYPercent: Double,
        val rawX2Percent: Double,
        val rawY2Percent: Double,
        val maxScrolls: Int
    )

    private data class PercentPointSpec(
        val rawXPercent: Double,
        val rawYPercent: Double
    )

    private data class UiNode(
        val text: String,
        val resourceId: String,
        val contentDesc: String,
        val className: String,
        val packageName: String,
        val clickable: Boolean,
        val enabled: Boolean,
        val boundsRaw: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }

    private fun JsonObject.string(key: String): String? {
        return runCatching {
            this[key]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun JsonObject.int(key: String): Int? {
        return runCatching {
            this[key]?.jsonPrimitive?.content?.trim()?.toIntOrNull()
        }.getOrNull()
    }

    private fun JsonObject.double(key: String): Double? {
        return runCatching {
            this[key]?.jsonPrimitive?.content?.trim()?.toDoubleOrNull()
        }.getOrNull()
    }

    private fun JsonObject.bool(key: String): Boolean? {
        return runCatching {
            this[key]?.jsonPrimitive?.content?.trim()?.toBooleanStrictOrNull()
        }.getOrNull()
    }

    private fun JsonObject.stringList(key: String): List<String> {
        val element = this[key] ?: return emptyList()
        return runCatching {
            element.jsonArray.mapNotNull { item ->
                runCatching { item.jsonPrimitive.content.trim() }.getOrNull()
            }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private companion object {
        val SUPPORTED_ACTIONS = listOf(
            "logcat_read",
            "logcat_clear",
            "app_list",
            "app_path",
            "app_info",
            "app_start",
            "app_force_stop",
            "app_clear_data",
            "app_install",
            "app_uninstall",
            "app_enable",
            "app_disable",
            "app_current_top",
            "app_grant",
            "app_revoke",
            "process_list",
            "process_by_package",
            "process_meminfo",
            "process_thread_summary",
            "process_kill",
            "dumpsys",
            "dumpsys_activity",
            "dumpsys_window",
            "dumpsys_package",
            "dumpsys_battery",
            "dumpsys_meminfo",
            "dumpsys_input",
            "dumpsys_display",
            "settings_get",
            "settings_put",
            "settings_list",
            "network_summary",
            "notification_summary",
            "notification_channels",
            "notification_permission_status",
            "app_special_access_status",
            "app_notification_listener_status",
            "app_accessibility_status",
            "app_usage_access_status",
            "notification_open_settings",
            "app_open_settings",
            "app_open_permission_settings",
            "app_open_battery_settings",
            "app_open_overlay_settings",
            "app_open_unknown_sources_settings",
            "app_open_notification_listener_settings",
            "app_open_accessibility_settings",
            "app_open_usage_access_settings",
            "app_install_sessions",
            "intent_start",
            "intent_broadcast",
            "ui_current_focus",
            "ui_dump",
            "ui_screenshot",
            "ui_dump_summary",
            "ui_find",
            "ui_tap",
            "ui_tap_percent",
            "ui_swipe_percent",
            "ui_click_node",
            "ui_click_first",
            "ui_long_press_node",
            "ui_long_press",
            "ui_long_press_percent",
            "ui_set_text_node",
            "ui_set_text_clear_node",
            "ui_select_all_and_replace_node",
            "ui_wait_text",
            "ui_wait_gone",
            "ui_wait_and_click_first",
            "ui_wait_and_replace_first",
            "ui_wait_and_long_press_first",
            "ui_wait_and_set_text_clear_first",
            "ui_wait_and_set_text_first",
            "ui_wait_and_tap_percent",
            "ui_wait_and_swipe_percent",
            "ui_wait_and_scroll_percent",
            "ui_wait_and_find",
            "ui_wait_and_dump",
            "ui_wait_and_dump_summary",
            "ui_wait_and_current_focus",
            "ui_wait_and_backstack_summary",
            "ui_wait_and_screenshot",
            "ui_wait_and_text",
            "ui_wait_and_keyevent",
            "ui_wait_and_tap",
            "ui_wait_and_swipe",
            "ui_wait_and_back",
            "ui_wait_and_long_press",
            "ui_wait_and_long_press_percent",
            "ui_wait_gone_then_click_first",
            "ui_wait_gone_then_back",
            "ui_wait_gone_then_set_text_first",
            "ui_wait_gone_then_set_text_clear_first",
            "ui_wait_gone_then_long_press_first",
            "ui_wait_gone_then_replace_first",
            "ui_wait_gone_then_keyevent",
            "ui_wait_gone_then_swipe",
            "ui_wait_gone_then_tap",
            "ui_wait_gone_then_long_press",
            "ui_wait_gone_then_tap_percent",
            "ui_wait_gone_then_swipe_percent",
            "ui_wait_gone_then_scroll_percent",
            "ui_wait_gone_then_long_press_percent",
            "ui_wait_gone_then_text",
            "ui_wait_gone_then_screenshot",
            "ui_wait_gone_then_dump",
            "ui_wait_gone_then_dump_summary",
            "ui_wait_gone_then_current_focus",
            "ui_wait_gone_then_backstack_summary",
            "ui_wait_gone_then_find",
            "ui_scroll_find",
            "ui_scroll_find_percent",
            "ui_scroll_click_first",
            "ui_scroll_click_first_percent",
            "ui_scroll_long_press_first",
            "ui_scroll_long_press_first_percent",
            "ui_scroll_select_all_and_replace_first",
            "ui_scroll_select_all_and_replace_first_percent",
            "ui_scroll_wait_and_click_first",
            "ui_scroll_wait_and_click_first_percent",
            "ui_scroll_wait_and_tap_percent",
            "ui_scroll_wait_and_swipe_percent",
            "ui_scroll_wait_and_scroll_percent",
            "ui_scroll_wait_and_replace_first",
            "ui_scroll_wait_and_replace_first_percent",
            "ui_scroll_wait_and_long_press_first",
            "ui_scroll_wait_and_long_press_first_percent",
            "ui_scroll_wait_and_set_text_clear_first",
            "ui_scroll_wait_and_set_text_clear_first_percent",
            "ui_scroll_wait_and_set_text_first",
            "ui_scroll_wait_and_set_text_first_percent",
            "ui_scroll_wait_and_find",
            "ui_scroll_wait_and_find_percent",
            "ui_scroll_wait_and_dump_percent",
            "ui_scroll_wait_and_dump",
            "ui_scroll_wait_and_dump_summary_percent",
            "ui_scroll_wait_and_dump_summary",
            "ui_scroll_wait_and_current_focus_percent",
            "ui_scroll_wait_and_current_focus",
            "ui_scroll_wait_and_backstack_summary_percent",
            "ui_scroll_wait_and_backstack_summary",
            "ui_scroll_wait_and_screenshot_percent",
            "ui_scroll_wait_and_screenshot",
            "ui_scroll_wait_and_text",
            "ui_scroll_wait_and_text_percent",
            "ui_scroll_wait_and_keyevent",
            "ui_scroll_wait_and_keyevent_percent",
            "ui_scroll_wait_and_tap",
            "ui_scroll_wait_and_swipe",
            "ui_scroll_wait_and_back",
            "ui_scroll_wait_and_back_percent",
            "ui_scroll_wait_and_long_press",
            "ui_scroll_wait_and_long_press_percent",
            "ui_scroll_set_text_clear_first",
            "ui_scroll_set_text_clear_first_percent",
            "ui_scroll_set_text_first",
            "ui_scroll_set_text_first_percent",
            "ui_backstack_summary",
            "ui_swipe",
            "ui_scroll_percent",
            "ui_text",
            "ui_keyevent"
        )
        const val DEFAULT_TIMEOUT_SECONDS = 12
        const val DEFAULT_OUTPUT_LIMIT = 120
        const val DEFAULT_LOGCAT_LINES = 200
        const val DEFAULT_LIST_LIMIT = 100
        const val DEFAULT_UI_DUMP_LINES = 180
        const val DEFAULT_UI_SUMMARY_LIMIT = 40
        const val DEFAULT_UI_FIND_LIMIT = 20
        const val DEFAULT_UI_BACKSTACK_LIMIT = 40
        const val DEFAULT_UI_SCROLL_FIND_MAX_SCROLLS = 6
        const val DEFAULT_UI_SCROLL_DURATION_MS = 450
        const val DEFAULT_UI_LONG_PRESS_DURATION_MS = 800
        const val DEFAULT_UI_SCROLL_WIDTH = 1080
        const val DEFAULT_UI_SCROLL_HEIGHT = 2400
        const val DEFAULT_SETTINGS_LIST_LIMIT = 120
        const val DEFAULT_NETWORK_SUMMARY_LIMIT = 120
        const val DEFAULT_NOTIFICATION_SUMMARY_LIMIT = 120
        const val DEFAULT_INSTALL_TIMEOUT_SECONDS = 120
        const val TIMEOUT_MARKER = "__RSNX_ANDROID_TIMEOUT__"
        const val FILE_MISSING_MARKER = "__RSNX_ANDROID_FILE_MISSING__"
        const val TMP_DIR = "/data/local/tmp"
        val TOKEN_REGEX = Regex("""^[A-Za-z0-9._:-]+$""")
        val THREADTIME_REGEX = Regex(
            """^(\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+):\s?(.*)$"""
        )
        val NODE_TAG_REGEX = Regex("""<node\s+([^>]+)/?>""")
        val ATTRIBUTE_REGEX = Regex("""([A-Za-z0-9_.:-]+)="([^"]*)"""")
        val BOUNDS_REGEX = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
    }
}

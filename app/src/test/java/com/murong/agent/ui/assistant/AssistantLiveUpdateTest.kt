package com.murong.agent.ui.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantLiveUpdateTest {
    @Test
    fun `extracts bounded step progress for live update`() {
        assertEquals(
            AssistantLiveUpdateProgress(current = 3, maximum = 60),
            assistantLiveUpdateProgress("第 3/60 步：正在识别界面并规划下一步"),
        )
        assertEquals(
            AssistantLiveUpdateProgress(current = 5, maximum = 5),
            assistantLiveUpdateProgress("第 99 / 5 步"),
        )
    }

    @Test
    fun `uses compact status chip text`() {
        assertEquals("3/60", assistantLiveUpdateChipText("第 3/60 步：执行中"))
        assertEquals("执行中", assistantLiveUpdateChipText("正在准备隔离屏"))
        assertNull(assistantLiveUpdateProgress("模型正在思考"))
    }

    @Test
    fun `offscreen surfaces keep chat and voice assistant as separate actions`() {
        assertEquals(
            listOf("返回聊天", "回到语音助手", "全屏预览", "全屏接管", "关闭离屏"),
            AssistantOffscreenActionLabels.all,
        )
        assertEquals(
            listOf("返回聊天", "回到语音助手", "离屏控制"),
            AssistantOffscreenActionLabels.fluidCloud,
        )
    }

    @Test
    fun `retained fluid cloud keeps offscreen review instead of destructive close`() {
        assertEquals(
            AssistantPhoneLiveThirdAction.OPEN_OFFSCREEN,
            assistantPhoneLiveThirdAction(
                offscreenAvailable = true,
                retainForReview = true,
            ),
        )
        assertEquals(
            AssistantPhoneLiveThirdAction.OPEN_OFFSCREEN,
            assistantPhoneLiveThirdAction(
                offscreenAvailable = true,
                retainForReview = false,
            ),
        )
        assertEquals(
            AssistantPhoneLiveThirdAction.CONFIRM_AND_CLOSE,
            assistantPhoneLiveThirdAction(
                offscreenAvailable = false,
                retainForReview = true,
            ),
        )
        assertEquals(
            AssistantPhoneLiveThirdAction.STOP,
            assistantPhoneLiveThirdAction(
                offscreenAvailable = false,
                retainForReview = false,
            ),
        )
    }

    @Test
    fun `completed phone task retains its offscreen result for acceptance`() {
        AssistantOffscreenTaskState.begin(
            request = "测试任务",
            title = "手机操作",
            detail = "正在运行",
        )
        AssistantOffscreenTaskState.update(
            title = "手机操作",
            detail = "任务已完成，离屏仍保留",
            active = false,
            displayAvailable = true,
            progressEntry = "模型回复：已完成并验证",
        )

        assertEquals(false, AssistantOffscreenTaskState.state.value?.active)
        assertEquals(true, AssistantOffscreenTaskState.state.value?.displayAvailable)
        assertEquals(
            listOf("正在运行", "模型回复：已完成并验证"),
            AssistantOffscreenTaskState.state.value?.progressHistory,
        )
        assertEquals("已完成并验证", AssistantOffscreenTaskState.state.value?.modelOutput)
        assertEquals(
            listOf("已完成并验证"),
            AssistantOffscreenTaskState.state.value?.modelOutputHistory,
        )
        assertEquals(
            listOf("正在运行"),
            AssistantOffscreenTaskState.state.value?.executionHistory,
        )
    }

    @Test
    fun `streaming model text replaces prior partial instead of flooding execution history`() {
        AssistantOffscreenTaskState.begin("测试任务", "手机操作", "正在读取屏幕")
        AssistantOffscreenTaskState.update(
            title = "手机操作",
            detail = "模型正在说：我看到搜索框",
            active = true,
            progressEntry = "模型正在说：我看到搜索框",
        )
        AssistantOffscreenTaskState.update(
            title = "手机操作",
            detail = "模型正在说：我看到搜索框，准备输入店名",
            active = true,
            progressEntry = "模型正在说：我看到搜索框，准备输入店名",
        )

        assertEquals(
            "我看到搜索框，准备输入店名",
            AssistantOffscreenTaskState.state.value?.modelOutput,
        )
        assertEquals(1, AssistantOffscreenTaskState.state.value?.executionHistory?.size)
        assertEquals(2, AssistantOffscreenTaskState.state.value?.progressHistory?.size)
        assertEquals(
            listOf("我看到搜索框，准备输入店名"),
            AssistantOffscreenTaskState.state.value?.modelOutputHistory,
        )
    }

    @Test
    fun `wait heartbeat is coalesced and excluded from execution actions`() {
        AssistantOffscreenTaskState.begin("测试任务", "手机操作", "正在读取屏幕")
        AssistantOffscreenTaskState.update(
            title = "手机操作",
            detail = "第 1/60 步：本地模型仍在分析当前截图，已等待 10 秒；可随时停止",
            active = true,
            progressEntry = "第 1/60 步：本地模型仍在分析当前截图，已等待 10 秒；可随时停止",
        )
        AssistantOffscreenTaskState.update(
            title = "手机操作",
            detail = "第 1/60 步：本地模型仍在分析当前截图，已等待 20 秒；可随时停止",
            active = true,
            progressEntry = "第 1/60 步：本地模型仍在分析当前截图，已等待 20 秒；可随时停止",
        )

        assertEquals(2, AssistantOffscreenTaskState.state.value?.progressHistory?.size)
        assertEquals(
            listOf("正在读取屏幕"),
            AssistantOffscreenTaskState.state.value?.executionHistory,
        )
        assertEquals("", AssistantOffscreenTaskState.state.value?.modelOutput)
    }

    @Test
    fun `model output appends across steps instead of overwriting prior narration`() {
        AssistantOffscreenTaskState.begin("测试任务", "手机操作", "正在读取屏幕")
        AssistantOffscreenTaskState.update(
            title = "手机操作",
            detail = "模型正在说：我看到拼多多首页",
            active = true,
            progressEntry = "模型正在说：我看到拼多多首页",
        )
        AssistantOffscreenTaskState.update(
            title = "手机操作",
            detail = "执行动作：检查底栏",
            active = true,
            progressEntry = "执行动作：检查底栏",
        )
        AssistantOffscreenTaskState.update(
            title = "手机操作",
            detail = "模型正在说：首页底栏已高亮，任务可以结束",
            active = true,
            progressEntry = "模型正在说：首页底栏已高亮，任务可以结束",
        )

        assertEquals(
            listOf("我看到拼多多首页", "首页底栏已高亮，任务可以结束"),
            AssistantOffscreenTaskState.state.value?.modelOutputHistory,
        )
        assertEquals(
            "我看到拼多多首页\n\n首页底栏已高亮，任务可以结束",
            AssistantOffscreenTaskState.state.value?.modelOutput,
        )
    }

    @Test
    fun `model output replaces heartbeat as the current visible progress`() {
        val heartbeat = "第 1/60 步：本地模型仍在分析当前截图，已等待 30 秒"
        assertEquals(true, isAssistantInferenceHeartbeat(heartbeat))
        assertEquals(true, shouldReplaceAssistantProgress(heartbeat, heartbeat.replace("30", "40")))
        assertEquals(
            true,
            shouldReplaceAssistantProgress("模型正在说：看到 QQ", "模型回复：已核对昵称"),
        )
    }
}

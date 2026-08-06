package com.murong.agent.ui.assistant

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantRequestRouterTest {
    @Test
    fun `basic device controls stay local and never invoke phone vision`() {
        listOf(
            "打开手电筒",
            "打开相机",
            "关闭自动旋转",
            "打开 Wi-Fi",
            "关闭移动数据",
        ).forEach { request ->
            val route = AssistantRequestRouter.classify(request)
            assertEquals(AssistantTaskKind.INSTANT_LOCAL, route.kind)
            assertFalse(route.requiresScreenContext)
            assertFalse(route.mayTakeOverScreen)
        }
    }

    @Test
    fun `messages replies and food orders use phone automation instead of chat`() {
        listOf(
            "给雪绒的3号小屋发消息说你好",
            "回复微信里老板的消息说收到",
            "帮我点一杯蜜雪冰城",
        ).forEach { request ->
            val route = AssistantRequestRouter.classify(request)
            assertEquals(AssistantTaskKind.PHONE_FOREGROUND, route.kind)
            assertTrue(route.runWithNotification)
        }
    }
    @Test
    fun `greeting never requests screen context`() {
        val route = AssistantRequestRouter.classify("你好")

        assertEquals(AssistantTaskKind.INSTANT_LOCAL, route.kind)
        assertFalse(route.requiresScreenContext)
        assertFalse(route.runWithNotification)
    }

    @Test
    fun `screen context is attached only for explicit screen references`() {
        val ordinary = AssistantRequestRouter.classify("给我讲个笑话")
        val screenAware = AssistantRequestRouter.classify("看看当前屏幕写了什么")
        val visual = AssistantRequestRouter.classify("识别图片里的文字")

        assertFalse(ordinary.requiresScreenContext)
        assertEquals(AssistantTaskKind.SCREEN_AWARE, screenAware.kind)
        assertTrue(screenAware.requiresScreenContext)
        assertEquals(AssistantTaskKind.SCREEN_AWARE, visual.kind)
        assertTrue(visual.requiresScreenContext)
    }

    @Test
    fun `phone action wins over generic screen reference`() {
        val route = AssistantRequestRouter.classify("帮我点击屏幕上的确定按钮")

        assertEquals(AssistantTaskKind.PHONE_FOREGROUND, route.kind)
        assertTrue(route.runWithNotification)
        assertTrue(route.mayTakeOverScreen)
    }

    @Test
    fun `food comparison remains public background research`() {
        val route = AssistantRequestRouter.classify("比较一下美团和饿了么哪家外卖便宜")
        val modelInput = AssistantRequestRouter.backgroundModelInput("外卖比价", route)

        assertEquals(AssistantTaskKind.BACKGROUND_WEB_RESEARCH, route.kind)
        assertTrue(route.runWithNotification)
        assertTrue(modelInput.contains("不要启动、点击或控制任何手机 App"))
    }

    @Test
    fun `fresh information uses web plus fast local summary`() {
        assertEquals(
            AssistantTaskKind.FAST_LOCAL_WEB,
            AssistantRequestRouter.classify("搜索一下今天最新资讯").kind,
        )
        assertEquals(
            AssistantTaskKind.FAST_LOCAL_WEB,
            AssistantRequestRouter.classify("查一下新闻").kind,
        )
    }

    @Test
    fun `calendar and clock queries stay deterministic`() {
        assertEquals(
            AssistantTaskKind.INSTANT_LOCAL,
            AssistantRequestRouter.classify("设置一个日程，在两天后更新调度").kind,
        )
        assertEquals(
            AssistantTaskKind.INSTANT_LOCAL,
            AssistantRequestRouter.classify("今天时间").kind,
        )
        listOf(
            "开启五分钟倒计时",
            "暂停倒计时",
            "关闭倒计时",
            "开始秒表",
            "暂停秒表",
            "秒表清零",
        ).forEach { request ->
            val route = AssistantRequestRouter.classify(request)
            assertEquals(AssistantTaskKind.INSTANT_LOCAL, route.kind)
            assertFalse(route.mayTakeOverScreen)
            assertFalse(route.requiresScreenContext)
        }
    }

    @Test
    fun `fresh news uses the isolated web-summary route`() {
        val route = AssistantRequestRouter.classify("搜索一下最新的新闻")

        assertEquals(AssistantTaskKind.FAST_LOCAL_WEB, route.kind)
        val source = File(
            "src/main/java/com/murong/agent/ui/assistant/AssistantConversationRunner.kt",
        ).readText()
        assertTrue(source.contains("route.kind != AssistantTaskKind.FAST_LOCAL_WEB"))
        assertTrue(source.contains("不得复述或回答先前不相关的话题"))
    }

    @Test
    fun `creative writing uses the capable main model`() {
        assertEquals(
            AssistantTaskKind.MAIN_MODEL,
            AssistantRequestRouter.classify("写个短篇小说").kind,
        )
    }

    @Test
    fun `code and process work uses main model in background`() {
        assertEquals(
            AssistantTaskKind.BACKGROUND_CODE,
            AssistantRequestRouter.classify("帮我写个 Python 脚本并运行测试").kind,
        )
        assertEquals(
            AssistantTaskKind.BACKGROUND_CODE,
            AssistantRequestRouter.classify("查看并管理进程").kind,
        )
    }
}

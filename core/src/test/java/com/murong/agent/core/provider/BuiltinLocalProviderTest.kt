package com.murong.agent.core.provider

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinLocalProviderTest {
    @Test
    fun `visible language policy keeps Chinese reasoning and answer for Chinese users`() {
        assertTrue(BUILTIN_LOCAL_VISIBLE_LANGUAGE_POLICY.contains("所有可见的思考过程"))
        assertTrue(BUILTIN_LOCAL_VISIBLE_LANGUAGE_POLICY.contains("最终回答都必须使用中文"))
        assertTrue(BUILTIN_LOCAL_VISIBLE_LANGUAGE_POLICY.contains("用户要求原样输出"))
    }

    @Test
    fun `legacy tool marker is never streamed as assistant prose`() {
        val source = File(
            "src/main/java/com/murong/agent/core/provider/BuiltinLocalProvider.kt"
        ).readText()

        assertTrue(source.contains("LEGACY_TOOL_START to Mode.TOOL"))
        assertTrue(source.contains("助手绝对不能输出或仿写 [TOOL]"))
        assertTrue(source.contains("本地模型返回了无效的工具调用"))
        assertTrue(source.contains("本轮只进行自然语言聊天、问答或总结"))
        assertTrue(source.contains("allowToolCalls"))
    }

    @Test
    fun `tool free request uses the dedicated plain chat protocol`() {
        val provider = BuiltinLocalProvider()
        val method = BuiltinLocalProvider::class.java.getDeclaredMethod(
            "buildLocalPrompt",
            ChatRequest::class.java,
        ).apply { isAccessible = true }
        val prompt = method.invoke(
            provider,
            ChatRequest(
                messages = listOf(ChatMessage(role = "user", content = "你好")),
                model = "glm-edge-1.5b-chat",
                tools = null,
            ),
        ) as String

        assertTrue(prompt.contains("本轮只进行自然语言聊天、问答或总结"))
        assertFalse(prompt.contains("Android 的 gui 启动应用必须用"))
        assertFalse(prompt.contains("[AVAILABLE TOOLS]"))
    }
}

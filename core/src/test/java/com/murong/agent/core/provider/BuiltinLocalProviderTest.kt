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

    @Test
    fun `tool request keeps the complete phone action system protocol`() {
        val provider = BuiltinLocalProvider()
        val method = BuiltinLocalProvider::class.java.getDeclaredMethod(
            "buildLocalPrompt",
            ChatRequest::class.java,
        ).apply { isAccessible = true }
        val protocolTail = "PHONE_PROTOCOL_TAIL"
        val prompt = method.invoke(
            provider,
            ChatRequest(
                messages = listOf(
                    ChatMessage(
                        role = "system",
                        content = "x".repeat(1_500) + protocolTail,
                    ),
                    ChatMessage(role = "user", content = "操作手机"),
                ),
                model = "glm-edge-1.5b-chat",
                tools = """[{"type":"function","function":{"name":"phone_action"}}]""",
            ),
        ) as String

        assertTrue(prompt.contains(protocolTail))
        assertTrue(prompt.contains("[AVAILABLE TOOLS]"))
        assertTrue(prompt.contains("手机单步动作执行器"))
        assertTrue(prompt.contains("这句话会原样显示给用户"))
        assertTrue(prompt.contains("\"message\":\"看到目标按钮，准备点击\""))
        assertFalse(prompt.contains("Android 的 gui 启动应用必须用"))
    }

    @Test
    fun `phone action history keeps original task ahead of long observations`() {
        val provider = BuiltinLocalProvider()
        val method = BuiltinLocalProvider::class.java.getDeclaredMethod(
            "buildLocalPrompt",
            ChatRequest::class.java,
        ).apply { isAccessible = true }
        val task = "打开微信给慕容茹艳发送你好"
        val prompt = method.invoke(
            provider,
            ChatRequest(
                messages = listOf(
                    ChatMessage(role = "system", content = "手机协议"),
                    ChatMessage(role = "user", content = task),
                    ChatMessage(
                        role = "user",
                        content = "第 2 步。当前应用：微信。\n" + "控件摘要".repeat(2_000),
                    ),
                ),
                model = "qwen3.5-4b-pro",
                tools = """[{"type":"function","function":{"name":"phone_action"}}]""",
            ),
        ) as String

        assertTrue(prompt.contains(task))
        assertTrue(prompt.contains("第 2 步。当前应用：微信"))
    }
}

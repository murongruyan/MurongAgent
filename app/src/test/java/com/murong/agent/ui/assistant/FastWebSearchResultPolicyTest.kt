package com.murong.agent.ui.assistant

import kotlin.test.Test
import kotlin.test.assertTrue

class FastWebSearchResultPolicyTest {
    @Test
    fun `failed retrieval is reported without asking a local model to invent an answer`() {
        val message = fastWebSearchUnavailableMessage("网页搜索暂时不可用：Bing 超时")

        assertTrue(message.contains("未调用本地模型编造回答"))
        assertTrue(message.contains("网页搜索暂时不可用"))
    }
}

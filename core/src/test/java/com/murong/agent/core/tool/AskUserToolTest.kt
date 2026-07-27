package com.murong.agent.core.tool

import com.murong.agent.core.loop.PendingAskRequestUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

class AskUserToolTest {
    @Test
    fun stringOptionsFromSmallLocalModelsAreNormalized() = runBlocking {
        var captured: PendingAskRequestUi? = null
        val tool = AskUserTool { request ->
            captured = request
            null
        }

        tool.execute(
            """
                {
                  "questions": [{
                    "header": "方式",
                    "question": "怎么继续？",
                    "options": ["自动处理", "停止"]
                  }]
                }
            """.trimIndent()
        )

        val question = assertNotNull(captured).questions.single()
        assertEquals(listOf("自动处理", "停止"), question.options.map { it.label })
    }
}

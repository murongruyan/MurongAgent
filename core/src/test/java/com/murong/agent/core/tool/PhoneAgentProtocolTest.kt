package com.murong.agent.core.tool

import com.murong.agent.core.provider.ToolCall
import com.murong.agent.core.provider.ToolCallFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PhoneAgentProtocolTest {
    @Test
    fun parsesOfficialTapSyntaxAfterReasoning() {
        val decision = PhoneAgentProtocol.parse(
            """需要点击搜索框。do(action="Tap", element=[512, 233])"""
        )

        val execute = assertIs<PhoneAgentDecision.Execute>(decision)
        assertEquals("Tap", execute.command.action)
        assertEquals(512, execute.command.x)
        assertEquals(233, execute.command.y)
    }

    @Test
    fun parsesSingleQuotedLaunchAndSwipe() {
        val launch = assertIs<PhoneAgentDecision.Execute>(
            PhoneAgentProtocol.parse("do(action='Launch', app='美团')")
        )
        val swipe = assertIs<PhoneAgentDecision.Execute>(
            PhoneAgentProtocol.parse(
                "do(action='Swipe', start=[500,800], end=[500,200], duration=600)"
            )
        )

        assertEquals("美团", launch.command.app)
        assertEquals(500, swipe.command.startX)
        assertEquals(200, swipe.command.endY)
        assertEquals(600, swipe.command.durationMs)
    }

    @Test
    fun parsesEscapedJsonFinishMessage() {
        val decision = PhoneAgentProtocol.parse(
            """finish(message="{\"query\":\"奶茶\",\"offers\":[]}")"""
        )

        val finish = assertIs<PhoneAgentDecision.Finish>(decision)
        assertEquals("""{"query":"奶茶","offers":[]}""", finish.message)
    }

    @Test
    fun latestProtocolCallWins() {
        val decision = PhoneAgentProtocol.parse(
            """不要采用 do(action="Wait")，改为 finish(message="已完成")"""
        )

        assertEquals("已完成", assertIs<PhoneAgentDecision.Finish>(decision).message)
    }

    @Test
    fun parsesStrictJsonFromGenericVisionModel() {
        val decision = PhoneAgentProtocol.parse(
            """
            ```json
            {"action":"swipe","startX":500,"startY":800,"endX":500,"endY":200,"durationMs":600}
            ```
            """.trimIndent()
        )

        val execute = assertIs<PhoneAgentDecision.Execute>(decision)
        assertEquals("Swipe", execute.command.action)
        assertEquals(800, execute.command.startY)
        assertEquals(200, execute.command.endY)
        assertEquals(600, execute.command.durationMs)
    }

    @Test
    fun parsesNativePhoneActionToolCallWithoutText() {
        val decision = PhoneAgentProtocol.parse(
            PhoneAgentModelResponse(
                toolCalls = listOf(
                    ToolCall(
                        id = "call-1",
                        function = ToolCallFunction(
                            name = "phone_action",
                            arguments = """{"action":"tap","x":512,"y":233}"""
                        )
                    )
                )
            )
        )

        val execute = assertIs<PhoneAgentDecision.Execute>(decision)
        assertEquals("Tap", execute.command.action)
        assertEquals(512, execute.command.x)
        assertEquals(233, execute.command.y)
    }

    @Test
    fun parsesNativePhoneFinishToolCall() {
        val decision = PhoneAgentProtocol.parse(
            PhoneAgentModelResponse(
                toolCalls = listOf(
                    ToolCall(
                        id = "call-2",
                        function = ToolCallFunction(
                            name = "phone_finish",
                            arguments = """{"message":"已经完成"}"""
                        )
                    )
                )
            )
        )

        assertEquals("已经完成", assertIs<PhoneAgentDecision.Finish>(decision).message)
    }
}

package com.murong.agent.core.loop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortableConversationCodecTest {

    @Test
    fun encode_dropsAndroidOnlyPathsAndMapsRoles() {
        val session = PersistedSession(
            id = "android-session",
            title = "跨端任务",
            createdAt = 10,
            updatedAt = 20,
            providerId = "openai",
            modelName = "mobile-model",
            sessionGoal = "继续实现跨端",
            projectPath = "/storage/emulated/0/private-project",
            usageSummary = UsageSummarySnapshot(
                promptTokens = 12,
                completionTokens = 4,
                totalTokens = 16,
                promptCacheHitTokens = 3
            ),
            compressionSnapshot = PersistedCompressionSnapshot(
                id = "compression",
                summary = "已确认消息映射",
                sourceMessageCount = 4,
                sourceEndMessageId = 4,
                sourceEndMessageIndex = 3,
                createdAt = 30,
                active = true
            ),
            messages = listOf(
                PersistedMessage(id = 1, role = "system", content = "内部系统提示", timestamp = 11),
                PersistedMessage(
                    id = 2,
                    role = "user",
                    content = "开始",
                    imageAttachments = listOf(
                        PersistedMessageImageAttachment(
                            id = "image",
                            fileName = "private.png",
                            mimeType = "image/png",
                            localCachePath = "/data/user/0/private.png"
                        )
                    ),
                    timestamp = 12
                ),
                PersistedMessage(id = 3, role = "subagent", content = "子代理结果", reasoning = "内部推理", timestamp = 13),
                PersistedMessage(id = 4, role = "tool_exec", content = "ok", timestamp = 14)
            )
        )

        val encoded = PortableConversationCodec.encode(session)

        assertTrue(encoded.contains(PORTABLE_CONVERSATION_FORMAT))
        assertTrue(encoded.contains("\"sourcePlatform\":\"android\""))
        assertTrue(encoded.contains("\"kind\":\"subagent\""))
        assertFalse(encoded.contains("private-project"))
        assertTrue(encoded.contains("跨端图片附件"))
        assertTrue(encoded.contains("private.png"))
        assertFalse(encoded.contains("/data/user/0/private.png"))
        assertFalse(encoded.contains("内部系统提示"))
        assertFalse(encoded.contains("内部推理"))

        val imported = PortableConversationCodec.decodeImportedConversation(encoded)
        assertEquals("跨端任务", imported.titleHint)
        assertEquals("继续实现跨端", imported.sessionGoal)
        assertEquals(listOf("user", "subagent", "tool_exec"), imported.messages.map { it.role })
        assertTrue(imported.messages.first().content.contains("private.png"))
        assertEquals(listOf(12L, 13L, 14L), imported.messages.map { it.timestamp })
        assertEquals(16, imported.usageSummary.totalTokens)
        assertEquals(3, imported.compression?.sourceMessageCount)
    }

    @Test
    fun decode_acceptsWindowsDocumentAndRejectsUnknownFields() {
        val windowsDocument = """
            {
              "format":"murong-portable-session",
              "formatVersion":1,
              "exportedAtEpochMillis":100,
              "sourcePlatform":"windows",
              "session":{
                "title":"Windows 任务",
                "createdAtEpochMillis":10,
                "updatedAtEpochMillis":20,
                "providerId":"openai",
                "modelName":"desktop-model",
                "goal":"在手机继续",
                "messages":[
                  {"role":"user","content":"你好","createdAtEpochMillis":11},
                  {"role":"assistant","content":"已完成","createdAtEpochMillis":12},
                  {"role":"tool","content":"ok","createdAtEpochMillis":13,"toolName":"run_terminal"}
                ],
                "usage":{"inputTokens":8,"outputTokens":2,"totalTokens":10}
              }
            }
        """.trimIndent()

        val imported = ConversationImportParser.parse(windowsDocument, "ignored.json")
        assertEquals("Windows 任务", imported.titleHint)
        assertEquals("在手机继续", imported.sessionGoal)
        assertEquals(listOf("user", "assistant", "tool_exec"), imported.messages.map { it.role })
        assertEquals(10, imported.usageSummary.totalTokens)

        val unknown = windowsDocument.replace(
            "\"sourcePlatform\":\"windows\"",
            "\"sourcePlatform\":\"windows\",\"projectPath\":\"C:\\\\private\""
        )
        assertFailsWith<IllegalArgumentException> {
            ConversationImportParser.parse(unknown)
        }
    }

    @Test
    fun decode_acceptsMacLinuxAndGenericDesktopSources() {
        for (source in listOf("darwin", "linux", "desktop")) {
            val document = """
                {
                  "format":"murong-portable-session",
                  "formatVersion":1,
                  "exportedAtEpochMillis":100,
                  "sourcePlatform":"$source",
                  "session":{
                    "title":"跨平台桌面任务",
                    "createdAtEpochMillis":10,
                    "updatedAtEpochMillis":20,
                    "messages":[{"role":"user","content":"继续","createdAtEpochMillis":11}]
                  }
                }
            """.trimIndent()

            val imported = ConversationImportParser.parse(document, "$source.json")
            assertEquals("跨平台桌面任务", imported.titleHint)
            assertEquals("继续", imported.messages.single().content)
        }
    }

    @Test
    fun parser_allowsGoalOnlyPortableSessionButStillRejectsEmptyGenericJson() {
        val goalOnly = """
            {
              "format":"murong-portable-session",
              "formatVersion":1,
              "exportedAtEpochMillis":100,
              "sourcePlatform":"windows",
              "session":{
                "title":"只有目标",
                "createdAtEpochMillis":10,
                "updatedAtEpochMillis":20,
                "goal":"继续完成任务",
                "messages":[]
              }
            }
        """.trimIndent()

        val imported = ConversationImportParser.parse(goalOnly)
        assertTrue(imported.messages.isEmpty())
        assertEquals("继续完成任务", imported.sessionGoal)
        assertFailsWith<IllegalArgumentException> {
            ConversationImportParser.parse("{\"messages\":[]}")
        }
    }
}

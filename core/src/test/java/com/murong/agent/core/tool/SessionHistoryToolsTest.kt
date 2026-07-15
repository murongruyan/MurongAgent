package com.murong.agent.core.tool

import com.murong.agent.core.loop.ConversationStore
import com.murong.agent.core.loop.PersistedMessage
import com.murong.agent.core.loop.PersistedSession
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionHistoryToolsTest {

    @Test
    fun execute_prefersTitleMatchBeforeGoalAndMessageContent() = runBlocking {
        val tempDir = Files.createTempDirectory("session-history-tool-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            assertTrue(
                store.saveSession(
                    PersistedSession(
                        id = "session-a",
                        title = "修复登录闪退",
                        createdAt = 100L,
                        updatedAt = 200L,
                        providerId = "provider",
                        modelName = "model",
                        sessionGoal = "定位登录闪退并修复",
                        projectPath = "C:/workspace/app",
                        messages = listOf(
                            PersistedMessage(
                                id = 1L,
                                role = "user",
                                content = "登录后会闪退，怀疑是 token 解析问题"
                            )
                        )
                    )
                )
            )
            assertTrue(
                store.saveSession(
                    PersistedSession(
                        id = "session-b",
                        title = "数据库迁移排查",
                        createdAt = 100L,
                        updatedAt = 300L,
                        providerId = "provider",
                        modelName = "model",
                        sessionGoal = "确认数据库迁移是否继续",
                        projectPath = "C:/workspace/app",
                        messages = listOf(
                            PersistedMessage(
                                id = 1L,
                                role = "assistant",
                                content = "建议先跑数据库迁移前的 smoke test"
                            )
                        )
                    )
                )
            )

            val tool = SessionHistorySearchTool(
                sessionsProvider = store::listSessions,
                sessionLoader = store::loadSession,
                currentSessionIdProvider = { null },
                currentProjectPathProvider = { "C:/workspace/app" }
            )

            val result = tool.execute("""{"query":"迁移","limit":2}""")

            assertTrue(result.contains("历史会话命中 1 条"))
            assertTrue(result.contains("session-b"))
            assertTrue(result.contains("matched_field: 标题"))
            assertTrue(result.contains("anchor_message_id: 1"))
            assertTrue(result.contains("message_reference: session-b#1"))
            assertTrue(result.contains("数据库迁移排查"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun execute_matchesMessageContentWhenTitleAndGoalDoNotHit() = runBlocking {
        val tempDir = Files.createTempDirectory("session-history-tool-message-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            assertTrue(
                store.saveSession(
                    PersistedSession(
                        id = "session-message",
                        title = "数据库排查",
                        createdAt = 100L,
                        updatedAt = 200L,
                        providerId = "provider",
                        modelName = "model",
                        sessionGoal = "确认迁移前后的兼容性",
                        projectPath = "C:/workspace/app",
                        messages = listOf(
                            PersistedMessage(
                                id = 1L,
                                role = "assistant",
                                content = "建议先跑数据库迁移前的 smoke test"
                            )
                        )
                    )
                )
            )

            val tool = SessionHistorySearchTool(
                sessionsProvider = store::listSessions,
                sessionLoader = store::loadSession,
                currentSessionIdProvider = { null },
                currentProjectPathProvider = { "C:/workspace/app" }
            )

            val result = tool.execute("""{"query":"smoke","limit":2}""")

            assertTrue(result.contains("历史会话命中 1 条"))
            assertTrue(result.contains("session-message"))
            assertTrue(result.contains("matched_field: 消息正文"))
            assertTrue(result.contains("anchor_message_id: 1"))
            assertTrue(result.contains("message_reference: session-message#1"))
            assertTrue(result.contains("assistant: 建议先跑数据库迁移前的 smoke test"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun execute_filtersToCurrentProjectAndExcludesCurrentSessionByDefault() = runBlocking {
        val tempDir = Files.createTempDirectory("session-history-project-filter-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            assertTrue(
                store.saveSession(
                    PersistedSession(
                        id = "current-session",
                        title = "当前会话",
                        createdAt = 100L,
                        updatedAt = 400L,
                        providerId = "provider",
                        modelName = "model",
                        sessionGoal = "处理当前问题",
                        projectPath = "C:/workspace/app",
                        messages = listOf(
                            PersistedMessage(
                                id = 1L,
                                role = "user",
                                content = "当前会话内容"
                            )
                        )
                    )
                )
            )
            assertTrue(
                store.saveSession(
                    PersistedSession(
                        id = "same-project",
                        title = "同项目旧会话",
                        createdAt = 100L,
                        updatedAt = 300L,
                        providerId = "provider",
                        modelName = "model",
                        sessionGoal = "同项目里的旧任务",
                        projectPath = "C:/workspace/app/",
                        messages = listOf(
                            PersistedMessage(
                                id = 1L,
                                role = "assistant",
                                content = "同项目会话摘要"
                            )
                        )
                    )
                )
            )
            assertTrue(
                store.saveSession(
                    PersistedSession(
                        id = "other-project",
                        title = "其他项目会话",
                        createdAt = 100L,
                        updatedAt = 200L,
                        providerId = "provider",
                        modelName = "model",
                        sessionGoal = "其他项目任务",
                        projectPath = "D:/workspace/other",
                        messages = listOf(
                            PersistedMessage(
                                id = 1L,
                                role = "assistant",
                                content = "其他项目内容"
                            )
                        )
                    )
                )
            )

            val tool = SessionHistorySearchTool(
                sessionsProvider = store::listSessions,
                sessionLoader = store::loadSession,
                currentSessionIdProvider = { "current-session" },
                currentProjectPathProvider = { "C:\\workspace\\app" }
            )

            val result = tool.execute("""{"query":"旧","project_only":true,"limit":5}""")

            assertTrue(result.contains("历史会话命中 1 条"))
            assertTrue(result.contains("same-project"))
            assertTrue(!result.contains("current-session"))
            assertTrue(!result.contains("other-project"))
            assertTrue(result.contains("范围：当前项目"))
            assertTrue(result.contains("message_reference: same-project#1"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun execute_readsStructuredExcerptBySessionId() = runBlocking {
        val tempDir = Files.createTempDirectory("session-history-excerpt-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            assertTrue(
                store.saveSession(
                    PersistedSession(
                        id = "session-excerpt",
                        title = "发布前联调",
                        createdAt = 100L,
                        updatedAt = 500L,
                        providerId = "provider",
                        modelName = "model",
                        sessionGoal = "整理发布前联调结论",
                        projectPath = "C:/workspace/app",
                        messages = listOf(
                            PersistedMessage(
                                id = 1L,
                                role = "user",
                                content = "先确认发布前 checklist"
                            ),
                            PersistedMessage(
                                id = 2L,
                                role = "assistant",
                                content = "已整理 checklist，接下来补接口联调结果"
                            ),
                            PersistedMessage(
                                id = 3L,
                                role = "user",
                                content = "把联调结论也补进来"
                            )
                        )
                    )
                )
            )

            val tool = SessionHistorySearchTool(
                sessionsProvider = store::listSessions,
                sessionLoader = store::loadSession,
                currentSessionIdProvider = { null },
                currentProjectPathProvider = { "C:/workspace/app" }
            )

            val result = tool.execute("""{"session_id":"session-excerpt","excerpt_message_limit":2}""")

            assertTrue(result.contains("历史会话摘录"))
            assertTrue(result.contains("session_id: session-excerpt"))
            assertTrue(result.contains("goal: 整理发布前联调结论"))
            assertTrue(result.contains("excerpt_strategy: 最近消息"))
            assertTrue(result.contains("excerpt_messages: 2/3"))
            assertTrue(result.contains("message_range: 2-3/3"))
            assertTrue(result.contains("ref=session-excerpt#2"))
            assertTrue(result.contains("ref=session-excerpt#3"))
            assertTrue(result.contains("assistant: 已整理 checklist，接下来补接口联调结果"))
            assertTrue(result.contains("user: 把联调结论也补进来"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun execute_readsQueryFocusedExcerptWindowBySessionId() = runBlocking {
        val tempDir = Files.createTempDirectory("session-history-focused-excerpt-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            assertTrue(
                store.saveSession(
                    PersistedSession(
                        id = "session-window",
                        title = "数据库演练复盘",
                        createdAt = 100L,
                        updatedAt = 600L,
                        providerId = "provider",
                        modelName = "model",
                        sessionGoal = "复盘数据库发布演练",
                        projectPath = "C:/workspace/app",
                        messages = listOf(
                            PersistedMessage(
                                id = 1L,
                                role = "user",
                                content = "先看发布准备项"
                            ),
                            PersistedMessage(
                                id = 2L,
                                role = "assistant",
                                content = "发布前 checklist 已确认"
                            ),
                            PersistedMessage(
                                id = 3L,
                                role = "user",
                                content = "重点看 smoke test 是否覆盖迁移"
                            ),
                            PersistedMessage(
                                id = 4L,
                                role = "assistant",
                                content = "smoke test 需要补一条迁移后登录校验"
                            ),
                            PersistedMessage(
                                id = 5L,
                                role = "user",
                                content = "最后整理复盘结论"
                            )
                        )
                    )
                )
            )

            val tool = SessionHistorySearchTool(
                sessionsProvider = store::listSessions,
                sessionLoader = store::loadSession,
                currentSessionIdProvider = { null },
                currentProjectPathProvider = { "C:/workspace/app" }
            )

            val result =
                tool.execute("""{"session_id":"session-window","query":"smoke","excerpt_message_limit":3}""")

            assertTrue(result.contains("matched_field: 消息正文"))
            assertTrue(result.contains("focus_snippet: assistant: smoke test 需要补一条迁移后登录校验"))
            assertTrue(result.contains("excerpt_strategy: 关键词命中附近"))
            assertTrue(result.contains("excerpt_messages: 3/5"))
            assertTrue(result.contains("message_range: 2-4/5"))
            assertTrue(result.contains("anchor_message_id: 3"))
            assertTrue(result.contains("ref=session-window#2"))
            assertTrue(result.contains("ref=session-window#3"))
            assertTrue(result.contains("ref=session-window#4"))
            assertTrue(result.contains("assistant: 发布前 checklist 已确认"))
            assertTrue(result.contains("user: 重点看 smoke test 是否覆盖迁移"))
            assertTrue(result.contains("assistant: smoke test 需要补一条迁移后登录校验"))
            assertFalse(result.contains("最后整理复盘结论"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun execute_readsExcerptAroundAnchorMessageId() = runBlocking {
        val tempDir = Files.createTempDirectory("session-history-anchor-excerpt-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            assertTrue(
                store.saveSession(
                    PersistedSession(
                        id = "session-anchor",
                        title = "发布回滚复盘",
                        createdAt = 100L,
                        updatedAt = 700L,
                        providerId = "provider",
                        modelName = "model",
                        sessionGoal = "回看发布回滚前后的结论",
                        projectPath = "C:/workspace/app",
                        messages = listOf(
                            PersistedMessage(
                                id = 10L,
                                role = "user",
                                content = "先看报警"
                            ),
                            PersistedMessage(
                                id = 11L,
                                role = "assistant",
                                content = "报警来自登录接口超时"
                            ),
                            PersistedMessage(
                                id = 12L,
                                role = "user",
                                content = "再看回滚决定"
                            ),
                            PersistedMessage(
                                id = 13L,
                                role = "assistant",
                                content = "决定先回滚并保留日志"
                            ),
                            PersistedMessage(
                                id = 14L,
                                role = "user",
                                content = "最后整理行动项"
                            )
                        )
                    )
                )
            )

            val tool = SessionHistorySearchTool(
                sessionsProvider = store::listSessions,
                sessionLoader = store::loadSession,
                currentSessionIdProvider = { null },
                currentProjectPathProvider = { "C:/workspace/app" }
            )

            val result =
                tool.execute("""{"session_id":"session-anchor","anchor_message_id":13,"excerpt_message_limit":3}""")

            assertTrue(result.contains("excerpt_strategy: 指定消息附近"))
            assertTrue(result.contains("anchor_message_id: 13"))
            assertTrue(result.contains("message_range: 3-5/5"))
            assertTrue(result.contains("ref=session-anchor#12"))
            assertTrue(result.contains("ref=session-anchor#13"))
            assertTrue(result.contains("ref=session-anchor#14"))
            assertFalse(result.contains("报警来自登录接口超时"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun execute_readsExcerptFromMessageReference() = runBlocking {
        val tempDir = Files.createTempDirectory("session-history-message-reference-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            assertTrue(
                store.saveSession(
                    PersistedSession(
                        id = "session-ref",
                        title = "登录修复复盘",
                        createdAt = 100L,
                        updatedAt = 800L,
                        providerId = "provider",
                        modelName = "model",
                        sessionGoal = "回看登录修复前后的结论",
                        projectPath = "C:/workspace/app",
                        messages = listOf(
                            PersistedMessage(
                                id = 20L,
                                role = "user",
                                content = "先看复现路径"
                            ),
                            PersistedMessage(
                                id = 21L,
                                role = "assistant",
                                content = "问题出在 token 续期判断"
                            ),
                            PersistedMessage(
                                id = 22L,
                                role = "user",
                                content = "继续看修复方案"
                            ),
                            PersistedMessage(
                                id = 23L,
                                role = "assistant",
                                content = "已经改成刷新前校验并补单测"
                            )
                        )
                    )
                )
            )

            val tool = SessionHistorySearchTool(
                sessionsProvider = store::listSessions,
                sessionLoader = store::loadSession,
                currentSessionIdProvider = { null },
                currentProjectPathProvider = { "C:/workspace/app" }
            )

            val result =
                tool.execute("""{"message_reference":"session-ref#22","excerpt_message_limit":3}""")

            assertTrue(result.contains("历史会话摘录"))
            assertTrue(result.contains("session_id: session-ref"))
            assertTrue(result.contains("message_reference: session-ref#22"))
            assertTrue(result.contains("excerpt_strategy: 指定消息附近"))
            assertTrue(result.contains("anchor_message_id: 22"))
            assertTrue(result.contains("resolved_message_reference: session-ref#22"))
            assertTrue(result.contains("message_range: 2-4/4"))
            assertTrue(result.contains("ref=session-ref#21"))
            assertTrue(result.contains("ref=session-ref#22"))
            assertTrue(result.contains("ref=session-ref#23"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun execute_readsCrossSessionExcerptsFromMessageReferences() = runBlocking {
        val tempDir = Files.createTempDirectory("session-history-multi-reference-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            assertTrue(
                store.saveSession(
                    PersistedSession(
                        id = "session-alpha",
                        title = "登录修复复盘",
                        createdAt = 100L,
                        updatedAt = 900L,
                        providerId = "provider",
                        modelName = "model",
                        sessionGoal = "回看登录修复结论",
                        projectPath = "C:/workspace/app",
                        messages = listOf(
                            PersistedMessage(
                                id = 30L,
                                role = "user",
                                content = "先看复现路径"
                            ),
                            PersistedMessage(
                                id = 31L,
                                role = "assistant",
                                content = "问题是 token 续期判断缺失"
                            ),
                            PersistedMessage(
                                id = 32L,
                                role = "user",
                                content = "继续确认修复"
                            )
                        )
                    )
                )
            )
            assertTrue(
                store.saveSession(
                    PersistedSession(
                        id = "session-beta",
                        title = "发布联调复盘",
                        createdAt = 100L,
                        updatedAt = 950L,
                        providerId = "provider",
                        modelName = "model",
                        sessionGoal = "回看发布 smoke test 结论",
                        projectPath = "C:/workspace/app",
                        messages = listOf(
                            PersistedMessage(
                                id = 40L,
                                role = "user",
                                content = "先确认 checklist"
                            ),
                            PersistedMessage(
                                id = 41L,
                                role = "assistant",
                                content = "smoke test 需要补登录校验"
                            ),
                            PersistedMessage(
                                id = 42L,
                                role = "user",
                                content = "最后整理联调结论"
                            )
                        )
                    )
                )
            )

            val tool = SessionHistorySearchTool(
                sessionsProvider = store::listSessions,
                sessionLoader = store::loadSession,
                currentSessionIdProvider = { null },
                currentProjectPathProvider = { "C:/workspace/app" }
            )

            val result = tool.execute(
                """{"message_references":["session-alpha#31","session-beta#41"],"excerpt_message_limit":2}"""
            )

            assertTrue(result.contains("跨会话历史摘录命中 2/2 条"))
            assertTrue(result.contains("message_references: session-alpha#31, session-beta#41"))
            assertTrue(result.contains("message_reference: session-alpha#31"))
            assertTrue(result.contains("message_reference: session-beta#41"))
            assertTrue(result.contains("resolved_message_reference: session-alpha#31"))
            assertTrue(result.contains("resolved_message_reference: session-beta#41"))
            assertTrue(result.contains("ref=session-alpha#31"))
            assertTrue(result.contains("ref=session-beta#41"))
            assertTrue(result.contains("token 续期判断缺失"))
            assertTrue(result.contains("smoke test 需要补登录校验"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun parseSessionHistoryToolRequest_normalizesMultiReferenceRequest() {
        val resolution = parseSessionHistoryToolRequest(
            """{"message_references":[" session-a#1 ","session-a#1","session-b#2"],"query":"登录","limit":9,"excerpt_message_limit":4,"project_only":true}"""
        )

        val request = (resolution as SessionHistoryToolRequestResolution.Valid).request
        assertEquals(listOf("session-a#1", "session-b#2"), request.messageReferences)
        assertEquals("登录", request.query)
        assertEquals(9, request.limit)
        assertEquals(4, request.excerptMessageLimit)
        assertTrue(request.projectOnly)
        assertFalse(request.includeCurrentSession)
    }

    @Test
    fun executeWithStructuredResult_returnsTypedMultiExcerptResponse() = runBlocking {
        val tempDir = Files.createTempDirectory("session-history-structured-multi-reference-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            assertTrue(
                store.saveSession(
                    PersistedSession(
                        id = "session-structured-a",
                        title = "登录修复记录",
                        createdAt = 100L,
                        updatedAt = 1000L,
                        providerId = "provider",
                        modelName = "model",
                        sessionGoal = "回看登录修复路径",
                        projectPath = "C:/workspace/app",
                        messages = listOf(
                            PersistedMessage(
                                id = 50L,
                                role = "assistant",
                                content = "修复点是 token 刷新前校验"
                            )
                        )
                    )
                )
            )
            assertTrue(
                store.saveSession(
                    PersistedSession(
                        id = "session-structured-b",
                        title = "发布演练记录",
                        createdAt = 100L,
                        updatedAt = 1100L,
                        providerId = "provider",
                        modelName = "model",
                        sessionGoal = "回看 smoke test 结论",
                        projectPath = "C:/workspace/app",
                        messages = listOf(
                            PersistedMessage(
                                id = 60L,
                                role = "assistant",
                                content = "smoke test 还要补登录回归"
                            )
                        )
                    )
                )
            )
            val tool = SessionHistorySearchTool(
                sessionsProvider = store::listSessions,
                sessionLoader = store::loadSession,
                currentSessionIdProvider = { null },
                currentProjectPathProvider = { "C:/workspace/app" }
            )

            val response = tool.executeWithStructuredResult(
                """{"message_references":["session-structured-a#50","session-structured-b#60"]}"""
            )

            assertTrue(response is SessionHistoryToolResponse.MultiExcerpt)
            assertEquals(
                listOf("session-structured-a#50", "session-structured-b#60"),
                response.requestedReferences
            )
            assertEquals(2, response.excerpts.size)
            assertTrue(response.output.contains("跨会话历史摘录命中 2/2 条"))
            assertTrue(response.output.contains("resolved_message_reference: session-structured-a#50"))
            assertTrue(response.output.contains("resolved_message_reference: session-structured-b#60"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun executeWithStructuredResult_returnsTypedSearchResponse() = runBlocking {
        val tempDir = Files.createTempDirectory("session-history-structured-search-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            assertTrue(
                store.saveSession(
                    PersistedSession(
                        id = "session-search",
                        title = "数据库迁移排查",
                        createdAt = 100L,
                        updatedAt = 1200L,
                        providerId = "provider",
                        modelName = "model",
                        sessionGoal = "确认迁移步骤",
                        projectPath = "C:/workspace/app",
                        messages = listOf(
                            PersistedMessage(
                                id = 70L,
                                role = "assistant",
                                content = "先做 smoke test"
                            )
                        )
                    )
                )
            )
            val tool = SessionHistorySearchTool(
                sessionsProvider = store::listSessions,
                sessionLoader = store::loadSession,
                currentSessionIdProvider = { null },
                currentProjectPathProvider = { "C:/workspace/app" }
            )

            val response = tool.executeWithStructuredResult("""{"query":"迁移"}""")

            assertTrue(response is SessionHistoryToolResponse.Search)
            assertEquals(1, response.matches.size)
            assertEquals("session-search", response.matches.single().summary.id)
            assertTrue(response.output.contains("message_reference: session-search#70"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun executeWithResult_exposesStructuredPayloadForCrossSessionConsumption() = runBlocking {
        val tempDir = Files.createTempDirectory("session-history-structured-payload-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            assertTrue(
                store.saveSession(
                    PersistedSession(
                        id = "session-payload",
                        title = "登录修复复盘",
                        createdAt = 100L,
                        updatedAt = 1300L,
                        providerId = "provider",
                        modelName = "model",
                        sessionGoal = "回看登录修复结论",
                        projectPath = "C:/workspace/app",
                        messages = listOf(
                            PersistedMessage(
                                id = 80L,
                                role = "assistant",
                                content = "token 刷新逻辑已修复"
                            )
                        )
                    )
                )
            )
            val tool = SessionHistorySearchTool(
                sessionsProvider = store::listSessions,
                sessionLoader = store::loadSession,
                currentSessionIdProvider = { null },
                currentProjectPathProvider = { "C:/workspace/app" }
            )

            val result = tool.executeWithResult("""{"query":"登录"}""")

            assertEquals(listOf("session-payload"), result.structuredPayload?.sessionHistory?.sessionIds)
            assertEquals(
                listOf("session-payload#80"),
                result.structuredPayload?.sessionHistory?.messageReferences
            )
            assertEquals(listOf(80L), result.structuredPayload?.sessionHistory?.anchorMessageIds)
            assertEquals(
                listOf("登录修复复盘"),
                result.structuredPayload?.sessionHistory?.snippets
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun executeWithResult_returnsStructuredPayloadForExcerptWindow() = runBlocking {
        val tempDir = Files.createTempDirectory("session-history-structured-excerpt-payload-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            assertTrue(
                store.saveSession(
                    PersistedSession(
                        id = "session-excerpt-payload",
                        title = "登录复盘",
                        createdAt = 100L,
                        updatedAt = 900L,
                        providerId = "provider",
                        modelName = "model",
                        sessionGoal = "整理登录复盘结论",
                        projectPath = "C:/workspace/app",
                        messages = listOf(
                            PersistedMessage(id = 21L, role = "user", content = "登录后偶发掉线"),
                            PersistedMessage(
                                id = 22L,
                                role = "assistant",
                                content = "token 续期判断缺失，需要补刷新逻辑"
                            ),
                            PersistedMessage(id = 23L, role = "user", content = "顺手补 smoke test")
                        )
                    )
                )
            )
            val tool = SessionHistorySearchTool(
                sessionsProvider = store::listSessions,
                sessionLoader = store::loadSession,
                currentSessionIdProvider = { null },
                currentProjectPathProvider = { "C:/workspace/app" }
            )

            val result = tool.executeWithResult(
                """{"message_reference":"session-excerpt-payload#22","query":"续期","excerpt_message_limit":2}"""
            )

            assertEquals(
                listOf("assistant: token 续期判断缺失，需要补刷新逻辑"),
                result.structuredPayload?.sessionHistory?.snippets
            )
            assertEquals(
                listOf("2-3/3（指定消息附近）"),
                result.structuredPayload?.sessionHistory?.excerptWindows
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun execute_reportsInvalidMessageReferences() = runBlocking {
        val tempDir = Files.createTempDirectory("session-history-invalid-multi-reference-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val tool = SessionHistorySearchTool(
                sessionsProvider = store::listSessions,
                sessionLoader = store::loadSession,
                currentSessionIdProvider = { null },
                currentProjectPathProvider = { "C:/workspace/app" }
            )

            val result = tool.execute(
                """{"message_references":["session-a#1","bad-reference","still-bad#x"]}"""
            )

            assertEquals(
                "未找到可读取的历史消息引用，message_references: bad-reference, still-bad#x。",
                result
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun executeWithStructuredResult_reportsTypedInvalidRequest() = runBlocking {
        val tempDir = Files.createTempDirectory("session-history-structured-invalid-reference-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val tool = SessionHistorySearchTool(
                sessionsProvider = store::listSessions,
                sessionLoader = store::loadSession,
                currentSessionIdProvider = { null },
                currentProjectPathProvider = { "C:/workspace/app" }
            )

            val response = tool.executeWithStructuredResult(
                """{"message_references":["bad-reference"]}"""
            )

            assertTrue(response is SessionHistoryToolResponse.InvalidRequest)
            assertEquals(
                "未找到可读取的历史消息引用，message_references: bad-reference。",
                response.output
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun execute_reportsMissingExcerptWhenSessionIdIsUnknown() = runBlocking {
        val tempDir = Files.createTempDirectory("session-history-missing-excerpt-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val tool = SessionHistorySearchTool(
                sessionsProvider = store::listSessions,
                sessionLoader = store::loadSession,
                currentSessionIdProvider = { null },
                currentProjectPathProvider = { "C:/workspace/app" }
            )

            val result = tool.execute("""{"session_id":"missing-session","query":"登录"}""")

            assertEquals(
                "未找到可读取的历史会话摘录，session_id: missing-session，关键词：登录。",
                result
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun execute_reportsInvalidMessageReference() = runBlocking {
        val tempDir = Files.createTempDirectory("session-history-invalid-reference-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val tool = SessionHistorySearchTool(
                sessionsProvider = store::listSessions,
                sessionLoader = store::loadSession,
                currentSessionIdProvider = { null },
                currentProjectPathProvider = { "C:/workspace/app" }
            )

            val result = tool.execute("""{"message_reference":"bad-reference"}""")

            assertEquals(
                "未找到可读取的历史消息引用，message_reference: bad-reference。",
                result
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun formatSessionHistorySearchResult_reportsEmptyState() {
        val result = formatSessionHistorySearchResult(
            matches = emptyList(),
            query = "登录",
            projectOnly = true
        )

        assertEquals("未找到匹配的历史会话，关键词：登录，范围：当前项目。", result)
    }
}

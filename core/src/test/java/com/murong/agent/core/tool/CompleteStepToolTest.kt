package com.murong.agent.core.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompleteStepToolTest {

    @Test
    fun executeWithContext_rejectsWhenEvidenceCannotBeMatched() = kotlinx.coroutines.runBlocking {
        val tool = CompleteStepTool()

        val result = tool.executeWithContext(
            args = """
            {
              "step": "运行测试",
              "result": "测试完成",
              "evidence": [
                {
                  "summary": "应该有 shell 收据",
                  "toolName": "shell",
                  "command": "./gradlew test"
                }
              ]
            }
            """.trimIndent(),
            runtimeContext = ToolRuntimeContext()
        )

        assertTrue(result.output.startsWith("Error:"), "没有真实收据时应拒绝签收")
        assertTrue(result.output.contains("请先执行实际工具"))
    }

    @Test
    fun executeWithContext_matchesWorkflowStepAndInvokesCompletionCallback() = kotlinx.coroutines.runBlocking {
        val callbacks = mutableListOf<WorkflowStepCompletion>()
        val tool = CompleteStepTool(
            workflowSnapshotProvider = {
                WorkflowExecutionSnapshot(
                    steps = listOf("先运行测试", "再汇总结果"),
                    currentStepIndex = 0,
                    status = "EXECUTING"
                )
            },
            onWorkflowStepCompleted = { callbacks += it }
        )

        val result = tool.executeWithContext(
            args = """
            {
              "step": "运行测试",
              "result": "测试已通过",
              "evidence": [
                {
                  "summary": "已运行 gradlew test",
                  "toolName": "shell",
                  "command": "./gradlew test"
                }
              ]
            }
            """.trimIndent(),
            runtimeContext = ToolRuntimeContext(
                currentTurnToolReceipts = listOf(
                    ToolExecutionReceipt(
                        toolName = "shell",
                        args = """{"command":"./gradlew test"}""",
                        result = "BUILD SUCCESSFUL",
                        timestamp = 1234L
                    )
                )
            )
        )

        assertTrue(result.output.contains("Step signed off."))
        assertTrue(result.output.contains("workflow_step=1/2"))
        assertTrue(result.output.contains("matched_evidence=1/1"))
        assertTrue(result.output.contains("matched_tools=shell"))
        assertTrue(result.output.contains("signoff_timestamp=1234"))
        val signOffReceipt = result.stepSignOffReceipt
        assertNotNull(signOffReceipt)
        assertEquals("运行测试", signOffReceipt.reportedStep)
        assertEquals("测试已通过", signOffReceipt.resultSummary)
        assertEquals(listOf("shell"), signOffReceipt.matchedToolNames)
        assertEquals(emptyList<String>(), signOffReceipt.matchedSessionHistorySessionIds)
        assertEquals(emptyList<String>(), signOffReceipt.matchedSessionHistoryMessageReferences)
        assertEquals(1234L, signOffReceipt.signOffTimestamp)
        assertEquals(0, signOffReceipt.workflowStepIndex)
        assertEquals("先运行测试", signOffReceipt.workflowStep)
        assertEquals(2, signOffReceipt.workflowTotalSteps)
        assertEquals(1, callbacks.size)
        assertEquals("先运行测试", callbacks.single().matchedStep)
        assertEquals(1, callbacks.single().matchedEvidenceCount)
        assertEquals(1, callbacks.single().totalEvidenceCount)
        assertEquals(listOf("shell"), callbacks.single().matchedToolNames)
        assertEquals(1234L, callbacks.single().signedOffAt)
    }

    @Test
    fun executeWithContext_prefersLatestReceiptTimestampForSignOff() = kotlinx.coroutines.runBlocking {
        val callbacks = mutableListOf<WorkflowStepCompletion>()
        val tool = CompleteStepTool(
            onWorkflowStepCompleted = { callbacks += it },
            workflowSnapshotProvider = {
                WorkflowExecutionSnapshot(
                    steps = listOf("运行测试"),
                    currentStepIndex = 0,
                    status = "EXECUTING"
                )
            }
        )

        val result = tool.executeWithContext(
            args = """
            {
              "step": "运行测试",
              "result": "测试已通过",
              "evidence": [
                {
                  "summary": "已运行 gradlew test",
                  "toolName": "shell",
                  "command": "./gradlew test"
                }
              ]
            }
            """.trimIndent(),
            runtimeContext = ToolRuntimeContext(
                currentTurnToolReceipts = listOf(
                    ToolExecutionReceipt(
                        toolName = "shell",
                        args = """{"command":"./gradlew test"}""",
                        result = "BUILD SUCCESSFUL",
                        timestamp = 100L
                    ),
                    ToolExecutionReceipt(
                        toolName = "shell",
                        args = """{"command":"./gradlew test --rerun-tasks"}""",
                        result = "BUILD SUCCESSFUL",
                        timestamp = 200L
                    )
                )
            )
        )

        assertTrue(result.output.contains("signoff_timestamp=200"))
        val completion = callbacks.singleOrNull()
        assertNotNull(completion)
        assertEquals(200L, completion.signedOffAt)
    }

    @Test
    fun executeWithContext_matchesSessionHistoryMessageReferenceEvidence() = kotlinx.coroutines.runBlocking {
        val tool = CompleteStepTool()

        val result = tool.executeWithContext(
            args = """
            {
              "step": "复用历史登录修复",
              "result": "已确认参考了历史登录案例",
              "evidence": [
                {
                  "summary": "已命中历史登录记录",
                  "toolName": "session_history_search",
                  "message_reference": "session-login#21"
                }
              ]
            }
            """.trimIndent(),
            runtimeContext = ToolRuntimeContext(
                currentTurnToolReceipts = listOf(
                    ToolExecutionReceipt(
                        toolName = "session_history_search",
                        args = """{"query":"登录"}""",
                        result = "历史会话命中 1 条",
                        structuredPayload = ToolStructuredPayload(
                            sessionHistory = SessionHistoryToolPayload(
                                kind = "search",
                                query = "登录",
                                sessionIds = listOf("session-login"),
                                messageReferences = listOf("session-login#21"),
                                anchorMessageIds = listOf(21L),
                                matchedFields = listOf("消息正文")
                            )
                        ),
                        timestamp = 210L
                    )
                )
            )
        )

        assertTrue(result.output.contains("Step signed off."))
        assertTrue(result.output.contains("matched_tools=session_history_search"))
        assertTrue(result.output.contains("signoff_timestamp=210"))
        val signOffReceipt = result.stepSignOffReceipt
        assertNotNull(signOffReceipt)
        assertEquals(listOf("session-login"), signOffReceipt.matchedSessionHistorySessionIds)
        assertEquals(
            listOf("session-login#21"),
            signOffReceipt.matchedSessionHistoryMessageReferences
        )
    }

    @Test
    fun executeWithContext_matchesSessionHistorySessionIdFromRecentReceipts() = kotlinx.coroutines.runBlocking {
        val tool = CompleteStepTool(
            recentToolReceiptsProvider = {
                listOf(
                    ToolExecutionReceipt(
                        toolName = "session_history_search",
                        args = """{"query":"支付"}""",
                        result = "历史会话命中 1 条",
                        structuredPayload = ToolStructuredPayload(
                            sessionHistory = SessionHistoryToolPayload(
                                kind = "search",
                                query = "支付",
                                sessionIds = listOf("session-payment"),
                                messageReferences = listOf("session-payment#55"),
                                anchorMessageIds = listOf(55L),
                                matchedFields = listOf("目标摘要")
                            )
                        ),
                        timestamp = 320L
                    )
                )
            }
        )

        val result = tool.executeWithContext(
            args = """
            {
              "step": "复用历史支付排查",
              "result": "已确认目标会话可复用",
              "evidence": [
                {
                  "summary": "已找到历史支付会话",
                  "session_id": "session-payment"
                }
              ]
            }
            """.trimIndent(),
            runtimeContext = ToolRuntimeContext()
        )

        assertTrue(result.output.contains("Step signed off."))
        assertTrue(result.output.contains("matched_tools=session_history_search"))
        assertTrue(result.output.contains("signoff_timestamp=320"))
    }

    @Test
    fun executeWithContext_requiresDistinctReceiptForEachEvidenceItem() = kotlinx.coroutines.runBlocking {
        val tool = CompleteStepTool()

        val result = tool.executeWithContext(
            args = """
            {
              "step": "完成两次验证",
              "result": "两次验证都已完成",
              "evidence": [
                {
                  "summary": "第一次运行 gradlew test",
                  "toolName": "shell",
                  "command": "./gradlew test"
                },
                {
                  "summary": "第二次运行 gradlew test",
                  "toolName": "shell",
                  "command": "./gradlew test"
                }
              ]
            }
            """.trimIndent(),
            runtimeContext = ToolRuntimeContext(
                currentTurnToolReceipts = listOf(
                    ToolExecutionReceipt(
                        toolName = "shell",
                        args = """{"command":"./gradlew test"}""",
                        result = "BUILD SUCCESSFUL",
                        timestamp = 100L
                    )
                )
            )
        )

        assertTrue(result.output.startsWith("Error:"))
        assertTrue(result.output.contains("第二次运行 gradlew test"))
    }

    @Test
    fun executeWithContext_reportsAllMatchedToolNames() = kotlinx.coroutines.runBlocking {
        val tool = CompleteStepTool()

        val result = tool.executeWithContext(
            args = """
            {
              "step": "完成修改与验证",
              "result": "修改和验证都已完成",
              "evidence": [
                {
                  "summary": "已经修改文件",
                  "toolName": "code_edit",
                  "path": "src/Main.kt"
                },
                {
                  "summary": "已经运行测试",
                  "toolName": "shell",
                  "command": "./gradlew test"
                }
              ]
            }
            """.trimIndent(),
            runtimeContext = ToolRuntimeContext(
                currentTurnToolReceipts = listOf(
                    ToolExecutionReceipt(
                        toolName = "code_edit",
                        args = """{"path":"src/Main.kt"}""",
                        result = "edit succeeded",
                        timestamp = 100L
                    ),
                    ToolExecutionReceipt(
                        toolName = "shell",
                        args = """{"command":"./gradlew test"}""",
                        result = "BUILD SUCCESSFUL",
                        timestamp = 200L
                    )
                )
            )
        )

        assertTrue(result.output.contains("matched_tools=code_edit,shell"))
    }

    @Test
    fun executeWithContext_prefersExactWorkflowStepMatchOverBroaderCandidate() = kotlinx.coroutines.runBlocking {
        val callbacks = mutableListOf<WorkflowStepCompletion>()
        val tool = CompleteStepTool(
            workflowSnapshotProvider = {
                WorkflowExecutionSnapshot(
                    steps = listOf("验证修复工作流", "修复工作流"),
                    currentStepIndex = 0,
                    status = "EXECUTING"
                )
            },
            onWorkflowStepCompleted = { callbacks += it }
        )

        val result = tool.executeWithContext(
            args = """
            {
              "step": "修复工作流",
              "result": "工作流已修复",
              "evidence": [
                {
                  "summary": "已经修改 workflow 文件",
                  "toolName": "code_edit",
                  "path": ".github/workflows/release.yml"
                }
              ]
            }
            """.trimIndent(),
            runtimeContext = ToolRuntimeContext(
                currentTurnToolReceipts = listOf(
                    ToolExecutionReceipt(
                        toolName = "code_edit",
                        args = """{"path":".github/workflows/release.yml"}""",
                        result = "edit succeeded",
                        timestamp = 100L
                    )
                )
            )
        )

        assertTrue(result.output.contains("workflow_step=2/2 -> 修复工作流"))
        assertEquals("修复工作流", callbacks.single().matchedStep)
        assertEquals(1, callbacks.single().matchedStepIndex)
    }

    @Test
    fun executeWithContext_skipsWorkflowMatchWhenFuzzyCandidatesAreAmbiguous() = kotlinx.coroutines.runBlocking {
        val callbacks = mutableListOf<WorkflowStepCompletion>()
        val tool = CompleteStepTool(
            workflowSnapshotProvider = {
                WorkflowExecutionSnapshot(
                    steps = listOf("修复工作流", "验证修复工作流"),
                    currentStepIndex = 0,
                    status = "EXECUTING"
                )
            },
            onWorkflowStepCompleted = { callbacks += it }
        )

        val result = tool.executeWithContext(
            args = """
            {
              "step": "修复",
              "result": "相关工作已完成",
              "evidence": [
                {
                  "summary": "已经运行验证命令",
                  "toolName": "shell",
                  "command": "./gradlew test"
                }
              ]
            }
            """.trimIndent(),
            runtimeContext = ToolRuntimeContext(
                currentTurnToolReceipts = listOf(
                    ToolExecutionReceipt(
                        toolName = "shell",
                        args = """{"command":"./gradlew test"}""",
                        result = "BUILD SUCCESSFUL",
                        timestamp = 100L
                    )
                )
            )
        )

        assertTrue(result.output.contains("Step signed off."))
        assertTrue(!result.output.contains("workflow_step="))
        assertTrue(callbacks.isEmpty())
    }
}

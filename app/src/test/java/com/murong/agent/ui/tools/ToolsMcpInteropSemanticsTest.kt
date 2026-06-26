package com.murong.agent.ui.tools

import com.murong.agent.core.mcp.McpConfigSource
import com.murong.agent.core.mcp.McpFailureRecord
import com.murong.agent.core.mcp.McpFailureStage
import com.murong.agent.core.mcp.McpServerConfig
import com.murong.agent.core.mcp.McpServerStatus
import com.murong.agent.core.mcp.McpTransportType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolsMcpInteropSemanticsTest {

    @Test
    fun buildMcpToolsOverview_includesSourceTrustAndFailureHints() {
        val overview = buildMcpToolsOverview(
            status = McpServerStatus(
                name = "filesystem",
                connected = false,
                toolCount = 0,
                failureRecord = McpFailureRecord(
                    stage = McpFailureStage.CONNECT,
                    message = "连接失败",
                    transport = McpTransportType.STDIO
                )
            ),
            config = McpServerConfig(
                name = "filesystem",
                source = McpConfigSource.MCP_JSON,
                sourcePath = "C:/workspace/.mcp.json",
                trustedReadOnlyTools = listOf("read_file", "list_dir"),
                autoStart = false
            )
        )

        assertTrue(overview.contains("工具 0"))
        assertTrue(overview.contains("来源 .mcp.json"))
        assertTrue(overview.contains("ro 2"))
        assertTrue(overview.contains("手动连接"))
        assertTrue(overview.contains("connect 失败"))
    }

    @Test
    fun buildMcpDetailFacts_includesConfigAndFailureDetails() {
        val facts = buildMcpDetailFacts(
            status = McpServerStatus(
                name = "filesystem",
                connected = false,
                toolCount = 1,
                toolNames = listOf("read_file"),
                failureRecord = McpFailureRecord(
                    stage = McpFailureStage.LIST_TOOLS,
                    message = "列出工具失败",
                    transport = McpTransportType.STDIO,
                    retryable = false
                )
            ),
            config = McpServerConfig(
                name = "filesystem",
                transport = McpTransportType.STDIO,
                source = McpConfigSource.IMPORTED_DRAFT,
                sourcePath = "C:/imports/filesystem.json",
                trustedReadOnlyTools = listOf("read_file")
            )
        )

        assertTrue(facts.contains("配置来源: draft"))
        assertTrue(facts.contains("来源路径: C:/imports/filesystem.json"))
        assertTrue(facts.contains("可信只读: 1 个"))
        assertTrue(facts.contains("失败阶段: list_tools"))
        assertTrue(facts.contains("失败传输: stdio"))
        assertTrue(facts.contains("可重试: 否"))
        assertTrue(facts.contains("失败信息: 列出工具失败"))
    }

    @Test
    fun buildMcpToolsConnectionLabel_prefersSavedConfigWhenDisconnected() {
        assertEquals(
            "未连接",
            buildMcpToolsConnectionLabel(
                status = null,
                config = McpServerConfig(name = "filesystem")
            )
        )
    }
}

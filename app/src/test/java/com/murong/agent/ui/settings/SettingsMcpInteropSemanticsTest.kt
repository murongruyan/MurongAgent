package com.murong.agent.ui.settings

import com.murong.agent.core.mcp.McpConfigSource
import com.murong.agent.core.mcp.McpFailureRecord
import com.murong.agent.core.mcp.McpFailureStage
import com.murong.agent.core.mcp.McpServerConfig
import com.murong.agent.core.mcp.McpServerStatus
import com.murong.agent.core.mcp.McpTransportType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsMcpInteropSemanticsTest {

    @Test
    fun buildMcpConfigSummary_includesSourceTrustAndManualStartHints() {
        val summary = buildMcpConfigSummary(
            McpServerConfig(
                name = "filesystem",
                transport = McpTransportType.STDIO,
                command = "npx",
                source = McpConfigSource.MCP_JSON,
                sourcePath = "C:/workspace/.mcp.json",
                trustedReadOnlyTools = listOf("read_file", "list_dir"),
                autoStart = false
            )
        )

        assertTrue(summary.contains(".mcp.json"))
        assertTrue(summary.contains("src .mcp.json"))
        assertTrue(summary.contains("ro 2"))
        assertTrue(summary.contains("手动连接"))
    }

    @Test
    fun buildMcpStatusSummary_explainsConfigIsRetainedAfterFailure() {
        val summary = buildMcpStatusSummary(
            status = McpServerStatus(
                name = "filesystem",
                connected = false,
                toolCount = 0,
                error = "连接失败",
                failureRecord = McpFailureRecord(
                    stage = McpFailureStage.CONNECT,
                    message = "连接失败",
                    transport = McpTransportType.STDIO
                )
            ),
            hasSavedConfig = true
        )

        assertTrue(summary.contains("未连接"))
        assertTrue(summary.contains("配置已保留"))
    }

    @Test
    fun buildMcpImportFailureMessage_onlyReturnsWarningWhenThereAreFailures() {
        assertNull(buildMcpImportFailureMessage(importedCount = 2, failedCount = 0))
        assertEquals(
            "已保存 3 个 MCP 配置，其中 1 个连接失败；配置不会回滚，可稍后重试连接。",
            buildMcpImportFailureMessage(importedCount = 3, failedCount = 1)
        )
    }

    @Test
    fun buildMcpConnectFailureMessage_onlyCountsEnabledSavedConfigsWithFailureRecords() {
        assertEquals(
            "已尝试连接 2 个已保存 MCP，其中 1 个失败；配置已保留，可稍后重试连接。",
            buildMcpConnectFailureMessage(
                configs = listOf(
                    McpServerConfig(name = "filesystem", enabled = true),
                    McpServerConfig(name = "github", enabled = true),
                    McpServerConfig(name = "disabled", enabled = false)
                ),
                statuses = listOf(
                    McpServerStatus(
                        name = "filesystem",
                        connected = false,
                        toolCount = 0,
                        failureRecord = McpFailureRecord(
                            stage = McpFailureStage.CONNECT,
                            message = "连接失败",
                            transport = McpTransportType.STDIO
                        )
                    ),
                    McpServerStatus(
                        name = "github",
                        connected = true,
                        toolCount = 3
                    ),
                    McpServerStatus(
                        name = "disabled",
                        connected = false,
                        toolCount = 0,
                        failureRecord = McpFailureRecord(
                            stage = McpFailureStage.CONNECT,
                            message = "连接失败",
                            transport = McpTransportType.STDIO
                        )
                    )
                )
            )
        )
    }

    @Test
    fun buildMcpConnectFailureMessage_returnsNullWhenThereAreNoRecordedFailures() {
        assertNull(
            buildMcpConnectFailureMessage(
                configs = listOf(McpServerConfig(name = "filesystem", enabled = true)),
                statuses = listOf(
                    McpServerStatus(
                        name = "filesystem",
                        connected = false,
                        toolCount = 0,
                        error = "连接失败"
                    )
                )
            )
        )
    }
}

package com.murong.agent.ui

import com.murong.agent.core.mcp.McpConfigSource
import com.murong.agent.core.mcp.McpFailureRecord
import com.murong.agent.core.mcp.McpFailureStage
import com.murong.agent.core.mcp.McpServerStatus
import com.murong.agent.core.mcp.McpTransportType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpInteropUpgradeTest {

    @Test
    fun parseMcpServerDrafts_supportsMcpJsonTrustedReadOnlyFields() {
        val result = parseMcpServerDrafts(
            """
            {
              "mcpServers": {
                "filesystem": {
                  "command": "npx",
                  "args": ["-y", "@modelcontextprotocol/server-filesystem", "C:/workspace"],
                  "trusted_read_only_tools": ["read_file", "list_dir"],
                  "autoStart": false,
                  "source": ".mcp.json",
                  "sourcePath": "C:/workspace/.mcp.json"
                }
              }
            }
            """.trimIndent()
        )

        val server = result.items.single()
        assertEquals("filesystem", server.name)
        assertEquals(McpConfigSource.MCP_JSON, server.source)
        assertEquals("C:/workspace/.mcp.json", server.sourcePath)
        assertEquals(listOf("read_file", "list_dir"), server.trustedReadOnlyTools)
        assertEquals(false, server.autoStart)
    }

    @Test
    fun mcpServerStatus_preservesStructuredFailureRecord() {
        val status = McpServerStatus(
            name = "filesystem",
            connected = false,
            toolCount = 0,
            error = "列出工具失败",
            failureRecord = McpFailureRecord(
                stage = McpFailureStage.LIST_TOOLS,
                message = "列出工具失败",
                transport = McpTransportType.STDIO
            )
        )

        assertEquals(McpFailureStage.LIST_TOOLS, status.failureRecord?.stage)
        assertEquals("列出工具失败", status.failureRecord?.message)
        assertEquals(McpTransportType.STDIO, status.failureRecord?.transport)
    }

    @Test
    fun parseMcpServerDrafts_appliesImportedMcpJsonSourceFallbackWhenSourcePathMissing() {
        val result = parseMcpServerDrafts(
            raw = """
            {
              "mcpServers": {
                "filesystem": {
                  "command": "npx",
                  "args": ["-y", "@modelcontextprotocol/server-filesystem", "C:/workspace"]
                }
              }
            }
            """.trimIndent(),
            importedSourcePath = ".mcp.json"
        )

        val server = result.items.single()
        assertEquals(McpConfigSource.MCP_JSON, server.source)
        assertEquals(".mcp.json", server.sourcePath)
    }

    @Test
    fun parseMcpServerDrafts_preservesExplicitSourcePathOverImportedDocumentFallback() {
        val result = parseMcpServerDrafts(
            raw = """
            {
              "mcpServers": {
                "filesystem": {
                  "command": "npx",
                  "args": ["-y", "@modelcontextprotocol/server-filesystem", "C:/workspace"],
                  "sourcePath": "C:/workspace/.mcp.json"
                }
              }
            }
            """.trimIndent(),
            importedSourcePath = "Downloads/imported.json"
        )

        val server = result.items.single()
        assertEquals("C:/workspace/.mcp.json", server.sourcePath)
    }
}

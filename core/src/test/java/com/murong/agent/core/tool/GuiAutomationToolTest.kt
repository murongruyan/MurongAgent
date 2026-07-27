package com.murong.agent.core.tool

import com.murong.agent.core.config.GuiInferenceMode
import com.murong.agent.core.config.ProviderConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuiAutomationToolTest {
    @Test
    fun actionSchemaContainsSemanticAndVisualPaths() {
        val tool = GuiAutomationTool { ProviderConfig() }
        val properties = tool.parameters["properties"] as Map<*, *>
        val action = properties["action"] as Map<*, *>
        val actions = action["enum"] as List<*>

        assertTrue("observe" in actions)
        assertTrue("click" in actions)
        assertTrue("input" in actions)
        assertTrue("vision_query" in actions)
        assertTrue("run_task" in actions)
    }

    @Test
    fun localOnlyVisionDoesNotRequestRemoteScreenshotScope() {
        val tool = GuiAutomationTool {
            ProviderConfig(guiInferenceMode = GuiInferenceMode.LOCAL_ONLY)
        }

        val approval = tool.buildApprovalRequest(
            """{"action":"vision_query","prompt":"find login"}"""
        )

        assertTrue(approval != null)
        assertFalse("gui:remote_screenshot" in approval!!.approvalScopeTokens)
    }

    @Test
    fun remoteApiVisionUsesDedicatedApprovalScope() {
        val tool = GuiAutomationTool {
            ProviderConfig(
                guiInferenceMode = GuiInferenceMode.USER_API,
                guiAllowRemoteScreenshots = true
            )
        }

        val approval = tool.buildApprovalRequest(
            """{"action":"vision_query","prompt":"find login"}"""
        )

        assertTrue(approval != null)
        assertTrue("gui:remote_screenshot" in approval!!.approvalScopeTokens)
    }

    @Test
    fun openAliasUsesLaunchApprovalSemantics() {
        val tool = GuiAutomationTool { ProviderConfig() }

        val approval = tool.buildApprovalRequest(
            """{"action":"open","target":"null","packageName":"com.ss.android.ugc.aweme"}"""
        )

        assertTrue(approval != null)
        assertTrue(approval!!.summary.contains("启动 Android 应用"))
    }

    @Test
    fun phoneAgentTaskUsesContinuousControlApprovalScopeForRemoteEndpoint() {
        val tool = GuiAutomationTool {
            ProviderConfig(
                activeProviderId = "openai-compatible",
                openaiBaseUrl = "https://configured.example/v1",
                openaiApiKey = "shared-secret",
                openaiModel = "configured-vision-model",
                phoneAgentAllowRemoteScreenshots = true
            )
        }

        val approval = tool.buildApprovalRequest(
            """{"action":"run_task","task":"比较咖啡外卖到手价"}"""
        )

        assertTrue(approval != null)
        assertTrue(approval!!.riskLevel == ApprovalRiskLevel.HIGH)
        assertTrue("gui:remote_screenshot" in approval.approvalScopeTokens)
        assertTrue("gui:continuous_control" in approval.approvalScopeTokens)
    }

    @Test
    fun phoneAgentTaskUsesSelectedLocalModelWithoutRemoteScreenshotScope() {
        val tool = GuiAutomationTool {
            ProviderConfig(
                activeProviderId = "openai-compatible",
                openaiBaseUrl = "http://127.0.0.1:11434/v1",
                openaiModel = "installed-vision-model"
            )
        }

        val approval = tool.buildApprovalRequest(
            """{"action":"run_task","task":"打开设置"}"""
        )

        assertTrue(approval != null)
        assertTrue("gui:continuous_control" in approval!!.approvalScopeTokens)
        assertFalse("gui:remote_screenshot" in approval.approvalScopeTokens)
    }
}

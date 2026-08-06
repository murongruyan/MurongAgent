package com.murong.agent.core.tool

import com.murong.agent.core.config.GuiInferenceMode
import com.murong.agent.core.config.ProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuiAutomationToolTest {

    @Test
    fun `task overlay capsule shows compact step and review state`() {
        assertEquals(
            "Murong · 5/60",
            taskOverlayCapsuleLabel("第 5/60 步：正在识别界面", completed = false),
        )
        assertEquals(
            "Murong · 执行中",
            taskOverlayCapsuleLabel("正在启动微信", completed = false),
        )
        assertEquals(
            "Murong · 待确认",
            taskOverlayCapsuleLabel("任务已完成", completed = true),
        )
    }

    @Test
    fun `foreground activity parser expands relative activity for matching package`() {
        assertEquals(
            "com.tencent.mm.plugin.fts.ui.FTSMainUI",
            parseForegroundActivityClass(
                "topResumedActivity=ActivityRecord{189224568 u0 " +
                    "com.tencent.mm/.plugin.fts.ui.FTSMainUI t17666}",
                "com.tencent.mm",
            ),
        )
        assertNull(
            parseForegroundActivityClass(
                "topResumedActivity=ActivityRecord{1 u0 com.tencent.mm/.ui.LauncherUI t1}",
                "com.tencent.mobileqq",
            ),
        )
    }

    @Test
    fun `isolated display observation never falls back to physical display package`() {
        val failedIsolatedSemantics = GuiObservation(
            success = false,
            target = "android",
            observationId = "",
            application = "com.tencent.mm",
            source = "accessibility",
            error = "当前窗口没有可访问的语义树",
        )
        assertEquals(
            "com.ss.android.ugc.aweme",
            resolvePhoneAgentApplication(
                isolatedDisplayActive = true,
                semanticObservation = failedIsolatedSemantics,
                isolatedPackage = "com.ss.android.ugc.aweme",
                latestPhysicalPackage = "com.tencent.mm",
            ),
        )
        assertNull(
            resolvePhoneAgentApplication(
                isolatedDisplayActive = true,
                semanticObservation = failedIsolatedSemantics,
                isolatedPackage = null,
                latestPhysicalPackage = "com.tencent.mm",
            ),
        )
    }

    @Test
    fun `isolated display package wins over successful semantics from physical display`() {
        val physicalSemantics = GuiObservation(
            success = true,
            target = "android",
            observationId = "android:1",
            application = "com.tencent.mm",
            nodes = listOf(
                GuiNodeSnapshot(
                    id = "android:1/0",
                    role = "android.view.View",
                    bounds = GuiRect(0, 0, 1440, 3136),
                ),
            ),
            source = "accessibility",
        )

        assertEquals(
            "com.ss.android.ugc.aweme",
            resolvePhoneAgentApplication(
                isolatedDisplayActive = true,
                semanticObservation = physicalSemantics,
                isolatedPackage = "com.ss.android.ugc.aweme",
                latestPhysicalPackage = "com.tencent.mm",
            ),
        )
        assertNull(
            selectPhoneAgentSemanticObservation(
                isolatedDisplayActive = true,
                semanticObservation = physicalSemantics,
                isolatedPackage = "com.ss.android.ugc.aweme",
            ),
        )
    }

    @Test
    fun `isolated display accepts matching meaningful semantics`() {
        val douyinSemantics = GuiObservation(
            success = true,
            target = "android",
            observationId = "android:2",
            application = "com.ss.android.ugc.aweme",
            nodes = listOf(
                GuiNodeSnapshot(
                    id = "android:2/0",
                    role = "android.widget.FrameLayout",
                    bounds = GuiRect(0, 0, 1440, 3136),
                ),
            ),
            source = "accessibility",
        )

        assertEquals(
            douyinSemantics,
            selectPhoneAgentSemanticObservation(
                isolatedDisplayActive = true,
                semanticObservation = douyinSemantics,
                isolatedPackage = "com.ss.android.ugc.aweme",
            ),
        )
    }

    @Test
    fun `isolated display rejects empty successful semantic root`() {
        val emptySemantics = GuiObservation(
            success = true,
            target = "android",
            observationId = "android:3",
            application = "com.ss.android.ugc.aweme",
            nodes = listOf(
                GuiNodeSnapshot(
                    id = "android:3/0",
                    role = "android.view.View",
                    bounds = GuiRect(0, 0, 0, 0),
                ),
            ),
            source = "accessibility",
        )

        assertNull(
            selectPhoneAgentSemanticObservation(
                isolatedDisplayActive = true,
                semanticObservation = emptySemantics,
                isolatedPackage = "com.ss.android.ugc.aweme",
            ),
        )
    }

    @Test
    fun `physical display observation can use latest accessibility package`() {
        assertEquals(
            "com.tencent.mm",
            resolvePhoneAgentApplication(
                isolatedDisplayActive = false,
                semanticObservation = null,
                isolatedPackage = null,
                latestPhysicalPackage = "com.tencent.mm",
            ),
        )
    }

    @Test
    fun `video call directly launches mentioned app after switching to physical display`() {
        assertTrue(
            shouldDirectLaunchPhoneAgentTarget(
                isolatedDisplayActive = false,
                videoCallRequested = true,
                mentionedPackage = PhoneAgentApps.DOUYIN_PACKAGE,
            ),
        )
        assertFalse(
            shouldDirectLaunchPhoneAgentTarget(
                isolatedDisplayActive = true,
                videoCallRequested = true,
                mentionedPackage = PhoneAgentApps.DOUYIN_PACKAGE,
            ),
        )
        assertFalse(
            shouldDirectLaunchPhoneAgentTarget(
                isolatedDisplayActive = false,
                videoCallRequested = false,
                mentionedPackage = PhoneAgentApps.DOUYIN_PACKAGE,
            ),
        )
    }

    @Test
    fun cancellationIsControlFlowAndNeverBecomesVisibleToolError() {
        val tool = GuiAutomationTool { ProviderConfig() }

        assertFailsWith<CancellationException> {
            runBlocking {
                withTimeout(10) {
                    tool.executeWithResult(
                        """{"action":"wait","waitMs":60000}"""
                    )
                }
            }
        }
    }

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

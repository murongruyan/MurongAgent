package com.murong.agent.lan

import com.murong.agent.core.loop.PendingApprovalUi
import com.murong.agent.core.tool.ApprovalRiskLevel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanWebApprovalBindingTest {
    private val pending = PendingApprovalUi(
        toolName = "shell",
        summary = "写入文件",
        detail = "将修改项目文件",
        rawArgs = "{\"command\":\"apply\"}",
        riskLevel = ApprovalRiskLevel.HIGH
    )

    @Test
    fun `approval id is bound to session and every material request field`() {
        val id = LanWebApprovalBinding.fingerprint("session-a", pending)
        assertTrue(LanWebApprovalBinding.matches("session-a", pending, "session-a", id))
        assertFalse(LanWebApprovalBinding.matches("session-a", pending, "session-b", id))
        assertFalse(
            LanWebApprovalBinding.matches(
                "session-a",
                pending.copy(rawArgs = "{\"command\":\"different\"}"),
                "session-a",
                id
            )
        )
        assertFalse(LanWebApprovalBinding.matches("session-a", null, "session-a", id))
    }

    @Test
    fun `replay only status changes approval identity`() {
        val liveId = LanWebApprovalBinding.fingerprint("session-a", pending)
        val replayId = LanWebApprovalBinding.fingerprint("session-a", pending.copy(isReplayOnly = true))
        assertFalse(liveId == replayId)
    }
}

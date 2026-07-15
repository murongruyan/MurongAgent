package com.murong.agent.core.loop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptSerializationPolicyTest {

    @Test
    fun resolvePromptSerializationDecision_blocksAskWhenApprovalIsPending() {
        val decision = resolvePromptSerializationDecision(
            requestedKind = PendingPromptKind.ASK,
            hasPendingApproval = true,
            hasPendingAsk = false
        )

        assertTrue(decision.blocked)
        assertTrue(decision.detail!!.contains("审批请求"))
        assertTrue(decision.detail.contains("ask_user"))
    }

    @Test
    fun resolvePromptSerializationDecision_blocksApprovalWhenAskIsPending() {
        val decision = resolvePromptSerializationDecision(
            requestedKind = PendingPromptKind.APPROVAL,
            hasPendingApproval = false,
            hasPendingAsk = true
        )

        assertTrue(decision.blocked)
        assertTrue(decision.detail!!.contains("提问卡片"))
        assertTrue(decision.detail.contains("新的审批请求"))
    }

    @Test
    fun resolvePromptSerializationDecision_allowsPromptWhenNoPendingPromptExists() {
        val decision = resolvePromptSerializationDecision(
            requestedKind = PendingPromptKind.APPROVAL,
            hasPendingApproval = false,
            hasPendingAsk = false
        )

        assertFalse(decision.blocked)
    }
}

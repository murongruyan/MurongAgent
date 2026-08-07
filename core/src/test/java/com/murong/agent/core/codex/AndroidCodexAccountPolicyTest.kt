package com.murong.agent.core.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidCodexAccountPolicyTest {
    @Test
    fun quotaPolicy_usesWorstRollingWindowAndConfiguredReserve() {
        assertTrue(
            CodexAccountQuotaSnapshot(
                primaryUsedPercent = 40,
                secondaryUsedPercent = 91,
            ).isBelowReserve(reservePercent = 10),
        )
        assertFalse(
            CodexAccountQuotaSnapshot(
                primaryUsedPercent = 40,
                secondaryUsedPercent = 89,
            ).isBelowReserve(reservePercent = 10),
        )
        assertEquals(
            9,
            CodexAccountQuotaSnapshot(
                primaryUsedPercent = 25,
                secondaryUsedPercent = 91,
            ).remainingScore(),
        )
    }

    @Test
    fun missingQuota_isNotTreatedAsExhaustedButRanksBehindMeasuredAccount() {
        val unknown = CodexAccountQuotaSnapshot()
        assertFalse(unknown.isBelowReserve(reservePercent = 10))
        assertEquals(-1, unknown.remainingScore())
        assertEquals(80, CodexAccountQuotaSnapshot(primaryUsedPercent = 20).remainingScore())
    }

    @Test
    fun emailSnapshot_withoutCredentialMaterial_isNotRestorable() {
        assertFalse(codexAccountHasDurableLogin(protectedAuth = "", authFilePresent = false))
        assertTrue(codexAccountHasDurableLogin(protectedAuth = "encrypted", authFilePresent = false))
        assertTrue(codexAccountHasDurableLogin(protectedAuth = "", authFilePresent = true))
    }
}

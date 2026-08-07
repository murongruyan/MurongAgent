package com.murong.agent.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderBalanceSourceTest {
    @Test
    fun `zero is unknown until a source confirms it`() {
        val config = ProviderConfig().withRelayConfigs(
            providerId = "deepseek",
            relays = listOf(RelayConfig(id = "official", kind = RelayKind.OFFICIAL)),
            activeRelayId = "official",
        )

        assertFalse(config.hasKnownBalance("deepseek"))

        val estimated = config.withLocalBalanceEstimate("deepseek", 0.0, "CNY")
        assertTrue(estimated.hasKnownBalance("deepseek"))
        assertEquals(BalanceDataSource.LOCAL_ESTIMATE, estimated.getBalanceSource("deepseek"))
    }

    @Test
    fun `official sync updates active relay instead of stale legacy fields only`() {
        val config = ProviderConfig().withRelayConfigs(
            providerId = "deepseek",
            relays = listOf(RelayConfig(id = "official", kind = RelayKind.OFFICIAL)),
            activeRelayId = "official",
        )

        val synced = config.withBalanceInfo(
            providerId = "deepseek",
            balanceUsd = 12.5,
            balanceCurrency = "CNY",
            syncedAt = 123L,
            source = BalanceDataSource.OFFICIAL_API,
        )

        assertEquals(12.5, synced.getBalanceAmount("deepseek"))
        assertEquals(123L, synced.getBalanceSyncedAt("deepseek"))
        assertEquals(BalanceDataSource.OFFICIAL_API, synced.getBalanceSource("deepseek"))
    }
}

package com.murong.agent.core.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodexAuthStorageConfigTest {
    @Test
    fun missingSetting_isAppendedWithoutDiscardingExistingConfig() {
        val result = normalizeCodexAuthStorageConfig("[features]\nexperimental_api = true\n")

        assertTrue(result.startsWith("[features]\nexperimental_api = true"))
        assertTrue(result.contains("cli_auth_credentials_store = \"file\""))
    }

    @Test
    fun existingSetting_isNormalizedToDurableFileStore() {
        assertEquals(
            "cli_auth_credentials_store = \"file\"\n",
            normalizeCodexAuthStorageConfig("cli_auth_credentials_store = \"keyring\"\n"),
        )
    }
}

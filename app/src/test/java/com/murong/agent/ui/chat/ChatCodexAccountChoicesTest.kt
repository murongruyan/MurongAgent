package com.murong.agent.ui.chat

import com.murong.agent.core.codex.CodexAccountPoolSnapshot
import com.murong.agent.core.codex.CodexAccountQuotaSnapshot
import com.murong.agent.core.codex.CodexManagedAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCodexAccountChoicesTest {
    @Test
    fun `logged in accounts are exposed as direct chat choices`() {
        val choices = buildCodexAccountConfigurationChoices(
            accountPool = CodexAccountPoolSnapshot(
                activeAccountId = "first",
                accounts = listOf(
                    account(id = "first", email = "first@example.com", active = true),
                    account(id = "second", email = "second@example.com"),
                    account(id = "pending", email = null, loggedIn = false),
                ),
            ),
            usesCodexChatGpt = true,
            canSwitchAccount = true,
        )

        assertEquals(2, choices.size)
        assertEquals("✓ first@example.com", choices[0].title)
        assertFalse(choices[0].enabled)
        assertEquals("second@example.com", choices[1].title)
        assertTrue(choices[1].enabled)
        assertEquals("second", codexAccountIdFromConfigurationKey(choices[1].key))
    }

    @Test
    fun `active account remains selectable when chat is using another backend`() {
        val choices = buildCodexAccountConfigurationChoices(
            accountPool = CodexAccountPoolSnapshot(
                activeAccountId = "first",
                accounts = listOf(account(id = "first", email = "first@example.com", active = true)),
            ),
            usesCodexChatGpt = false,
            canSwitchAccount = true,
        )

        assertEquals("first@example.com", choices.single().title)
        assertTrue(choices.single().enabled)
    }

    @Test
    fun `task execution disables switching and malformed keys are ignored`() {
        val choices = buildCodexAccountConfigurationChoices(
            accountPool = CodexAccountPoolSnapshot(
                accounts = listOf(
                    account(
                        id = "second",
                        email = "second@example.com",
                        quota = CodexAccountQuotaSnapshot(primaryUsedPercent = 25),
                    ),
                ),
            ),
            usesCodexChatGpt = true,
            canSwitchAccount = false,
        )

        assertFalse(choices.single().enabled)
        assertTrue(choices.single().subtitle.orEmpty().contains("剩余 75%"))
        assertNull(codexAccountIdFromConfigurationKey("__codex_account__:"))
        assertNull(codexAccountIdFromConfigurationKey("__codex_chatgpt_backend__"))
    }

    private fun account(
        id: String,
        email: String?,
        active: Boolean = false,
        loggedIn: Boolean = true,
        quota: CodexAccountQuotaSnapshot = CodexAccountQuotaSnapshot(),
    ): CodexManagedAccount = CodexManagedAccount(
        id = id,
        label = "账号 $id",
        email = email,
        planType = "free",
        active = active,
        loggedIn = loggedIn,
        quota = quota,
    )
}

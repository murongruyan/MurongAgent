package com.murong.agent.core.github

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AndroidGitHubAccountManagerInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val documentFile by lazy { File(context.filesDir, AndroidGitHubAccountManager.DOCUMENT_FILE) }

    @Before
    @After
    fun clearDocument() {
        documentFile.delete()
    }

    @Test
    fun accountPoolEncryptsTokensAndSwitchesWithoutRemovingOtherAccounts() {
        val firstToken = "github-test-token-first-${System.nanoTime()}"
        val secondToken = "github-test-token-second-${System.nanoTime()}"
        val manager = AndroidGitHubAccountManager(context)

        val first = manager.saveAuthenticatedAccount(
            token = firstToken,
            backendSessionToken = "backend-first",
            login = "first-user",
            name = "First User",
        )
        val second = manager.saveAuthenticatedAccount(
            token = secondToken,
            backendSessionToken = "backend-second",
            login = "second-user",
            name = "Second User",
        )

        assertNotEquals(first.id, second.id)
        assertEquals(2, manager.state.value.accounts.size)
        assertEquals(secondToken, manager.activeCredentials()?.token)
        assertFalse(documentFile.readText().contains(firstToken))
        assertFalse(documentFile.readText().contains(secondToken))

        manager.activateAccount(first.id)
        assertEquals(firstToken, manager.activeCredentials()?.token)

        val transfer = manager.exportAccountPool()
        assertEquals(2, transfer.accounts.size)
        documentFile.delete()
        val replica = AndroidGitHubAccountManager(context)
        assertEquals(2, replica.importAccountPool(transfer, replaceExisting = true))
        assertEquals(first.id, replica.state.value.activeAccountId)
        assertEquals(firstToken, replica.activeCredentials()?.token)
        assertFalse(documentFile.readText().contains(firstToken))
        assertFalse(documentFile.readText().contains(secondToken))

        replica.logoutAccount(first.id)

        assertTrue(replica.state.value.accounts.first { it.id == first.id }.loggedIn.not())
        assertTrue(replica.state.value.accounts.first { it.id == second.id }.loggedIn)
        assertEquals(secondToken, replica.activateAccount(second.id).token)

        val reloaded = AndroidGitHubAccountManager(context)
        assertEquals(secondToken, reloaded.activeCredentials()?.token)
        assertEquals(2, reloaded.state.value.accounts.size)
    }

    @Test
    fun legacyTokenMigratesIntoDefaultSlotAndDuplicateLoginUpdatesIt() {
        val manager = AndroidGitHubAccountManager(context)
        manager.migrateLegacyAccount(token = "legacy-token", login = "same-user")
        val initialId = manager.state.value.activeAccountId

        manager.saveAuthenticatedAccount(token = "refreshed-token", login = "SAME-USER")

        assertEquals(1, manager.state.value.accounts.size)
        assertEquals(initialId, manager.state.value.activeAccountId)
        assertEquals("refreshed-token", manager.activeCredentials()?.token)
    }
}

package com.murong.agent.lan

import com.murong.agent.core.config.ProviderConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanWebGitHubCredentialMergeTest {
    @Test
    fun nonEmptyTokenReplacesCredentialAndClearsStaleViewerMetadata() {
        val current = ProviderConfig(
            githubApiBaseUrl = "https://api.github.com",
            githubToken = "old-token",
            githubViewerLogin = "old-user",
            githubViewerName = "Old User",
            githubViewerAvatarUrl = "https://avatars.example/old",
        )

        val (merged, imported) = mergeSyncedGitHubCredential(
            current,
            LanWebSyncedGitHubCredential(
                apiBaseUrl = "https://github.example/api/v3/",
                token = " new-token ",
                viewerLogin = "new-user",
            ),
        )

        assertTrue(imported)
        assertEquals("https://github.example/api/v3", merged.githubApiBaseUrl)
        assertEquals("new-token", merged.githubToken)
        assertEquals("new-user", merged.githubViewerLogin)
        assertEquals("", merged.githubViewerName)
        assertEquals("", merged.githubViewerAvatarUrl)
    }

    @Test
    fun absentTokenPreservesTargetCredentialAndViewer() {
        val current = ProviderConfig(
            githubToken = "keep-token",
            githubViewerLogin = "keep-user",
            githubViewerName = "Keep User",
        )

        val (merged, imported) = mergeSyncedGitHubCredential(
            current,
            LanWebSyncedGitHubCredential(apiBaseUrl = "https://api.github.com", token = null),
        )

        assertFalse(imported)
        assertEquals("keep-token", merged.githubToken)
        assertEquals("keep-user", merged.githubViewerLogin)
        assertEquals("Keep User", merged.githubViewerName)
    }
}

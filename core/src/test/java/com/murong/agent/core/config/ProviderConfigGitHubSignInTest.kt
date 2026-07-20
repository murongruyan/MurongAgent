package com.murong.agent.core.config

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderConfigGitHubSignInTest {
    @Test
    fun githubAccessTokenIsSufficientForSignedInTools() {
        assertTrue(ProviderConfig(githubToken = "github-access-token").isGitHubSignedIn())
        assertFalse(ProviderConfig(githubBackendSessionToken = "backend-session-only").isGitHubSignedIn())
        assertFalse(ProviderConfig().isGitHubSignedIn())
    }
}

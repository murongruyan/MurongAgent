package com.murong.agent.core.tool

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuiForegroundVerificationTest {
    @Test
    fun `launch success requires the requested package to be foreground`() {
        assertTrue(
            foregroundPackageMatches(
                observed = "com.ss.android.ugc.aweme",
                requested = "com.ss.android.ugc.aweme",
            ),
        )
        assertFalse(
            foregroundPackageMatches(
                observed = "com.android.launcher",
                requested = "com.ss.android.ugc.aweme",
            ),
        )
        assertFalse(foregroundPackageMatches(observed = null, requested = "com.example"))
    }
}

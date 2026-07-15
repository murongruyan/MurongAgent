package com.murong.agent.core.doctor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensitiveDataSanitizerTest {

    @Test
    fun sanitizeText_redactsBearerJwtEmailPathAndSecrets() {
        val sanitized = SensitiveDataSanitizer.sanitizeText(
            """
            Authorization: Bearer abcdefghijklmnopqrstuvwxyz
            api_key=super-secret-value
            contact=alice@example.com
            jwt=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.signaturevalue
            path=C:\Users\Alice\project\secrets.txt
            unix=/home/alice/workspace/secret.env
            token=ghp_1234567890abcdef
            """.trimIndent()
        )

        assertFalse(sanitized.contains("abcdefghijklmnopqrstuvwxyz"))
        assertFalse(sanitized.contains("super-secret-value"))
        assertFalse(sanitized.contains("alice@example.com"))
        assertFalse(sanitized.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(sanitized.contains("""C:\Users\Alice\project\secrets.txt"""))
        assertFalse(sanitized.contains("/home/alice/workspace/secret.env"))
        assertFalse(sanitized.contains("ghp_1234567890abcdef"))
        assertTrue(sanitized.contains("Bearer [REDACTED_BEARER]"))
        assertTrue(sanitized.contains("[REDACTED_SECRET]"))
        assertTrue(sanitized.contains("[REDACTED_EMAIL]"))
        assertTrue(sanitized.contains("[REDACTED_JWT]"))
        assertTrue(sanitized.contains("[REDACTED_PATH]"))
        assertTrue(sanitized.contains("[REDACTED_TOKEN]"))
    }

    @Test
    fun sanitizeTransportHeaderValue_stripsControlCharactersButKeepsValue() {
        val sanitized = SensitiveDataSanitizer.sanitizeTransportHeaderValue(
            "Bearer abc123\r\nInjected: nope\t"
        )

        assertEquals("Bearer abc123 Injected: nope", sanitized)
    }
}

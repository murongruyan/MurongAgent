package com.murong.agent.core.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodexUserMessageSanitizerTest {
    @Test
    fun deactivatedAccount_isExplainedWithoutTransportNoise() {
        assertEquals(
            "该 ChatGPT 账号已被 OpenAI 删除或停用，请更换账号后重试。",
            CodexUserMessageSanitizer.sanitize(
                "Codex app-server request failed (-32000): account_deactivated",
            ),
        )
    }

    @Test
    fun lowLevelNetworkFailure_becomesActionableMessage() {
        assertEquals(
            "连接 OpenAI 失败，请检查网络或代理后重试。",
            CodexUserMessageSanitizer.sanitize("error sending request for url (https://[REDACTED_PATH])"),
        )
    }

    @Test
    fun unknownError_remainsSanitized() {
        val message = CodexUserMessageSanitizer.sanitize("unexpected /data/user/0/com.murong.agent/cache")
        assertTrue("[REDACTED_PATH]" in message)
    }
}

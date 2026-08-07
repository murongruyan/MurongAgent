package com.murong.agent.core.provider

import com.murong.agent.core.config.AuxiliaryModelRoute

/**
 * Converts image input into a compact textual observation before the primary
 * agent runs. This lets a text-only main model use a separately configured
 * vision model without copying credentials into a second settings surface.
 */
class AuxiliaryVisionAnalyzer {
    suspend fun analyze(
        route: AuxiliaryModelRoute,
        images: List<ChatImageAttachment>,
        userPrompt: String,
    ): String {
        require(images.isNotEmpty()) { "看图路由没有收到图片" }
        val provider = when {
            route.providerId == "custom" -> OpenAIProvider()
            route.wireFormat == ProviderWireFormat.ANTHROPIC_MESSAGES -> ClaudeProvider()
            else -> ProviderRegistry.getProvider(route.providerId) ?: OpenAIProvider()
        }
        val resolvedBaseUrl = when (route.wireFormat) {
            ProviderWireFormat.RESPONSES -> route.baseUrl.trimEnd('/').let { base ->
                if (base.endsWith("/responses", ignoreCase = true)) base else "$base/responses"
            }
            else -> route.baseUrl
        }
        val response = provider.chat(
            request = ChatRequest(
                messages = listOf(
                    ChatMessage(
                        role = "system",
                        content = "你是独立看图模型。只描述图片中可验证的内容，保留文字、代码、界面状态和空间关系；不猜测看不到的信息。",
                    ),
                    ChatMessage(
                        role = "user",
                        content = userPrompt.trim().ifBlank { "提取图片中与当前任务有关的全部关键信息。" },
                        images = images,
                    ),
                ),
                model = route.model,
                temperature = 0.1,
                maxTokens = 2_048,
                stream = false,
            ),
            apiKey = route.apiKey,
            baseUrl = resolvedBaseUrl.takeIf(String::isNotBlank),
        )
        return response.content.orEmpty().trim().ifBlank {
            throw IllegalStateException("独立看图模型没有返回可用描述")
        }
    }
}

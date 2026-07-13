package com.murong.agent.core.provider

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * ReasoningProtocolDetector — 推理协议自动检测
 *
 * 根据 Base URL 自动检测后端类型，选择合适的推理协议参数。
 * 不同 OpenAI 兼容后端对 reasoning_effort 和 thinking 的支持各不相同。
 *
 * 移植自 Reasonix (DeepSeek-Reasonix) internal/provider/openai/openai.go 的检测逻辑。
 */
object ReasoningProtocolDetector {

    /** 后端类型枚举 */
    enum class BackendType {
        /** 标准 OpenAI 兼容（默认 reasoning_effort） */
        STANDARD,
        /** DeepSeek（extra_body.thinking.type + reasoning_effort） */
        DEEPSEEK,
        /** MiniMax M3（thinking.type=adaptive|disabled，无 reasoning_effort） */
        MINIMAX,
        /** Zhipu GLM（thinking.type=enabled|disabled，忽略 reasoning_effort） */
        ZHIPU,
        /** LongCat（thinking.type=enabled|disabled） */
        LONGCAT,
        /** Ollama Cloud（支持 reasoning_effort 范围） */
        OLLAMA_CLOUD,
        /** Azure OpenAI */
        AZURE
    }

    data class ReasoningConfig(
        val backend: BackendType,
        /** 是否发送 thinking.type 字段 */
        val useThinkingType: Boolean = false,
        /** 是否发送 reasoning_effort 字段 */
        val useReasoningEffort: Boolean = true,
        /** thinking.type 的值 ("enabled", "disabled", "adaptive") */
        val thinkingTypeValue: String = "enabled"
    )

    /**
     * 根据 Base URL 检测后端类型
     */
    fun detectBackend(baseUrl: String): BackendType {
        val url = baseUrl.trimEnd('/').lowercase()
        return when {
            url.contains(".openai.azure.com") -> BackendType.AZURE
            url.contains("api.deepseek.com") -> BackendType.DEEPSEEK
            url.contains("api.minimaxi.com") -> BackendType.MINIMAX
            url.contains("open.bigmodel.cn") -> BackendType.ZHIPU
            url.contains("api.z.ai") -> BackendType.ZHIPU
            url.contains("api.longcat.chat") -> BackendType.LONGCAT
            url.contains("ollama.com") -> BackendType.OLLAMA_CLOUD
            url.contains("api.openai.com") -> BackendType.STANDARD
            else -> BackendType.STANDARD
        }
    }

    /**
     * 根据后端类型构建推理配置。
     *
     * @param baseUrl API Base URL
     * @param reasoningEffort 用户配置的推理档位（可为空）
     * @param explicitThinking 可选的显式 thinking 覆盖（"enabled"/"disabled"/null）
     * @return 包含调整后的 reasoningEffort 和配置信息的 ReasoningConfig
     */
    fun buildConfig(
        baseUrl: String,
        reasoningEffort: String?,
        explicitThinking: String? = null
    ): Pair<String?, ReasoningConfig> {
        val backend = detectBackend(baseUrl)
        var effort = reasoningEffort?.trim()?.lowercase().orEmpty().takeIf { it.isNotBlank() }
        val thinkingOverride = explicitThinking?.trim()?.lowercase()
            ?.takeIf { it in listOf("enabled", "disabled") }

        val config = when (backend) {
            BackendType.AZURE -> {
                // Azure OpenAI 不支持 extra_body.thinking
                ReasoningConfig(
                    backend = BackendType.AZURE,
                    useThinkingType = false,
                    useReasoningEffort = effort != null
                )
            }

            BackendType.DEEPSEEK -> {
                if (thinkingOverride == "disabled") {
                    effort = null
                    ReasoningConfig(
                        backend = BackendType.DEEPSEEK,
                        useThinkingType = true,
                        useReasoningEffort = false,
                        thinkingTypeValue = "disabled"
                    )
                } else {
                    // DeepSeek: 如果没设 effort 默认 "high"
                    if (effort in listOf(null, "", "off")) {
                        effort = "high"
                    }
                    if (effort == "disabled") {
                        effort = null
                        ReasoningConfig(
                            backend = BackendType.DEEPSEEK,
                            useThinkingType = true,
                            useReasoningEffort = false,
                            thinkingTypeValue = "disabled"
                        )
                    } else {
                        ReasoningConfig(
                            backend = BackendType.DEEPSEEK,
                            useThinkingType = true,
                            useReasoningEffort = true
                        )
                    }
                }
            }

            BackendType.MINIMAX -> {
                // MiniMax M3: thinking.type=adaptive|disabled，无 reasoning_effort
                val thinkingVal = when (effort) {
                    null, "", "off", "disabled" -> "disabled"
                    else -> "adaptive"
                }
                ReasoningConfig(
                    backend = BackendType.MINIMAX,
                    useThinkingType = true,
                    useReasoningEffort = false,
                    thinkingTypeValue = thinkingVal
                )
            }

            BackendType.ZHIPU, BackendType.LONGCAT -> {
                // Zhipu/LongCat: thinking.type=enabled|disabled
                val thinkingVal = when (effort) {
                    null, "", "off", "disabled" -> "disabled"
                    else -> "enabled"
                }
                ReasoningConfig(
                    backend = backend,
                    useThinkingType = true,
                    useReasoningEffort = false,
                    thinkingTypeValue = thinkingVal
                )
            }

            BackendType.OLLAMA_CLOUD -> {
                // Ollama Cloud: 支持 reasoning_effort（包括 max）
                // 但 effort 为空时不要发
                ReasoningConfig(
                    backend = BackendType.OLLAMA_CLOUD,
                    useThinkingType = false,
                    useReasoningEffort = effort != null && effort != "off"
                )
            }

            BackendType.STANDARD -> {
                // 标准 OpenAI: reasoning_effort 标准用法
                if (effort == "off") effort = null
                ReasoningConfig(
                    backend = BackendType.STANDARD,
                    useThinkingType = thinkingOverride != null,
                    useReasoningEffort = effort != null && thinkingOverride != "disabled",
                    thinkingTypeValue = thinkingOverride ?: "enabled"
                )
            }
        }

        return effort to config
    }

    /**
     * 给请求体添加 reasoning 相关参数。
     *
     * @param bodyBuilder 正在构建的 JsonObject builder
     * @param baseUrl API Base URL
     * @param reasoningEffort 用户配置的推理档位
     * @param modelName 模型名称
     * @param explicitThinkingOverride 可选的显式 thinking 覆盖
     */
    fun applyReasoningToBody(
        bodyBuilder: JsonObjectBuilder,
        baseUrl: String,
        reasoningEffort: String?,
        modelName: String,
        explicitThinkingOverride: String? = null
    ) {
        val (adjustedEffort, config) = buildConfig(baseUrl, reasoningEffort, explicitThinkingOverride)

        if (config.useReasoningEffort && adjustedEffort != null) {
            bodyBuilder.put("reasoning_effort", adjustedEffort)
        }

        if (config.useThinkingType) {
            when (config.backend) {
                BackendType.DEEPSEEK -> {
                    bodyBuilder.putJsonObject("extra_body") {
                        putJsonObject("thinking") {
                            put("type", config.thinkingTypeValue)
                        }
                    }
                }
                BackendType.MINIMAX, BackendType.ZHIPU, BackendType.LONGCAT -> {
                    bodyBuilder.putJsonObject("thinking") {
                        put("type", config.thinkingTypeValue)
                    }
                }
                else -> {
                    // STANDARD with explicit thinking override
                    bodyBuilder.putJsonObject("thinking") {
                        put("type", config.thinkingTypeValue)
                    }
                }
            }
        }
    }
}

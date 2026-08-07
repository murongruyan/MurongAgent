package com.murong.agent.core.provider

enum class ProviderApiProtocol(val displayName: String) {
    OPENAI_COMPATIBLE("OpenAI Compatible"),
    ANTHROPIC("Anthropic Messages"),
    GEMINI("Gemini GenerateContent"),
}

/** The upstream wire format for an OpenAI-compatible connection. */
@kotlinx.serialization.Serializable
enum class ProviderWireFormat(val displayName: String) {
    CHAT_COMPLETIONS("Chat Completions（需开启路由）"),
    RESPONSES("Responses（原生）"),
    ANTHROPIC_MESSAGES("Anthropic Messages（需开启路由）"),
}

enum class ProviderPresetCategory(val displayName: String) {
    OFFICIAL("国际官方"),
    CN_OFFICIAL("国内官方"),
}

data class ProviderPresetMetadata(
    val providerId: String,
    val displayName: String,
    val category: ProviderPresetCategory,
    val protocol: ProviderApiProtocol,
    val websiteUrl: String,
    val apiKeyUrl: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val recommendedModels: List<String>,
    /** Models from this vendor preset that accept image input. */
    val visionModels: List<String> = emptyList(),
    /** Models exposed by the vendor's image-generation endpoint. */
    val imageGenerationModels: List<String> = emptyList(),
    val defaultWireFormat: ProviderWireFormat = ProviderWireFormat.CHAT_COMPLETIONS,
    val supportedReasoningEfforts: List<String> = emptyList(),
    val endpointCandidates: List<String> = listOf(defaultBaseUrl),
    val requiresOAuth: Boolean = false,
)

/**
 * Stable provider presets shared by settings, model recommendations and the
 * runtime registry. Third-party relay sites continue to enter through the
 * CC Switch/Murong import flow instead of being presented as official APIs.
 */
object ProviderPresetCatalog {
    private val presets = listOf(
        preset(
            "openai-compatible", "OpenAI Compatible", ProviderPresetCategory.OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://platform.openai.com/docs",
            "https://platform.openai.com/api-keys", "https://api.openai.com",
            "gpt-5.6-sol",
            models("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.5", "gpt-5.3-codex", "gpt-5.2"),
            efforts("low", "medium", "high", "xhigh", "max"),
            visionModels = models("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.5", "gpt-5.2"),
            imageGenerationModels = models("gpt-image-2", "gpt-image-1.5", "gpt-image-1", "gpt-image-1-mini"),
            defaultWireFormat = ProviderWireFormat.RESPONSES,
            endpointCandidates = listOf("https://api.openai.com", "https://api.openai.com/v1"),
        ),
        preset(
            "claude", "Claude (Anthropic)", ProviderPresetCategory.OFFICIAL,
            ProviderApiProtocol.ANTHROPIC, "https://docs.anthropic.com",
            "https://console.anthropic.com/settings/keys", "https://api.anthropic.com",
            "claude-fable-5",
            models("claude-fable-5", "claude-opus-4-8", "claude-sonnet-5", "claude-opus-5", "claude-haiku-4-5-20251001"),
            efforts("low", "medium", "high", "xhigh", "max"),
            visionModels = models("claude-fable-5", "claude-opus-4-8", "claude-sonnet-5", "claude-opus-5", "claude-haiku-4-5-20251001"),
            defaultWireFormat = ProviderWireFormat.ANTHROPIC_MESSAGES,
        ),
        preset(
            "gemini", "Google Gemini", ProviderPresetCategory.OFFICIAL,
            ProviderApiProtocol.GEMINI, "https://ai.google.dev/gemini-api/docs",
            "https://aistudio.google.com/apikey", "https://generativelanguage.googleapis.com/v1beta",
            "gemini-3.6-flash",
            models("gemini-3.6-flash", "gemini-3-pro-preview", "gemini-3-flash-preview", "gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite"),
            visionModels = models("gemini-3.6-flash", "gemini-3-pro-preview", "gemini-3-flash-preview", "gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite"),
        ),
        preset(
            "deepseek", "DeepSeek", ProviderPresetCategory.CN_OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://platform.deepseek.com",
            "https://platform.deepseek.com/api_keys", "https://api.deepseek.com",
            "deepseek-v4-flash", models("deepseek-v4-flash", "deepseek-v4-pro", "deepseek-chat", "deepseek-reasoner"),
            efforts("low", "medium", "high", "max"),
            defaultWireFormat = ProviderWireFormat.RESPONSES,
            endpointCandidates = listOf("https://api.deepseek.com", "https://api.deepseek.com/v1"),
        ),
        preset(
            "kimi", "Kimi (Moonshot)", ProviderPresetCategory.CN_OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://platform.kimi.com",
            "https://platform.kimi.com/console/api-keys", "https://api.moonshot.cn/v1",
            "kimi-k2.7-code", models("kimi-k2.7-code", "kimi-k3", "kimi-k2.5", "moonshot-v1-128k"),
            efforts("on", "off"),
            visionModels = models("kimi-k2.7-code", "kimi-k3", "kimi-k2.5"),
        ),
        preset(
            "kimi-coding", "Kimi For Coding", ProviderPresetCategory.CN_OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://www.kimi.com/code",
            "https://www.kimi.com/code", "https://api.kimi.com/coding/v1",
            "kimi-for-coding", models("kimi-for-coding"), efforts("on", "off"),
        ),
        preset(
            "glm", "智谱 GLM", ProviderPresetCategory.CN_OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://open.bigmodel.cn",
            "https://open.bigmodel.cn/usercenter/apikeys", "https://open.bigmodel.cn/api/coding/paas/v4",
            "glm-5.2", models("glm-5.2", "glm-5.1", "glm-4.7", "glm-4.5-air"),
            efforts("low", "medium", "high", "xhigh", "max"),
            visionModels = models("glm-5v-turbo"),
        ),
        preset(
            "qwen", "阿里百炼 / Qwen", ProviderPresetCategory.CN_OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://bailian.console.aliyun.com",
            "https://bailian.console.aliyun.com/#/api-key", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "qwen3-coder-plus",
            models("qwen3-coder-plus", "qwen3.8-max-preview", "qwen3-coder-480b-a35b-instruct", "qwen3-max", "qwen-plus", "qwen-vl-max"),
            efforts("low", "medium", "xhigh"),
            visionModels = models("qwen-vl-max"),
        ),
        preset(
            "qianfan", "百度千帆 Coding Plan", ProviderPresetCategory.CN_OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://cloud.baidu.com/product/qianfan_modelbuilder",
            "https://console.bce.baidu.com/qianfan/ais/console/applicationConsole/application",
            "https://qianfan.baidubce.com/v2/coding", "qianfan-code-latest",
            models("qianfan-code-latest", "ernie-4.5-turbo-128k", "deepseek-v3.2"),
        ),
        preset(
            "volcengine-agentplan", "火山引擎 AgentPlan", ProviderPresetCategory.CN_OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://www.volcengine.com/activity/codingplan",
            "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey",
            "https://ark.cn-beijing.volces.com/api/coding/v3", "ark-code-latest",
            models("ark-code-latest"), efforts("low", "medium", "high"),
            defaultWireFormat = ProviderWireFormat.RESPONSES,
        ),
        preset(
            "byteplus", "BytePlus ModelArk", ProviderPresetCategory.OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://www.byteplus.com/en/product/modelark",
            "https://console.byteplus.com/ark/region:ark+ap-southeast-1/apiKey",
            "https://ark.ap-southeast.bytepluses.com/api/coding/v3", "ark-code-latest",
            models("ark-code-latest"), efforts("low", "medium", "high"),
        ),
        preset(
            "doubao", "豆包 Seed", ProviderPresetCategory.CN_OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://www.volcengine.com/product/doubao",
            "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey",
            "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-2-1-pro-260628",
            models("doubao-seed-2-1-pro-260628", "doubao-seed-1-8-251228", "doubao-seed-code-preview-latest"),
            defaultWireFormat = ProviderWireFormat.RESPONSES,
            supportedReasoningEfforts = efforts("low", "medium", "high"),
        ),
        preset(
            "stepfun", "阶跃星辰 StepFun", ProviderPresetCategory.CN_OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://platform.stepfun.com/step-plan",
            "https://platform.stepfun.com/interface-key", "https://api.stepfun.com/step_plan/v1",
            "step-3.7-flash", models("step-3.7-flash", "step-3.5-flash-2603", "step-3.5-flash"),
        ),
        preset(
            "longcat", "LongCat", ProviderPresetCategory.CN_OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://longcat.chat/platform",
            "https://longcat.chat/platform/api_keys", "https://api.longcat.chat/openai/v1",
            "LongCat-2.0", models("LongCat-2.0", "LongCat-Flash-Thinking-2601", "LongCat-Flash-Chat"),
        ),
        preset(
            "bailing", "蚂蚁百灵 BaiLing", ProviderPresetCategory.CN_OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://alipaytbox.yuque.com/sxs0ba/ling/get_started",
            "https://alipaytbox.yuque.com/sxs0ba/ling/get_started", "https://api.tbox.cn/v1",
            "Ling-2.5-1T", models("Ling-2.5-1T", "Ling-2.0-1T"),
        ),
        preset(
            "minimax", "MiniMax", ProviderPresetCategory.CN_OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://platform.minimaxi.com",
            "https://platform.minimaxi.com/user-center/basic-information/interface-key", "https://api.minimaxi.com/v1",
            "MiniMax-M2.7", models("MiniMax-M3", "MiniMax-M2.7", "MiniMax-M2.5", "MiniMax-Text-01"),
            visionModels = models("MiniMax-M3"),
        ),
        preset(
            "minimax-global", "MiniMax Global", ProviderPresetCategory.OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://platform.minimax.io",
            "https://platform.minimax.io/user-center/basic-information/interface-key", "https://api.minimax.io/v1",
            "MiniMax-M2.7", models("MiniMax-M2.7", "MiniMax-M2.5", "MiniMax-Text-01"),
        ),
        preset(
            "mimo", "小米 MiMo", ProviderPresetCategory.CN_OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://platform.xiaomimimo.com",
            "https://platform.xiaomimimo.com/#/console/api-keys", "https://api.xiaomimimo.com/v1/responses",
            "mimo-v2.5-pro", models("mimo-v2.5-pro", "mimo-v2.5", "mimo-v2-flash"),
            visionModels = models("mimo-v2.5"),
            defaultWireFormat = ProviderWireFormat.RESPONSES,
        ),
        preset(
            "mimo-token-plan", "小米 MiMo Token Plan", ProviderPresetCategory.CN_OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://platform.xiaomimimo.com/#/token-plan",
            "https://platform.xiaomimimo.com/#/console/plan-manage", "https://token-plan-cn.xiaomimimo.com/v1",
            "mimo-v2.5-pro", models("mimo-v2.5-pro", "mimo-v2.5"),
        ),
        preset(
            "hy3", "腾讯混元 Hy3", ProviderPresetCategory.CN_OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://cloud.tencent.com/product/tokenhub",
            "https://console.cloud.tencent.com/tokenhub/apikey", "https://tokenhub.tencentmaas.com/v1",
            "hy3", models("hy3", "hy3-preview", "hunyuan-turbos-latest"),
            defaultWireFormat = ProviderWireFormat.RESPONSES,
        ),
        preset(
            "grok", "xAI Grok", ProviderPresetCategory.OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://docs.x.ai",
            "https://console.x.ai", "https://api.x.ai/v1/responses",
            "grok-4.5", models("grok-4.5", "grok-4", "grok-4-fast-reasoning", "grok-4-fast-non-reasoning", "grok-code-fast-1"),
            efforts("low", "medium", "high"),
            defaultWireFormat = ProviderWireFormat.RESPONSES,
        ),
        preset(
            "modelscope-vision", "魔搭 ModelScope Vision", ProviderPresetCategory.CN_OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://modelscope.cn",
            "https://modelscope.cn/my/myaccesstoken", "https://api-inference.modelscope.cn/v1",
            "Qwen/Qwen3-VL-30B-A3B-Instruct",
            models("Qwen/Qwen3-VL-30B-A3B-Instruct", "Qwen/Qwen3-Coder-480B-A35B-Instruct", "ZhipuAI/GLM-5.1", "deepseek-ai/DeepSeek-V3.2"),
            visionModels = models("Qwen/Qwen3-VL-30B-A3B-Instruct"),
        ),
        preset(
            "nvidia-nim", "NVIDIA NIM", ProviderPresetCategory.OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://build.nvidia.com",
            "https://build.nvidia.com/settings/api-keys", "https://integrate.api.nvidia.com/v1",
            "moonshotai/kimi-k2.5",
            models("moonshotai/kimi-k2.5", "deepseek-ai/deepseek-v3.2", "qwen/qwen3-coder-480b-a35b-instruct", "meta/llama-4-maverick-17b-128e-instruct"),
        ),
        preset(
            "azure-openai", "Azure OpenAI", ProviderPresetCategory.OFFICIAL,
            ProviderApiProtocol.OPENAI_COMPATIBLE, "https://learn.microsoft.com/azure/ai-foundry/openai/",
            "https://portal.azure.com", "https://YOUR_RESOURCE.openai.azure.com/openai/v1",
            "gpt-5.6-sol", models("gpt-5.6-sol", "gpt-5.3-codex", "gpt-5.2"),
            efforts("low", "medium", "high", "xhigh"),
            defaultWireFormat = ProviderWireFormat.RESPONSES,
        ),
    )

    private val byId = presets.associateBy(ProviderPresetMetadata::providerId)

    fun get(providerId: String): ProviderPresetMetadata? = byId[providerId]

    fun all(): List<ProviderPresetMetadata> = presets

    private fun preset(
        providerId: String,
        displayName: String,
        category: ProviderPresetCategory,
        protocol: ProviderApiProtocol,
        websiteUrl: String,
        apiKeyUrl: String,
        defaultBaseUrl: String,
        defaultModel: String,
        recommendedModels: List<String>,
        supportedReasoningEfforts: List<String> = emptyList(),
        visionModels: List<String> = emptyList(),
        imageGenerationModels: List<String> = emptyList(),
        endpointCandidates: List<String> = listOf(defaultBaseUrl),
        defaultWireFormat: ProviderWireFormat = ProviderWireFormat.CHAT_COMPLETIONS,
    ) = ProviderPresetMetadata(
        providerId = providerId,
        displayName = displayName,
        category = category,
        protocol = protocol,
        websiteUrl = websiteUrl,
        apiKeyUrl = apiKeyUrl,
        defaultBaseUrl = defaultBaseUrl,
        defaultModel = defaultModel,
        recommendedModels = recommendedModels,
        visionModels = visionModels,
        imageGenerationModels = imageGenerationModels,
        defaultWireFormat = defaultWireFormat,
        supportedReasoningEfforts = supportedReasoningEfforts,
        endpointCandidates = endpointCandidates,
    )

    private fun models(vararg values: String): List<String> = values.toList()

    private fun efforts(vararg values: String): List<String> = values.toList()
}

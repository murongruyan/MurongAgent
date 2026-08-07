package main

// This list is deliberately limited to direct vendor/cloud APIs. Relay sites
// and aggregators are accepted through the CC Switch import flow, but are not
// presented as official provider presets.
type desktopProviderPreset struct {
	Name            string
	BaseURL         string
	Model           string
	APIMode         string
	ReasoningEffort string
}

var desktopProviderPresets = map[string]desktopProviderPreset{
	providerOpenAI:         {"OpenAI-compatible", "https://api.openai.com/v1", "gpt-5.6-sol", "auto", "high"},
	providerDeepSeek:       {"DeepSeek", "https://api.deepseek.com/v1", "deepseek-v4-flash", "responses", "high"},
	providerClaude:         {"Claude (Anthropic)", "https://api.anthropic.com", "claude-fable-5", "messages", "high"},
	providerGemini:         {"Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-3.6-flash", "gemini", ""},
	providerKimi:           {"Kimi (Moonshot)", "https://api.moonshot.cn/v1", "kimi-k2.7-code", "chat-completions", ""},
	"kimi-coding":          {"Kimi For Coding", "https://api.kimi.com/coding/v1", "kimi-for-coding", "chat-completions", ""},
	providerGLM:            {"智谱 GLM", "https://open.bigmodel.cn/api/coding/paas/v4", "glm-5.2", "chat-completions", "high"},
	providerQwen:           {"阿里百炼 / Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-coder-plus", "responses", "medium"},
	"qianfan":              {"百度千帆 Coding Plan", "https://qianfan.baidubce.com/v2/coding", "qianfan-code-latest", "chat-completions", ""},
	"volcengine-agentplan": {"火山引擎 AgentPlan", "https://ark.cn-beijing.volces.com/api/coding/v3", "ark-code-latest", "responses", "high"},
	"byteplus":             {"BytePlus ModelArk", "https://ark.ap-southeast.bytepluses.com/api/coding/v3", "ark-code-latest", "chat-completions", "high"},
	"doubao":               {"豆包 Seed", "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-2-1-pro-260628", "responses", "high"},
	"stepfun":              {"阶跃星辰 StepFun", "https://api.stepfun.com/step_plan/v1", "step-3.7-flash", "chat-completions", ""},
	"longcat":              {"LongCat", "https://api.longcat.chat/openai/v1", "LongCat-2.0", "chat-completions", ""},
	"bailing":              {"蚂蚁百灵 BaiLing", "https://api.tbox.cn/v1", "Ling-2.5-1T", "chat-completions", ""},
	providerMiniMax:        {"MiniMax", "https://api.minimaxi.com/v1", "MiniMax-M2.7", "chat-completions", ""},
	"minimax-global":       {"MiniMax Global", "https://api.minimax.io/v1", "MiniMax-M2.7", "chat-completions", ""},
	providerMiMo:           {"小米 MiMo", "https://api.xiaomimimo.com/v1", "mimo-v2.5-pro", "responses", ""},
	"mimo-token-plan":      {"小米 MiMo Token Plan", "https://token-plan-cn.xiaomimimo.com/v1", "mimo-v2.5-pro", "chat-completions", ""},
	providerHy3:            {"腾讯混元 Hy3", "https://tokenhub.tencentmaas.com/v1", "hy3", "responses", ""},
	providerGrok:           {"xAI Grok", "https://api.x.ai/v1", "grok-4.5", "responses", "medium"},
	"modelscope-vision":    {"魔搭 ModelScope", "https://api-inference.modelscope.cn/v1", "Qwen/Qwen3-VL-30B-A3B-Instruct", "chat-completions", ""},
	"nvidia-nim":           {"NVIDIA NIM", "https://integrate.api.nvidia.com/v1", "moonshotai/kimi-k2.5", "chat-completions", ""},
	"azure-openai":         {"Azure OpenAI", "https://YOUR_RESOURCE.openai.azure.com/openai/v1", "gpt-5.6-sol", "responses", "high"},
}

func isKnownDesktopProviderID(id string) bool {
	_, ok := desktopProviderPresets[id]
	return ok
}

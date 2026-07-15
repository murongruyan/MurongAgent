package com.murong.agent.core.tool

import com.murong.agent.core.loop.AskAnswerUi
import com.murong.agent.core.loop.AskOptionUi
import com.murong.agent.core.loop.AskQuestionUi
import com.murong.agent.core.loop.PendingAskRequestUi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AskUserTool(
    private val requestAnswer: suspend (PendingAskRequestUi) -> List<AskAnswerUi>?
) : Tool {

    override val name = "ask_user"
    override val description =
        "当你遇到必须由用户决定的分叉时，向用户发起 1-4 个结构化选择题。每题要有简短 header、完整问题、2-4 个选项；推荐项放在第一个。只有在无法从需求、代码或合理默认值中自行判断时才使用。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "questions" to mapOf(
                "type" to "array",
                "description" to "1-4 个需要用户确认的问题",
                "minItems" to 1,
                "maxItems" to 4,
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "header" to mapOf(
                            "type" to "string",
                            "description" to "问题短标题，适合做标签名"
                        ),
                        "question" to mapOf(
                            "type" to "string",
                            "description" to "完整问题文本"
                        ),
                        "options" to mapOf(
                            "type" to "array",
                            "minItems" to 2,
                            "maxItems" to 4,
                            "description" to "可选项列表，推荐项放第一个",
                            "items" to mapOf(
                                "type" to "object",
                                "properties" to mapOf(
                                    "label" to mapOf(
                                        "type" to "string",
                                        "description" to "选项文本"
                                    ),
                                    "description" to mapOf(
                                        "type" to "string",
                                        "description" to "选项说明，可选"
                                    )
                                ),
                                "required" to listOf("label")
                            )
                        ),
                        "multiSelect" to mapOf(
                            "type" to "boolean",
                            "description" to "是否允许多选"
                        )
                    ),
                    "required" to listOf("header", "question", "options")
                )
            )
        ),
        "required" to listOf("questions")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: String): String {
        val root = json.parseToJsonElement(args).jsonObject
        val questions = root["questions"]?.jsonArray?.mapIndexed { index, element ->
            val item = element.jsonObject
            val prompt = item["question"]?.jsonPrimitive?.content?.trim().orEmpty()
            val options = item["options"]?.jsonArray?.mapNotNull { optionElement ->
                val option = optionElement.jsonObject
                val label = option["label"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (label.isBlank()) return@mapNotNull null
                AskOptionUi(
                    label = label,
                    description = option["description"]?.jsonPrimitive?.content?.trim()?.ifBlank { null }
                )
            }.orEmpty()
            require(prompt.isNotBlank()) { "question ${index + 1}: question 不能为空" }
            require(options.size in 2..4) { "question ${index + 1}: options 数量必须在 2-4 之间" }
            AskQuestionUi(
                id = "q${index + 1}",
                header = item["header"]?.jsonPrimitive?.content?.trim().orEmpty(),
                question = prompt,
                options = options,
                multiSelect = item["multiSelect"]?.jsonPrimitive?.booleanOrNull == true
            )
        }.orEmpty()

        require(questions.isNotEmpty()) { "至少需要一个问题" }

        val answers = requestAnswer(
            PendingAskRequestUi(
                questions = questions
            )
        )
        if (answers.isNullOrEmpty()) {
            return "用户没有给出明确选择。请基于现有上下文继续，并明确说明你采用的默认假设。"
        }
        return formatAnswers(questions, answers)
    }

    private fun formatAnswers(
        questions: List<AskQuestionUi>,
        answers: List<AskAnswerUi>
    ): String {
        val answersByQuestion = answers.associateBy { it.questionId }
        return buildString {
            appendLine("用户已回答：")
            questions.forEach { question ->
                val selected = answersByQuestion[question.id]?.selectedOptions.orEmpty()
                append("- ")
                append(question.header.ifBlank { question.question })
                append(": ")
                append(
                    if (selected.isEmpty()) {
                        "未选择"
                    } else {
                        selected.joinToString("、")
                    }
                )
                appendLine()
            }
        }.trimEnd()
    }
}

package dev.reasonix.mobile.core.tool

import dev.reasonix.mobile.common.utils.RootFile
import kotlinx.serialization.json.*

/**
 * 代码编辑工具——读取、查看和修改代码文件
 */
class CodeEditTool : Tool {

    override val name = "code_edit"
    override val description = "查看、搜索和编辑代码文件。使用 SEARCH/REPLACE 块精确修改文件内容。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "operation" to mapOf(
                "type" to "string",
                "enum" to listOf("view", "search_replace", "create"),
                "description" to "操作：view=查看文件, search_replace=搜索替换, create=创建新文件"
            ),
            "path" to mapOf(
                "type" to "string",
                "description" to "文件绝对路径"
            ),
            "search" to mapOf(
                "type" to "string",
                "description" to "SEARCH/REPLACE 模式：要查找的精确文本（仅 search_replace 需要）"
            ),
            "replace" to mapOf(
                "type" to "string",
                "description" to "SEARCH/REPLACE 模式：替换后的文本（仅 search_replace 需要）"
            ),
            "content" to mapOf(
                "type" to "string",
                "description" to "新文件内容（仅 create 需要）"
            ),
            "startLine" to mapOf(
                "type" to "integer",
                "description" to "起始行号（1-indexed），view 时可选"
            ),
            "endLine" to mapOf(
                "type" to "integer",
                "description" to "结束行号，view 时可选"
            )
        ),
        "required" to listOf("operation", "path")
    )

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val op = obj["operation"]?.jsonPrimitive?.content ?: return null
            val path = obj["path"]?.jsonPrimitive?.content ?: return null

            when (op) {
                "search_replace" -> ToolApprovalRequest(
                    toolName = name,
                    summary = "修改代码文件",
                    detail = path,
                    riskLevel = ApprovalRiskLevel.HIGH,
                    rawArgs = args,
                    pathBoundaryValue = path
                )

                "create" -> ToolApprovalRequest(
                    toolName = name,
                    summary = "创建新文件",
                    detail = path,
                    riskLevel = ApprovalRiskLevel.HIGH,
                    rawArgs = args,
                    pathBoundaryValue = path
                )

                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun execute(args: String): String {
        return executeWithResult(args).output
    }

    override suspend fun executeWithResult(args: String): ToolExecutionResult {
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val op = obj["operation"]?.jsonPrimitive?.content
                ?: return ToolExecutionResult("Error: 'operation' required")
            val path = obj["path"]?.jsonPrimitive?.content
                ?: return ToolExecutionResult("Error: 'path' required")

            when (op) {
                "view" -> {
                    if (!RootFile.exists(path)) {
                        return ToolExecutionResult("File not found: $path")
                    }
                    val content = RootFile.readFile(path)
                    if (content.startsWith("error:")) return ToolExecutionResult("Error reading $path")

                    val startLine = obj["startLine"]?.jsonPrimitive?.intOrNull
                    val endLine = obj["endLine"]?.jsonPrimitive?.intOrNull

                    if (startLine != null || endLine != null) {
                        val lines = content.lines()
                        val s = (startLine ?: 1).coerceAtLeast(1) - 1
                        val e = (endLine ?: lines.size).coerceAtMost(lines.size)
                        if (s >= lines.size || s >= e) {
                            return ToolExecutionResult(
                                "Error: invalid line range for $path (start=${startLine ?: 1}, end=${endLine ?: lines.size})"
                            )
                        }
                        ToolExecutionResult(lines.subList(s, e).joinToString("\n"))
                    } else {
                        ToolExecutionResult(content)
                    }
                }
                "search_replace" -> {
                    val search = obj["search"]?.jsonPrimitive?.content
                        ?: return ToolExecutionResult("Error: 'search' required for search_replace")
                    val replace = obj["replace"]?.jsonPrimitive?.content
                        ?: return ToolExecutionResult("Error: 'replace' required for search_replace")
                    if (search.isEmpty()) {
                        return ToolExecutionResult("Error: 'search' cannot be empty for search_replace")
                    }

                    if (!RootFile.exists(path)) {
                        return ToolExecutionResult("File not found: $path")
                    }

                    val currentContent = RootFile.readFile(path)
                    if (currentContent.startsWith("error:")) {
                        return ToolExecutionResult("Error reading $path: $currentContent")
                    }
                    if (!currentContent.contains(search)) {
                        return ToolExecutionResult("Error: SEARCH text not found in $path")
                    }

                    // 统计出现次数
                    val count = currentContent.windowed(search.length).count { it == search }
                    if (count > 1) {
                        return ToolExecutionResult(
                            "Error: SEARCH text found $count times in $path — must be unique"
                        )
                    }

                    val newContent = currentContent.replace(search, replace)
                    val result = RootFile.writeFileChecked(path, newContent)
                    if (result.success) {
                        ToolExecutionResult(
                            output = "Applied SEARCH/REPLACE to $path",
                            fileChanges = listOf(
                                ToolFileChange(
                                    path = path,
                                    operation = "search_replace",
                                    beforeContent = currentContent,
                                    afterContent = newContent,
                                    diffPreview = buildDiffPreview(currentContent, newContent)
                                )
                            )
                        )
                    } else {
                        ToolExecutionResult("Error writing updated content to $path: ${result.error ?: result.output}")
                    }
                }
                "create" -> {
                    val content = obj["content"]?.jsonPrimitive?.content
                        ?: return ToolExecutionResult("Error: 'content' required for create")
                    if (RootFile.exists(path)) {
                        return ToolExecutionResult("File already exists: $path. Use search_replace to modify.")
                    }
                    val result = RootFile.writeFileChecked(path, content)
                    if (result.success) {
                        ToolExecutionResult(
                            output = "Created new file: $path",
                            fileChanges = listOf(
                                ToolFileChange(
                                    path = path,
                                    operation = "create",
                                    beforeContent = null,
                                    afterContent = content,
                                    diffPreview = buildDiffPreview(null, content)
                                )
                            )
                        )
                    } else {
                        ToolExecutionResult("Error creating file $path: ${result.error ?: result.output}")
                    }
                }
                else -> ToolExecutionResult("Unknown operation: $op")
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error in code edit: ${e.message}")
        }
    }
}

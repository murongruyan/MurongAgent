package com.murong.agent.core.tool

import com.murong.agent.common.utils.RootFile
import kotlinx.serialization.json.*

/**
 * 文件系统工具——通过 Root 访问设备文件
 */
class FileTool(
    private val allowedOperations: Set<String> = setOf("read", "write", "list", "delete", "exists", "chmod")
) : Tool {

    override val name = "file"
    override val description = if (allowedOperations == setOf("read", "list", "exists")) {
        "只读文件工具。读取文件内容、列出目录、检查文件是否存在。适合安全的只读探索任务。已知文件名但还不知道精确路径时，先用 list 在 src/main、src/test、app/src、core/src、common/src 等源码目录逐步缩小范围；读取内容再用 read，比 shell cat 更可靠。"
    } else {
        "文件系统工具。读取、写入、列出或删除 Android 设备上的文件。路径可以是任何位置（需要 root）。已知文件名但还不知道精确路径时，先用 list 在源码目录逐步定位，再用 read 读取内容；读取文件比 shell cat 更可靠，写入用 write，列目录用 list，删除用 delete，检查存在用 exists。"
    }
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "operation" to mapOf(
                "type" to "string",
                "enum" to allowedOperations.toList(),
                "description" to "操作类型：read=读取文件内容，write=写入文件，list=列出目录，delete=删除，exists=检查存在，chmod=修改权限"
            ),
            "path" to mapOf(
                "type" to "string",
                "description" to "文件或目录的绝对路径"
            ),
            "content" to mapOf(
                "type" to "string",
                "description" to "写入操作时的文件内容（仅 write 操作需要）"
            ),
            "offset" to mapOf(
                "type" to "integer",
                "description" to "读取或列目录时的起始偏移。read 按行偏移，list 按条目偏移，默认 0"
            ),
            "limit" to mapOf(
                "type" to "integer",
                "description" to "读取或列目录时的返回上限。read 默认 80 行，list 默认 200 项"
            ),
            "mode" to mapOf(
                "type" to "string",
                "description" to "权限模式，如 755（仅 chmod 操作需要）"
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
            if (op !in allowedOperations) return null

            when (op) {
                "write" -> ToolApprovalRequest(
                    toolName = name,
                    summary = "写入文件",
                    detail = path,
                    riskLevel = ApprovalRiskLevel.HIGH,
                    rawArgs = args,
                    pathBoundaryValue = path
                )

                "delete" -> ToolApprovalRequest(
                    toolName = name,
                    summary = "删除文件或目录",
                    detail = path,
                    riskLevel = ApprovalRiskLevel.HIGH,
                    rawArgs = args,
                    pathBoundaryValue = path
                )

                "chmod" -> ToolApprovalRequest(
                    toolName = name,
                    summary = "修改文件权限",
                    detail = path,
                    riskLevel = ApprovalRiskLevel.MEDIUM,
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
            val offset = obj["offset"]?.jsonPrimitive?.intOrNull ?: 0
            val limit = obj["limit"]?.jsonPrimitive?.intOrNull
            if (op !in allowedOperations) {
                return ToolExecutionResult(
                    "Error: Operation '$op' is not allowed. Supported: ${allowedOperations.joinToString(", ")}"
                )
            }

            when (op) {
                "read" -> {
                    val readResult = RootFile.readFilePaged(
                        path = path,
                        offsetLines = offset,
                        lineLimit = limit ?: DEFAULT_READ_LIMIT
                    )
                    when (readResult.status) {
                        RootFile.ReadStatus.NOT_FOUND -> {
                            ToolExecutionResult("Error: File or directory not found: $path")
                        }
                        RootFile.ReadStatus.IS_DIRECTORY -> {
                            ToolExecutionResult("Error: '$path' is a directory. Use operation 'list' instead.")
                        }
                        RootFile.ReadStatus.ERROR -> {
                            ToolExecutionResult("Error reading file: ${readResult.content}")
                        }
                        RootFile.ReadStatus.OK -> {
                            ToolExecutionResult(
                                formatReadOutput(path, offset, limit ?: DEFAULT_READ_LIMIT, readResult)
                            )
                        }
                    }
                }
                "write" -> {
                    val content = obj["content"]?.jsonPrimitive?.content
                        ?: return ToolExecutionResult("Error: 'content' required for write operation")
                    val beforeContent = if (RootFile.exists(path)) RootFile.readFile(path).takeIf { !it.startsWith("error:") } else null
                    val result = RootFile.writeFileChecked(path, content)
                    if (result.success) {
                        val afterContent = RootFile.readFile(path).takeIf { !it.startsWith("error:") } ?: content
                        ToolExecutionResult(
                            output = "File written successfully: $path",
                            fileChanges = listOf(
                                ToolFileChange(
                                    path = path,
                                    operation = if (beforeContent == null) "create" else "write",
                                    beforeContent = beforeContent,
                                    afterContent = afterContent,
                                    diffPreview = buildDiffPreview(beforeContent, afterContent)
                                )
                            )
                        )
                    } else {
                        ToolExecutionResult("Error writing file: ${result.error ?: result.output}")
                    }
                }
                "list" -> {
                    if (!RootFile.dirExists(path)) {
                        return ToolExecutionResult("Error: Directory not found: $path")
                    }
                    val listResult = RootFile.lsPaged(
                        path = path,
                        offset = offset,
                        limit = limit ?: DEFAULT_LIST_LIMIT
                    )
                    if (listResult.entries.isEmpty()) ToolExecutionResult("(empty directory)")
                    else ToolExecutionResult(formatListOutput(path, offset, limit ?: DEFAULT_LIST_LIMIT, listResult))
                }
                "delete" -> {
                    val beforeContent = if (RootFile.exists(path)) RootFile.readFile(path).takeIf { !it.startsWith("error:") } else null
                    val result = RootFile.deleteChecked(path)
                    if (result.success) {
                        ToolExecutionResult(
                            output = "Deleted: $path",
                            fileChanges = listOf(
                                ToolFileChange(
                                    path = path,
                                    operation = "delete",
                                    beforeContent = beforeContent,
                                    afterContent = null,
                                    diffPreview = buildDiffPreview(beforeContent, null)
                                )
                            )
                        )
                    } else {
                        ToolExecutionResult("Error deleting target: ${result.error ?: result.output}")
                    }
                }
                "exists" -> {
                    val exists = RootFile.exists(path)
                    if (exists) ToolExecutionResult("File exists: $path")
                    else ToolExecutionResult("File does not exist: $path")
                }
                "chmod" -> {
                    val mode = obj["mode"]?.jsonPrimitive?.content ?: "644"
                    val result = RootFile.chmodChecked(path, mode)
                    if (result.success) ToolExecutionResult("Permission changed: $path → $mode")
                    else ToolExecutionResult("Error changing permission: ${result.error ?: result.output}")
                }
                else -> ToolExecutionResult("Unknown operation: $op. Supported: ${allowedOperations.joinToString(", ")}")
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error in file operation: ${e.message}")
        }
    }

    private fun formatReadOutput(
        path: String,
        offset: Int,
        limit: Int,
        result: RootFile.ReadResult
    ): String {
        val startLine = offset.coerceAtLeast(0) + 1
        val endLine = startLine + maxOf(result.content.lineSequence().count(), 1) - 1
        return buildString {
            append("File: $path\n")
            append("Lines: $startLine-$endLine")
            if (result.truncated) {
                append(" (partial)")
            }
            append("\n\n")
            append(result.content.ifBlank { "(empty file)" })
            if (result.truncated) {
                append("\n\n...(已截断，继续读取可传 offset=")
                append(offset + limit.coerceAtLeast(1))
                append(")")
            }
        }
    }

    private fun formatListOutput(
        path: String,
        offset: Int,
        limit: Int,
        result: RootFile.ListResult
    ): String {
        val startIndex = offset.coerceAtLeast(0) + 1
        val endIndex = offset.coerceAtLeast(0) + result.entries.size
        return buildString {
            append("Directory: $path\n")
            append("Entries: $startIndex-$endIndex")
            if (result.truncated) {
                append(" (partial)")
            }
            append("\n\n")
            append(result.entries.joinToString("\n"))
            if (result.truncated) {
                append("\n\n...(目录结果过多，继续查看可传 offset=")
                append(offset + limit.coerceAtLeast(1))
                append(")")
            }
        }
    }

    private companion object {
        const val DEFAULT_READ_LIMIT = 80
        const val DEFAULT_LIST_LIMIT = 200
    }
}

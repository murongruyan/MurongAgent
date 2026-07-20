package com.murong.agent.core.workspace

/** A short-lived computer-node directory capability. Absolute computer paths are never exposed. */
data class ComputerWorkspaceDescriptor(
    val sessionId: String,
    val label: String,
    val readable: Boolean,
    val writable: Boolean,
    val platform: String = "windows",
    val architecture: String = "",
    val terminal: Boolean = false,
    val terminalBackends: List<ComputerTerminalDescriptor> = emptyList()
) {
    val effectiveTerminalBackends: List<ComputerTerminalDescriptor>
        get() = terminalBackends.ifEmpty {
            if (terminal) {
                if (platform == "windows") {
                    listOf(ComputerTerminalDescriptor("windows-powershell", "Windows PowerShell", ""))
                } else {
                    listOf(ComputerTerminalDescriptor("sh", "POSIX Shell", ""))
                }
            } else {
                emptyList()
            }
        }
}

data class ComputerTerminalDescriptor(
    val id: String,
    val label: String,
    val version: String = ""
)

enum class ComputerWorkspaceOperation {
    LIST,
    READ,
    STAT,
    WRITE,
    MKDIR,
    RUN
}

data class ComputerWorkspaceRequest(
    val operation: ComputerWorkspaceOperation,
    val relativePath: String,
    val content: String? = null,
    val expectedSha256: String? = null,
    val command: String? = null,
    val terminalId: String? = null,
    val timeoutMillis: Long? = null,
    val maxBytes: Int = DEFAULT_COMPUTER_WORKSPACE_MAX_TEXT_BYTES,
    val maxEntries: Int = DEFAULT_COMPUTER_WORKSPACE_MAX_DIRECTORY_ENTRIES
)

data class ComputerWorkspaceEntry(
    val name: String,
    val relativePath: String,
    val directory: Boolean,
    val size: Long? = null,
    val lastModified: Long? = null
)

data class ComputerWorkspaceResponse(
    val success: Boolean,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val entries: List<ComputerWorkspaceEntry> = emptyList(),
    val content: String? = null,
    val sha256: String? = null,
    val size: Long? = null,
    val lastModified: Long? = null,
    val directory: Boolean? = null,
    val created: Boolean = false,
    val diffPreview: String? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    val exitCode: Int? = null,
    val timedOut: Boolean = false
)

data class ComputerWorkspaceChangeBatch(
    val opaqueId: String,
    val attachment: String
)

/**
 * The core only knows a capability gateway. The computer node retains the actual root path and
 * resolves every relative path below that root.
 */
interface ComputerWorkspaceGateway {
    fun activeWorkspace(): ComputerWorkspaceDescriptor?

    suspend fun execute(request: ComputerWorkspaceRequest): ComputerWorkspaceResponse

    fun prepareExternalChanges(): ComputerWorkspaceChangeBatch? = null

    fun acknowledgeExternalChanges(batch: ComputerWorkspaceChangeBatch) = Unit
}

object UnavailableComputerWorkspaceGateway : ComputerWorkspaceGateway {
    override fun activeWorkspace(): ComputerWorkspaceDescriptor? = null

    override suspend fun execute(request: ComputerWorkspaceRequest): ComputerWorkspaceResponse =
        ComputerWorkspaceResponse(
            success = false,
            errorCode = "workspace_unavailable",
            errorMessage = "电脑工作区未连接"
        )
}

const val DEFAULT_COMPUTER_WORKSPACE_MAX_TEXT_BYTES = 1024 * 1024
const val DEFAULT_COMPUTER_WORKSPACE_MAX_DIRECTORY_ENTRIES = 500

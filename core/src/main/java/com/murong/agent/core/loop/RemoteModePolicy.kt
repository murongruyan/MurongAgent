package com.murong.agent.core.loop

import kotlinx.serialization.Serializable

@Serializable
enum class WorkspaceMode {
    REMOTE_PREFERRED,
    HYBRID,
    LOCAL_ONLY
}

internal fun hasRemoteTaskRepositoryContext(state: SessionState): Boolean {
    val owner = state.remoteTaskRepositoryOwner?.trim().orEmpty()
    val repo = state.remoteTaskRepositoryName?.trim().orEmpty()
    return owner.isNotBlank() && repo.isNotBlank()
}

internal fun hasLocalProjectContext(state: SessionState): Boolean {
    return !state.projectPath.isNullOrBlank()
}

fun resolveWorkspaceMode(state: SessionState): WorkspaceMode {
    if (!hasRemoteTaskRepositoryContext(state)) return WorkspaceMode.LOCAL_ONLY
    return when (state.workspaceMode) {
        WorkspaceMode.REMOTE_PREFERRED -> WorkspaceMode.REMOTE_PREFERRED
        WorkspaceMode.HYBRID -> if (hasLocalProjectContext(state)) WorkspaceMode.HYBRID else WorkspaceMode.REMOTE_PREFERRED
        WorkspaceMode.LOCAL_ONLY -> if (hasLocalProjectContext(state)) WorkspaceMode.LOCAL_ONLY else WorkspaceMode.REMOTE_PREFERRED
    }
}

/**
 * 远端模式下是否应暴露本地 [shell] 工具。
 * shell 在远端模式下仍然保留（可通过审批系统管控风险），
 * 因为用户可能需要执行本地 shell 命令做环境检查、构建或诊断。
 */
internal fun shouldExposeLocalShellTool(state: SessionState): Boolean {
    // shell 保留，通过 isEnabled + allowWriteTools + 审批系统来控制风险
    return true
}

/**
 * 远端模式下是否应暴露本地 [file] 工具的读取操作（read/list/exists）。
 * 读取本地文件在远端模式下仍然有用，例如查看本地项目配置、对比远端代码。
 */
internal fun shouldExposeLocalFileReadTool(state: SessionState): Boolean {
    return true
}

/**
 * 远端模式下是否应暴露本地 [file] 工具的写入操作（write/delete/chmod）。
 * 远端模式下应避免写入本地文件，除非用户显式切回本地项目。
 */
internal fun shouldExposeLocalFileWriteTool(state: SessionState): Boolean {
    return !hasRemoteTaskRepositoryContext(state) ||
        resolveWorkspaceMode(state) != WorkspaceMode.REMOTE_PREFERRED
}

/**
 * 远端模式下是否应暴露本地 [code_search] 工具。
 * 代码搜索仅读取本地索引，在远端模式下仍有助理解本地代码结构。
 */
internal fun shouldExposeLocalCodeSearchTool(state: SessionState): Boolean {
    return true
}

/**
 * 远端模式下是否应暴露本地 [code_edit] 工具。
 * 远端模式下应避免直接修改本地文件，优先使用 task_repo_* 编辑远端仓库。
 */
internal fun shouldExposeLocalCodeEditTool(state: SessionState): Boolean {
    return !hasRemoteTaskRepositoryContext(state) ||
        resolveWorkspaceMode(state) != WorkspaceMode.REMOTE_PREFERRED
}

/**
 * 兼容旧函数——判断是否完全隐藏所有本地工具（旧行为）。
 */
internal fun shouldExposeLocalProjectTools(state: SessionState): Boolean {
    return !hasRemoteTaskRepositoryContext(state) ||
        resolveWorkspaceMode(state) != WorkspaceMode.REMOTE_PREFERRED
}

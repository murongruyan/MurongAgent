package com.murong.agent.core.loop

internal fun hasRemoteTaskRepositoryContext(state: SessionState): Boolean {
    val owner = state.remoteTaskRepositoryOwner?.trim().orEmpty()
    val repo = state.remoteTaskRepositoryName?.trim().orEmpty()
    return owner.isNotBlank() && repo.isNotBlank()
}

internal fun shouldExposeLocalProjectTools(state: SessionState): Boolean {
    return !hasRemoteTaskRepositoryContext(state)
}

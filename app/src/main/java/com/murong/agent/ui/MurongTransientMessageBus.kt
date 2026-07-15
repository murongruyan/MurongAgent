package com.murong.agent.ui

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object MurongTransientMessageBus {
    private val _messages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val messages = _messages.asSharedFlow()

    fun show(message: String) {
        val normalized = message.trim()
        if (normalized.isNotBlank()) {
            _messages.tryEmit(normalized)
        }
    }
}

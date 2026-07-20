package com.murong.agent.lan

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

data class LanWebEvent(
    val id: Long,
    val type: String,
    val jsonData: String,
    val targetClientId: String? = null
) {
    fun isVisibleTo(clientId: String): Boolean = targetClientId == null || targetClientId == clientId
}

class LanWebEventHub(
    private val maxReplayEvents: Int = 256
) {
    private val sequence = AtomicLong(0L)
    private val replayLock = Any()
    private val replayBuffer = ArrayDeque<LanWebEvent>()
    private val mutableEvents = MutableSharedFlow<LanWebEvent>(
        extraBufferCapacity = maxReplayEvents,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<LanWebEvent> = mutableEvents

    fun publish(
        type: String,
        jsonData: String,
        targetClientId: String? = null,
        replayable: Boolean = true
    ): LanWebEvent {
        val event = LanWebEvent(
            id = sequence.incrementAndGet(),
            type = type,
            jsonData = jsonData,
            targetClientId = targetClientId
        )
        if (replayable) {
            synchronized(replayLock) {
                replayBuffer.addLast(event)
                while (replayBuffer.size > maxReplayEvents) replayBuffer.removeFirst()
            }
        }
        mutableEvents.tryEmit(event)
        return event
    }

    fun replayAfter(lastEventId: Long?, clientId: String? = null): List<LanWebEvent>? = synchronized(replayLock) {
        if (lastEventId == null) return@synchronized emptyList()
        val firstId = replayBuffer.firstOrNull()?.id ?: return@synchronized emptyList()
        if (lastEventId < firstId - 1) return@synchronized null
        replayBuffer.filter { event ->
            event.id > lastEventId && (clientId == null || event.isVisibleTo(clientId))
        }
    }

    fun currentSequence(): Long = sequence.get()
}

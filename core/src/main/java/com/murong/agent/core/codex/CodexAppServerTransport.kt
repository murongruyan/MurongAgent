package com.murong.agent.core.codex

import com.murong.agent.common.process.PersistentJsonLineProcess
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Minimal process-independent transport contract used by [CodexAppServerClient]. */
interface CodexAppServerTransport {
    val messages: Flow<JsonElement>
    val stderrLines: Flow<String>
    val protocolViolations: Flow<CodexTransportProtocolViolation>
    val ioFailures: Flow<CodexTransportIoFailure>
    val exits: Flow<CodexTransportExit>

    fun start()
    suspend fun request(message: JsonObject): JsonElement
    suspend fun send(message: JsonObject)
    suspend fun close()
    fun kill()
}

fun interface CodexAppServerTransportFactory {
    fun create(): CodexAppServerTransport
}

data class CodexTransportProtocolViolation(
    val rawLine: String,
    val description: String,
)

data class CodexTransportIoFailure(
    val stream: String,
    val description: String,
)

data class CodexTransportExit(
    val exitCode: Int?,
    val expected: Boolean,
)

/** Production adapter around the shared long-lived JSONL process primitive. */
internal class PersistentCodexAppServerTransport(
    private val process: PersistentJsonLineProcess,
    private val onProcessStopped: () -> Unit = {},
) : CodexAppServerTransport {
    override val messages: Flow<JsonElement> = process.stdoutLines.map { it.value }
    override val stderrLines: Flow<String> = process.stderrLines
    override val protocolViolations: Flow<CodexTransportProtocolViolation> =
        process.stdoutViolations.map { violation ->
            CodexTransportProtocolViolation(
                rawLine = violation.rawLine,
                description = violation.cause.message.orEmpty(),
            )
        }
    override val ioFailures: Flow<CodexTransportIoFailure> = process.ioFailures.map { failure ->
        CodexTransportIoFailure(
            stream = failure.stream.name.lowercase(),
            description = failure.cause.message.orEmpty(),
        )
    }
    override val exits: Flow<CodexTransportExit> = process.exitEvents.map { exit ->
        CodexTransportExit(exitCode = exit.exitCode, expected = exit.expected)
    }

    override fun start() {
        try {
            process.start()
            Thread(
                {
                    runBlocking { process.awaitExit() }
                    onProcessStopped()
                },
                "codex-app-server-exit-watcher",
            ).apply {
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            onProcessStopped()
            throw error
        }
    }

    override suspend fun request(message: JsonObject): JsonElement = process.request(message)

    override suspend fun send(message: JsonObject) = process.sendJsonLine(message)

    override suspend fun close() {
        try {
            runCatching { process.closeAndAwait() }
                .getOrElse { error ->
                    process.kill()
                    throw IOException("Failed to stop Codex app-server process", error)
                }
        } finally {
            onProcessStopped()
        }
    }

    override fun kill() {
        try {
            process.kill()
        } finally {
            onProcessStopped()
        }
    }
}

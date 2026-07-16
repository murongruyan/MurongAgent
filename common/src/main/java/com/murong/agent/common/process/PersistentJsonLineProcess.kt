package com.murong.agent.common.process

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Long-lived, bidirectional JSONL transport for a single child process.
 *
 * The caller owns the supplied [ProcessBuilder]. This class always disables
 * `redirectErrorStream` before launch so stderr can never contaminate the
 * stdout protocol. Valid stdout JSON lines, stderr lines and invalid stdout
 * lines are delivered through separate, buffered flows.
 *
 * [stdoutLines], [stderrLines], [stdoutViolations] and [ioFailures] are queue
 * backed flows: each stream should have one owning collector. This avoids
 * losing protocol messages when a collector is briefly busy or is attached
 * immediately after the process starts.
 */
class PersistentJsonLineProcess(
    private val processBuilder: ProcessBuilder,
    private val processLauncher: (ProcessBuilder) -> Process = { it.start() },
    private val json: Json = Json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    enum class State {
        NEW,
        RUNNING,
        STOPPING,
        EXITED
    }

    enum class Stream {
        STDOUT,
        STDERR
    }

    data class StdoutJsonLine(
        val rawLine: String,
        val value: JsonElement
    )

    data class StdoutProtocolViolation(
        val rawLine: String,
        val cause: Throwable
    )

    data class StreamIoFailure(
        val stream: Stream,
        val cause: Throwable
    )

    data class ProcessExit(
        val exitCode: Int?,
        val expected: Boolean
    )

    private val lifecycleLock = Any()
    private val stdinLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val expectedTermination = AtomicBoolean(false)
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()

    private val stdoutChannel = Channel<StdoutJsonLine>(Channel.UNLIMITED)
    private val stderrChannel = Channel<String>(Channel.UNLIMITED)
    private val violationChannel = Channel<StdoutProtocolViolation>(Channel.UNLIMITED)
    private val ioFailureChannel = Channel<StreamIoFailure>(Channel.UNLIMITED)
    private val exitDeferred = CompletableDeferred<ProcessExit>()
    private val mutableExitEvents = MutableSharedFlow<ProcessExit>(replay = 1)
    private val mutableState = MutableStateFlow(State.NEW)

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    val state: StateFlow<State> = mutableState.asStateFlow()
    val stdoutLines: Flow<StdoutJsonLine> = stdoutChannel.receiveAsFlow()
    val stderrLines: Flow<String> = stderrChannel.receiveAsFlow()
    val stdoutViolations: Flow<StdoutProtocolViolation> = violationChannel.receiveAsFlow()
    val ioFailures: Flow<StreamIoFailure> = ioFailureChannel.receiveAsFlow()
    val exitEvents: SharedFlow<ProcessExit> = mutableExitEvents.asSharedFlow()

    val pendingRequestCount: Int
        get() = pendingRequests.size

    /** Starts the injected builder exactly once. A failed launch may be retried. */
    fun start() {
        synchronized(lifecycleLock) {
            check(mutableState.value == State.NEW) {
                "Process can only be started from NEW state (was ${mutableState.value})"
            }

            // Protocol stdout must never contain stderr diagnostics.
            processBuilder.redirectErrorStream(false)
            val launchedProcess = processLauncher(processBuilder)
            val launchedWriter = BufferedWriter(
                OutputStreamWriter(launchedProcess.outputStream, Charsets.UTF_8)
            )
            process = launchedProcess
            writer = launchedWriter
            mutableState.value = State.RUNNING

            val stdoutJob = launchReader(
                stream = Stream.STDOUT,
                input = launchedProcess.inputStream,
                onLine = ::handleStdoutLine
            )
            val stderrJob = launchReader(
                stream = Stream.STDERR,
                input = launchedProcess.errorStream,
                onLine = { stderrChannel.send(it) }
            )
            scope.launch {
                val exitCode = waitForExit(launchedProcess)
                stdoutJob.join()
                stderrJob.join()
                finishExit(ProcessExit(exitCode, expectedTermination.get()))
            }
        }
    }

    /** Serializes one JSON value as one flushed UTF-8 line on child stdin. */
    suspend fun sendJsonLine(value: JsonElement) {
        val encoded = json.encodeToString(JsonElement.serializer(), value)
        withContext(ioDispatcher) {
            synchronized(stdinLock) {
                ensureRunning()
                val activeWriter = writer
                    ?: throw IOException("Child process stdin is not available")
                activeWriter.write(encoded)
                activeWriter.newLine()
                activeWriter.flush()
            }
        }
    }

    /**
     * Registers a JSON request before writing it and resolves it when stdout
     * produces an object with the same string or numeric `id`.
     *
     * Callers may wrap this method in `withTimeout`. Cancellation removes the
     * request from the pending map without affecting other concurrent calls.
     */
    suspend fun request(request: JsonObject): JsonElement {
        val id = request["id"]
            ?: throw IllegalArgumentException("JSONL request must contain an id")
        val requestKey = requestKey(id)
        val response = CompletableDeferred<JsonElement>()
        check(pendingRequests.putIfAbsent(requestKey, response) == null) {
            "A request with id $id is already pending"
        }

        try {
            sendJsonLine(request)
            return response.await()
        } finally {
            pendingRequests.remove(requestKey, response)
        }
    }

    suspend fun awaitExit(): ProcessExit = exitDeferred.await()

    /** Closes stdin and requests normal process termination without blocking. */
    fun close() {
        val activeProcess = markStopping()
        closeStdin()
        if (activeProcess == null) {
            finishExit(ProcessExit(exitCode = null, expected = true))
        } else {
            runCatching { activeProcess.destroy() }
        }
    }

    /** Closes stdin and immediately requests forcible process termination. */
    fun kill() {
        val activeProcess = markStopping()
        closeStdin()
        if (activeProcess == null) {
            finishExit(ProcessExit(exitCode = null, expected = true))
        } else {
            runCatching { activeProcess.destroyForcibly() }
        }
    }

    /**
     * Requests a normal close, then escalates to [kill] after [gracePeriodMillis].
     */
    suspend fun closeAndAwait(
        gracePeriodMillis: Long = DEFAULT_GRACE_PERIOD_MILLIS,
        forceKillWaitMillis: Long = DEFAULT_FORCE_KILL_WAIT_MILLIS
    ): ProcessExit {
        require(gracePeriodMillis >= 0L) { "gracePeriodMillis must not be negative" }
        require(forceKillWaitMillis > 0L) { "forceKillWaitMillis must be positive" }

        close()
        withTimeoutOrNull(gracePeriodMillis) { awaitExit() }?.let { return it }
        kill()
        return withTimeout(forceKillWaitMillis) { awaitExit() }
    }

    private fun launchReader(
        stream: Stream,
        input: InputStream,
        onLine: suspend (String) -> Unit
    ): Job = scope.launch {
        try {
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    onLine(line)
                }
            }
        } catch (error: IOException) {
            if (mutableState.value == State.RUNNING) {
                ioFailureChannel.send(StreamIoFailure(stream, error))
                if (stream == Stream.STDOUT) {
                    runCatching { process?.destroyForcibly() }
                }
            }
        }
    }

    private suspend fun handleStdoutLine(line: String) {
        if (!looksLikeStrictJson(line)) {
            violationChannel.send(
                StdoutProtocolViolation(
                    rawLine = line,
                    cause = IllegalArgumentException("stdout line is not strict JSON")
                )
            )
            return
        }
        val parsed = runCatching { json.parseToJsonElement(line) }
            .getOrElse { error ->
                violationChannel.send(StdoutProtocolViolation(line, error))
                return
            }

        responseKey(parsed)?.let { key ->
            pendingRequests.remove(key)?.complete(parsed)
        }
        stdoutChannel.send(StdoutJsonLine(line, parsed))
    }

    private fun looksLikeStrictJson(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        return when (trimmed.first()) {
            '{', '[', '"' -> true
            't' -> trimmed == "true"
            'f' -> trimmed == "false"
            'n' -> trimmed == "null"
            else -> STRICT_JSON_NUMBER.matches(trimmed)
        }
    }

    private fun responseKey(value: JsonElement): String? {
        val id = (value as? JsonObject)?.get("id") ?: return null
        return runCatching { requestKey(id) }.getOrNull()
    }

    private fun requestKey(id: JsonElement): String {
        require(
            id is JsonPrimitive &&
                id !is JsonNull &&
                (id.isString || STRICT_JSON_NUMBER.matches(id.content))
        ) {
            "JSONL request id must be a string or number"
        }
        return json.encodeToString(JsonElement.serializer(), id)
    }

    private fun ensureRunning() {
        check(mutableState.value == State.RUNNING && process?.isAlive == true) {
            "Child process is not running (state=${mutableState.value})"
        }
    }

    private fun markStopping(): Process? {
        expectedTermination.set(true)
        synchronized(lifecycleLock) {
            if (mutableState.value == State.NEW || mutableState.value == State.RUNNING) {
                mutableState.value = State.STOPPING
            }
            return process
        }
    }

    private fun closeStdin() {
        synchronized(stdinLock) {
            val activeWriter = writer
            writer = null
            runCatching { activeWriter?.close() }
        }
    }

    private fun waitForExit(activeProcess: Process): Int? {
        return try {
            activeProcess.waitFor()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { activeProcess.exitValue() }.getOrNull()
        }
    }

    private fun finishExit(exit: ProcessExit) {
        synchronized(lifecycleLock) {
            if (mutableState.value == State.EXITED) return
            mutableState.value = State.EXITED
            process = null
            writer = null
        }

        val failure = PersistentStdioProcessExitedException(exit)
        pendingRequests.values.forEach { it.completeExceptionally(failure) }
        pendingRequests.clear()
        exitDeferred.complete(exit)
        mutableExitEvents.tryEmit(exit)
        stdoutChannel.close()
        stderrChannel.close()
        violationChannel.close()
        ioFailureChannel.close()
        scope.cancel()
    }

    companion object {
        private const val DEFAULT_GRACE_PERIOD_MILLIS = 1_000L
        private const val DEFAULT_FORCE_KILL_WAIT_MILLIS = 1_000L
        private val STRICT_JSON_NUMBER =
            Regex("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")
    }
}

class PersistentStdioProcessExitedException(
    val processExit: PersistentJsonLineProcess.ProcessExit
) : IOException(
    "Child process exited${processExit.exitCode?.let { " with code $it" }.orEmpty()}" +
        if (processExit.expected) " (expected)" else ""
)

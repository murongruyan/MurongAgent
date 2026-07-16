package com.murong.agent.common.process

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PersistentJsonLineProcessTest {

    @Test
    fun start_usesInjectedBuilderAndWritesOneJsonLine() = runBlocking {
        val fake = FakeProcess()
        val builder = ProcessBuilder("ignored-command").redirectErrorStream(true)
        var launchedBuilder: ProcessBuilder? = null
        val transport = PersistentJsonLineProcess(
            processBuilder = builder,
            processLauncher = {
                launchedBuilder = it
                fake
            }
        )

        transport.start()
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "ping")
        }
        transport.sendJsonLine(message)

        assertSame(builder, launchedBuilder)
        assertFalse(builder.redirectErrorStream())
        assertEquals(message, Json.parseToJsonElement(fake.readStdinLine()))
        transport.closeAndAwait()
        Unit
    }

    @Test
    fun stdoutJson_stderrAndProtocolPollutionStaySeparated() = runBlocking {
        val fake = FakeProcess()
        val transport = newTransport(fake)
        transport.start()
        val stdout = async(start = CoroutineStart.UNDISPATCHED) {
            transport.stdoutLines.first()
        }
        val stderr = async(start = CoroutineStart.UNDISPATCHED) {
            transport.stderrLines.first()
        }
        val violation = async(start = CoroutineStart.UNDISPATCHED) {
            transport.stdoutViolations.first()
        }

        fake.writeStdout("not-json")
        fake.writeStderr("diagnostic only")
        fake.writeStdout("{\"method\":\"notice\"}")

        assertEquals("not-json", withTimeout(TEST_TIMEOUT) { violation.await() }.rawLine)
        assertEquals("diagnostic only", withTimeout(TEST_TIMEOUT) { stderr.await() })
        val jsonLine = withTimeout(TEST_TIMEOUT) { stdout.await() }
        assertEquals("notice", jsonLine.value.jsonObject["method"]?.jsonPrimitive?.content)
        transport.closeAndAwait()
        Unit
    }

    @Test
    fun concurrentRequests_areResolvedByIdOutOfOrder() = runBlocking {
        val fake = FakeProcess()
        val transport = newTransport(fake)
        transport.start()
        val first = async {
            transport.request(buildJsonObject {
                put("id", "first")
                put("method", "one")
            })
        }
        val second = async {
            transport.request(buildJsonObject {
                put("id", 2)
                put("method", "two")
            })
        }

        val sent = setOf(
            Json.parseToJsonElement(fake.readStdinLine()).jsonObject["id"].toString(),
            Json.parseToJsonElement(fake.readStdinLine()).jsonObject["id"].toString()
        )
        assertEquals(setOf("\"first\"", "2"), sent)
        assertEquals(2, transport.pendingRequestCount)

        fake.writeStdout("{\"id\":2,\"result\":\"second-result\"}")
        fake.writeStdout("{\"id\":\"first\",\"result\":\"first-result\"}")

        assertEquals(
            "first-result",
            withTimeout(TEST_TIMEOUT) { first.await() }
                .jsonObject["result"]?.jsonPrimitive?.content
        )
        assertEquals(
            "second-result",
            withTimeout(TEST_TIMEOUT) { second.await() }
                .jsonObject["result"]?.jsonPrimitive?.content
        )
        assertEquals(0, transport.pendingRequestCount)
        transport.closeAndAwait()
        Unit
    }

    @Test
    fun processExit_notifiesAndFailsPendingRequests() = runBlocking {
        val fake = FakeProcess()
        val transport = newTransport(fake)
        transport.start()
        val request = async {
            runCatching {
                transport.request(buildJsonObject {
                    put("id", "pending")
                    put("method", "wait")
                })
            }
        }
        fake.readStdinLine()

        fake.exit(17)

        val exit = withTimeout(TEST_TIMEOUT) { transport.awaitExit() }
        val exitEvent = withTimeout(TEST_TIMEOUT) { transport.exitEvents.first() }
        assertEquals(17, exit.exitCode)
        assertEquals(exit, exitEvent)
        assertFalse(exit.expected)
        assertEquals(PersistentJsonLineProcess.State.EXITED, transport.state.value)
        val failure = withTimeout(TEST_TIMEOUT) { request.await() }.exceptionOrNull()
        assertTrue(failure is PersistentStdioProcessExitedException)
        assertEquals(17, (failure as PersistentStdioProcessExitedException).processExit.exitCode)
    }

    @Test
    fun closeAndAwait_escalatesToForcibleKillAfterGracePeriod() = runBlocking {
        val fake = FakeProcess(exitOnDestroy = false)
        val transport = newTransport(fake)
        transport.start()

        val exit = transport.closeAndAwait(
            gracePeriodMillis = 20L,
            forceKillWaitMillis = TEST_TIMEOUT
        )

        assertTrue(fake.destroyCalled.get())
        assertTrue(fake.destroyForciblyCalled.get())
        assertEquals(137, exit.exitCode)
        assertTrue(exit.expected)
    }

    @Test
    fun request_rejectsBooleanIdsBeforeWriting() = runBlocking {
        val fake = FakeProcess()
        val transport = newTransport(fake)
        transport.start()

        try {
            transport.request(buildJsonObject {
                put("id", true)
                put("method", "invalid")
            })
            fail("boolean request id should be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("string or number"))
        }

        assertEquals(0, transport.pendingRequestCount)
        transport.closeAndAwait()
        Unit
    }

    private fun newTransport(fake: FakeProcess): PersistentJsonLineProcess {
        return PersistentJsonLineProcess(
            processBuilder = ProcessBuilder("ignored-command"),
            processLauncher = { fake }
        )
    }

    private class FakeProcess(
        private val exitOnDestroy: Boolean = true
    ) : Process() {
        private val childStdin = PipedInputStream()
        private val parentStdin = PipedOutputStream(childStdin)
        private val parentStdout = PipedInputStream()
        private val childStdout = BufferedWriter(
            OutputStreamWriter(PipedOutputStream(parentStdout), Charsets.UTF_8)
        )
        private val parentStderr = PipedInputStream()
        private val childStderr = BufferedWriter(
            OutputStreamWriter(PipedOutputStream(parentStderr), Charsets.UTF_8)
        )
        private val stdinReader = BufferedReader(InputStreamReader(childStdin, Charsets.UTF_8))
        private val exited = CountDownLatch(1)
        private val alive = AtomicBoolean(true)

        @Volatile
        private var code: Int? = null

        val destroyCalled = AtomicBoolean(false)
        val destroyForciblyCalled = AtomicBoolean(false)

        override fun getOutputStream(): OutputStream = parentStdin

        override fun getInputStream(): InputStream = parentStdout

        override fun getErrorStream(): InputStream = parentStderr

        override fun waitFor(): Int {
            exited.await()
            return code ?: error("exit code missing")
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            return exited.await(timeout, unit)
        }

        override fun exitValue(): Int {
            if (alive.get()) throw IllegalThreadStateException("still alive")
            return code ?: error("exit code missing")
        }

        override fun destroy() {
            destroyCalled.set(true)
            if (exitOnDestroy) exit(0)
        }

        override fun destroyForcibly(): Process {
            destroyForciblyCalled.set(true)
            exit(137)
            return this
        }

        override fun isAlive(): Boolean = alive.get()

        suspend fun readStdinLine(): String = withTimeout(TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                stdinReader.readLine() ?: error("stdin closed before a line was written")
            }
        }

        fun writeStdout(line: String) {
            childStdout.write(line)
            childStdout.newLine()
            childStdout.flush()
        }

        fun writeStderr(line: String) {
            childStderr.write(line)
            childStderr.newLine()
            childStderr.flush()
        }

        fun exit(exitCode: Int) {
            if (!alive.compareAndSet(true, false)) return
            code = exitCode
            runCatching { childStdout.close() }
            runCatching { childStderr.close() }
            runCatching { parentStdin.close() }
            exited.countDown()
        }
    }

    companion object {
        private const val TEST_TIMEOUT = 2_000L
    }
}

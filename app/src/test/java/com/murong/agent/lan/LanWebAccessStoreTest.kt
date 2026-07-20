package com.murong.agent.lan

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanWebAccessStoreTest {
    private class MemorySyncKeyStore : LanWebSyncKeyStore {
        val values = linkedMapOf<String, ByteArray>()

        override fun put(clientId: String, key: ByteArray) {
            values[clientId] = key.copyOf()
        }

        override fun read(clientId: String): ByteArray? = values[clientId]?.copyOf()

        override fun remove(clientId: String) {
            values.remove(clientId)?.fill(0)
        }

        override fun clear() {
            values.values.forEach { it.fill(0) }
            values.clear()
        }
    }

    @Test
    fun `pairing stores only token hash and code is one time`() {
        val file = tempStateFile()
        val store = LanWebAccessStore(file)
        val pairing = store.beginPairing(now = 1_000L)

        val issued = store.pair(
            rawCode = pairing.value,
            rawClientName = "  Work   Laptop  ",
            remoteAddress = "192.168.1.10",
            now = 2_000L
        ).getOrThrow()

        assertEquals("Work Laptop", issued.summary.name)
        assertNotNull(store.authenticate(issued.accessToken, now = 3_000L))
        val persisted = store.persistedTextForTest().orEmpty()
        assertFalse(persisted.contains(issued.accessToken))
        assertFalse(persisted.contains(pairing.value.replace("-", "")))
        assertFalse(store.isPairingAvailable(now = 3_000L))
        assertTrue(
            store.pair(pairing.value, "Second", "192.168.1.11", now = 3_100L).isFailure
        )
    }

    @Test
    fun `expired and malformed pairing codes fail closed`() {
        val store = LanWebAccessStore(tempStateFile())
        val pairing = store.beginPairing(now = 10L)

        assertTrue(
            store.pair(pairing.value, "Laptop", "192.168.1.10", now = 5 * 60 * 1000L + 11L).isFailure
        )
        val replacement = store.beginPairing(now = 1_000_000L)
        assertTrue(
            store.pair("!${replacement.value}", "Laptop", "192.168.1.10", now = 1_000_001L).isFailure
        )
    }

    @Test
    fun `secure pairing persists only a wrapped sync key reference`() {
        val file = tempStateFile()
        val vault = MemorySyncKeyStore()
        val store = LanWebAccessStore(file, syncKeyStore = vault)
        val pairing = store.beginPairing(now = 1_000L)

        val issued = store.pair(
            rawCode = "",
            rawCodeProof = LanWebPairingCrypto.codeProof(pairing.value),
            rawClientName = "Murong Windows",
            remoteAddress = "192.168.1.12",
            secureSync = true,
            now = 2_000L,
        ).getOrThrow()

        assertTrue(issued.summary.secureSync)
        assertEquals(32, issued.syncKey?.size)
        assertTrue(issued.syncKey!!.contentEquals(store.syncKey(issued.summary.id)))
        val persisted = store.persistedTextForTest().orEmpty()
        assertFalse(persisted.contains(java.util.Base64.getEncoder().encodeToString(issued.syncKey)))
        assertFalse(persisted.contains(issued.accessToken))
        assertTrue(store.revokeClient(issued.summary.id))
        assertNull(store.syncKey(issued.summary.id))
        assertTrue(vault.values.isEmpty())
    }

    @Test
    fun `corrupt credential file cannot authenticate or be silently overwritten`() {
        val file = tempStateFile().apply {
            parentFile?.mkdirs()
            writeText("{ definitely not json")
        }
        val store = LanWebAccessStore(file)

        assertNull(store.authenticate("x".repeat(43)))
        assertTrue(store.clients().isEmpty())
        assertTrue(runCatching { store.beginPairing() }.isFailure)
        assertEquals("{ definitely not json", file.readText())
    }

    @Test
    fun `revocation invalidates token and its replay ledger`() {
        val store = LanWebAccessStore(tempStateFile())
        val pairing = store.beginPairing(now = 1_000L)
        val issued = store.pair(pairing.value, "Desktop", "10.0.0.4", now = 2_000L).getOrThrow()

        assertTrue(store.claimRequest(issued.summary.id, "request-0001", now = 3_000L))
        assertFalse(store.claimRequest(issued.summary.id, "request-0001", now = 3_001L))
        assertTrue(store.revokeClient(issued.summary.id))
        assertNull(store.authenticate(issued.accessToken, now = 4_000L))
        assertFalse(store.claimRequest(issued.summary.id, "request-0002", now = 4_000L))
    }

    @Test
    fun `parallel stores claim a request id exactly once`() {
        val file = tempStateFile()
        val first = LanWebAccessStore(file)
        val pairing = first.beginPairing(now = 1_000L)
        val issued = first.pair(pairing.value, "Desktop", "10.0.0.4", now = 2_000L).getOrThrow()
        val stores = List(16) { LanWebAccessStore(file) }
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll(
                stores.map { store ->
                    Callable { store.claimRequest(issued.summary.id, "parallel-0001", now = 3_000L) }
                }
            ).map { it.get() }
            assertEquals(1, results.count { it })
        } finally {
            executor.shutdownNow()
        }
    }

    private fun tempStateFile(): File = createTempDirectory("lan-access-").resolve("access.json").toFile()
}

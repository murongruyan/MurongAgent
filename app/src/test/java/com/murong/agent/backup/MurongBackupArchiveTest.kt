package com.murong.agent.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class MurongBackupArchiveTest {
    @Test
    fun writeAndValidate_roundTripsPayloadAndManifest() {
        val root = Files.createTempDirectory("murong-backup-test").toFile()
        try {
            val source = File(root, "source.json").apply { writeText("{\"message\":\"你好\"}") }
            val archive = ByteArrayOutputStream()
            val manifest = MurongBackupArchive.write(
                output = archive,
                createdAtEpochMillis = 1_700_000_000_000L,
                appVersionName = "1.27",
                appVersionCode = 127,
                kind = MurongBackupKind.MANUAL,
                payloads = listOf(
                    MurongBackupPayload(
                        "state/provider-settings.json",
                        MurongBackupCategory.PROVIDER_SETTINGS,
                        source
                    )
                )
            )
            val extracted = MurongBackupArchive.extractAndValidate(
                ByteArrayInputStream(archive.toByteArray()),
                File(root, "extracted")
            )

            assertEquals(manifest, extracted.manifest)
            assertEquals(source.readText(), File(extracted.payloadRoot, "state/provider-settings.json").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun extractAndValidate_rejectsHashMismatch() {
        val payload = "changed".toByteArray()
        val manifest = validManifest(
            MurongBackupEntry(
                path = "state/provider-settings.json",
                category = MurongBackupCategory.PROVIDER_SETTINGS,
                sizeBytes = payload.size.toLong(),
                sha256 = "0".repeat(64)
            )
        )
        val archive = rawArchive(manifest, listOf("state/provider-settings.json" to payload))
        val destination = Files.createTempDirectory("murong-backup-hash").toFile()
        try {
            val error = assertFailsWith<IllegalArgumentException> {
                MurongBackupArchive.extractAndValidate(ByteArrayInputStream(archive), destination)
            }
            assertTrue(error.message.orEmpty().contains("哈希不匹配"))
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun validateRelativePath_rejectsTraversalAndAbsolutePaths() {
        assertFailsWith<IllegalArgumentException> {
            MurongBackupArchive.validateRelativePath("data/conversations/../secure.xml")
        }
        assertFailsWith<IllegalArgumentException> {
            MurongBackupArchive.validateRelativePath("/data/conversations/session.json")
        }
        assertFailsWith<IllegalArgumentException> {
            MurongBackupArchive.validateRelativePath("C:/data/conversations/session.json")
        }
    }

    @Test
    fun extractAndValidate_rejectsUnknownVersionBeforePayloadWrite() {
        val manifest = validManifest().copy(formatVersion = MURONG_BACKUP_FORMAT_VERSION + 1)
        val archive = rawArchive(manifest, emptyList())
        val destination = Files.createTempDirectory("murong-backup-version").toFile()
        try {
            val error = assertFailsWith<IllegalArgumentException> {
                MurongBackupArchive.extractAndValidate(ByteArrayInputStream(archive), destination)
            }
            assertTrue(error.message.orEmpty().contains("不支持的备份格式版本"))
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun extractAndValidate_acceptsLegacyV1AndV2PortableState() {
        val portable = "{}".toByteArray()
        val v2 = validManifest(
            MurongBackupEntry(
                path = "state/portable-state.json",
                category = MurongBackupCategory.PORTABLE_STATE,
                sizeBytes = portable.size.toLong(),
                sha256 = sha256(portable)
            )
        )
        val root = Files.createTempDirectory("murong-backup-versions").toFile()
        try {
            val validatedV2 = MurongBackupArchive.extractAndValidate(
                ByteArrayInputStream(rawArchive(v2, listOf("state/portable-state.json" to portable))),
                File(root, "v2")
            )
            assertEquals(MURONG_BACKUP_FORMAT_VERSION, validatedV2.manifest.formatVersion)

            val legacy = validManifest().copy(formatVersion = 1)
            val validatedV1 = MurongBackupArchive.extractAndValidate(
                ByteArrayInputStream(rawArchive(legacy, emptyList())),
                File(root, "v1")
            )
            assertEquals(1, validatedV1.manifest.formatVersion)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun extractAndValidate_rejectsCategoryMismatchAndManifestExtraEntry() {
        val payload = "{}".toByteArray()
        val wrongCategoryManifest = validManifest(
            MurongBackupEntry(
                path = "state/provider-settings.json",
                category = MurongBackupCategory.MCP_CONFIG,
                sizeBytes = payload.size.toLong(),
                sha256 = sha256(payload)
            )
        )
        val destination = Files.createTempDirectory("murong-backup-category").toFile()
        try {
            assertFailsWith<IllegalArgumentException> {
                MurongBackupArchive.extractAndValidate(
                    ByteArrayInputStream(rawArchive(wrongCategoryManifest, emptyList())),
                    destination
                )
            }

            val archiveWithExtra = rawArchive(validManifest(), listOf("state/provider-settings.json" to payload))
            assertFailsWith<IllegalStateException> {
                MurongBackupArchive.extractAndValidate(
                    ByteArrayInputStream(archiveWithExtra),
                    File(destination, "extra")
                )
            }
        } finally {
            destination.deleteRecursively()
        }
    }

    private fun validManifest(vararg entries: MurongBackupEntry): MurongBackupManifest {
        return MurongBackupManifest(
            createdAtEpochMillis = 1_700_000_000_000L,
            appVersionName = "1.27",
            appVersionCode = 127,
            kind = MurongBackupKind.MANUAL,
            entries = entries.toList()
        )
    }

    private fun rawArchive(
        manifest: MurongBackupManifest,
        entries: List<Pair<String, ByteArray>>
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val json = Json { encodeDefaults = true }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(MurongBackupArchive.MANIFEST_PATH))
            zip.write(json.encodeToString(MurongBackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

package com.murong.agent.core.loop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MentionPathResolverTest {

    @Test
    fun androidAbsoluteStoragePath_keepsForwardSlashes() {
        val path = "/storage/emulated/0/备份/gt8pro/boot.img"

        assertTrue(isDirectMentionPath(path))
        assertEquals(path, normalizeDirectMentionPath(path))
    }

    @Test
    fun windowsAbsolutePath_normalizesCopiedForwardSlashes() {
        val path = "C:/workspace/scripts/setup.sh"

        assertTrue(isDirectMentionPath(path))
        val expected = "C:" + '\\' + "workspace" + '\\' + "scripts" + '\\' + "setup.sh"
        assertEquals(expected, normalizeDirectMentionPath(path))
    }

    @Test
    fun relativePath_isNotTreatedAsDirectPath() {
        val path = "gt8pro/flash.sh"

        assertFalse(isDirectMentionPath(path))
        assertEquals(path, normalizeDirectMentionPath(path))
    }
}

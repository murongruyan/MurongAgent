package com.murong.agent.core.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocalUpdatePatchTest {

    @Test
    fun applyLocalUpdatePatch_appliesMultipleHunksAndPreservesCrLf() {
        val current = "alpha\r\nbeta\r\ngamma\r\ndelta\r\n"
        val patch = """
            *** Begin Patch
            *** Update File: src/demo.txt
            @@
             alpha
            -beta
            +beta changed
            @@
             gamma
            -delta
            +delta changed
            *** End Patch
        """.trimIndent()

        val result = applyLocalUpdatePatch(
            expectedPath = "/workspace/src/demo.txt",
            currentContent = current,
            patchText = patch
        )

        assertEquals(2, result.hunkCount)
        assertEquals("alpha\r\nbeta changed\r\ngamma\r\ndelta changed\r\n", result.content)
    }

    @Test
    fun applyLocalUpdatePatch_rejectsPatchForAnotherFile() {
        assertFailsWith<IllegalArgumentException> {
            applyLocalUpdatePatch(
                expectedPath = "/workspace/src/demo.txt",
                currentContent = "before\n",
                patchText = """
                    *** Begin Patch
                    *** Update File: src/other.txt
                    @@
                    -before
                    +after
                    *** End Patch
                """.trimIndent()
            )
        }
    }

    @Test
    fun applyLocalUpdatePatch_rejectsAmbiguousContextWithoutHint() {
        assertFailsWith<IllegalArgumentException> {
            applyLocalUpdatePatch(
                expectedPath = "/workspace/src/demo.txt",
                currentContent = "same\nother\nsame\n",
                patchText = """
                    *** Begin Patch
                    *** Update File: src/demo.txt
                    @@
                    -same
                    +changed
                    *** End Patch
                """.trimIndent()
            )
        }
    }

    @Test
    fun applyLocalUpdatePatch_rejectsTruncatedPatch() {
        assertFailsWith<IllegalArgumentException> {
            applyLocalUpdatePatch(
                expectedPath = "/workspace/src/demo.txt",
                currentContent = "before\n",
                patchText = """
                    *** Begin Patch
                    *** Update File: src/demo.txt
                    @@
                    -before
                    +after
                """.trimIndent()
            )
        }
    }

    @Test
    fun applyLocalUpdatePatch_rejectsInsertionWithoutContext() {
        assertFailsWith<IllegalArgumentException> {
            applyLocalUpdatePatch(
                expectedPath = "/workspace/src/demo.txt",
                currentContent = "before\n",
                patchText = """
                    *** Begin Patch
                    *** Update File: src/demo.txt
                    @@ -1,0 +1,1 @@
                    +inserted
                    *** End Patch
                """.trimIndent()
            )
        }
    }
}

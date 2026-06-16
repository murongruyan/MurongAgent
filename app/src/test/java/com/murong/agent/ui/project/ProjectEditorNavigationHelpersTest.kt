package com.murong.agent.ui.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectEditorNavigationHelpersTest {

    @Test
    fun projectLanguageForPath_handlesRemotePathsAndIncFiles() {
        assertEquals("json", projectLanguageForPath("owner/repo/bin/cpu/SM8850.json"))
        assertEquals("cpp", projectLanguageForPath("owner/repo/bin/activity_common.inc"))
    }

    @Test
    fun projectLanguageForPath_handlesSpecialFileNames() {
        assertEquals("bash", projectLanguageForPath("CMakeLists.txt"))
        assertEquals("bash", projectLanguageForPath(".bashrc"))
        assertEquals("properties", projectLanguageForPath(".editorconfig"))
        assertEquals("yaml", projectLanguageForPath(".clang-format"))
    }

    @Test
    fun buildProjectEditorDiagnostics_reportsJsonLocation() {
        val diagnostics = buildProjectEditorDiagnostics(
            content = """
                {
                  "name": "murong",
                }
            """.trimIndent(),
            language = "json"
        )

        assertTrue(diagnostics.isNotEmpty())
        assertTrue(diagnostics.first().message.contains("JSON 结构错误，第"))
    }
}

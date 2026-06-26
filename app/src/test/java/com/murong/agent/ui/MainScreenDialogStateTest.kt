package com.murong.agent.ui

import com.murong.agent.core.doctor.PendingCrashReport
import com.murong.agent.core.loop.ConversationExportData
import com.murong.agent.core.loop.ConversationExportFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainScreenDialogStateTest {

    private val pendingCrashReport = PendingCrashReport(
        timestamp = 42L,
        threadName = "main",
        exceptionType = "java.lang.IllegalStateException",
        message = "boom",
        stackTrace = "stack",
        fatal = true
    )

    private val pendingDoctorExport = ConversationExportData(
        fileName = "doctor-report.md",
        mimeType = "text/markdown",
        content = "# Doctor Report"
    )

    @Test
    fun reduceMainScreenDialogState_tracksPendingCrashExportCleanupFlag() {
        val prepared = reduceMainScreenDialogState(
            state = MainScreenDialogState(),
            action = MainScreenDialogAction.PreparePendingExport(
                exportData = pendingDoctorExport,
                clearPendingCrashAfterExport = true
            )
        )

        assertEquals(pendingDoctorExport, prepared.pendingExportData)
        assertTrue(prepared.clearPendingCrashAfterExport)

        val cleared = reduceMainScreenDialogState(
            state = prepared,
            action = MainScreenDialogAction.ClearPendingExport
        )

        assertNull(cleared.pendingExportData)
        assertFalse(cleared.clearPendingCrashAfterExport)
    }

    @Test
    fun reduceMainScreenDialogState_hidesOrClearsPendingCrashDialogSeparately() {
        val shown = reduceMainScreenDialogState(
            state = MainScreenDialogState(),
            action = MainScreenDialogAction.ShowPendingCrashDialog(pendingCrashReport)
        )

        assertTrue(shown.showPendingCrashDialog)
        assertEquals(pendingCrashReport, shown.pendingCrashReport)

        val hidden = reduceMainScreenDialogState(
            state = shown,
            action = MainScreenDialogAction.HidePendingCrashDialog
        )

        assertFalse(hidden.showPendingCrashDialog)
        assertNotNull(hidden.pendingCrashReport)

        val cleared = reduceMainScreenDialogState(
            state = hidden,
            action = MainScreenDialogAction.ClearPendingCrashDialog
        )

        assertFalse(cleared.showPendingCrashDialog)
        assertNull(cleared.pendingCrashReport)
    }

    @Test
    fun reduceMainScreenDialogState_showPendingCrashDialogRestoresVisibilityWithExistingReport() {
        val hidden = MainScreenDialogState(
            showPendingCrashDialog = false,
            pendingCrashReport = pendingCrashReport
        )

        val restored = reduceMainScreenDialogState(
            state = hidden,
            action = MainScreenDialogAction.ShowPendingCrashDialog(pendingCrashReport)
        )

        assertTrue(restored.showPendingCrashDialog)
        assertEquals(pendingCrashReport, restored.pendingCrashReport)
        assertEquals(ConversationExportFormat.MARKDOWN, restored.exportFormat)
    }

    @Test
    fun applyPendingCrashExportCancelledState_restoresCrashDialogAndClearsPendingExport() {
        val state = MainScreenDialogState(
            showPendingCrashDialog = false,
            pendingCrashReport = pendingCrashReport,
            pendingExportData = pendingDoctorExport,
            clearPendingCrashAfterExport = true
        )

        val restored = applyPendingCrashExportCancelledState(state)

        assertTrue(restored.showPendingCrashDialog)
        assertEquals(pendingCrashReport, restored.pendingCrashReport)
        assertNull(restored.pendingExportData)
        assertFalse(restored.clearPendingCrashAfterExport)
    }

    @Test
    fun applyPendingCrashExportFailedState_restoresCrashDialogAndClearsPendingExport() {
        val state = MainScreenDialogState(
            showPendingCrashDialog = false,
            pendingCrashReport = pendingCrashReport,
            pendingExportData = pendingDoctorExport,
            clearPendingCrashAfterExport = true
        )

        val restored = applyPendingCrashExportFailedState(state)

        assertTrue(restored.showPendingCrashDialog)
        assertEquals(pendingCrashReport, restored.pendingCrashReport)
        assertNull(restored.pendingExportData)
        assertFalse(restored.clearPendingCrashAfterExport)
    }

    @Test
    fun applyPendingCrashExportSucceededState_clearsCrashDialogAndPendingExport() {
        val state = MainScreenDialogState(
            showPendingCrashDialog = false,
            pendingCrashReport = pendingCrashReport,
            pendingExportData = pendingDoctorExport,
            clearPendingCrashAfterExport = true
        )

        val cleared = applyPendingCrashExportSucceededState(state)

        assertFalse(cleared.showPendingCrashDialog)
        assertNull(cleared.pendingCrashReport)
        assertNull(cleared.pendingExportData)
        assertFalse(cleared.clearPendingCrashAfterExport)
    }

    @Test
    fun beginPendingCrashRecoveryExportState_hidesCrashDialogButKeepsReport() {
        val visible = MainScreenDialogState(
            showPendingCrashDialog = true,
            pendingCrashReport = pendingCrashReport
        )

        val hidden = beginPendingCrashRecoveryExportState(visible)

        assertFalse(hidden.showPendingCrashDialog)
        assertEquals(pendingCrashReport, hidden.pendingCrashReport)
    }

    @Test
    fun applyPendingCrashExportLaunchFailedState_restoresCrashDialogWithoutPendingExport() {
        val hidden = MainScreenDialogState(
            showPendingCrashDialog = false,
            pendingCrashReport = pendingCrashReport
        )

        val restored = applyPendingCrashExportLaunchFailedState(
            state = hidden,
            clearPendingCrashAfterExport = true
        )

        assertTrue(restored.showPendingCrashDialog)
        assertEquals(pendingCrashReport, restored.pendingCrashReport)
        assertNull(restored.pendingExportData)
        assertFalse(restored.clearPendingCrashAfterExport)
    }

    @Test
    fun pendingCrashRecoveryExportFlow_cancelledRestoresDialog() {
        val visible = reduceMainScreenDialogState(
            state = MainScreenDialogState(),
            action = MainScreenDialogAction.ShowPendingCrashDialog(pendingCrashReport)
        )
        val hidden = beginPendingCrashRecoveryExportState(visible)
        val prepared = reduceMainScreenDialogState(
            state = hidden,
            action = MainScreenDialogAction.PreparePendingExport(
                exportData = pendingDoctorExport,
                clearPendingCrashAfterExport = true
            )
        )

        val restored = applyPendingCrashExportCancelledState(prepared)

        assertTrue(restored.showPendingCrashDialog)
        assertEquals(pendingCrashReport, restored.pendingCrashReport)
        assertNull(restored.pendingExportData)
        assertFalse(restored.clearPendingCrashAfterExport)
    }

    @Test
    fun pendingCrashRecoveryExportFlow_succeededClearsEverything() {
        val visible = reduceMainScreenDialogState(
            state = MainScreenDialogState(),
            action = MainScreenDialogAction.ShowPendingCrashDialog(pendingCrashReport)
        )
        val hidden = beginPendingCrashRecoveryExportState(visible)
        val prepared = reduceMainScreenDialogState(
            state = hidden,
            action = MainScreenDialogAction.PreparePendingExport(
                exportData = pendingDoctorExport,
                clearPendingCrashAfterExport = true
            )
        )

        val cleared = applyPendingCrashExportSucceededState(prepared)

        assertFalse(cleared.showPendingCrashDialog)
        assertNull(cleared.pendingCrashReport)
        assertNull(cleared.pendingExportData)
        assertFalse(cleared.clearPendingCrashAfterExport)
    }
}

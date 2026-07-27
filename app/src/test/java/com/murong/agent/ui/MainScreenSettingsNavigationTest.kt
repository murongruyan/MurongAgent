package com.murong.agent.ui

import com.murong.agent.ui.settings.SettingsFocus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainScreenSettingsNavigationTest {

    @Test
    fun detailAction_opensFocusedSettingsAndBackReturnsToDirectory() {
        val detail = reduceMainScreenSettingsState(
            state = MainScreenSettingsState(),
            action = MainScreenSettingsAction.OpenDetail(SettingsFocus.DEVICE)
        )

        assertEquals(SettingsSubpage.Detail(SettingsFocus.DEVICE), detail.subpage)

        val directory = reduceMainScreenSettingsState(
            state = detail,
            action = MainScreenSettingsAction.CloseSecondaryPage
        )

        assertEquals(SettingsSubpage.Main, directory.subpage)
    }

    @Test
    fun shellDoesNotExposeBottomNavigation() {
        val state = resolveMainScreenShellState(
            shellScreens = listOf(Screen.Chat, Screen.Projects, Screen.Tools, Screen.Settings),
            selectedTopLevelPage = 0,
            visibleTopLevelPage = 0,
            topLevelNavigationTargetPage = null,
            pagerIsScrollInProgress = false,
            pagerCurrentPage = 0,
            pagerSettledPage = 0,
            settingsSubpage = SettingsSubpage.Main,
            projectSecondaryChromeState = ProjectSecondaryChromeState(),
            chatPageIndex = 0,
            topLevelHistoryLastPage = null,
            topLevelBackProgress = 0f
        )

        assertFalse(state.showBottomBar)
    }

    @Test
    fun featurePageBack_returnsToSettingsWithoutCreatingBounceHistory() {
        val returning = reduceMainScreenTopLevelNavigationState(
            state = MainScreenTopLevelNavigationState(
                selectedPage = 2,
                lastSettledPage = 2,
                history = listOf(0, 3)
            ),
            action = MainScreenTopLevelNavigationAction.NavigateBackToPage(3)
        )

        assertEquals(3, returning.selectedPage)
        assertEquals(listOf(0), returning.history)
        assertTrue(returning.consumingBackNavigation)

        val settled = reduceMainScreenTopLevelNavigationState(
            state = returning,
            action = MainScreenTopLevelNavigationAction.SyncSettledPage(3)
        )

        assertEquals(listOf(0), settled.history)
        assertFalse(settled.consumingBackNavigation)
    }

    @Test
    fun featurePageBack_returnsToSettingsEvenWhenRapidNavigationSkippedDirectoryHistory() {
        val returning = reduceMainScreenTopLevelNavigationState(
            state = MainScreenTopLevelNavigationState(
                selectedPage = 2,
                lastSettledPage = 2,
                history = listOf(0)
            ),
            action = MainScreenTopLevelNavigationAction.NavigateBackToPage(3)
        )

        assertEquals(3, returning.selectedPage)
        assertEquals(listOf(0), returning.history)
    }

    @Test
    fun predictiveBack_alwaysRevealsParentDirectoryFromTheLeft() {
        val shellState = resolveMainScreenShellState(
            shellScreens = listOf(Screen.Chat, Screen.Projects, Screen.Tools, Screen.Settings),
            selectedTopLevelPage = 2,
            visibleTopLevelPage = 2,
            topLevelNavigationTargetPage = null,
            pagerIsScrollInProgress = false,
            pagerCurrentPage = 2,
            pagerSettledPage = 2,
            settingsSubpage = SettingsSubpage.Main,
            projectSecondaryChromeState = ProjectSecondaryChromeState(),
            chatPageIndex = 0,
            topLevelHistoryLastPage = 3,
            topLevelBackProgress = 0.5f
        )

        val preview = resolveMainScreenTopLevelPredictivePreviewState(
            selectedTopLevelPage = 2,
            shellState = shellState
        )

        assertEquals(3, preview.targetPage)
        assertEquals(1f, preview.directionMultiplier)
    }
}

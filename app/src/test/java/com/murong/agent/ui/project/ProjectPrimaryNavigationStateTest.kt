package com.murong.agent.ui.project

import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectPrimaryNavigationStateTest {

    @Test
    fun settingsDirectoryRequest_canTargetTerminalDirectly() {
        val state = reduceProjectPrimaryNavigationState(
            state = ProjectPrimaryNavigationState(selectedTab = ProjectPrimaryTab.EDITOR),
            action = ProjectPrimaryNavigationAction.SelectTab(ProjectPrimaryTab.TERMINAL)
        )

        assertEquals(ProjectPrimaryTab.TERMINAL, state.selectedTab)
        assertEquals(ProjectPrimaryTab.TERMINAL, state.navigationTargetTab)
    }

    @Test
    fun settingsDirectoryRequest_canTargetGitDirectly() {
        val state = reduceProjectPrimaryNavigationState(
            state = ProjectPrimaryNavigationState(selectedTab = ProjectPrimaryTab.EDITOR),
            action = ProjectPrimaryNavigationAction.SelectTab(ProjectPrimaryTab.GIT)
        )

        assertEquals(ProjectPrimaryTab.GIT, state.selectedTab)
        assertEquals(ProjectPrimaryTab.GIT, state.navigationTargetTab)
    }

    @Test
    fun settingsDirectoryRequest_canTargetProjectConfigDirectly() {
        val state = reduceProjectPrimaryNavigationState(
            state = ProjectPrimaryNavigationState(selectedTab = ProjectPrimaryTab.EDITOR),
            action = ProjectPrimaryNavigationAction.SelectTab(ProjectPrimaryTab.CONFIG)
        )

        assertEquals(ProjectPrimaryTab.CONFIG, state.selectedTab)
        assertEquals(ProjectPrimaryTab.CONFIG, state.navigationTargetTab)
    }
}

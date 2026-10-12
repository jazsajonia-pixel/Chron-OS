package com.chronos.pocketos

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronos.pocketos.core.settings.ThemeMode
import com.chronos.pocketos.core.window.WindowMode
import com.chronos.pocketos.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp-land-mdpi")
class ShellUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<PocketOsApp>()

    private fun launch(appId: String) {
        rule.onNodeWithTag("start_button").performClick()
        rule.onNodeWithTag("launcher").assertIsDisplayed()
        rule.onNodeWithTag("launch_$appId").performClick()
        rule.waitForIdle()
    }

    @Test fun desktopShowsTaskbarAndIcons() {
        rule.onNodeWithTag("taskbar").assertIsDisplayed()
        rule.onNodeWithTag("clock").assertIsDisplayed()
        rule.onNodeWithTag("icon_files").assertIsDisplayed()
    }

    @Test fun launcherOpensCalculatorAndItComputes() {
        launch("calculator")
        rule.onNodeWithTag("window_calculator").assertIsDisplayed()
        listOf("7", "×", "6", "=").forEach { rule.onNodeWithTag("calc_$it").performClick() }
        rule.onNodeWithTag("calc_expr").assertTextEquals("42")
    }

    @Test fun launcherSearchFilters() {
        rule.onNodeWithTag("start_button").performClick()
        rule.onNodeWithTag("launcher_search").performTextInput("calc")
        rule.onNodeWithTag("launch_calculator").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("launch_files").fetchSemanticsNodes().size)
    }

    @Test fun windowControlsUpdateRealState() {
        launch("sysinfo")
        val id = app.shell.desktop.value.focusedId!!
        rule.onNodeWithTag("max_sysinfo").performClick()
        rule.waitForIdle()
        assertEquals(WindowMode.MAXIMIZED, app.shell.desktop.value.windows[id]!!.mode)
        rule.onNodeWithTag("max_sysinfo").performClick()
        rule.waitForIdle()
        assertEquals(WindowMode.NORMAL, app.shell.desktop.value.windows[id]!!.mode)
        rule.onNodeWithTag("min_sysinfo").performClick()
        rule.waitForIdle()
        assertEquals(WindowMode.MINIMIZED, app.shell.desktop.value.windows[id]!!.mode)
        assertEquals(0, rule.onAllNodesWithTag("window_sysinfo").fetchSemanticsNodes().size)
        rule.onNodeWithTag("task_sysinfo").performClick()
        rule.onNodeWithTag("window_sysinfo").assertIsDisplayed()
        rule.onNodeWithTag("close_sysinfo").performClick()
        rule.waitForIdle()
        assertTrue(app.shell.desktop.value.windows.isEmpty())
        assertEquals(0, rule.onAllNodesWithTag("task_sysinfo").fetchSemanticsNodes().size)
    }

    @Test fun settingsPersistAcrossRestart() {
        launch("settings")
        rule.onNodeWithTag("theme_Dark").performClick()
        rule.onNodeWithTag("toggle_24h").performClick()
        rule.waitForIdle()
        // A fresh repository reads what was written to disk, as after a process restart.
        val reloaded = SettingsRepository(app).settings.value
        assertEquals(ThemeMode.DARK, reloaded.theme)
        assertTrue(reloaded.use24HourClock)
    }

    @Test fun backClosesLauncherThenMinimizes() {
        launch("calculator")
        rule.onNodeWithTag("start_button").performClick()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag("launcher").fetchSemanticsNodes().size)
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(WindowMode.MINIMIZED, app.shell.desktop.value.focused?.mode ?: app.shell.desktop.value.windows.values.first().mode)
    }

    @Test fun filesCreateFolderAndTextFileThenEditAndSave() {
        launch("files")
        rule.onNodeWithTag("file_Documents").assertIsDisplayed()
        rule.onNodeWithTag("files_new_folder").performClick()
        rule.onNodeWithTag("prompt_field").performTextReplacement("Projects")
        rule.onNodeWithTag("prompt_ok").performClick()
        rule.waitForIdle()
        assertTrue(File(app.home.root, "Projects").isDirectory)
        rule.onNodeWithTag("file_Projects").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("files_new_file").performClick()
        rule.onNodeWithTag("prompt_field").performTextReplacement("notes.txt")
        rule.onNodeWithTag("prompt_ok").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("window_editor").assertIsDisplayed()
        rule.onNodeWithTag("editor_text").performTextInput("Hello PocketOS")
        rule.onNodeWithTag("editor_save").performClick()
        rule.waitUntil(5000) { File(app.home.root, "Projects/notes.txt").readText() == "Hello PocketOS" }
    }

    @Test fun sessionSurvivesSaveAndRestore() {
        launch("calculator")
        app.saveSession()
        val restored = com.chronos.pocketos.shell.ShellController.decodeSession(app.settings.loadSession())
        assertEquals(listOf("calculator"), restored.windows.values.map { it.appId })
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
class PhonePortraitUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun windowsOpenMaximizedOnPhonePortrait() {
        rule.onNodeWithTag("start_button").performClick()
        rule.onNodeWithTag("launch_files").performClick()
        rule.waitForIdle()
        val app = ApplicationProvider.getApplicationContext<PocketOsApp>()
        assertEquals(WindowMode.MAXIMIZED, app.shell.desktop.value.focused!!.mode)
        rule.onNodeWithTag("files_home").assertIsDisplayed()
        rule.onNodeWithTag("file_Documents").assertIsDisplayed()
    }
}

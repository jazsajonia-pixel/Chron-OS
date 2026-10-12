package com.chronos.pocketos

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h900dp-land-mdpi")
class OnboardingTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<PocketOsApp>()

    private fun startTour() { rule.runOnIdle { app.settings.update { it.copy(onboardingCompleted = false) } }; rule.waitForIdle() }

    @Test fun tourIsHonestAndCompletesOnce() {
        startTour()
        rule.onNodeWithTag("onb_body").assertTextContains("not a separate operating system", substring = true)
        rule.onNodeWithTag("onb_next").performClick(); rule.onNodeWithTag("onb_next").performClick()
        rule.onNodeWithTag("onb_body").assertTextContains("Many phones do not support video output", substring = true)
        rule.onNodeWithTag("onb_next").performClick()
        rule.onNodeWithTag("onb_body").assertTextContains("built-in Windows runtime", substring = true)
        rule.onNodeWithTag("onb_back").performClick()
        rule.onNodeWithTag("onb_step").assertTextContains("Step 3 of 5")
        rule.onNodeWithTag("onb_next").performClick(); rule.onNodeWithTag("onb_next").performClick()
        rule.onNodeWithTag("onb_body").assertTextContains("no analytics", substring = true)
        rule.onNodeWithTag("onb_next").assertTextContains("Get started").performClick()
        assertEquals(0, rule.onAllNodesWithTag("onboarding").fetchSemanticsNodes().size)
        assertTrue(app.settings.settings.value.onboardingCompleted)
        rule.onNodeWithTag("taskbar").assertIsDisplayed()
    }

    @Test fun skipAndReopenFromSettings() {
        startTour()
        rule.onNodeWithTag("onb_skip").performClick()
        assertTrue(app.settings.settings.value.onboardingCompleted)
        rule.runOnIdle { app.shell.openApp("settings") }
        rule.onNodeWithTag("settings_privacy").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("settings_tour").performScrollTo().performClick()
        rule.onNodeWithTag("onboarding").assertIsDisplayed()
        rule.onNodeWithTag("onb_step").assertTextContains("Step 1 of 5")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun keyboardNavigation() {
        startTour()
        rule.onNodeWithTag("onboarding").performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithTag("onb_step").assertTextContains("Step 2 of 5")
        rule.onNodeWithTag("onboarding").performKeyInput { pressKey(Key.DirectionLeft) }
        rule.onNodeWithTag("onb_step").assertTextContains("Step 1 of 5")
        rule.onNodeWithTag("onboarding").performKeyInput { pressKey(Key.Escape) }
        assertTrue(app.settings.settings.value.onboardingCompleted)
    }

    @Test fun tourSurvivesRecreation() {
        startTour()
        rule.onNodeWithTag("onb_next").performClick()
        rule.activityRule.scenario.recreate(); rule.waitForIdle()
        rule.onNodeWithTag("onb_step").assertTextContains("Step 2 of 5")
        assertFalse(app.settings.settings.value.onboardingCompleted)
    }
}

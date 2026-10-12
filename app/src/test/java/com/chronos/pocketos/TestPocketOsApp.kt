package com.chronos.pocketos

/** Test application: skips the first-run tour so tests start on the plain desktop. OnboardingTest re-enables it. */
class TestPocketOsApp : PocketOsApp() {
    override fun onCreate() {
        super.onCreate()
        settings.update { it.copy(onboardingCompleted = true) }
    }
}

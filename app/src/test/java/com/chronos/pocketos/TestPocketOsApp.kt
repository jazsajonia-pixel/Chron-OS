package com.chronos.pocketos

/** Test application: skips the first-run tour and replaces the embedded Windows runtime with [FakeRuntimeBackend]. */
class TestPocketOsApp : PocketOsApp() {
    lateinit var fakeRuntime: FakeRuntimeBackend

    override fun createRuntimeBackend(): com.chronos.pocketos.runtime.RuntimeBackend =
        FakeRuntimeBackend(this).also { fakeRuntime = it }

    override fun onCreate() {
        super.onCreate()
        settings.update { it.copy(onboardingCompleted = true) }
    }
}

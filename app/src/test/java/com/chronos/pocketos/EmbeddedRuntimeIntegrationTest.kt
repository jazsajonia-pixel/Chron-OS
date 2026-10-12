package com.chronos.pocketos

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronos.pocketos.core.library.GraphicsChoice
import com.chronos.pocketos.core.library.RuntimeProfile
import com.chronos.pocketos.runtime.EmbeddedWindowsBackend
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Runs PocketOS's real runtime glue against the real bundled runtime files: unpacks the Linux userland with Wine and
 * Box64, creates the Windows environment, applies settings and scans for installed programs. Starting a program
 * (which needs an ARM64 Android phone and its GPU) is not covered.
 *
 * Slow and disk-hungry, so opt-in: ./gradlew :app:testDebugUnitTest -Ppocketos.runtimeIntegration=true --tests '*EmbeddedRuntimeIntegrationTest*'
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class EmbeddedRuntimeIntegrationTest {
    @Test fun realRuntimeInstallsCreatesEnvironmentAndFindsInstalledPrograms() {
        assumeTrue(System.getProperty("pocketos.runtimeIntegration") == "true")
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val backend = EmbeddedWindowsBackend(context)
        assertFalse(backend.systemFilesInstalled())

        val progress = mutableListOf<Int>()
        assertTrue("system files unpack", backend.installSystemFiles { progress += it })
        assertTrue(backend.systemFilesInstalled())
        assertEquals(100, progress.last())
        val rootfs = File(context.filesDir, "rootfs")
        assertTrue(File(rootfs, "opt/wine/bin/wine").isFile)
        // ("lib" is a symlink to usr/lib; Robolectric does not create symlinks or apply file modes.)
        assertTrue(File(rootfs, "usr/lib/ld-linux-aarch64.so.1").isFile)
        // The retargeted userland points at this app's own folder.
        val ldCache = File(rootfs, "etc/ld.so.cache").takeIf { it.isFile }?.readBytes()
        if (ldCache != null) assertFalse(String(ldCache, Charsets.ISO_8859_1).contains("com.winlator"))
        // No runtime screens: the runtime declares no activities of its own.
        val pm = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_ACTIVITIES)
        assertTrue(pm.activities.orEmpty().none { it.name.startsWith("com.chronos.pocketos.windows") })

        val programs = File(context.filesDir, "pc-apps").apply { mkdirs() }
        val home = File(context.filesDir, "home").apply { mkdirs() }
        val drives = mapOf('D' to programs.path, 'E' to home.path)
        val id = backend.createContainer("PocketOS", drives, RuntimeProfile())
        assertNotNull("container created", id)
        assertEquals(id, backend.containerId())
        val containerDir = File(rootfs, "home/xuser-$id")
        assertTrue("Wine's common DLLs copied", File(containerDir, ".wine/drive_c/windows/system32/kernel32.dll").isFile)
        assertTrue(File(containerDir, ".wine/drive_c/windows/syswow64/kernel32.dll").isFile)
        val config = { JSONObject(File(containerDir, ".container").readText()) }
        assertEquals("D:${programs.path}E:${home.path}", config().getString("drives"))

        backend.applyProfile(id!!, RuntimeProfile(width = 1024, height = 768, graphics = GraphicsChoice.SAFER))
        assertEquals("1024x768", config().getString("screenSize"))
        assertTrue(config().getString("graphicsDriver").endsWith(",virgl"))
        assertEquals("wined3d", config().getString("dxwrapper"))

        // Shortcuts exactly as Wine 10 created them for tools/pe-fixtures/sample_installer_shortcuts.nsi.
        val user = File(containerDir, ".wine/drive_c/users/xuser")
        File(user, "Desktop").mkdirs()
        File(user, "Desktop/Sample Paint.desktop").writeText("[Desktop Entry]\nName=Sample Paint\nExec=wine C:\\\\Program\\ Files\\\\Sample\\ Paint\\\\SamplePaint.exe\nType=Application\n")
        File(containerDir, ".wine/drive_c/Program Files/Sample Paint").mkdirs()
        File(containerDir, ".wine/drive_c/Program Files/Sample Paint/SamplePaint.exe").writeText("MZ")
        val found = backend.shortcuts(id)
        assertTrue(found.any { it.name == "Sample Paint" && it.path.endsWith("Desktop/Sample Paint.desktop") })
        assertTrue(found.any { it.path.endsWith("Program Files/Sample Paint/SamplePaint.exe") })

        assertTrue(backend.installedSize() > 300L * 1024 * 1024)
    }
}

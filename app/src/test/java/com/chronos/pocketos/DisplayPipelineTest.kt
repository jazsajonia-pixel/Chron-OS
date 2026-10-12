package com.chronos.pocketos

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronos.pocketos.windows.WindowsSession
import com.chronos.pocketos.windows.renderer.DisplayHost
import com.chronos.pocketos.windows.renderer.GLRenderer
import com.chronos.pocketos.windows.widget.DisplayView
import com.chronos.pocketos.windows.xconnector.UnixSocketConfig
import com.chronos.pocketos.windows.xenvironment.components.SysVSharedMemoryComponent
import com.chronos.pocketos.windows.xenvironment.components.XServerComponent
import com.chronos.pocketos.windows.xserver.ScreenInfo
import com.chronos.pocketos.windows.xserver.Window
import com.chronos.pocketos.windows.xserver.XServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Opt-in end-to-end check of the display pipeline on a PC: a real Windows program (tools/runtime/host-display-test/
 * click_test.c) runs with Box64 + Wine under qemu-aarch64 and connects to PocketOS's own X display server. The test
 * then checks the program's window appeared and was drawn, types into it and clicks its button through
 * [DisplayView] — the same view a PocketOS window shows — and verifies the program received both.
 *
 * Needs the runtime unpacked at /data/data/com.chron.os/files/rootfs (see EmbeddedRuntimeIntegrationTest), qemu
 * binfmt for aarch64, and `tools/runtime/host-display-test/build.sh`. Run with -Ppocketos.displayPipeline=true.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class DisplayPipelineTest {
    private val root = File("/data/data/com.chron.os/files/rootfs")

    @Test fun realWindowsProgramDrawsIntoPocketOsDisplayAndReceivesMouseAndKeyboard() {
        assumeTrue(System.getProperty("pocketos.displayPipeline") == "true")
        assumeTrue(File(root, "usr/local/bin/box64").exists())
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val drive = File(root, "home/xuser/.wine/drive_c")
        File(System.getProperty("pocketos.clickTestExe")!!).copyTo(File(drive, "click_test.exe"), overwrite = true)
        val result = File(drive, "pocketos_click.txt").apply { delete() }

        killRuntimeProcesses() // a wineserver left from an earlier run would keep its old desktop
        // The same display pieces WindowsSession builds, without a GPU (frames are checked in memory instead).
        val session = WindowsSession(context, 0, "/unused", null, null)
        val xServer = XServer(session, ScreenInfo("800x600"))
        WindowsSession::class.java.getDeclaredField("xServer").apply { isAccessible = true }.set(session, xServer)
        val host = DisplayHost(context)
        val renderer = GLRenderer(host, xServer)
        host.setRenderer(renderer)
        xServer.renderer = renderer
        val shm = SysVSharedMemoryComponent(xServer, UnixSocketConfig.create(root.path, UnixSocketConfig.SYSVSHM_SERVER_PATH))
        val display = XServerComponent(xServer, UnixSocketConfig.create(root.path, UnixSocketConfig.XSERVER_PATH))
        shm.start(); display.start()

        val log = File(System.getProperty("java.io.tmpdir"), "pocketos-display-pipeline.log")
        val env = mapOf(
            "HOME" to "${root.path}/home/xuser", "USER" to "xuser", "TMPDIR" to "${root.path}/tmp", "DISPLAY" to ":0",
            "PATH" to "${root.path}/opt/wine/bin:${root.path}/usr/local/bin:${root.path}/usr/bin",
            "LD_LIBRARY_PATH" to "${root.path}/usr/lib", "BOX64_LD_LIBRARY_PATH" to "${root.path}/lib/x86_64-linux-gnu",
            "BOX64_RCFILE" to "${root.path}/etc/config.box64rc", "BOX64_DYNAREC" to "1", "BOX64_NOBANNER" to "1",
            "WINEPREFIX" to drive.parent, "WINEDEBUG" to (System.getenv("POCKETOS_TEST_WINEDEBUG") ?: "err+all,fixme-all"), "WINEESYNC" to "0",
            "ANDROID_SYSVSHM_SERVER" to "${root.path}${UnixSocketConfig.SYSVSHM_SERVER_PATH}",
        )
        // Same command shape as WindowsSession uses for a program: hidden Windows desktop + winhandler.
        val pb = ProcessBuilder("${root.path}/usr/local/bin/box64", "wine", "explorer", "/desktop=nogui,800x600",
            "C:\\windows\\winhandler.exe", "C:\\click_test.exe")
            .directory(root).redirectErrorStream(true).redirectOutput(log)
        pb.environment().clear(); pb.environment().putAll(env)
        val wine = pb.start()
        try {
            val window = waitFor(900) { findWindow(xServer.windowManager.rootWindow) }
            if (window == null) dump(xServer.windowManager.rootWindow, "")
            assertNotNull("the program's window never appeared on PocketOS's display (see $log)", window)
            window!!
            println("window ${window.name} at ${window.rootX},${window.rootY} ${window.width}x${window.height}")

            // It was drawn: the window has real pixels, including its blue background.
            val drawn = waitFor(300) { if (colours(window).let { it.size > 3 && it.any(::isBlue) }) true else null }
            assertEquals("window contents were not drawn (colours ${colours(window).take(16).map { Integer.toHexString(it) }})", true, drawn)

            // Input through the same view a PocketOS window shows, sized like the display.
            val view = DisplayView(context, xServer, host)
            view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 800, 600)
            Thread.sleep(3000)
            view.typeText("pocket")
            Thread.sleep(3000)
            val cx = window.rootX + window.width / 2f
            val cy = window.rootY + window.height / 2f
            val t = SystemClock.uptimeMillis()
            view.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, cy, 0))
            Thread.sleep(300)
            view.onTouchEvent(MotionEvent.obtain(t, t + 300, MotionEvent.ACTION_UP, cx, cy, 0))

            // The program writes what was typed when its button is clicked, then exits (Wine's hidden desktop stays).
            assertNotNull("the click never reached the program (see $log)", waitFor(300) { if (result.length() > 0) true else null })
            val closed = waitFor(120) { if (findWindow(xServer.windowManager.rootWindow) == null) true else null }
            assertNotNull("the program's window is still on the display after it exited", closed)
            assertEquals("clicked:pocket", result.readText())
        }
        finally {
            if (wine.isAlive) wine.destroyForcibly()
            killRuntimeProcesses()
            display.stop(); shm.stop()
        }
    }

    private fun dump(w: Window, indent: String) {
        System.err.println("PIPE: $indent${w.id} '${w.name}' class='${w.className}' mapped=${w.attributes.isMapped} ${w.getRootX()},${w.getRootY()} ${w.getWidth()}x${w.getHeight()}")
        for (c in w.children) dump(c, "$indent  ")
    }

    private fun killRuntimeProcesses() {
        ProcessBuilder("pkill", "-9", "-f", "qemu-aarch64 ${root.path}").start().waitFor()
        Thread.sleep(1000)
    }

    private fun <T> waitFor(seconds: Int, probe: () -> T?): T? {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) { probe()?.let { return it }; Thread.sleep(500) }
        return null
    }

    private fun findWindow(w: Window): Window? {
        if (w.className == "click_test.exe" && w.attributes.isMapped && w.getWidth() > 100) return w
        for (c in w.children) findWindow(c)?.let { return it }
        return null
    }

    private val Window.rootX get() = getRootX().toInt()
    private val Window.rootY get() = getRootY().toInt()
    private val Window.width get() = getWidth().toInt()
    private val Window.height get() = getHeight().toInt()

    /** Distinct colours in the window's pixels (stored as BGRA, read as 0xRRGGBB). */
    private fun colours(w: Window): Set<Int> {
        val data = w.content?.data ?: return emptySet()
        val out = LinkedHashSet<Int>()
        val dup = data.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN); dup.rewind()
        while (dup.remaining() >= 4 && out.size < 4096) out += dup.int and 0xffffff
        return out
    }

    /** The program's background, RGB(0, 120, 215). */
    private fun isBlue(px: Int) = px == 0x0078D7
}

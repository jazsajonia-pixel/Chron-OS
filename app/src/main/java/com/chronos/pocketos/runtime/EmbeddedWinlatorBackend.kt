package com.chronos.pocketos.runtime

import android.content.Context
import android.content.Intent
import com.chronos.pocketos.core.library.GraphicsChoice
import com.chronos.pocketos.core.library.RuntimeProfile
import com.chronos.pocketos.core.runtime.InstalledShortcut
import com.winlator.MainActivity
import com.winlator.XServerDisplayActivity
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.container.DXWrappers
import com.winlator.container.GraphicsDrivers
import com.winlator.xenvironment.RootFS
import com.winlator.xenvironment.RootFSInstaller
import org.json.JSONObject
import java.io.File

/**
 * Drives the embedded runtime (module :winruntime, derived from Winlator, LGPL-2.1).
 * The runtime's activities run in the separate ":winruntime" process; this class only prepares files and intents.
 */
class EmbeddedWinlatorBackend(private val context: Context) : RuntimeBackend {

    override fun systemFilesInstalled(): Boolean = runCatching { RootFSInstaller.isInstalled(context) }.getOrDefault(false)

    override fun installSystemFiles(progress: (Int) -> Unit): Boolean =
        RootFSInstaller.installBlocking(context) { progress(it) }

    private fun manager() = ContainerManager(context)
    private val prefs = context.getSharedPreferences("pocketos_runtime_backend", Context.MODE_PRIVATE)

    /**
     * Best graphics driver pair for this GPU ("turnip,gladio" on Adreno, "vortek,gladio" elsewhere). Detecting the
     * GPU needs native code and an offscreen EGL context, so it is done once (during setup, off the main thread).
     */
    private fun defaultDriver(): String = prefs.getString(KEY_DEFAULT_DRIVER, null)
        ?: runCatching { GraphicsDrivers.getDefaultDriver(context) }.getOrDefault(FALLBACK_DRIVER)
            .also { prefs.edit().putString(KEY_DEFAULT_DRIVER, it).apply() }

    private fun container(id: Int): Container? = manager().getContainerById(id)

    override fun containerId(): Int? = runCatching {
        manager().containers.firstOrNull { it.name == CONTAINER_NAME && File(it.rootDir, ".wine").isDirectory }?.id
    }.getOrNull()

    override fun createContainer(name: String, drives: Map<Char, String>, profile: RuntimeProfile): Int? {
        defaultDriver()
        val data = JSONObject()
            .put("name", name)
            .put("screenSize", "${profile.width}x${profile.height}")
            .put("graphicsDriver", graphicsDriverFor(profile))
            .put("dxwrapper", dxWrapperFor(profile))
            .put("drives", encodeDrives(drives))
        return manager().createContainerBlocking(data)?.id
    }

    override fun ensureDrives(containerId: Int, drives: Map<Char, String>) {
        val c = container(containerId) ?: return
        val wanted = encodeDrives(drives)
        if (c.getDrives() != wanted) { c.setDrives(wanted); c.saveData() }
    }

    override fun applyProfile(containerId: Int, profile: RuntimeProfile) {
        val c = container(containerId) ?: return
        c.setScreenSize("${profile.width}x${profile.height}")
        c.setGraphicsDriver(graphicsDriverFor(profile))
        c.setDXWrapper(dxWrapperFor(profile))
        c.saveData()
    }

    private fun graphicsDriverFor(p: RuntimeProfile): String {
        val default = defaultDriver()
        val vulkan = default.substringBefore(',')
        return when (p.graphics) {
            GraphicsChoice.RUNTIME_DEFAULT -> default
            GraphicsChoice.SAFER -> "$vulkan,${GraphicsDrivers.VIRGL}"
        }
    }

    private fun dxWrapperFor(p: RuntimeProfile) = when (p.graphics) {
        GraphicsChoice.RUNTIME_DEFAULT -> Container.DEFAULT_DXWRAPPER
        GraphicsChoice.SAFER -> DXWrappers.WINED3D
    }

    override fun launchIntent(containerId: Int, unixPath: String?, shortcutPath: String?, sessionId: String): Intent =
        Intent(context, XServerDisplayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("container_id", containerId)
            if (shortcutPath != null) putExtra("shortcut_path", shortcutPath)
            else if (unixPath != null) putExtra("exec_path", unixPath)
            putExtra(XServerDisplayActivity.EXTRA_HOSTED, true)
            putExtra(XServerDisplayActivity.EXTRA_SESSION_ID, sessionId)
        }

    override fun shortcuts(containerId: Int): List<InstalledShortcut> {
        val c = container(containerId) ?: return emptyList()
        val out = ArrayList<InstalledShortcut>()
        // Winlator's Wine writes a .desktop file (with an icon) for every shortcut an installer creates.
        val desktopRoots = listOf(File(c.userDir, "Desktop"), File(c.rootDir, ".local/share/applications"))
        desktopRoots.forEach { root ->
            root.walkTopDown().maxDepth(6).filter { it.isFile && it.name.endsWith(".desktop") }.forEach { f ->
                out += InstalledShortcut(f.nameWithoutExtension, f.path, iconFor(c, f))
            }
        }
        // Fallback: plain Windows shortcuts in the Start Menu and on the desktops.
        val lnkRoots = listOf(
            File(c.startMenuDir, "Programs"),
            File(c.userDir, "Desktop"),
            File(c.rootDir, ".wine/drive_c/users/Public/Desktop"),
            File(c.userDir, "AppData/Roaming/Microsoft/Windows/Start Menu/Programs"),
        )
        lnkRoots.forEach { root ->
            root.walkTopDown().maxDepth(6).filter { it.isFile && it.name.endsWith(".lnk", ignoreCase = true) }.forEach { f ->
                out += InstalledShortcut(f.nameWithoutExtension, f.path, null)
            }
        }
        // Fallback for installers that create no shortcuts: program files they put in Program Files.
        listOf("Program Files", "Program Files (x86)").map { File(c.rootDir, ".wine/drive_c/$it") }.forEach { root ->
            root.walkTopDown().maxDepth(3).filter { it.isFile && it.name.endsWith(".exe", ignoreCase = true) }.forEach { f ->
                out += InstalledShortcut(f.nameWithoutExtension, f.path, null)
            }
        }
        return out
    }

    private fun iconFor(c: Container, desktopFile: File): String? {
        val icon = runCatching { desktopFile.readLines().firstOrNull { it.startsWith("Icon=") }?.substringAfter('=') }.getOrNull() ?: return null
        for (size in intArrayOf(64, 48, 128, 32, 256)) {
            val f = File(c.getIconsDir(size), "$icon.png")
            if (f.isFile) return f.path
        }
        return null
    }

    override fun advancedSettingsIntent(): Intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    override fun sessionResultFile(): File = File(context.filesDir, "pocketos_runtime/last_session.json")

    override fun installedSize(): Long = runCatching {
        RootFS.find(context).rootDir.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    companion object {
        const val CONTAINER_NAME = "PocketOS"
        private const val KEY_DEFAULT_DRIVER = "default_graphics_driver"
        private const val FALLBACK_DRIVER = "vortek,gladio"

        /** Winlator's drive format: "D:/path/oneE:/path/two". */
        fun encodeDrives(drives: Map<Char, String>): String = drives.toSortedMap().entries.joinToString("") { (d, p) -> "$d:$p" }
    }
}

package com.chronos.pocketos.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * PocketOS's Windows engine: Wine 11 built for Android (see engine/wine/build.sh) plus the FEX emulator DLLs that run
 * x86 and x64 code on ARM. There is no Linux system image and no X server: Wine runs as Android processes from the
 * app's private files and draws its windows through [WineHost] onto the PocketOS desktop.
 *
 * Layout under files/engine: `arm64-v8a/bin/wineserver`, `arm64-v8a/lib/wine/{aarch64-unix,aarch64-windows,
 * i386-windows}`, `share/wine`, the FEX DLLs in `fex`, `VERSION`. The Windows environment ("prefix") is files/wineprefix.
 */
class WineEngine(private val context: Context) {
    val root = File(context.filesDir, "engine")
    val prefix = File(context.filesDir, "wineprefix")
    private val abiDir = File(root, "arm64-v8a")
    private val libDir = File(abiDir, "lib")
    val unixDir = File(libDir, "wine/aarch64-unix")
    val loader = File(unixDir, "wine")
    val server = File(abiDir, "bin/wineserver")
    private val logDir = File(context.filesDir, "engine-logs").apply { mkdirs() }
    private val homeDir = File(context.filesDir, "winehome")
    private val main = Handler(Looper.getMainLooper())

    /** Page size of this phone's kernel; Wine on ARM needs 4 KB pages. */
    val pageSize: Long get() = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(4096L)

    fun bundledVersion(): String? = runCatching {
        context.assets.open("$ASSET_DIR/VERSION").bufferedReader().readText().trim()
    }.getOrNull()

    fun installedVersion(): String? = File(root, "VERSION").takeIf { it.isFile }?.readText()?.trim()

    fun isInstalled(): Boolean = loader.canExecute() && server.canExecute() && File(unixDir, "wineandroid.so").isFile &&
        installedVersion() != null && installedVersion() == bundledVersion()

    /** Unpacks the engine from the APK. Blocking. */
    fun install(progress: (Int) -> Unit): Boolean {
        val parts = runCatching { context.assets.list(ASSET_DIR)?.filter { it.endsWith(".zip") }?.sorted() }.getOrNull().orEmpty()
        if (parts.isEmpty()) return false
        val total = parts.sumOf { name -> runCatching { context.assets.openFd("$ASSET_DIR/$name").use { it.length } }.getOrDefault(1L) }
        var done = 0L
        val tmp = File(context.filesDir, "engine.new")
        tmp.deleteRecursively()
        try {
            for (name in parts) {
                val size = runCatching { context.assets.openFd("$ASSET_DIR/$name").use { it.length } }.getOrDefault(1L)
                val before = done
                CountingStream(context.assets.open("$ASSET_DIR/$name")) { read ->
                    progress((((before + read) * 100) / total).toInt().coerceIn(0, 99))
                }.use { input -> unzip(ZipInputStream(input), tmp) }
                done += size
            }
            context.assets.open("$ASSET_DIR/VERSION").use { inp -> File(tmp, "VERSION").outputStream().use { inp.copyTo(it) } }
            root.deleteRecursively()
            if (!tmp.renameTo(root)) return false
            progress(100)
            return isInstalled()
        } catch (e: IOException) {
            Log.e(TAG, "engine install failed", e)
            tmp.deleteRecursively()
            return false
        }
    }

    private fun unzip(zip: ZipInputStream, dest: File) {
        val canonical = dest.canonicalPath + File.separator
        while (true) {
            val e = zip.nextEntry ?: break
            val out = File(dest, e.name)
            if (!out.canonicalPath.startsWith(canonical)) throw IOException("bad entry ${e.name}")
            if (e.isDirectory) { out.mkdirs(); continue }
            out.parentFile?.mkdirs()
            // Symlinks are stored as small text entries ending in ".symlink" holding the target.
            if (e.name.endsWith(SYMLINK_SUFFIX)) {
                val link = File(dest, e.name.removeSuffix(SYMLINK_SUFFIX))
                link.delete()
                Os.symlink(zip.readBytes().toString(Charsets.UTF_8).trim(), link.path)
                continue
            }
            out.outputStream().use { zip.copyTo(it) }
            if (isExecutable(e.name)) out.setExecutable(true, true)
        }
    }

    private fun isExecutable(name: String) =
        name.startsWith("arm64-v8a/bin/") || (name.startsWith("arm64-v8a/lib/") && !name.contains("-windows/"))

    /** True once the Windows environment exists (Wine creates it on the first start). */
    fun prefixReady(): Boolean = File(prefix, "system.reg").isFile && File(prefix, "drive_c/windows/system32").isDirectory &&
        File(prefix, ".pocketos-ready").isFile

    /** Environment for every Wine process. */
    fun environment(debug: String? = null): Map<String, String> {
        homeDir.mkdirs()
        val locale = Locale.getDefault().let { "${it.language}_${it.country.ifEmpty { "US" }}.UTF-8" }
        return mapOf(
            "WINEPREFIX" to prefix.path,
            "WINESERVER" to server.path,
            "HOME" to homeDir.path,
            "USER" to "pocketos",
            "TMPDIR" to context.cacheDir.path,
            "LD_LIBRARY_PATH" to "${libDir.path}:${context.applicationInfo.nativeLibraryDir}",
            "LANG" to locale,
            "LC_ALL" to locale,
            "WINEDEBUG" to (debug ?: "-all,err+all"),
            // No Linux desktop menus; .NET and HTML engines are not bundled (programs that need them say so).
            "WINEDLLOVERRIDES" to "winemenubuilder.exe=d;mscoree=d;mshtml=d",
        )
    }

    /**
     * Starts a Wine program: [args] are passed to Wine's loader (e.g. `start.exe /wait /unix /path/setup.exe`).
     * Output goes to [log]. The process is a child of PocketOS; Wine itself keeps running until [shutdown].
     */
    fun start(args: List<String>, log: File = File(logDir, "wine.log"), debug: String? = null): Process {
        prefix.mkdirs()
        val pb = ProcessBuilder(listOf(loader.path) + args)
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        pb.environment().putAll(environment(debug))
        return pb.start()
    }

    /** Runs a Wine command and waits for it (used for setup steps). Returns the exit code, or null on timeout. */
    fun run(args: List<String>, timeoutSeconds: Long = 600): Int? {
        val p = start(args, File(logDir, "setup.log"))
        return if (p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) p.exitValue() else { p.destroy(); null }
    }

    /** Loads the display driver into PocketOS (on the main thread) so Wine can show windows. Blocking. */
    fun loadDriver(host: WineHost) {
        if (host.isNativeReady) return
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        main.post {
            try { host.loadDriver(unixDir.path) } catch (t: Throwable) { error = t } finally { latch.countDown() }
        }
        latch.await(30, TimeUnit.SECONDS)
        error?.let { throw it }
    }

    /** Starts Wine's (invisible) desktop; Windows programs then attach to it. */
    fun startDesktop() {
        start(listOf("explorer.exe", "/desktop=Default,,android"), File(logDir, "desktop.log"))
    }

    /**
     * First start: Wine creates the Windows environment (a few minutes on a phone), then PocketOS adds the x86/x64
     * emulators and its drives. Blocking.
     */
    fun preparePrefix(drives: Map<Char, String>, status: (String) -> Unit): Boolean {
        if (!prefixReady()) {
            status("Creating the Windows environment (first start takes a few minutes)\u2026")
            run(listOf("wineboot.exe", "--init"), timeoutSeconds = 1200) ?: return false
            if (!File(prefix, "drive_c/windows/system32").isDirectory) return false
            status("Adding the x86 and x64 emulators\u2026")
            installEmulators()
            File(prefix, ".pocketos-ready").writeText(installedVersion().orEmpty())
        }
        ensureDrives(drives)
        return true
    }

    /** Copies FEX into system32 and tells Wine to use it for x64 (ARM64EC) and 32-bit x86 (WoW64) programs. */
    private fun installEmulators() {
        val sys32 = File(prefix, "drive_c/windows/system32")
        File(root, "fex").listFiles()?.filter { it.name.endsWith(".dll") }?.forEach { it.copyTo(File(sys32, it.name), overwrite = true) }
        run(listOf("reg.exe", "add", "HKLM\\Software\\Microsoft\\Wow64\\amd64", "/ve", "/d", "libarm64ecfex.dll", "/f"))
        run(listOf("reg.exe", "add", "HKLM\\Software\\Microsoft\\Wow64\\x86", "/ve", "/d", "libwow64fex.dll", "/f"))
    }

    /** Maps Windows drive letters to PocketOS folders (D: programs, E: Home). */
    fun ensureDrives(drives: Map<Char, String>) {
        val dos = File(prefix, "dosdevices").apply { mkdirs() }
        for ((letter, path) in drives) {
            val link = File(dos, "${letter.lowercaseChar()}:")
            val current = runCatching { Os.readlink(link.path) }.getOrNull()
            if (current == path) continue
            link.delete()
            runCatching { Os.symlink(path, link.path) }.onFailure { Log.w(TAG, "drive $letter", it) }
        }
    }

    /** Sets the Windows version Wine reports to one program (e.g. "win7"), or the default for null. */
    fun setWindowsVersion(exeName: String, version: String?) {
        val key = "HKCU\\Software\\Wine\\AppDefaults\\$exeName"
        if (version == null) run(listOf("reg.exe", "delete", key, "/v", "Version", "/f"), 60)
        else run(listOf("reg.exe", "add", key, "/v", "Version", "/d", version, "/f"), 60)
    }

    /** Ends every Windows program and Wine itself. */
    fun shutdown() {
        if (!server.canExecute()) return
        runCatching {
            val pb = ProcessBuilder(server.path, "-k").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(File(logDir, "setup.log")))
            pb.environment().putAll(environment())
            pb.start().waitFor(10, TimeUnit.SECONDS)
        }
    }

    fun installedBytes(): Long = sizeOf(root) + sizeOf(prefix)

    private fun sizeOf(f: File): Long = f.walkTopDown().onEnter { !isSymlink(it) }.filter { it.isFile }.sumOf { it.length() }
    private fun isSymlink(f: File) = runCatching { f.canonicalPath != f.absolutePath && f != f.canonicalFile }.getOrDefault(false)

    fun logFile(name: String) = File(logDir, name)

    private class CountingStream(private val inner: java.io.InputStream, private val onRead: (Long) -> Unit) : java.io.InputStream() {
        private var count = 0L
        private var lastReport = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) bump(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) bump(it.toLong()) }
        private fun bump(n: Long) { count += n; if (count - lastReport > 1 shl 20) { lastReport = count; onRead(count) } }
        override fun close() = inner.close()
    }

    companion object {
        private const val TAG = "PocketOS-Engine"
        const val ASSET_DIR = "engine"
        const val SYMLINK_SUFFIX = ".symlink"
    }
}

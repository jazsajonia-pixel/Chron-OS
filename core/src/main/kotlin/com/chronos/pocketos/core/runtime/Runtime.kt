package com.chronos.pocketos.core.runtime

/**
 * PocketOS includes its own Windows engine: Wine built for Android (no Linux system image, no X server) with the
 * FEX emulator for x86 and x64 code. Windows programs run as processes of the app and draw their windows directly on
 * the PocketOS desktop. PocketOS still never reports success it cannot observe: a program "works" only when the user
 * says so, and the self-test proves execution with a marker file. See docs/WINDOWS_ENGINE.md.
 */
data class RuntimeInfo(
    val id: String,
    val name: String,
    val engine: String,
    val license: String,
    val homepage: String,
    val requiredAbi: String,
    /** Windows drive with the programs PocketOS imported. */
    val programsDrive: Char,
    /** Windows drive with the PocketOS Home folder, so programs can open and save the user's files. */
    val homeDrive: Char,
    /** Approximate space the system files need once installed. */
    val installedBytes: Long,
)

object RuntimeCatalog {
    val BUILTIN = RuntimeInfo(
        id = "builtin",
        name = "PocketOS Windows runtime",
        engine = "Wine 11.19 + FEX 2610",
        license = "Wine: LGPL-2.1-or-later; FEX: MIT",
        homepage = "https://www.winehq.org",
        requiredAbi = "arm64-v8a",
        programsDrive = 'D',
        homeDrive = 'E',
        installedBytes = 900L * 1024 * 1024,
    )
    val all = listOf(BUILTIN)
}

/** State of the built-in runtime on this phone. */
sealed interface RuntimeState {
    /** The phone cannot run the runtime at all (it needs a 64-bit ARM processor). */
    data class Unsupported(val reason: String) : RuntimeState
    /** The Windows engine has not been unpacked yet. */
    data object NotInstalled : RuntimeState
    data class Installing(val percent: Int, val stage: String) : RuntimeState
    data object Ready : RuntimeState
    data class Failed(val message: String) : RuntimeState
}

object RuntimeStateRules {
    fun initial(runtime: RuntimeInfo, deviceAbis: List<String>, systemFilesInstalled: Boolean, containerReady: Boolean): RuntimeState {
        if (runtime.requiredAbi !in deviceAbis) {
            return RuntimeState.Unsupported(
                "The Windows runtime needs a 64-bit ARM phone (${runtime.requiredAbi}). This device reports: ${deviceAbis.joinToString().ifEmpty { "unknown" }}.",
            )
        }
        return if (systemFilesInstalled && containerReady) RuntimeState.Ready else RuntimeState.NotInstalled
    }
}

/** Windows paths for files PocketOS keeps in its own programs folder (mapped as [RuntimeInfo.programsDrive]). */
object RuntimePaths {
    fun windowsPath(drive: Char, relative: String): String =
        "$drive:\\" + relative.trim('/').replace('/', '\\')
}

/** What a launch was for, so PocketOS knows what to do when the program exits. */
enum class SessionPurpose { RUN, INSTALL, SAMPLE, DESKTOP }

/** A shortcut (or, as a fallback, a program file) found inside the runtime after an installer ran. */
data class InstalledShortcut(
    val name: String,
    /** Absolute path of the .desktop, .lnk or .exe file inside the runtime's container. */
    val path: String,
    val iconPath: String? = null,
) {
    val isExecutable: Boolean get() = path.endsWith(".exe", ignoreCase = true)
}

object InstalledProgramDiscovery {
    private val noise = Regex(
        """\b(uninstall\w*|uninst\w*|remove|readme|read me|help|manual|documentation|license|licence|website|web site|homepage|support|changelog|release notes|what's new)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val helperExe = Regex(
        """^(unins\d*|setup|install|installer|update|updater|autoupdate|crashreporter|crashhandler|vc_?redist.*|dxsetup|dotnetfx.*|helper|launcherhelper)$""",
        RegexOption.IGNORE_CASE,
    )
    const val MAX_EXECUTABLES = 5

    /**
     * Programs that appeared since [before]. Shortcuts (Start Menu / Desktop) are preferred, without uninstallers,
     * manuals or web links. Only if an installer created no shortcuts at all are new .exe files in Program Files
     * offered instead (at most [MAX_EXECUTABLES], skipping uninstallers, updaters and redistributables).
     */
    fun newPrograms(before: Set<String>, after: List<InstalledShortcut>): List<InstalledShortcut> {
        val fresh = after.filter { it.path !in before }
        val shortcuts = fresh.filter { !it.isExecutable && !noise.containsMatchIn(it.name) }
        val picked = shortcuts.ifEmpty {
            fresh.filter { it.isExecutable && !noise.containsMatchIn(it.name) && !helperExe.matches(it.name) }
                .sortedBy { it.path.count { c -> c == '/' } }
                .take(MAX_EXECUTABLES)
        }
        // Prefer .desktop entries (they carry icons) over the raw .lnk with the same name.
        return picked.sortedBy { if (it.path.endsWith(".desktop")) 0 else 1 }
            .distinctBy { it.name.lowercase() }
            .sortedBy { it.name.lowercase() }
    }
}

enum class SampleResult { VERIFIED, NOT_RUN_YET, STALE_OR_FOREIGN }

/** Verifies the sample program's marker file, proving that it actually executed. */
object SampleVerification {
    const val SAMPLE_FOLDER = "PocketOS Sample"
    const val SAMPLE_EXE = "pocketos-sample.exe"
    const val NONCE_FILE = "pocketos_nonce.txt"
    const val MARKER_FILE = "pocketos_sample_ok.txt"
    private const val PREFIX = "POCKETOS_FIXTURE_OK "

    fun newNonce(random: java.util.Random = java.security.SecureRandom()): String =
        (1..16).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")

    fun check(markerContent: String?, expectedNonce: String): SampleResult {
        if (markerContent == null) return SampleResult.NOT_RUN_YET
        val line = markerContent.lineSequence().firstOrNull()?.trim() ?: return SampleResult.NOT_RUN_YET
        return if (line == PREFIX + expectedNonce) SampleResult.VERIFIED else SampleResult.STALE_OR_FOREIGN
    }
}

data class FriendlyError(val title: String, val advice: String, val evidence: String)

/** Translates common Wine/Box64 log lines into plain language. Unknown lines are not guessed at. */
object RuntimeLogTranslator {
    private data class Rule(val regex: Regex, val make: (MatchResult) -> Pair<String, String>)

    private val rules = listOf(
        Rule(Regex("""Library (\S+?\.dll) \(which is needed by [^)]*\) not found""", RegexOption.IGNORE_CASE)) {
            "A required component is missing (${it.groupValues[1]})" to
                "The program needs an extra Windows component. Its installer may provide it, or add it in PC Apps → Runtime → Advanced runtime settings."
        },
        Rule(Regex("""Bad EXE format for (.+)|Application could not be started, or no application associated""", RegexOption.IGNORE_CASE)) {
            "This file is not a program the runtime can start" to "It may be damaged, for another kind of computer, or not a Windows program."
        },
        Rule(Regex("""could not load kernel32\.dll""", RegexOption.IGNORE_CASE)) {
            "The runtime is not set up correctly" to "Repair the runtime in PC Apps → Runtime, then try again."
        },
        Rule(Regex("""Unhandled page fault|unhandled exception""", RegexOption.IGNORE_CASE)) {
            "The program crashed" to "Tap “Didn't work”: PocketOS tries safer settings on the next start."
        },
        Rule(Regex("""(?:cannot find|could not find|File not found)[: ]+['"]?([^'"\r\n]+)""", RegexOption.IGNORE_CASE)) {
            "A file could not be found (${it.groupValues[1].trim()})" to "Some programs need their whole folder. Import the installer instead of only the .exe."
        },
        Rule(Regex("""Unsupported (?:16-bit|DOS) application|win16 application""", RegexOption.IGNORE_CASE)) {
            "Very old 16-bit program" to "16-bit and DOS programs are not supported."
        },
        Rule(Regex("""(?:Vulkan|vkCreateInstance).*(?:fail|error)|(?:wined3d|d3d).*(?:failed|not supported)""", RegexOption.IGNORE_CASE)) {
            "The graphics system could not start" to "Tap “Didn't work”: PocketOS switches to the compatibility graphics driver and a lower resolution next time."
        },
        Rule(Regex("""Out of memory|ENOMEM|Cannot allocate memory""", RegexOption.IGNORE_CASE)) {
            "The phone ran out of memory" to "Close other apps and windows, then try the Battery Saver profile."
        },
    )

    fun translate(log: String, max: Int = 5): List<FriendlyError> {
        val out = LinkedHashMap<String, FriendlyError>()
        for (line in log.lineSequence()) {
            for (r in rules) {
                val m = r.regex.find(line) ?: continue
                val (title, advice) = r.make(m)
                out.putIfAbsent(title, FriendlyError(title, advice, line.trim().take(200)))
            }
            if (out.size >= max) break
        }
        return out.values.toList()
    }
}

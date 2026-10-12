package com.chronos.pocketos.core.runtime

/**
 * PocketOS does not contain a Windows runtime. It hands staged programs to a separately installed,
 * openly licensed runtime app and never reports success it cannot observe. See docs/PHASE5_RUNTIME.md.
 */
data class RuntimeInfo(
    val id: String,
    val name: String,
    val packageName: String,
    val license: String,
    val homepage: String,
    val requiredAbi: String,
    /** Windows drive letter the runtime maps to the phone's public Download folder by default. */
    val downloadsDrive: Char?,
)

object RuntimeCatalog {
    val WINLATOR = RuntimeInfo(
        id = "winlator",
        name = "Winlator",
        packageName = "com.winlator",
        license = "LGPL-2.1",
        homepage = "https://github.com/brunodev85/winlator",
        requiredAbi = "arm64-v8a",
        downloadsDrive = 'D',
    )
    val all = listOf(WINLATOR)
}

sealed interface RuntimeAvailability {
    data class Installed(val versionName: String?, val versionCode: Long) : RuntimeAvailability
    data object NotInstalled : RuntimeAvailability
    data class DeviceIncompatible(val reason: String) : RuntimeAvailability
}

object RuntimeAvailabilityRules {
    fun evaluate(runtime: RuntimeInfo, deviceAbis: List<String>, installedVersion: Pair<String?, Long>?): RuntimeAvailability {
        if (runtime.requiredAbi !in deviceAbis) {
            return RuntimeAvailability.DeviceIncompatible(
                "${runtime.name} needs a 64-bit ARM phone (${runtime.requiredAbi}). This device reports: ${deviceAbis.joinToString().ifEmpty { "unknown" }}.",
            )
        }
        return installedVersion?.let { RuntimeAvailability.Installed(it.first, it.second) } ?: RuntimeAvailability.NotInstalled
    }
}

/** Where a staged file appears inside the runtime, derived from the Android storage document id. */
sealed interface RuntimePath {
    data class Mapped(val windowsPath: String) : RuntimePath
    /** The folder is not on a default drive; the user must add it as a drive in the runtime's container settings. */
    data class NeedsDrive(val androidPath: String) : RuntimePath
}

object RuntimePaths {
    /**
     * [treeDocumentId] is the ExternalStorageProvider id, e.g. "primary:Download/PocketOS".
     * [relative] is the path inside that folder, e.g. "Sample/pocketos-sample.exe".
     */
    fun locate(runtime: RuntimeInfo, treeDocumentId: String, relative: String): RuntimePath {
        val volume = treeDocumentId.substringBefore(':', "")
        val path = treeDocumentId.substringAfter(':', "").trim('/')
        val rel = relative.trim('/')
        val drive = runtime.downloadsDrive
        if (volume == "primary" && drive != null && (path == "Download" || path.startsWith("Download/"))) {
            val inner = (path.removePrefix("Download").trim('/') + "/" + rel).trim('/')
            return RuntimePath.Mapped("$drive:\\" + inner.replace('/', '\\'))
        }
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return RuntimePath.NeedsDrive(("$root/$path/$rel").replace("//", "/"))
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
                "The program needs an extra Windows component. Its installer or the runtime's component settings may provide it."
        },
        Rule(Regex("""Bad EXE format for (.+)|Application could not be started, or no application associated""", RegexOption.IGNORE_CASE)) {
            "This file is not a program the runtime can start" to "It may be damaged, for another kind of computer, or not a Windows program."
        },
        Rule(Regex("""could not load kernel32\.dll""", RegexOption.IGNORE_CASE)) {
            "The runtime is not set up correctly" to "Reopen the runtime app so it can finish setting up, or reinstall it."
        },
        Rule(Regex("""Unhandled page fault|unhandled exception""", RegexOption.IGNORE_CASE)) {
            "The program crashed" to "Try the safe settings preset, or check whether the program is known to work with the runtime."
        },
        Rule(Regex("""(?:cannot find|could not find|File not found)[: ]+['"]?([^'"\r\n]+)""", RegexOption.IGNORE_CASE)) {
            "A file could not be found (${it.groupValues[1].trim()})" to "Make sure the program's whole folder was copied, not only the .exe."
        },
        Rule(Regex("""Unsupported (?:16-bit|DOS) application|win16 application""", RegexOption.IGNORE_CASE)) {
            "Very old 16-bit program" to "16-bit and DOS programs are not supported."
        },
        Rule(Regex("""(?:Vulkan|vkCreateInstance).*(?:fail|error)|(?:wined3d|d3d).*(?:failed|not supported)""", RegexOption.IGNORE_CASE)) {
            "The graphics system could not start" to "Try a different graphics driver in the runtime, or a lower resolution."
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

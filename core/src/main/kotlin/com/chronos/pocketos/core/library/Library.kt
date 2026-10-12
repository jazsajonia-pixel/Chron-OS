package com.chronos.pocketos.core.library

import com.chronos.pocketos.core.capability.PerformanceProfile
import com.chronos.pocketos.core.capability.ProfileSettings
import com.chronos.pocketos.core.pe.FileKind
import com.chronos.pocketos.core.pe.InstallerType
import com.chronos.pocketos.core.pe.Machine
import com.chronos.pocketos.core.pe.PeReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class AppStatus(val label: String) { READY("Ready"), NEEDS_SETUP("Needs Setup"), UNSUPPORTED("Unsupported"), FAILED("Failed") }

/** What the user reported after trying the program in the runtime. PocketOS cannot observe arbitrary programs. */
enum class UserResult { WORKED, DID_NOT_WORK }

enum class GraphicsChoice(val label: String) { RUNTIME_DEFAULT("Runtime default"), SAFER("Safer driver (e.g. VirGL)") }

/** Settings recommended for the runtime. PocketOS cannot set them inside the runtime; the user applies them there. */
@Serializable
data class RuntimeProfile(
    val width: Int = 1280,
    val height: Int = 720,
    val graphics: GraphicsChoice = GraphicsChoice.RUNTIME_DEFAULT,
) {
    val label: String get() = "${width}×${height}, ${graphics.label}"

    companion object {
        /** Safe default derived from the device profile (Phase 4). */
        fun safeDefault(p: PerformanceProfile): RuntimeProfile {
            val s = ProfileSettings.of(p)
            val (w, h) = if (s.runtimeMaxWidth >= 1280) 1280 to 720 else s.runtimeMaxWidth to s.runtimeMaxHeight
            return RuntimeProfile(w, h, GraphicsChoice.RUNTIME_DEFAULT)
        }
    }
}

@Serializable
data class LogLine(val at: Long, val text: String)

@Serializable
data class LibraryEntry(
    val id: String,
    val displayName: String,
    val sourceFileName: String,
    val sha256: String,
    val sizeBytes: Long,
    val kind: FileKind,
    val machine: Machine,
    val installer: InstallerType,
    val status: AppStatus,
    val statusDetail: String,
    /** Path relative to the shared folder, e.g. "Apps/Sample Notes/setup.exe". Null if not copied. */
    val stagedPath: String? = null,
    val runtimeId: String = "winlator",
    val profile: RuntimeProfile = RuntimeProfile(),
    val knownGood: RuntimeProfile? = null,
    val recoveryStep: Int = 0,
    val userResult: UserResult? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val log: List<LogLine> = emptyList(),
) {
    fun withLog(now: Long, text: String) = copy(updatedAt = now, log = (log + LogLine(now, text)).takeLast(MAX_LOG))

    companion object { const val MAX_LOG = 100 }
}

@Serializable
data class LibraryState(val version: Int = 1, val entries: List<LibraryEntry> = emptyList())

object LibraryCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    fun encode(s: LibraryState): String = json.encodeToString(LibraryState.serializer(), s)
    fun decode(text: String?): LibraryState = if (text.isNullOrBlank()) LibraryState() else runCatching { json.decodeFromString(LibraryState.serializer(), text) }.getOrDefault(LibraryState())
}

/** Environment facts needed to decide an entry's status. */
data class SetupEnvironment(val runtimeInstalled: Boolean, val runtimeCompatibleDevice: Boolean, val sharedFolderChosen: Boolean)

data class Assessment(val status: AppStatus, val detail: String, val nextSteps: List<String>)

/**
 * Decides an honest status. "Ready" only means everything PocketOS controls is in place; it never means
 * the program is known to work. Compatibility is reported only from the user's own result.
 */
object Compatibility {
    fun importable(r: PeReport): Assessment? = when (r.kind) {
        FileKind.WINDOWS_PROGRAM, FileKind.MSI_PACKAGE -> null
        FileKind.WINDOWS_LIBRARY -> Assessment(AppStatus.UNSUPPORTED, "This is a library (DLL), not a program.", listOf("Import the program (.exe) that uses it instead."))
        FileKind.DOS_PROGRAM -> Assessment(AppStatus.UNSUPPORTED, "Very old DOS program.", listOf("DOS programs are not supported by PocketOS."))
        FileKind.WIN16_PROGRAM -> Assessment(AppStatus.UNSUPPORTED, "16-bit Windows program.", listOf("16-bit programs are not supported."))
        FileKind.ARCHIVE -> Assessment(AppStatus.UNSUPPORTED, "ZIP archive.", listOf("Extract it first, then import the program inside."))
        FileKind.SCRIPT -> Assessment(AppStatus.UNSUPPORTED, "Script file.", listOf("Scripts are not supported."))
        FileKind.NOT_WINDOWS -> Assessment(AppStatus.UNSUPPORTED, "Not a Windows program.", listOf("Choose a .exe or .msi file."))
        FileKind.DAMAGED -> Assessment(AppStatus.UNSUPPORTED, "The file looks damaged.", listOf("Download it again from the original source."))
    }

    fun assess(e: LibraryEntry, env: SetupEnvironment): Assessment {
        if (e.status == AppStatus.UNSUPPORTED) return Assessment(e.status, e.statusDetail, emptyList())
        if (e.userResult == UserResult.DID_NOT_WORK) {
            return Assessment(AppStatus.FAILED, "You reported that it did not work with ${e.profile.label}.", Recovery.nextAdvice(e))
        }
        if (!env.runtimeCompatibleDevice) return Assessment(AppStatus.NEEDS_SETUP, "This phone cannot use the supported runtime.", listOf("A 64-bit ARM phone is required."))
        val steps = buildList {
            if (!env.runtimeInstalled) add("Install the compatibility runtime (PC Apps → Runtime).")
            if (!env.sharedFolderChosen) add("Choose the shared folder (PC Apps → Runtime).")
            if (e.stagedPath == null) add("Copy the program to the shared folder.")
        }
        if (steps.isNotEmpty()) return Assessment(AppStatus.NEEDS_SETUP, "A few steps are left before you can try it.", steps)
        val tryIt = if (e.kind == FileKind.MSI_PACKAGE || e.installer != InstallerType.NONE)
            "Open the runtime and run the installer. After installing, start the program from the runtime."
        else "Open the runtime and start the program."
        val detail = when (e.userResult) {
            UserResult.WORKED -> "You reported it works with ${e.profile.label}."
            else -> "Ready to try. Whether it works depends on the program; it has not been tested on this phone."
        }
        return Assessment(AppStatus.READY, detail, listOf(tryIt, "Suggested runtime settings: ${e.profile.label}.", "Come back and tell PocketOS whether it worked."))
    }
}

/**
 * Bounded, visible recovery sequence for "did not work" reports. Each step changes one setting and says so.
 * After the last step PocketOS stops suggesting changes.
 */
object Recovery {
    data class Step(val description: String, val apply: (RuntimeProfile) -> RuntimeProfile)

    /** Sets exactly [w]×[h] if that has fewer pixels than the current setting; otherwise keeps it. */
    private fun lowerTo(w: Int, h: Int): (RuntimeProfile) -> RuntimeProfile = { p -> if (w.toLong() * h < p.width.toLong() * p.height) p.copy(width = w, height = h) else p }

    val steps: List<Step> = listOf(
        Step("Lower the resolution to 1024×768", lowerTo(1024, 768)),
        Step("Switch to the safer graphics driver") { it.copy(graphics = GraphicsChoice.SAFER) },
        Step("Lower the resolution to 800×600", lowerTo(800, 600)),
    )
    val MAX_STEPS get() = steps.size

    fun nextAdvice(e: LibraryEntry): List<String> =
        if (e.recoveryStep < steps.size) listOf("Next suggestion (${e.recoveryStep + 1} of ${steps.size}): ${steps[e.recoveryStep].description}.", "Apply it in the runtime's settings, try again, then report the result.")
        else listOf("All ${steps.size} suggestions were tried. This program may not be compatible with the runtime on this phone.", "You can keep the entry, view its log, or remove it.")

    /** Applies the next step; returns null when the sequence is exhausted (no endless retries). */
    fun applyNext(e: LibraryEntry, now: Long): LibraryEntry? {
        if (e.recoveryStep >= steps.size) return null
        val step = steps[e.recoveryStep]
        val before = e.profile
        val after = step.apply(before)
        return e.copy(profile = after, recoveryStep = e.recoveryStep + 1, userResult = null)
            .withLog(now, "Recovery ${e.recoveryStep + 1}/${steps.size}: ${step.description} (${before.label} → ${after.label})")
    }

    /** Saves the current settings as known-good. */
    fun markWorked(e: LibraryEntry, now: Long) = e.copy(userResult = UserResult.WORKED, knownGood = e.profile).withLog(now, "You reported it works with ${e.profile.label}")

    fun markFailed(e: LibraryEntry, now: Long) = e.copy(userResult = UserResult.DID_NOT_WORK).withLog(now, "You reported it did not work with ${e.profile.label}")
}

/** File-name-safe folder name for a library entry. */
fun safeFolderName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trim('.').take(60).ifEmpty { "App" }

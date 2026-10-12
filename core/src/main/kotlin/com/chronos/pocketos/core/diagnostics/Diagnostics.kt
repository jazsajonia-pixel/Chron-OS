package com.chronos.pocketos.core.diagnostics

import com.chronos.pocketos.core.capability.Capability
import com.chronos.pocketos.core.capability.DeviceCapabilities
import com.chronos.pocketos.core.capability.ProfileDecision
import com.chronos.pocketos.core.library.LibraryEntry
import com.chronos.pocketos.core.runtime.RuntimeLogTranslator

/**
 * Removes personal data from text before it is stored or shared: e-mail addresses, long tokens/keys, user names in
 * paths, document URIs, and app-private directories. File contents are never logged in the first place.
 */
object LogSanitizer {
    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to "<email>",
        Regex("""(?i)\b(bearer|token|api[_-]?key|password|passwd|secret|authorization)\b\s*[:=]\s*\S+""") to "$1=<redacted>",
        Regex("""/data/(?:user/\d+|data)/[A-Za-z0-9_.]+""") to "<app-data>",
        Regex("""/storage/emulated/\d+""") to "<storage>",
        Regex("""/storage/[0-9A-F]{4}-[0-9A-F]{4}""") to "<sd-card>",
        Regex("""(?i)([A-Z]:\\+users\\+)[^\\\s"]+""") to "$1<user>",
        Regex("""(?i)(/home/)[^/\s"]+""") to "$1<user>",
        // Long hex/base64-like secrets (keep SHA-256 prefixes, which are ≤ 12 chars in our logs).
        Regex("""\b[A-Za-z0-9+/_-]{32,}={0,2}""") to "<long-token>",
    )

    private val contentUri = Regex("""content://[^\s"')]+""")

    /** Keeps only the file name of a document URI; SAF ids percent-encode the full path into the last segment. */
    private fun shortenUri(uri: String): String {
        val last = uri.trimEnd('/').substringAfterLast('/')
        val decoded = runCatching { java.net.URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
        return "content://…/" + decoded.substringAfterLast('/').substringAfterLast(':')
    }

    fun clean(text: String): String {
        val noUris = contentUri.replace(text) { shortenUri(it.value) }
        return rules.fold(noUris) { acc, (re, rep) -> re.replace(acc, rep) }
    }
}

/** A bounded, sanitized in-memory/on-disk event log. */
class EventLog(private val capacity: Int = 300, private val clock: () -> Long = System::currentTimeMillis) {
    data class Event(val at: Long, val area: String, val text: String)
    private val events = ArrayDeque<Event>()

    @Synchronized fun add(area: String, text: String) {
        events.addLast(Event(clock(), area, LogSanitizer.clean(text).take(400)))
        while (events.size > capacity) events.removeFirst()
    }
    @Synchronized fun snapshot(): List<Event> = events.toList()
    @Synchronized fun clear() = events.clear()
    @Synchronized fun restore(lines: List<Event>) { events.clear(); lines.takeLast(capacity).forEach { events.addLast(it) } }

    companion object {
        fun encode(e: Event) = "${e.at}\t${e.area}\t${e.text.replace('\n', ' ').replace('\t', ' ')}"
        fun decode(line: String): Event? = line.split('\t', limit = 3).takeIf { it.size == 3 }?.let { p -> p[0].toLongOrNull()?.let { Event(it, p[1], p[2]) } }
    }
}

/** Decides whether to start in safe mode (skip restoring the last desktop session) after repeated early crashes. */
object CrashLoopGuard {
    const val WINDOW_MS = 60_000L
    const val THRESHOLD = 2

    /** [recentCrashes] are timestamps of crashes that happened within a few seconds of starting. */
    fun shouldStartSafe(recentCrashes: List<Long>, now: Long): Boolean = recentCrashes.count { now - it in 0..WINDOW_MS * 10 } >= THRESHOLD

    /** Keeps only crash records that still matter. */
    fun prune(recentCrashes: List<Long>, now: Long): List<Long> = recentCrashes.filter { now - it in 0..WINDOW_MS * 10 }.takeLast(5)
}

/**
 * Compatibility report shared only by explicit user action, after a preview. Contains no account names, serial numbers,
 * location, contacts, file contents, or full file paths.
 */
object CompatibilityReport {
    fun build(
        pocketOsVersion: String,
        caps: DeviceCapabilities,
        profile: ProfileDecision,
        runtimeName: String,
        runtimeVersion: String?,
        entry: LibraryEntry?,
        runtimeLog: String?,
        maxLogLines: Int = 40,
    ): String = buildString {
        appendLine("PocketOS compatibility report")
        appendLine("format: 1")
        appendLine("pocketos: $pocketOsVersion")
        appendLine()
        appendLine("[device]")
        appendLine("model: ${caps.manufacturer} ${caps.model}")
        appendLine("android: ${caps.androidRelease} (API ${caps.apiLevel})")
        appendLine("abis: ${caps.abis.joinToString()}")
        appendLine("memory: " + when (val r = caps.totalRamBytes) { is Capability.Known -> "${(r.value + (1L shl 29)) / (1L shl 30)} GB"; else -> "unknown" })
        appendLine("gpu: OpenGL ES ${caps.openGlEs.valueOrNull() ?: "unknown"}, Vulkan ${caps.vulkan.valueOrNull() ?: "unknown"}")
        appendLine("profile: ${profile.profile.label}${if (profile.automatic) " (auto)" else " (manual)"}")
        appendLine()
        appendLine("[runtime]")
        appendLine("name: $runtimeName")
        appendLine("version: ${runtimeVersion ?: "not installed"}")
        if (entry != null) {
            appendLine()
            appendLine("[app]")
            appendLine("name: ${LogSanitizer.clean(entry.displayName)}")
            appendLine("file: ${LogSanitizer.clean(entry.sourceFileName)} (${entry.sizeBytes} bytes)")
            appendLine("sha256: ${entry.sha256}")
            appendLine("type: ${entry.kind}, ${entry.machine}, installer ${entry.installer}")
            appendLine("settings: ${entry.profile.label}")
            appendLine("status: ${entry.status.label}; user result: ${entry.userResult ?: "not reported"}; suggestions used: ${entry.recoveryStep}")
            entry.knownGood?.let { appendLine("known good: ${it.label}") }
        }
        if (!runtimeLog.isNullOrBlank()) {
            appendLine()
            appendLine("[errors]")
            val errs = RuntimeLogTranslator.translate(runtimeLog)
            if (errs.isEmpty()) appendLine("none recognised")
            errs.forEach { appendLine("- ${it.title}") }
            appendLine()
            appendLine("[log excerpt, sanitized, last $maxLogLines lines]")
            runtimeLog.lines().filter { it.isNotBlank() }.takeLast(maxLogLines).forEach { appendLine(LogSanitizer.clean(it).take(300)) }
        }
        appendLine()
        appendLine("Note: compatibility is not guaranteed. This report describes one attempt on one device.")
    }
}

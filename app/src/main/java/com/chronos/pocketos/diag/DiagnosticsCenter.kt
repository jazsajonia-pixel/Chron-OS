package com.chronos.pocketos.diag

import android.content.Context
import android.os.SystemClock
import com.chronos.pocketos.core.diagnostics.CrashLoopGuard
import com.chronos.pocketos.core.diagnostics.EventLog
import com.chronos.pocketos.core.diagnostics.LogSanitizer
import java.io.File

/**
 * Local-only diagnostics: a sanitized event log, crash records, and crash-loop safe mode. Nothing is sent anywhere;
 * the user can view, clear, or explicitly share it.
 */
class DiagnosticsCenter(private val context: Context) {
    val log = EventLog(300)
    private val file = File(context.filesDir, "diagnostics.log")
    private val prefs = context.getSharedPreferences("pocketos_diag", Context.MODE_PRIVATE)
    private val startedAt = SystemClock.elapsedRealtime()

    /** Set at startup: true when PocketOS crashed early twice recently, so the last session is not restored. */
    val safeMode: Boolean
    /** Sanitized summary of the previous crash, if the last run ended in one. */
    val previousCrash: String?

    init {
        runCatching { log.restore(file.readLines().mapNotNull(EventLog::decode)) }
        val now = System.currentTimeMillis()
        val crashes = prefs.getString(KEY_EARLY_CRASHES, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        safeMode = CrashLoopGuard.shouldStartSafe(crashes, now)
        prefs.edit().putString(KEY_EARLY_CRASHES, CrashLoopGuard.prune(crashes, now).joinToString(",")).apply()
        previousCrash = prefs.getString(KEY_LAST_CRASH, null)
        prefs.edit().remove(KEY_LAST_CRASH).apply()
        if (previousCrash != null) log.add("crash", "Previous run ended unexpectedly: $previousCrash")
        if (safeMode) log.add("crash", "Safe start: the last desktop session was not restored after repeated crashes")
    }

    fun add(area: String, text: String) { log.add(area, text) }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { recordCrash(e) }
            previous?.uncaughtException(t, e)
        }
    }

    /** Stores the exception type and top frames only (no messages, which can contain user data). */
    fun recordCrash(e: Throwable) {
        val frames = e.stackTrace.take(8).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        val summary = LogSanitizer.clean("${e.javaClass.name} at $frames")
        val early = SystemClock.elapsedRealtime() - startedAt < 15_000
        val crashes = prefs.getString(KEY_EARLY_CRASHES, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        prefs.edit()
            .putString(KEY_LAST_CRASH, summary)
            .putString(KEY_EARLY_CRASHES, (if (early) crashes + System.currentTimeMillis() else crashes).joinToString(","))
            .commit() // synchronous: the process is about to die
        log.add("crash", summary)
        save()
    }

    fun save() { runCatching { file.writeText(log.snapshot().joinToString("\n", transform = EventLog::encode)) } }

    fun clear() { log.clear(); file.delete() }

    companion object {
        private const val KEY_LAST_CRASH = "last_crash"
        private const val KEY_EARLY_CRASHES = "early_crashes"
    }
}

package com.chronos.pocketos.diag

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.chronos.pocketos.BuildConfig
import com.chronos.pocketos.PocketOsApp
import com.chronos.pocketos.core.diagnostics.CompatibilityReport
import com.chronos.pocketos.core.library.safeFolderName
import java.io.File

data class StorageUsage(
    val homeBytes: Long,
    val tempImportBytes: Long,
    val reportBytes: Long,
    /** Copies of imported programs (drive D: of the runtime). */
    val programsBytes: Long,
    /** Runtime system files and the Windows environment, including installed programs. */
    val runtimeBytes: Long,
    val orphanFolders: List<String>,
)

/** Storage accounting and cleanup limited to files PocketOS manages. */
class StorageManager(private val app: PocketOsApp) {
    private val reportsDir get() = File(app.cacheDir, "reports")
    private val importsDir get() = File(app.cacheDir, "imports")

    private fun File.bytes(): Long = if (!exists()) 0 else walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun usage(): StorageUsage {
        val store = app.runtime.programs
        val referenced = app.library.state.value.entries.mapNotNull { it.stagedPath?.substringBeforeLast('/')?.substringAfter("Apps/") }.toSet()
        val orphans = runCatching { store.list("Apps").filter { it !in referenced } }.getOrDefault(emptyList())
        return StorageUsage(
            homeBytes = File(app.filesDir, "home").bytes(),
            tempImportBytes = if (app.imports.state.value is com.chronos.pocketos.library.ImportState.Idle) importsDir.bytes() else 0,
            reportBytes = reportsDir.bytes(),
            programsBytes = runCatching { store.sizeOf("Apps") + store.sizeOf(com.chronos.pocketos.core.runtime.SampleVerification.SAMPLE_FOLDER) }.getOrDefault(0L),
            runtimeBytes = runCatching { app.runtime.installedSize() }.getOrDefault(0L),
            orphanFolders = orphans,
        )
    }

    /** Deletes temporary imports (if no import is running) and old reports. Returns bytes freed. */
    fun clearTemporary(): Long {
        var freed = 0L
        if (app.imports.state.value is com.chronos.pocketos.library.ImportState.Idle) freed += app.imports.cleanupTemp()
        freed += reportsDir.bytes(); reportsDir.deleteRecursively()
        app.diagnostics.add("storage", "Cleared temporary files: $freed bytes")
        return freed
    }

    /** Deletes copied program folders that no library entry refers to. */
    fun deleteOrphans(): Int {
        val store = app.runtime.programs
        val names = usage().orphanFolders
        val n = names.count { runCatching { store.delete("Apps/$it") }.getOrDefault(false) }
        app.diagnostics.add("storage", "Removed $n leftover program folders")
        return n
    }

    fun buildReport(entryId: String?, runtimeLog: String?): String {
        val rm = app.runtime
        return CompatibilityReport.build(
            pocketOsVersion = BuildConfig.VERSION_NAME,
            caps = app.capabilities.capabilities.value,
            profile = app.capabilities.decision.value,
            runtimeName = rm.runtime.name,
            runtimeVersion = rm.runtime.engine + " (" + rm.state.value.javaClass.simpleName + ")",
            entry = entryId?.let(app.library::get),
            runtimeLog = runtimeLog,
        )
    }

    /** Writes the report to a private cache file and returns a share intent (the user picks where it goes). */
    fun shareIntent(report: String, label: String?): Intent {
        reportsDir.mkdirs()
        val f = File(reportsDir, "pocketos-report-${safeFolderName(label ?: "device").replace(' ', '_')}-${System.currentTimeMillis()}.txt")
        f.writeText(report)
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "PocketOS compatibility report").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Share report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Saves the report into PocketOS Home → Documents. Returns the relative path. */
    fun saveToHome(report: String): String {
        val rel = "Documents/pocketos-report-${System.currentTimeMillis()}.txt"
        app.home.writeText(rel, report)
        return rel
    }

    companion object { fun of(context: Context) = StorageManager(context.applicationContext as PocketOsApp) }
}

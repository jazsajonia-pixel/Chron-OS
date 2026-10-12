package com.chronos.pocketos.core.capability

import com.chronos.pocketos.core.display.DisplayInfo
import com.chronos.pocketos.core.display.DisplayModeInfo
import com.chronos.pocketos.core.settings.ProfileChoice

/** A detected value, or an explicit statement that it is unknown / unsupported. Never a guess. */
sealed interface Capability<out T> {
    data class Known<T>(val value: T) : Capability<T>
    data class Unknown(val reason: String) : Capability<Nothing>
    data class Unsupported(val reason: String) : Capability<Nothing>

    fun valueOrNull(): T? = (this as? Known<T>)?.value
}

/** Thermal status levels mirrored from android.os.PowerManager.THERMAL_STATUS_*. */
object Thermal {
    const val NONE = 0; const val LIGHT = 1; const val MODERATE = 2; const val SEVERE = 3
    const val CRITICAL = 4; const val EMERGENCY = 5; const val SHUTDOWN = 6
    fun label(v: Int) = when (v) {
        NONE -> "Normal"; LIGHT -> "Warm"; MODERATE -> "Hot"; SEVERE -> "Very hot"
        CRITICAL, EMERGENCY, SHUTDOWN -> "Critical"; else -> "Unknown"
    }
}

data class DeviceCapabilities(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val apiLevel: Int,
    val abis: List<String>,
    val cpuCores: Capability<Int>,
    val totalRamBytes: Capability<Long>,
    val availableRamBytes: Capability<Long>,
    val memoryClassMb: Capability<Int>,
    val lowRamDevice: Boolean,
    val lowMemoryNow: Boolean,
    val freeStorageBytes: Capability<Long>,
    val powerSaveMode: Capability<Boolean>,
    val thermalStatus: Capability<Int>,
    val phoneScreen: DisplayInfo?,
    val externalDisplay: DisplayInfo?,
    val activitiesOnSecondaryDisplays: Boolean,
    val vulkan: Capability<String>,
    val openGlEs: Capability<String>,
) {
    val is64Bit: Boolean get() = abis.any { it.contains("64") }
    val hasArm64: Boolean get() = "arm64-v8a" in abis
}

enum class PerformanceProfile(val label: String) { BATTERY_SAVER("Battery Saver"), BALANCED("Balanced"), PERFORMANCE("Performance") }

/** Concrete effects of a profile; every field is used by the UI or runtime launcher. */
data class ProfileSettings(
    val windowShadows: Boolean,
    val animations: Boolean,
    val maxRecommendedWindows: Int,
    /** Upper bound for a Windows runtime's virtual desktop; actual value is chosen per app in Phase 7. */
    val runtimeMaxWidth: Int,
    val runtimeMaxHeight: Int,
    val preferredMaxRefreshHz: Float,
) {
    companion object {
        fun of(p: PerformanceProfile) = when (p) {
            PerformanceProfile.BATTERY_SAVER -> ProfileSettings(false, false, 4, 1024, 768, 60f)
            PerformanceProfile.BALANCED -> ProfileSettings(true, true, 8, 1280, 720, 60f)
            PerformanceProfile.PERFORMANCE -> ProfileSettings(true, true, 12, 1920, 1080, 120f)
        }
    }
}

data class ProfileDecision(
    val profile: PerformanceProfile,
    val automatic: Boolean,
    val reasons: List<String>,
    val warnings: List<String> = emptyList(),
) {
    val settings: ProfileSettings get() = ProfileSettings.of(profile)
}

object ProfileSelector {
    private const val GB = 1024L * 1024 * 1024

    /** Deterministic: the same capabilities and choice always give the same decision. */
    fun select(c: DeviceCapabilities, choice: ProfileChoice): ProfileDecision {
        val ram = c.totalRamBytes.valueOrNull()
        val cores = c.cpuCores.valueOrNull()
        val thermal = c.thermalStatus.valueOrNull()
        val warnings = buildList {
            if (thermal != null && thermal >= Thermal.SEVERE) add("The phone is very hot. PocketOS is using lighter settings until it cools down.")
            if (c.lowMemoryNow) add("Android reports low memory right now. Close windows or other apps if things slow down.")
        }
        if (choice != ProfileChoice.AUTO) {
            val p = when (choice) {
                ProfileChoice.BATTERY_SAVER -> PerformanceProfile.BATTERY_SAVER
                ProfileChoice.BALANCED -> PerformanceProfile.BALANCED
                else -> PerformanceProfile.PERFORMANCE
            }
            val extra = if (p == PerformanceProfile.PERFORMANCE && (c.lowRamDevice || (ram != null && ram < 4 * GB)))
                listOf("Performance may drain the battery quickly and feel slower on a phone with this little memory.") else emptyList()
            return ProfileDecision(p, automatic = false, reasons = listOf("You chose ${p.label}."), warnings = warnings + extra)
        }
        return when {
            thermal != null && thermal >= Thermal.SEVERE ->
                ProfileDecision(PerformanceProfile.BATTERY_SAVER, true, listOf("The phone is overheating (${Thermal.label(thermal)})."), warnings)
            c.powerSaveMode.valueOrNull() == true ->
                ProfileDecision(PerformanceProfile.BATTERY_SAVER, true, listOf("Android Battery Saver is on."), warnings)
            c.lowRamDevice ->
                ProfileDecision(PerformanceProfile.BATTERY_SAVER, true, listOf("Android marks this phone as a low-memory device."), warnings)
            ram != null && ram < 3 * GB ->
                ProfileDecision(PerformanceProfile.BATTERY_SAVER, true, listOf("The phone has less than 3 GB of memory."), warnings)
            ram == null ->
                ProfileDecision(PerformanceProfile.BALANCED, true, listOf("Memory size could not be read, so PocketOS uses the safe middle setting."), warnings)
            ram >= 8 * GB && (cores ?: 0) >= 8 && c.is64Bit && (thermal == null || thermal <= Thermal.LIGHT) ->
                ProfileDecision(PerformanceProfile.PERFORMANCE, true, listOf("The phone has ${ram / GB} GB of memory and $cores CPU cores."), warnings)
            else -> ProfileDecision(
                PerformanceProfile.BALANCED, true,
                listOf("The phone has ${ram / GB} GB of memory" + (cores?.let { " and $it CPU cores" } ?: "") + "."), warnings,
            )
        }
    }
}

object DisplayRecommendation {
    /**
     * Suggests a mode from the monitor's own supported list. Returns null if the display reports no modes
     * (then PocketOS keeps whatever the system chose). Never invents a mode.
     */
    fun recommendMode(display: DisplayInfo, profile: PerformanceProfile): DisplayModeInfo? {
        if (display.modes.isEmpty()) return null
        val s = ProfileSettings.of(profile)
        val maxPixels = when (profile) {
            PerformanceProfile.BATTERY_SAVER -> 1920 * 1080
            PerformanceProfile.BALANCED -> 1920 * 1080
            PerformanceProfile.PERFORMANCE -> 2560 * 1440
        }
        val fits = display.modes.filter { it.widthPx * it.heightPx <= maxPixels && it.refreshRate <= s.preferredMaxRefreshHz + 0.5f }
        val pool = fits.ifEmpty { listOf(display.modes.minBy { it.widthPx * it.heightPx }) }
        return pool.sortedWith(compareByDescending<DisplayModeInfo> { it.widthPx * it.heightPx }.thenByDescending { it.refreshRate }.thenBy { it.id }).first()
    }

    /** UI scale suggestion (multiplier for the desktop on a monitor). */
    fun recommendUiScale(display: DisplayInfo): Float = when {
        display.widthPx >= 3000 -> 1.15f
        display.widthPx < 1280 -> 0.9f
        else -> 1.0f
    }

    fun explainUiScale(display: DisplayInfo): String = when {
        display.widthPx >= 3000 -> "Large high-resolution monitor: text is made slightly bigger for comfortable reading."
        display.widthPx < 1280 -> "Small or low-resolution display: things are made slightly smaller so windows fit."
        else -> "Standard monitor: normal size."
    }
}

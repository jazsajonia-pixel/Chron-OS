package com.chronos.pocketos.capability

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import androidx.core.content.ContextCompat
import com.chronos.pocketos.core.capability.Capability
import com.chronos.pocketos.core.capability.DeviceCapabilities
import com.chronos.pocketos.core.capability.ProfileDecision
import com.chronos.pocketos.core.capability.ProfileSelector
import com.chronos.pocketos.core.display.DisplayInfo
import com.chronos.pocketos.core.display.ExternalDisplayPolicy
import com.chronos.pocketos.core.display.ExternalDisplayStatus
import com.chronos.pocketos.core.settings.ProfileChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads device capabilities through public Android APIs only. Everything stays on the device; nothing is uploaded.
 * Call [recheck] when memory, power, thermal or display conditions change.
 */
class CapabilityMonitor(
    private val context: Context,
    private val displays: () -> List<DisplayInfo>,
    private val profileChoice: () -> ProfileChoice,
    private val supportsSecondaryActivities: Boolean,
) {
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val _caps = MutableStateFlow(read())
    val capabilities: StateFlow<DeviceCapabilities> = _caps.asStateFlow()
    private val _decision = MutableStateFlow(ProfileSelector.select(_caps.value, profileChoice()))
    val decision: StateFlow<ProfileDecision> = _decision.asStateFlow()
    /** False when the device's thermal service refused a listener; status is then read only on re-checks. */
    var thermalListening = false
        private set
    var lastRecheckReason: String = "Startup"
        private set

    fun startListening() {
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = recheck("Battery Saver changed") },
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (Build.VERSION.SDK_INT >= 29) {
            // Throws RuntimeException("Listener failed to set") when the thermal service is unavailable.
            thermalListening = runCatching {
                pm.addThermalStatusListener(ContextCompat.getMainExecutor(context)) { recheck("Temperature changed") }
            }.isSuccess
        }
    }

    fun recheck(reason: String) {
        lastRecheckReason = reason
        _caps.value = read()
        _decision.value = ProfileSelector.select(_caps.value, profileChoice())
    }

    private fun read(): DeviceCapabilities {
        val mem = runCatching { ActivityManager.MemoryInfo().also(am::getMemoryInfo) }.getOrNull()
        val list = runCatching { displays() }.getOrDefault(emptyList())
        val external = (ExternalDisplayPolicy.evaluate(list) as? ExternalDisplayStatus.Available)?.display
        // Platform calls below may return null on some devices/builds; treat that as "unknown", never crash.
        val features: Array<android.content.pm.FeatureInfo>? = runCatching { context.packageManager.systemAvailableFeatures }.getOrNull()
        val vulkanFeature = features?.firstOrNull { it?.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
        val gles = runCatching { am.deviceConfigurationInfo?.glEsVersion }.getOrNull()
        return DeviceCapabilities(
            manufacturer = Build.MANUFACTURER ?: "Unknown",
            model = Build.MODEL ?: "Unknown",
            androidRelease = Build.VERSION.RELEASE ?: "Unknown",
            apiLevel = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            cpuCores = Runtime.getRuntime().availableProcessors().let { if (it > 0) Capability.Known(it) else Capability.Unknown("not reported") },
            totalRamBytes = mem?.totalMem?.takeIf { it > 0 }?.let { Capability.Known(it) } ?: Capability.Unknown("Android did not report memory size"),
            availableRamBytes = mem?.availMem?.takeIf { it > 0 }?.let { Capability.Known(it) } ?: Capability.Unknown("not reported"),
            memoryClassMb = am.memoryClass.takeIf { it > 0 }?.let { Capability.Known(it) } ?: Capability.Unknown("not reported"),
            lowRamDevice = am.isLowRamDevice,
            lowMemoryNow = mem?.lowMemory == true,
            freeStorageBytes = runCatching { Capability.Known(StatFs(context.filesDir.path).availableBytes) }
                .getOrElse { Capability.Unknown("storage could not be read") },
            powerSaveMode = runCatching { Capability.Known(pm.isPowerSaveMode) }.getOrElse { Capability.Unknown("not reported") },
            thermalStatus = if (Build.VERSION.SDK_INT >= 29) runCatching { Capability.Known(pm.currentThermalStatus) }.getOrElse { Capability.Unknown("not reported") }
            else Capability.Unsupported("Temperature status needs Android 10 or newer"),
            phoneScreen = list.firstOrNull { it.isDefault },
            externalDisplay = external,
            activitiesOnSecondaryDisplays = supportsSecondaryActivities,
            vulkan = vulkanFeature?.let { f ->
                val v = f.version
                Capability.Known("${v shr 22}.${(v shr 12) and 0x3ff}")
            } ?: Capability.Unknown("No Vulkan version reported"),
            openGlEs = gles?.let { Capability.Known(it) } ?: Capability.Unknown("not reported"),
        )
    }
}

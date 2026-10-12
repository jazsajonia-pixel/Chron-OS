package com.chronos.pocketos.ui.apps

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.chronos.pocketos.ui.components.KeyValue
import com.chronos.pocketos.ui.components.SectionTitle
import com.chronos.pocketos.ui.components.formatBytes

@Composable
fun SystemInfoApp() {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mem = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
    val stat = StatFs(Environment.getDataDirectory().path)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("sysinfo_root")) {
        SectionTitle("Device")
        KeyValue("Model", "${Build.MANUFACTURER} ${Build.MODEL}")
        KeyValue("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        KeyValue("CPU ABIs", Build.SUPPORTED_ABIS.joinToString())
        SectionTitle("Memory and storage")
        KeyValue("Total RAM", formatBytes(mem.totalMem))
        KeyValue("Available RAM", formatBytes(mem.availMem))
        KeyValue("Free app storage", formatBytes(stat.availableBytes))
        SectionTitle("Screen")
        KeyValue("Size", "${config.screenWidthDp} × ${config.screenHeightDp} dp")
        KeyValue("Density", "${config.densityDpi} dpi")
    }
}

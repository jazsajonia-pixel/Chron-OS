package com.chronos.pocketos.windows.core;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.chronos.pocketos.windows.xenvironment.RootFS;

import java.io.File;
import java.util.ArrayList;

/**
 * PocketOS: runtime constants and environment helpers that used to live in Winlator's settings screens and dialogs
 * (which PocketOS removed). The code is unchanged apart from the method names.
 */
public final class RuntimeConfig {
    private RuntimeConfig() {}

    public static final boolean DEBUG_MODE = false;
    public static final byte CONTAINER_PATTERN_COMPRESSION_LEVEL = 9;
    public static final String DEFAULT_WINE_DEBUG_CHANNELS = "warn,err,fixme";
    public static final byte AUDIO_DEFAULT_PERFORMANCE_MODE = 1;
    public static final float AUDIO_DEFAULT_VOLUME = 1.0f;
    public static final byte AUDIO_DEFAULT_LATENCY_MILLIS = 16;
    public static final String DEFAULT_VKD3D_FEATURE_LEVEL = "12.1";
    public static final String DEFAULT_VIRGL_GL_VERSION = "3.1";
    private static final GPUHelper.VkPresentMode DEFAULT_TURNIP_PRESENT_MODE = GPUHelper.VkPresentMode.MAILBOX;

    public static void setDXVKEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        if (config.get("version").contains("async")) envVars.put("DXVK_ASYNC", "1");
        envVars.put("DXVK_STATE_CACHE_PATH", RootFS.getDosUserCachePath());
        envVars.put("DXVK_LOG_LEVEL", "none");

        File rootDir = RootFS.find(context).getRootDir();
        File dxvkConfigFile = new File(rootDir, RootFS.USER_CONFIG_PATH+"/dxvk.conf");

        FileUtils.delete(dxvkConfigFile);
        String content = getDXVKConfigContent(config);
        if (FileUtils.writeString(dxvkConfigFile, content)) {
            envVars.put("DXVK_CONFIG_FILE", RootFS.getDosUserConfigPath()+"\\dxvk.conf");
        }
    }

    private static String getDXVKConfigContent(KeyValueSet config) {
        String content = "";

        String maxDeviceMemory = config.get("maxDeviceMemory");
        if (!maxDeviceMemory.isEmpty() && !maxDeviceMemory.equals("0")) {
            content += "dxgi.maxDeviceMemory = "+maxDeviceMemory+"\n";
            content += "dxgi.maxSharedMemory = "+maxDeviceMemory+"\n";
        }

        String framerate = config.get("framerate");
        if (!framerate.isEmpty() && !framerate.equals("0")) {
            content += "dxgi.maxFrameRate = "+framerate+"\n";
            content += "d3d9.maxFrameRate = "+framerate+"\n";
        }

        String customDevice = config.get("customDevice");
        if (customDevice.contains(":")) {
            String[] parts = customDevice.split(":");
            content += "dxgi.customDeviceId = "+parts[0]+"\n";
            content += "dxgi.customVendorId = "+parts[1]+"\n";

            content += "d3d9.customDeviceId = "+parts[0]+"\n";
            content += "d3d9.customVendorId = "+parts[1]+"\n";

            content += "dxgi.customDeviceDesc = \""+parts[2]+"\"\n";
            content += "d3d9.customDeviceDesc = \""+parts[2]+"\"\n";
        }

        content += "d3d11.constantBufferRangeCheck = \"True\"\n\n";

        content += "[GTA5.exe]\n";
        content += "dxgi.maxDeviceMemory = 512\n";
        content += "dxgi.maxSharedMemory = 512\n";
        return content;
    }

    public static void setWineD3DEnvVars(KeyValueSet config, EnvVars envVars) {
        envVars.put("WINE_D3D_CONFIG", String.join(",",
            "renderer="+config.get("renderer", "gl"),
            "csmt="+config.getHexString("csmt", 3),
            "VideoPciDeviceID="+config.getHexString("VideoPciDeviceID", 1728),
            "VideoPciVendorID="+config.getHexString("VideoPciVendorID", 4318),
            "OffscreenRenderingMode="+config.get("OffscreenRenderingMode", "fbo"),
            "strict_shader_math="+config.getHexString("strict_shader_math", 1),
            "VideoMemorySize="+config.get("VideoMemorySize", "2048")
        ));
    }

    public static void setVKD3DEnvVars(KeyValueSet config, EnvVars envVars) {
        envVars.put("DXVK_LOG_LEVEL", "none");
        envVars.put("VKD3D_DEBUG", "none");
        envVars.put("VKD3D_CONFIG", "skip_application_workarounds,no_upload_hvv,nodxr,no_staggered_submit");
        envVars.put("DXVK_STATE_CACHE_PATH", RootFS.getDosUserCachePath());
        envVars.put("VKD3D_FEATURE_LEVEL", config.get("featureLevel", DEFAULT_VKD3D_FEATURE_LEVEL).replace(".", "_"));
        envVars.put("VKD3D_SHADER_CACHE_PATH", RootFS.getDosUserCachePath());
    }

    private static boolean isForceSYSMEM(Context context) {
        short modelId = GPUHelper.getAdrenoModelId(context);
        return modelId >= 700 && modelId != 710 && modelId != 720 && modelId != 732;
    }

    private static boolean isForceGMEM(Context context) {
        short modelId = GPUHelper.getAdrenoModelId(context);
        return modelId == 710 || modelId == 720 || modelId == 732;
    }

    public static void setTurnipEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        String maxDeviceMemory = config.get("maxDeviceMemory", "0");
        if (!maxDeviceMemory.equals("0")) envVars.put("TU_OVERRIDE_HEAP_SIZE", maxDeviceMemory);
        if (config.getBoolean("directRendering", true)) envVars.put("MESA_VK_WSI_NATIVE_MEM_IMPORTED", "1");

        int presentModeIdx = config.getInt("presentMode", DEFAULT_TURNIP_PRESENT_MODE.ordinal());
        String presentMode = GPUHelper.VkPresentMode.values()[presentModeIdx].value();
        envVars.put("MESA_VK_WSI_PRESENT_MODE", presentMode);

        String tuDebug = envVars.get("TU_DEBUG");
        if (isForceSYSMEM(context) && !tuDebug.contains("sysmem")) {
            envVars.put("TU_DEBUG", (!tuDebug.isEmpty() ? tuDebug+"," : "")+"sysmem");
        }
        else if (isForceGMEM(context) && !tuDebug.contains("gmem")) {
            envVars.put("TU_DEBUG", (!tuDebug.isEmpty() ? tuDebug+"," : "")+"gmem");
        }
        envVars.put("TU_DEBUG", (!tuDebug.isEmpty() ? tuDebug+"," : "")+"deck_emu");
    }

    public static void setVirGLEnvVars(KeyValueSet config, EnvVars envVars) {
        ArrayList<String> disabledExtensions = new ArrayList<>();
        disabledExtensions.add("GL_KHR_debug");
        if (config.getBoolean("disableVertexArrayBGRA", true)) disabledExtensions.add("GL_EXT_vertex_array_bgra");

        String mesaExtensionOverride = "";
        for (String disabledExtension : disabledExtensions) {
            mesaExtensionOverride += (!mesaExtensionOverride.isEmpty() ? " " : "")+"-"+disabledExtension;
        }

        if (!mesaExtensionOverride.isEmpty()) envVars.put("MESA_EXTENSION_OVERRIDE", mesaExtensionOverride);
        envVars.put("MESA_GL_VERSION_OVERRIDE", config.get("glVersion", DEFAULT_VIRGL_GL_VERSION));
    }

    public static void resetPreferenceVersions(android.content.Context activity) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString("box64_version", DefaultVersion.BOX64);
        editor.remove("current_box64_version");
        editor.remove("current_graphics_driver");
        editor.apply();
    }
}

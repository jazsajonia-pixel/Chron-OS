package com.chronos.pocketos.windows.xenvironment;

import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;

import com.chronos.pocketos.windows.core.RuntimeConfig;
import com.chronos.pocketos.windows.R;
import com.chronos.pocketos.windows.core.RuntimeConfig;
import com.chronos.pocketos.windows.container.Container;
import com.chronos.pocketos.windows.container.ContainerManager;
import com.chronos.pocketos.windows.core.AppUtils;
import com.chronos.pocketos.windows.core.FileUtils;
import com.chronos.pocketos.windows.core.TarCompressorUtils;
import com.chronos.pocketos.windows.core.WineInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public abstract class RootFSInstaller {
    public static final byte LATEST_VERSION = 23; // TODO increment it on rootfs update
    public static final byte UPDATE_WINEPREFIX_VERSION = 16; // set it if main wine version change
    public static final String FILENAME = "rootfs.tzst";

    private static void resetContainerRFSVersions(Context context) {
        ContainerManager manager = new ContainerManager(context);
        for (Container container : manager.getContainers()) {
            String rfsVersion = container.getExtra("rfsVersion");
            String wineVersion = container.getWineVersion();
            if (!rfsVersion.isEmpty() && WineInfo.isMainWineVersion(wineVersion) && Short.parseShort(rfsVersion) <= UPDATE_WINEPREFIX_VERSION) {
                container.putExtra("wineprefixNeedsUpdate", "t");
            }

            container.putExtra("rfsVersion", null);
            container.saveData();
        }
    }


    /** Progress callback for {@link #installBlocking}. Values are 0..100. */
    public interface ProgressListener { void onProgress(int percent); }

    /** True when the system files are extracted and current. */
    public static boolean isInstalled(Context context) {
        RootFS rootFS = RootFS.find(context);
        return rootFS.isValid() && rootFS.getVersion() >= LATEST_VERSION;
    }

    /**
     * PocketOS: installs the system files without any UI. Must be called off the main thread.
     * Returns true on success. Safe to call again after a failure or interruption (the root is cleared first).
     */
    public static synchronized boolean installBlocking(Context context, ProgressListener listener) {
        RootFS rootFS = RootFS.find(context);
        final File rootDir = rootFS.getRootDir();
        RuntimeConfig.resetPreferenceVersions(context);
        clearRootDir(rootDir);
        final long contentLength = Math.max(1, TarCompressorUtils.getContentLength(TarCompressorUtils.Type.ZSTD, context, FILENAME, rootDir));
        AtomicLong totalSizeRef = new AtomicLong();
        final int[] last = {-1};
        boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, FILENAME, rootDir, (file, size) -> {
            if (size > 0 && listener != null) {
                int progress = (int)Math.min(100, ((float)totalSizeRef.addAndGet(size) / contentLength) * 100);
                if (progress != last[0]) { last[0] = progress; listener.onProgress(progress); }
            }
            return file;
        });
        if (success) {
            rootFS.createRFSVersionFile(LATEST_VERSION);
            resetContainerRFSVersions(context);
        }
        return success;
    }


    private static void clearOptDir(File optDir) {
        File[] files = optDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().equals("installed-wine")) continue;
                FileUtils.delete(file);
            }
        }
    }

    private static void clearRootDir(File rootDir) {
        if (rootDir.isDirectory()) {
            File[] files = rootDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        String name = file.getName();
                        if (name.equals("home") || name.equals("opt")) {
                            if (name.equals("opt")) clearOptDir(file);
                            continue;
                        }
                    }
                    FileUtils.delete(file);
                }
            }
        }
        else rootDir.mkdirs();
    }

}

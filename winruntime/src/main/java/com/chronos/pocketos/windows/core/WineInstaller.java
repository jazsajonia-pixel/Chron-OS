package com.chronos.pocketos.windows.core;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;

import com.chronos.pocketos.windows.core.RuntimeConfig;
import com.chronos.pocketos.windows.R;
import com.chronos.pocketos.windows.box64.Box64Preset;
import com.chronos.pocketos.windows.container.Container;
import com.chronos.pocketos.windows.xenvironment.RootFS;
import com.chronos.pocketos.windows.xenvironment.XEnvironment;
import com.chronos.pocketos.windows.xenvironment.components.GuestProgramLauncherComponent;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class WineInstaller {

    public static void extractWineFileForInstallAsync(Context context, Uri uri, Callback<File> callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            File destination = new File(RootFS.find(context).getInstalledWineDir(), "/preinstall/wine");
            FileUtils.delete(destination);
            destination.mkdirs();
            boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, context, uri, destination);
            if (!success) FileUtils.delete(destination);
            if (callback != null) callback.call(success ? destination : null);
        });
    }

    public static void findWineVersionAsync(Context context, File wineDir, Callback<WineInfo> callback) {
        if (wineDir == null || !wineDir.isDirectory()) {
            callback.call(null);
            return;
        }
        File[] files = wineDir.listFiles();
        if (files == null || files.length == 0) {
            callback.call(null);
            return;
        }

        if (files.length == 1) {
            if (!files[0].isDirectory()) {
                callback.call(null);
                return;
            }
            wineDir = files[0];
            files = wineDir.listFiles();
            if (files == null || files.length == 0) {
                callback.call(null);
                return;
            }
        }

        File binDir = null;
        for (File file : files) {
            if (file.isDirectory() && file.getName().equals("bin")) {
                binDir = file;
                break;
            }
        }

        if (binDir == null) {
            callback.call(null);
            return;
        }

        File wineBin = new File(binDir, "wine");
        File wineBin64 = new File(binDir, "wine64");

        if (!wineBin.isFile()) {
            callback.call(null);
            return;
        }

        final boolean is64Bit = (wineBin64.isFile() && ElfHelper.is64Bit(wineBin64)) || ElfHelper.is64Bit(wineBin);
        if (!is64Bit) {
            callback.call(null);
            return;
        }

        RootFS rootFS = RootFS.find(context);
        File rootDir = rootFS.getRootDir();
        String wineBinPath = wineBin64.isFile() ? wineBin64.getPath() : wineBin.getPath();
        final String winePath = wineDir.getPath();

        final AtomicReference<WineInfo> wineInfoRef = new AtomicReference<>();
        Callback<String> debugCallback = (line) -> {
            Pattern pattern = Pattern.compile("^wine\\-([0-9\\.]+)\\-?([0-9\\.]+)?", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                String version = matcher.group(1);
                String subversion = matcher.groupCount() >= 2 ? matcher.group(2) : null;
                wineInfoRef.set(new WineInfo(version, subversion, winePath));
            }
        };

        ProcessHelper.addDebugCallback(debugCallback);

        File linkFile = new File(rootDir, RootFS.HOME_PATH);
        linkFile.delete();
        FileUtils.symlink(wineDir, linkFile);

        XEnvironment environment = new XEnvironment(context, rootFS);
        GuestProgramLauncherComponent guestProgramLauncherComponent = new GuestProgramLauncherComponent();
        guestProgramLauncherComponent.setGuestExecutable(wineBinPath+" --version");
        guestProgramLauncherComponent.setTerminationCallback((status) -> {
            callback.call(wineInfoRef.get());
            ProcessHelper.removeDebugCallback(debugCallback);
        });
        environment.addComponent(guestProgramLauncherComponent);
        environment.startEnvironmentComponents();
    }

    public static ArrayList<WineInfo> getInstalledWineInfos(Context context) {
        ArrayList<WineInfo> wineInfos = new ArrayList<>();
        wineInfos.add(WineInfo.MAIN_WINE_INFO);
        File installedWineDir = RootFS.find(context).getInstalledWineDir();

        File[] files = installedWineDir.listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (name.startsWith("wine")) wineInfos.add(WineInfo.fromIdentifier(context, name));
            }
        }

        return wineInfos;
    }
}

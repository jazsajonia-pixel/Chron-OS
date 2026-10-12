package com.chronos.pocketos.windows;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.system.Os;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.chronos.pocketos.windows.alsaserver.ALSAClient;
import com.chronos.pocketos.windows.container.AudioDrivers;
import com.chronos.pocketos.windows.container.Container;
import com.chronos.pocketos.windows.container.ContainerManager;
import com.chronos.pocketos.windows.container.DXWrappers;
import com.chronos.pocketos.windows.container.GraphicsDrivers;
import com.chronos.pocketos.windows.container.Shortcut;
import com.chronos.pocketos.windows.core.AppUtils;
import com.chronos.pocketos.windows.core.DefaultVersion;
import com.chronos.pocketos.windows.core.EnvVars;
import com.chronos.pocketos.windows.core.FileUtils;
import com.chronos.pocketos.windows.core.GeneralComponents;
import com.chronos.pocketos.windows.core.KeyValueSet;
import com.chronos.pocketos.windows.core.RuntimeConfig;
import com.chronos.pocketos.windows.core.StringUtils;
import com.chronos.pocketos.windows.core.TarCompressorUtils;
import com.chronos.pocketos.windows.core.Win32AppWorkarounds;
import com.chronos.pocketos.windows.core.WineInfo;
import com.chronos.pocketos.windows.core.WineRegistryEditor;
import com.chronos.pocketos.windows.core.WineStartMenuCreator;
import com.chronos.pocketos.windows.core.WineThemeManager;
import com.chronos.pocketos.windows.core.WineUtils;
import com.chronos.pocketos.windows.renderer.DisplayHost;
import com.chronos.pocketos.windows.renderer.GLRenderer;
import com.chronos.pocketos.windows.services.ForegroundService;
import com.chronos.pocketos.windows.widget.DisplayView;
import com.chronos.pocketos.windows.winhandler.WinHandler;
import com.chronos.pocketos.windows.xconnector.UnixSocketConfig;
import com.chronos.pocketos.windows.xenvironment.RootFS;
import com.chronos.pocketos.windows.xenvironment.XEnvironment;
import com.chronos.pocketos.windows.xenvironment.components.ALSAServerComponent;
import com.chronos.pocketos.windows.xenvironment.components.GuestProgramLauncherComponent;
import com.chronos.pocketos.windows.xenvironment.components.NetworkInfoUpdateComponent;
import com.chronos.pocketos.windows.xenvironment.components.PulseAudioComponent;
import com.chronos.pocketos.windows.xenvironment.components.SysVSharedMemoryComponent;
import com.chronos.pocketos.windows.xenvironment.components.VirGLRendererComponent;
import com.chronos.pocketos.windows.xenvironment.components.VortekRendererComponent;
import com.chronos.pocketos.windows.xenvironment.components.XServerComponent;
import com.chronos.pocketos.windows.xserver.ScreenInfo;
import com.chronos.pocketos.windows.xserver.Window;
import com.chronos.pocketos.windows.xserver.WindowManager;
import com.chronos.pocketos.windows.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PocketOS: one running Windows program (or the Windows desktop), hosted inside the PocketOS desktop.
 *
 * This replaces the full-screen display activity of the upstream runtime. It prepares the Wine prefix, starts the
 * X server, audio, graphics bridges and the guest program, and renders into a {@link DisplayView} that PocketOS puts
 * inside one of its own windows. It has no screens, menus or dialogs of its own.
 *
 * The session object is a ContextWrapper so the runtime classes that used to receive the activity keep working.
 */
public class WindowsSession extends ContextWrapper {
    private static final String TAG = "PocketOS.Session";

    public interface Listener {
        /** Progress text while the session starts ("Preparing Windows files…"). Called on the main thread. */
        void onStatus(String message);
        /** The program has shown its first window. Called on the main thread. */
        void onReady();
        /** The program has ended (exitStatus is null when unknown or stopped). Called once, on the main thread. */
        void onEnded(Integer exitStatus);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final int containerId;
    private final String execUnixPath;
    private final String shortcutPath;
    private final Listener listener;

    private XEnvironment environment;
    private Container container;
    private XServer xServer;
    private DisplayHost displayHost;
    private RootFS rootFS;
    private Shortcut shortcut;
    private String[] graphicsDriver = {GraphicsDrivers.DEFAULT_VULKAN_DRIVER, GraphicsDrivers.DEFAULT_OPENGL_DRIVER};
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private String dxwrapper = Container.DEFAULT_DXWRAPPER;
    private ScreenInfo screenInfo = new ScreenInfo(Container.DEFAULT_SCREEN_SIZE);
    private KeyValueSet[] dxwrapperConfig;
    private KeyValueSet[] graphicsDriverConfig = {new KeyValueSet(), new KeyValueSet()};
    private KeyValueSet audioDriverConfig;
    private String wincomponents;
    private WineInfo wineInfo;
    private final EnvVars envVars = new EnvVars();
    private EnvVars overrideEnvVars;
    private SharedPreferences preferences;
    private final WinHandler winHandler;
    public int frameRatingWindowId = -1;
    private Win32AppWorkarounds win32AppWorkarounds;
    private final AtomicBoolean ended = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicInteger exitStatus = new AtomicInteger(Integer.MIN_VALUE);

    /**
     * @param execUnixPath  path of the .exe/.msi/.lnk/.bat inside the runtime's file system, or null
     * @param shortcutPath  path of a Wine .desktop shortcut, or null; with neither, the Windows desktop opens
     */
    public WindowsSession(Context context, int containerId, String execUnixPath, String shortcutPath, Listener listener) {
        super(context.getApplicationContext());
        this.containerId = containerId;
        this.execUnixPath = execUnixPath != null && !execUnixPath.isEmpty() ? execUnixPath : null;
        this.shortcutPath = shortcutPath != null && !shortcutPath.isEmpty() ? shortcutPath : null;
        this.listener = listener;
        this.winHandler = new WinHandler(this);
    }

    /** Starts the session. Call on the main thread; the heavy work runs in the background. */
    public void start() {
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        rootFS = RootFS.find(this);
        ContainerManager containerManager = new ContainerManager(this);
        container = containerManager.getContainerById(containerId);
        if (container == null) {
            postStatus("The Windows runtime is not set up yet.");
            end();
            return;
        }
        containerManager.activateContainer(container);

        win32AppWorkarounds = new Win32AppWorkarounds(this);
        wineInfo = WineInfo.fromIdentifier(this, container.getWineVersion());
        if (wineInfo != WineInfo.MAIN_WINE_INFO) rootFS.setWinePath(wineInfo.path);

        if (shortcutPath != null) shortcut = new Shortcut(container, new File(shortcutPath));

        String graphicsDriver = container.getGraphicsDriver();
        audioDriver = container.getAudioDriver();
        String dxwrapper = container.getDXWrapper();
        wincomponents = container.getWinComponents();
        String dxwrapperConfig = container.getDXWrapperConfig();
        String graphicsDriverConfig = container.getGraphicsDriverConfig();
        audioDriverConfig = new KeyValueSet(container.getAudioDriverConfig());
        screenInfo = resolveScreenInfo(container.getScreenSize());

        if (shortcut != null) {
            graphicsDriver = shortcut.getExtra("graphicsDriver", container.getGraphicsDriver());
            audioDriver = shortcut.getExtra("audioDriver", container.getAudioDriver());
            dxwrapper = shortcut.getExtra("dxwrapper", container.getDXWrapper());
            wincomponents = shortcut.getExtra("wincomponents", container.getWinComponents());
            dxwrapperConfig = shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig());
            graphicsDriverConfig = shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig());
            audioDriverConfig = new KeyValueSet(shortcut.getExtra("audioDriverConfig", container.getAudioDriverConfig()));
            screenInfo = resolveScreenInfo(shortcut.getExtra("screenSize", container.getScreenSize()));

            String dinputMapperType = shortcut.getExtra("dinputMapperType");
            if (!dinputMapperType.isEmpty()) winHandler.gamepadHandler.setDInputMapperType(Byte.parseByte(dinputMapperType));

            win32AppWorkarounds.applyStartupWorkarounds(!shortcut.wmClass.isEmpty() ? shortcut.wmClass : shortcut.path);
        }
        else if (execUnixPath != null) {
            win32AppWorkarounds.applyStartupWorkarounds(FileUtils.getName(execUnixPath));
        }

        this.graphicsDriver = GraphicsDrivers.parseIdentifiers(graphicsDriver);
        this.graphicsDriverConfig = GraphicsDrivers.parseConfigs(graphicsDriver, graphicsDriverConfig);
        this.dxwrapper = DXWrappers.parseIdentifier(dxwrapper);
        this.dxwrapperConfig = DXWrappers.parseConfigs(dxwrapper, dxwrapperConfig);

        xServer = new XServer(this, screenInfo);
        xServer.setWinHandler(winHandler);
        displayHost = new DisplayHost(this);
        GLRenderer renderer = new GLRenderer(displayHost, xServer);
        displayHost.setRenderer(renderer);
        renderer.setCursorVisible(false);
        renderer.setCursorColor(preferences.getInt("cursor_color", 0xffffff));
        renderer.setCursorScale(preferences.getFloat("cursor_scale", 1.0f));
        renderer.setForceWindowsFullscreen(shortcut != null && shortcut.getExtra("forceFullscreen", "0").equals("1"));
        xServer.setRenderer(renderer);

        final boolean hideWindowsDesktop = shortcut != null || execUnixPath != null;
        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onMapWindow(Window window) {
                if (!ready.get() && window.isRenderable() && !window.getClassName().isEmpty()) {
                    renderer.setCursorVisible(true);
                    if (ready.compareAndSet(false, true)) mainHandler.post(() -> { if (!ended.get()) listener.onReady(); });
                }
                if (hideWindowsDesktop && window.attributes.isViewable() && window.isDesktopWindow()) {
                    window.attributes.setViewable(false);
                    if (window.attributes.isEnabled()) window.disableAllDescendants();
                }
                if (win32AppWorkarounds != null) win32AppWorkarounds.applyWindowWorkarounds(window);
            }
        });
        displayHost.start();

        ForegroundService.startSession(this);

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                if (container.getExtra("wineprefixNeedsUpdate").equals("t")) {
                    postStatus("Updating Windows system files…");
                    CountDownLatch latch = new CountDownLatch(1);
                    int[] status = {-1};
                    WineUtils.updateWineprefix(this, (s) -> { status[0] = s; latch.countDown(); });
                    latch.await();
                    if (status[0] != 0) {
                        postStatus("Updating Windows system files failed.");
                        end();
                        return;
                    }
                    container.putExtra("wineprefixNeedsUpdate", null);
                    container.putExtra("wincomponents", null);
                    container.saveData();
                }
                if (ended.get()) return;
                postStatus("Preparing Windows files…");
                setupWineSystemFiles();
                extractGraphicsDriverFiles();
                changeWineAudioDriver();
                if (ended.get()) return;
                postStatus("Starting…");
                setupXEnvironment();
            }
            catch (Throwable t) {
                Log.e(TAG, "session start failed", t);
                postStatus("Could not start: " + t.getMessage());
                end();
            }
        });
    }

    /** Creates a view that shows this session. PocketOS puts it in a window; only one view draws at a time. */
    public DisplayView createView(Context context) {
        return new DisplayView(context, xServer, displayHost);
    }

    public boolean isReady() {
        return ready.get();
    }

    public boolean isEnded() {
        return ended.get();
    }

    /** Stops the program and everything it started. */
    public void stop() {
        end();
    }

    private synchronized void end() {
        if (!ended.compareAndSet(false, true)) return;
        try { winHandler.stop(); } catch (Throwable ignored) {}
        try { if (environment != null) environment.stopEnvironmentComponents(); } catch (Throwable ignored) {}
        killRemainingGuestProcesses();
        if (displayHost != null) displayHost.stop();
        ForegroundService.stopSession(this);
        int status = exitStatus.get();
        mainHandler.post(() -> listener.onEnded(status != Integer.MIN_VALUE ? status : null));
    }

    /**
     * The guest program, wineserver and helpers run as child processes of PocketOS. The upstream runtime ended its
     * whole process to clean them up; PocketOS keeps running, so it ends every other process of this app instead.
     */
    private static void killRemainingGuestProcesses() {
        int myPid = Os.getpid();
        int myUid = Os.getuid();
        String[] pids = new File("/proc").list((dir, name) -> name.matches("[0-9]+"));
        if (pids == null) return;
        for (String p : pids) {
            int pid = Integer.parseInt(p);
            if (pid == myPid) continue;
            try {
                if (Os.stat("/proc/" + p).st_uid == myUid) Process.killProcess(pid);
            }
            catch (Exception ignored) {}
        }
    }

    private void postStatus(String message) {
        mainHandler.post(() -> { if (!ended.get()) listener.onStatus(message); });
    }

    private void setupXEnvironment() {
        String rootPath = rootFS.getRootDir().getPath();
        envVars.put("MESA_DEBUG", "silent");
        envVars.put("MESA_NO_ERROR", "1");
        envVars.put("WINEPREFIX", rootPath+RootFS.WINEPREFIX);
        envVars.put("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER", "1");

        boolean enableWineDebug = preferences.getBoolean("enable_wine_debug", false);
        String wineDebugChannels = preferences.getString("wine_debug_channels", RuntimeConfig.DEFAULT_WINE_DEBUG_CHANNELS);
        envVars.put("WINEDEBUG", enableWineDebug && !wineDebugChannels.isEmpty() ? "+"+wineDebugChannels.replace(",", ",+") : "-all");

        FileUtils.clear(rootFS.getTmpDir());

        GuestProgramLauncherComponent guestProgramLauncherComponent = new GuestProgramLauncherComponent();

        String desktopName = shortcut != null || execUnixPath != null ? "nogui" : "shell";
        String guestExecutable = "wine explorer /desktop="+desktopName+","+xServer.screenInfo+" "+getWineStartCommand();
        guestProgramLauncherComponent.setGuestExecutable(guestExecutable);

        envVars.putAll(container.getEnvVars());
        if (shortcut != null) envVars.putAll(shortcut.getExtra("envVars"));
        if (!envVars.has("WINEESYNC")) envVars.put("WINEESYNC", "1");

        guestProgramLauncherComponent.setBox64Preset(shortcut != null ? shortcut.getExtra("box64Preset", container.getBox64Preset()) : container.getBox64Preset());

        environment = new XEnvironment(this, rootFS);
        environment.addComponent(new SysVSharedMemoryComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)));
        environment.addComponent(new XServerComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.XSERVER_PATH)));
        environment.addComponent(new NetworkInfoUpdateComponent());

        if (audioDriver.equals(AudioDrivers.ALSA)) {
            envVars.put("ANDROID_ALSA_SERVER", rootPath+UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", ALSAClient.USE_SHARED_MEMORY ? "true" : "false");

            ALSAClient.Options options = ALSAClient.Options.fromKeyValueSet(audioDriverConfig);
            environment.addComponent(new ALSAServerComponent(UnixSocketConfig.create(rootPath, UnixSocketConfig.ALSA_SERVER_PATH), options));
        }
        else if (audioDriver.equals(AudioDrivers.PULSEAUDIO)) {
            PulseAudioComponent pulseAudioComponent = new PulseAudioComponent(UnixSocketConfig.create(rootPath, UnixSocketConfig.PULSE_SERVER_PATH));
            envVars.put("PULSE_SERVER", rootPath+UnixSocketConfig.PULSE_SERVER_PATH);

            if (!audioDriverConfig.isEmpty()) {
                envVars.put("PULSE_LATENCY_MSEC", audioDriverConfig.getInt("latencyMillis", RuntimeConfig.AUDIO_DEFAULT_LATENCY_MILLIS));
                pulseAudioComponent.setVolume(audioDriverConfig.getFloat("volume", RuntimeConfig.AUDIO_DEFAULT_VOLUME));
                pulseAudioComponent.setPerformanceMode(audioDriverConfig.getInt("performanceMode", RuntimeConfig.AUDIO_DEFAULT_PERFORMANCE_MODE));
            }
            else envVars.put("PULSE_LATENCY_MSEC", RuntimeConfig.AUDIO_DEFAULT_LATENCY_MILLIS);
            environment.addComponent(pulseAudioComponent);
        }

        if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK)) {
            VortekRendererComponent.Options options = VortekRendererComponent.Options.fromKeyValueSet(this, graphicsDriverConfig[0]);
            VortekRendererComponent vortekRendererComponent = new VortekRendererComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.VORTEK_SERVER_PATH), options);
            environment.addComponent(vortekRendererComponent);
        }
        if (graphicsDriver[1].equals(GraphicsDrivers.VIRGL)) {
            environment.addComponent(new VirGLRendererComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.VIRGL_SERVER_PATH)));
        }

        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setTerminationCallback((status) -> { exitStatus.set(status); end(); });
        environment.addComponent(guestProgramLauncherComponent);

        if (overrideEnvVars != null) {
            envVars.putAll(overrideEnvVars);
            overrideEnvVars = null;
        }
        environment.startEnvironmentComponents();

        winHandler.start();
        envVars.clear();
        dxwrapperConfig = null;
        graphicsDriverConfig = null;
        audioDriverConfig = null;
    }

    public SharedPreferences getPreferences() {
        return preferences;
    }

    public XServer getXServer() {
        return xServer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public DisplayHost getDisplayHost() {
        return displayHost;
    }

    public Container getContainer() {
        return container;
    }

    public RootFS getRootFs() {
        return rootFS;
    }

    public EnvVars getOverrideEnvVars() {
        if (overrideEnvVars == null) overrideEnvVars = new EnvVars();
        return overrideEnvVars;
    }

    public String getDXWrapper() {
        return dxwrapper;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public ScreenInfo getScreenInfo() {
        return screenInfo;
    }

    public void setScreenInfo(ScreenInfo screenInfo) {
        this.screenInfo = screenInfo;
    }

    private ScreenInfo resolveScreenInfo(String value) {
        if (!value.equals("native")) return new ScreenInfo(value);
        int width = AppUtils.getScreenWidth();
        int height = AppUtils.getScreenHeight();
        return new ScreenInfo(width - (width % 2), height - (height % 2));
    }

    public String getWinComponents() {
        return wincomponents;
    }

    public void setWinComponents(String wincomponents) {
        this.wincomponents = wincomponents;
    }

    public void changeFrameRatingVisibility(Window window, boolean visible) {}

    private void setupWineSystemFiles() {
        String appVersion = String.valueOf(AppUtils.getVersionCode(this));
        String rfsVersion = String.valueOf(rootFS.getVersion());
        boolean containerDataChanged = false;

        boolean wineprefixWasUpdated = WineUtils.isWineprefixWasUpdated(container);
        if (!container.getExtra("appVersion").equals(appVersion) || !container.getExtra("rfsVersion").equals(rfsVersion) || wineprefixWasUpdated) {
            applyGeneralPatches(container);
            container.putExtra("appVersion", appVersion);
            container.putExtra("rfsVersion", rfsVersion);
            containerDataChanged = true;
        }

        if (verifyUserRegistry()) containerDataChanged = true;
        if (extractDXWrapperFiles()) containerDataChanged = true;

        if (!wincomponents.equals(container.getExtra("wincomponents"))) {
            extractWinComponentFiles();
            container.putExtra("wincomponents", wincomponents);
            containerDataChanged = true;
        }

        String desktopTheme = container.getDesktopTheme();
        if (!(desktopTheme+","+xServer.screenInfo).equals(container.getExtra("desktopTheme"))) {
            WineThemeManager.apply(this, new WineThemeManager.ThemeInfo(desktopTheme), xServer.screenInfo);
            container.putExtra("desktopTheme", desktopTheme+","+xServer.screenInfo);
            containerDataChanged = true;
        }

        WineStartMenuCreator.create(this, container);
        WineUtils.createDosdevicesSymlinks(container, true);

        String startupSelection = String.valueOf(container.getStartupSelection());
        if (!startupSelection.equals(container.getExtra("startupSelection")) || wineprefixWasUpdated) {
            WineUtils.changeServicesStatus(container, container.getStartupSelection());
            container.putExtra("startupSelection", startupSelection);
            containerDataChanged = true;
        }

        boolean openAndroidBrowserFromWine = preferences.getBoolean("open_android_browser_from_wine", true);
        String openAndroidBrowserFromWineStr = openAndroidBrowserFromWine ? "t" : "f";
        if (!openAndroidBrowserFromWineStr.equals(container.getExtra("openAndroidBrowserFromWine")) || wineprefixWasUpdated) {
            WineUtils.changeBrowsersRegistryKey(container, openAndroidBrowserFromWine);
            container.putExtra("openAndroidBrowserFromWine", openAndroidBrowserFromWineStr);
            containerDataChanged = true;
        }

        if (containerDataChanged) container.saveData();
    }

    private void extractGraphicsDriverFiles() {
        envVars.put("vblank_mode", "0");

        String cacheId = "";
        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) {
            cacheId += graphicsDriver[0]+"-"+graphicsDriverConfig[0].get("version", DefaultVersion.TURNIP);
        }
        else cacheId += graphicsDriver[0]+"-"+DefaultVersion.valueOf(graphicsDriver[0]);
        cacheId += "-"+graphicsDriver[1]+"-"+DefaultVersion.valueOf(graphicsDriver[1]);

        String currentGraphicsDriver = preferences.getString("current_graphics_driver", "");
        boolean changed = !cacheId.equals(currentGraphicsDriver);
        File rootDir = rootFS.getRootDir();
        File libDir = rootFS.getLibDir();

        if (changed) {
            FileUtils.delete(new File(libDir, "libvulkan_freedreno.so"));
            FileUtils.delete(new File(libDir, "libvulkan_vortek.so"));
            FileUtils.delete(new File(libDir, "libGL.so.1.7.0"));

            File vulkanICDDir = new File(rootDir, "/usr/share/vulkan/icd.d");
            FileUtils.delete(vulkanICDDir);
            vulkanICDDir.mkdirs();

            preferences.edit().putString("current_graphics_driver", cacheId).apply();
        }

        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) {
            RuntimeConfig.setTurnipEnvVars(this, graphicsDriverConfig[0], envVars);

            if (changed) {
                String version = graphicsDriverConfig[0].get("version", DefaultVersion.TURNIP);
                GeneralComponents.extractFile(GeneralComponents.Type.TURNIP, this, version, DefaultVersion.TURNIP);
            }
        }
        else if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK) && (changed || RuntimeConfig.DEBUG_MODE)) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/vortek-" + DefaultVersion.VORTEK + ".tzst", rootDir);
        }

        switch (graphicsDriver[1]) {
            case GraphicsDrivers.ZINK:
                envVars.put("GALLIUM_DRIVER", "zink");
                envVars.put("ZINK_CONTEXT_THREADED", "1");
                if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK)) envVars.put("MESA_GL_VERSION_OVERRIDE", "3.3");

                if (changed) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/zink-"+DefaultVersion.ZINK+".tzst", rootDir);
                break;
            case GraphicsDrivers.VIRGL:
                envVars.put("GALLIUM_DRIVER", "virpipe");
                envVars.put("VIRGL_NO_READBACK", "true");
                envVars.put("VIRGL_SERVER_PATH", rootDir+UnixSocketConfig.VIRGL_SERVER_PATH);
                RuntimeConfig.setVirGLEnvVars(graphicsDriverConfig[1], envVars);

                if (changed) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/virgl-"+DefaultVersion.VIRGL+".tzst", rootDir);
                break;
            case GraphicsDrivers.GLADIO:
                envVars.put("GLADIO_NO_ERROR", "1");

                if (changed || RuntimeConfig.DEBUG_MODE) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/gladio-"+DefaultVersion.GLADIO+".tzst", rootDir);
                break;
        }
    }

    private boolean extractDXWrapperFiles() {
        String cacheId = "";
        if (dxwrapper.equals(DXWrappers.DXVK)) {
            RuntimeConfig.setDXVKEnvVars(this, dxwrapperConfig[0], envVars);
            cacheId += dxwrapper+"-"+dxwrapperConfig[0].get("version", DefaultVersion.DXVK(graphicsDriver[0]));
        }
        else if (dxwrapper.equals(DXWrappers.WINED3D)) {
            RuntimeConfig.setWineD3DEnvVars(dxwrapperConfig[0], envVars);
            cacheId += dxwrapper+"-"+dxwrapperConfig[0].get("version", DefaultVersion.WINED3D);
        }

        String ddrawWrapper = dxwrapperConfig[0].get("ddrawWrapper", DXWrappers.WINED3D);
        cacheId += "-"+DXWrappers.VKD3D+"-"+dxwrapperConfig[1].get("version", DefaultVersion.VKD3D)+"-"+ddrawWrapper;
        boolean changed = !cacheId.equals(container.getExtra("dxwrapper"));
        RuntimeConfig.setVKD3DEnvVars(dxwrapperConfig[1], envVars);

        if (ddrawWrapper.equals(DXWrappers.CNC_DDRAW)) envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\ProgramData\\cnc-ddraw\\ddraw.ini");

        if (!changed) return false;
        container.putExtra("dxwrapper", cacheId);

        File rootDir = rootFS.getRootDir();
        File windowsDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows");

        if (dxwrapper.equals(DXWrappers.WINED3D)) {
            String version = dxwrapperConfig[0].get("version", DefaultVersion.WINED3D);
            if (version.equals(WineInfo.MAIN_WINE_VERSION)) {
                final String[] dlls = {"d3d8.dll", "d3d9.dll", "d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll", "dxgi.dll", "ddraw.dll", "wined3d.dll"};
                restoreBuiltinDllFiles(dlls);
            }
            else GeneralComponents.extractFile(GeneralComponents.Type.WINED3D, this, version, DefaultVersion.WINED3D);
        }
        else if (dxwrapper.equals(DXWrappers.DXVK)) {
            final boolean[] hasD3D8DllFile = {false};
            final boolean[] hasD3D10DllFile = {false};

            GeneralComponents.extractFile(GeneralComponents.Type.DXVK, this, dxwrapperConfig[0].get("version"), DefaultVersion.DXVK(graphicsDriver[0]), (destination, size) -> {
                String name = destination.getName();
                if (name.equals("d3d10.dll")) {
                    hasD3D10DllFile[0] = true;
                }
                else if (name.equals("d3d8.dll")) {
                    hasD3D8DllFile[0] = true;
                }
                return destination;
            });

            if (!hasD3D8DllFile[0]) {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d8vk-"+DefaultVersion.D8VK+".tzst", windowsDir);
            }
            if (!hasD3D10DllFile[0]) restoreBuiltinDllFiles("d3d10.dll", "d3d10_1.dll");
        }

        GeneralComponents.extractFile(GeneralComponents.Type.VKD3D, this, dxwrapperConfig[1].get("version"), DefaultVersion.VKD3D);

        File containerSysWoW64Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/syswow64");
        FileUtils.delete(new File(containerSysWoW64Dir, "ddraw_.dll"));

        switch (ddrawWrapper) {
            case DXWrappers.CNC_DDRAW:
                final String assetDir = "dxwrapper/cnc-ddraw-"+DefaultVersion.CNC_DDRAW;
                File configFile = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/ProgramData/cnc-ddraw/ddraw.ini");
                if (!configFile.isFile()) FileUtils.copy(this, assetDir+"/ddraw.ini", configFile);
                File shadersDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/ProgramData/cnc-ddraw/Shaders");
                FileUtils.delete(shadersDir);
                FileUtils.copy(this, assetDir+"/Shaders", shadersDir);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, assetDir+"/ddraw.tzst", windowsDir);
                break;
            case DXWrappers.D7VK:
                restoreBuiltinDllFiles("ddraw.dll");
                (new File(containerSysWoW64Dir, "ddraw.dll")).renameTo(new File(containerSysWoW64Dir, "ddraw_.dll"));
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d7vk-"+DefaultVersion.D7VK+".tzst", windowsDir);
                break;
            default:
                restoreBuiltinDllFiles("ddraw.dll");
                break;
        }
        return true;
    }

    private void extractWinComponentFiles() {
        File rootDir = rootFS.getRootDir();
        File windowsDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows");
        File systemRegFile = new File(rootDir, RootFS.WINEPREFIX+"/system.reg");

        try {
            JSONObject wincomponentsJSONObject = new JSONObject(FileUtils.readString(this, "wincomponents/wincomponents.json"));
            Iterator<String[]> oldWinComponentsIter = new KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator();
            ArrayList<String> builtinDlls = new ArrayList<>();

            for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1])) continue;
                String identifier = wincomponent[0];
                boolean useNative = wincomponent[1].equals("1");

                if (useNative) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "wincomponents/"+identifier+".tzst", windowsDir);
                }
                else {
                    JSONObject wincomponentJSONObject = wincomponentsJSONObject.getJSONObject(identifier);
                    if (wincomponentJSONObject.getBoolean("restoreBuiltinDlls")) {
                        JSONArray dlnames = wincomponentJSONObject.getJSONArray("dlnames");
                        for (int i = 0; i < dlnames.length(); i++) {
                            String dlname = dlnames.getString(i);
                            builtinDlls.add(!dlname.endsWith(".exe") ? dlname+".dll" : dlname);
                        }
                    }
                    else {
                        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "wincomponents/"+identifier+".tzst", windowsDir, (destination, size) -> {
                            String name = destination.getName();
                            if (name.endsWith(".dll") || name.endsWith(".manifest") || name.endsWith("_deadbeef")) FileUtils.delete(destination);
                            return null;
                        });
                    }
                }

                WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative);
            }

            if (!builtinDlls.isEmpty()) restoreBuiltinDllFiles(builtinDlls.toArray(new String[0]));
            WineUtils.overrideWinComponentDlls(this, container, wincomponents);
        }
        catch (JSONException e) {}
    }

    private void restoreBuiltinDllFiles(final String... dlls) {
        File rootDir = rootFS.getRootDir();
        File wineDir = new File(rootDir, rootFS.getWinePath());
        File wineSystem32Dir = new File(wineDir, "/lib/wine/x86_64-windows");
        File wineSysWoW64Dir = new File(wineDir, "/lib/wine/i386-windows");
        File containerSystem32Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/system32");
        File containerSysWoW64Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/syswow64");;

        for (String dll : dlls) {
            FileUtils.copy(new File(wineSysWoW64Dir, dll), new File(containerSysWoW64Dir, dll));
            FileUtils.copy(new File(wineSystem32Dir, dll), new File(containerSystem32Dir, dll));
        }
    }

    private String getWineStartCommand() {
        String cmdArgs = "";
        String execPath = null;
        String execArgs = "";

        if (shortcut != null) {
            execArgs = shortcut.getExtra("execArgs");
            execArgs = !execArgs.isEmpty() ? " "+execArgs : "";

            if (shortcut.isLinkPath()) {
                cmdArgs = "\""+shortcut.path+"\""+execArgs;
            }
            else execPath = shortcut.path;
        }
        else {
            if (execUnixPath != null) {
                execPath = WineUtils.unixToDOSPath(execUnixPath, container);

                if (execPath.endsWith(".lnk")) {
                    cmdArgs = "\""+execPath+"\"";
                    execPath = null;
                }
            }
        }

        if (execPath != null) {
            String execDir = FileUtils.getDirname(execPath);
            String filename = FileUtils.getName(execPath);
            int dotIndex, spaceIndex;
            if ((dotIndex = filename.lastIndexOf(".")) != -1 && (spaceIndex = filename.indexOf(" ", dotIndex)) != -1) {
                execArgs = filename.substring(spaceIndex+1)+execArgs;
                filename = filename.substring(0, spaceIndex);
            }
            cmdArgs = "/dir "+StringUtils.escapeDOSPath(execDir)+" \""+filename+"\""+execArgs;
        }

        if (cmdArgs.isEmpty()) cmdArgs = "/dir C:\\windows \"wfm.exe\"";

        if (overrideEnvVars != null && overrideEnvVars.has("EXTRA_EXEC_ARGS")) {
            cmdArgs += " "+overrideEnvVars.get("EXTRA_EXEC_ARGS");
            overrideEnvVars.remove("EXTRA_EXEC_ARGS");
        }
        return "C:\\windows\\winhandler.exe "+cmdArgs;
    }

    private void changeWineAudioDriver() {
        if (!audioDriver.equals(container.getExtra("audioDriver"))) {
            File rootDir = rootFS.getRootDir();
            File userRegFile = new File(rootDir, RootFS.WINEPREFIX+"/user.reg");
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                if (audioDriver.equals(AudioDrivers.ALSA)) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa");
                }
                else if (audioDriver.equals(AudioDrivers.PULSEAUDIO)) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse");
                }
            }
            container.putExtra("audioDriver", audioDriver);
            container.saveData();
        }
    }

    private void applyGeneralPatches(Container container) {
        File rootDir = rootFS.getRootDir();
        FileUtils.delete(new File(rootDir, "/opt/apps"));
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "rootfs_patches.tzst", rootDir);
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "pulseaudio.tzst", new File(getFilesDir(), "pulseaudio"));
        WineUtils.applySystemTweaks(this, wineInfo);
        container.putExtra("dxwrapper", null);
        container.putExtra("desktopTheme", null);
        RuntimeConfig.resetPreferenceVersions(this);
    }

    public boolean verifyUserRegistry() {
        File userRegFile = new File(rootFS.getRootDir(), RootFS.WINEPREFIX+"/user.reg");
        String lastModified = String.valueOf(userRegFile.lastModified());

        if (!lastModified.equals(container.getExtra("userRegLastModified"))) {
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                registryEditor.removeKey("Software\\Wow6432Node\\Wine", true);
            }

            container.putExtra("userRegLastModified", lastModified);
            return true;
        }
        else return false;
    }}

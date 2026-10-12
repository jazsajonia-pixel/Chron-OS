package com.chronos.pocketos.windows.renderer;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.util.Log;

import java.util.ArrayDeque;

/**
 * PocketOS: owns the OpenGL ES context that draws the Windows display and runs the renderer on its own thread.
 *
 * Unlike a GLSurfaceView, the context belongs to the session rather than to a view: when PocketOS minimizes the
 * window, or moves the desktop to an external monitor, only the output surface changes. Textures (including the
 * hardware buffers that Vulkan/OpenGL programs render into) stay valid, and the X server keeps running unchanged.
 *
 * The API mirrors the parts of GLSurfaceView that the renderer uses: {@link #requestRender()},
 * {@link #queueEvent(Runnable)} and {@link #getContext()}.
 */
public class DisplayHost {
    private static final String TAG = "PocketOS.Display";

    private final Context context;
    private GLRenderer renderer;
    private final Object lock = new Object();
    private final ArrayDeque<Runnable> events = new ArrayDeque<>();
    private Thread thread;
    private boolean running;
    private boolean renderRequested = true;

    // Pending output surface change, applied on the render thread.
    private boolean surfaceChangePending;
    private SurfaceTexture pendingSurface;
    private int pendingWidth, pendingHeight;
    private Runnable pendingSurfaceReleased;

    // Render-thread state.
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig eglConfig;
    private EGLSurface pbufferSurface = EGL14.EGL_NO_SURFACE;
    private EGLSurface windowSurface = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture currentSurface;
    private int surfaceWidth, surfaceHeight;
    private boolean sizeChanged;

    public DisplayHost(Context context) {
        this.context = context;
    }

    public void setRenderer(GLRenderer renderer) {
        this.renderer = renderer;
    }

    public Context getContext() {
        return context;
    }

    public void start() {
        synchronized (lock) {
            if (thread != null) return;
            running = true;
            thread = new Thread(this::loop, "PocketOS-WindowsDisplay");
            thread.start();
        }
    }

    /** Stops drawing and releases the context. The X server itself is stopped by the session. */
    public void stop() {
        Thread t;
        synchronized (lock) {
            running = false;
            t = thread;
            thread = null;
            lock.notifyAll();
        }
        if (t != null && t != Thread.currentThread()) {
            try { t.join(2000); } catch (InterruptedException ignored) {}
        }
    }

    public void requestRender() {
        synchronized (lock) {
            renderRequested = true;
            lock.notifyAll();
        }
    }

    public void queueEvent(Runnable r) {
        if (r == null) return;
        synchronized (lock) {
            events.add(r);
            lock.notifyAll();
        }
    }

    /**
     * Sets the surface to draw into (from a TextureView), or null to stop drawing. [onReleased] runs on the render
     * thread once the previous surface is no longer used, so the caller can release its SurfaceTexture safely.
     */
    public void setOutput(SurfaceTexture surface, int width, int height, Runnable onReleased) {
        synchronized (lock) {
            if (pendingSurfaceReleased != null) {
                // A previous change was not applied yet; its surface is unused, so release it now.
                Runnable r = pendingSurfaceReleased;
                pendingSurfaceReleased = null;
                r.run();
            }
            surfaceChangePending = true;
            pendingSurface = surface;
            pendingWidth = width;
            pendingHeight = height;
            pendingSurfaceReleased = onReleased;
            renderRequested = true;
            lock.notifyAll();
        }
    }

    public void resize(int width, int height) {
        synchronized (lock) {
            if (!surfaceChangePending) {
                pendingSurface = currentSurfaceRef();
                surfaceChangePending = pendingSurface != null;
            }
            pendingWidth = width;
            pendingHeight = height;
            renderRequested = true;
            lock.notifyAll();
        }
    }

    private volatile SurfaceTexture currentSurfaceVolatile;

    private SurfaceTexture currentSurfaceRef() {
        return currentSurfaceVolatile;
    }

    private void loop() {
        try {
            if (!initEGL()) return;
            if (renderer != null) renderer.onSurfaceCreated(null, null);
            while (true) {
                Runnable event = null;
                boolean draw = false;
                boolean changeSurface = false;
                SurfaceTexture newSurface = null;
                int newWidth = 0, newHeight = 0;
                Runnable released = null;
                synchronized (lock) {
                    while (running && events.isEmpty() && !surfaceChangePending && !(renderRequested && windowSurface != EGL14.EGL_NO_SURFACE)) {
                        try { lock.wait(); } catch (InterruptedException e) { running = false; }
                    }
                    if (!running) break;
                    if (surfaceChangePending) {
                        changeSurface = true;
                        newSurface = pendingSurface;
                        newWidth = pendingWidth;
                        newHeight = pendingHeight;
                        released = pendingSurfaceReleased;
                        surfaceChangePending = false;
                        pendingSurface = null;
                        pendingSurfaceReleased = null;
                    }
                    else if (!events.isEmpty()) {
                        event = events.poll();
                    }
                    else {
                        draw = true;
                        renderRequested = false;
                    }
                }

                if (changeSurface) {
                    applySurface(newSurface, newWidth, newHeight);
                    if (released != null) released.run();
                    continue;
                }
                if (event != null) {
                    try { event.run(); } catch (Throwable t) { Log.w(TAG, "render event failed", t); }
                    continue;
                }
                if (draw) drawFrame();
            }
        }
        catch (Throwable t) {
            Log.e(TAG, "display thread stopped", t);
        }
        finally {
            releaseEGL();
            Runnable released;
            synchronized (lock) {
                released = pendingSurfaceReleased;
                pendingSurfaceReleased = null;
            }
            if (released != null) released.run();
        }
    }

    private boolean initEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize failed");
            return false;
        }
        int[] attribs = {
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            Log.e(TAG, "no suitable EGL config");
            return false;
        }
        eglConfig = configs[0];
        int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext failed");
            return false;
        }
        int[] pbAttribs = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
        pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbAttribs, 0);
        return EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext);
    }

    private void applySurface(SurfaceTexture surface, int width, int height) {
        if (surface != currentSurface) {
            EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext);
            if (windowSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, windowSurface);
                windowSurface = EGL14.EGL_NO_SURFACE;
            }
            currentSurface = null;
            currentSurfaceVolatile = null;
            if (surface != null) {
                int[] attribs = {EGL14.EGL_NONE};
                windowSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0);
                if (windowSurface == EGL14.EGL_NO_SURFACE) {
                    Log.w(TAG, "eglCreateWindowSurface failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
                    return;
                }
                currentSurface = surface;
                currentSurfaceVolatile = surface;
                EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext);
                EGL14.eglSwapInterval(eglDisplay, 0);
            }
        }
        if (surface != null) {
            surfaceWidth = width;
            surfaceHeight = height;
            sizeChanged = true;
        }
    }

    private void drawFrame() {
        if (windowSurface == EGL14.EGL_NO_SURFACE || renderer == null) return;
        if (!EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)) return;
        if (sizeChanged) {
            sizeChanged = false;
            renderer.onSurfaceChanged(null, surfaceWidth, surfaceHeight);
        }
        renderer.onDrawFrame(null);
        if (!EGL14.eglSwapBuffers(eglDisplay, windowSurface)) {
            int error = EGL14.eglGetError();
            if (error == EGL14.EGL_BAD_SURFACE || error == EGL14.EGL_BAD_NATIVE_WINDOW) {
                EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext);
                EGL14.eglDestroySurface(eglDisplay, windowSurface);
                windowSurface = EGL14.EGL_NO_SURFACE;
                currentSurface = null;
                currentSurfaceVolatile = null;
            }
        }
    }

    private void releaseEGL() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return;
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        if (windowSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, windowSurface);
        if (pbufferSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbufferSurface);
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
        // No eglTerminate: the default display is shared with Android's own UI renderer in this process.
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        windowSurface = EGL14.EGL_NO_SURFACE;
        pbufferSurface = EGL14.EGL_NO_SURFACE;
        currentSurface = null;
        currentSurfaceVolatile = null;
    }
}

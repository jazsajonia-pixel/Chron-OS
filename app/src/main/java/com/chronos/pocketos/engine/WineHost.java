/*
 * PocketOS host for Wine's Android display driver (dlls/wineandroid.drv in Wine 11.x).
 *
 * Wine runs in its own Linux process. The driver library loaded here (wineandroid.so) receives that process's
 * requests over a socket and calls the methods below on the UI thread: "create a window", "the window moved",
 * "set the cursor". Each Windows top-level window becomes an Android view placed on the PocketOS desktop at the
 * position Wine gives it; Wine draws straight into that view's surface. Touches, the Smart Cursor, a mouse and the
 * keyboard go back to Wine through the native methods.
 *
 * The window bookkeeping follows Wine's own sample host, dlls/wineandroid.drv/WineActivity.java
 * (Copyright 2013-2017 Alexandre Julliard, LGPL-2.1-or-later). PocketOS changes: no Activity; the desktop window
 * is invisible (PocketOS's own desktop shows through); input outside Windows windows passes to PocketOS; an input
 * connection lets the on-screen keyboard type into Windows programs.
 *
 * This file is distributed under the GNU Lesser General Public License 2.1 or later, like Wine.
 */
package com.chronos.pocketos.engine;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class WineHost {
    private static final String TAG = "PocketOS-Wine";

    // Implemented by wineandroid.so (registered in its JNI_OnLoad for this class name).
    private native void wine_init();
    private native void wine_desktop_changed(int width, int height);
    private native void wine_config_changed(int dpi);
    private native void wine_surface_changed(int hwnd, Surface surface, boolean opengl);
    private native boolean wine_motion_event(int hwnd, int action, int x, int y, int state, int vscroll);
    private native boolean wine_keyboard_event(int hwnd, int action, int keycode, int state);

    /** What PocketOS needs to know about Windows windows. */
    public interface Listener {
        /** Wine's desktop exists: programs can be started. */
        void onDesktopReady();
        /** A visible top-level Windows window appeared, closed, or was focused by a click. */
        void onWindowsChanged(int visibleTopLevelWindows);
        void onWindowActivated(int hwnd);
    }

    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static WineHost instance;

    private final Context context;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final HashMap<Integer, WineWindow> windows = new HashMap<>();
    private DesktopLayer layer;
    private WineWindow desktop;
    private WineWindow messageWindow;
    private PointerIcon cursor;
    private boolean nativeReady;
    private boolean desktopReady;
    private int dpi = 120;
    private int activeHwnd;

    private WineHost(Context context) { this.context = context.getApplicationContext(); }

    public static synchronized WineHost get(Context context) {
        if (instance == null) instance = new WineHost(context);
        return instance;
    }

    public void addListener(Listener l) { listeners.add(l); if (desktopReady) l.onDesktopReady(); }
    public void removeListener(Listener l) { listeners.remove(l); }
    public boolean isDesktopReady() { return desktopReady; }
    public boolean isNativeReady() { return nativeReady; }

    /**
     * Loads Wine's Unix libraries into this process and opens the driver socket. Must run once, before the first
     * Wine process starts, on the main thread (the driver attaches to the main looper).
     */
    public void loadDriver(String unixLibDir) {
        if (nativeReady) return;
        for (String lib : new String[] { "ntdll.so", "win32u.so", "wineandroid.so" }) System.load(unixLibDir + "/" + lib);
        wine_init();
        nativeReady = true;
    }

    /** Windows DPI: 96 = 100%. PocketOS picks it from the screen density and the user's scale setting. */
    public void setDpi(int dpi) {
        this.dpi = dpi;
        if (desktopReady && nativeReady) wine_config_changed(dpi);
    }

    /** The view PocketOS places over its desktop area. Created once and reused. */
    public DesktopLayer layer(Context activityContext) {
        if (layer == null) {
            layer = new DesktopLayer(activityContext);
            messageWindow = new WineWindow(WineWindow.HWND_MESSAGE, null);
            messageWindow.createGroups();
        }
        return layer;
    }

    public int visibleTopLevelCount() {
        int n = 0;
        if (desktop != null) for (WineWindow w : desktop.children) if (w.visible) n++;
        return n;
    }

    /** Hwnd of the Windows window that last received a click, if it is still open and visible. */
    public int activeWindow() {
        WineWindow w = windows.get(activeHwnd);
        return w != null && w.visible ? activeHwnd : 0;
    }

    /** Sends a key to the Windows window that has focus. Returns false if no Windows window could take it. */
    public boolean sendKey(KeyEvent event) {
        int hwnd = activeWindow();
        if (hwnd == 0 || !nativeReady) return false;
        return wine_keyboard_event(hwnd, event.getAction(), event.getKeyCode(), event.getMetaState());
    }

    /** Types text into the focused Windows window (on-screen keyboard, companion keyboard). */
    public void typeText(CharSequence text) {
        if (activeWindow() == 0) return;
        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toString().toCharArray());
        if (events == null) return;
        for (KeyEvent e : events) sendKey(e);
    }

    /** Moves the Windows mouse pointer without clicking (Smart Cursor hover, mouse hover). */
    public void hover(float layerX, float layerY) {
        WineWindow w = topLevelAt(Math.round(layerX), Math.round(layerY));
        if (w == null || !nativeReady) return;
        wine_motion_event(w.hwnd, MotionEvent.ACTION_HOVER_MOVE, Math.round(layerX), Math.round(layerY), 0, 0);
    }

    public void showSoftKeyboard() {
        WineWindow w = windows.get(activeWindow());
        View v = w != null && w.windowGroup != null ? w.windowGroup.content : null;
        if (v == null) return;
        v.requestFocus();
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(v, 0);
    }

    WineWindow topLevelAt(int x, int y) {
        if (desktop == null) return null;
        // children is kept in Wine's z-order (front first, like SetWindowPos's insert-after), so check from the front.
        for (WineWindow w : desktop.children) if (w.visible && w.windowGroup != null && w.visibleRect.contains(x, y)) return w;
        return null;
    }

    private void notifyWindows() {
        int n = visibleTopLevelCount();
        for (Listener l : listeners) l.onWindowsChanged(n);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Windows and views

    final class WineWindow {
        static final int HWND_MESSAGE = 0xfffffffd;
        static final int SWP_NOZORDER = 0x04;
        static final int WS_VISIBLE = 0x10000000;

        final int hwnd;
        int owner;
        int style;
        boolean visible;
        Rect visibleRect = new Rect();
        Rect clientRect = new Rect();
        WineWindow parent;
        final ArrayList<WineWindow> children = new ArrayList<>();
        Surface windowSurface, clientSurface;
        SurfaceTexture windowTexture, clientTexture;
        WindowGroup windowGroup, clientGroup;

        WineWindow(int hwnd, WineWindow parent) {
            this.hwnd = hwnd;
            this.parent = parent;
            windows.put(hwnd, this);
            if (parent != null) parent.children.add(this);
        }

        void destroy() {
            visible = false;
            windows.remove(hwnd);
            if (parent != null) parent.children.remove(this);
            destroyGroups();
        }

        WindowGroup createGroups() {
            if (clientGroup != null) return clientGroup;
            windowGroup = new WindowGroup(this);
            clientGroup = new WindowGroup(this);
            windowGroup.addView(clientGroup);
            clientGroup.setLayout(clientRect.left - visibleRect.left, clientRect.top - visibleRect.top,
                                  clientRect.right - visibleRect.left, clientRect.bottom - visibleRect.top);
            if (parent != null) {
                parent.createGroups();
                if (visible) addToParent();
                windowGroup.setLayout(visibleRect.left, visibleRect.top, visibleRect.right, visibleRect.bottom);
            }
            return clientGroup;
        }

        void destroyGroups() {
            if (windowGroup != null) {
                if (parent != null && parent.clientGroup != null) removeFromParent();
                windowGroup.destroyContent();
            }
            if (clientGroup != null) clientGroup.destroyContent();
            windowGroup = null;
            clientGroup = null;
        }

        void createWholeView() {
            if (windowGroup == null) createGroups();
            windowGroup.createContent(false).layout(0, 0, visibleRect.width(), visibleRect.height());
        }

        void createClientView() {
            if (clientGroup == null) createGroups();
            clientGroup.createContent(true).layout(0, 0, clientRect.width(), clientRect.height());
        }

        private ViewGroup parentGroup() {
            // Top-level windows go straight into the PocketOS layer; child windows into their parent's client area.
            return parent == desktop ? layer : parent.clientGroup;
        }

        void addToParent() {
            ViewGroup group = parentGroup();
            if (group == null) return;
            int pos = group.getChildCount() - 1;
            if (group instanceof WindowGroup && pos >= 0 && group.getChildAt(pos) == ((WindowGroup) group).content) pos--;
            for (int i = 0; i < parent.children.size() && pos >= 0; i++) {
                WineWindow child = parent.children.get(i);
                if (child == this) break;
                if (!child.visible) continue;
                View v = group.getChildAt(pos);
                if (v instanceof WindowGroup && child == ((WindowGroup) v).window) pos--;
            }
            group.addView(windowGroup, pos + 1);
        }

        void removeFromParent() {
            ViewGroup group = parentGroup();
            if (group != null) group.removeView(windowGroup);
        }

        void setZOrder(WineWindow prev) {
            int pos = -1;
            parent.children.remove(this);
            if (prev != null) pos = parent.children.indexOf(prev);
            parent.children.add(pos + 1, this);
        }

        void syncViewsZOrder() {
            ViewGroup group = parentGroup();
            if (group == null) return;
            int i, j;
            for (i = parent.children.size() - 1, j = 0; i >= 0; i--) {
                WineWindow child = parent.children.get(i);
                if (!child.visible) continue;
                if (j >= group.getChildCount()) break;
                View v = group.getChildAt(j);
                if (group instanceof WindowGroup && v == ((WindowGroup) group).content) continue;
                if (!(v instanceof WindowGroup) || child != ((WindowGroup) v).window) break;
                j++;
            }
            while (i >= 0) {
                WineWindow child = parent.children.get(i--);
                if (child.visible && child.windowGroup != null) child.windowGroup.bringToFront();
            }
        }

        void posChanged(int flags, int insertAfter, int owner, int style, Rect clientRect, Rect visibleRect) {
            boolean wasVisible = visible;
            this.visibleRect = visibleRect;
            this.clientRect = clientRect;
            this.style = style;
            this.owner = owner;
            visible = (style & WS_VISIBLE) != 0;
            if ((flags & SWP_NOZORDER) == 0 && parent != null) setZOrder(windows.get(insertAfter));
            if (windowGroup != null) {
                windowGroup.setLayout(visibleRect.left, visibleRect.top, visibleRect.right, visibleRect.bottom);
                if (parent != null) {
                    if (!wasVisible && visible) addToParent();
                    else if (wasVisible && !visible) removeFromParent();
                    else if (visible && (flags & SWP_NOZORDER) == 0) syncViewsZOrder();
                }
            }
            if (clientGroup != null)
                clientGroup.setLayout(clientRect.left - visibleRect.left, clientRect.top - visibleRect.top,
                                      clientRect.right - visibleRect.left, clientRect.bottom - visibleRect.top);
            if (parent == desktop && wasVisible != visible) notifyWindows();
        }

        void setParent(WineWindow newParent) {
            if (windowGroup != null) {
                if (visible) removeFromParent();
                newParent.createGroups();
                windowGroup.setLayout(visibleRect.left, visibleRect.top, visibleRect.right, visibleRect.bottom);
            }
            parent.children.remove(this);
            parent = newParent;
            parent.children.add(this);
            if (visible && windowGroup != null) addToParent();
        }

        void setSurface(SurfaceTexture texture, boolean isClient) {
            if (!nativeReady) return;
            if (isClient) {
                if (texture == null) clientSurface = null;
                else if (texture != clientTexture) { clientTexture = texture; clientSurface = new Surface(texture); }
                if (clientSurface != null) wine_surface_changed(hwnd, clientSurface, true);
            } else {
                if (texture == null) windowSurface = null;
                else if (texture != windowTexture) { windowTexture = texture; windowSurface = new Surface(texture); }
                if (windowSurface != null) wine_surface_changed(hwnd, windowSurface, false);
            }
        }

        /** Screen position (Wine coordinates) of a touch inside this window's whole view. */
        void eventPos(MotionEvent event, int[] pos) {
            int left = 0, top = 0;
            for (WineWindow w = this; w != null && w != desktop; w = w.parent) {
                if (w.windowGroup != null) { left += w.windowGroup.getLeft(); top += w.windowGroup.getTop(); }
                if (w.parent != null && w.parent != desktop && w.parent.clientGroup != null) {
                    left += w.parent.clientGroup.getLeft(); top += w.parent.clientGroup.getTop();
                }
            }
            pos[0] = Math.round(event.getX() + left);
            pos[1] = Math.round(event.getY() + top);
        }

        WineWindow topLevel() {
            WineWindow w = this;
            while (w.parent != null && w.parent != desktop) w = w.parent;
            return w;
        }
    }

    /** A Windows window (whole or client area) as an Android view group, optionally with a drawing surface. */
    final class WindowGroup extends ViewGroup {
        final WineWindow window;
        WineView content;

        WindowGroup(WineWindow window) {
            super(layer != null ? layer.getContext() : context);
            this.window = window;
        }

        void setLayout(int left, int top, int right, int bottom) {
            if (right <= left + 1) right = left + 2;
            if (bottom <= top + 1) bottom = top + 2;
            layout(left, top, right, bottom);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            if (content != null) content.layout(0, 0, r - l, b - t);
        }

        WineView createContent(boolean isClient) {
            if (content != null) return content;
            content = new WineView(getContext(), window, isClient);
            addView(content);
            return content;
        }

        void destroyContent() {
            if (content == null) return;
            removeView(content);
            content = null;
        }
    }

    /** Drawing surface of a Windows window. Wine draws into its SurfaceTexture; input goes back to Wine. */
    @SuppressLint("ViewConstructor")
    final class WineView extends TextureView implements TextureView.SurfaceTextureListener {
        final WineWindow window;
        final boolean isClient;

        WineView(Context c, WineWindow window, boolean isClient) {
            super(c);
            this.window = window;
            this.isClient = isClient;
            setSurfaceTextureListener(this);
            setOpaque(false);
            setFocusable(!isClient);
            setFocusableInTouchMode(!isClient);
        }

        @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { window.setSurface(s, isClient); }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { window.setSurface(s, isClient); }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { window.setSurface(null, isClient); return false; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }

        private boolean handlesInput() {
            // Child windows are drawn into their top-level window's surface; the top-level view takes the input.
            return !isClient && (window.parent == null || window.parent == desktop);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!handlesInput() || !nativeReady) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) activate();
            int[] pos = new int[2];
            window.eventPos(event, pos);
            return wine_motion_event(window.hwnd, event.getAction(), pos[0], pos[1], event.getButtonState(), 0);
        }

        @Override public boolean onGenericMotionEvent(MotionEvent event) {
            if (!handlesInput() || !nativeReady) return false;
            if ((event.getSource() & android.view.InputDevice.SOURCE_CLASS_POINTER) == 0) return super.onGenericMotionEvent(event);
            // A mouse's primary button also arrives as touch down/up; sending both confuses Wine's button state.
            int m = event.getActionMasked();
            if ((m == MotionEvent.ACTION_BUTTON_PRESS || m == MotionEvent.ACTION_BUTTON_RELEASE)
                    && event.getActionButton() == MotionEvent.BUTTON_PRIMARY) return true;
            if (m == MotionEvent.ACTION_BUTTON_PRESS) activate();
            int[] pos = new int[2];
            window.eventPos(event, pos);
            return wine_motion_event(window.hwnd, event.getAction(), pos[0], pos[1], event.getButtonState(),
                                     (int) event.getAxisValue(MotionEvent.AXIS_VSCROLL));
        }

        private void activate() {
            activeHwnd = window.topLevel().hwnd;
            requestFocus();
            for (Listener l : listeners) l.onWindowActivated(activeHwnd);
        }

        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            if (nativeReady && wine_keyboard_event(window.hwnd, event.getAction(), event.getKeyCode(), event.getMetaState())) return true;
            return super.dispatchKeyEvent(event);
        }

        @Override public PointerIcon onResolvePointerIcon(MotionEvent event, int index) { return cursor; }

        @Override public boolean onCheckIsTextEditor() { return handlesInput(); }

        @Override public InputConnection onCreateInputConnection(EditorInfo info) {
            info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            info.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
            return new BaseInputConnection(this, false) {
                @Override public boolean commitText(CharSequence text, int newCursorPosition) { typeText(text); return true; }
                @Override public boolean deleteSurroundingText(int before, int after) {
                    for (int i = 0; i < before; i++) {
                        sendKey(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                        sendKey(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
                    }
                    return true;
                }
                @Override public boolean sendKeyEvent(KeyEvent event) { return sendKey(event); }
            };
        }
    }

    /**
     * The layer PocketOS puts over its desktop area. Only Windows windows are drawn; touches anywhere else fall
     * through to PocketOS (the layer reports them as not handled).
     */
    public final class DesktopLayer extends ViewGroup {
        DesktopLayer(Context c) {
            super(c);
            setClipChildren(true);
        }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            if (nativeReady && w > 0 && h > 0) wine_desktop_changed(w, h);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) { /* Wine positions windows */ }

        /** True if a visible Windows window covers this point of the layer. */
        public boolean hasWindowAt(float x, float y) { return topLevelAt(Math.round(x), Math.round(y)) != null; }

        @Override public boolean dispatchTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && !hasWindowAt(ev.getX(), ev.getY())) return false;
            return super.dispatchTouchEvent(ev);
        }

        @Override public boolean dispatchGenericMotionEvent(MotionEvent ev) {
            if (!hasWindowAt(ev.getX(), ev.getY())) return false;
            return super.dispatchGenericMotionEvent(ev);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Called by wineandroid.so (any thread); everything is moved to the UI thread in order.

    @SuppressWarnings("unused") public void createDesktopView() {
        ui.post(() -> {
            desktopReady = true;
            if (nativeReady) {
                wine_config_changed(dpi);
                if (layer != null && layer.getWidth() > 0) wine_desktop_changed(layer.getWidth(), layer.getHeight());
            }
            for (Listener l : listeners) l.onDesktopReady();
        });
    }

    @SuppressWarnings("unused") public void createWindow(int hwnd, boolean isDesktop, boolean opengl, int parent) {
        ui.post(() -> {
            WineWindow w = windows.get(hwnd);
            if (w == null) {
                w = new WineWindow(hwnd, isDesktop ? null : windows.get(parent));
                if (isDesktop) {
                    // The Windows desktop is not drawn: PocketOS's desktop is the background.
                    desktop = w;
                } else {
                    w.createGroups();
                    if (w.parent == desktop) w.createWholeView();
                }
            }
            if (opengl && !isDesktop) w.createClientView();
        });
    }

    @SuppressWarnings("unused") public void destroyWindow(int hwnd) {
        ui.post(() -> {
            WineWindow w = windows.get(hwnd);
            if (w == null) return;
            boolean topLevel = w.parent == desktop && w.visible;
            w.destroy();
            if (hwnd == activeHwnd) activeHwnd = 0;
            if (topLevel) notifyWindows();
        });
    }

    @SuppressWarnings("unused") public void setParent(int hwnd, int parent) {
        ui.post(() -> {
            WineWindow w = windows.get(hwnd), p = windows.get(parent);
            if (w == null || p == null || w.parent == null) return;
            w.setParent(p);
            if (w.parent == desktop) w.createWholeView();
        });
    }

    @SuppressWarnings("unused") public void setCursor(int id, int width, int height, int hotX, int hotY, int[] bits) {
        ui.post(() -> {
            if (bits != null) cursor = PointerIcon.create(Bitmap.createBitmap(bits, width, height, Bitmap.Config.ARGB_8888), hotX, hotY);
            else if (layer != null) cursor = PointerIcon.getSystemIcon(layer.getContext(), id);
        });
    }

    @SuppressWarnings("unused") public void windowPosChanged(int hwnd, int flags, int insertAfter, int owner, int style,
            int wl, int wt, int wr, int wb, int cl, int ct, int cr, int cb, int vl, int vt, int vr, int vb) {
        final Rect client = new Rect(cl, ct, cr, cb), visibleR = new Rect(vl, vt, vr, vb);
        ui.post(() -> {
            WineWindow w = windows.get(hwnd);
            if (w != null) w.posChanged(flags, insertAfter, owner, style, client, visibleR);
        });
    }

    /** Forgets all windows (the Windows engine stopped). */
    public void reset() {
        ui.post(() -> {
            for (WineWindow w : new ArrayList<>(windows.values())) if (w != messageWindow) w.destroyGroups();
            windows.clear();
            if (messageWindow != null) windows.put(messageWindow.hwnd, messageWindow);
            if (layer != null) layer.removeAllViews();
            desktop = null;
            desktopReady = false;
            activeHwnd = 0;
            notifyWindows();
        });
        Log.i(TAG, "windows reset");
    }
}

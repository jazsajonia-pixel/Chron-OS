package com.chronos.pocketos.windows.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.text.InputType;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import com.chronos.pocketos.windows.renderer.DisplayHost;
import com.chronos.pocketos.windows.renderer.GLRenderer;
import com.chronos.pocketos.windows.renderer.ViewTransformation;
import com.chronos.pocketos.windows.xserver.Pointer;
import com.chronos.pocketos.windows.xserver.ScreenInfo;
import com.chronos.pocketos.windows.xserver.XServer;

/**
 * PocketOS: shows a running Windows session inside an ordinary view, so PocketOS can place it in one of its own
 * windows. It is a TextureView, so it clips, overlaps and moves like any other part of the PocketOS desktop.
 *
 * Input:
 *  - tap / Smart Cursor click = left click where the pointer is; press and move = drag;
 *  - mouse right/middle buttons (and the Smart Cursor's two-finger tap) = right/middle click;
 *  - mouse hover moves the Windows pointer, wheel/two-finger scroll = mouse wheel;
 *  - hardware keyboard keys, and text from the on-screen keyboard, go to the focused Windows program.
 */
@SuppressLint("ViewConstructor")
public class DisplayView extends TextureView implements TextureView.SurfaceTextureListener {
    private final XServer xServer;
    private final DisplayHost host;
    private Pointer.Button pressedButton;
    private float scrollRemainder;
    private final KeyCharacterMap charMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);

    public DisplayView(Context context, XServer xServer, DisplayHost host) {
        super(context);
        this.xServer = xServer;
        this.host = host;
        setOpaque(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setSurfaceTextureListener(this);
    }

    // ---- surface -------------------------------------------------------------------------------------------

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        host.setOutput(surface, width, height, null);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        host.resize(width, height);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        // Release the SurfaceTexture only after the render thread has stopped drawing into it.
        host.setOutput(null, 0, 0, surface::release);
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    // ---- pointer -------------------------------------------------------------------------------------------

    /** Maps a point in this view to Windows screen coordinates, following the renderer's letterboxing. */
    private int[] toScreen(float x, float y) {
        ScreenInfo screen = xServer.screenInfo;
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        float sx, sy;
        GLRenderer renderer = xServer.getRenderer();
        if (renderer != null && renderer.isFullscreen()) {
            sx = x * screen.width / w;
            sy = y * screen.height / h;
        }
        else {
            ViewTransformation vt = new ViewTransformation();
            vt.update(w, h, screen.width, screen.height);
            sx = (x - vt.viewOffsetX) / vt.aspect;
            sy = (y - vt.viewOffsetY) / vt.aspect;
        }
        int px = Math.max(0, Math.min(screen.width - 1, Math.round(sx)));
        int py = Math.max(0, Math.min(screen.height - 1, Math.round(sy)));
        return new int[]{px, py};
    }

    private void movePointer(MotionEvent event) {
        int[] p = toScreen(event.getX(), event.getY());
        xServer.injectPointerMove(p[0], p[1]);
    }

    private static Pointer.Button buttonFor(MotionEvent event) {
        int state = event.getButtonState();
        if ((state & MotionEvent.BUTTON_SECONDARY) != 0) return Pointer.Button.BUTTON_RIGHT;
        if ((state & MotionEvent.BUTTON_TERTIARY) != 0) return Pointer.Button.BUTTON_MIDDLE;
        return Pointer.Button.BUTTON_LEFT;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!hasFocus()) requestFocus();
                movePointer(event);
                releasePressed();
                pressedButton = buttonFor(event);
                xServer.injectPointerButtonPress(pressedButton);
                return true;
            case MotionEvent.ACTION_MOVE:
                movePointer(event);
                return true;
            case MotionEvent.ACTION_UP:
                movePointer(event);
                releasePressed();
                return true;
            case MotionEvent.ACTION_CANCEL:
                releasePressed();
                return true;
            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return true; // handled through DOWN/UP and their button state
        }
        return true;
    }

    private void releasePressed() {
        if (pressedButton != null) {
            xServer.injectPointerButtonRelease(pressedButton);
            pressedButton = null;
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_HOVER_ENTER) {
            movePointer(event);
            return true;
        }
        if (action == MotionEvent.ACTION_SCROLL) {
            movePointer(event);
            scrollRemainder += event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            while (scrollRemainder >= 1f) { click(Pointer.Button.BUTTON_SCROLL_UP); scrollRemainder -= 1f; }
            while (scrollRemainder <= -1f) { click(Pointer.Button.BUTTON_SCROLL_DOWN); scrollRemainder += 1f; }
            float h = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
            if (h >= 0.5f) click(Pointer.Button.BUTTON_SCROLL_CLICK_RIGHT);
            else if (h <= -0.5f) click(Pointer.Button.BUTTON_SCROLL_CLICK_LEFT);
            return true;
        }
        if (event.isFromSource(InputDevice.SOURCE_CLASS_JOYSTICK)) {
            return false; // game controllers are read by the session's controller handler
        }
        return super.onGenericMotionEvent(event);
    }

    private void click(Pointer.Button button) {
        xServer.injectPointerButtonPress(button);
        xServer.injectPointerButtonRelease(button);
    }

    // ---- keyboard ------------------------------------------------------------------------------------------

    /** Sends a key event to Windows. Returns false for keys Windows has no equivalent for. */
    public boolean sendKey(KeyEvent event) {
        return xServer.keyboard.onKeyEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (sendKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    public void showSoftKeyboard() {
        requestFocus();
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(this, 0);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                typeText(text);
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                for (int i = 0; i < Math.max(1, beforeLength); i++) tapKey(KeyEvent.KEYCODE_DEL);
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                return sendKey(event);
            }

            @Override
            public boolean performEditorAction(int actionCode) {
                tapKey(KeyEvent.KEYCODE_ENTER);
                return true;
            }
        };
    }

    /** Types text into Windows, one character at a time. */
    public void typeText(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') { tapKey(KeyEvent.KEYCODE_ENTER); continue; }
            KeyEvent[] events = charMap.getEvents(new char[]{c});
            if (events != null) {
                for (KeyEvent e : events) sendKey(e);
            }
            else {
                sendKey(new KeyEvent(SystemClock.uptimeMillis(), String.valueOf(c), KeyCharacterMap.VIRTUAL_KEYBOARD, 0));
            }
        }
    }

    private void tapKey(int keyCode) {
        long now = SystemClock.uptimeMillis();
        sendKey(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        sendKey(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    @Override
    protected void onDetachedFromWindow() {
        releasePressed();
        super.onDetachedFromWindow();
    }
}

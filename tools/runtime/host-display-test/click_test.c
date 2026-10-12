/* Display-pipeline test program: a 400x300 window with a text box and a big button. Clicking the button writes
 * the text box contents to C:\pocketos_click.txt and exits. Lets the host test prove that drawing, mouse and
 * keyboard all go through PocketOS's own display server. */
#include <windows.h>
static HWND edit;
static LRESULT CALLBACK proc(HWND h, UINT m, WPARAM w, LPARAM l) {
    if (m == WM_COMMAND && LOWORD(w) == 2) {
        char buf[256] = {0}; GetWindowTextA(edit, buf, sizeof buf);
        HANDLE f = CreateFileA("C:\\pocketos_click.txt", GENERIC_WRITE, 0, 0, CREATE_ALWAYS, 0, 0);
        DWORD n; WriteFile(f, "clicked:", 8, &n, 0); WriteFile(f, buf, lstrlenA(buf), &n, 0); CloseHandle(f);
        PostQuitMessage(0); return 0;
    }
    if (m == WM_DESTROY) { PostQuitMessage(0); return 0; }
    return DefWindowProcA(h, m, w, l);
}
int WINAPI WinMain(HINSTANCE i, HINSTANCE p, LPSTR c, int s) {
    WNDCLASSA wc = {0}; wc.lpfnWndProc = proc; wc.hInstance = i; wc.lpszClassName = "PocketOSClickTest";
    wc.hbrBackground = CreateSolidBrush(RGB(0, 120, 215)); wc.hCursor = LoadCursor(0, IDC_ARROW);
    RegisterClassA(&wc);
    RECT r = {0, 0, 400, 300}; AdjustWindowRect(&r, WS_OVERLAPPEDWINDOW, FALSE);
    HWND w = CreateWindowA("PocketOSClickTest", "PocketOS click test", WS_OVERLAPPEDWINDOW | WS_VISIBLE,
                           0, 0, r.right - r.left, r.bottom - r.top, 0, 0, i, 0);
    edit = CreateWindowA("EDIT", "", WS_CHILD | WS_VISIBLE | WS_BORDER, 10, 10, 380, 30, w, (HMENU)1, i, 0);
    CreateWindowA("BUTTON", "OK", WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON, 10, 60, 380, 230, w, (HMENU)2, i, 0);
    SetFocus(edit);
    MSG msg; while (GetMessageA(&msg, 0, 0, 0) > 0) { TranslateMessage(&msg); DispatchMessageA(&msg); }
    return 0;
}

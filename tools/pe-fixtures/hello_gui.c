/*
 * PocketOS runtime verification sample (public domain). GUI subsystem, no window.
 * Writes "POCKETOS_FIXTURE_OK <nonce>" to pocketos_sample_ok.txt next to the executable, where <nonce> is read from
 * pocketos_nonce.txt in the same folder (written by PocketOS when staging). PocketOS then reads the marker back to
 * prove the program really executed. Exits 0 on success.
 */
#include <windows.h>
#include <string.h>
static void dir_of_exe(char *buf, DWORD n) {
    DWORD len = GetModuleFileNameA(NULL, buf, n);
    while (len > 0 && buf[len - 1] != '\\' && buf[len - 1] != '/') len--;
    buf[len] = 0;
}
int WINAPI WinMain(HINSTANCE h, HINSTANCE p, LPSTR c, int n) {
    char dir[MAX_PATH], path[MAX_PATH], nonce[64] = "none";
    dir_of_exe(dir, MAX_PATH);
    lstrcpyA(path, dir); lstrcatA(path, "pocketos_nonce.txt");
    HANDLE in = CreateFileA(path, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    if (in != INVALID_HANDLE_VALUE) {
        DWORD r = 0; ReadFile(in, nonce, sizeof(nonce) - 1, &r, NULL); nonce[r] = 0; CloseHandle(in);
        for (DWORD i = 0; i < r; i++) if (nonce[i] == '\r' || nonce[i] == '\n') { nonce[i] = 0; break; }
    }
    lstrcpyA(path, dir); lstrcatA(path, "pocketos_sample_ok.txt");
    HANDLE f = CreateFileA(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (f == INVALID_HANDLE_VALUE) return 1;
    char line[128]; lstrcpyA(line, "POCKETOS_FIXTURE_OK "); lstrcatA(line, nonce); lstrcatA(line, "\r\n");
    DWORD w; WriteFile(f, line, lstrlenA(line), &w, NULL); CloseHandle(f);
    return 0;
}

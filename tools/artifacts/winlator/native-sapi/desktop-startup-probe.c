#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
static LRESULT CALLBACK proc(HWND window, UINT message, WPARAM word, LPARAM data) {
    return DefWindowProcW(window, message, word, data);
}
int main(int argc, char **argv) {
    WCHAR name[128];
    HDESK desktop;
    WNDCLASSW cls = {0};
    HWND window;
    MSG message;
    if (argc != 3) return 1;
    MultiByteToWideChar(CP_UTF8, 0, argv[2], -1, name, 128);
    if (!strcmp(argv[1], "--precreate"))
        desktop = CreateDesktopW(name, NULL, NULL, 0, GENERIC_ALL, NULL);
    else {
        desktop = NULL;
        for (int i = 0; i < 300 && !desktop; ++i) {
            desktop = OpenDesktopW(name, 0, FALSE, GENERIC_ALL);
            if (!desktop) Sleep(100);
        }
    }
    if (!desktop || !SetThreadDesktop(desktop)) return 2;
    cls.lpfnWndProc = proc;
    cls.hInstance = GetModuleHandleW(NULL);
    cls.lpszClassName = L"WinlatorDesktopProbe";
    RegisterClassW(&cls);
    window = CreateWindowExW(0, cls.lpszClassName, L"Probe", WS_OVERLAPPED,
        0, 0, 0, 0, NULL, NULL, cls.hInstance, NULL);
    if (!window) return 3;
    puts("window-ready"); fflush(stdout);
    while (GetMessageW(&message, NULL, 0, 0) > 0) DispatchMessageW(&message);
    return 0;
}

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>

/* Diagnostic guest only: no game binaries or packaged speech payload changes. */
static FILE *output;
typedef DWORD (__stdcall *speak_fn)(const WCHAR *);
static speak_fn speak;
static BOOL held[4];
static WCHAR status[256] = L"Arrow key checker ready. No arrow keys held.";
static const WCHAR *names[] = {L"Left arrow", L"Up arrow", L"Right arrow", L"Down arrow"};

static void say(const WCHAR *text) {
    DWORD result = speak ? speak(text) : ERROR_MOD_NOT_FOUND;
    fprintf(output, "speech status=%lu text=%ls\n", result, text); fflush(output);
}
static void report_arrow(HWND window, UINT message, WPARAM key) {
    if (key < VK_LEFT || key > VK_DOWN) return;
    unsigned index = (unsigned)(key - VK_LEFT);
    BOOL down = message == WM_KEYDOWN || message == WM_SYSKEYDOWN;
    if (held[index] == down) return; /* Never chatter on keyboard repeats. */
    held[index] = down;
    WCHAR text[100];
    swprintf(text, 100, L"%ls %ls.", names[index], down ? L"pressed and held" : L"released");
    say(text);
    wcscpy(status, L"Held arrows:");
    BOOL any = FALSE;
    for (unsigned i = 0; i < 4; i++) if (held[i]) {
        wcscat(status, L" "); wcscat(status, names[i]); any = TRUE;
    }
    if (!any) wcscpy(status, L"No arrow keys held.");
    InvalidateRect(window, NULL, TRUE);
}
static LRESULT CALLBACK window_proc(HWND window, UINT message, WPARAM key, LPARAM detail) {
    switch (message) {
    case WM_KEYDOWN: case WM_KEYUP: case WM_SYSKEYDOWN: case WM_SYSKEYUP:
        fprintf(output, "key time=%llu message=%x vk=%llu repeat=%lu\n", GetTickCount64(),
                message, (unsigned long long)key, (unsigned long)(detail & 0xffff));
        fflush(output);
        report_arrow(window, message, key);
        return 0;
    case WM_PAINT: {
        PAINTSTRUCT paint;
        HDC dc = BeginPaint(window, &paint);
        RECT area; GetClientRect(window, &area);
        area.left += 20; area.top += 20;
        DrawTextW(dc, status, -1, &area, DT_LEFT | DT_WORDBREAK);
        EndPaint(window, &paint); return 0;
    }
    case WM_KILLFOCUS:
        for (unsigned i = 0; i < 4; i++) if (held[i]) report_arrow(window, WM_KEYUP, VK_LEFT + i);
        return 0;
    case WM_CLOSE: DestroyWindow(window); return 0;
    case WM_DESTROY: PostQuitMessage(0); return 0;
    }
    return DefWindowProcW(window, message, key, detail);
}
int WINAPI WinMain(HINSTANCE instance, HINSTANCE previous, LPSTR command, int show) {
    (void)previous; (void)command; (void)show;
    output = fopen("C:\\NVDA-Bridge\\gesture-input-probe.log", "w");
    if (!output) return 1;
    HMODULE controller = LoadLibraryW(L"C:\\WinlatorNativeSapi\\payload\\nvdaControllerClient64.dll");
    if (!controller) controller = LoadLibraryW(L"nvdaControllerClient.dll");
    if (controller) speak = (speak_fn)(void *)GetProcAddress(controller, "nvdaController_speakText");
    fprintf(output, "speech available=%u error=%lu\n", speak != NULL, speak ? 0 : GetLastError());
    WNDCLASSW type = {0};
    type.lpfnWndProc = window_proc; type.hInstance = instance;
    type.lpszClassName = L"WinlatorGestureProbe";
    type.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    RegisterClassW(&type);
    HWND window = CreateWindowW(type.lpszClassName, L"Arrow key checker",
            WS_OVERLAPPEDWINDOW | WS_VISIBLE, 20, 20, 700, 420, NULL, NULL, instance, NULL);
    if (!window) { fclose(output); return 2; }
    SetForegroundWindow(window); SetFocus(window);
    fprintf(output, "ready window=%p foreground=%p\n", window, GetForegroundWindow()); fflush(output);
    say(L"Arrow key checker ready. With Hold until finger lift enabled, swipe and keep your finger down to hold an arrow. Lift to release. A stationary long press works when assigned.");
    MSG message;
    while (GetMessageW(&message, NULL, 0, 0) > 0) {
        TranslateMessage(&message); DispatchMessageW(&message);
    }
    fputs("closed\n", output); fclose(output);
    return 0;
}

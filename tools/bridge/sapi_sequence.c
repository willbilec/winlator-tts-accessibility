#define COBJMACROS
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <objbase.h>
#include <sapi.h>
#include <stdio.h>

static LRESULT CALLBACK diagnostic_window_proc(HWND window, UINT message, WPARAM word, LPARAM data) {
    return DefWindowProcW(window, message, word, data);
}

int WINAPI WinMain(HINSTANCE instance, HINSTANCE previous, LPSTR command, int show) {
    HRESULT hr;
    ISpVoice *voice = NULL;
    FILE *log = fopen("C:\\NVDA-Bridge\\sapi-sequence.log", "w");
    DWORD start;
    WNDCLASSW window_class = {0};
    HWND window;
    (void)previous; (void)command; (void)show;
    if (!log) return 1;
    window_class.lpfnWndProc = diagnostic_window_proc;
    window_class.hInstance = instance;
    window_class.lpszClassName = L"SapiSequenceDiagnostic";
    if (!RegisterClassW(&window_class)) {
        fprintf(log, "register window=%lu\n", GetLastError()); fflush(log);
        return 4;
    }
    window = CreateWindowExW(0, window_class.lpszClassName,
        L"SAPI sequence diagnostic", WS_OVERLAPPEDWINDOW,
        100, 100, 450, 160, NULL, NULL, instance, NULL);
    fprintf(log, "window=%p error=%lu\n", window, window ? 0 : GetLastError()); fflush(log);
    if (window) ShowWindow(window, SW_SHOW);
    hr = CoInitializeEx(NULL, COINIT_APARTMENTTHREADED);
    fprintf(log, "com=%08lx\n", (unsigned long)hr); fflush(log);
    if (FAILED(hr)) return 2;
    hr = CoCreateInstance(&CLSID_SpVoice, NULL, CLSCTX_INPROC_SERVER,
                          &IID_ISpVoice, (void **)&voice);
    fprintf(log, "create=%08lx\n", (unsigned long)hr); fflush(log);
    if (FAILED(hr) || !voice) return 3;
    start = GetTickCount();
    hr = ISpVoice_Speak(voice, L"First SAPI sentence is complete.", SPF_DEFAULT, NULL);
    fprintf(log, "first=%08lx duration=%lu\n", (unsigned long)hr, GetTickCount()-start); fflush(log);
    Sleep(1500);
    start = GetTickCount();
    hr = ISpVoice_Speak(voice, L"Second SAPI sentence is complete.", SPF_DEFAULT, NULL);
    fprintf(log, "second=%08lx duration=%lu\n", (unsigned long)hr, GetTickCount()-start); fflush(log);
    ISpVoice_Release(voice);
    CoUninitialize();
    Sleep(3000);
    if (window) DestroyWindow(window);
    fclose(log);
    return 0;
}

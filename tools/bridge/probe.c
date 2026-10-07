#include <windows.h>
#include <stdio.h>
#include <string.h>

typedef DWORD (__stdcall *check_fn)(void);
typedef DWORD (__stdcall *speak_fn)(const WCHAR *);

static void timed_check(FILE *log, const char *label, check_fn call) {
    ULONGLONG started = GetTickCount64();
    DWORD result = call();
    fprintf(log, "%s=%lu\n%sMilliseconds=%llu\n", label, result, label,
        GetTickCount64() - started); fflush(log);
}
static void timed_speak(FILE *log, const char *label, speak_fn call, const WCHAR *text) {
    ULONGLONG started = GetTickCount64();
    DWORD result = call(text);
    fprintf(log, "%s=%lu\n%sMilliseconds=%llu\n", label, result, label,
        GetTickCount64() - started); fflush(log);
}

int main(int argc, char **argv) {
    HMODULE dll = LoadLibraryA(argc > 1 ? argv[1] : "nvdaControllerClient.dll");
    check_fn check;
    check_fn cancel;
    speak_fn speak;
    char output[MAX_PATH];
    char *slash;
    FILE *log;
    GetModuleFileNameA(NULL, output, sizeof(output));
    slash = strrchr(output, '\\');
    if (slash) strcpy(slash + 1, "probe-result.txt");
    log = fopen(output, "w");
    if (!log) return 3;
    if (!dll) { fprintf(log, "load=%lu\n", GetLastError()); fclose(log); return 1; }
    check = (check_fn)GetProcAddress(dll, "nvdaController_testIfRunning");
    speak = (speak_fn)GetProcAddress(dll, "nvdaController_speakText");
    cancel = (check_fn)GetProcAddress(dll, "nvdaController_cancelSpeech");
    if (!check || !speak || !cancel) { fputs("missing export\n", log); fclose(log); return 2; }
    timed_check(log, "ready", check);
#ifdef ACTIVE_CANCEL_TEST
    timed_speak(log, "speak", speak,
        L"This deliberately long message must be interrupted while speech is active. "
        L"Repeated menu cancellation must purge the current message only once, "
        L"and the next requested message must still be delivered in its original order. "
        L"This deliberately long message must be interrupted while speech is active. "
        L"Repeated menu cancellation must purge the current message only once, "
        L"and the next requested message must still be delivered in its original order. "
        L"This deliberately long message must be interrupted while speech is active. "
        L"Repeated menu cancellation must purge the current message only once, "
        L"and the next requested message must still be delivered in its original order.");
#ifdef EXTERNAL_CONTROL_TEST
    Sleep(3000);
#else
    Sleep(10);
#endif
#else
    timed_speak(log, "speak", speak, L"First bridge message.");
    Sleep(2500);
#endif
#ifndef NO_CANCEL
    timed_check(log, "cancel", cancel);
    timed_check(log, "cancel2", cancel);
#endif
    timed_speak(log, "speak2", speak, L"Second message after cancel. This sentence should play completely.");
    Sleep(12000);
    fclose(log);
    return 0;
}

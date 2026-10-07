#include <windows.h>
#include <stdio.h>
#include <string.h>
typedef DWORD (__stdcall *check_fn)(void);
typedef DWORD (__stdcall *speak_fn)(const WCHAR *);
int main(void) {
    char path[MAX_PATH];
    char *slash;
    FILE *log;
    HMODULE dll = LoadLibraryA("nvdaControllerClient.dll");
    check_fn check;
    speak_fn speak;
    GetModuleFileNameA(NULL, path, sizeof(path));
    slash = strrchr(path, '\\');
    if (slash) strcpy(slash + 1, "proxy-probe-result.txt");
    log = fopen(path, "w");
    if (!log) return 1;
    if (!dll) { fprintf(log, "load=%lu\n", GetLastError()); fclose(log); return 2; }
    check = (check_fn)GetProcAddress(dll, "nvdaController_testIfRunning");
    speak = (speak_fn)GetProcAddress(dll, "nvdaController_speakText");
    if (!check || !speak) { fputs("missing export\n", log); fclose(log); return 3; }
    fprintf(log, "ready=%lu\n", check()); fflush(log);
    fprintf(log, "speak=%lu\n", speak(L"This is the sixty four bit bridge host. Please listen for this complete sentence.")); fflush(log);
    Sleep(15000);
    fputs("finished\n", log);
    fclose(log);
    return 0;
}

#include <windows.h>
#include <stdio.h>
#include <string.h>
#include <stdbool.h>

typedef void (__cdecl *load_fn)(void);
typedef const WCHAR *(__cdecl *detect_fn)(void);
typedef bool (__cdecl *output_fn)(const WCHAR *, bool);

int main(void) {
    char path[MAX_PATH];
    char *slash;
    FILE *log;
    HMODULE dll;
    load_fn load, unload;
    detect_fn detect;
    output_fn output;
    const WCHAR *reader;
    GetModuleFileNameA(NULL, path, sizeof(path));
    slash = strrchr(path, '\\');
    if (slash) strcpy(slash + 1, "tolk-result.txt");
    log = fopen(path, "w");
    if (!log) return 1;
    dll = LoadLibraryA("Tolk.dll");
    if (!dll) { fprintf(log, "load=%lu\n", GetLastError()); fclose(log); return 2; }
    load = (load_fn)GetProcAddress(dll, "Tolk_Load");
    unload = (load_fn)GetProcAddress(dll, "Tolk_Unload");
    detect = (detect_fn)GetProcAddress(dll, "Tolk_DetectScreenReader");
    output = (output_fn)GetProcAddress(dll, "Tolk_Output");
    if (!load || !unload || !detect || !output) {
        fputs("missing export\n", log); fclose(log); return 3;
    }
    load();
    reader = detect();
    fprintf(log, "reader=%ls\n", reader ? reader : L"none");
    fprintf(log, "output=%d\n", output(L"Tolk bridge speech in Container 2", true));
    fflush(log);
    Sleep(4000);
    unload();
    fclose(log);
    return 0;
}

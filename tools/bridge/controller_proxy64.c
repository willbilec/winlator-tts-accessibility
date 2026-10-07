#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdlib.h>
#include <wchar.h>
#include "bridge_protocol.h"

static DWORD transact(DWORD op, const WCHAR *text, DWORD bytes, int launch) {
    HANDLE pipe = INVALID_HANDLE_VALUE;
    BridgeRequest request = {op, bytes};
    DWORD transferred, result = ERROR_SERVICE_NOT_ACTIVE;
    int attempt;
    for (attempt = 0; attempt < (launch ? 50 : 1); ++attempt) {
        pipe = CreateFileW(BRIDGE_PIPE, GENERIC_READ | GENERIC_WRITE, 0,
                           NULL, OPEN_EXISTING, 0, NULL);
        if (pipe != INVALID_HANDLE_VALUE) break;
        if (attempt == 0 && launch) {
            WCHAR host[MAX_PATH] = L"C:\\NVDA-Bridge\\x86\\bridge_host.exe";
            WCHAR command[MAX_PATH + 3];
            STARTUPINFOW startup = {0};
            PROCESS_INFORMATION process = {0};
            if (GetEnvironmentVariableW(L"WINLATOR_NVDA_BRIDGE_HOST",
                                        host, MAX_PATH) >= MAX_PATH)
                return ERROR_BAD_PATHNAME;
            swprintf(command, MAX_PATH + 3, L"\"%ls\"", host);
            startup.cb = sizeof(startup);
            if (CreateProcessW(host, command, NULL, NULL, FALSE,
                               0, NULL, NULL,
                               &startup, &process)) {
                CloseHandle(process.hThread);
                CloseHandle(process.hProcess);
            }
        }
        Sleep(100);
    }
    if (pipe == INVALID_HANDLE_VALUE) return result;
    if (!WriteFile(pipe, &request, sizeof(request), &transferred, NULL) ||
        transferred != sizeof(request)) goto done;
    if (bytes && (!WriteFile(pipe, text, bytes, &transferred, NULL) ||
                  transferred != bytes)) goto done;
    if (!ReadFile(pipe, &result, sizeof(result), &transferred, NULL) ||
        transferred != sizeof(result)) result = ERROR_SERVICE_NOT_ACTIVE;
done:
    CloseHandle(pipe);
    return result;
}

DWORD __stdcall nvdaController_testIfRunning(void) {
    return transact(BRIDGE_PING, NULL, 0, 1);
}
DWORD __stdcall nvdaController_speakText(const WCHAR *text) {
    size_t count;
    if (!text) return ERROR_INVALID_PARAMETER;
    count = wcslen(text);
    if (count > BRIDGE_MAX_TEXT) return ERROR_INVALID_PARAMETER;
    return transact(BRIDGE_SPEAK, text, (DWORD)((count + 1) * sizeof(WCHAR)), 1);
}
DWORD __stdcall nvdaController_cancelSpeech(void) {
    return transact(BRIDGE_CANCEL, NULL, 0, 1);
}
DWORD __stdcall nvdaController_brailleMessage(const WCHAR *text) {
    (void)text;
    return ERROR_NOT_SUPPORTED;
}
BOOL WINAPI DllMain(HINSTANCE dll, DWORD reason, LPVOID reserved) {
    (void)reserved;
    if (reason == DLL_PROCESS_ATTACH) DisableThreadLibraryCalls(dll);
    return TRUE;
}

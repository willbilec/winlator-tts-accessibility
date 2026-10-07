#define WIN32_LEAN_AND_MEAN
#include "sapi_backend.h"

DWORD __stdcall nvdaController_testIfRunning(void) { return sapi_backend_ready(); }
DWORD __stdcall nvdaController_speakText(const WCHAR *text) {
    return text ? sapi_backend_submit(text) : ERROR_INVALID_PARAMETER;
}
DWORD __stdcall nvdaController_cancelSpeech(void) { return sapi_backend_submit(NULL); }
DWORD __stdcall nvdaController_brailleMessage(const WCHAR *text) {
    (void)text; return ERROR_NOT_SUPPORTED;
}
BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved) {
    HMODULE pinned;
    (void)reserved;
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(instance);
        /* Worker lifetime is the process lifetime. */
        GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_PIN,
                          (LPCWSTR)&nvdaController_testIfRunning, &pinned);
    }
    return TRUE;
}

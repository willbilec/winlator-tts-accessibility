#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <rpc.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <wchar.h>
#include "nvda_rpc_v1.h"

typedef DWORD (__stdcall *check_fn)(void);
typedef DWORD (__stdcall *speak_fn)(const WCHAR *);
static check_fn backend_check, backend_cancel;
static speak_fn backend_speak;
static FILE *service_log;

static LRESULT CALLBACK window_proc(HWND window, UINT message, WPARAM word, LPARAM data) {
    if (message == WM_DESTROY) { PostQuitMessage(0); return 0; }
    return DefWindowProcW(window, message, word, data);
}

void *__RPC_USER midl_user_allocate(size_t bytes) { return malloc(bytes); }
void __RPC_USER midl_user_free(void *data) { free(data); }

error_status_t __stdcall testIfRunning(void) {
    return backend_check ? backend_check() : ERROR_SERVICE_NOT_ACTIVE;
}
error_status_t __stdcall speakText(const WCHAR *text) {
    DWORD status = backend_speak ? backend_speak(text) : ERROR_SERVICE_NOT_ACTIVE;
    if (service_log) {
        fprintf(service_log, "speak length=%lu status=%lu\n",
            text ? (unsigned long)wcslen(text) : 0, status);
        fflush(service_log);
    }
    return status;
}
error_status_t __stdcall cancelSpeech(void) {
    return backend_cancel ? backend_cancel() : ERROR_SERVICE_NOT_ACTIVE;
}
error_status_t __stdcall brailleMessage(const WCHAR *message) {
    (void)message;
    return ERROR_NOT_SUPPORTED;
}

int WINAPI WinMain(HINSTANCE instance, HINSTANCE previous, LPSTR command, int show) {
    HMODULE dll;
    HANDLE desktop;
    WNDCLASSW window_class = {0};
    HWND window;
    MSG message;
    DWORD session = 0;
    WCHAR desktop_name[64] = L"Default";
    WCHAR endpoint[128];
    RPC_STATUS status;
    (void)previous; (void)command; (void)show;
    service_log = fopen("C:\\NVDA-Bridge\\nvda-rpc-service.log", "a");
    dll = LoadLibraryW(L"C:\\NVDA-Bridge\\x86\\nvdaControllerClient32.dll");
    if (!dll) return 1;
    backend_check = (check_fn)GetProcAddress(dll, "nvdaController_testIfRunning");
    backend_speak = (speak_fn)GetProcAddress(dll, "nvdaController_speakText");
    backend_cancel = (check_fn)GetProcAddress(dll, "nvdaController_cancelSpeech");
    if (!backend_check || !backend_speak || !backend_cancel || backend_check()) return 2;
    ProcessIdToSessionId(GetCurrentProcessId(), &session);
    desktop = GetThreadDesktop(GetCurrentThreadId());
    GetUserObjectInformationW(desktop, UOI_NAME, desktop_name,
                              sizeof(desktop_name), NULL);
    swprintf(endpoint, 128, L"NvdaCtlr.%lu.%ls", session, desktop_name);
    status = RpcServerUseProtseqEpW((RPC_WSTR)L"ncalrpc",
        RPC_C_PROTSEQ_MAX_REQS_DEFAULT, (RPC_WSTR)endpoint, NULL);
    if (service_log) {
        fwprintf(service_log, L"endpoint=%ls status=%lu\n", endpoint, status);
        fflush(service_log);
    }
    if (status != RPC_S_OK && status != RPC_S_DUPLICATE_ENDPOINT) return 3;
    status = RpcServerRegisterIf(NvdaController_v1_0_s_ifspec, NULL, NULL);
    if (service_log) { fprintf(service_log, "register=%lu\n", status); fflush(service_log); }
    if (status != RPC_S_OK) return 4;
    window_class.lpfnWndProc = window_proc;
    window_class.hInstance = instance;
    window_class.lpszClassName = L"wxWindowClassNR";
    if (!RegisterClassW(&window_class)) {
        if (service_log) { fprintf(service_log, "register window error=%lu\n", GetLastError()); fflush(service_log); }
        return 5;
    }
    window = CreateWindowExW(0, window_class.lpszClassName, L"NVDA",
        WS_OVERLAPPED, 0, 0, 0, 0, NULL, NULL, instance, NULL);
    if (!window) {
        if (service_log) { fprintf(service_log, "create window error=%lu\n", GetLastError()); fflush(service_log); }
    }
    if (service_log) {
        fprintf(service_log, "window=%lu\n", (unsigned long)(uintptr_t)window);
        fflush(service_log);
    }
    status = RpcServerListen(1, 20, TRUE);
    if (service_log) { fprintf(service_log, "listen=%lu\n", status); fflush(service_log); }
    if (status != RPC_S_OK) return 7;
#if defined(BRIDGE_TEST) || defined(BRIDGE_TEST_GAME)
    {
        STARTUPINFOW startup = {0};
        PROCESS_INFORMATION client = {0};
        LPCWSTR program;
        startup.cb = sizeof(startup);
#ifdef BRIDGE_TEST_GAME
        program = L"D:\\Arcadelux1.0.0\\Arcadelux.exe";
#else
        program = L"C:\\NVDA-Bridge\\original-client\\probe.exe";
#endif
        if (CreateProcessW(program, NULL,
                NULL, NULL, FALSE, 0, NULL, NULL, &startup, &client)) {
            if (service_log) { fprintf(service_log, "test client=%lu\n", client.dwProcessId); fflush(service_log); }
            CloseHandle(client.hThread);
            CloseHandle(client.hProcess);
        } else if (service_log) {
            fprintf(service_log, "test client error=%lu\n", GetLastError());
            fflush(service_log);
        }
    }
#endif
    while (GetMessageW(&message, NULL, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
    RpcMgmtStopServerListening(NULL);
    RpcServerUnregisterIf(NULL, NULL, FALSE);
    return 0;
}

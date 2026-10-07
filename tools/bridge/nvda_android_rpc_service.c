#define WIN32_LEAN_AND_MEAN
#ifdef NATIVE_SAPI
#include "sapi_backend.h"
#endif
#include <winsock2.h>
#include <windows.h>
#include <rpc.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <wchar.h>
#include "nvda_rpc_v1.h"

static FILE *service_log;

#ifdef NATIVE_SAPI
/* Android can stop the bridge voice without waiting for a blocked game's
 * event loop. Keep this channel independent of GUI windows and guest focus. */
static SOCKET speech_control_socket = INVALID_SOCKET;
static DWORD WINAPI speech_control_thread(void *unused) {
    char command[2];
    (void)unused;
    for (;;) {
        int count = recv(speech_control_socket, command, sizeof(command), 0);
        if (count == SOCKET_ERROR) break;
        if (count == 1 && command[0] == 'C') {
            DWORD result = sapi_backend_submit(NULL);
            if (service_log) {
                fprintf(service_log, "keyboard cancel status=%lu\n", result);
                fflush(service_log);
            }
        }
    }
    return 0;
}
static void start_speech_control(void) {
    struct sockaddr_in address = {0};
    HANDLE thread;
    speech_control_socket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (speech_control_socket == INVALID_SOCKET) return;
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons(51235);
    if (bind(speech_control_socket, (struct sockaddr *)&address, sizeof(address)) == SOCKET_ERROR) {
        closesocket(speech_control_socket);
        speech_control_socket = INVALID_SOCKET;
        return;
    }
    thread = CreateThread(NULL, 0, speech_control_thread, NULL, 0, NULL);
    if (thread) CloseHandle(thread);
    else {
        closesocket(speech_control_socket);
        speech_control_socket = INVALID_SOCKET;
    }
}
#endif

static DWORD WINAPI heartbeat_thread(void *unused) {
    (void)unused;
    for (;;) {
        FILE *heartbeat = fopen("C:\\NVDA-Bridge\\nvda-rpc-heartbeat", "w");
        if (heartbeat) {
            fprintf(heartbeat, "%lu\n", GetTickCount());
            fclose(heartbeat);
        }
        Sleep(2000);
    }
    return 0;
}

#ifndef NATIVE_SAPI
static LRESULT CALLBACK window_proc(HWND window, UINT message, WPARAM word, LPARAM data) {
    return DefWindowProcW(window, message, word, data);
}

static DWORD WINAPI nvda_window_thread(void *unused) {
    HDESK desktop;
    WNDCLASSW window_class = {0};
    HWND window;
    MSG message;
    const WCHAR *desktop_name = unused ? (const WCHAR *)unused : L"nogui";
    desktop = NULL;
    /* Winlator's explorer must create and own its virtual desktop. Creating
     * it here starts a different Wine desktop owner; explorer then hands off
     * the game and exits, causing Android to tear down the complete session. */
    for (int attempt = 0; attempt < 300 && !desktop; ++attempt) {
        desktop = OpenDesktopW(desktop_name, 0, FALSE, GENERIC_ALL);
        if (!desktop) Sleep(100);
    }
    if (service_log) { fprintf(service_log, "desktop=%p error=%lu\n", desktop,
        desktop ? 0 : GetLastError()); fflush(service_log); }
    if (!desktop || !SetThreadDesktop(desktop)) {
        if (service_log) { fprintf(service_log, "set desktop error=%lu\n", GetLastError()); fflush(service_log); }
        return 1;
    }
    window_class.lpfnWndProc = window_proc;
    window_class.hInstance = GetModuleHandleW(NULL);
    window_class.lpszClassName = L"wxWindowClassNR";
    RegisterClassW(&window_class);
    /* OpenDesktop can succeed before explorer creates its desktop window.
     * Retry attachment while that owner initializes; never create a desktop. */
    window = NULL;
    for (int attempt = 0; attempt < 300 && !window; ++attempt) {
        window = CreateWindowExW(0, window_class.lpszClassName, L"NVDA",
            WS_OVERLAPPED, 0, 0, 0, 0, NULL, NULL, window_class.hInstance, NULL);
        if (!window) Sleep(100);
    }
    if (service_log) { fprintf(service_log, "detection desktop=%ls\n", desktop_name); fflush(service_log); }
    if (service_log) { fprintf(service_log, "nvda window=%p error=%lu\n", window,
        window ? 0 : GetLastError()); fflush(service_log); }
    if (!window) { CloseDesktop(desktop); return 2; }
    while (GetMessageW(&message, NULL, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
    CloseDesktop(desktop);
    return 0;
}
#endif

static DWORD android_request(char command, const char *text, unsigned int length) {
#ifdef NATIVE_SAPI
    WCHAR *wide;
    DWORD result;
    int count;
    if (command == 'P') return sapi_backend_ready();
    if (command == 'C') return sapi_backend_submit(NULL);
    if (command != 'S' || !text || !length) return ERROR_INVALID_PARAMETER;
    count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text, length, NULL, 0);
    if (!count) return ERROR_INVALID_PARAMETER;
    wide = calloc((size_t)count + 1, sizeof(WCHAR));
    if (!wide) return ERROR_NOT_ENOUGH_MEMORY;
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text, length, wide, count);
    result = sapi_backend_submit(wide);
    free(wide);
    return result;
#else
    SOCKET socket_handle;
    struct sockaddr_in address = {0};
    unsigned int network_length = htonl(length);
    char header[5], reply;
    unsigned int offset = 0;
    int sent;
    socket_handle = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (socket_handle == INVALID_SOCKET) return ERROR_SERVICE_NOT_ACTIVE;
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons(51234);
    if (connect(socket_handle, (struct sockaddr *)&address, sizeof(address)) == SOCKET_ERROR) {
        closesocket(socket_handle);
        return ERROR_SERVICE_NOT_ACTIVE;
    }
    header[0] = command;
    memcpy(header + 1, &network_length, 4);
    while (offset < sizeof(header)) {
        sent = send(socket_handle, header + offset, (int)(sizeof(header) - offset), 0);
        if (sent <= 0) { closesocket(socket_handle); return ERROR_GEN_FAILURE; }
        offset += sent;
    }
    offset = 0;
    while (offset < length) {
        sent = send(socket_handle, text + offset, (int)(length - offset), 0);
        if (sent <= 0) { closesocket(socket_handle); return ERROR_GEN_FAILURE; }
        offset += sent;
    }
    sent = recv(socket_handle, &reply, 1, 0);
    closesocket(socket_handle);
    return sent == 1 && reply == 0 ? ERROR_SUCCESS : ERROR_GEN_FAILURE;
#endif
}

void *__RPC_USER midl_user_allocate(size_t bytes) { return malloc(bytes); }
void __RPC_USER midl_user_free(void *data) { free(data); }

error_status_t __stdcall testIfRunning(void) {
    return android_request('P', NULL, 0);
}
error_status_t __stdcall speakText(const WCHAR *text) {
#ifndef NATIVE_SAPI
    int size;
    char *utf8;
#endif
    DWORD status;
    ULONGLONG started;
    if (!text) return ERROR_INVALID_PARAMETER;
#ifdef NATIVE_SAPI
    started = GetTickCount64();
    status = sapi_backend_submit(text);
#else
    size = WideCharToMultiByte(CP_UTF8, 0, text, -1, NULL, 0, NULL, NULL);
    if (size <= 1 || size > 32769) return ERROR_INVALID_PARAMETER;
    utf8 = malloc(size);
    if (!utf8) return ERROR_NOT_ENOUGH_MEMORY;
    WideCharToMultiByte(CP_UTF8, 0, text, -1, utf8, size, NULL, NULL);
    started = GetTickCount64();
    status = android_request('S', utf8, (unsigned int)size - 1);
    free(utf8);
#endif
    if (service_log) {
        fprintf(service_log, "speak length=%lu status=%lu requestMs=%llu\n",
            text ? (unsigned long)wcslen(text) : 0, status,
            (unsigned long long)(GetTickCount64() - started));
        fflush(service_log);
    }
    return status;
}
error_status_t __stdcall cancelSpeech(void) {
    ULONGLONG started = GetTickCount64();
    DWORD status = android_request('C', NULL, 0);
    if (service_log) {
        fprintf(service_log, "cancel status=%lu requestMs=%llu\n", status,
            (unsigned long long)(GetTickCount64() - started));
        fflush(service_log);
    }
    return status;
}
error_status_t __stdcall brailleMessage(const WCHAR *message) {
    (void)message;
    return ERROR_NOT_SUPPORTED;
}

int WINAPI WinMain(HINSTANCE instance, HINSTANCE previous, LPSTR command, int show) {
    WSADATA winsock_data;
    HANDLE desktop;
    MSG message;
    DWORD session = 0;
    WCHAR desktop_name[64] = L"Default";
    WCHAR endpoint[128];
    const WCHAR *desktops[] = { L"nogui", L"shell", desktop_name };
    unsigned int index;
    RPC_STATUS status;
    (void)instance; (void)previous; (void)command; (void)show;
    service_log = fopen("C:\\NVDA-Bridge\\nvda-rpc-service.log", "a");
    if (WSAStartup(MAKEWORD(2, 2), &winsock_data)) return 1;
#ifdef NATIVE_SAPI
    /* Initialize the process's GUI/audio driver on its main thread before
     * starting the COM worker. Wine dispatches audio-driver notifications
     * through this thread even when speech runs in a separate MTA. */
    {
        HWND desktop_window = GetDesktopWindow();
        UINT devices = waveOutGetNumDevs();
        if (service_log) {
            fprintf(service_log, "main audio desktop=%p devices=%u\n", desktop_window, devices);
            fflush(service_log);
        }
    }
    {
        DWORD ready = sapi_backend_ready();
        if (service_log) { fprintf(service_log, "SAPI ready=%lu hr=%08lx\n", ready,
            (unsigned long)sapi_initialization_hr); fflush(service_log); }
        if (ready != ERROR_SUCCESS) {
            FILE *error = fopen("C:\\NVDA-Bridge\\native-sapi-error", "w");
            if (error) { fprintf(error, "SAPI initialization failed: %08lx (%lu)\n",
                (unsigned long)sapi_initialization_hr, ready); fclose(error); }
            return 1;
        }
    }
#endif
    ProcessIdToSessionId(GetCurrentProcessId(), &session);
    desktop = GetThreadDesktop(GetCurrentThreadId());
    GetUserObjectInformationW(desktop, UOI_NAME, desktop_name,
                              sizeof(desktop_name), NULL);
    for (index = 0; index < 3; ++index) {
        swprintf(endpoint, 128, L"NvdaCtlr.%lu.%ls", session, desktops[index]);
        status = RpcServerUseProtseqEpW((RPC_WSTR)L"ncalrpc",
            RPC_C_PROTSEQ_MAX_REQS_DEFAULT, (RPC_WSTR)endpoint, NULL);
        if (service_log) {
            fwprintf(service_log, L"endpoint=%ls status=%lu\n", endpoint, status);
            fflush(service_log);
        }
        if (status != RPC_S_OK && status != RPC_S_DUPLICATE_ENDPOINT) return 3;
    }
    status = RpcServerRegisterIf(NvdaController_v1_0_s_ifspec, NULL, NULL);
    if (service_log) { fprintf(service_log, "register=%lu\n", status); fflush(service_log); }
    if (status != RPC_S_OK) return 4;
    /* RPC readiness does not depend on a detection window. The window thread
     * attaches only after Winlator has created the managed game desktop. */
    status = RpcServerListen(1, 20, TRUE);
    if (service_log) { fprintf(service_log, "listen=%lu\n", status); fflush(service_log); }
    if (status != RPC_S_OK) return 7;
#ifdef NATIVE_SAPI
    start_speech_control();
#endif
    for (index = 0; index < 100 && android_request('P', NULL, 0) != ERROR_SUCCESS; ++index)
        Sleep(100);
    if (service_log) { fprintf(service_log, "android ready=%lu\n",
        android_request('P', NULL, 0)); fflush(service_log); }
    /* Native SAPI detection is owned exclusively by nvda-launch on the
     * explorer-owned game desktop. A prelaunch helper must not initialize
     * windows on nogui/shell while explorer is still establishing ownership. */
#ifndef NATIVE_SAPI
    {
        const WCHAR *names[] = {L"nogui", L"shell"};
        for (index = 0; index < 2; ++index) {
            HANDLE thread = CreateThread(NULL, 0, nvda_window_thread, (void *)names[index], 0, NULL);
            if (!thread) return 8;
            CloseHandle(thread);
        }
    }
#endif
    {
        FILE *ready_file = fopen("C:\\NVDA-Bridge\\nvda-rpc-ready", "w");
        if (ready_file) { fprintf(ready_file, "%lu\n", GetCurrentProcessId()); fclose(ready_file); }
    }
    {
        HANDLE thread = CreateThread(NULL, 0, heartbeat_thread, NULL, 0, NULL);
        if (thread) CloseHandle(thread);
    }
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
#ifdef NATIVE_SAPI
    if (speech_control_socket != INVALID_SOCKET) closesocket(speech_control_socket);
#endif
    RpcServerUnregisterIf(NULL, NULL, FALSE);
    DeleteFileA("C:\\NVDA-Bridge\\nvda-rpc-ready");
    DeleteFileA("C:\\NVDA-Bridge\\nvda-rpc-heartbeat");
    return 0;
}

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdlib.h>
#include "bridge_protocol.h"

typedef DWORD (__stdcall *check_fn)(void);
typedef DWORD (__stdcall *speak_fn)(const WCHAR *);

static int read_full(HANDLE pipe, void *data, DWORD bytes) {
    BYTE *cursor = (BYTE *)data;
    DWORD got;
    while (bytes) {
        if (!ReadFile(pipe, cursor, bytes, &got, NULL) || !got) return 0;
        cursor += got;
        bytes -= got;
    }
    return 1;
}

int WINAPI WinMain(HINSTANCE instance, HINSTANCE previous, LPSTR command, int show) {
    HMODULE dll;
    HANDLE mutex;
    check_fn check, cancel;
    speak_fn speak;
    (void)instance; (void)previous; (void)command; (void)show;
    mutex = CreateMutexW(NULL, TRUE, L"WinlatorNvdaBridgeHost");
    if (!mutex || GetLastError() == ERROR_ALREADY_EXISTS) return 1;
    dll = LoadLibraryW(L"C:\\NVDA-Bridge\\x86\\nvdaControllerClient32.dll");
    if (!dll) return 2;
    check = (check_fn)GetProcAddress(dll, "nvdaController_testIfRunning");
    speak = (speak_fn)GetProcAddress(dll, "nvdaController_speakText");
    cancel = (check_fn)GetProcAddress(dll, "nvdaController_cancelSpeech");
    if (!check || !speak || !cancel || check()) return 3;
    for (;;) {
        HANDLE pipe = CreateNamedPipeW(BRIDGE_PIPE, PIPE_ACCESS_DUPLEX,
            PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT, 1,
            sizeof(DWORD), sizeof(BridgeRequest) + (BRIDGE_MAX_TEXT + 1) * 2,
            0, NULL);
        BridgeRequest request;
        DWORD result = ERROR_INVALID_PARAMETER, sent;
        WCHAR *text = NULL;
        if (pipe == INVALID_HANDLE_VALUE) return 4;
        if (ConnectNamedPipe(pipe, NULL) || GetLastError() == ERROR_PIPE_CONNECTED) {
            if (read_full(pipe, &request, sizeof(request))) {
                if (request.op == BRIDGE_PING && request.bytes == 0)
                    result = check();
                else if (request.op == BRIDGE_CANCEL && request.bytes == 0)
                    result = cancel();
                else if (request.op == BRIDGE_SPEAK && request.bytes >= 2 &&
                         request.bytes <= (BRIDGE_MAX_TEXT + 1) * 2 &&
                         request.bytes % 2 == 0) {
                    text = (WCHAR *)malloc(request.bytes);
                    if (text && read_full(pipe, text, request.bytes) &&
                        text[request.bytes / 2 - 1] == 0) result = speak(text);
                }
                WriteFile(pipe, &result, sizeof(result), &sent, NULL);
            }
        }
        free(text);
        FlushFileBuffers(pipe);
        DisconnectNamedPipe(pipe);
        CloseHandle(pipe);
    }
}

#define COBJMACROS
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <objbase.h>
#include <sapi.h>
#include <stdio.h>
#include <stdlib.h>
#include "bridge_protocol.h"

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
    HANDLE mutex;
    ISpVoice *voice = NULL;
    HRESULT hr;
    FILE *log;
    (void)instance; (void)previous; (void)command; (void)show;
    log = fopen("C:\\NVDA-Bridge\\x86\\bridge-host.log", "a");
    mutex = CreateMutexW(NULL, TRUE, L"WinlatorNvdaBridgeHost");
    if (!mutex || GetLastError() == ERROR_ALREADY_EXISTS) return 1;
    hr = CoInitializeEx(NULL, COINIT_APARTMENTTHREADED);
    if (log) { fprintf(log, "init=%08lx\n", (unsigned long)hr); fflush(log); }
    if (SUCCEEDED(hr)) hr = CoCreateInstance(&CLSID_SpVoice, NULL,
        CLSCTX_INPROC_SERVER, &IID_ISpVoice, (void **)&voice);
    if (log) { fprintf(log, "create=%08lx\n", (unsigned long)hr); fflush(log); }
    if (!voice) return 2;
    for (;;) {
        HANDLE pipe = CreateNamedPipeW(BRIDGE_PIPE, PIPE_ACCESS_DUPLEX,
            PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT, 1,
            sizeof(DWORD), sizeof(BridgeRequest) + (BRIDGE_MAX_TEXT + 1) * 2,
            0, NULL);
        BridgeRequest request;
        DWORD result = ERROR_INVALID_PARAMETER, sent;
        WCHAR *text = NULL;
        if (pipe == INVALID_HANDLE_VALUE) return 3;
        if (ConnectNamedPipe(pipe, NULL) || GetLastError() == ERROR_PIPE_CONNECTED) {
            if (read_full(pipe, &request, sizeof(request))) {
                if (log) { fprintf(log, "op=%lu bytes=%lu\n", request.op, request.bytes); fflush(log); }
                if (request.op == BRIDGE_PING && request.bytes == 0)
                    result = ERROR_SUCCESS;
                else if (request.op == BRIDGE_CANCEL && request.bytes == 0)
                    result = SUCCEEDED(ISpVoice_Speak(voice, NULL,
                        SPF_ASYNC | SPF_PURGEBEFORESPEAK, NULL)) ? 0 : ERROR_SERVICE_NOT_ACTIVE;
                else if (request.op == BRIDGE_SPEAK && request.bytes >= 2 &&
                         request.bytes <= (BRIDGE_MAX_TEXT + 1) * 2 &&
                         request.bytes % 2 == 0) {
                    text = (WCHAR *)malloc(request.bytes);
                    if (text && read_full(pipe, text, request.bytes) &&
                        text[request.bytes / 2 - 1] == 0) {
                        hr = ISpVoice_Speak(voice, text, SPF_DEFAULT, NULL);
                        result = SUCCEEDED(hr) ? 0 : ERROR_SERVICE_NOT_ACTIVE;
                        if (log) { fprintf(log, "speak_hr=%08lx length=%lu\n",
                            (unsigned long)hr, request.bytes / 2 - 1); fflush(log); }
                    }
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

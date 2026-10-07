#define COBJMACROS
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <objbase.h>
#include <sapi.h>
#include <stdlib.h>
#include <stdio.h>
#include <wchar.h>

/* One latest-wins speech slot keeps game navigation responsive. COM belongs
   entirely to the worker; exported calls never enter it from game threads. */
static CRITICAL_SECTION lock;
static HANDLE wake_event, ready_event, worker;
static WCHAR *pending;
static int cancel_pending;
static DWORD backend_status = ERROR_SERVICE_NOT_ACTIVE;
static INIT_ONCE init_once = INIT_ONCE_STATIC_INIT;

static DWORD WINAPI speech_worker(void *unused) {
    ISpVoice *voice = NULL;
    FILE *trace = fopen("C:\\NVDA-Bridge\\backend.log", "a");
    HRESULT hr = CoInitializeEx(NULL, COINIT_APARTMENTTHREADED);
    (void)unused;
    if (SUCCEEDED(hr)) {
        hr = CoCreateInstance(&CLSID_SpVoice, NULL, CLSCTX_INPROC_SERVER,
                              &IID_ISpVoice, (void **)&voice);
    }
    backend_status = SUCCEEDED(hr) && voice ? ERROR_SUCCESS : ERROR_SERVICE_NOT_ACTIVE;
    if (trace) { fprintf(trace, "init hr=%08lx\n", (unsigned long)hr); fflush(trace); }
    SetEvent(ready_event);
    if (backend_status != ERROR_SUCCESS) {
        if (SUCCEEDED(hr)) CoUninitialize();
        return 0;
    }
    for (;;) {
        WCHAR *text;
        int cancel;
        WaitForSingleObject(wake_event, INFINITE);
        EnterCriticalSection(&lock);
        text = pending;
        pending = NULL;
        cancel = cancel_pending;
        cancel_pending = 0;
        LeaveCriticalSection(&lock);
        if (cancel || text) {
            if (voice) ISpVoice_Release(voice);
            voice = NULL;
            hr = CoCreateInstance(&CLSID_SpVoice, NULL, CLSCTX_INPROC_SERVER,
                                  &IID_ISpVoice, (void **)&voice);
            if (trace) {
                fprintf(trace, "recreate hr=%08lx len=%lu cancel=%d\n",
                        (unsigned long)hr, text ? (unsigned long)wcslen(text) : 0, cancel);
                fflush(trace);
            }
            if (SUCCEEDED(hr) && voice && text && *text)
                hr = ISpVoice_Speak(voice, text, SPF_ASYNC, NULL);
            if (trace) { fprintf(trace, "speak hr=%08lx\n", (unsigned long)hr); fflush(trace); }
            if (FAILED(hr)) backend_status = ERROR_SERVICE_NOT_ACTIVE;
        }
        free(text);
    }
}

static BOOL CALLBACK start_worker(PINIT_ONCE once, PVOID parameter, PVOID *context) {
    HMODULE pinned;
    (void)once; (void)parameter; (void)context;
    InitializeCriticalSection(&lock);
    wake_event = CreateEventW(NULL, FALSE, FALSE, NULL);
    ready_event = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (!wake_event || !ready_event) return TRUE;
    GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                       GET_MODULE_HANDLE_EX_FLAG_PIN,
                       (LPCWSTR)&start_worker, &pinned);
    worker = CreateThread(NULL, 0, speech_worker, NULL, 0, NULL);
    return TRUE;
}

static DWORD ensure_backend(void) {
    InitOnceExecuteOnce(&init_once, start_worker, NULL, NULL);
    if (!worker) return ERROR_SERVICE_NOT_ACTIVE;
    if (WaitForSingleObject(ready_event, 5000) != WAIT_OBJECT_0)
        return ERROR_SERVICE_NOT_ACTIVE;
    return backend_status;
}

DWORD __stdcall nvdaController_testIfRunning(void) {
    return ensure_backend();
}

DWORD __stdcall nvdaController_speakText(const WCHAR *text) {
    WCHAR *copy;
    size_t len;
    DWORD result = ensure_backend();
    if (result) return result;
    if (!text) return ERROR_INVALID_PARAMETER;
    len = wcslen(text);
    if (len > 32767) return ERROR_INVALID_PARAMETER;
    copy = (WCHAR *)malloc((len + 1) * sizeof(WCHAR));
    if (!copy) return ERROR_OUTOFMEMORY;
    memcpy(copy, text, (len + 1) * sizeof(WCHAR));
    EnterCriticalSection(&lock);
    free(pending);
    pending = copy;
    cancel_pending = 0;
    LeaveCriticalSection(&lock);
    SetEvent(wake_event);
    return ERROR_SUCCESS;
}

DWORD __stdcall nvdaController_cancelSpeech(void) {
    DWORD result = ensure_backend();
    if (result) return result;
    EnterCriticalSection(&lock);
    free(pending);
    pending = NULL;
    cancel_pending = 1;
    LeaveCriticalSection(&lock);
    SetEvent(wake_event);
    return ERROR_SUCCESS;
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

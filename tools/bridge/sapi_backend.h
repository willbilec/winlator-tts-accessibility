#ifndef WINLATOR_SAPI_BACKEND_H
#define WINLATOR_SAPI_BACKEND_H
#define COBJMACROS
#include <windows.h>
#include <objbase.h>
#include <sapi.h>
#include <stdlib.h>
#include <wchar.h>
#include <stdio.h>
#include <mmsystem.h>

/* SAPI owns the audio stream and completion. Only this COM worker touches
 * the voice; RPC and game threads submit ordered commands and receive the
 * actual Speak result, rather than an acknowledgement from another player. */
typedef struct sapi_request {
    struct sapi_request *next;
    HANDLE done;
    LONG references;
    DWORD result;
    WCHAR *text;
} sapi_request;
static INIT_ONCE sapi_once = INIT_ONCE_STATIC_INIT;
static CRITICAL_SECTION sapi_lock;
static HANDLE sapi_wake, sapi_ready, sapi_thread;
static sapi_request *sapi_head, *sapi_tail;
static DWORD sapi_status = ERROR_SERVICE_NOT_ACTIVE;
static HRESULT sapi_initialization_hr = E_PENDING;

static DWORD sapi_error(HRESULT hr) {
    if (SUCCEEDED(hr)) return ERROR_SUCCESS;
    return HRESULT_FACILITY(hr) == FACILITY_WIN32 ? HRESULT_CODE(hr) : ERROR_GEN_FAILURE;
}
static void sapi_release_request(sapi_request *request) {
    if (!InterlockedDecrement(&request->references)) {
        CloseHandle(request->done);
        free(request->text);
        free(request);
    }
}
static DWORD WINAPI sapi_worker(void *unused) {
    ISpVoice *voice = NULL;
    ISpObjectToken *token = NULL;
    HRESULT hr;
    BOOL initialized;
    BOOL audio_pending = FALSE;
    BOOL voice_queue_empty = TRUE;
    FILE *trace = fopen("C:\\NVDA-Bridge\\sapi-backend.log", "a");
    (void)unused;
    /* Wine's audio/window driver must initialize before native SAPI/RHVoice.
     * Otherwise first playback can stall in driver initialization despite
     * Speak returning success. Warm the standard WinMM device path on the
     * COM worker; no NVDA detection window or desktop switch is involved. */
    {
        HWND desktop = GetDesktopWindow();
        UINT devices = waveOutGetNumDevs();
        if (trace) {
            fprintf(trace, "audio initialization desktop=%p devices=%u\n", desktop, devices);
            fflush(trace);
        }
#ifndef BRIDGE_TEST_RENDER
        if (!devices) {
            sapi_initialization_hr = HRESULT_FROM_WIN32(ERROR_DEVICE_NOT_CONNECTED);
            sapi_status = ERROR_DEVICE_NOT_CONNECTED;
            SetEvent(sapi_ready);
            if (trace) fclose(trace);
            return 0;
        }
#endif
    }
    hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    initialized = SUCCEEDED(hr);
    if (trace) { fprintf(trace, "COM bits=%u hr=%08lx\n", (unsigned int)(sizeof(void *) * 8), (unsigned long)hr); fflush(trace); }
    if (initialized)
        hr = CoCreateInstance(&CLSID_SpVoice, NULL, CLSCTX_INPROC_SERVER,
                              &IID_ISpVoice, (void **)&voice);
    if (trace) { fprintf(trace, "create hr=%08lx\n", (unsigned long)hr); fflush(trace); }
    if (SUCCEEDED(hr)) hr = ISpVoice_GetVoice(voice, &token);
    if (SUCCEEDED(hr)) {
        HKEY key;
        LONG rate = 0;
        DWORD size = sizeof(rate), type = 0;
        if (RegOpenKeyExW(HKEY_CURRENT_USER, L"Software\\Winlator\\Speech", 0,
                KEY_QUERY_VALUE, &key) == ERROR_SUCCESS) {
            if (RegQueryValueExW(key, L"NvdaRate", NULL, &type, (BYTE *)&rate, &size)
                    != ERROR_SUCCESS || type != REG_DWORD || size != sizeof(rate)) rate = 0;
            RegCloseKey(key);
        }
        if (rate < -10) rate = -10;
        if (rate > 10) rate = 10;
        hr = ISpVoice_SetRate(voice, rate);
        if (trace) { fprintf(trace, "NVDA rate=%ld hr=%08lx\n", rate, (unsigned long)hr); fflush(trace); }
    }
#ifndef BRIDGE_TEST_RENDER
    if (SUCCEEDED(hr)) {
        ISpStreamFormat *output = NULL;
        ISpAudio *audio = NULL;
        SPAUDIOBUFFERINFO info = {0};
        HRESULT buffer_hr = ISpVoice_GetOutputStream(voice, &output);
        if (SUCCEEDED(buffer_hr)) buffer_hr = ISpStreamFormat_QueryInterface(output,
            &IID_ISpAudio, (void **)&audio);
        if (SUCCEEDED(buffer_hr)) buffer_hr = ISpAudio_GetBufferInfo(audio, &info);
        if (trace) {
            fprintf(trace, "audio buffer before=%08lx notify=%lu size=%lu bias=%lu\n",
                (unsigned long)buffer_hr, info.ulMsMinNotification,
                info.ulMsBufferSize, info.ulMsEventBias); fflush(trace);
        }
        if (SUCCEEDED(buffer_hr)) {
            /* SAPI requires at least 200 ms and notification <= buffer/4.
             * Configure while closed; retain the standard output device. */
            info.ulMsMinNotification = 20;
            info.ulMsBufferSize = 200;
            info.ulMsEventBias = 0;
            buffer_hr = ISpAudio_SetBufferInfo(audio, &info);
            if (trace) {
                fprintf(trace, "audio buffer set=%08lx notify=20 size=200 bias=0\n",
                    (unsigned long)buffer_hr); fflush(trace);
            }
        }
        /* Unsupported tuning must not prevent the proven speech route. */
        if (audio) ISpAudio_Release(audio);
        if (output) ISpStreamFormat_Release(output);
    }
#else
    /* Isolated RPC regression builds render the real SAPI output to a file.
     * This macro is never enabled in the Android payload build. */
    if (SUCCEEDED(hr)) {
        ISpStream *stream = NULL;
        GUID format_id;
        WAVEFORMATEX format = {WAVE_FORMAT_PCM, 1, 24000, 48000, 2, 16, 0};
        hr = CLSIDFromString(L"{C31ADBAE-527F-4FF5-A230-F62BB61FF70C}", &format_id);
        if (SUCCEEDED(hr)) hr = CoCreateInstance(&CLSID_SpFileStream, NULL,
            CLSCTX_INPROC_SERVER, &IID_ISpStream, (void **)&stream);
        if (SUCCEEDED(hr)) hr = ISpStream_BindToFile(stream,
            L"C:\\NVDA-Bridge\\nvda-rpc-test.wav", SPFM_CREATE_ALWAYS, &format_id, &format, 0);
        if (SUCCEEDED(hr)) hr = ISpVoice_SetOutput(voice, (IUnknown *)stream, FALSE);
        if (stream) ISpStream_Release(stream);
    }
#endif
    if (trace) { fprintf(trace, "GetVoice hr=%08lx\n", (unsigned long)hr); fflush(trace); }
    if (token) ISpObjectToken_Release(token);
    sapi_status = sapi_error(hr);
    sapi_initialization_hr = hr;
    SetEvent(sapi_ready);
    if (FAILED(hr)) {
        if (trace) fclose(trace);
        if (voice) ISpVoice_Release(voice);
        if (initialized) CoUninitialize();
        return 0;
    }
    for (;;) {
        WaitForSingleObject(sapi_wake, audio_pending ? 100 : INFINITE);
        for (;;) {
            sapi_request *request;
            EnterCriticalSection(&sapi_lock);
            request = sapi_head;
            if (request) {
                sapi_head = request->next;
                if (!sapi_head) sapi_tail = NULL;
            }
            LeaveCriticalSection(&sapi_lock);
            if (!request) break;
            BOOL skip_cancel = !request->text && voice_queue_empty;
            ULONGLONG started = GetTickCount64();
            hr = skip_cancel ? S_OK : ISpVoice_Speak(voice, request->text ? request->text : L"",
                SPF_ASYNC | SPF_IS_NOT_XML | (request->text ? 0 : SPF_PURGEBEFORESPEAK), NULL);
            if (trace) {
                fprintf(trace, "Speak submit=%08lx cancel=%u skipped=%u submitMs=%llu\n", (unsigned long)hr,
                        request->text ? 0 : 1, skip_cancel, GetTickCount64() - started); fflush(trace);
            }
            if (SUCCEEDED(hr)) {
                voice_queue_empty = !request->text;
                if (!skip_cancel) audio_pending = TRUE;
            }
            request->result = sapi_error(hr);
            SetEvent(request->done);
            sapi_release_request(request);
        }
        if (audio_pending) {
            SPVOICESTATUS status = {0};
            hr = ISpVoice_GetStatus(voice, &status, NULL);
            if (FAILED(hr) || status.dwRunningState == SPRS_DONE) {
                if (trace) {
                    fprintf(trace, "Speech completion=%08lx\n",
                            (unsigned long)(FAILED(hr) ? hr : status.hrLastResult)); fflush(trace);
                }
                audio_pending = FALSE;
                /* Finished speech has nothing left to purge. Menu navigation
                 * often cancels twice even after the previous item ended. */
                if (SUCCEEDED(hr) && SUCCEEDED(status.hrLastResult)) voice_queue_empty = TRUE;
            }
        }
    }
}
static BOOL CALLBACK sapi_start(PINIT_ONCE once, PVOID arg, PVOID *context) {
    (void)once; (void)arg; (void)context;
    InitializeCriticalSection(&sapi_lock);
    sapi_wake = CreateEventW(NULL, FALSE, FALSE, NULL);
    sapi_ready = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (sapi_wake && sapi_ready)
        sapi_thread = CreateThread(NULL, 0, sapi_worker, NULL, 0, NULL);
    return TRUE;
}
static DWORD sapi_backend_ready(void) {
    InitOnceExecuteOnce(&sapi_once, sapi_start, NULL, NULL);
    if (!sapi_thread) return ERROR_SERVICE_NOT_ACTIVE;
    if (WaitForSingleObject(sapi_ready, 5000) != WAIT_OBJECT_0) return ERROR_TIMEOUT;
    return sapi_status;
}
static DWORD sapi_backend_submit(const WCHAR *text) {
    sapi_request *request;
    DWORD result = sapi_backend_ready();
    size_t length = text ? wcslen(text) : 0;
    if (result) return result;
    if (length > 32767) return ERROR_INVALID_PARAMETER;
    request = calloc(1, sizeof(*request));
    if (!request) return ERROR_NOT_ENOUGH_MEMORY;
    request->done = CreateEventW(NULL, TRUE, FALSE, NULL);
    request->references = 2; /* caller and worker; timeout cannot free a queued command */
    if (text) {
        request->text = malloc((length + 1) * sizeof(WCHAR));
        if (request->text) memcpy(request->text, text, (length + 1) * sizeof(WCHAR));
    }
    if (!request->done || (text && !request->text)) {
        if (request->done) CloseHandle(request->done);
        free(request->text); free(request);
        return ERROR_NOT_ENOUGH_MEMORY;
    }
    EnterCriticalSection(&sapi_lock);
    if (sapi_tail) sapi_tail->next = request;
    else sapi_head = request;
    sapi_tail = request;
    LeaveCriticalSection(&sapi_lock);
    SetEvent(sapi_wake);
    result = WaitForSingleObject(request->done, 5000) == WAIT_OBJECT_0
        ? request->result : ERROR_TIMEOUT;
    sapi_release_request(request);
    return result;
}
#endif

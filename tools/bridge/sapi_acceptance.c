#define COBJMACROS
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <objbase.h>
#include <sapi.h>
#include <stdio.h>

static int failed;
static void check(const char *label, HRESULT hr) {
    printf("%s=%08lx\n", label, (unsigned long)hr); fflush(stdout);
    if (FAILED(hr)) failed = 1;
}
int main(void) {
    ISpVoice *voice = NULL;
    ISpObjectToken *token = NULL;
    WCHAR *id = NULL;
    SPVOICESTATUS status;
    DWORD started;
    HRESULT hr;
    if (GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "wine_get_version")) {
#ifdef _WIN64
        if (!freopen("C:\\NVDA-Bridge\\sapi-acceptance64.log", "w", stdout)) return 1;
#else
        if (!freopen("C:\\NVDA-Bridge\\sapi-acceptance32.log", "w", stdout)) return 1;
#endif
    }
    hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    check("COM", hr);
    if (FAILED(hr)) return 1;
    hr = CoCreateInstance(&CLSID_SpVoice, NULL, CLSCTX_INPROC_SERVER,
                          &IID_ISpVoice, (void **)&voice);
    check("create", hr);
    if (FAILED(hr)) { CoUninitialize(); return 1; }
    hr = ISpVoice_GetVoice(voice, &token);
    check("voice", hr);
    if (token) {
        if (SUCCEEDED(ISpObjectToken_GetId(token, &id))) {
            printf("token=%ls\n", id); CoTaskMemFree(id);
        }
        ISpObjectToken_Release(token);
    }
    started = GetTickCount();
    check("first", ISpVoice_Speak(voice, L"First framework speech test.", SPF_IS_NOT_XML, NULL));
    printf("firstMilliseconds=%lu\n", GetTickCount() - started);
    started = GetTickCount();
    check("second", ISpVoice_Speak(voice, L"Second framework speech test.", SPF_IS_NOT_XML, NULL));
    printf("secondMilliseconds=%lu\n", GetTickCount() - started);
    check("longAsync", ISpVoice_Speak(voice,
        L"This long sentence must be interrupted before it reaches the end. "
        L"If the whole sentence continues, cancellation has failed.", SPF_ASYNC | SPF_IS_NOT_XML, NULL));
    Sleep(300);
    check("purge", ISpVoice_Speak(voice, L"", SPF_ASYNC | SPF_PURGEBEFORESPEAK, NULL));
    check("purgeCompletion", ISpVoice_WaitUntilDone(voice, 5000));
    ZeroMemory(&status, sizeof(status));
    check("status", ISpVoice_GetStatus(voice, &status, NULL));
    printf("runningState=%lu\n", status.dwRunningState);
    if (status.dwRunningState != SPRS_DONE) failed = 1;
    check("afterPurge", ISpVoice_Speak(voice,
        L"Speech after cancellation works. Angle bracket less than five is literal.", SPF_IS_NOT_XML, NULL));
    ISpVoice_Release(voice);
    CoUninitialize();
    return failed;
}

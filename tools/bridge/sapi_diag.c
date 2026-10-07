#define COBJMACROS
#include <windows.h>
#include <objbase.h>
#include <sapi.h>
#include <stdio.h>
#include <string.h>

int main(void) {
    char path[MAX_PATH];
    char *slash;
    FILE *log;
    ISpVoice *voice = NULL;
    ISpObjectToken *token = NULL;
    HRESULT hr;
    GetModuleFileNameA(NULL, path, sizeof(path));
    slash = strrchr(path, '\\');
    if (slash) strcpy(slash + 1, "sapi-diagnostic.txt");
    log = fopen(path, "w");
    if (!log) return 1;
    hr = CoInitializeEx(NULL, COINIT_APARTMENTTHREADED);
    fprintf(log, "CoInitializeEx=%08lx\n", (unsigned long)hr);
    if (SUCCEEDED(hr)) {
        hr = CoCreateInstance(&CLSID_SpVoice, NULL, CLSCTX_INPROC_SERVER,
                              &IID_ISpVoice, (void **)&voice);
        fprintf(log, "CoCreateInstance=%08lx\n", (unsigned long)hr);
        if (voice) {
            hr = ISpVoice_GetVoice(voice, &token);
            fprintf(log, "GetVoice=%08lx\n", (unsigned long)hr);
            if (token) ISpObjectToken_Release(token);
            hr = ISpVoice_Speak(voice, L"64 bit SAPI diagnostic", SPF_ASYNC, NULL);
            fprintf(log, "Speak=%08lx\n", (unsigned long)hr);
            Sleep(3000);
            ISpVoice_Release(voice);
        }
        CoUninitialize();
    }
    fclose(log);
    return 0;
}

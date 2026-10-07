#define COBJMACROS
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <objbase.h>
#include <sapi.h>
#include <stdio.h>

static void report(const char *step, HRESULT hr) {
    printf("%s=%08lx\n", step, (unsigned long)hr); fflush(stdout);
}
static void category(const WCHAR *path) {
    ISpObjectTokenCategory *cat = NULL;
    IEnumSpObjectTokens *tokens = NULL;
    WCHAR *id = NULL;
    ULONG count = 0;
    HRESULT hr = CoCreateInstance(&CLSID_SpObjectTokenCategory, NULL, CLSCTX_INPROC_SERVER,
        &IID_ISpObjectTokenCategory, (void **)&cat);
    printf("category=%ls\n", path);
    if (SUCCEEDED(hr)) hr = ISpObjectTokenCategory_SetId(cat, path, FALSE);
    report("category SetId", hr);
    if (SUCCEEDED(hr)) {
        hr = ISpObjectTokenCategory_GetDefaultTokenId(cat, &id);
        report("category default", hr);
        if (id) { printf("default=%ls\n", id); CoTaskMemFree(id); }
        hr = ISpObjectTokenCategory_EnumTokens(cat, NULL, NULL, &tokens);
        report("category enum", hr);
        if (tokens) {
            report("category count", IEnumSpObjectTokens_GetCount(tokens, &count));
            printf("count=%lu\n", count);
            IEnumSpObjectTokens_Release(tokens);
        }
        ISpObjectTokenCategory_Release(cat);
    }
}
int main(int argc, char **argv) {
    ISpVoice *voice = NULL;
    LONG rate = 0;
    ISpObjectToken *token = NULL, *current = NULL;
    IUnknown *engine = NULL;
    WCHAR module[MAX_PATH];
    HRESULT hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    (void)argv;
    report("COM", hr);
    if (FAILED(hr)) return 1;
    category(L"HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Speech\\Voices");
    category(L"HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Speech\\AudioOutput");
    hr = CoCreateInstance(&CLSID_SpVoice, NULL, CLSCTX_INPROC_SERVER, &IID_ISpVoice, (void **)&voice);
    report("voice create", hr);
    if (GetModuleFileNameW(GetModuleHandleW(L"sapi.dll"), module, MAX_PATH)) printf("sapi=%ls\n", module);
    if (voice) {
        report("GetRate", ISpVoice_GetRate(voice, &rate));
        printf("default rate=%ld\n", rate);
        report("voice default", ISpVoice_GetVoice(voice, &current));
        if (current) { ISpObjectToken_Release(current); current = NULL; }
    }
    if (voice && argc > 1) {
        ISpStream *stream = NULL;
        GUID wave_format;
        WAVEFORMATEX format = {WAVE_FORMAT_PCM, 1, 24000, 48000, 2, 16, 0};
        hr = CLSIDFromString(L"{C31ADBAE-527F-4FF5-A230-F62BB61FF70C}", &wave_format);
        if (SUCCEEDED(hr)) hr = CoCreateInstance(&CLSID_SpFileStream, NULL, CLSCTX_INPROC_SERVER,
            &IID_ISpStream, (void **)&stream);
        if (SUCCEEDED(hr)) hr = ISpStream_BindToFile(stream,
            L"C:\\NVDA-Bridge\\isolated-rhvoice.wav", SPFM_CREATE_ALWAYS,
            &wave_format, &format, 0);
        report("render file", hr);
        if (SUCCEEDED(hr)) hr = ISpVoice_SetOutput(voice, (IUnknown *)stream, FALSE);
        report("render output", hr);
        if (SUCCEEDED(hr)) hr = ISpVoice_Speak(voice,
            L"The Microsoft speech framework and Alan voice are working together.", SPF_IS_NOT_XML, NULL);
        report("render Speak", hr);
        if (stream) {
            report("render close", ISpStream_Close(stream));
            ISpStream_Release(stream);
        }
    }
    hr = CoCreateInstance(&CLSID_SpObjectToken, NULL, CLSCTX_INPROC_SERVER, &IID_ISpObjectToken, (void **)&token);
    if (SUCCEEDED(hr)) hr = ISpObjectToken_SetId(token, NULL,
        L"HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Speech\\Voices\\Tokens\\RHVoiceAlan", FALSE);
    report("token reopen", hr);
    if (SUCCEEDED(hr)) {
        report("engine create", ISpObjectToken_CreateInstance(token, NULL, CLSCTX_INPROC_SERVER,
            &IID_IUnknown, (void **)&engine));
        if (engine) IUnknown_Release(engine);
        if (voice) {
            report("explicit SetVoice", ISpVoice_SetVoice(voice, token));
            report("explicit GetVoice", ISpVoice_GetVoice(voice, &current));
            if (current) { ISpObjectToken_Release(current); current = NULL; }
            report("output token", ISpVoice_GetOutputObjectToken(voice, &current));
            if (current) ISpObjectToken_Release(current);
        }
    }
    if (token) ISpObjectToken_Release(token);
    if (voice) ISpVoice_Release(voice);
    CoUninitialize();
    return 0;
}

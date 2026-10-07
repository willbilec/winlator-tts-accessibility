/* Exercise the token-copy routine with a real host SAPI token. Only a
 * temporary HKCU test key is written; installed speech defaults are untouched. */
#define main configure_main
#include "configure.c"
#undef main
int main(void) {
    ISpVoice *voice = NULL;
    ISpObjectToken *token = NULL;
    ISpDataKey *data = NULL;
    WCHAR path[128], actual[1024], *expected = NULL;
    DWORD bytes = sizeof(actual), type;
    HKEY key = NULL;
    HRESULT hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    BOOL initialized = SUCCEEDED(hr);
    int result = 1;
    log_file = stdout;
    swprintf(path, 128, L"Software\\WinlatorNativeSapiTest-%lu", GetCurrentProcessId());
    if (SUCCEEDED(hr)) hr = CoCreateInstance(&CLSID_SpVoice, NULL, CLSCTX_INPROC_SERVER,
        &IID_ISpVoice, (void **)&voice);
    if (SUCCEEDED(hr)) hr = ISpVoice_GetVoice(voice, &token);
    if (SUCCEEDED(hr)) hr = ISpObjectToken_QueryInterface(token, &IID_ISpDataKey, (void **)&data);
    if (SUCCEEDED(hr)) hr = ISpDataKey_GetStringValue(data, NULL, &expected);
    if (SUCCEEDED(hr)) hr = HRESULT_FROM_WIN32(RegCreateKeyExW(HKEY_CURRENT_USER, path,
        0, NULL, 0, KEY_ALL_ACCESS, NULL, &key, NULL));
    if (SUCCEEDED(hr)) hr = persist_token_data(data, key);
    if (SUCCEEDED(hr)) hr = HRESULT_FROM_WIN32(RegQueryValueExW(key, NULL, NULL, &type,
        (BYTE *)actual, &bytes));
    if (SUCCEEDED(hr) && type == REG_SZ && expected && !wcscmp(expected, actual)) {
        HKEY attributes;
        hr = HRESULT_FROM_WIN32(RegOpenKeyExW(key, L"Attributes", 0, KEY_READ, &attributes));
        if (SUCCEEDED(hr)) {
            bytes = sizeof(actual);
            hr = HRESULT_FROM_WIN32(RegQueryValueExW(attributes, L"Name", NULL, &type,
                (BYTE *)actual, &bytes));
            RegCloseKey(attributes);
            if (SUCCEEDED(hr) && type == REG_SZ && actual[0]) result = 0;
        }
    }
    if (key) RegCloseKey(key);
    RegDeleteTreeW(HKEY_CURRENT_USER, path);
    CoTaskMemFree(expected);
    if (data) ISpDataKey_Release(data);
    if (token) ISpObjectToken_Release(token);
    if (voice) ISpVoice_Release(voice);
    if (initialized) CoUninitialize();
    printf("Token copy host test: %s hr=%08lx\n", result ? "FAIL" : "PASS", (unsigned long)hr);
    return result;
}

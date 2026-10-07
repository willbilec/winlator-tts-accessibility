#define COBJMACROS
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <objbase.h>
#include <sapi.h>
#include <sperror.h>
#include <stdio.h>
#include <wchar.h>

/* The MinGW SAPI header omits this documented enumeration HRESULT. */
#define SAPI_ENUM_END ((HRESULT)0x80045039)

static WCHAR base[MAX_PATH], backup[MAX_PATH];
static FILE *log_file;
static int bootstrap_helper(void) {
    WCHAR command[] = L"C:\\NVDA-Bridge\\nvda_sapi_rpc_service.exe";
    STARTUPINFOW startup = {0};
    PROCESS_INFORMATION process = {0};
    DWORD status = 1;
    WCHAR source[MAX_PATH];
    startup.cb = sizeof(startup);
    DeleteFileW(L"C:\\NVDA-Bridge\\native-sapi-error");
    swprintf(source, MAX_PATH, L"%ls\\nvda_sapi_rpc_service32.exe", base);
    if (!CopyFileW(source, L"C:\\NVDA-Bridge\\nvda_sapi_rpc_service.exe.new", FALSE) ||
        !MoveFileExW(L"C:\\NVDA-Bridge\\nvda_sapi_rpc_service.exe.new",
                     L"C:\\NVDA-Bridge\\nvda_sapi_rpc_service.exe", MOVEFILE_REPLACE_EXISTING)) return 1;
    if (!CreateProcessW(NULL, command, NULL, NULL, FALSE, CREATE_NO_WINDOW,
                        NULL, NULL, &startup, &process)) return 1;
    CloseHandle(process.hThread);
    /* Keep the launcher alive so Android can stop the complete helper tree. */
    WaitForSingleObject(process.hProcess, INFINITE);
    GetExitCodeProcess(process.hProcess, &status);
    CloseHandle(process.hProcess);
    return (int)status;
}
static void setup_error(void) {
    FILE *error;
    CreateDirectoryW(L"C:\\NVDA-Bridge", NULL);
    if (GetFileAttributesW(L"C:\\NVDA-Bridge\\native-sapi-error") != INVALID_FILE_ATTRIBUTES) return;
    error = fopen("C:\\NVDA-Bridge\\native-sapi-error", "w");
    if (error) { fputs("Speech setup failed.\n", error); fclose(error); }
}
static int run(const WCHAR *args) {
    WCHAR command[2048];
    STARTUPINFOW si = {0};
    PROCESS_INFORMATION pi = {0};
    DWORD result;
    si.cb = sizeof(si);
    swprintf(command, 2048, L"%ls", args);
    if (!CreateProcessW(NULL, command, NULL, NULL, FALSE, CREATE_NO_WINDOW,
                        NULL, base, &si, &pi)) return 1;
    CloseHandle(pi.hThread);
    if (WaitForSingleObject(pi.hProcess, 120000) != WAIT_OBJECT_0) {
        TerminateProcess(pi.hProcess, ERROR_TIMEOUT);
        CloseHandle(pi.hProcess); return 1;
    }
    GetExitCodeProcess(pi.hProcess, &result);
    CloseHandle(pi.hProcess);
    fprintf(log_file, "command=%ls result=%lu\n", args, result); fflush(log_file);
    return result != 0 && result != 3010;
}
static int registry_snapshot(BOOL restore) {
    const WCHAR *keys[] = {
        L"HKLM\\Software\\Microsoft\\Speech",
        L"HKLM\\Software\\Wow6432Node\\Microsoft\\Speech",
        L"HKCU\\Software\\Microsoft\\Speech",
        L"HKCU\\Software\\Wow6432Node\\Microsoft\\Speech"
    };
    WCHAR cmd[2048], file[MAX_PATH];
    unsigned int i;
    for (i = 0; i < sizeof(keys)/sizeof(keys[0]); ++i) {
        swprintf(file, MAX_PATH, L"%ls\\speech-%u.reg", backup, i);
        if (restore) {
            if (GetFileAttributesW(file) != INVALID_FILE_ATTRIBUTES) {
                swprintf(cmd, 2048, L"reg.exe import \"%ls\"", file);
                if (run(cmd)) return 1;
            }
        } else {
            swprintf(cmd, 2048, L"reg.exe export \"%ls\" \"%ls\" /y", keys[i], file);
            /* Absent per-user speech keys are expected. */
            if (run(cmd) && i < 2) return 1;
        }
    }
    return 0;
}
static int runtime_files(BOOL restore) {
    const WCHAR *dirs[] = {L"system32", L"syswow64"};
    const WCHAR *arch[] = {L"x64", L"x86"};
    WCHAR target[MAX_PATH], saved[MAX_PATH], source[MAX_PATH], absent[MAX_PATH], cmd[2048];
    unsigned int i, j;
    /* Snapshot every file before the first replacement, so a partial failure
     * always has a complete rollback set. */
    if (!restore) for (i = 0; i < 2; ++i) for (j = 0; j < 2; ++j) {
        swprintf(target, MAX_PATH, L"C:\\windows\\%ls%ls\\sapi.dll", dirs[i],
                 j ? L"\\Speech\\Common" : L"");
        swprintf(saved, MAX_PATH, L"%ls\\sapi-%u-%u.dll", backup, i, j);
        swprintf(absent, MAX_PATH, L"%ls\\sapi-%u-%u.absent", backup, i, j);
        if (GetFileAttributesW(saved) != INVALID_FILE_ATTRIBUTES ||
            GetFileAttributesW(absent) != INVALID_FILE_ATTRIBUTES) continue;
        if (GetFileAttributesW(target) == INVALID_FILE_ATTRIBUTES) {
            HANDLE file;
            swprintf(absent, MAX_PATH, L"%ls\\sapi-%u-%u.absent", backup, i, j);
            file = CreateFileW(absent, GENERIC_WRITE, 0, NULL, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL);
            if (file == INVALID_HANDLE_VALUE) return 1;
            CloseHandle(file);
        } else if (!CopyFileW(target, saved, TRUE)) {
            fprintf(log_file, "backup=%ls error=%lu\n", target, GetLastError()); fflush(log_file);
            return 1;
        }
    }
    for (i = 0; i < 2; ++i) {
        for (j = 0; j < 2; ++j) {
            swprintf(target, MAX_PATH, L"C:\\windows\\%ls%ls\\sapi.dll", dirs[i],
                     j ? L"\\Speech\\Common" : L"");
            swprintf(saved, MAX_PATH, L"%ls\\sapi-%u-%u.dll", backup, i, j);
            if (!restore) {
                swprintf(source, MAX_PATH, L"%ls\\%ls\\sapi.dll", base, arch[i]);
            } else {
                swprintf(absent, MAX_PATH, L"%ls\\sapi-%u-%u.absent", backup, i, j);
                if (GetFileAttributesW(absent) != INVALID_FILE_ATTRIBUTES) {
                    if (!DeleteFileW(target) && GetLastError() != ERROR_FILE_NOT_FOUND) return 1;
                    continue;
                }
                if (GetFileAttributesW(saved) == INVALID_FILE_ATTRIBUTES) continue;
                swprintf(source, MAX_PATH, L"%ls", saved);
            }
            if (!CopyFileW(source, target, FALSE)) {
                fprintf(log_file, "copy=%ls to=%ls error=%lu\n", source, target, GetLastError()); fflush(log_file);
                return 1;
            }
        }
        swprintf(cmd, 2048, L"C:\\windows\\%ls\\regsvr32.exe /s C:\\windows\\%ls%ls\\sapi.dll",
            dirs[i], dirs[i], restore ? L"\\Speech\\Common" : L"");
        if (run(cmd)) return 1;
    }
    return 0;
}
static int override(BOOL restore) {
    HKEY key;
    WCHAR value[256], ini[MAX_PATH];
    DWORD bytes = sizeof(value), type;
    LONG status;
    swprintf(ini, MAX_PATH, L"%ls\\override.ini", backup);
    if (RegCreateKeyExW(HKEY_CURRENT_USER, L"Software\\Wine\\DllOverrides", 0, NULL,
        0, KEY_QUERY_VALUE | KEY_SET_VALUE, NULL, &key, NULL)) return 1;
    if (!restore) {
        status = RegQueryValueExW(key, L"sapi", NULL, &type, (BYTE *)value, &bytes);
        if (status != ERROR_FILE_NOT_FOUND && (status || type != REG_SZ)) { RegCloseKey(key); return 1; }
        if (!WritePrivateProfileStringW(L"sapi", L"present", status == ERROR_FILE_NOT_FOUND ? L"0" : L"1", ini) ||
            !WritePrivateProfileStringW(L"sapi", L"value", status == ERROR_FILE_NOT_FOUND ? L"" : value, ini)) {
            RegCloseKey(key); return 1;
        }
        wcscpy(value, L"native");
        status = RegSetValueExW(key, L"sapi", 0, REG_SZ, (BYTE *)value,
                               ((DWORD)wcslen(value) + 1) * sizeof(WCHAR));
    } else if (GetPrivateProfileIntW(L"sapi", L"present", 0, ini)) {
        GetPrivateProfileStringW(L"sapi", L"value", L"", value, 256, ini);
        status = RegSetValueExW(key, L"sapi", 0, REG_SZ, (BYTE *)value,
                               ((DWORD)wcslen(value) + 1) * sizeof(WCHAR));
    } else {
        status = RegDeleteValueW(key, L"sapi");
        if (status == ERROR_FILE_NOT_FOUND) status = 0;
    }
    RegCloseKey(key);
    return status != 0;
}
/* RHVoice enumerates in-memory tokens. Persist the provider's own data so
 * native SAPI can reopen the default by ID in a different process. */
static HRESULT persist_token_data(ISpDataKey *data, HKEY key) {
    HRESULT hr;
    WCHAR *name = NULL, *value = NULL;
    ULONG index;
    /* RHVoice 1.6.0's EnumValues returns values rather than names. Read its
     * published voice_token schema directly through GetStringValue. */
    const WCHAR *fields[] = {NULL, L"CLSID", L"Age", L"Vendor", L"Language", L"Gender", L"Name"};
    for (index = 0; index < sizeof(fields)/sizeof(fields[0]); ++index) {
        hr = ISpDataKey_GetStringValue(data, fields[index], &value);
        fprintf(log_file, "token field=%ls hr=%08lx value=%ls\n",
                fields[index] ? fields[index] : L"(default)", (unsigned long)hr,
                value ? value : L"(absent)"); fflush(log_file);
        if (hr == (HRESULT)0x8004503a) continue;
        if (SUCCEEDED(hr)) hr = HRESULT_FROM_WIN32(RegSetValueExW(key, fields[index], 0,
            REG_SZ, (BYTE *)value, ((DWORD)wcslen(value) + 1) * sizeof(WCHAR)));
        CoTaskMemFree(value); value = NULL;
        if (FAILED(hr)) return hr;
    }
    for (index = 0; ; ++index) {
        ISpDataKey *child = NULL;
        HKEY child_key;
        hr = ISpDataKey_EnumKeys(data, index, &name);
        if (hr == SAPI_ENUM_END) return S_OK;
        if (FAILED(hr)) return hr;
        hr = ISpDataKey_OpenKey(data, name, &child);
        if (SUCCEEDED(hr)) {
            hr = HRESULT_FROM_WIN32(RegCreateKeyExW(key, name, 0, NULL, 0,
                KEY_WRITE, NULL, &child_key, NULL));
            if (SUCCEEDED(hr)) {
                hr = persist_token_data(child, child_key);
                RegCloseKey(child_key);
            }
            ISpDataKey_Release(child);
        }
        CoTaskMemFree(name); name = NULL;
        if (FAILED(hr)) return hr;
    }
}
static HRESULT persist_token(ISpObjectToken *token, const WCHAR *id) {
    const WCHAR prefix[] = L"HKEY_LOCAL_MACHINE\\";
    HKEY key;
    ISpDataKey *data = NULL;
    HRESULT hr;
    if (wcsncmp(id, prefix, wcslen(prefix))) return E_INVALIDARG;
    hr = ISpObjectToken_QueryInterface(token, &IID_ISpDataKey, (void **)&data);
    if (FAILED(hr)) return hr;
    hr = HRESULT_FROM_WIN32(RegCreateKeyExW(HKEY_LOCAL_MACHINE,
        id + wcslen(prefix), 0, NULL, 0, KEY_WRITE, NULL, &key, NULL));
    if (SUCCEEDED(hr)) {
        hr = persist_token_data(data, key);
        if (SUCCEEDED(hr)) hr = HRESULT_FROM_WIN32(RegFlushKey(key));
        RegCloseKey(key);
    }
    ISpDataKey_Release(data);
    return hr;
}
static int apply_speech_rates(void) {
    const WCHAR *path = L"C:\\WinlatorNativeSapi\\speech-settings.ini";
    const WCHAR *keys[] = {L"Software\\Microsoft\\Speech\\Voices", L"Software\\Winlator\\Speech"};
    const WCHAR *names[] = {L"DefaultTTSRate", L"NvdaRate"};
    const WCHAR *settings[] = {L"regularRate", L"nvdaRate"};
    for (unsigned int i = 0; i < 2; ++i) {
        LONG rate = (LONG)GetPrivateProfileIntW(L"speech", settings[i], 0, path);
        HKEY key;
        LONG status;
        if (rate < -10) rate = -10;
        if (rate > 10) rate = 10;
        status = RegCreateKeyExW(HKEY_CURRENT_USER, keys[i], 0, NULL, 0,
            KEY_SET_VALUE, NULL, &key, NULL);
        if (status == ERROR_SUCCESS) {
            status = RegSetValueExW(key, names[i], 0, REG_DWORD, (BYTE *)&rate, sizeof(rate));
            RegCloseKey(key);
        }
        fprintf(log_file, "speech rate %ls=%ld status=%ld bits=%u\n", names[i], rate,
            status, (unsigned int)(sizeof(void *) * 8)); fflush(log_file);
        if (status != ERROR_SUCCESS) return 1;
    }
    return 0;
}

static int select_voice(void) {
    ISpObjectTokenCategory *category = NULL;
    IEnumSpObjectTokens *tokens = NULL;
    ISpObjectToken *token = NULL;
    WCHAR *id = NULL;
    WCHAR registration[2048];
    HRESULT hr;
    int result = 1;
    const WCHAR *obsolete[] = {
        L"Software\\Microsoft\\Speech\\Voices\\Tokens\\WinlatorEspeakEnglish",
        L"Software\\Microsoft\\Speech\\Voices\\Tokens\\Wine Default Voice"
    };
    /* DllRegisterServer registers COM classes, but the SP1 component manifest
     * supplies the phone maps required by native SAPI voice initialization. */
    swprintf(registration, 2048, L"reg.exe import \"%ls\\phone-converters.reg\"", base);
    if (run(registration)) return 1;
    if (apply_speech_rates()) return 1;
    hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    if (FAILED(hr)) return 1;
    for (unsigned int i = 0; i < sizeof(obsolete)/sizeof(obsolete[0]); ++i) {
        LONG status = RegDeleteTreeW(HKEY_LOCAL_MACHINE, obsolete[i]);
        if (status != ERROR_SUCCESS && status != ERROR_FILE_NOT_FOUND) {
            fprintf(log_file, "remove obsolete token error=%ld\n", status); fflush(log_file);
            CoUninitialize(); return 1;
        }
    }
    hr = CoCreateInstance(&CLSID_SpObjectTokenCategory, NULL, CLSCTX_INPROC_SERVER,
        &IID_ISpObjectTokenCategory, (void **)&category);
    if (SUCCEEDED(hr)) hr = ISpObjectTokenCategory_SetId(category,
        L"HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Speech\\Voices", FALSE);
    if (SUCCEEDED(hr)) hr = ISpObjectTokenCategory_EnumTokens(category, NULL, NULL, &tokens);
    while (SUCCEEDED(hr) && tokens && IEnumSpObjectTokens_Next(tokens, 1, &token, NULL) == S_OK) {
        if (SUCCEEDED(ISpObjectToken_GetId(token, &id))) {
            fprintf(log_file, "token=%ls\n", id); fflush(log_file);
            if (wcsstr(id, L"RHVoice") || wcsstr(id, L"rhvoice")) {
                HKEY key;
                const WCHAR stable_id[] = L"HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Speech\\Voices\\Tokens\\RHVoiceAlan";
                const WCHAR *roots[] = {L"Software\\Microsoft\\Speech\\Voices",
                    L"Software\\Wow6432Node\\Microsoft\\Speech\\Voices"};
                /* SAPI's per-user default takes precedence over the machine
                 * default; migrate it in each architecture's actual view. */
                hr = persist_token(token, stable_id);
                fprintf(log_file, "persist token=%08lx bits=%u\n", (unsigned long)hr,
                        (unsigned int)(sizeof(void *) * 8)); fflush(log_file);
                if (SUCCEEDED(hr)) hr = ISpObjectTokenCategory_SetDefaultTokenId(category, stable_id);
                result = FAILED(hr);
                fprintf(log_file, "select default=%08lx bits=%u\n", (unsigned long)hr,
                        (unsigned int)(sizeof(void *) * 8)); fflush(log_file);
                for (unsigned int i = 0; i < 2; ++i) {
                    if (RegCreateKeyExW(HKEY_LOCAL_MACHINE, roots[i], 0, NULL, 0,
                        KEY_SET_VALUE, NULL, &key, NULL)) { result = 1; break; }
                    if (RegSetValueExW(key, L"DefaultDefaultTokenId", 0, REG_SZ,
                        (BYTE *)stable_id, sizeof(stable_id))) result = 1;
                    RegFlushKey(key);
                    RegCloseKey(key);
                }
                CoTaskMemFree(id); id = NULL;
                ISpObjectToken_Release(token); token = NULL;
                break;
            }
            CoTaskMemFree(id); id = NULL;
        }
        ISpObjectToken_Release(token); token = NULL;
    }
    if (tokens) IEnumSpObjectTokens_Release(tokens);
    if (category) ISpObjectTokenCategory_Release(category);
    if (!result) {
        ISpVoice *voice = NULL;
        ISpObjectToken *reopened = NULL;
        ISpObjectToken *direct = NULL;
        hr = CoCreateInstance(&CLSID_SpObjectToken, NULL, CLSCTX_INPROC_SERVER,
            &IID_ISpObjectToken, (void **)&direct);
        if (SUCCEEDED(hr)) hr = ISpObjectToken_SetId(direct, NULL,
            L"HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Speech\\Voices\\Tokens\\RHVoiceAlan", FALSE);
        fprintf(log_file, "direct token reopen=%08lx\n", (unsigned long)hr); fflush(log_file);
        if (direct) ISpObjectToken_Release(direct);
        hr = CoCreateInstance(&CLSID_SpVoice, NULL, CLSCTX_INPROC_SERVER,
            &IID_ISpVoice, (void **)&voice);
        if (SUCCEEDED(hr)) hr = ISpVoice_GetVoice(voice, &reopened);
        fprintf(log_file, "reopen default=%08lx bits=%u\n", (unsigned long)hr,
                (unsigned int)(sizeof(void *) * 8)); fflush(log_file);
        if (reopened) ISpObjectToken_Release(reopened);
        if (voice) ISpVoice_Release(voice);
        result = FAILED(hr);
    }
    if (result) {
        FILE *error;
        CreateDirectoryW(L"C:\\NVDA-Bridge", NULL);
        error = fopen("C:\\NVDA-Bridge\\native-sapi-error", "w");
        if (error) {
            fprintf(error, "Speech voice initialization failed: %08lx (%u-bit).\n",
                    (unsigned long)hr, (unsigned int)(sizeof(void *) * 8));
            fclose(error);
        }
    }
    CoUninitialize();
    return result;
}
int main(int argc, char **argv) {
    WCHAR cmd[2048], file[MAX_PATH];
    HANDLE marker;
    BOOL restore = argc == 2 && !strcmp(argv[1], "--rollback");
    BOOL bootstrap = argc == 2 && !strcmp(argv[1], "--bootstrap");
#ifndef _WIN64
    if (argc != 2 || strcmp(argv[1], "--select-voice")) return 1;
#endif
    if (!GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "wine_get_version")) {
        fprintf(stderr, "This installer is for Wine containers only.\n"); return 1;
    }
    GetModuleFileNameW(NULL, base, MAX_PATH);
    *wcsrchr(base, L'\\') = 0;
    swprintf(backup, MAX_PATH, L"%ls\\backup", base);
    swprintf(file, MAX_PATH, L"%ls\\configure.log", base);
    log_file = _wfopen(file, L"a");
    if (!log_file) return 1;
    if (argc == 2 && !strcmp(argv[1], "--select-voice")) return select_voice();
    if (restore) {
        if (GetFileAttributesW(backup) == INVALID_FILE_ATTRIBUTES) return 1;
        if (override(TRUE) || runtime_files(TRUE) || registry_snapshot(TRUE)) return 1;
        DeleteFileW(L"C:\\NVDA-Bridge\\native-sapi.enabled");
        return 0;
    }
    if (bootstrap && GetFileAttributesW(L"C:\\NVDA-Bridge\\native-sapi.enabled") != INVALID_FILE_ATTRIBUTES) {
        swprintf(cmd, 2048, L"\"%ls\\configure-native-sapi.exe\" --select-voice", base);
        if (run(cmd)) { setup_error(); return 1; }
        swprintf(cmd, 2048, L"\"%ls\\configure-native-sapi32.exe\" --select-voice", base);
        if (run(cmd)) { setup_error(); return 1; }
        fclose(log_file);
        return bootstrap_helper();
    }
    if (GetFileAttributesW(backup) != INVALID_FILE_ATTRIBUTES) {
        if (!bootstrap) {
            fprintf(log_file, "Backup already exists; refusing to overwrite it.\n"); setup_error(); return 1;
        }
        /* A stopped/failed first startup may have installed only some pieces.
         * Keep the original snapshots and finish the same idempotent install. */
        fprintf(log_file, "Resuming container speech provisioning with existing backup.\n"); fflush(log_file);
        swprintf(file, MAX_PATH, L"%ls\\override.ini", backup);
        if (GetFileAttributesW(file) == INVALID_FILE_ATTRIBUTES) {
            if (registry_snapshot(FALSE) || override(FALSE)) { setup_error(); return 1; }
        } else {
            HKEY key;
            const WCHAR native[] = L"native";
            if (RegCreateKeyExW(HKEY_CURRENT_USER, L"Software\\Wine\\DllOverrides", 0, NULL,
                0, KEY_SET_VALUE, NULL, &key, NULL)) { setup_error(); return 1; }
            LONG status = RegSetValueExW(key, L"sapi", 0, REG_SZ, (const BYTE *)native, sizeof(native));
            RegCloseKey(key);
            if (status) { setup_error(); return 1; }
        }
    } else {
        if (!CreateDirectoryW(backup, NULL) || registry_snapshot(FALSE) || override(FALSE)) { setup_error(); return 1; }
    }
    if (runtime_files(FALSE)) goto rollback;
    {
        const WCHAR *packages[] = {L"RHVoice-x86-v1.6.0-setup.msi", L"RHVoice-x64-v1.6.0-setup.msi",
            L"RHVoice-language-English-v2.8.2-setup.msi", L"RHVoice-voice-English-Alan-v4.0.2-setup.msi"};
        for (unsigned int i = 0; i < 4; ++i) {
            swprintf(cmd, 2048, L"msiexec.exe /i \"%ls\\packages\\%ls\" /qn /norestart", base, packages[i]);
            if (run(cmd)) goto rollback;
        }
    }
    /* Select in a child process: it must unload native SAPI before rollback
     * can restore the DLLs on failure. */
    swprintf(cmd, 2048, L"\"%ls\\configure-native-sapi.exe\" --select-voice", base);
    if (run(cmd)) goto rollback;
    swprintf(cmd, 2048, L"\"%ls\\configure-native-sapi32.exe\" --select-voice", base);
    if (run(cmd)) goto rollback;
    CreateDirectoryW(L"C:\\NVDA-Bridge", NULL);
    swprintf(file, MAX_PATH, L"%ls\\nvda_sapi_rpc_service32.exe", base);
    if (!CopyFileW(file, L"C:\\NVDA-Bridge\\nvda_sapi_rpc_service.exe", FALSE)) goto rollback;
    marker = CreateFileW(L"C:\\NVDA-Bridge\\native-sapi.enabled", GENERIC_WRITE, 0, NULL,
                        CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL);
    if (marker == INVALID_HANDLE_VALUE) goto rollback;
    CloseHandle(marker);
    fprintf(log_file, "Speech provisioned for this container.\n");
    fclose(log_file);
    return bootstrap ? bootstrap_helper() : 0;
rollback:
    fprintf(log_file, "Activation failed; restoring previous speech configuration.\n"); fflush(log_file);
    override(TRUE); runtime_files(TRUE); registry_snapshot(TRUE);
    setup_error();
    return 1;
}

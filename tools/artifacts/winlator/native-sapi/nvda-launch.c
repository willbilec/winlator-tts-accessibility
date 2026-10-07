#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
#include <wchar.h>
#include <stdlib.h>

/* Wine forwards WINEDLLOVERRIDES from its Unix environment, ignoring changes
 * made with SetEnvironmentVariable. Use its application-specific registry
 * setting so only Super Liam uses built-in SAPI; the RPC worker stays native. */
static LONG configure_game_sapi(void) {
    HKEY key;
    const WCHAR builtin[] = L"builtin";
    LONG status = RegCreateKeyExW(HKEY_CURRENT_USER,
        L"Software\\Wine\\AppDefaults\\Super Liam.exe\\DllOverrides",
        0, NULL, 0, KEY_SET_VALUE, NULL, &key, NULL);
    if (status != ERROR_SUCCESS) return status;
    status = RegSetValueExW(key, L"sapi", 0, REG_SZ,
        (const BYTE *)builtin, sizeof(builtin));
    RegCloseKey(key);
    return status;
}

static LRESULT CALLBACK detection_window_proc(HWND window, UINT message, WPARAM word, LPARAM data) {
    return DefWindowProcW(window, message, word, data);
}

static BOOL CALLBACK trace_window(HWND window, LPARAM context) {
    FILE *log = (FILE *)context;
    WCHAR title[256] = {0}, class_name[128] = {0};
    DWORD pid;
    DWORD thread = GetWindowThreadProcessId(window, &pid);
    GUITHREADINFO info = {0};
    RECT rect = {0};
    info.cbSize = sizeof(info);
    GetWindowTextW(window, title, 256);
    GetClassNameW(window, class_name, 128);
    GetWindowRect(window, &rect);
    GetGUIThreadInfo(thread, &info);
    fprintf(log, "window=%p pid=%lu visible=%u enabled=%u rect=%ld,%ld,%ld,%ld class=%ls title=%ls active=%p focus=%p\n",
        window, pid, IsWindowVisible(window), IsWindowEnabled(window), rect.left, rect.top,
        rect.right, rect.bottom, class_name, title, info.hwndActive, info.hwndFocus);
    return TRUE;
}

/* Explorer owns the virtual desktop before this child runs. Advertise NVDA
 * here, without switching the WOW64 speech worker to another desktop. Never
 * create a Wine desktop or make game startup depend on the window succeeding. */
int WINAPI WinMain(HINSTANCE instance, HINSTANCE previous, LPSTR args, int show) {
    WCHAR *command = GetCommandLineW(), *copy;
    STARTUPINFOW startup = {0};
    PROCESS_INFORMATION child = {0};
    DWORD status;
    HWND detection = NULL;
    WNDCLASSW window_class = {0};
    HWND last_foreground = (HWND)(INT_PTR)-1;
    DWORD trace_started, next_snapshot;
    BOOL desktop_speech = FALSE;
    BOOL builtin_game_sapi = FALSE;
    FILE *log = fopen("C:\\NVDA-Bridge\\native-launch.log", "w");
    (void)instance; (void)previous; (void)args; (void)show;
    if (!log) return 1;
    if (*command == L'"') {
        ++command;
        while (*command && *command != L'"') ++command;
        if (*command) ++command;
    } else while (*command && *command != L' ' && *command != L'\t') ++command;
    while (*command == L' ' || *command == L'\t') ++command;
    if (!wcsncmp(command, L"--desktop-speech ", 17)) {
        desktop_speech = TRUE;
        command += 17;
        while (*command == L' ' || *command == L'\t') ++command;
    }
    if (!wcsncmp(command, L"--builtin-game-sapi ", 20)) {
        builtin_game_sapi = TRUE;
        command += 20;
        while (*command == L' ' || *command == L'\t') ++command;
        status = configure_game_sapi();
        fprintf(log, "game SAPI=b; normal prelaunch native helper; registry status=%lu\n", status);
        fflush(log);
        if (status != ERROR_SUCCESS) { fclose(log); return status; }
    }
    if (!*command) { fclose(log); return 1; }
    if (desktop_speech) {
        WCHAR bootstrap_command[] = L"C:\\WinlatorNativeSapi\\payload\\configure-native-sapi.exe --bootstrap";
        WCHAR desktop_name[256] = {0};
        STARTUPINFOW bootstrap_startup = {0};
        PROCESS_INFORMATION bootstrap = {0};
        DWORD started = GetTickCount();
        BOOL created;
        status = configure_game_sapi();
        if (status != ERROR_SUCCESS) {
            fprintf(log, "game SAPI compatibility registry error=%lu\n", status);
            fclose(log); return status;
        }
        GetUserObjectInformationW(GetThreadDesktop(GetCurrentThreadId()), UOI_NAME,
                                  desktop_name, sizeof(desktop_name), NULL);
        fprintf(log, "speech bootstrap on existing desktop=%ls window=%p\n", desktop_name, GetDesktopWindow());
        fflush(log);
        DeleteFileW(L"C:\\NVDA-Bridge\\nvda-rpc-ready");
        DeleteFileW(L"C:\\NVDA-Bridge\\native-sapi-error");
        bootstrap_startup.cb = sizeof(bootstrap_startup);
        bootstrap_startup.lpDesktop = desktop_name;
        created = CreateProcessW(NULL, bootstrap_command, NULL, NULL, FALSE, CREATE_NO_WINDOW,
                NULL, NULL, &bootstrap_startup, &bootstrap);
        status = created ? 0 : GetLastError();
        if (!created) {
            fprintf(log, "speech bootstrap create error=%lu\n", status);
            fclose(log); return 1;
        }
        CloseHandle(bootstrap.hThread);
        while (GetFileAttributesW(L"C:\\NVDA-Bridge\\nvda-rpc-ready") == INVALID_FILE_ATTRIBUTES &&
               GetFileAttributesW(L"C:\\NVDA-Bridge\\native-sapi-error") == INVALID_FILE_ATTRIBUTES &&
               WaitForSingleObject(bootstrap.hProcess, 100) == WAIT_TIMEOUT &&
               GetTickCount() - started < 180000) {}
        CloseHandle(bootstrap.hProcess);
        if (GetFileAttributesW(L"C:\\NVDA-Bridge\\nvda-rpc-ready") == INVALID_FILE_ATTRIBUTES) {
            fprintf(log, "speech bootstrap failed or timed out\n");
            MessageBoxW(NULL, L"Speech setup failed. See native-sapi-error and native-launch.log.",
                        L"Speech setup failed", MB_OK | MB_ICONERROR);
            fclose(log); return 1;
        }
        fprintf(log, "speech ready on game desktop; helper SAPI=n, guest SAPI=b\n"); fflush(log);
        // Trace only the guest's driver registry lookup, after provisioning.
        SetEnvironmentVariableW(L"WINEDEBUG", L"-all,err+all,trace+driver,trace+reg");
    }
    if (!desktop_speech && !builtin_game_sapi && !FindWindowW(L"wxWindowClassNR", L"NVDA")) {
        window_class.lpfnWndProc = detection_window_proc;
        window_class.hInstance = instance;
        window_class.lpszClassName = L"wxWindowClassNR";
        RegisterClassW(&window_class);
        detection = CreateWindowExW(0, window_class.lpszClassName, L"NVDA",
            WS_OVERLAPPED, 0, 0, 0, 0, NULL, NULL, instance, NULL);
        fprintf(log, "local NVDA window=%p error=%lu\n", detection,
            detection ? 0 : GetLastError()); fflush(log);
    }
    fprintf(log, "NVDA detected=%u; starting guest\n",
        FindWindowW(L"wxWindowClassNR", L"NVDA") ? 1 : 0); fflush(log);
    copy = malloc((wcslen(command) + 1) * sizeof(WCHAR));
    if (!copy) { fclose(log); return ERROR_NOT_ENOUGH_MEMORY; }
    wcscpy(copy, command);
    startup.cb = sizeof(startup);
    if (!CreateProcessW(NULL, copy, NULL, NULL, FALSE, 0, NULL, NULL, &startup, &child)) {
        status = GetLastError();
        fprintf(log, "guest create error=%lu\n", status);
        free(copy); fclose(log); return status;
    }
    free(copy);
    fprintf(log, "guest started=%lu\n", child.dwProcessId); fflush(log);
    CloseHandle(child.hThread);
    trace_started = GetTickCount();
    next_snapshot = 2000;
    for (;;) {
        DWORD wait = MsgWaitForMultipleObjects(1, &child.hProcess, FALSE, 250, QS_ALLINPUT);
        MSG message;
        HWND foreground;
        if (wait == WAIT_OBJECT_0 || wait == WAIT_FAILED) break;
        while (PeekMessageW(&message, NULL, 0, 0, PM_REMOVE)) {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
        foreground = GetForegroundWindow();
        if (foreground != last_foreground) {
            fprintf(log, "foreground=%p\n", foreground);
            if (foreground) trace_window(foreground, (LPARAM)log);
            last_foreground = foreground;
            fflush(log);
        }
        if (next_snapshot && GetTickCount() - trace_started >= next_snapshot) {
            fprintf(log, "desktop windows after %lu ms\n", GetTickCount() - trace_started);
            EnumDesktopWindows(GetThreadDesktop(GetCurrentThreadId()), trace_window, (LPARAM)log);
            fflush(log);
            next_snapshot = next_snapshot == 2000 ? 10000 : 0;
        }
    }
    GetExitCodeProcess(child.hProcess, &status);
    CloseHandle(child.hProcess);
    if (detection) DestroyWindow(detection);
    fprintf(log, "guest exit=%lu\n", status);
    fclose(log);
    return status;
}

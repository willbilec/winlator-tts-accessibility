#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
#include <string.h>
#include <tlhelp32.h>

static FILE *log_file;
static BOOL close_detection;
static void CALLBACK report_event(HWINEVENTHOOK hook, DWORD event, HWND window,
        LONG object, LONG child, DWORD thread, DWORD time) {
    WCHAR name[128] = {0}, title[128] = {0};
    GUITHREADINFO info = {sizeof(info)};
    (void)hook;
    GetClassNameW(window, name, 128);
    GetWindowTextW(window, title, 128);
    GetGUIThreadInfo(thread, &info);
    fprintf(log_file, "event time=%lu type=%lx window=%p object=%ld child=%ld thread=%lu class=%ls title=%ls active=%p focus=%p\n",
        time, event, window, object, child, thread, name, title, info.hwndActive, info.hwndFocus);
    fflush(log_file);
}
typedef struct probe_thread_basic {
    LONG exit_status;
    void *teb;
    ULONG_PTR process_id, thread_id, affinity;
    LONG priority, base_priority;
} probe_thread_basic;
typedef LONG (WINAPI *query_thread_fn)(HANDLE, ULONG, void *, ULONG, ULONG *);
static BOOL CALLBACK report_desktop(WCHAR *name, LPARAM unused);
static BOOL CALLBACK report_child(HWND window, LPARAM unused) {
    WCHAR title[256] = {0}, name[128] = {0};
    (void)unused;
    GetWindowTextW(window, title, 256);
    GetClassNameW(window, name, 128);
    fprintf(log_file, "child=%p parent=%p id=%d visible=%u enabled=%u style=%lx class=%ls title=%ls\n",
        window, GetParent(window), GetDlgCtrlID(window), IsWindowVisible(window),
        IsWindowEnabled(window), (unsigned long)GetWindowLongW(window, GWL_STYLE), name, title);
    return TRUE;
}
static BOOL CALLBACK report_window(HWND window, LPARAM unused) {
    WCHAR title[256] = {0}, class_name[128] = {0};
    DWORD pid;
    DWORD thread = GetWindowThreadProcessId(window, &pid);
    GUITHREADINFO info = {sizeof(info)};
    RECT rect = {0};
    (void)unused;
    GetWindowTextW(window, title, 256);
    GetClassNameW(window, class_name, 128);
    GetWindowRect(window, &rect);
    BOOL gui_ok = GetGUIThreadInfo(thread, &info);
    fprintf(log_file, "window=%p pid=%lu visible=%u enabled=%u rect=%ld,%ld,%ld,%ld class=%ls title=%ls active=%p focus=%p\n",
        window, pid, IsWindowVisible(window), IsWindowEnabled(window), rect.left, rect.top,
        rect.right, rect.bottom, class_name, title, info.hwndActive, info.hwndFocus);
    fprintf(log_file, "gui thread=%lu result=%u flags=%lu capture=%p menu=%p move=%p\n",
        thread, gui_ok, info.flags, info.hwndCapture, info.hwndMenuOwner, info.hwndMoveSize);
    if (!wcscmp(class_name, L"#32770")) {
        DWORD_PTR default_id = 0;
        LRESULT result = SendMessageTimeoutW(window, DM_GETDEFID, 0, 0,
            SMTO_ABORTIFHUNG, 300, &default_id);
        fprintf(log_file, "dialog default_result=%lld default_id=%llu\n",
            (long long)result, (unsigned long long)default_id);
        EnumChildWindows(window, report_child, 0);
    }
    if (close_detection && !wcscmp(class_name, L"wxWindowClassNR") && !wcscmp(title, L"NVDA")) {
        fprintf(log_file, "close detection=%p queued=%u\n", window, PostMessageW(window, WM_CLOSE, 0, 0));
    }
    return TRUE;
}
static BOOL CALLBACK report_desktop(WCHAR *name, LPARAM unused) {
    HDESK desktop = OpenDesktopW(name, 0, FALSE, GENERIC_ALL);
    (void)unused;
    fprintf(log_file, "desktop=%ls handle=%p error=%lu\n", name, desktop,
        desktop ? 0 : GetLastError());
    if (desktop) { EnumDesktopWindows(desktop, report_window, 0); CloseDesktop(desktop); }
    return TRUE;
}
int WINAPI WinMain(HINSTANCE instance, HINSTANCE previous, LPSTR args, int show) {
    HDESK desktop = OpenDesktopW(L"nogui", 0, FALSE, GENERIC_READ | DESKTOP_ENUMERATE);
    (void)instance; (void)previous; (void)args; (void)show;
    close_detection = strstr(args, "--close-detection") != NULL;
    log_file = fopen("C:\\NVDA-Bridge\\focus-probe.log", "w");
    if (!log_file) return 1;
    if (!desktop || !SetThreadDesktop(desktop)) {
        fprintf(log_file, "desktop error=%lu\n", GetLastError()); fclose(log_file); return 2;
    }
    fprintf(log_file, "foreground=%p\n", GetForegroundWindow());
    {
        HWND window = GetDesktopWindow();
        ATOM atom = (ATOM)(ULONG_PTR)GetPropW(window, L"__wine_display_device_guid");
        WCHAR guid[256] = {0}, path[512], driver[256] = {0};
        HKEY key;
        DWORD bytes = sizeof(driver);
        GlobalGetAtomNameW(atom, guid, 256);
        swprintf(path, 512, L"System\\CurrentControlSet\\Control\\Video\\{%ls}\\0000", guid);
        LONG result = RegOpenKeyExW(HKEY_LOCAL_MACHINE, path, 0, KEY_READ, &key);
        fprintf(log_file, "desktop=%p driverAtom=%u guid=%ls registry=%ld\n", window, atom, guid, result);
        if (!result) {
            result = RegQueryValueExW(key, L"GraphicsDriver", NULL, NULL, (BYTE *)driver, &bytes);
            fprintf(log_file, "driver=%ls result=%ld\n", driver, result);
            RegCloseKey(key);
        }
    }
    if (GetForegroundWindow()) report_window(GetForegroundWindow(), 0);
    EnumDesktopsW(GetProcessWindowStation(), report_desktop, 0);
    {
        HANDLE processes = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        PROCESSENTRY32W process = {sizeof(process)};
        if (Process32FirstW(processes, &process)) do {
            if (wcsstr(process.szExeFile, L"Liam") || wcsstr(process.szExeFile, L"winhandler")) {
                HANDLE threads = CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0);
                HANDLE memory = OpenProcess(PROCESS_VM_READ | PROCESS_QUERY_INFORMATION, FALSE, process.th32ProcessID);
                query_thread_fn query_thread = (query_thread_fn)(void *)GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "NtQueryInformationThread");
                THREADENTRY32 thread = {sizeof(thread)};
                fprintf(log_file, "process=%lu name=%ls\n", process.th32ProcessID, process.szExeFile);
                if (Thread32First(threads, &thread)) do {
                    if (thread.th32OwnerProcessID == process.th32ProcessID) {
                        WCHAR name[256] = {0};
                        HDESK target = GetThreadDesktop(thread.th32ThreadID);
                        GetUserObjectInformationW(target, UOI_NAME, name, sizeof(name), NULL);
                        fprintf(log_file, "thread=%lu desktop=%ls handle=%p error=%lu\n",
                            thread.th32ThreadID, name, target, GetLastError());
                        if (memory && query_thread) {
                            HANDLE handle = OpenThread(THREAD_QUERY_INFORMATION, FALSE, thread.th32ThreadID);
                            probe_thread_basic basic = {0};
                            unsigned char cache[72] = {0};
                            SIZE_T read = 0;
                            if (handle && !query_thread(handle, 0, &basic, sizeof(basic), NULL) &&
                                ReadProcessMemory(memory, (char *)basic.teb + 0x800, cache, sizeof(cache), &read)) {
                                UINT64 driver;
                                UINT top;
                                memcpy(&driver, cache, sizeof(driver));
                                memcpy(&top, cache + 40, sizeof(top));
                                fprintf(log_file, "cache thread=%lu teb=%p driver=%llx top=%08x valid=%u atom=%llu\n",
                                    thread.th32ThreadID, basic.teb, (unsigned long long)driver, top,
                                    IsWindow((HWND)(ULONG_PTR)top),
                                    (unsigned long long)(ULONG_PTR)GetPropW((HWND)(ULONG_PTR)top, L"__wine_display_device_guid"));
                            }
                            if (handle) CloseHandle(handle);
                        }
                    }
                } while (Thread32Next(threads, &thread));
                CloseHandle(threads);
                if (memory) CloseHandle(memory);
            }
        } while (Process32NextW(processes, &process));
        CloseHandle(processes);
    }
    if (strstr(args, "--watch")) {
        HWINEVENTHOOK foreground_hook = SetWinEventHook(EVENT_SYSTEM_FOREGROUND,
            EVENT_SYSTEM_FOREGROUND, NULL, report_event, 0, 0, WINEVENT_OUTOFCONTEXT);
        HWINEVENTHOOK control_hook = SetWinEventHook(EVENT_OBJECT_CREATE,
            EVENT_OBJECT_FOCUS, NULL, report_event, 0, 0, WINEVENT_OUTOFCONTEXT);
        DWORD start = GetTickCount();
        MSG message;
        fprintf(log_file, "watch started time=%lu duration=120000\n", start);
        fflush(log_file);
        while (GetTickCount() - start < 120000) {
            while (PeekMessageW(&message, NULL, 0, 0, PM_REMOVE)) {
                TranslateMessage(&message); DispatchMessageW(&message);
            }
            Sleep(20);
        }
        if (foreground_hook) UnhookWinEvent(foreground_hook);
        if (control_hook) UnhookWinEvent(control_hook);
    }
    fclose(log_file);
    CloseDesktop(desktop);
    return 0;
}

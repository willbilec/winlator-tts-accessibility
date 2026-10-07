#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>

int WINAPI WinMain(HINSTANCE instance, HINSTANCE previous, LPSTR command, int show) {
    STARTUPINFOW startup = {0};
    PROCESS_INFORMATION service = {0}, client = {0};
    DWORD result = 0;
    FILE *log;
    (void)instance; (void)previous; (void)command; (void)show;
    log = fopen("C:\\NVDA-Bridge\\rpc-test-launcher.log", "w");
    if (!log) return 1;
    startup.cb = sizeof(startup);
    if (!CreateProcessW(L"C:\\NVDA-Bridge\\nvda_rpc_service.exe", NULL, NULL,
                        NULL, FALSE, 0, NULL, NULL, &startup, &service)) {
        fprintf(log, "service start=%lu\n", GetLastError());
        fclose(log);
        return 2;
    }
    fprintf(log, "service pid=%lu\n", service.dwProcessId);
    fflush(log);
    Sleep(3000);
    if (!CreateProcessW(L"C:\\NVDA-Bridge\\original-client\\probe.exe", NULL,
                        NULL, NULL, FALSE, 0, NULL, NULL, &startup, &client)) {
        fprintf(log, "client start=%lu\n", GetLastError());
        TerminateProcess(service.hProcess, 1);
        fclose(log);
        return 3;
    }
    fprintf(log, "client pid=%lu\n", client.dwProcessId);
    fflush(log);
    WaitForSingleObject(client.hProcess, 25000);
    GetExitCodeProcess(client.hProcess, &result);
    fprintf(log, "client exit=%lu\n", result);
    fflush(log);
    TerminateProcess(service.hProcess, 0);
    CloseHandle(client.hThread);
    CloseHandle(client.hProcess);
    CloseHandle(service.hThread);
    CloseHandle(service.hProcess);
    fclose(log);
    return 0;
}

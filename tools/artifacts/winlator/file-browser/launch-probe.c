#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
typedef DWORD (__stdcall *SpeakFn)(const WCHAR *);
static LRESULT CALLBACK procedure(HWND window,UINT message,WPARAM word,LPARAM data) {
    if(message==WM_TIMER){DestroyWindow(window);return 0;}
    if(message==WM_DESTROY){PostQuitMessage(0);return 0;}
    return DefWindowProcW(window,message,word,data);
}
int WINAPI wWinMain(HINSTANCE instance,HINSTANCE previous,PWSTR command,int show) {
    (void)previous;(void)command;(void)show;
    FILE *log=_wfopen(L"C:\\WinlatorFileBrowser\\launch-probe.log",L"w");
    STARTUPINFOW startup={0};PROCESS_INFORMATION child={0};startup.cb=sizeof(startup);
    WCHAR selfTest[]=L"C:\\WinlatorFileBrowser\\accessible-browser.exe --self-test";
    DWORD filesystem=ERROR_GEN_FAILURE;
    if(CreateProcessW(NULL,selfTest,NULL,NULL,FALSE,0,NULL,NULL,&startup,&child)) {
        if(WaitForSingleObject(child.hProcess,20000)==WAIT_OBJECT_0)GetExitCodeProcess(child.hProcess,&filesystem);
        else TerminateProcess(child.hProcess,1);
        CloseHandle(child.hThread);CloseHandle(child.hProcess);
    }
    if(log){fprintf(log,"native filesystem/control self-test=%lu\n",filesystem);fflush(log);}
    WNDCLASSW type={0};type.lpfnWndProc=procedure;type.hInstance=instance;type.lpszClassName=L"BrowserLaunchProbe";
    type.hbrBackground=(HBRUSH)(COLOR_WINDOW+1);RegisterClassW(&type);
    HWND window=CreateWindowW(type.lpszClassName,L"Browser launch test",WS_OVERLAPPEDWINDOW|WS_VISIBLE,30,30,600,300,NULL,NULL,instance,NULL);
    if(!window)return 1;
    HMODULE dll=LoadLibraryW(L"C:\\WinlatorNativeSapi\\payload\\nvdaControllerClient64.dll");
    SpeakFn speak=dll?(SpeakFn)(void *)GetProcAddress(dll,"nvdaController_speakText"):NULL;
    DWORD result=speak?speak(L"Browser launch test ready. Returning to your folder in six seconds."):ERROR_MOD_NOT_FOUND;
    if(log){fprintf(log,"ready speech=%lu\n",result);fflush(log);}
    SetTimer(window,1,6000,NULL);MSG message;
    while(GetMessageW(&message,NULL,0,0)>0){TranslateMessage(&message);DispatchMessageW(&message);}
    if(log){fprintf(log,"normal exit\n");fclose(log);}if(dll)FreeLibrary(dll);return 0;
}

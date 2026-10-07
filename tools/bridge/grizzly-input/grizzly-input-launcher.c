#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
#include <string.h>
#ifndef DEFAULT_GAME_PATH
#define DEFAULT_GAME_PATH "E:\\grizzly-gulch\\Grizzly Gulch.exe"
#endif

/* Launches the original executable with a pass-through diagnostic DLL.
 * The game file, saves and persistent DLL overrides are not changed. */
int WINAPI WinMain(HINSTANCE instance,HINSTANCE previous,LPSTR command,int show) {
    char dll[MAX_PATH],directory[MAX_PATH],target[MAX_PATH],line[32768],*slash;
    const char *arguments="";
    STARTUPINFOA startup;
    PROCESS_INFORMATION process;
    HANDLE remote_thread;
    void *remote;
    DWORD loaded=0;
    BOOL ok;
    FILE *log;
    extern int __argc;
    extern char **__argv;
    int target_index=1;
    BOOL raw_queue=TRUE;
    (void)instance; (void)previous; (void)command; (void)show;
    GetModuleFileNameA(NULL,dll,MAX_PATH);
    slash=strrchr(dll,'\\'); if(!slash) return 2;
    strcpy(slash+1,"grizzly-trace-launcher.log");
    log=fopen(dll,"w"); if(!log) return 3;
    strcpy(slash+1,"grizzly-input.dll");
    if(__argc>1) {
        if(!strncmp(__argv[1],"--bavisoft-raw-queue=",21)) {
            raw_queue=_stricmp(__argv[1]+21,"off")!=0;
            target_index=2;
        }
        if(__argc<=target_index || strlen(__argv[target_index])>=MAX_PATH) { fclose(log); return 4; }
        strcpy(target,__argv[target_index]);
        arguments=command;
        while(*arguments==' ' || *arguments=='\t') ++arguments;
        if(target_index==2) {
            while(*arguments && *arguments!=' ' && *arguments!='\t') ++arguments;
            while(*arguments==' ' || *arguments=='\t') ++arguments;
        }
        if(*arguments=='"') {
            ++arguments;
            while(*arguments && *arguments!='"') ++arguments;
            if(*arguments) ++arguments;
        } else while(*arguments && *arguments!=' ' && *arguments!='\t') ++arguments;
    } else strcpy(target,DEFAULT_GAME_PATH);
    strcpy(directory,target);
    slash=strrchr(directory,'\\'); if(!slash) slash=strrchr(directory,'/');
    if(!slash) { fclose(log); return 5; }
    *slash=0;
    if(strlen(target)+strlen(arguments)+3>sizeof(line)) { fclose(log); return 4; }
    snprintf(line,sizeof(line),"\"%s\"%s",target,arguments);
#ifdef BAVISOFT_BUILTIN_DSOUND
    {
        const char *name=strrchr(target,'\\');
        char registry_path[256];
        HKEY key;
        LONG result;
        if(!name) name=strrchr(target,'/');
        name=name ? name+1 : target;
        if(!_stricmp(name,"Chillingham.exe") || !_stricmp(name,"Grizzly Gulch.exe")) {
            /* Wine's Unix loader does not inherit this override reliably from
             * a Windows-side SetEnvironmentVariable/CreateProcess pair. Set
             * the exact game's AppDefaults key before its loader starts. */
            if(GetProcAddress(GetModuleHandleA("ntdll.dll"),"wine_get_version")) {
                snprintf(registry_path,sizeof(registry_path),
                         "Software\\Wine\\AppDefaults\\%s\\DllOverrides",name);
                result=RegCreateKeyExA(HKEY_CURRENT_USER,registry_path,0,NULL,0,KEY_SET_VALUE,NULL,&key,NULL);
                if(result==ERROR_SUCCESS) {
                    result=RegSetValueExA(key,"dsound",0,REG_SZ,(const BYTE *)"builtin",sizeof("builtin"));
                    RegCloseKey(key);
                }
                fprintf(log,"game-only DirectSound=builtin registry_result=%ld\n",result);
                if(result!=ERROR_SUCCESS) { fclose(log); return 8; }
            }
        }
    }
#endif
    memset(&startup,0,sizeof(startup));startup.cb=sizeof(startup);
    memset(&process,0,sizeof(process));
    SetEnvironmentVariableA("BAVISOFT_RAW_QUEUE",raw_queue ? "1" : "0");
    ok=CreateProcessA(target,line,NULL,NULL,FALSE,CREATE_SUSPENDED,NULL,directory,&startup,&process);
    fprintf(log,"target=%s create=%d error=%lu\n",target,ok,ok ? 0 : GetLastError());
    if(!ok) { fclose(log); return 6; }
    remote=VirtualAllocEx(process.hProcess,NULL,strlen(dll)+1,MEM_COMMIT|MEM_RESERVE,PAGE_READWRITE);
    ok=remote && WriteProcessMemory(process.hProcess,remote,dll,strlen(dll)+1,NULL);
    remote_thread=ok ? CreateRemoteThread(process.hProcess,NULL,0,
        (LPTHREAD_START_ROUTINE)(void *)GetProcAddress(GetModuleHandleA("kernel32.dll"),"LoadLibraryA"),remote,0,NULL) : NULL;
    if(remote_thread) {
        if(WaitForSingleObject(remote_thread,10000)==WAIT_OBJECT_0)
            GetExitCodeThread(remote_thread,&loaded);
        CloseHandle(remote_thread);
    }
    fprintf(log,"diagnostic=%s loaded=%08lx error=%lu\n",dll,loaded,loaded ? 0 : GetLastError());
    fflush(log);
    /* Free the parameter only after the load thread actually completed. */
    if(loaded) VirtualFreeEx(process.hProcess,remote,0,MEM_RELEASE);
    ResumeThread(process.hThread);
    CloseHandle(process.hThread);
    if(loaded && GetProcAddress(GetModuleHandleA("ntdll.dll"),"wine_get_version")) {
        HMODULE image=LoadLibraryExA(dll,NULL,DONT_RESOLVE_DLL_REFERENCES);
        const BYTE *status=image ? (const BYTE *)GetProcAddress(image,"BavisoftInputStatus") : NULL;
        if(status) {
            DWORD value=0,attempt;
            SIZE_T copied;
            const void *remote_status=(const BYTE *)(uintptr_t)loaded+(status-(const BYTE *)image);
            for(attempt=0;attempt<20;++attempt) {
                if(!ReadProcessMemory(process.hProcess,remote_status,&value,sizeof(value),&copied) ||
                   (value&3)==3) break;
                Sleep(500);
            }
            fprintf(log,"raw_input_queue_status=%08lx\n",value); fflush(log);
        }
        if(image) FreeLibrary(image);
    }
    WaitForSingleObject(process.hProcess,INFINITE);
    {
        DWORD exit_code=STILL_ACTIVE;
        GetExitCodeProcess(process.hProcess,&exit_code);
        fprintf(log,"game_exit=%08lx\n",exit_code);
    }
    CloseHandle(process.hProcess);
    fclose(log);
    return loaded ? 0 : 7;
}

#include <windows.h>
#include <stdio.h>
#include <stdint.h>
#include <stdarg.h>

static HMODULE real;
static volatile DWORD last_channel;
static FARPROC resolve(const char *name) {
    if (!real) real = LoadLibraryA("bass-original.dll");
    return real ? GetProcAddress(real,name) : NULL;
}
static int error(void) {
    int (WINAPI *fn)(void) = (void*)resolve("BASS_ErrorGetCode");
    return fn ? fn() : -999;
}
static void logline(const char *format, ...) {
    FILE *file = fopen("bass-audio-trace.log","a");
    if (!file) return;
    fprintf(file,"%lu thread=%lu ",GetTickCount(),GetCurrentThreadId());
    va_list args; va_start(args,format); vfprintf(file,format,args); va_end(args);
    fputc('\n',file); fclose(file);
}
static DWORD WINAPI monitor(LPVOID unused) {
    DWORD (WINAPI *active)(DWORD)=(void*)resolve("BASS_ChannelIsActive");
    uint64_t (WINAPI *position)(DWORD,DWORD)=(void*)resolve("BASS_ChannelGetPosition");
    BOOL (WINAPI *started)(void)=(void*)resolve("BASS_IsStarted");
    for (int i=0;i<30;i++) {
        Sleep(1000);
        DWORD channel=last_channel;
        logline("Monitor channel=%lu active=%lu position=%llu started=%d",channel,
            active ? active(channel) : 999, position ? position(channel,0) : 0,
            started ? started() : -1);
    }
    return 0;
}
BOOL WINAPI BASS_Init(int device,DWORD rate,DWORD flags,HWND window,const GUID *clsid) {
    BOOL (WINAPI *fn)(int,DWORD,DWORD,HWND,const GUID*)=(void*)resolve("BASS_Init");
    BOOL result=fn ? fn(device,rate,flags,window,clsid) : FALSE;
    logline("Init device=%d rate=%lu flags=%lx result=%d error=%d",device,rate,flags,result,error());
#ifdef BM_KEEPALIVE
    if (result) {
        BOOL (WINAPI *config)(DWORD,DWORD)=(void*)resolve("BASS_SetConfig");
        BOOL nonstop=config ? config(50,1) : FALSE; /* BASS_CONFIG_DEV_NONSTOP */
        logline("KeepAlive nonstop=%d error=%d",nonstop,error());
    }
#else
    if (result) { HANDLE thread=CreateThread(NULL,0,monitor,NULL,0,NULL); if (thread) CloseHandle(thread); }
#endif
    return result;
}
DWORD WINAPI BASS_StreamCreateFile(BOOL memory,const void *file,uint64_t offset,uint64_t length,DWORD flags) {
    DWORD (WINAPI *fn)(BOOL,const void*,uint64_t,uint64_t,DWORD)=(void*)resolve("BASS_StreamCreateFile");
    DWORD result=fn ? fn(memory,file,offset,length,flags) : 0;
    logline("StreamCreateFile memory=%d offset=%llu length=%llu flags=%lx handle=%lu error=%d",memory,offset,length,flags,result,error());
    return result;
}
DWORD WINAPI BASS_SampleLoad(BOOL memory,const void *file,uint64_t offset,DWORD length,DWORD max,DWORD flags) {
    DWORD (WINAPI *fn)(BOOL,const void*,uint64_t,DWORD,DWORD,DWORD)=(void*)resolve("BASS_SampleLoad");
    DWORD result=fn ? fn(memory,file,offset,length,max,flags) : 0;
    logline("SampleLoad memory=%d length=%lu flags=%lx handle=%lu error=%d",memory,length,flags,result,error());
    return result;
}
BOOL WINAPI BASS_ChannelPlay(DWORD channel,BOOL restart) {
    BOOL (WINAPI *fn)(DWORD,BOOL)=(void*)resolve("BASS_ChannelPlay");
    BOOL result=fn ? fn(channel,restart) : FALSE;
    last_channel=channel;
    logline("ChannelPlay channel=%lu restart=%d result=%d error=%d",channel,restart,result,error());
    return result;
}
BOOL WINAPI BASS_ChannelStop(DWORD channel) {
    BOOL (WINAPI *fn)(DWORD)=(void*)resolve("BASS_ChannelStop");
    BOOL result=fn ? fn(channel) : FALSE;
    logline("ChannelStop channel=%lu result=%d error=%d",channel,result,error()); return result;
}
BOOL WINAPI BASS_ChannelSetAttribute(DWORD channel,DWORD attribute,float value) {
    BOOL (WINAPI *fn)(DWORD,DWORD,float)=(void*)resolve("BASS_ChannelSetAttribute");
    BOOL result=fn ? fn(channel,attribute,value) : FALSE;
    logline("SetAttribute channel=%lu attribute=%lu value=%g result=%d error=%d",channel,attribute,value,result,error()); return result;
}
BOOL WINAPI BASS_SetConfig(DWORD option,DWORD value) {
    BOOL (WINAPI *fn)(DWORD,DWORD)=(void*)resolve("BASS_SetConfig");
    BOOL result=fn ? fn(option,value) : FALSE;
    logline("SetConfig option=%lu value=%lu result=%d error=%d",option,value,result,error()); return result;
}
BOOL WINAPI BASS_Start(void) {
    BOOL (WINAPI *fn)(void)=(void*)resolve("BASS_Start");
    BOOL result=fn ? fn() : FALSE;
    logline("Start result=%d error=%d",result,error()); return result;
}
BOOL WINAPI BASS_Stop(void) {
    BOOL (WINAPI *fn)(void)=(void*)resolve("BASS_Stop");
    BOOL result=fn ? fn() : FALSE;
    logline("Stop result=%d error=%d",result,error()); return result;
}
BOOL WINAPI DllMain(HINSTANCE instance,DWORD reason,LPVOID reserved) {
    if (reason==DLL_PROCESS_ATTACH) DisableThreadLibraryCalls(instance);
    return TRUE;
}

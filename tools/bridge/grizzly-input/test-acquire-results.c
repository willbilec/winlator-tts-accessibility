#define WIN32_LEAN_AND_MEAN
#define COBJMACROS
#define DIRECTINPUT_VERSION 0x0800
#include <windows.h>
#include <dinput.h>
#include <stdio.h>

int main(void) {
    WCHAR path[MAX_PATH],*slash;
    GetModuleFileNameW(NULL,path,MAX_PATH); slash=wcsrchr(path,L'\\');
    if(slash) { wcscpy(slash+1,L"acquire-results.log"); _wfreopen(path,L"w",stdout); }
    HWND window=CreateWindowW(L"STATIC",L"Acquire return comparison",WS_OVERLAPPED,
                             0,0,100,100,NULL,NULL,GetModuleHandleW(NULL),NULL);
    DWORD versions[]={0x500,0x800}; unsigned i,j;
    for(i=0;i<2;++i) {
        IDirectInputA *legacy=NULL; IDirectInput8A *modern=NULL;
        IDirectInputDeviceA *keyboard=NULL; HRESULT hr;
        if(!i) {
            HMODULE library=LoadLibraryW(L"dinput.dll");
            HRESULT (WINAPI *create)(HINSTANCE,DWORD,IDirectInputA **,IUnknown *)=
                (void *)GetProcAddress(library,"DirectInputCreateA");
            hr=create(GetModuleHandleW(NULL),versions[i],&legacy,NULL);
            if(SUCCEEDED(hr)) hr=IDirectInput_CreateDevice(legacy,&GUID_SysKeyboard,&keyboard,NULL);
        } else {
            HMODULE library=LoadLibraryW(L"dinput8.dll");
            HRESULT (WINAPI *create)(HINSTANCE,DWORD,REFIID,void **,IUnknown *)=
                (void *)GetProcAddress(library,"DirectInput8Create");
            hr=create(GetModuleHandleW(NULL),versions[i],&IID_IDirectInput8A,(void **)&modern,NULL);
            if(SUCCEEDED(hr)) hr=IDirectInput8_CreateDevice(modern,&GUID_SysKeyboard,(IDirectInputDevice8A **)&keyboard,NULL);
        }
        if(FAILED(hr)) { printf("create=%08lx\n",(unsigned long)hr); return 1; }
        hr=IDirectInputDevice_SetDataFormat(keyboard,&c_dfDIKeyboard);
        if(SUCCEEDED(hr)) hr=IDirectInputDevice_SetCooperativeLevel(keyboard,window,6);
        if(FAILED(hr)) return 2;
        printf("version=%lx acquire=",(unsigned long)versions[i]);
        for(j=0;j<4;++j) { hr=IDirectInputDevice_Acquire(keyboard); printf("%08lx ",(unsigned long)hr); }
        puts("");
        IDirectInputDevice_Unacquire(keyboard); IDirectInputDevice_Release(keyboard);
        if(legacy) IDirectInput_Release(legacy);
        if(modern) IDirectInput8_Release(modern);
    }
    DestroyWindow(window); return 0;
}

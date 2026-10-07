#define RAW_INPUT_KEYBOARD
#include "grizzly-input.c"

/* Exercise the real DirectInput 8 device through the legacy ABI, including
 * repeated creation/release. No keyboard injection or physical input needed. */
int main(void) {
    HMODULE legacy=LoadLibraryW(L"dinput.dll");
    factory_fn create5=legacy ? (factory_fn)GetProcAddress(legacy,"DirectInputCreateA") : NULL;
    IDirectInputA *input=NULL;
    HWND window=CreateWindowW(L"STATIC",L"Backend compatibility test",WS_OVERLAPPED,
                             0,0,100,100,NULL,NULL,GetModuleHandleW(NULL),NULL);
    unsigned i;
    HRESULT hr;
    QueryPerformanceFrequency(&frequency); origin=ticks();
    if(!create5 || !window) return 1;
    hr=create5(GetModuleHandleW(NULL),0x0500,&input,NULL);
    if(FAILED(hr)) { printf("legacy factory failed %08lx\n",(unsigned long)hr); return 2; }
    inputs[0].object=input; inputs[0].original=*(void ***)input; input_count=1;
    for(i=0;i<1000;++i) {
        IDirectInputDeviceA *keyboard=NULL;
        DIDEVCAPS caps={0};
        BYTE state[256];
        caps.dwSize=sizeof(caps);
        hr=create_raw_keyboard(&GUID_SysKeyboard,&keyboard,NULL);
        if(FAILED(hr)) { printf("raw create failed %08lx\n",(unsigned long)hr); return 3; }
        hr=IDirectInputDevice_GetCapabilities(keyboard,&caps);
        if(FAILED(hr) || GET_DIDEVICE_TYPE(caps.dwDevType)!=DI8DEVTYPE_KEYBOARD) {
            printf("caps hr=%08lx type=%08lx expected=%u\n",(unsigned long)hr,(unsigned long)caps.dwDevType,DI8DEVTYPE_KEYBOARD); return 4;
        }
        if(FAILED(IDirectInputDevice_SetDataFormat(keyboard,&c_dfDIKeyboard))) return 5;
        if(FAILED(IDirectInputDevice_SetCooperativeLevel(keyboard,window,DISCL_BACKGROUND|DISCL_NONEXCLUSIVE))) return 6;
        if(FAILED(IDirectInputDevice_Acquire(keyboard))) return 7;
        if(!i) printf("raw repeated Acquire=%08lx\n",(unsigned long)IDirectInputDevice_Acquire(keyboard));
        memset(state,0xa5,sizeof(state));
        if(FAILED(IDirectInputDevice_GetDeviceState(keyboard,sizeof(state),state))) return 8;
        if(FAILED(IDirectInputDevice_Unacquire(keyboard))) return 9;
        if(IDirectInputDevice_GetDeviceState(keyboard,sizeof(state),state)!=DIERR_NOTACQUIRED) return 10;
        if(IDirectInputDevice_Release(keyboard)) return 11;
    }
    IDirectInput_Release(input); DestroyWindow(window);
    printf("PASS: 1000 raw keyboard lifetimes through legacy ABI; format, cooperation, acquire, snapshot and unacquired error\n");
    return 0;
}

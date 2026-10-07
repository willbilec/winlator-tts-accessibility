#define WIN32_LEAN_AND_MEAN
#define COBJMACROS
#define DIRECTINPUT_VERSION 0x0800
#include <windows.h>
#include <dinput.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>

/* Bavisoft-specific Wine input compatibility. RAW_INPUT_KEYBOARD gives the
 * legacy game a DirectInput 8 keyboard device; SYNC_LEGACY_RELEASES retains
 * the earlier worker-synchronization experiment. Build only one candidate.
 * No buffered replay, synthetic key events, or timed key pulses are used.
 * Per-instance hooks avoid global DLL overrides. Bounded telemetry is written
 * by a separate worker, outside the game's input path. */
typedef HRESULT (WINAPI *factory_fn)(HINSTANCE,DWORD,IDirectInputA **,IUnknown *);
typedef HRESULT (WINAPI *factory8_fn)(HINSTANCE,DWORD,REFIID,void **,IUnknown *);
typedef HRESULT (WINAPI *create_fn)(IDirectInputA *,REFGUID,IDirectInputDeviceA **,IUnknown *);
typedef HRESULT (WINAPI *state_fn)(IDirectInputDeviceA *,DWORD,void *);
typedef HRESULT (WINAPI *simple_fn)(IDirectInputDeviceA *);
typedef HRESULT (WINAPI *coop_fn)(IDirectInputDeviceA *,HWND,DWORD);
typedef HRESULT (WINAPI *format_fn)(IDirectInputDeviceA *,const DIDATAFORMAT *);
typedef ULONG (WINAPI *release_fn)(void *);
typedef struct {
    void *object;
    void **original;
    void **copy;
    BYTE last[8];
    DWORD polls;
    LONGLONG previous, report;
    LONGLONG synchronized;
    DWORD sync_count;
} instance_record;
typedef struct {
    volatile LONG ready;
    LONGLONG time, duration;
    DWORD thread, polls, code, value;
    HRESULT result;
    void *object, *caller;
    BYTE state[8], mapped[4];
} trace_record;
#define RECORD_COUNT 65536
static trace_record ring[RECORD_COUNT];
static volatile LONG reserved_count;
static instance_record inputs[64], devices[64];
static LONG input_count, device_count;
static factory_fn original_factory;
static LARGE_INTEGER frequency;
static LONGLONG origin;
static FILE *log_file;
static HMODULE trace_module;
static HWND input_worker;
static instance_record *find(instance_record *records,LONG count,void *object);
static void record(DWORD,void *,void *,HRESULT,DWORD,DWORD,LONGLONG,const BYTE *,const BYTE *);
#ifdef RAW_INPUT_DRAIN
__declspec(dllexport) volatile LONG BavisoftInputStatus;
static HWND raw_worker;
static WNDPROC raw_window_proc;
static UINT raw_fence_message;
static BOOL raw_one_packet=TRUE;
static LRESULT CALLBACK drain_raw_messages(HWND window,UINT message,WPARAM wparam,LPARAM lparam) {
#ifndef NO_INPUT_TRACE
    if(message==WM_INPUT) {
        RAWINPUT raw;
        UINT size=sizeof(raw);
        if(GetRawInputData((HRAWINPUT)lparam,RID_INPUT,&raw,&size,sizeof(RAWINPUTHEADER))!=(UINT)-1 &&
           raw.header.dwType==RIM_TYPEKEYBOARD)
            record(12,window,NULL,S_OK,raw.data.keyboard.VKey|((DWORD)raw.data.keyboard.Flags<<16),
                   raw.data.keyboard.MakeCode,
                   (DWORD)(GetTickCount()-(DWORD)GetMessageTime())*frequency.QuadPart/1000,
                   NULL,NULL);
    }
#endif
    if(message==raw_fence_message && wparam==0x47524747) {
        MSG queued;
        unsigned count=0;
        /* A sent fence can overtake queued WM_INPUT. Explicitly process one real
         * raw packet on the owning thread, through Wine's original procedure.
         * No input data is retained or synthesized. */
        /* The game polls immediate state. If a very short physical tap has
         * both packets queued already, dispatching both before one snapshot
         * erases the press. Advance one keyboard packet per snapshot so the
         * game observes down, then release on its following read. */
        while(count<(raw_one_packet ? 1 : 128) && PeekMessageW(&queued,window,WM_INPUT,WM_INPUT,PM_REMOVE)) {
            DispatchMessageW(&queued); ++count;
        }
        return count != 0;
    }
    return CallWindowProcW(raw_window_proc,window,message,wparam,lparam);
}
static HWND keyboard_raw_worker(void) {
    RAWINPUTDEVICE registered[32];
    UINT count=32,i;
    DWORD process;
    if(raw_worker && IsWindow(raw_worker)) return raw_worker;
    count=GetRegisteredRawInputDevices(registered,&count,sizeof(registered[0]));
    if(count==(UINT)-1) return NULL;
    for(i=0;i<count;++i) {
        HWND window=registered[i].hwndTarget;
        if(registered[i].usUsagePage!=1 || registered[i].usUsage!=6 || !window) continue;
        GetWindowThreadProcessId(window,&process);
        if(process!=GetCurrentProcessId()) continue;
        if(!raw_fence_message) raw_fence_message=RegisterWindowMessageW(L"Winlator.Bavisoft.RawKeyboardFence.98D7E101");
        if(!raw_fence_message) return NULL;
        raw_window_proc=(WNDPROC)GetWindowLongPtrW(window,GWLP_WNDPROC);
        if(!raw_window_proc) return NULL;
        SetLastError(0);
        if(!SetWindowLongPtrW(window,GWLP_WNDPROC,(LONG_PTR)drain_raw_messages) && GetLastError()) return NULL;
        raw_worker=window;
        InterlockedOr(&BavisoftInputStatus,1);
        return window;
    }
    return NULL;
}
static HRESULT WINAPI fresh_raw_state(IDirectInputDeviceA *object,DWORD size,void *data) {
    instance_record *entry=find(devices,device_count,object);
    HRESULT hr=((state_fn)entry->original[9])(object,size,data);
    if(SUCCEEDED(hr)) {
        HWND worker=keyboard_raw_worker();
        DWORD_PTR drained=0;
        if(worker && SendMessageTimeoutW(worker,raw_fence_message,0x47524747,0,
                SMTO_BLOCK|SMTO_ABORTIFHUNG,20,&drained)) {
            InterlockedOr(&BavisoftInputStatus,2);
            hr=((state_fn)entry->original[9])(object,size,data);
        }
    }
    return hr;
}
#endif
static HWND find_input_worker(void) {
    HWND window=NULL;
    DWORD process;
    if (input_worker && IsWindow(input_worker)) return input_worker;
    while ((window=FindWindowExW(HWND_MESSAGE,window,L"DIEmWin",NULL))) {
        GetWindowThreadProcessId(window,&process);
        if (process==GetCurrentProcessId()) return input_worker=window;
    }
    return NULL;
}
static LONGLONG ticks(void) { LARGE_INTEGER value; QueryPerformanceCounter(&value); return value.QuadPart; }
static instance_record *find(instance_record *records, LONG count, void *object) {
    LONG i;
    for (i=0; i<count; ++i) if (records[i].object == object) return &records[i];
    return NULL;
}
static instance_record *vacant(instance_record *records,LONG *count) {
    LONG i;
    for(i=0;i<*count;++i) if(!records[i].object) {
        memset(&records[i],0,sizeof(records[i])); return &records[i];
    }
    return *count<64 ? &records[(*count)++] : NULL;
}
static ULONG WINAPI release_device(void *object) {
    instance_record *item=find(devices,device_count,object);
    ULONG remaining=((release_fn)item->original[2])(object);
    if(!remaining) { HeapFree(GetProcessHeap(),0,item->copy); item->object=NULL; }
    return remaining;
}
static ULONG WINAPI release_input(void *object) {
    instance_record *item=find(inputs,input_count,object);
    ULONG remaining=((release_fn)item->original[2])(object);
    if(!remaining) { HeapFree(GetProcessHeap(),0,item->copy); item->object=NULL; }
    return remaining;
}
static void record(DWORD code, void *object, void *caller, HRESULT result,
                   DWORD value, DWORD polls, LONGLONG duration,
                   const BYTE *state, const BYTE *mapped) {
#ifndef NO_INPUT_TRACE
    LONG index = InterlockedIncrement(&reserved_count)-1;
    trace_record *entry;
    if (index < 0 || index >= RECORD_COUNT) return;
    entry = &ring[index];
    entry->time=ticks(); entry->thread=GetCurrentThreadId(); entry->code=code;
    entry->object=object; entry->caller=caller; entry->result=result;
    entry->value=value; entry->polls=polls; entry->duration=duration;
    if (state) memcpy(entry->state,state,8);
    if (mapped) memcpy(entry->mapped,mapped,4);
    InterlockedExchange(&entry->ready,1);
#endif
}
static HRESULT WINAPI trace_state(IDirectInputDeviceA *object,DWORD size,void *data) {
    instance_record *entry=find(devices,device_count,object);
    LONGLONG before=ticks(), after;
#ifdef RAW_INPUT_DRAIN
    HRESULT hr=fresh_raw_state(object,size,data);
#else
    HRESULT hr=((state_fn)entry->original[9])(object,size,data);
#endif
    BYTE state[8]={0}, mapped[4]={0};
    void *caller=__builtin_return_address(0);
    after=ticks();
#ifdef SYNC_LEGACY_RELEASES
    /* GetDeviceState's Poll forwards X11 events but can return before the
     * legacy worker applies them. After a game pause, cross that thread before
     * taking the snapshot the game will consume. Never hold device locks here. */
    if (SUCCEEDED(hr)) {
        HWND worker=find_input_worker();
        DWORD_PTR result;
        BOOL synced=worker && SendMessageTimeoutW(worker,WM_NULL,0,0,
                SMTO_ABORTIFHUNG|SMTO_BLOCK,20,&result);
        if (synced) hr=((state_fn)entry->original[9])(object,size,data);
        ++entry->sync_count;
        if(!synced || before-entry->previous > frequency.QuadPart/20 ||
                entry->sync_count<4 || !(entry->sync_count%200))
            record(10,object,caller,hr,synced,entry->polls,ticks()-after,NULL,NULL);
        after=ticks();
        entry->synchronized=after;
    }
#endif
    ++entry->polls;
    if (SUCCEEDED(hr) && size==256 && data) {
        BYTE *keys=data;
        const UINT offsets[8]={DIK_UP,DIK_RIGHT,DIK_DOWN,DIK_LEFT,DIK_NUMPAD8,DIK_NUMPAD6,DIK_NUMPAD2,DIK_NUMPAD4};
        UINT i;
        for(i=0;i<8;++i) state[i]=keys[offsets[i]];
        if (caller==(BYTE *)GetModuleHandleW(NULL)+0x2202e ||
            caller==(BYTE *)GetModuleHandleW(NULL)+0x2dbde) {
            BYTE *context=keys-0x186;
            mapped[0]=context[0x78+0x88]; mapped[1]=context[0x78+0x87];
            mapped[2]=context[0x78+0x89]; mapped[3]=context[0x78+0x86];
        }
    }
    if(entry->polls<8 || FAILED(hr) || memcmp(state,entry->last,8) ||
       after-entry->report > frequency.QuadPart/10) {
        record(3,object,caller,hr,size,entry->polls,after-before,state,mapped);
        entry->report=after;
        memcpy(entry->last,state,8);
    }
    entry->previous=after;
    return hr;
}
static HRESULT WINAPI trace_acquire(IDirectInputDeviceA *object) {
    instance_record *entry=find(devices,device_count,object);
    HRESULT hr=((simple_fn)entry->original[7])(object);
    record(4,object,__builtin_return_address(0),hr,0,entry->polls,0,NULL,NULL);
    return hr;
}
static HRESULT WINAPI trace_unacquire(IDirectInputDeviceA *object) {
    instance_record *entry=find(devices,device_count,object);
    HRESULT hr=((simple_fn)entry->original[8])(object);
    record(5,object,__builtin_return_address(0),hr,0,entry->polls,0,NULL,NULL);
    return hr;
}
static HRESULT WINAPI trace_coop(IDirectInputDeviceA *object,HWND window,DWORD flags) {
    instance_record *entry=find(devices,device_count,object);
    HRESULT hr=((coop_fn)entry->original[13])(object,window,flags);
    record(6,object,__builtin_return_address(0),hr,flags,0,0,NULL,NULL);
    return hr;
}
static HRESULT WINAPI trace_format(IDirectInputDeviceA *object,const DIDATAFORMAT *format) {
    instance_record *entry=find(devices,device_count,object);
    HRESULT hr=((format_fn)entry->original[11])(object,format);
    record(7,object,__builtin_return_address(0),hr,format ? format->dwDataSize : 0,0,0,NULL,NULL);
    return hr;
}
#ifdef RAW_INPUT_KEYBOARD
static HRESULT create_raw_keyboard(REFGUID guid,IDirectInputDeviceA **out,IUnknown *outer) {
    static HMODULE library;
    factory8_fn create8;
    IDirectInput8A *input8=NULL;
    HRESULT hr;
    if(!library) library=LoadLibraryW(L"dinput8.dll");
    create8=library ? (factory8_fn)GetProcAddress(library,"DirectInput8Create") : NULL;
    hr=create8 ? create8(GetModuleHandleW(NULL),0x0800,&IID_IDirectInput8A,(void **)&input8,NULL) : E_NOINTERFACE;
    if(SUCCEEDED(hr)) {
        hr=IDirectInput8_CreateDevice(input8,guid,(IDirectInputDevice8A **)out,outer);
        IDirectInput8_Release(input8);
    }
    return hr;
}
#endif
static HRESULT WINAPI trace_create(IDirectInputA *object,REFGUID guid,IDirectInputDeviceA **out,IUnknown *outer) {
    instance_record *entry=find(inputs,input_count,object);
    HRESULT hr;
    UINT slots=18;
#ifdef RAW_INPUT_KEYBOARD
    if(guid && IsEqualGUID(guid,&GUID_SysKeyboard)) {
        /* Keep the legacy factory and its version semantics. Only the keyboard
         * comes from DirectInput 8, whose first 18 device slots are ABI-compatible
         * with IDirectInputDeviceA. Wine selects raw input for this device.
         * No snapshots, presses or releases are synthesized. */
        hr=create_raw_keyboard(guid,out,outer);
        /* Keep the DLL loaded while its devices remain alive. Failure is exposed
         * rather than silently falling back and invalidating the comparison. */
        slots=32;
        record(11,object,__builtin_return_address(0),hr,0x0800,0,0,NULL,NULL);
    } else
#endif
        hr=((create_fn)entry->original[3])(object,guid,out,outer);
    record(8,object,__builtin_return_address(0),hr,guid ? guid->Data1 : 0,0,0,NULL,NULL);
#if !defined(NO_INPUT_TRACE) || defined(RAW_INPUT_DRAIN)
    if(SUCCEEDED(hr) && IsEqualGUID(guid,&GUID_SysKeyboard) && out && *out) {
        instance_record *item=vacant(devices,&device_count);
        if(!item) return hr;
        void **copy=HeapAlloc(GetProcessHeap(),0,slots*sizeof(void *));
        if(copy) {
            item->object=*out; item->original=*(void ***)*out;
            item->copy=copy;
            memcpy(copy,item->original,slots*sizeof(void *));
            copy[2]=(void *)release_device;
#ifdef RAW_INPUT_DRAIN
            copy[9]=(void *)fresh_raw_state;
#endif
#ifndef NO_INPUT_TRACE
            copy[7]=(void *)trace_acquire; copy[8]=(void *)trace_unacquire;
            copy[9]=(void *)trace_state; copy[11]=(void *)trace_format;
            copy[13]=(void *)trace_coop;
#endif
            *(void ***)*out=copy;
            record(2,*out,__builtin_return_address(0),hr,0,0,0,NULL,NULL);
        }
    }
#endif
    return hr;
}
static HRESULT WINAPI trace_factory(HINSTANCE module,DWORD version,IDirectInputA **out,IUnknown *outer) {
    HRESULT hr=original_factory(module,version,out,outer);
    if(SUCCEEDED(hr) && out && *out) {
        instance_record *item=vacant(inputs,&input_count);
        if(!item) return hr;
        void **copy=HeapAlloc(GetProcessHeap(),0,8*sizeof(void *));
        if(copy) {
            item->object=*out; item->original=*(void ***)*out;
            item->copy=copy;
            memcpy(copy,item->original,8*sizeof(void *));
            copy[3]=(void *)trace_create;
            copy[2]=(void *)release_input;
            *(void ***)*out=copy;
        }
    }
    record(1,out ? *out : NULL,__builtin_return_address(0),hr,version,0,0,NULL,NULL);
    return hr;
}
static BOOL install(void) {
    BYTE *image=(BYTE *)GetModuleHandleW(NULL);
    IMAGE_DOS_HEADER *dos=(void *)image;
    IMAGE_NT_HEADERS *nt=(void *)(image+dos->e_lfanew);
    IMAGE_IMPORT_DESCRIPTOR *imports=(void *)(image+nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT].VirtualAddress);
    for(;imports->Name;++imports) {
        IMAGE_THUNK_DATA *name,*address;
        if(_stricmp((char *)image+imports->Name,"DINPUT.dll")) continue;
        if(!imports->OriginalFirstThunk) return FALSE;
        name=(void *)(image+imports->OriginalFirstThunk);
        address=(void *)(image+imports->FirstThunk);
        for(;name->u1.AddressOfData;++name,++address) {
            IMAGE_IMPORT_BY_NAME *symbol;
            DWORD old;
            if(IMAGE_SNAP_BY_ORDINAL(name->u1.Ordinal)) continue;
            symbol=(void *)(image+name->u1.AddressOfData);
            if(strcmp((char *)symbol->Name,"DirectInputCreateA")) continue;
            if(!VirtualProtect(&address->u1.Function,sizeof(void *),PAGE_READWRITE,&old)) return FALSE;
            original_factory=(factory_fn)(uintptr_t)address->u1.Function;
            address->u1.Function=(uintptr_t)trace_factory;
            VirtualProtect(&address->u1.Function,sizeof(void *),old,&old);
            return TRUE;
        }
    }
    return FALSE;
}
static DWORD WINAPI writer(void *unused) {
    LONG index=0;
    (void)unused;
    while(index<RECORD_COUNT) {
        while(index<RECORD_COUNT && ring[index].ready) {
            trace_record *r=&ring[index++];
            fprintf(log_file,"t=%.3f code=%lu thread=%lu object=%p caller=%p hr=%08lx value=%lu polls=%lu call_ms=%.3f arrows=%02x,%02x,%02x,%02x numpad=%02x,%02x,%02x,%02x mapped_before=%u,%u,%u,%u\n",
                1000.0*(r->time-origin)/frequency.QuadPart,r->code,r->thread,r->object,r->caller,
                (unsigned long)r->result,r->value,r->polls,1000.0*r->duration/frequency.QuadPart,
                r->state[0],r->state[1],r->state[2],r->state[3],r->state[4],r->state[5],r->state[6],r->state[7],
                r->mapped[0],r->mapped[1],r->mapped[2],r->mapped[3]);
        }
        fflush(log_file);
        Sleep(100);
    }
    fprintf(log_file,"TRACE FULL: bounded recording stopped\n");
    fflush(log_file);
    return 0;
}
BOOL WINAPI DllMain(HINSTANCE module,DWORD reason,void *reserved) {
    WCHAR path[MAX_PATH],*slash;
    const char *(__cdecl *wine_version)(void);
    HANDLE thread;
    (void)reserved;
    if(reason!=DLL_PROCESS_ATTACH) return TRUE;
    trace_module=module;
    DisableThreadLibraryCalls(module);
#ifdef RAW_INPUT_DRAIN
    {
        char setting[8];
        if (GetEnvironmentVariableA("BAVISOFT_RAW_QUEUE",setting,sizeof(setting)) &&
            !_stricmp(setting,"0")) raw_one_packet=FALSE;
    }
#endif
#ifdef NO_INPUT_TRACE
    /* Production raw-backend path has no pulse timers, input telemetry thread
     * or trace file. RAW_INPUT_DRAIN optionally synchronizes
     * actual queued raw packets before reading. Native Windows is unchanged. */
    wine_version=(void *)GetProcAddress(GetModuleHandleW(L"ntdll.dll"),"wine_get_version");
    if(wine_version) install();
    return TRUE;
#endif
    QueryPerformanceFrequency(&frequency); origin=ticks();
    GetModuleFileNameW(trace_module,path,MAX_PATH);
    slash=wcsrchr(path,L'\\'); if(!slash) return TRUE;
    wcscpy(slash+1,L"grizzly-dinput-trace.log");
    log_file=_wfopen(path,L"w"); if(!log_file) return TRUE;
    wine_version=(void *)GetProcAddress(GetModuleHandleW(L"ntdll.dll"),"wine_get_version");
    if(!wine_version) { fclose(log_file); log_file=NULL; return TRUE; }
    fprintf(log_file,"platform=%s codes=1:create,2:keyboard,3:snapshot,4:acquire,5:unacquire,6:cooperative,7:format\n",
        wine_version ? wine_version() : "Windows");
    fprintf(log_file,"installed=%d\n",install()); fflush(log_file);
#ifdef RAW_INPUT_KEYBOARD
#ifdef RAW_INPUT_DRAIN
    fprintf(log_file,"candidate=build88-raw-queue-capture codes=11:raw-keyboard-create,12:raw-packet(value=vk|flags<<16,polls=scan,call_ms=event_age)\n");
#else
    fprintf(log_file,"candidate=directinput8-raw-keyboard-v7 codes=11:raw-keyboard-create\n");
#endif
#else
    fprintf(log_file,"candidate=sync-legacy-every-poll-v6\n");
#endif
    fflush(log_file);
    thread=CreateThread(NULL,0,writer,NULL,0,NULL);
    if(thread) CloseHandle(thread);
    return TRUE;
}

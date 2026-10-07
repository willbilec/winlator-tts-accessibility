#define RAW_INPUT_KEYBOARD
#define RAW_INPUT_DRAIN
#define NO_INPUT_TRACE
#include "grizzly-input.c"
#include <assert.h>

static HANDLE ready;
static volatile LONG cached,packet_count;
static HRESULT result=S_OK;
static LRESULT CALLBACK mock_worker(HWND window,UINT message,WPARAM wparam,LPARAM lparam) {
    if(message==WM_INPUT) { InterlockedExchange(&cached,lparam); InterlockedIncrement(&packet_count); return 0; }
    if(message==WM_APP+99) { PostQuitMessage(0); return 0; }
    return DefWindowProcW(window,message,wparam,lparam);
}
static DWORD WINAPI worker(void *unused) {
    WNDCLASSW cls={0}; MSG message;
    (void)unused;
    cls.lpfnWndProc=drain_raw_messages; cls.hInstance=GetModuleHandleW(NULL); cls.lpszClassName=L"RawDrainRegression";
    RegisterClassW(&cls);
    raw_window_proc=mock_worker;
    raw_fence_message=RegisterWindowMessageW(L"Winlator.Bavisoft.RawKeyboardFence.98D7E101");
    raw_worker=CreateWindowW(cls.lpszClassName,L"",0,0,0,0,0,HWND_MESSAGE,NULL,cls.hInstance,NULL);
    SetEvent(ready);
    while(GetMessageW(&message,NULL,0,0)>0) DispatchMessageW(&message);
    DestroyWindow(raw_worker);
    return 0;
}
static HRESULT WINAPI cached_state(IDirectInputDeviceA *object,DWORD size,void *data) {
    (void)object;
    if(FAILED(result)) return result;
    memset(data,0,size); ((BYTE *)data)[DIK_RIGHT]=(BYTE)cached;
    return result;
}
int main(void) {
    void *vtable[32]={0}; void **object=vtable;
    BYTE data[256]; HANDLE thread; HWND window;
    ready=CreateEventW(NULL,TRUE,FALSE,NULL);
    thread=CreateThread(NULL,0,worker,NULL,0,NULL);
    assert(WaitForSingleObject(ready,2000)==WAIT_OBJECT_0 && raw_worker);
    vtable[9]=cached_state; devices[0].object=&object; devices[0].original=vtable; device_count=1;
    PostMessageW(raw_worker,WM_INPUT,0,0x80);
    assert(fresh_raw_state((void *)&object,sizeof(data),data)==S_OK && data[DIK_RIGHT]==0x80);
    PostMessageW(raw_worker,WM_INPUT,0,0);
    assert(fresh_raw_state((void *)&object,sizeof(data),data)==S_OK && data[DIK_RIGHT]==0);
    assert(packet_count==2);
    InterlockedExchange(&cached,0x80);
    assert(fresh_raw_state((void *)&object,sizeof(data),data)==S_OK && data[DIK_RIGHT]==0x80);
    result=DIERR_NOTACQUIRED; memset(data,0xa5,sizeof(data));
    assert(fresh_raw_state((void *)&object,sizeof(data),data)==DIERR_NOTACQUIRED && data[DIK_RIGHT]==0xa5);
    result=S_OK; window=raw_worker; raw_worker=NULL;
    assert(fresh_raw_state((void *)&object,sizeof(data),data)==S_OK && data[DIK_RIGHT]==0x80);
    PostMessageW(window,WM_APP+99,0,0); WaitForSingleObject(thread,2000);
    CloseHandle(thread); CloseHandle(ready);
    puts("PASS: queued down/up, completed tap not replayed, holds, original errors and missing-worker fallback");
    return 0;
}

#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0601
#include <winsock2.h>
#include <windows.h>
#include <commctrl.h>
#include <shellapi.h>
#include <shlobj.h>
#include <objbase.h>
#include <stdio.h>
#include <stdlib.h>
#include <wchar.h>
#include <wctype.h>
#include <stdint.h>

/* Separate guest asset: the speech runtime and games are never modified here. */
#define CAP 32768
#define DONE (WM_APP + 1)
#define PROGRESS (WM_APP + 2)
#define CONFIG L"C:\\WinlatorFileBrowser\\session.ini"
#define STATE L"C:\\WinlatorFileBrowser\\position.ini"
typedef DWORD (__stdcall *SpeakFn)(const WCHAR *);
typedef DWORD (__stdcall *CancelFn)(void);
typedef enum { PATH_ENTRY, FAVORITES_ENTRY, EXIT_ENTRY } EntryKind;
typedef struct { WCHAR *name; DWORD attributes; ULONGLONG size; EntryKind kind; } Entry;
typedef enum { BROWSE, ACTIONS, CONFIRM, TEXT, INFO } Screen;
typedef enum { SCAN, COPY, MOVE, REMOVE, RENAME, MKDIR, LAUNCH, SHORTCUT, ASSOC } Operation;
typedef struct {
    Operation op;
    WCHAR source[CAP], target[CAP], selected[CAP], message[2048];
    Entry *entries; size_t count; DWORD error;
    BOOL createIfMissing;
} Job;
static HWND window, list, statusLabel;
static SpeakFn speak; static CancelFn cancelSpeech;
static FILE *logFile;
static Screen screen = BROWSE;
static Entry *entries; static size_t count;
static WCHAR folder[CAP], selectedName[CAP], clipboard[CAP], edit[CAP], search[256];
static WCHAR favoritesFolder[CAP];
static BOOL cut, busy, foreground = TRUE;
static LONG cancelled;
static Operation pending;
static WCHAR pendingSource[CAP], pendingTarget[CAP];
static ULONGLONG lastType;
static int savedSelection;
static const WCHAR *actions[] = {L"Open", L"Copy", L"Cut", L"Paste", L"Rename", L"Delete permanently",
    L"New folder", L"Go to path", L"Create Winlator game shortcut", L"Properties", L"Refresh", L"Help", L"Exit browser", L"Back"};
static const WCHAR alphabet[] = L"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 ._-,()[]!@#$%&+=';{}~`^\\:/";
static int pickerCount;
static Operation activeOperation;
static ULONGLONG lastProgress;
static unsigned long completedFiles;
static WCHAR progressName[CAP];
static WCHAR completionNotice[2048];
static int selection(void);
static BOOL createInterface(HINSTANCE instance,BOOL visible);

static BOOL put(WCHAR *out, const WCHAR *value) {
    if (wcslen(value) >= CAP) { SetLastError(ERROR_FILENAME_EXCED_RANGE); return FALSE; }
    wcscpy(out, value); return TRUE;
}
static BOOL join(WCHAR *out, const WCHAR *parent, const WCHAR *name) {
    size_t n = wcslen(parent), m = wcslen(name);
    if (n + m + 2 >= CAP) { SetLastError(ERROR_FILENAME_EXCED_RANGE); return FALSE; }
    wcscpy(out, parent);
    if (n && out[n-1] != L'\\') out[n++] = L'\\';
    wcscpy(out+n, name); return TRUE;
}
static const WCHAR *basename(const WCHAR *path) {
    const WCHAR *p = wcsrchr(path, L'\\'); return p ? p+1 : path;
}
static void errorText(DWORD error, WCHAR *out, size_t capacity) {
    if (!FormatMessageW(FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
            NULL, error, 0, out, (DWORD)capacity, NULL))
        swprintf(out, capacity, L"Windows error %lu", error);
}
static void say(const WCHAR *text) {
    SetWindowTextW(statusLabel, text);
    if (cancelSpeech && foreground) cancelSpeech();
    DWORD result = foreground && speak ? speak(text) : ERROR_NOT_READY;
    if (logFile) { fwprintf(logFile, L"speech status=%lu text=%ls\n", result, text); fflush(logFile); }
}
static void freeEntries(Entry *items, size_t n) {
    for (size_t i=0; i<n; i++) free(items[i].name);
    free(items);
}
static int compareEntries(const void *a, const void *b) {
    const Entry *x=a, *y=b;
    BOOL xd = !!(x->attributes & FILE_ATTRIBUTE_DIRECTORY), yd = !!(y->attributes & FILE_ATTRIBUTE_DIRECTORY);
    if (xd != yd) return xd ? -1 : 1;
    return _wcsicmp(x->name, y->name);
}
static void progress(const WCHAR *text) {
    ULONGLONG now=GetTickCount64();
    if(window && now-lastProgress>=3000) {
        WCHAR *copy=_wcsdup(text);
        if(copy && !PostMessageW(window,PROGRESS,0,(LPARAM)copy))free(copy);
        lastProgress=now;
    }
}
static DWORD addEntry(Job *job, const WCHAR *name, DWORD attributes, ULONGLONG size) {
    Entry *more = realloc(job->entries, (job->count+1)*sizeof(Entry));
    if (!more) return ERROR_NOT_ENOUGH_MEMORY;
    job->entries = more;
    WCHAR *copy = _wcsdup(name);
    if (!copy) return ERROR_NOT_ENOUGH_MEMORY;
    job->entries[job->count++] = (Entry){copy, attributes, size, PATH_ENTRY}; return 0;
}
static DWORD scan(Job *job) {
    if (!*job->target) {
        DWORD drives = GetLogicalDrives();
        if (!drives) return GetLastError();
        DWORD error = addEntry(job, L"Favorites", FILE_ATTRIBUTE_DIRECTORY, 0);
        if (error) return error;
        job->entries[0].kind = FAVORITES_ENTRY;
        for (int i=0; i<26; i++) if (drives & (1u<<i)) {
            WCHAR name[] = L"A:\\"; name[0] += i;
            DWORD error = addEntry(job, name, FILE_ATTRIBUTE_DIRECTORY, 0);
            if (error) return error;
        }
        error = addEntry(job, L"Exit", 0, 0);
        if (!error) job->entries[job->count-1].kind = EXIT_ENTRY;
        return error;
    }
    DWORD attr = GetFileAttributesW(job->target);
    if (attr == INVALID_FILE_ATTRIBUTES && job->createIfMissing) {
        if (!CreateDirectoryW(job->target, NULL) && GetLastError() != ERROR_ALREADY_EXISTS) return GetLastError();
        attr = GetFileAttributesW(job->target);
    }
    if (attr == INVALID_FILE_ATTRIBUTES) return GetLastError();
    if (!(attr & FILE_ATTRIBUTE_DIRECTORY)) return ERROR_DIRECTORY;
    WCHAR mask[CAP]; if (!join(mask, job->target, L"*")) return GetLastError();
    WIN32_FIND_DATAW data;
    HANDLE find = FindFirstFileW(mask, &data);
    if (find == INVALID_HANDLE_VALUE) return GetLastError() == ERROR_FILE_NOT_FOUND ? 0 : GetLastError();
    DWORD error = 0;
    do {
        if (InterlockedCompareExchange(&cancelled, 0, 0)) { error=ERROR_CANCELLED; break; }
        if (!wcscmp(data.cFileName,L".") || !wcscmp(data.cFileName,L"..")) continue;
        error=addEntry(job,data.cFileName,data.dwFileAttributes,((ULONGLONG)data.nFileSizeHigh<<32)|data.nFileSizeLow);
        if (error) break;
        WCHAR update[100];swprintf(update,100,L"Scanning. %llu items found.",(unsigned long long)job->count);progress(update);
    } while (FindNextFileW(find,&data));
    if (!error && GetLastError()!=ERROR_NO_MORE_FILES) error=GetLastError();
    FindClose(find);
    if (!error && job->count) qsort(job->entries,job->count,sizeof(Entry),compareEntries);
    return error;
}
/* Resolve existing paths through junctions before checking directory ancestry. */
static DWORD finalPath(const WCHAR *path, WCHAR *out) {
    HANDLE file=CreateFileW(path,0,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,NULL,OPEN_EXISTING,FILE_FLAG_BACKUP_SEMANTICS,NULL);
    if (file==INVALID_HANDLE_VALUE) return GetLastError();
    DWORD n=GetFinalPathNameByHandleW(file,out,CAP,FILE_NAME_NORMALIZED);
    DWORD error=n && n<CAP ? 0 : (n>=CAP ? ERROR_FILENAME_EXCED_RANGE : GetLastError());
    CloseHandle(file); return error;
}
static BOOL descendant(const WCHAR *parent,const WCHAR *child) {
    size_t n=wcslen(parent); while(n && parent[n-1]==L'\\') --n;
    return !_wcsnicmp(parent,child,n) && (!child[n] || child[n]==L'\\');
}
static DWORD safeDestination(const WCHAR *source,const WCHAR *target) {
    WCHAR from[CAP], to[CAP], parent[CAP], resolved[CAP];
    DWORD error=finalPath(source,from); if(error) return error;
    if (GetFileAttributesW(target)!=INVALID_FILE_ATTRIBUTES) {
        error=finalPath(target,to); if(error) return error;
    } else {
        if(!put(parent,target)) return GetLastError();
        WCHAR *slash=wcsrchr(parent,L'\\'); if(!slash) return ERROR_INVALID_NAME;
        if(slash==parent+2) slash[1]=0; else *slash=0;
        error=finalPath(parent,resolved); if(error) return error;
        if(!join(to,resolved,basename(target))) return GetLastError();
    }
    return descendant(from,to) ? ERROR_INVALID_PARAMETER : 0;
}
static DWORD CALLBACK copyProgress(LARGE_INTEGER a,LARGE_INTEGER b,LARGE_INTEGER c,LARGE_INTEGER d,
    DWORD stream,DWORD reason,HANDLE source,HANDLE destination,LPVOID context) {
    (void)a;(void)c;(void)d;(void)stream;(void)reason;(void)source;(void)destination;(void)context;
    WCHAR update[1024];swprintf(update,1024,L"Copying %.700ls. %llu bytes transferred.",progressName,(unsigned long long)b.QuadPart);progress(update);
    return InterlockedCompareExchange(&cancelled,0,0) ? PROGRESS_CANCEL : PROGRESS_CONTINUE;
}
/* Never recurse through reparse points: avoids cycles and unintended deletion. */
static DWORD treeOperation(const WCHAR *source,const WCHAR *target,BOOL deleting) {
    if(InterlockedCompareExchange(&cancelled,0,0)) return ERROR_CANCELLED;
    DWORD attr=GetFileAttributesW(source);
    if(attr==INVALID_FILE_ATTRIBUTES) return GetLastError();
    if(attr & FILE_ATTRIBUTE_REPARSE_POINT) return ERROR_NOT_SUPPORTED;
    if(!(attr & FILE_ATTRIBUTE_DIRECTORY)) {
        ++completedFiles;
        WCHAR update[1024];swprintf(update,1024,L"%ls %.700ls. %lu files processed.",deleting?L"Deleting":L"Copying",basename(source),completedFiles);progress(update);
        if(deleting) return DeleteFileW(source) ? 0 : GetLastError();
        put(progressName,basename(source));
        DWORD destAttr=GetFileAttributesW(target);
        if(destAttr!=INVALID_FILE_ATTRIBUTES && (destAttr & FILE_ATTRIBUTE_REPARSE_POINT)) return ERROR_NOT_SUPPORTED;
        return CopyFileExW(source,target,copyProgress,NULL,NULL,0) ? 0 : GetLastError();
    }
    if(!deleting && !CreateDirectoryW(target,NULL)) {
        DWORD error=GetLastError(), destAttr=GetFileAttributesW(target);
        if(error!=ERROR_ALREADY_EXISTS) return error;
        if(destAttr==INVALID_FILE_ATTRIBUTES || !(destAttr&FILE_ATTRIBUTE_DIRECTORY)) return ERROR_DIRECTORY;
        if(destAttr&FILE_ATTRIBUTE_REPARSE_POINT) return ERROR_NOT_SUPPORTED;
    }
    struct Frame { WCHAR mask[CAP],child[CAP],dest[CAP]; WIN32_FIND_DATAW data; };
    struct Frame *frame=calloc(1,sizeof(*frame));
    if(!frame)return ERROR_NOT_ENOUGH_MEMORY;
    if(!join(frame->mask,source,L"*")){DWORD error=GetLastError();free(frame);return error;}
    HANDLE find=FindFirstFileW(frame->mask,&frame->data); DWORD error=0;
    if(find!=INVALID_HANDLE_VALUE) {
        do {
            if(!wcscmp(frame->data.cFileName,L".") || !wcscmp(frame->data.cFileName,L"..")) continue;
            if(!join(frame->child,source,frame->data.cFileName) || (!deleting && !join(frame->dest,target,frame->data.cFileName))) {error=GetLastError();break;}
            error=treeOperation(frame->child,deleting ? NULL : frame->dest,deleting); if(error) break;
        } while(FindNextFileW(find,&frame->data));
        if(!error && GetLastError()!=ERROR_NO_MORE_FILES) error=GetLastError();
        FindClose(find);
    } else if(GetLastError()!=ERROR_FILE_NOT_FOUND) error=GetLastError();
    if(!error && deleting && !RemoveDirectoryW(source)) error=GetLastError();
    free(frame);
    return error;
}
static BOOL transfer(SOCKET socket,char *data,int length,BOOL sending) {
    while(length>0) { int n=sending?send(socket,data,length,0):recv(socket,data,length,0); if(n<=0)return FALSE;data+=n;length-=n; }
    return TRUE;
}
static BOOL number(SOCKET socket,uint32_t *value,BOOL sending) {
    uint32_t wire=sending?htonl(*value):0;
    if(!transfer(socket,(char *)&wire,4,sending))return FALSE;
    if(!sending)*value=ntohl(wire);
    return TRUE;
}
static BOOL sendString(SOCKET socket,const WCHAR *value) {
    int n=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,value,-1,NULL,0,NULL,NULL);
    if(n<=0 || n>131072)return FALSE;
    char *utf8=malloc((size_t)n); if(!utf8)return FALSE;
    WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,value,-1,utf8,n,NULL,NULL);
    uint32_t length=(uint32_t)n-1;
    BOOL ok=number(socket,&length,TRUE)&&transfer(socket,utf8,(int)length,TRUE);free(utf8);return ok;
}
static BOOL receiveString(SOCKET socket,WCHAR *out,int capacity) {
    uint32_t length=0;if(!number(socket,&length,FALSE)||length>131072)return FALSE;
    char *utf8=malloc((size_t)length+1);if(!utf8)return FALSE;
    BOOL ok=transfer(socket,utf8,(int)length,FALSE);utf8[length]=0;
    if(ok)ok=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,utf8,-1,out,capacity)>0;
    free(utf8);return ok;
}
static DWORD requestAt(Job *job,const WCHAR *configuration) {
    WCHAR token[128];GetPrivateProfileStringW(L"session",L"token",L"",token,128,configuration);
    UINT port=GetPrivateProfileIntW(L"session",L"port",0,configuration);
    if(!port || !*token)return ERROR_NOT_READY;
    WSADATA data;if(WSAStartup(MAKEWORD(2,2),&data))return ERROR_NOT_READY;
    SOCKET peer=socket(AF_INET,SOCK_STREAM,IPPROTO_TCP);
    if(peer==INVALID_SOCKET){WSACleanup();return ERROR_NOT_READY;}
    DWORD timeout=15000;setsockopt(peer,SOL_SOCKET,SO_RCVTIMEO,(char *)&timeout,sizeof(timeout));
    setsockopt(peer,SOL_SOCKET,SO_SNDTIMEO,(char *)&timeout,sizeof(timeout));
    struct sockaddr_in address={0};address.sin_family=AF_INET;address.sin_port=htons((u_short)port);address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    WCHAR id[100];swprintf(id,100,L"%lu-%llu",GetCurrentProcessId(),GetTickCount64());
    uint32_t magic=0x57464231, status=1;
    WCHAR returnedId[100];
    BOOL ok=connect(peer,(struct sockaddr *)&address,sizeof(address))==0 && number(peer,&magic,TRUE) &&
        sendString(peer,token)&&sendString(peer,id)&&sendString(peer,job->op==LAUNCH?L"launch":L"shortcut")&&
        sendString(peer,job->source)&&receiveString(peer,returnedId,100)&&!wcscmp(id,returnedId)&&
        number(peer,&status,FALSE)&&receiveString(peer,job->message,2048);
    closesocket(peer);WSACleanup();return ok ? (status?ERROR_GEN_FAILURE:0):ERROR_CONNECTION_ABORTED;
}
static DWORD WINAPI work(void *context) {
    Job *job=context;
    switch(job->op) {
    case SCAN:job->error=scan(job);break;
    case COPY:case MOVE:
        job->error=safeDestination(job->source,job->target);
        if(!job->error)job->error=treeOperation(job->source,job->target,FALSE);
        if(!job->error && job->op==MOVE)job->error=treeOperation(job->source,NULL,TRUE);
        break;
    case REMOVE:job->error=treeOperation(job->source,NULL,TRUE);break;
    case RENAME:job->error=MoveFileW(job->source,job->target)?0:GetLastError();break;
    case MKDIR:job->error=CreateDirectoryW(job->target,NULL)?0:GetLastError();break;
    case LAUNCH:case SHORTCUT:job->error=requestAt(job,CONFIG);break;
    case ASSOC:{
        HRESULT initialized=CoInitializeEx(NULL,COINIT_APARTMENTTHREADED);
        HINSTANCE result=ShellExecuteW(NULL,L"open",job->source,NULL,NULL,SW_SHOWNORMAL);
        job->error=(INT_PTR)result>32?0:ERROR_NO_ASSOCIATION;
        if(SUCCEEDED(initialized))CoUninitialize();
        break;
    }}
    if(window)PostMessageW(window,DONE,0,(LPARAM)job);
    else {freeEntries(job->entries,job->count);free(job);}
    return 0;
}
static void startJob(Operation op,const WCHAR *source,const WCHAR *target,const WCHAR *desiredSelection) {
    if(busy)return;
    if(op==SCAN && screen==BROWSE)savedSelection=selection();
    Job *job=calloc(1,sizeof(Job));if(!job){say(L"Not enough memory.");return;}
    job->op=op;
    job->createIfMissing=op==SCAN && target && *favoritesFolder && !_wcsicmp(target,favoritesFolder);
    if(!put(job->source,source?source:L"")||!put(job->target,target?target:L"")||!put(job->selected,desiredSelection?desiredSelection:L"")) {free(job);say(L"Path is too long.");return;}
    busy=TRUE;activeOperation=op;lastProgress=GetTickCount64();completedFiles=0;InterlockedExchange(&cancelled,0);
    say(op==SCAN?L"Loading folder. Left cancels.":L"Working. Left cancels file operations.");
    HANDLE thread=CreateThread(NULL,0,work,job,0,NULL);
    if(thread)CloseHandle(thread);else {busy=FALSE;free(job);say(L"Could not start operation.");}
}
static int selection(void) { LRESULT index=SendMessageW(list,LB_GETCURSEL,0,0);return index==LB_ERR?-1:(int)index; }
static BOOL selectedPath(WCHAR *out) {
    int i=selection();if(i<0 || (size_t)i>=count)return FALSE;
    if(entries[i].kind==EXIT_ENTRY)return FALSE;
    if(entries[i].kind==FAVORITES_ENTRY)return *favoritesFolder && put(out,favoritesFolder);
    return *folder?join(out,folder,entries[i].name):put(out,entries[i].name);
}
static const WCHAR *entryType(const Entry *item) {
    if(item->kind==EXIT_ENTRY)return L"action";
    if(item->kind==FAVORITES_ENTRY)return L"folder";
    if(!*folder)return L"drive";
    return item->attributes&FILE_ATTRIBUTE_DIRECTORY?L"folder":L"file";
}
static void announceSelection(void) {
    int i=selection();if(i<0)return;
    WCHAR text[2048];
    if(screen==BROWSE) {
        if((size_t)i>=count)return;
        swprintf(text,2048,L"%.1500ls, %ls, %d of %llu",entries[i].name,
            entryType(&entries[i]),i+1,(unsigned long long)count);
    } else {
        WCHAR item[512];SendMessageW(list,LB_GETTEXT,(WPARAM)i,(LPARAM)item);
        swprintf(text,2048,L"%ls",item);
    }
    say(text);
}
static void populate(void) {
    screen=BROWSE;SendMessageW(list,LB_RESETCONTENT,0,0);
    int restore=0;
    for(size_t i=0;i<count;i++) {
        SendMessageW(list,LB_ADDSTRING,0,(LPARAM)entries[i].name);
        if(!wcscmp(entries[i].name,selectedName))restore=(int)i;
    }
    if(count)SendMessageW(list,LB_SETCURSEL,(WPARAM)restore,0);
    WCHAR title[1024];swprintf(title,1024,L"Speaking File Browser - %.900ls",*folder?folder:L"Drives");SetWindowTextW(window,title);
    SetFocus(list);
}
static void menu(Screen mode,const WCHAR **items,int n,int initial) {
    if(screen==BROWSE)savedSelection=selection();
    screen=mode;SendMessageW(list,LB_RESETCONTENT,0,0);
    for(int i=0;i<n;i++)SendMessageW(list,LB_ADDSTRING,0,(LPARAM)items[i]);
    SendMessageW(list,LB_SETCURSEL,(WPARAM)initial,0);SetFocus(list);
}
static void backToBrowser(void) {
    if(savedSelection>=0 && (size_t)savedSelection<count)put(selectedName,entries[savedSelection].name);
    populate();if(count)announceSelection();else say(L"Empty folder. Right opens actions.");
}
static void savePosition(void) {
    int i=screen==BROWSE?selection():savedSelection;
    WritePrivateProfileStringW(L"position",L"folder",folder,STATE);
    WritePrivateProfileStringW(L"position",L"selection",i>=0 && (size_t)i<count?entries[i].name:L"",STATE);
}
static void refresh(void) {
    if(screen==BROWSE){int i=selection();if(i>=0 && (size_t)i<count)put(selectedName,entries[i].name);}
    startJob(SCAN,NULL,folder,selectedName);
}
static void up(void) {
    if(!*folder){say(L"Already at drives.");return;}
    if(*favoritesFolder && !_wcsicmp(folder,favoritesFolder)) {startJob(SCAN,NULL,L"",L"Favorites");return;}
    WCHAR parent[CAP], old[CAP];put(parent,folder);
    size_t n=wcslen(parent);while(n>3 && parent[n-1]==L'\\')parent[--n]=0;
    put(old,basename(parent));WCHAR *slash=wcsrchr(parent,L'\\');
    if(n<=3) {put(old,folder);*parent=0;} else if(slash==parent+2)slash[1]=0;else if(slash)*slash=0;
    startJob(SCAN,NULL,parent,old);
}
static void showInfo(const WCHAR *text) {
    const WCHAR *items[]={L"Repeat information",L"Back"};menu(INFO,items,2,1);
    put(edit,text);say(text);
}
static BOOL nameValid(const WCHAR *name) {
    size_t n=wcslen(name);if(!n || n>255 || name[n-1]==L'.' || name[n-1]==L' ' || !wcscmp(name,L".") || !wcscmp(name,L".."))return FALSE;
    for(size_t i=0;i<n;i++)if(name[i]<32 || wcschr(L"<>:\"/\\|?*",name[i]))return FALSE;
    WCHAR stem[256];wcsncpy(stem,name,255);stem[255]=0;WCHAR *dot=wcschr(stem,L'.');if(dot)*dot=0;
    if(!_wcsicmp(stem,L"CON")||!_wcsicmp(stem,L"PRN")||!_wcsicmp(stem,L"AUX")||!_wcsicmp(stem,L"NUL"))return FALSE;
    if(wcslen(stem)==4 && (!_wcsnicmp(stem,L"COM",3)||!_wcsnicmp(stem,L"LPT",3)) && stem[3]>=L'1' && stem[3]<=L'9')return FALSE;
    return TRUE;
}
static void confirm(Operation op,const WCHAR *source,const WCHAR *target,const WCHAR *message) {
    pending=op;put(pendingSource,source?source:L"");put(pendingTarget,target?target:L"");
    const WCHAR *items[]={L"Cancel",L"Confirm"};menu(CONFIRM,items,2,0);say(message);
}
static void textEntry(Operation op,const WCHAR *source,const WCHAR *initial) {
    pending=op;put(pendingSource,source?source:L"");put(edit,initial?initial:L"");
    if(screen==BROWSE)savedSelection=selection();
    screen=TEXT;SendMessageW(list,LB_RESETCONTENT,0,0);
    pickerCount=(int)wcslen(alphabet);
    for(int i=0;i<pickerCount;i++) {
        WCHAR item[32]={alphabet[i],0};
        if(alphabet[i]>=L'A' && alphabet[i]<=L'Z')swprintf(item,32,L"Uppercase %lc",alphabet[i]);
        const WCHAR *punctuation=L" ._-,()[]!@#$%&+=';{}~`^\\:/";
        const WCHAR *names[]={L"Space",L"Period",L"Underscore",L"Hyphen",L"Comma",L"Left parenthesis",L"Right parenthesis",L"Left bracket",L"Right bracket",L"Exclamation mark",L"At sign",L"Hash",L"Dollar",L"Percent",L"Ampersand",L"Plus",L"Equals",L"Apostrophe",L"Semicolon",L"Left brace",L"Right brace",L"Tilde",L"Backtick",L"Caret",L"Backslash",L"Colon",L"Slash"};
        const WCHAR *symbol=wcschr(punctuation,alphabet[i]);if(symbol)wcscpy(item,names[symbol-punctuation]);
        SendMessageW(list,LB_ADDSTRING,0,(LPARAM)item);
    }
    const WCHAR *special[]={L"Backspace",L"Read text",L"Clear text",L"Done",L"Cancel"};
    for(int i=0;i<5;i++)SendMessageW(list,LB_ADDSTRING,0,(LPARAM)special[i]);
    SendMessageW(list,LB_SETCURSEL,(WPARAM)(pickerCount+3),0);SetFocus(list);
    WCHAR text[2048];swprintf(text,2048,L"%ls. Text: %.1500ls. Type or choose characters with arrows and Enter. Done saves; Cancel returns.",
        op==RENAME?L"Rename":op==MKDIR?L"New folder":L"Go to path",*edit?edit:L"empty");say(text);
}
static void finishText(void) {
    if(pending==SCAN) {
        WCHAR full[CAP];DWORD n=GetFullPathNameW(edit,CAP,full,NULL);
        if(!*edit || !n || n>=CAP || !(wcslen(edit)>=3 && edit[1]==L':' && (edit[2]==L'\\'||edit[2]==L'/'))) {say(L"Enter an absolute Windows drive path, such as C colon backslash.");return;}
        startJob(SCAN,NULL,full,NULL);return;
    }
    if(!nameValid(edit)){say(L"Invalid name. Choose a name without reserved characters or a trailing dot or space.");return;}
    WCHAR target[CAP];if(!join(target,folder,edit)){say(L"Path too long.");return;}
    startJob(pending,pendingSource,target,edit);
}
static void openSelected(void) {
    int i=selection();
    if(i>=0 && (size_t)i<count && entries[i].kind==EXIT_ENTRY){PostMessageW(window,WM_CLOSE,0,0);return;}
    WCHAR path[CAP];if(!selectedPath(path)){say(L"No item selected.");return;}
    if(entries[i].attributes&FILE_ATTRIBUTE_DIRECTORY){startJob(SCAN,NULL,path,NULL);return;}
    const WCHAR *ext=wcsrchr(path,L'.');
    BOOL executable=ext && (!_wcsicmp(ext,L".exe")||!_wcsicmp(ext,L".msi")||!_wcsicmp(ext,L".bat")||!_wcsicmp(ext,L".cmd")||!_wcsicmp(ext,L".lnk"));
    if(executable)savePosition();
    startJob(executable?LAUNCH:ASSOC,path,NULL,NULL);
}
static void action(int index) {
    WCHAR path[CAP]=L"";
    if(savedSelection>=0 && (size_t)savedSelection<count)
        {Entry *item=&entries[savedSelection];
         if(item->kind==FAVORITES_ENTRY)put(path,favoritesFolder);
         else if(item->kind!=EXIT_ENTRY){if(*folder)join(path,folder,item->name);else put(path,item->name);}}
    if((index==1||index==2||index==4||index==5||index==8) && (!*folder || !*path)) {say(L"Choose a file or folder inside a drive first.");return;}
    switch(index) {
    case 0:backToBrowser();openSelected();break;
    case 1:case 2:put(clipboard,path);cut=index==2;say(cut?L"Cut selected. Choose a destination and Paste.":L"Copied to browser clipboard. Choose a destination and Paste.");break;
    case 3:{
        if(!*clipboard || !*folder){say(L"Copy or cut an item, then enter a destination folder.");break;}
        WCHAR dest[CAP];if(!join(dest,folder,basename(clipboard))){say(L"Path too long.");break;}
        DWORD error=safeDestination(clipboard,dest);if(error){say(L"Cannot paste into the same item or into its descendants, or the source is unavailable.");break;}
        if(GetFileAttributesW(dest)!=INVALID_FILE_ATTRIBUTES) {
            WCHAR text[2048];swprintf(text,2048,L"Replace matching destination files in %.1500ls? Cancel selected.",dest);
            confirm(cut?MOVE:COPY,clipboard,dest,text);
        } else startJob(cut?MOVE:COPY,clipboard,dest,NULL);
        break;}
    case 4:textEntry(RENAME,path,basename(path));break;
    case 5:{WCHAR text[2048];swprintf(text,2048,L"Permanently delete %.1500ls and its contents? This cannot be undone. Cancel selected.",basename(path));confirm(REMOVE,path,NULL,text);break;}
    case 6:if(*folder)textEntry(MKDIR,NULL,L"");else say(L"Enter a drive first.");break;
    case 7:textEntry(SCAN,NULL,folder);break;
    case 8:{const WCHAR *ext=wcsrchr(path,L'.');
        if(!ext || (_wcsicmp(ext,L".exe") && _wcsicmp(ext,L".lnk"))) {say(L"Choose an executable or Windows shortcut.");break;}
        startJob(SHORTCUT,path,NULL,NULL);break;}
    case 9:if(*path){WCHAR text[2048];Entry *e=&entries[savedSelection];swprintf(text,2048,L"%.1500ls. %ls. Size %llu bytes.%ls",path,
        e->attributes&FILE_ATTRIBUTE_DIRECTORY?L"Folder":L"File",e->size,e->attributes&FILE_ATTRIBUTE_REPARSE_POINT?L" Link; recursive operations are unavailable.":L"");showInfo(text);}else say(L"No item selected.");break;
    case 10:backToBrowser();refresh();break;
    case 11:showInfo(L"Speaking File Browser. Up and Down select. Enter opens. Left goes back. Right opens actions. In menus, Left or Back returns. Text entry has a character picker, Done and Cancel. Control C copies, Control X cuts, Control V pastes. F2 renames, Delete asks to delete, Control Shift N creates a folder, Control L enters a path. F5 refreshes. F1 repeats this help. Escape stops speech or cancels an operation. Game exit returns here. Exit browser closes this session.");break;
    case 12:PostMessageW(window,WM_CLOSE,0,0);break;
    default:backToBrowser();break;
    }
}
static void activate(void) {
    int i=selection();if(i<0)return;
    switch(screen) {
    case BROWSE:openSelected();break;
    case ACTIONS:action(i);break;
    case CONFIRM:
        if(i==0)backToBrowser();else startJob(pending,pendingSource,pendingTarget,NULL);break;
    case INFO:if(i==0)say(edit);else backToBrowser();break;
    case TEXT:
        if(i<pickerCount) {
            size_t n=wcslen(edit);if(n+1<CAP){edit[n]=alphabet[i];edit[n+1]=0;}
            WCHAR text[2048];swprintf(text,2048,L"%.1800ls",edit);say(text);
        } else if(i==pickerCount){size_t n=wcslen(edit);if(n)edit[n-1]=0;say(*edit?edit:L"Empty text.");}
        else if(i==pickerCount+1)say(*edit?edit:L"Empty text.");
        else if(i==pickerCount+2){*edit=0;say(L"Text cleared.");}
        else if(i==pickerCount+3)finishText();else backToBrowser();
        break;
    }
}
static BOOL key(WPARAM k) {
    BOOL control=GetKeyState(VK_CONTROL)<0,shift=GetKeyState(VK_SHIFT)<0;
    if(busy){if(k==VK_ESCAPE||k==VK_LEFT){
        if(activeOperation==LAUNCH || activeOperation==SHORTCUT || activeOperation==ASSOC)say(L"Waiting for the request result.");
        else {InterlockedExchange(&cancelled,1);say(L"Cancellation requested. Completed changes remain.");}
    }return TRUE;}
    if(k==VK_UP||k==VK_DOWN||k==VK_HOME||k==VK_END||k==VK_PRIOR||k==VK_NEXT) {
        int n=(int)SendMessageW(list,LB_GETCOUNT,0,0),i=selection();
        if(n<=0){say(L"Empty folder. Right opens actions.");return TRUE;}
        i=k==VK_HOME?0:k==VK_END?n-1:i+(k==VK_UP?-1:k==VK_DOWN?1:k==VK_PRIOR?-10:10);
        if(i<0)i=0;
        if(i>=n)i=n-1;
        SendMessageW(list,LB_SETCURSEL,(WPARAM)i,0);announceSelection();return TRUE;
    }
    if(k==VK_RETURN){activate();return TRUE;}
    if(k==VK_LEFT && screen!=TEXT){if(screen==BROWSE)up();else backToBrowser();return TRUE;}
    if(k==VK_ESCAPE){if(screen==BROWSE){if(cancelSpeech)cancelSpeech();}else backToBrowser();return TRUE;}
    if(k==VK_F1){if(screen==BROWSE)savedSelection=selection();action(11);return TRUE;}
    if(screen==TEXT){
        if(k==VK_BACK){size_t n=wcslen(edit);if(n)edit[n-1]=0;say(*edit?edit:L"Empty text.");return TRUE;}
        if(k==VK_LEFT||k==VK_RIGHT){int i=selection();i+=k==VK_LEFT?-1:1;int n=pickerCount+5;if(i<0)i=n-1;if(i>=n)i=0;SendMessageW(list,LB_SETCURSEL,(WPARAM)i,0);announceSelection();return TRUE;}
        return FALSE;
    }
    if(screen==BROWSE){
        if(k==VK_RIGHT||k==VK_APPS){menu(ACTIONS,actions,14,0);say(L"Actions. Open.");return TRUE;}
        if(k==VK_F5){refresh();return TRUE;}
        int a=-1;
        if(k==VK_F2)a=4;else if(k==VK_DELETE)a=5;
        else if(control && k=='C')a=1;else if(control && k=='X')a=2;else if(control && k=='V')a=3;
        else if(control && shift && k=='N')a=6;else if(control && k=='L')a=7;
        if(a>=0){savedSelection=selection();action(a);return TRUE;}
    }
    return FALSE;
}
static LRESULT CALLBACK listProc(HWND hwnd,UINT message,WPARAM word,LPARAM data,UINT_PTR id,DWORD_PTR context) {
    (void)id;(void)context;
    if(message==WM_KEYDOWN||message==WM_SYSKEYDOWN) {
        if(GetKeyState(VK_MENU)<0 && word==VK_UP && screen==BROWSE && !busy){up();return 0;}
        if(key(word))return 0;
    }
    if(message==WM_CHAR) {
        if(busy || word<32 || GetKeyState(VK_CONTROL)<0)return 0;
        if(screen==TEXT) {
            size_t n=wcslen(edit);if(n+1<CAP){edit[n]=(WCHAR)word;edit[n+1]=0;}say(edit);
        } else if(screen==BROWSE) {
            ULONGLONG now=GetTickCount64();if(now-lastType>1000)*search=0;lastType=now;
            size_t n=wcslen(search);if(n+1<256){search[n]=(WCHAR)word;search[n+1]=0;}
            for(size_t i=0;i<count;i++)if(!_wcsnicmp(entries[i].name,search,wcslen(search))){SendMessageW(list,LB_SETCURSEL,i,0);announceSelection();break;}
        }
        return 0;
    }
    return DefSubclassProc(hwnd,message,word,data);
}
static LRESULT CALLBACK windowProc(HWND hwnd,UINT message,WPARAM word,LPARAM data) {
    switch(message) {
    case WM_SIZE:MoveWindow(statusLabel,10,10,LOWORD(data)-20,60,TRUE);MoveWindow(list,10,80,LOWORD(data)-20,HIWORD(data)-90,TRUE);return 0;
    case WM_SETFOCUS:SetFocus(list);return 0;
    case WM_ACTIVATEAPP:foreground=word!=0;if(!foreground && cancelSpeech)cancelSpeech();else if(foreground && !busy && count)announceSelection();return 0;
    case WM_COMMAND:if(HIWORD(word)==LBN_SELCHANGE && !busy)announceSelection();else if(HIWORD(word)==LBN_DBLCLK && !busy)activate();return 0;
    case DONE:{
        Job *job=(Job *)data;busy=FALSE;
        if(logFile){fwprintf(logFile,L"operation=%d error=%lu source=%ls target=%ls count=%llu\n",job->op,job->error,job->source,job->target,(unsigned long long)job->count);fflush(logFile);}
        if(job->error) {
            WCHAR reason[1000],text[2048];errorText(job->error,reason,1000);
            swprintf(text,2048,L"%ls. %ls",*job->message?job->message:reason,
                job->op==COPY||job->op==MOVE||job->op==REMOVE?L"Some changes may have completed. Refresh to inspect results.":L"Choose another item or retry.");
            backToBrowser();say(text);
        } else if(job->op==SCAN) {
            freeEntries(entries,count);entries=job->entries;count=job->count;job->entries=NULL;job->count=0;
            put(folder,job->target);put(selectedName,job->selected);populate();
            int i=selection();WCHAR text[4096];
            swprintf(text,4096,L"%.1000ls %.1500ls. %llu items. %.1000ls %ls%ls",completionNotice,*folder?folder:L"Drives",(unsigned long long)count,
                count && i>=0?entries[i].name:L"Empty folder. Right opens actions.",count && i>=0?entryType(&entries[i]):L"",count && i>=0?L" selected.":L"");
            *completionNotice=0;say(text);
        } else if(job->op==LAUNCH) {backToBrowser();say(L"Launching. Your folder will return when the program closes.");}
        else if(job->op==SHORTCUT||job->op==ASSOC){backToBrowser();say(*job->message?job->message:L"Opened.");}
        else {
            if(job->op==MOVE)*clipboard=0;
            backToBrowser();put(completionNotice,L"Operation completed.");
            if(job->op==RENAME||job->op==MKDIR)put(selectedName,basename(job->target));
            startJob(SCAN,NULL,folder,selectedName);
        }
        freeEntries(job->entries,job->count);free(job);return 0;
    }
    case PROGRESS:{WCHAR *text=(WCHAR *)data;if(busy)say(text);free(text);return 0;}
    case WM_CLOSE:
        if(busy){InterlockedExchange(&cancelled,1);say(L"Cancelling. Wait for the operation to finish before closing.");return 0;}
        DestroyWindow(hwnd);return 0;
    case WM_DESTROY:window=NULL;PostQuitMessage(0);return 0;
    }
    return DefWindowProcW(hwnd,message,word,data);
}
static BOOL createInterface(HINSTANCE instance,BOOL visible) {
    INITCOMMONCONTROLSEX controls={sizeof(controls),ICC_STANDARD_CLASSES};InitCommonControlsEx(&controls);
    WNDCLASSW type={0};type.lpfnWndProc=windowProc;type.hInstance=instance;type.lpszClassName=L"WinlatorSpeakingBrowser";
    type.hbrBackground=(HBRUSH)(COLOR_WINDOW+1);type.hCursor=LoadCursorW(NULL,IDC_ARROW);RegisterClassW(&type);
    window=CreateWindowW(type.lpszClassName,L"Speaking File Browser",WS_OVERLAPPEDWINDOW,20,20,800,600,NULL,NULL,instance,NULL);
    if(!window)return FALSE;
    statusLabel=CreateWindowW(L"STATIC",L"",WS_CHILD|WS_VISIBLE|SS_LEFT,10,10,760,60,window,NULL,instance,NULL);
    list=CreateWindowExW(WS_EX_CLIENTEDGE,L"LISTBOX",NULL,WS_CHILD|WS_VISIBLE|WS_VSCROLL|WS_TABSTOP|LBS_NOTIFY|LBS_NOINTEGRALHEIGHT,10,80,760,480,window,(HMENU)1,instance,NULL);
    SendMessageW(list,WM_SETFONT,(WPARAM)GetStockObject(DEFAULT_GUI_FONT),TRUE);SendMessageW(statusLabel,WM_SETFONT,(WPARAM)GetStockObject(DEFAULT_GUI_FONT),TRUE);
    SetWindowSubclass(list,listProc,1,0);
    if(visible){ShowWindow(window,SW_SHOW);SetForegroundWindow(window);SetFocus(list);}
    return TRUE;
}
static BOOL waitForWork(void) {
    ULONGLONG end=GetTickCount64()+10000;
    do {
        MSG message;while(PeekMessageW(&message,NULL,0,0,PM_REMOVE)) {TranslateMessage(&message);DispatchMessageW(&message);}
        if(!busy)return TRUE;
        Sleep(5);
    } while(GetTickCount64()<end);
    return FALSE;
}
static int uiTest(const WCHAR *root) {
    if(!createInterface(GetModuleHandleW(NULL),FALSE))return 30;
    startJob(SCAN,NULL,root,NULL);if(!waitForWork())return 31;
    key(VK_RIGHT);if(screen!=ACTIONS)return 32;
    SendMessageW(list,LB_SETCURSEL,6,0);key(VK_RETURN);if(screen!=TEXT)return 33;
    const WCHAR *name=L"UI test \x65e5\x672c";
    for(const WCHAR *c=name;*c;c++)SendMessageW(list,WM_CHAR,*c,0);
    if(wcscmp(edit,name))return 34;
    SendMessageW(list,LB_SETCURSEL,pickerCount+3,0);key(VK_RETURN);
    if(!waitForWork() || screen!=BROWSE)return 35;
    WCHAR path[CAP];join(path,root,name);
    if(GetFileAttributesW(path)==INVALID_FILE_ATTRIBUTES || selection()<0 || wcscmp(entries[selection()].name,name))return 36;
    key(VK_DELETE);if(screen!=CONFIRM || selection()!=0)return 37;
    key(VK_RETURN);if(screen!=BROWSE || GetFileAttributesW(path)==INVALID_FILE_ATTRIBUTES)return 38;
    key(VK_F2);if(screen!=TEXT)return 39;
    SendMessageW(list,LB_SETCURSEL,pickerCount+4,0);key(VK_RETURN);
    if(screen!=BROWSE || wcscmp(entries[selection()].name,name))return 40;
    key(VK_RETURN);if(!waitForWork() || wcscmp(folder,path) || count)return 41;
    key(VK_LEFT);if(!waitForWork() || wcscmp(folder,root) || wcscmp(entries[selection()].name,name))return 42;
    key(VK_F1);if(screen!=INFO)return 43;
    key(VK_RETURN);if(screen!=BROWSE)return 44;
    key(VK_DELETE);SendMessageW(list,LB_SETCURSEL,1,0);key(VK_RETURN);
    if(!waitForWork() || GetFileAttributesW(path)!=INVALID_FILE_ATTRIBUTES)return 45;
    startJob(SCAN,NULL,L"Q:\\definitely-missing-browser-test",NULL);
    if(!waitForWork() || wcscmp(folder,root))return 46;
    join(favoritesFolder,root,L"Favorites");
    startJob(SCAN,NULL,L"",NULL);
    if(!waitForWork() || count<3 || entries[0].kind!=FAVORITES_ENTRY || wcscmp(entries[0].name,L"Favorites") ||
            entries[count-1].kind!=EXIT_ENTRY || wcscmp(entries[count-1].name,L"Exit"))return 47;
    key(VK_HOME);key(VK_RETURN);
    if(!waitForWork() || wcscmp(folder,favoritesFolder) || count)return 48;
    key(VK_LEFT);
    if(!waitForWork() || *folder || selection()!=0)return 49;
    key(VK_END);key(VK_RETURN);waitForWork();
    if(window)return 50;
    freeEntries(entries,count);entries=NULL;count=0;return 0;
}
/* Exercises the actual filesystem and native controls, on a disposable root. */
static int selfTest(void) {
    WCHAR temp[CAP],root[CAP],source[CAP],dest[CAP],child[CAP],file[CAP];
    GetTempPathW(CAP,temp);swprintf(root,CAP,L"%lswinlator-browser-test-%lu",temp,GetCurrentProcessId());
    if(!CreateDirectoryW(root,NULL))return 10;
    join(source,root,L"Unicode \x65e5\x672c folder");CreateDirectoryW(source,NULL);
    join(file,source,L"spaced file.txt");HANDLE h=CreateFileW(file,GENERIC_WRITE,0,NULL,CREATE_NEW,0,NULL);DWORD written;
    if(h==INVALID_HANDLE_VALUE)return 11;
    WriteFile(h,"test",4,&written,NULL);CloseHandle(h);
    join(dest,root,L"copy");
    int result=0;
    if(safeDestination(source,dest)||treeOperation(source,dest,FALSE))result=12;
    join(child,source,L"child");if(safeDestination(source,child)!=ERROR_INVALID_PARAMETER)result=13;
    if(safeDestination(source,source)!=ERROR_INVALID_PARAMETER)result=14;
    Job job={0};put(job.target,dest);if(scan(&job)||job.count!=1||wcscmp(job.entries[0].name,L"spaced file.txt"))result=15;
    freeEntries(job.entries,job.count);
    join(child,source,L"nested");CreateDirectoryW(child,NULL);
    join(file,child,L"nested.txt");h=CreateFileW(file,GENERIC_WRITE,0,NULL,CREATE_NEW,0,NULL);
    if(h==INVALID_HANDLE_VALUE)result=19;else CloseHandle(h);
    if(treeOperation(source,dest,FALSE))result=20; /* replacement and directory merge */
    Job sorted={0};put(sorted.target,source);
    if(scan(&sorted)||sorted.count!=2 || !(sorted.entries[0].attributes&FILE_ATTRIBUTE_DIRECTORY))result=21;
    freeEntries(sorted.entries,sorted.count);
    join(child,root,L"empty");CreateDirectoryW(child,NULL);Job empty={0};put(empty.target,child);
    if(scan(&empty)||empty.count)result=22;
    freeEntries(empty.entries,empty.count);
    join(child,root,L"missing");Job missing={0};put(missing.target,child);
    if(scan(&missing)!=ERROR_FILE_NOT_FOUND)result=23;
    freeEntries(missing.entries,missing.count);
    join(file,source,L"spaced file.txt");join(child,source,L"nested");
    if(MoveFileW(file,child))result=24; /* rename never overwrites */
    InterlockedExchange(&cancelled,1);if(treeOperation(source,dest,FALSE)!=ERROR_CANCELLED)result=16;InterlockedExchange(&cancelled,0);
    if(nameValid(L"CON.txt")||nameValid(L"a/b")||nameValid(L"trailing.")||!nameValid(L"Unicode \x65e5\x672c.txt"))result=17;
    if(treeOperation(dest,NULL,TRUE))result=25;
    join(dest,root,L"moved");if(MoveFileW(source,dest)==FALSE || GetFileAttributesW(source)!=INVALID_FILE_ATTRIBUTES)result=26;
    if(!result)result=uiTest(root);
    if(treeOperation(root,NULL,TRUE))result=18;
    return result;
}
int WINAPI wWinMain(HINSTANCE instance,HINSTANCE previous,PWSTR command,int show) {
    (void)previous;(void)show;
    if(wcsstr(command,L"--self-test"))return selfTest();
    if(wcsstr(command,L"--bridge-test")) {
        int argc;WCHAR **argv=CommandLineToArgvW(GetCommandLineW(),&argc);
        if(!argv || argc!=4){if(argv)LocalFree(argv);return 2;}
        Job *job=calloc(1,sizeof(Job));if(!job){LocalFree(argv);return 3;}
        job->op=LAUNCH;put(job->source,argv[3]);DWORD result=requestAt(job,argv[2]);
        free(job);LocalFree(argv);return (int)result;
    }
    CreateDirectoryW(L"C:\\WinlatorFileBrowser",NULL);
    GetPrivateProfileStringW(L"session",L"favoritesPath",L"",favoritesFolder,CAP,CONFIG);
    if(!*favoritesFolder) {
        WCHAR profile[MAX_PATH];
        if(SUCCEEDED(SHGetFolderPathW(NULL,CSIDL_PROFILE,NULL,SHGFP_TYPE_CURRENT,profile)))join(favoritesFolder,profile,L"Favorites");
    }
    logFile=_wfopen(L"C:\\WinlatorFileBrowser\\browser.log",L"w, ccs=UTF-8");
    HMODULE controller=LoadLibraryW(L"C:\\WinlatorNativeSapi\\payload\\nvdaControllerClient64.dll");
    if(controller){speak=(SpeakFn)(void *)GetProcAddress(controller,"nvdaController_speakText");cancelSpeech=(CancelFn)(void *)GetProcAddress(controller,"nvdaController_cancelSpeech");}
    if(!createInterface(instance,TRUE))return 1;
    if(wcsstr(command,L"--resume")) {
        GetPrivateProfileStringW(L"position",L"folder",L"",folder,CAP,STATE);
        GetPrivateProfileStringW(L"position",L"selection",L"",selectedName,CAP,STATE);
        if(*folder && GetFileAttributesW(folder)==INVALID_FILE_ATTRIBUTES){*folder=0;*selectedName=0;}
    }
    if(GetPrivateProfileIntW(L"session",L"launchStatus",0,CONFIG))put(completionNotice,L"The program launch ended with an error. Your previous folder has been restored.");
    say(speak?L"Speaking File Browser. Right opens actions. F1 gives help.":L"Speech bridge unavailable. Close and restart the container.");
    startJob(SCAN,NULL,folder,selectedName);
    MSG message;while(GetMessageW(&message,NULL,0,0)>0){TranslateMessage(&message);DispatchMessageW(&message);}
    if(cancelSpeech)cancelSpeech();
    freeEntries(entries,count);if(logFile)fclose(logFile);if(controller)FreeLibrary(controller);return 0;
}

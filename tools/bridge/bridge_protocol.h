#ifndef WINLATOR_NVDA_BRIDGE_PROTOCOL_H
#define WINLATOR_NVDA_BRIDGE_PROTOCOL_H
#include <windows.h>
#define BRIDGE_PIPE L"\\\\.\\pipe\\WinlatorNvdaBridge"
#define BRIDGE_MAX_TEXT 32767u
enum { BRIDGE_PING = 1, BRIDGE_SPEAK = 2, BRIDGE_CANCEL = 3 };
typedef struct { DWORD op; DWORD bytes; } BridgeRequest;
#endif

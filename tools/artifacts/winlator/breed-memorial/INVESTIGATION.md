# Breed Memorial update-check crash

Current candidate: October 6, 2026. User confirmed the game works after the update-check fix.

## Observed failure

Container 1 runs Wine 10.10 in experimental WoW64 mode. Setup and speech work.
During launch the game loads its bundled `dll/hspinet.dll`, connects over HTTPS
to `hirotaka2014.sakura.ne.jp`, and requests `/mh0406/game/version/bm.txt`.
TLS succeeds and the server returns HTTP 200 with a four-byte version response.
The original trace contains no launch of `data/bm_updater.exe` before the crash.
Thus this reproduced failure is in the main game's update check, before an
external updater is observed, not a demonstrated updater-executable failure.

The next call is `HttpQueryInfoA(..., HTTP_QUERY_RAW_HEADERS_CRLF, ...)`.
Wine reports insufficient buffer, then faults writing to address zero at
`7A79AE6B`. WININET was loaded at `7A780000`, so the fault RVA is `0x1ae6b`.
Disassembly shows `mov [esi],eax` there, with ESI zero. Wine 10.10's source
likewise unconditionally writes the converted required size through
`lpdwBufferLength` in that failure branch.

In the bundled HSPInet DLL, the call at RVA `0x3829` passes the object's
response-header buffer and length-pointer fields. The observed null pointer
is consistent with those optional fields being unset. The current upstream
HSP source explicitly initializes a default buffer and length pointer before
this query. This is an HSPInet/Wine API interaction; the captured request
does not show a connectivity, DNS, certificate, or HTTP-server failure.

## Alternatives checked

- Automatic container close was disabled during all recent reproductions;
  the game itself still faulted. The exit monitor is not the initiating cause.
- Unifont and Source Han Sans were placed into Container 1's real prefix at
  `files/rootfs/home/xuser-1/.wine/drive_c/windows/Fonts`. Japanese aliases
  were applied. The original fault was unchanged. This does not prove fonts
  are unnecessary elsewhere in the game.
- The hosts-file block did not stop Wine from reaching the server. It has
  been removed. The real update host remains reachable for the guard test.
- Speech is supplied by a separate helper process, so queued speech continuing
  after a game crash does not establish that the game is still running.

## Current direct game-file candidate

`patch-hspinet.ps1` checks the original instruction bytes, adds a small
position-independent guard, redirects only the optional raw-header query,
and removes the obsolete absolute-operand relocation. The guard returns FALSE
if its output-length pointer is null; otherwise it forwards the original call.
It does not alter server URLs, certificate checking, downloads, or version data.
The other header-query call sites remain untouched.

The first guard attempt omitted removal of the old relocation and produced a
different bad jump (`FC881000`). That candidate was replaced; it is not evidence
of a second original game fault.

Revised candidate SHA-256:
`BB19E109EDD7FD067204FE556DD56D7AE7187B749E6722D18B4448D2EFF12666`.

Installed directly at:
`/storage/emulated/0/games/Breed memorial/Breed memorial/dll/hspinet.dll`.
Original retained beside it as `hspinet.dll.before-bmguard`, and locally as
`device/hspinet.dll`. Original SHA-256:
`7279E26DB1E6D2B02FA531548B259D1F21116963108E0CD7ECE4A528AC2D55C9`.

Fresh session at 01:25:55 MDT read all four bytes from the real update server,
then completed the response read with zero remaining bytes. No page fault or
launcher exit was logged; both the game and speech-helper processes stayed alive.
Remaining acceptance: main menu responds and user confirms speech. Actual installation
of a newer game version has not been tested. Automatic provisioning is deferred;
the new source-level Breed font hook was removed. No catalog change was made.

## Sources

- https://raw.githubusercontent.com/wine-mirror/wine/wine-10.10/dlls/wininet/http.c
- https://raw.githubusercontent.com/onitama/OpenHSP/master/src/plugins/win32/hspinet/czhttp.cpp

## Tab in the name-entry screen

Android physical-key logs showed KEYCODE_TAB down/up handled with the game
focused. Keyboard.onKeyEvent nevertheless supplied Android's U+0009 as an
X11 keysym, causing InputDeviceManager to replace the guest's XK_Tab mapping.
The fix preserves the guest mapping for Tab, as for Enter. Modifier handling
is unchanged. Tab was added to the existing optional X11 event trace.

The Android build and existing keyboard tests passed. The APK was installed
with data preserved; the previous APK is `rollback/before-tab-fix.apk`.
In the fresh session, physical Tab down/up events were sent to the game window
and an ADB screenshot showed the dotted focus rectangle on the name dialog's
OK button. Reverse Shift+Tab and spoken focus announcements still require
user confirmation. The network DLL guard remains installed.

## Intermittent game audio after the intro (October 6)

The user accepted the Tab fix, then reported silence after the intro while
speech continued. Raising both volume settings and resetting game settings did
not resolve it. Disabling detailed Wine diagnostics also failed.

The original BASS DLL is version 2.4.17, SHA-256
`6E1BF8EA63F9923687709F4E2F0DAC7FF558B2AB923E8C8AA147384746E05B1D`.
A temporary forwarding DLL logged initialization, sample/stream creation,
channel playback, volume and stop calls. Initialization, all observed sound
loads and playback calls succeeded. The menu music stream had nonzero volume;
there was no immediate stop call for it. Android's stereo 48 kHz game track
repeatedly stopped advancing after approximately 3.4 seconds, with underruns,
while the separate speech tracks continued working.

A second trace included a 30-second playback-position monitor. In that run,
music and menu sounds advanced successfully for a while. BASS also reported
output state 2 between sounds (started but inactive), not an explicit shutdown.
The user reported sound cutting out after disconnecting the Bluetooth keyboard.
Keyboard connection/disconnection resets are recorded in Android logs; this is
a possible trigger, not a proven cause. A subsequent launch failed even with
the keyboard connected.

The pre-Tab APK and Tab APK differ only in classes.dex; all other ZIP entries
were compared by SHA-256 and match, including native/audio/speech assets.
Disassembled changed classes are Keyboard, InputDeviceManager,
XServerDisplayActivity and BreedMemorialCjkFonts. The activity lost the deferred
Breed font-provisioning call; it did not gain audio lifecycle calls.
The actual pre-Tab APK was installed with the original BASS DLL for an A/B test.
The user confirmed that it also failed with the keyboard connected. The Tab
APK was then restored. No container data or game settings were rolled back.

Current experiment: `build-bass-trace.ps1 -KeepAlive` builds a forwarding DLL
whose only intercepted export is BASS_Init. After successful initialization it
enables BASS_CONFIG_DEV_NONSTOP (50), retaining the game's original BASS library
for all other exports. This tests idle-output transition handling without
changing global audio or other games. There is no monitor thread in this mode.
The original library is retained in the game root as bass-original.dll and
beside the original location as dll/bass.dll.before-audio-trace. The candidate
is currently installed as dll/bass.dll. The user confirmed sustained audio
including Bluetooth keyboard connection/disconnection. Android's stereo game
track also continued advancing after those transitions with zero underruns;
the original and patched hspinet hashes were read back unchanged. This supports
idle-output transition handling as the failure boundary, but does not prove
which underlying Wine/ALSA component caused the stall. The Tab APK remains
installed. Restore `device/bass.dll` to that location to undo the audio
experiment; leave the independently accepted hspinet guard intact.

BASS documents the inactive output state and keep-active option here:
https://www.un4seen.com/doc/bass/BASS_IsStarted.html
https://www.un4seen.com/doc/bass/BASS_CONFIG_DEV_NONSTOP.html

## Input Controls and Android crashes (Build 91)

Input Controls now has a per-executable Breed Memorial audio compatibility
checkbox (enabled by default; changes apply on next launch). Build 91 passed
nine tests and was installed with data preserved. The user confirmed audio,
Tab, the checkbox, and stability leaving the game with auto-close disabled.

Crash-buffer analysis found four JNI aborts caused by ALSA shared-buffer writes
of 6352, 7088, 7200 and 8040 bytes into a 6144-byte auxiliary buffer. The uncaught
IllegalArgumentException crossed the native callback boundary and ART aborted.
Each was followed by a separate MainActivity restart failure: the file-manager
fragment lacked a zero-argument constructor. These stacks do not implicate the
auto-close preference. The bounds validator now throws a handled IOException,
closing only the bad client. Fragment identity/path are persisted in arguments
and saved state, with a constructor suitable for Android restoration. Pending
window-based automatic closure also rechecks the preference before acting.

A real Android diagnostic client exercised the valid 6144-byte shared write and
the historically crashing 7200-byte write. The latter received EOF; Winlator
retained PID 30805, logged the handled error, and the game's audio continued
advancing with zero underruns. Logs are `device/crashes-before-build91.log`;
probe source is `alsa-bounds-probe.c`. Build 91 rollback is
`rollback/before-audio-controls.apk` (the preceding Tab build).

## Automatic dependency provisioning (Build 92 accepted)

At the user's subsequent request, Breed Memorial was added to Winlator's
dependency installer, not Audio Game Manager's script/catalog. Its entry
bundles both font files and the known-good updater/audio payloads. Direct
launches install fonts, aliases and the per-game Win7 setting, then apply the
hash-guarded updater fix and checkbox-controlled audio wrapper automatically.
The launcher preserves unknown/newer HSPInet and custom BASS replacements.
The audio-off setting is respected by provisioning; the actual off/on UI cycle
has not been tested on-device. Build 92 passed ten tests and was installed with
data preserved. The speech asset remained unchanged. To test automatic game
patching, both original game DLLs were restored while Winlator was stopped.
The next normal launch logged `updater guard=true; audio enabled=true applied=true`.
Both installed DLL SHA-256 values matched their accepted payloads. The CJK v2
marker was created and per-game Win7 was read back from user.reg. The user
confirmed the game reached its menu with speech, music and effects working.
Automatic closing remained disabled. A completely new container has not been
tested. Build 91 rollback is `rollback/build91.apk`; APK hash for Build 92 is
`0FD21ADF3175265250678849DAA6097AB4BCB7078F5798D9E0447A2D30CF8209`.

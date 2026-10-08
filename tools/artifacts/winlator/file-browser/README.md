# Speaking File Browser

Container **Run** now opens a self-speaking Windows file browser instead of
`wfm.exe`. Current package, rollback, and acceptance status are recorded in the
[Winlator handoff](../README.md). The browser uses the existing native NVDA
controller speech route and its configured voice/rate; no speech DLLs changed.

## Controls

| Input | Action |
| --- | --- |
| Up / Down | Select and speak an item or menu choice |
| Enter | Open an item or activate a choice |
| Left | Parent folder; back from an action menu or confirmation |
| Right | Open actions; move in the character picker |
| Home / End / Page Up / Page Down | Move through the list |
| Typing in the file list | Select by name prefix |
| Ctrl+C / Ctrl+X / Ctrl+V | Copy / cut / paste within the browser |
| F2 / Delete | Rename / confirm permanent deletion |
| Ctrl+Shift+N / Ctrl+L | New folder / go to an absolute Windows path |
| Alt+Up / F5 / F1 | Parent folder / refresh / spoken help |
| Escape | Stop navigation speech; leave a menu; cancel a running file operation |

Existing arrow and Enter gesture mappings work with these controls. All menus
have Back or Cancel. Text entry includes a spoken character picker: letters,
uppercase letters, digits, punctuation (including colon/backslash for paths),
Backspace, Read text, Clear text, Done, and Cancel. Choose characters with arrows
and Enter, or type using a physical or on-screen keyboard. Use Clear text before
typing a completely new name when renaming. Left/Right move between character
choices in this screen; choose Cancel to leave it.

Actions include Open, Copy, Cut, Paste, Rename, Delete permanently, New folder,
Go to path, Create Winlator game shortcut, Properties, Refresh, Help, Exit browser,
and Back. A fresh Run shows **Favorites**, the available drives in letter order,
then **Exit**. Favorites opens the container's existing user Favorites folder;
Left from that folder returns to the drive list with Favorites selected. Enter on
Exit closes the browser and returns to Winlator. These entries are navigation/
action rows, never filenames used by copy, rename, or delete. Within folders,
folders sort before files by name.
The browser selects one item at a time. Folder operations are recursive.

Delete and replacement confirmations default to Cancel. Directory paste merges
contents only after confirmation when a destination exists. Rename never replaces
another item. Pasting an item onto itself or into its own descendants is rejected,
including destination aliases through existing filesystem links. Recursive file
operations reject reparse points instead of following links. Cancellation or
failure can leave completed changes; the browser reports that boundary and offers
Refresh. Clipboard contents remain within the browser.

The character picker uses Latin letters; keyboard text input and filenames support
Unicode. Properties show the entry's recorded size, not a recursively calculated
folder total. Application-owned Windows Open/Save dialogs are outside this change.

## Launch and return

Programs, installers, scripts, and Windows shortcuts are handed to Android's
normal launch flow. The browser saves its folder and selected name before the
transition. The old guest session is stopped before the next is created, so
dependency provisioning, native speech warmup, Super Liam's SAPI exception,
Bavisoft input compatibility, and game-specific gesture/input settings remain
on their existing paths. `.lnk` files retain the dependency-picker fallback.

Normal program exit reopens the browser and restores its saved position, even
when automatic container closing is disabled. Explicit Winlator session exit
returns to the container list. If the saved folder disappeared, restoration starts
at Drives. Non-program files use their existing Wine file associations.

Winlator shortcuts are created in the container's existing Desktop directory.
Existing shortcuts and their settings are never replaced; colliding names get a
numbered suffix. Generated paths use the encoding required by Winlator's existing
two-pass shortcut parser.

## Implementation and diagnostics

The executable is a separate asset, provisioned automatically into
`C:\WinlatorFileBrowser\accessible-browser.exe` when a generic session starts.
`session.ini` holds an ephemeral loopback port and session token; `position.ini`
holds the saved folder and selection. `browser.log` records operations and speech
return codes. A speech return code of zero alone does not prove audible playback.

The request protocol starts with big-endian `0x57464231`, followed by UTF-8
length-prefixed token, request ID, operation (`launch` or `shortcut`), and DOS path.
Responses contain the request ID, numeric status, and UTF-8 message. Field lengths
are bounded. Invalid UTF-8, control characters, stale tokens, relative paths,
unsupported operations, and reused IDs with changed contents are rejected.
Identical duplicate requests replay the response without repeating the action.
The listener exists only for the active browser session and binds to loopback.

Build from the workspace root:

```powershell
wsl -- bash tools/artifacts/winlator/file-browser/build.sh
python tools/artifacts/winlator/file-browser/verify.py
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

The build script also prepares a diagnostic executable in **androidTest assets
only**. Production includes only the browser. The diagnostic runs the browser's
filesystem/control self-test, speaks, creates a visible window, then closes after
six seconds. No games are automated by this test.

After backing up the installed APK, install both APKs with `adb install -r` and run:

```powershell
adb shell am instrument -w -r -e class com.winlator.filebrowser.FileBrowserDeviceTest com.winlator.test/androidx.test.runner.AndroidJUnitRunner
```

The device test requires Container 1. It temporarily disables automatic closing,
sets a diagnostic browsing position, uses real guest Enter/actions to create a
shortcut and launch the diagnostic, and asserts fresh speech, game identity,
normal exit, and folder/selection restoration. It restores the prior preference
and browsing position, removes its diagnostic and generated shortcuts, and removes
the temporary diagnostic from the recent-game picker.

## Verification on October 7, 2026

- Native MinGW build passed `-Wall -Wextra -Werror`.
- Windows native filesystem/control self-test and Unicode/malformed loopback
  protocol checks passed. Covered folder-first sorting, empty/missing folders,
  Unicode/spaced names, replacement/merge, ancestry rejection, cancellation,
  rename collisions, moving/deleting, arrow/Enter menus, text entry, confirmation
  defaults, creation/deletion, help/back, and selection restoration. Build 100
  extends those checks with Favorites first, Exit last, Favorites opening/return,
  and Enter on Exit actually closing the window.
- 56 Android unit tests passed, including six new bridge/shortcut tests.
- APK and instrumentation APK builds passed. Embedded browser and unchanged
  native speech archive match their source assets.
- The full device integration test passed on `R5CWA1XFMZJ` in 29.470 seconds:
  native self-test inside Wine returned 0; shortcut parsed to the intended path;
  diagnostic speech returned 0; normal exit restored the folder and selection
  while automatic closing was disabled.
- Fresh generic Run showed an `accessible-browser.exe` X11 window and successful
  guest speech calls. Build 100 was installed with app/container data preserved.
  On-device key checks opened the existing Favorites folder and returned to
  Drives, selected Exit at the bottom, and exited to Winlator's MainActivity.

The user confirmed Build 99's browser works. Remaining acceptance for Build 100:
the added entries' audible usability, text/punctuation announcements,
progress and cancellation, usable gestures, and affected real-game launch/input/
speech/cancellation/return behavior. Tests are diagnostic evidence, not audible or
real-game acceptance. A completely new container has not been tested.

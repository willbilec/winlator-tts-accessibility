# Winlator speech and keyboard handoff

Updated October 7, 2026. This is the single current document for Winlator
speech, keyboard recovery, build steps, test results, and known limits.

## Current package

**Build 100** is installed on `R5CWA1XFMZJ`: `11.2-tts61-browser-favorites-exit`,
package `com.winlator`. Container Run now opens a self-speaking Windows file
browser. App/container data and the existing native speech payload are preserved;
the browser is a separate executable asset. The APK is
`upstream/Winlator/app/app/build/outputs/apk/debug/app-debug.apk`, SHA-256
`766BDB0B76B615A07CAC36218118F06B1202916C2BD180E3BA23166A0AA6F796`.
Installed rollback APKs for Builds 94–97 are retained in
`artifacts/winlator/rollback/2026-10-07-R5CWA1XFMZJ/gesture-keyboard/`.
The installed Build 98 APK was backed up and hash-verified before updating:
`rollback/2026-10-07-R5CWA1XFMZJ/file-browser/installed-build98.apk`.
Build 99 was also backed up before this update:
`rollback/2026-10-07-R5CWA1XFMZJ/file-browser/installed-build99.apk`.

### Speaking file browser

Container **Run** starts at Drives, with **Favorites first** and **Exit last**.
Favorites opens the existing container user's Favorites folder; Left from that
folder returns to Drives with Favorites selected. Enter on Exit closes the
browser and returns to Winlator. Up/Down select and speak, Enter opens, Left
goes back, and Right opens actions. Actions provide copy/cut/paste, rename,
permanent delete with Cancel selected, new folder, go to path, game shortcut
creation, properties, refresh, help, and exit. Standard keyboard shortcuts and
type-to-select work alongside a spoken character picker usable with arrows/Enter.
All menus include Back or Cancel. Speech uses the existing game-speech voice/rate.

Executable/installer launches pass through Android's normal launch preparation,
preserving dependency installation and per-game speech/input/gesture behavior.
Normal program exit restores the browser's saved folder and selection, including
when automatic closing is disabled. Explicit session exit returns to Winlator.
The old guest stops before the new session starts. Non-program files use Wine
associations; opaque Windows shortcuts retain the existing dependency picker.

Build 100 passed **56 unit tests**, native Windows filesystem/control and protocol
checks, and both APK builds. The full on-device diagnostic integration test passed
in **29.470 seconds**: shortcut creation retained the correct path, native file/
control checks passed inside Wine, fresh diagnostic speech returned 0, and normal
exit restored the browser with automatic closing disabled. Embedded browser and
native speech hashes match their assets. Fresh generic Run created the browser's
X11 window with successful guest speech calls. The user confirmed Build 99's
browser works. Build 100's Favorites ordering, actual folder opening, return to
Drives, Exit ordering and Exit to MainActivity were checked on device; the extended
native tests cover Favorites opening/return and Exit activation inside Wine.
The added entries' audible usability and affected real-game launch/input/
cancellation/return remain pending user confirmation; a new container remains
untested. The browser is reopened for
user testing. Controls, source/build steps, limits, and diagnostics are in the
[browser documentation](file-browser/README.md).

### Gesture keyboard controls

Open **Gesture management** from the in-game menu, a game shortcut menu, or an
executable file menu. The game selector starts with Global default gesture map,
then the current game and the ten most recent launches without duplicates.
Generic desktop sessions use the global map without entering game history.
Maps are scoped by container and normalized executable path. Build 98 merges
Android `/data/data` and `/data/user/0` aliases, preserving existing assignments
and swipe behavior when the same game is opened through different launch paths.

One-finger swipes default to the matching arrow and one-finger tap to Enter.
One-, two-, and three-finger directional swipes, single/double/triple taps and
stationary long presses can be assigned. Four-finger tap opens the game menu.
Selecting a gesture opens a searchable key picker. Inherited rows and the
inheritance choice name the actual global key. Unassigned explicitly disables
a gesture; Use global default continues to inherit future global changes.
Assignments are saved and applied immediately.

**Swipe behavior for this game** offers **Quick presses**, **Hold until finger
lift**, and **Use global default**. The factory global behavior is Quick presses;
each game can override it independently. Hold mode presses the arrow as soon as
the swipe is recognized and releases when a finger lifts. Stationary long presses
have their own key assignments and hold until lift. Quick presses use the existing
30 ms release interval; very brief swipe holds retain that minimum delivery time.
Cancellation always releases immediately. Unassigned double/triple taps do not
delay singles: two taps produce two Enter presses. Assigned longer taps wait for
the Android multi-tap interval and emit only the winning gesture.

Gameplay control mode defaults to **Automatic**: a physical keyboard disables
gesture capture, and disconnecting it enables capture. Virtual keyboards and
controllers do not count. **Gestures** and **Touchpad** provide manual overrides.
Gestures use visible screen directions in both orientations; keyboard/touchpad
modes retain landscape behavior. Menus/dialogs suspend gesture input and restore
guest focus on closing. Keyboard changes, mode changes, rotation, backgrounding,
and touch cancellation cancel pending gestures and release gesture-owned keys.
Control assignments also use the existing speech-cancellation preference.

Settings use native accessible selectors, buttons and editable search controls.
TalkBack can intercept gameplay gestures: normal applications cannot configure
another accessibility service's touch-exploration pass-through region. Pause
TalkBack using its shortcut if needed and resume it for settings. Winlator does
not change reader settings. Continuous coexistence remains unverified.

### Gesture verification and remaining acceptance

Build 98 passed **50 unit tests**, `testDebugUnitTest`, `assembleDebug`, and
`assembleDebugAndroidTest`. Coverage includes recognition, multi-finger rejection,
tap precedence, holds/cancellation, repeated keys, physical/gesture ownership,
map inheritance/persistence/MRU, swipe overrides, alias migration, and existing
keyboard/Bavisoft regressions. Two Android instrumentation tests passed on the
device: accessible search and selection, and real Android MotionEvents delivered
through XServer to a diagnostic Wine window. The latter verified all four arrows
and Enter, stationary holds, menu cancellation, per-game swipe holds versus quick
presses, and equal identities for DOS shortcut and private Android paths.

The latest eight-event instrumentation sample measured mean **301.712 µs** and
maximum **697.969 µs** from recognition callback to XServer key delivery. These
exclude gesture recognition waits, the release interval, Wine/game processing,
and audio; they are a small diagnostic sample, not a game-latency guarantee.
Tests call the pad directly and do not prove TalkBack touch pass-through.

The user confirmed all four arrow gestures work and are responsive on Build 95.
Portrait and landscape session dimensions were observed during checker testing.
Full Chillingham/Grizzly Gulch gesture acceptance, long-hold behavior in each game,
physical keyboard connection switching, audible Control cancellation, and spoken
screen-reader navigation through both dialogs still require user confirmation.
Fresh checker startup returned native speech status 0 after this update; that
confirms the guest RPC route. The user subsequently confirmed the checker stays
open, announces press/release, and swipe holds work. Audible cancellation and
affected-game acceptance remain pending. No native payload changed.

**Arrow Key Checker** is installed as a Container 1 shortcut, with executable
`C:\NVDA-Bridge\gesture-input-probe.exe`. It remains open until explicitly closed,
announces arrow press/release transitions, displays held arrows, and logs Windows
key events beside the executable. Hold until finger lift is enabled for the
checker only; the global default remains Quick presses. It was reopened after
instrumentation and left running; the user confirmed it works. Source and instructions:
[checker documentation](gesture-keyboard/README.md).

## Prior keyboard and dependency verification

**Build 94** was installed previously: `11.2-tts55-auto-game-dependencies`,
package `com.winlator`. The current Android-only rebuild retains keyboard
recovery, preserves child input when hiding the desktop, and excludes hidden
windows from automatic focus. The per-executable short-arrow setting was
confirmed in Grizzly Gulch: it advances one choice per tap, and the latest
input-path optimization improved responsiveness. Subsequent gameplay reports
show occasional unwanted movement and shooting presses requiring several
attempts; the short-arrow workaround is not accepted for complete Grizzly
gameplay. See [Grizzly input investigation](GRIZZLY_INPUT_INVESTIGATION.md).
The setting is currently disabled in Container 1 for diagnosis. Isolated taps
reproduced double navigation: legacy DirectInput still returned Right down
about 838 ms after press, despite physical release after 74–89 ms. The game
clears its logical states between actions, so that stale snapshot becomes a
new press. With pulsing disabled, the user confirms shooting works completely
fine while menus skip options. Preserve ordinary shooting input when fixing
the menu behavior. Build 86 routes normal direct launches of Grizzly Gulch and
Chillingham through a clean DirectInput 8 keyboard helper (v7). It has no input
snapshot hooks, replay, telemetry worker or synchronization waits. The prior
diagnostic raw candidate improved menu skipping, but brief taps still missed
around Chillingham's Inspect/Take/Use menu after audio had finished; a half-second
hold succeeded, unlike the user's native Windows test. Full input acceptance
remains pending. Android per-key/X11 tracing is now disabled in normal sessions,
and Wine logging is limited to errors. Quiet Build 86 also missed a tap after
roughly six to ten successful presses. Build 87's game-only built-in DirectSound
experiment also failed; it was reverted and its Chillingham registry override
removed with Wine stopped. The global native,builtin setting remains intact.
Build 88 keeps DirectInput 8 and drains actual queued WM_INPUT packets on their
own Wine thread before returning a keyboard snapshot. No presses are replayed
and no key bytes are synthesized. Its launcher reports queue status 3, confirming
the handler and first queue drain are active in the fresh Chillingham launch.
Build 89 fixed that failure by dispatching one raw keyboard packet per game
snapshot. The user confirmed quick taps now work. Build 90 adds a per-executable
“Bavisoft quick-tap keyboard compatibility” checkbox in Input Controls. It is
enabled by default for Grizzly Gulch and Chillingham and can be disabled for
either game; other games are never routed through this helper.
The buffered replay candidates v4/v5 caused spontaneous actions and are rejected.
The v2 synchronization fallback fixed menus/shooting but missed some short taps.
See the investigation for the current candidate and Audio Games Manager comparison.
A small residual delay remains unmeasured. Other keyboard recovery scenarios still need live
acceptance. Build 82's speech behavior was accepted and uses the same native
speech payload.

| Artifact | Path relative to the workspace root |
| --- | --- |
| Installed Build 85 rollback APK | `artifacts/winlator/rollback/2026-10-05-R5CWA1XFMZJ/grizzly-input-fix/installed-before-build86.apk` |
| Installed Build 86 rollback APK | `artifacts/winlator/rollback/2026-10-05-R5CWA1XFMZJ/grizzly-input-fix/installed-build86-quiet-raw.apk` |
| Current Build 100 APK | `upstream/Winlator/app/app/build/outputs/apk/debug/app-debug.apk` |
| Installed Build 99 rollback | `artifacts/winlator/rollback/2026-10-07-R5CWA1XFMZJ/file-browser/installed-build99.apk` |
| Installed Build 98 rollback | `artifacts/winlator/rollback/2026-10-07-R5CWA1XFMZJ/file-browser/installed-build98.apk` |
| Native payload | `artifacts/winlator/native-sapi/winlator-native-sapi-candidate.tzst` |
| Android source asset | `upstream/Winlator/app/app/src/main/assets/winlator-native-sapi-candidate.tzst` |

Verified SHA-256:

- Current Build 100 APK: `766BDB0B76B615A07CAC36218118F06B1202916C2BD180E3BA23166A0AA6F796`
- Browser executable, source asset, and embedded APK asset:
  `0C169D9DF397193C6BC616BA8E584391C1AB98E84336D73300AA63FB06124AC0`
- Native archive, source asset, and embedded APK asset:
  `BD33C79E1A686BA4E7357E73A78DB9C7DB43D8C0774463EA017F27A8C36D6323`

Build 92 passed ten tests and the Android build; its embedded speech payload
matches the existing native archive. Build 91's audio/Tab/Input Controls behavior
and stability with automatic closing disabled were confirmed by the user.
Its ALSA bounds guard also passed a live malformed-client test without stopping
Winlator or interrupting the game's audio. Build 92 adds Breed Memorial to the
dependency installer and applies bundled CJK fonts, its known-version updater
guard and checkbox-controlled audio fix before direct launches. Automatic
provisioning was tested after restoring both original game DLLs: the launcher
reapplied both accepted payloads, installed font aliases and per-game Win7, and
the user confirmed menu, speech and audio. A completely new container has not
been tested. See [Breed Memorial investigation](breed-memorial/INVESTIGATION.md).
Build 91 rollback is `breed-memorial/rollback/build91.apk`.

Build 93 added a separate Chillingham dependency-picker entry beside Grizzly
Gulch, sharing the recipes' VB6 and MFC 4.2 runtime assets. Build 94 makes
automatic dependency setup catalog-driven: direct executable launches match
`GameDefinition.processNames` by exact basename, case-insensitively, and install
missing dependencies before starting the guest. This also applies to new catalog
entries without adding separate launcher cases. Existing installed entries are
skipped; Breed Memorial retains its per-launch provisioning. Direct launches
and executable shortcuts are supported; generic desktops and opaque `.lnk`
launches retain the manual picker. The developer workflow is documented in
`upstream/Winlator/app/AUDIO_GAME_DEPENDENCIES.md`.
Build 94 passed all twelve tests and `assembleDebug`; embedded runtimes, keyboard
helper and speech payload match the current assets. APK SHA-256:
`f42a9f26dabb9a12f1176a0dc37c2aa00313956efa64c9bdd9d4ebcfd66016af`.
In Container 1, Chillingham's first direct launch logged automatic installation,
recorded its ID, and installed three DLLs matching the bundled SHA-256 hashes.
The next fresh launch retained the installed status and skipped installation.
Rollback APKs for installed Builds 92 and 93 are in
`artifacts/winlator/rollback/2026-10-06-R5CWA1XFMZJ/`.

Build 88 passed the Android build, two Bavisoft routing tests and three keyboard
reset tests. Native regression checks cover queued down/up packets, no replay
of completed taps, holds, original errors and missing-worker fallback. Embedded
helper/speech assets match source assets. It was installed with `adb install -r`,
preserving data. Grizzly input and fresh audible SAPI acceptance remain unverified.

Historical Build 85 validation: the original candidate passed the Android build, three keyboard
regression tests, diff checks, and embedded-payload comparison. The current
Android-only rebuild passed `assembleDebug`, was installed with `adb install -r`,
and its Grizzly Gulch setting was read back through ADB. App/container data was
preserved. The latest input change was confirmed by the user in Grizzly Gulch;
no automated tests were run for that rebuild.
Previous installed APKs were saved as
`artifacts/winlator/Winlator-build83-keyboard-reset-rollback.apk` and
`artifacts/winlator/Winlator-build84-installer-input-rollback.apk` before updating.
The APK installed immediately before the latest input-path optimization is at
`artifacts/winlator/rollback/2026-10-05-R5CWA1XFMZJ/input-latency/installed-before-latency-optimization.apk`.
The older `Winlator-11.2-espeak-tts-fixed-debug.apk` remains a historical
artifact, not the current package. No historical binaries or backups were deleted.

## Using speech and keyboard recovery

Speech is provisioned automatically during normal container startup after
creation/update. No manual installer, NVDA launch, or special navigation is needed.

**Fresh containers inherit the current implementation automatically.** Creation
extracts the base Wine prefix; the first normal launch applies the current APK's
container patches and stages its native speech payload. Startup writes the
current regular/NVDA speed settings and runs `configure-native-sapi.exe
--bootstrap` before launching the game. In a new prefix, this installs both
SAPI architectures and RHVoice packages, selects the voices/converters, and
starts the current helper. Later launches refresh the helper and voice settings
without overwriting the original rollback backups. Super Liam's game-only
override is applied by its launch path, not copied from an old container.

Control interruption, device monitoring, stale-repeat protection, input tracing,
and the Reset keyboard menu belong to the APK's session/input code and apply to
every container. Their app preferences use defaults when no saved preference
exists. No previous container, manual registry import, or diagnostic executable
is required. This path was checked against the source and packaged payload;
a new phone container was not created or reset just to verify this documentation.

- **Regular TTS speed** and **NVDA speed** are separate Winlator settings.
  Save and restart the container to apply them. Games can override their own
  regular SAPI speech rate.
- **Control stops screen reader speech.** Either Control key cancels the native
  helper's voice independently of the game's event loop and still reaches the
  game. Disable this with **Use Control to stop screen reader speech** if needed.
  It does not cancel independent SAPI voices owned by games or Android's reader.
- Restart a game after selecting its screen-reader speech mode; some games
  detect the reader only at startup.
- Open the in-game Back menu and select **Reset keyboard** to release held keys
  and refresh keyboard-device monitoring, view focus, and Android capture.
  This resets the input session without restarting Wine or closing the game.
  Caps Lock and Num Lock state are retained.
- Keys are also released when the game menu opens or a physical keyboard
  connects, disconnects, or changes configuration. Old Android repeat events
  cannot recreate a cleared hold; a fresh key-down starts the next hold.
- **Per-executable short arrow taps:** Open the game pop-up menu's **Input
  Controls** dialog and toggle **Single-step short arrow taps for this
  executable**. The dialog displays the executable path. The setting is stored
  with that container and defaults off for other executables; launching the
  container desktop without a specific executable disables this option.
  Grizzly Gulch in Container 1 is currently disabled through ADB for the
  normal-input investigation. See
  `upstream/Winlator/app/ADB_CONTROL.md` for independent ADB get/set commands.
  With the option enabled, each arrow key-down is sent immediately and released
  after a 5 ms pulse. If held, the key re-engages after 200 ms and stays held
  until key-up. The user confirmed one choice per tap and improved response;
  slight remaining latency is not yet attributed to a specific cause. Later
  pulse-enabled shooting and movement failures remain unresolved; ordinary
  shooting is now user-confirmed working. A 5 ms pulse can finish
  between the game's DirectInput state polls, and the 200 ms re-engagement
  introduces a second guest press. This option is an experimental workaround,
  not a verified solution for all gameplay.
- Arrow input no longer synchronously writes per-key diagnostic records to
  `guest-session.log`, avoiding disk I/O in the event path. `WinlatorInput`
  logcat diagnostics remain. Consequently, the session log is not a complete
  per-key trace; use logcat when investigating individual Android key events.
- Android TTS engine/voice preferences do not configure the native RHVoice route.
  Old Android-loopback and eSpeak setup instructions no longer apply.

## What was verified

| Behavior | Evidence |
| --- | --- |
| Regular SAPI speech | User confirmed audible native speech; warmed native playback probes also spoke. |
| Manamon 2 | User confirmed screen-reader speech after a fresh restart and working speed settings. |
| Super Liam | Fresh launches of build 80 had the game's window, keyboard input, and speech. |
| Arcadelux | Fresh speech worked on build 80; build 81 improved responsiveness; Control audibly stopped speech on build 82. |
| Native bridge | Stock-client RPC tests on nogui/shell produced real PCM, active purge, repeated cancellation, and speech after cancellation. |
| Direct interruption | A sleeping stock client did not request cancellation; the external Control channel performed a real purge, and later speech submission succeeded. User confirmed audible interruption. |
| Keyboard reset logic | Tests cover a missing Right key-up, stale repeats after reset, guest-held keys absent from local tracking, fresh Enter/Right presses, and modifier/lock handling. |

Game confirmations occurred across the listed builds, not a full retest of
every game on build 85. A successful build or RPC result alone is not audible
acceptance. Fresh game checks remain necessary after changes.

## Remaining issues

### Super Liam's mid-game held Right key

The user reported Right becoming stuck after about 20 minutes of play.
There was **no keyboard disconnect or focus change at onset**. Disconnecting
and reconnecting afterward did not recover it, and Enter failed at the end screen.
The original triggering event was not captured; its root cause is unconfirmed.

Source inspection found no physical-keyboard disconnect cleanup and no input
release when the in-game drawer opens. Build 83 addresses those recovery gaps
and adds a forced reset that emits key-up events even if local tracking has
already lost the held key. This is not proof that disconnect/focus caused the
reported mid-game failure. Do not use a timeout to release legitimate long holds.

Build 83 recorded direction/Enter event action, device, repeat count, event/down
time, guest focus, and X11 delivery/drop results in the session log. The current
input path omits synchronous per-key session-log writes for responsiveness;
Android key details remain in `WinlatorInput` logcat. If the failure recurs,
collect logcat and the remaining session evidence before restarting.
The menu reset and a long Super Liam session still need live acceptance.

### Installer keys rejected by disabled X11 windows

In Light Cars 1.2 Setup at Ready to install, Android received physical Enter
events but X11 routing logged `result=disabled`. A fresh native window probe
showed the setup dialog visible, enabled, and foreground in Wine. The upstream
direct-launch desktop-hiding routine recursively disabled all existing desktop
descendants; this was the only source of the X11 disabled flag.

Build 84 preserved child input, but the user confirmed the installer still
stalled. Its new trace identified the disabled target as the hidden desktop
itself (12582919), rather than the dialog. The automatic map-focus listener
still selected hidden desktops. Build 85 skips disabled/hidden windows in that
listener, explicitly focuses mapped dialogs, and logs focus transitions.
These changes apply to every container, including newly created ones, through
the APK's session code. No prefix repair or manual configuration is needed.
The user confirmed build 85 still stalls at Ready to install. This time its X11
focus stays on `light cars setup.exe` and Enter press/release events are sent.
A live native probe shows the installer foreground and responsive, with the
Install button enabled, but its UI thread has `hwndFocus=0`. The button's
accelerator is Alt+I, not Alt+N. The exact control-focus loss during page changes
is still under investigation; the hidden desktop no longer explains this trace.
`focus-probe.c` can enumerate controls and watch native focus/lifetime events as
a standalone diagnostic without changing the speech payload or restarting Wine.
The separate long-running Super Liam stuck-arrow incident has not been proven
to share this cause. Keyboard unit tests do not establish live installer acceptance.

### Idle keyboard focus

In an earlier Arcadelux episode, game/helper processes remained running and
Wine reported the game's window visible, enabled, active, and focused. Switching
focus through the Android screen reader restored input. Build 82 reapplies
Android keyboard capture on focus changes and releases pressed keys on
focus loss/pause. Long-idle recovery remains unverified; no crash was established.

### Arcadelux loading delay

Control stops speech, but Play a game still takes roughly 3–4 seconds to
reach the next menu. The user disabled the game's music-generation setting.
Selecting the Chip sound engine helped somewhat; starting gameplay also takes
a couple of seconds. These delays are not fixed.

Read-only analysis found the cabinet menu's random-seed music path bypasses
the helper function implementing Fresh menu music. Game startup prerenders
a track and then calls `play_music`, which can join a matching prerender
thread for up to three seconds and obtain/build music synchronously.
An isolated benchmark of matching eight-bar Chip tracks took 0.470/0.259
seconds on Windows and 1.560/1.192 seconds in the phone container.
This proves a computation slowdown, not the full cause of the live delay.
Build 82 helper requests averaged about 23 ms, maximum 103 ms; those timings
exclude client transport and game-thread work. Game files and saves are unchanged.

### Full Windows screen reading

This bridge provides game speech through existing NVDA controller clients,
without running full NVDA. It does not provide general Wine menu/dialog
reading, MSAA/UIA accessibility, or braille. Earlier full-NVDA experiments
did not establish reliable UI reading.

## Architecture and fixes to preserve

Regular TTS uses **Microsoft SAPI 5.4 and RHVoice's Windows SAPI5 engine**.
The Microsoft runtime is extracted from Windows 7 SP1 packages used by
Winetricks' `sapi` recipe. Packaged versions are RHVoice 1.6.0 x86/x64,
English 2.8.2, and Alan 4.0.2, provisioned through the original MSI packages.

NVDA clients call a background **32-bit Wine RPC helper** serving stock
32-bit and 64-bit clients. A persistent COM worker submits asynchronous
speech; SAPI/RHVoice owns audio. The active route does not use the Android
speech server, custom eSpeak playback, Piper, or GameNative.

Preserve these details together:

- Register both SAPI architectures and valid voice defaults, including the
  18 matching phone-converter registry keys. Missing converters caused
  `8004503a` despite successful voice installation.
- Initialize `GetDesktopWindow` and `waveOutGetNumDevs` on the helper's
  **main thread before the COM worker**. Worker-only warmup returned success
  without audible phone playback.
- The launcher owns Arcadelux's hidden NVDA detection window on the established
  game desktop. The prelaunch helper must not create detection windows or switch
  desktops while Explorer is establishing desktop ownership.
- Super Liam uses a **game-only `sapi=builtin` override**; the helper retains
  native SAPI. Preserve unrelated overrides. Changing Windows environment
  variables after launch did not update Wine's initial Unix DLL overrides.
- Use the 200 ms SAPI buffer and 20 ms notifications when supported, with fallback
  to the output defaults. Skip redundant purges only when the worker knows its
  voice queue is empty; still purge active speech.
- Direct interruption sends a one-byte `C` UDP datagram to **127.0.0.1:51235**.
  Its socket thread queues a purge on the existing COM worker. Android sends
  off the input thread; no extra GUI window is created.
- Reset transient keyboard state under XServer locks and send actual X11 key-up
  events. Preserve key mappings/listeners and lock state. Reset input, not the
  entire Wine/XServer session.

Provenance: [Winetricks recipe](https://github.com/Winetricks/winetricks/blob/master/src/winetricks),
[RHVoice packages](https://github.com/RHVoice/RHVoice/blob/master/doc/en/Binaries.md).
These references explain the runtime choice; versions above describe this build.

## Source and build

The nested Android Git checkout is `upstream/Winlator/app`, currently on
`winlator-nvda-keyboard`, with local speech changes and upstream Winlator 11.2
commit `a030f55` as an ancestor. Preserve its changes; do not reset it to
the parent repository's older submodule pin.

| Source | Responsibility |
| --- | --- |
| `bridge/sapi_backend.h` | COM voice worker, rates, buffering, ordered speech/purge |
| `bridge/nvda_android_rpc_service.c` | Native RPC, main-thread warmup, Control listener |
| `bridge/nvda_sapi_client.c` | Controller adapter; games retain their original DLLs |
| `artifacts/winlator/native-sapi/configure.c` | Provisioning, migration, backups, rollback, helper lifetime |
| `artifacts/winlator/native-sapi/nvda-launch.c` | Detection/desktop integration and Super Liam isolation |
| Android `XServerDisplayActivity.java`, `xserver/Keyboard.java`, `xserver/InputDeviceManager.java`, `core/ExecutableInputSettings.java`, `AdbControlHandler.java` | Launch, capture, device monitoring, reset, per-executable arrow filtering, ADB control |
| Android `winhandler/NativeSpeechControl.java` | Nonblocking speech cancellation |
| Android settings/menu resources | Separate rates, interruption option, Reset keyboard |

Gradle does not compile the native C payload. After native changes, rebuild
and copy the archive before building Android. Using existing staged runtime/MSI
dependencies and WSL/MinGW, from the workspace root:

```powershell
wsl bash artifacts/winlator/native-sapi/build-candidate.sh
Copy-Item artifacts/winlator/native-sapi/winlator-native-sapi-candidate.tzst upstream/Winlator/app/app/src/main/assets/winlator-native-sapi-candidate.tzst
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'C:\Users\willb\AppData\Local\Android\Sdk'
Push-Location upstream/Winlator/app
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon --console=plain
Pop-Location
```

For Android-only keyboard changes, the native payload need not be regenerated.
Verify embedded archive hashes and the signing certificate before updating with
`adb -s <device> install -r <apk>`. Do not uninstall or clear app data.

## Tests, diagnostics, and recovery

Native regression, using the staged isolated Wine prefix and dependencies:

```powershell
wsl bash artifacts/winlator/native-sapi/build-rpc-test.sh
wsl bash -lc 'cd "/mnt/c/Users/willb/programs/game native" && xvfb-run -a python3 artifacts/winlator/native-sapi/test-rpc-bridge.py 0'
```

It uses `/home/willb/winlator-sapi-diagnostic`, Wine, Xvfb, and the staged native
runtime/voice. It stops that isolated prefix's Wine processes at completion;
it does not reset the phone. Other rate/desktop diagnostics remain beside it.

After speech changes, run staged `sapi_acceptance32.exe` and
`sapi_acceptance64.exe` in a fresh container. Check results and listen, then
verify affected games' speech, navigation, cancellation, windows, and keyboard.
For build 85, also retry Light Cars Setup's Enter/Alt+N navigation and verify
Reset keyboard, Bluetooth reconnect recovery, and a
long Super Liam session. Provision automatically and use CLI diagnostics;
let the user perform game actions rather than manually driving the app.

Guest evidence:

- `C:\WinlatorNativeSapi\payload\configure.log`: provisioning.
- `C:\WinlatorNativeSapi\payload\backup`: original speech snapshots/files; retain.
- `C:\NVDA-Bridge\nvda-rpc-service.log`: readiness, RPC timings, Control requests.
- `C:\NVDA-Bridge\sapi-backend.log`: voice initialization, submissions/completion.
- `C:\NVDA-Bridge\native-launch.log`: detection and game-window startup.
- `C:\NVDA-Bridge\native-sapi-error`: failure details when present.
- Android private `files/guest-session.log`: lifecycle, X11 delivery, resets,
  and guest-process evidence; previous session is `guest-session.previous.log`.
- Android log tag `WinlatorInput`: key events and resets.

For deliberate speech rollback, run the staged
`configure-native-sapi.exe --rollback` inside the container while retaining its
backup directory. It restores saved registry/files and Wine SAPI registration;
RHVoice MSI packages remain. It is not a complete uninstall or app-data reset.

This workspace now contains only Winlator work. The abandoned GameNative,
Piper, and ARM64EC experiment has been removed. Keep Winlator historical
binaries and recovery backups locally; Git ignores them.

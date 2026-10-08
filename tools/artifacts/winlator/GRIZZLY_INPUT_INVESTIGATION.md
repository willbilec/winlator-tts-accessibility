# Grizzly Gulch input investigation

October 5, 2026. The current short-arrow workaround is not accepted for full
gameplay: the user reports occasional unwanted movement and shooting presses
that require three to five attempts with the short-pulse workaround. With that
setting disabled, the user now confirms shooting works completely fine while
menus skip options. Replacement candidates have since been built and tested
on the phone; the current candidate is described below.

## Fix implementation and live results

**Current: Build 90**, `11.2-tts55-bavisoft-raw-queue-toggle`, freshly installed
in Chillingham. The DirectSound experiment below failed and was reverted. Its
per-game registry value was removed with Wine stopped after an initial live
registry deletion failed to persist across restart. Only the global
`dsound=native,builtin` setting remains.

Build 88 keeps the version-8 keyboard and processes already-queued raw WM_INPUT
packets on their owning Wine thread before returning the original snapshot.
A sent WM_NULL alone can overtake queued raw input; this callback explicitly
dispatches pending real raw packets through Wine's original window procedure.
It does not buffer/replay presses or replace state bytes. It selects the actual
registered raw keyboard target rather than the separate legacy mouse worker.
The production build has no input telemetry thread or per-key log. A one-time
launcher status reads an exported flag: `raw_input_queue_status=00000003`
confirms handler installation and a completed drain in the live game.

Native queued-message regression checks pass for down/up packets, completed
taps remaining released, holds, original errors and missing-worker fallback.
The Android build and five routing/reset tests pass; embedded helper and speech
payloads match source assets. APK SHA-256:
`616dbcb2741333b83cd864698de45062d175c5f1fa72e7b86b4914d09e232dcb`.
Build 89 changed the drain to dispatch one real raw keyboard packet per game
snapshot. The user confirmed that quick taps now work. Build 90 adds a
per-executable Input Controls checkbox, enabled by default for these two exact
executables and disabled by unchecking it. The launcher passes the choice to
the same helper; all other games retain their normal input path.

The user also reports that native Windows Chillingham takes control of the
keyboard, interfering with screen-reader shortcuts. The captured game requests
cooperative flags 6 (background, nonexclusive), so that observation alone does
not establish an exclusive-mode mismatch. Its acquisition routine treats any
nonzero result as failure and retries 50 times without sleeping. A working
native Windows DirectInput 8 test also returns S_FALSE on repeated Acquire,
matching Wine; no return-value normalization was deployed. A separate new
legacy/modern comparison could not acquire either device and is excluded.
Disassembly of the game's key-transition routine shows no minimum-hold check.

A separate bounded capture is now running from
`C:\\NVDA-Bridge\\bavisoft-capture\\capture-launcher.exe`. It uses Build 88's
same raw keyboard and queue-drain behavior, adds raw packet records (code 12,
value = virtual key | raw flags << 16, polls = make code), and records the
Chillingham logical state before each reported snapshot. Startup confirms
`raw_input_queue_status=00000003`. Its DLL and launcher are built by
`bridge/grizzly-input/build-capture.sh`; they do not replace the APK assets.
The first user capture still occasionally missed a press; the user estimated
tap 9 of the first 12 failed. The trace contains completed Right down/up pairs
processed about 0.05–0.15 ms apart, with no held-key snapshot. Those are Wine
processing intervals, not measured physical durations, and the estimated tap
number has not been mapped unambiguously to the trace. The first capture is
preserved on-device as `bavisoft-capture/capture-first.log`.

The timestamp-enhanced capture is freshly running. Raw packet `call_ms` now
records event age from GetTickCount minus GetMessageTime; Wine's X11 keyboard
path supplies the converted X11 event timestamp to SendInput. The analyzer
`bridge/grizzly-input/analyze-capture.py` reports processed duration, event ages,
derived event duration, held snapshots and logical state. The repeated physical
tap test is pending. Normal direct launches retain the quiet
Build 88 helper; pulsing, replay and the failed DirectSound override remain off.

The original Windows Chillingham executable in Downloads has the same SHA-256
as the phone copy. A timing-only callsite diagnostic leaves native COM objects
and returned input untouched; its Windows run observed approximately 100 ms
between some keyboard reads, with API calls around 0.001–0.002 ms. Therefore
the earlier Wine gaps alone do not establish slower game polling. This diagnostic
was stopped after sampling; its Windows game process was subsequently found
still alive and terminated during the acquisition comparison. The isolated Wine SendInput comparison returned
access denied and supplies no valid tap-retention comparison.

After the user requested fixing the issue as well as investigating it, a
Grizzly-specific guest compatibility library and launcher were implemented in
`bridge/grizzly-input`. They hook only this process's legacy keyboard interface.
The game's executable and saves remain unchanged. Android short-key pulsing
remains disabled.

- v1: checking asynchronous arrow state alone did not fix menu skipping.
- v2: synchronizing with Wine's legacy input worker after a polling pause fixed
  menus and shooting according to the user. Some taps still required a hold.
- v3: regular synchronization improved responsiveness; the user still reported
  misses for very brief taps. Sparse logs were insufficient to establish a
  stall; the user explicitly confirmed the game was responding.
- v4: retaining buffered presses introduced unintended actions. It was stopped
  and the working v2 restored immediately. v4 is rejected for deployment.
- v5: the revised buffered-history candidate also produced spontaneous actions,
  including after the Bluetooth keyboard was disconnected. It is rejected.
  The v2 fallback was restored; neither replay candidate is current deployment.
- v6: source-only experiment synchronizing every legacy snapshot. Not deployed.
- v7: replaces only the game's keyboard device with a DirectInput 8 device,
  preserving the legacy factory and the shared device ABI. Wine 10.10 selects
  raw input for this device instead of its legacy low-level hook. No buffered
  data is replayed, and no key bytes or events are synthesized. Chillingham has
  freshly launched on Build 85 using a separate manual launcher. Its trace
  confirms the v7 DLL loaded, legacy factory version 0x500, successful raw
  keyboard creation at 0x800, 256-byte format, cooperative flags 6 and acquire.
  The user first confirmed no menu skips, but reported needing longer holds.
  Subsequent testing identified repeatable quick-tap misses around Inspect,
  Take and Use, even after the audio finishes and an additional two-second
  pause. A half-second hold reliably succeeds. The user's native Windows test
  does not need that hold. v7 therefore remains incomplete for brief taps.
- Clean v7 removes all device snapshot hooks and the input telemetry thread;
  it only adapts keyboard creation. Its real-device ABI test passes. Initially
  this ran on Build 85, whose Android X11 key tracing was still enabled. Build
  86 now disables that tracing and per-key Logcat calls unless Wine debugging
  is explicitly enabled; normal Wine output is limited to errors. The current
  session has zero X11/per-key trace rows, no DirectInput registry overrides,
  and the clean raw helper is loaded through the normal Chillingham launch.

The rejected replay candidates passed their synthetic tests but failed live
acceptance. Those tests do not validate v7. A new real Windows DirectInput test
passes 1,000 raw keyboard creation/release cycles through the legacy device ABI,
including format, cooperation, acquisition, snapshots and unacquired errors.
This confirms API compatibility, not Wine timing or audible gameplay acceptance.

Build 86 integration provisions the separate compatibility helpers and routes
normal direct launches of exactly `Grizzly Gulch.exe` and `Chillingham.exe`
through them. The clean, quiet Build 86 is now installed with data preserved.
Other game launches and the
native speech payload are unchanged. Earlier proposals and statements below are
historical investigation context, not current deployment instructions.

The v7 Build 86 APK passed `testDebugUnitTest assembleDebug` (two Bavisoft routing
tests and three keyboard reset tests). Embedded launcher/library assets match
their source assets, and the embedded native speech archive retains SHA-256
`bd33c79e1a686ba4e7357e73a78db9c7db43d8c0774463ea017f27a8c36d6323`.
Installed clean, quiet Build 86 APK SHA-256:
`c6fb1ecdadba6021bd97fdc39613e0f55e28b3f3e945b609845e9423633f9ffd`.
Clean helper SHA-256:
`876cccb0dcb09ba211a8bfabfdf54dcac1d508fd286cac022910401b7ac8d5d6`.
The original diagnostic/replay APKs were never installed. Current Chillingham
uses `C:\NVDA-Bridge\grizzly-input` on Build 86. Old manual helper files are
retained for rollback and are not loaded. Grizzly's executable hash still matches
the original backup. Live brief-tap acceptance of this quiet session is pending;
Grizzly gameplay and fresh audible SAPI acceptance also remain unverified.

## Remaining short-tap investigation and DirectSound candidate

Waiting until audio ends plus two seconds did not eliminate the user's misses
around Inspect/Take/Use; a half-second hold reliably succeeds. Windows does not
require that hold. This is an unresolved runtime difference, not accepted as a
normal game behavior. Quiet Build 86 also missed taps: the user reports a missed
press after roughly six to ten successful presses, regardless of the selected
option. Disabling tracing did not resolve that remaining issue.

The installed container globally overrides `dsound` to `native,builtin`.
Chillingham actually loaded a Microsoft DirectSound DLL, version
6.1.7600.16385 (Windows 7), from syswow64. Audio Games Manager's two installers
do not request this override and use Wine's default built-in DirectSound.
Chillingham's menu playback routine checks DirectSound buffer status inside
its keyboard/menu loop. This is a concrete configuration difference worth
testing; it is not yet a proven cause of missed brief presses.

Build 87 (`11.2-tts54-bavisoft-dsound-candidate`) is now installed with data
preserved; quiet Build 86 rollback was pulled and hash-verified first.
Its launcher sets the Wine AppDefaults `dsound=builtin` override only when the
original target is exactly Chillingham.exe or Grizzly Gulch.exe. Other DLL
override settings and the container-wide override are unchanged. The initial
Windows-side environment override did not affect Wine's actual loaded DLL;
module verification caught this, and the registry-based launcher replaced it.
It keeps the same clean raw-input helper and quiet Android logging as Build 86.
The APK built successfully and all three embedded helper/speech assets match
their source files. APK SHA-256:
`6af650a3ff662d619070d74830fdea6f50fe1acaffbbde691eb7c3f47b0225ea`.
The installed quiet Build 86 APK is backed up as
`rollback/2026-10-05-R5CWA1XFMZJ/grizzly-input-fix/installed-build86-quiet-raw.apk`.
The shared Gradle APK output and installed app now contain Build 87. The
launcher reports registry_result=0, and the live Chillingham process maps
`/opt/wine/lib/wine/i386-windows/dsound.dll`, replacing the Microsoft syswow64
DLL. The clean raw helper is the only game-specific input DLL loaded. User
acceptance of repeated quick taps, holds and menu audio is pending.

Rebuild the two helper assets in WSL with
`bash bridge/grizzly-input/build.sh`, then build the Android APK. This script
does not rebuild or change the native SAPI archive. Build 86 rollback restores
the earlier APK; to undo this additional game-specific audio setting, remove
only the `dsound` value under `HKCU\Software\Wine\AppDefaults\Chillingham.exe\DllOverrides`
and, if Grizzly has been launched with Build 87, its matching AppDefaults key.
Both per-game values were absent before this experiment. The global DirectSound
override remains `native,builtin` throughout.

## Audio Games Manager comparison

The local `work/audiogame-manager` checkout's `.install/Chillingham.sh` and
`.install/Grizzly Gulch.sh` both install `vb6run mfc42`, extract the game and
launch its original executable. Neither overrides DirectInput nor changes
keyboard timing. The shared bottle script currently defaults to win64 and
system Wine. The Grizzly F11/F12 note adjusts combat speed only.

Both game executables request DirectInput 5 and contain byte-identical keyboard
snapshot, transition and logical-state reset routines. The shared behavior
supports investigating the Winlator runtime rather than assuming a general
Wine incompatibility. Wine's keyboard implementation selects raw input only
for version 8 devices (`dlls/dinput/keyboard.c`, Wine 10.10 reference checkout).
v7 tests that actual backend distinction while leaving other devices legacy.

## What the earlier conversation established

Reviewed the Grizzly Gulch turns in **Run Light Cars in Winlator**, including
the original event tracing, 25 ms experiment, 5 ms experiment, immediate
dispatch change, per-executable setting, and disk-logging optimization.

- Ordinary quick Right taps lasted about 92–123 ms. Earlier tracing showed one
  Android down/up pair and one Wine KeyPress/KeyRelease pair, without Android
  repeats. The user nevertheless heard two menu choices.
- A 25 ms synthetic press still skipped two choices. Reducing it to 5 ms helped
  the tested menu. Sending it immediately improved responsiveness.
- These were menu acceptance results. Shooting and complete gameplay were not
  verified. The latest user report supersedes the earlier broad impression
  that the workaround was successful.

The current `XServerDisplayActivity` filter sends down immediately, sends up
after 5 ms, and sends another down at 200 ms if the physical key remains held.
The real release ends that second hold. This deliberately turns a long physical
hold into two separate guest presses and makes a short press at most 5 ms.
Android's main-thread Handler schedules the synthetic events, so these are
requested delays rather than precise guest-observed timings.

## New executable evidence

Disassembled the backed-up 507,904-byte executable at
`rollback/2026-10-05-R5CWA1XFMZJ/grizzly-input-trace/Grizzly Gulch.exe` using
PE import inspection and x86 disassembly. Addresses below are preferred-image
virtual addresses, with image base `0x400000`.

| Address | Observation |
| --- | --- |
| Import `0x426000` | `DINPUT.dll!DirectInputCreateA` |
| `0x422010` | Shared input update routine |
| `0x42202B` | Calls device vtable offset `0x24` with a 256-byte buffer: legacy keyboard `GetDeviceState` |
| `0x42204C`–`0x422067` | Iterates the keyboard snapshot, tests each key's high bit, and updates mapped game-key states |
| `0x422240` | State transitions: 0 idle, 2 newly pressed, 3 held, 1 just released, then back to 0 |
| `0x421EC0` | Returns the mapped game-key state |
| `0x421E80` | Clears mapped states, raw keyboard snapshot, timestamps, and associated coordinates |
| `0x40F759`, `0x40F764` | A menu-selection loop calls that clear routine before looping back to the shared input update at `0x40EEDD` |

The game really samples keyboard state; importing DirectInput was only a clue
in the previous investigation. The examined keyboard path does not consume a
buffer of press/release events. A 5 ms down/up that completes between samples
can therefore be entirely absent from the next snapshot. This is a mechanism
for missed presses, not a measurement of the live shooting polling interval.

Clearing mapped states also means a key that remains physically down can be
classified as newly pressed again on a subsequent poll. Different game paths
query these states differently; some test for any nonzero value, and others
compare against newly pressed state 2. A single synthetic pulse duration cannot
reliably reproduce all those behaviors.

Microsoft describes immediate DirectInput data as a current-state snapshot,
and buffered data as retained events:
[Buffered and Immediate Data](https://learn.microsoft.com/en-us/previous-versions/windows/desktop/ee416236(v=vs.85)).

## Investigation direction superseded

The earlier adapter proposal below is deferred. The user requested identifying
the Windows/Wine difference before implementing another workaround. It is a
possible design, not a demonstrated solution or an implementation instruction.

A possible candidate would replace the fixed-duration Android pulse with a
Grizzly-specific compatibility adapter at the guest DirectInput boundary.
Keep Android delivery as one physical down/up pair and retain the existing
keyboard-reset, lock-state, disconnect, focus, and stale-repeat protections.

The adapter should capture buffered keyboard transitions and expose a coherent
snapshot to the game's legacy `GetDeviceState` reader. A press that ends between
polls must be retained until an eligible gameplay poll observes it; release
must follow in order. It must also retain the physical held state separately
from an unconsumed press. Merely substituting a buffered queue for the snapshot
does not fix menu repetition.

Menu navigation requires one action per physical press. Gameplay must preserve
intentional held aiming/movement and repeated shots according to the game's
actual controls. Because the game clears its own state, the adapter or a narrowly
scoped game compatibility patch needs verified knowledge of menu versus action
context. Do not infer mode from an arbitrary timeout, speech completion, or an
unconfirmed window title. Identify and trace the relevant game paths before
implementing mode-sensitive behavior.

First diagnostic candidate requirements:

1. Log keyboard acquisition results, `GetDeviceState` results and poll gaps,
   buffered transitions, and the game paths consuming arrow state, with bounded
   logging away from the input event thread.
2. Compare normal physical delivery with the 5 ms filter disabled, separately
   in town menus, target range, and gunfights. The user performs those actions;
   provisioning and trace retrieval use CLI/ADB.
3. Verify where duplication occurs: physical transition, Wine buffer, game
   snapshot, or game action. Handle reacquisition and focus changes without
   replaying stale actions.
4. Build the compatibility candidate only for Grizzly, preserve the original
   executable and saves, and retain a rollback route. Do not change global
   DirectInput DLL overrides or apply edge-only behavior to other games.

A blanket larger pulse, additional delay, or universal one-poll key state is
not an adequate replacement: it trades missed shots against navigation skips
or destroys legitimate holds. A second synthetic press at 200 ms should not
be part of the replacement.

## Acceptance and remaining uncertainty

Acceptance requires a fresh launch and user-confirmed one-step menu navigation,
single-press shooting, deliberate long holds, rapid separate presses, combined
aim/fire input, release stopping movement, and normal speech/cancellation.
Also check menu transitions while a key is held and keyboard reset/reconnection.
Synthetic timing and queue tests can validate implementation invariants but do
not establish audible or gameplay acceptance.

## Live Windows/Wine investigation

The saved Grizzly `shortDpadTaps` setting was disabled for the ordinary-input
baseline. Its prior container configuration was backed up on the device as
`files/rootfs/home/xuser-1/.container-before-grizzly-input-investigation`.
No replacement game fix or APK has been installed.

### Isolated taps identify the double navigation

The user performed three separated quick Right taps in town and reported one
option for the first tap, two options for the second, and two for the third.
Android `WinlatorInput` recorded exactly one down/up per tap, all repeat counts
zero, all physical and handled, with the same guest focus:

| Tap | Android down / up (device wall time) | Physical duration | First game snapshot (trace ms) | Next game snapshot | User result |
| --- | --- | --- | --- | --- | --- |
| 1 | 20:51:43.511 / 43.589 | 78 ms | 424693.983: Right down, poll 1152976 | 425445.333: Right up, poll 1152977 | One option |
| 2 | 20:51:45.735 / 45.824 | 89 ms | 426917.692: Right down, poll 1164110 | 427755.868: **Right still down**, poll 1164111 | Two options |
| 3 | 20:51:48.484 / 48.558 | 74 ms | 429667.331: Right down, poll 1173235 | 430505.399: **Right still down**, poll 1173236 | Two options |

All listed snapshots returned S_OK, with mapped game arrow states zero before
the update. For taps 2 and 3, the second snapshot is about 838 ms after the first,
long after the 89/74 ms physical holds ended. There was no intervening physical
press. The next snapshot after the second unwanted action finally reports up
at 428505.170 / 431255.152 ms respectively.

Wall-clock and QPC trace times are different clocks; matching uses the isolated
press order and inter-press intervals (2224 ms / 2749 ms on Android versus
2223.709 ms / 2749.639 ms in the game trace), not an asserted absolute clock
conversion. Evidence is saved in `work/grizzly-input-diag/wine-game-normal-input.log`
and `work/grizzly-input-diag/wine-game-android-input.log`.

This localizes the observed duplication to stale held state returned at the
guest legacy DirectInput boundary after a game polling pause. The game clears
its logical states and reclassifies the still-down snapshot as a new press.
It is not an extra Android press or physical autorepeat in this capture.
The internal cause of delayed release delivery across X11/Wine's input thread
still needs a targeted synchronization test; the trace does not distinguish
those internal stages by itself.

The replacement direction is to make the legacy snapshot current after event
polling and preserve acquisition/held-key semantics, scoped to Grizzly until
verified. Do not implement a buffered event replay or time-based menu adapter
merely on the basis of the earlier proposal. First test a pass-through legacy
diagnostic that pauses polling like the game and compares Windows and Wine
release freshness. The user subsequently confirmed that shooting works
completely fine with ordinary input while menus skip options. Preserve that
working shooting path and legitimate holds. Do not treat shooting as an
established underlying Wine failure: the missed-shot report was associated
with the pulse-enabled workaround, and the exact cause of those misses was
not directly traced.

The actual executable requests DirectInput version `0x500`, uses keyboard
cooperative flags 6 (background, nonexclusive), and reads immediate 256-byte
snapshots. The installed Wine reports version 10.10. Its matching source is
`brunodev85/wine-10.10-custom`, checked out independently at
`2b9afc38d9531d384f038bbaaf3f637e515fb297`; the modified GameNative Wine source
was not used as the installed-Wine reference.

In that Wine implementation, keyboards below DirectInput 8 use a low-level
keyboard hook and cached state; version 8 selects raw input. `keyboard_poll`
calls `check_dinput_events`, whose own comment says Wine's X11 implementation
needs event polling that Windows does not. Keyboard unacquisition clears the
cached state; acquisition does not repopulate it. Relevant source files are
`dlls/dinput/keyboard.c` and `dlls/dinput/dinput_main.c` in the independent checkout.

Physical diagnostic results:

- With ordinary delivery, the Wine diagnostic saw separate down/up transitions
  on both legacy and modern interfaces, including releases after long holds.
  That does not by itself reproduce the game's scene changes or audio pauses.
- In Wine, unacquiring and acquiring the legacy keyboard while Right was held
  made legacy Right become up until a new event; modern Right remained held
  until the physical release. Saved trace: `wine-reacquire-held.log`.
- A **legacy-only** Windows diagnostic saw Right down at 3802.944 ms,
  reacquisition at 4204.780 ms, and continuous down until physical release at
  6672.502 ms. Saved trace: `work/grizzly-input-diag/windows-legacy-only-reacquire.log`.
- The initial dual-interface Windows diagnostic was invalid for the legacy
  comparison: legacy remained zero while modern received the keys. Do not use
  it to infer legacy Windows behavior. A Wine legacy-only repeat was requested
  but the running-game launch did not switch programs; it was not performed.

The held-key reacquisition difference is concrete evidence worth isolating,
but is not yet proven to cause either double menu navigation or missed shots.
The Wine and usable Windows runs differ in whether a second input interface
was active; repeat a matched single-interface test before treating that detail
as fully controlled.

The game's captured normal-input trace ran through a standalone diagnostic DLL
and launcher in `C:\\NVDA-Bridge`. It records acquisition results, keyboard
snapshots, poll counters, and the game's mapped state before each reported
snapshot. A bounded memory buffer is flushed by a separate worker; no keyboard
bytes, API arguments, or return values are intentionally changed on Wine.
The original executable and saves are untouched by the instrumentation.

Observed game behavior includes repeated `Acquire` calls returning S_FALSE
and 50 immediate retries, new keyboard objects between contexts, and intervals
of roughly 400–800 ms between some action-time polls despite rapid idle polling.
S_FALSE means already acquired and must not be called a Wine error without a
Windows comparison. The user reproduced two-option navigation with pulsing
disabled. Correlating isolated physical taps with these snapshots remains
necessary before assigning the duplication to a particular layer.

A copied Windows game startup did not provide a valid game input comparison:
with the per-instance vtable tracer its CreateDevice calls returned
`0x80070057`. This run is excluded; it does not contradict the user's working
Windows game. The standalone native legacy diagnostic above is the usable
Windows evidence.

Windows 11 working normally supports a compatibility/timing explanation. It
does not identify a specific Wine defect, prove that every unwanted movement
has the same cause, or establish that the game itself has no timing assumptions.
The original double navigation now has matched physical/game traces. Ordinary
shooting has user-confirmed acceptance; pulse-enabled missed shots have not
been separately traced. The 5 ms workaround has a demonstrated design
limitation; a complete replacement remains to be implemented and accepted.

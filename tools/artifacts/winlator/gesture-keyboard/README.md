# Arrow Key Checker

Current deployment and acceptance status live in [the Winlator handoff](../README.md).
This Windows diagnostic stays open until explicitly closed. It speaks arrow press
and release transitions, shows held arrows, and logs Windows key events to
`C:\NVDA-Bridge\gesture-input-probe.log`. Keyboard repeats do not repeat speech.
It loads the existing native NVDA controller bridge; it does not replace speech
DLLs or alter game input settings.

Choose **Gesture management**, select this program, and set **Swipe behavior for
this game** to **Hold until finger lift**. Swipe in a direction, leave your finger
down for several seconds, then lift. The arrow should remain held until lift.
**Quick presses** should release while the finger remains down. Stationary long
press is a separate gesture and needs a key assignment. With default tap maps,
two taps produce two Enter presses; an assigned double tap emits its chosen key
once after recognition. Four-finger tap opens the menu.

Build from the workspace root with the installed MinGW compiler under WSL:

```sh
x86_64-w64-mingw32-gcc -O2 -Wall -Wextra -mwindows \
  artifacts/winlator/gesture-keyboard/input-probe.c \
  -o artifacts/winlator/gesture-keyboard/input-probe64.exe -luser32
```

Deploy with ADB while the checker is closed:

```sh
adb push artifacts/winlator/gesture-keyboard/input-probe64.exe /data/local/tmp/winlator-gesture-input-probe64.exe
adb shell run-as com.winlator cp /data/local/tmp/winlator-gesture-input-probe64.exe files/rootfs/home/xuser-1/.wine/drive_c/NVDA-Bridge/gesture-input-probe.exe
```

`Arrow Key Checker.desktop` is the Container 1 shortcut template. The current
checker SHA-256 is
`7B1AAE1AB0D7C576534999A6B89B48CC062E30D3FBE80BADB9A64766749E6FEA`.
The Android test in `GestureDeviceTest` validates a diagnostic Wine window,
not the actual games or audible output. Follow the CLI instructions in
`upstream/Winlator/app/ADB_CONTROL.md`; reopen the checker after instrumentation.

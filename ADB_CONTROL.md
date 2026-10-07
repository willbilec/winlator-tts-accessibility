# ADB control

Winlator exposes small ADB entry points for container diagnostics and automation.
Settings use a headless broadcast receiver, so they work while a container screen
is already open. Program launches use a foreground Activity to satisfy Android's
background launch rules. The entry points accept only explicit launch, get, and
set operations; they do not execute shell commands. Android's `DUMP` permission
protects both entry points. The Android shell UID has this permission, while
ordinary apps do not.

## Launch a program

Program launches use the explicit Activity entry point so Android treats the
command as a foreground launch. Pass the container ID and an absolute host path
mounted into that container, or a DOS path mapped by one of its drives:

```sh
adb shell am start -W -n com.winlator/.AdbControlActivity \
  --es action launch --ei container_id 1 \
  --es path '"/storage/emulated/0/games/grizzly-gulch/Grizzly Gulch.exe"'
```

To open the container desktop without a program, omit `--es path ...`.
For Windows paths with spaces, quote the path inside the argument as shown.

## Read or change a container setting

Read a value from Android's log:

```sh
adb shell am broadcast -n com.winlator/.AdbControlReceiver \
  --es action get --ei container_id 1 --es setting envVars
adb logcat -d -s WinlatorAdbControl:I
```

Change a value:

```sh
adb shell am broadcast -n com.winlator/.AdbControlReceiver \
  --es action set --ei container_id 1 --es setting envVars \
  --es value 'WINEDEBUG=+event'
```

Supported settings are `name`, `envVars`, `screenSize`, `graphicsDriver`,
`dxwrapper`, `dxwrapperConfig`, `audioDriver`, `audioDriverConfig`,
`wincomponents`, `drives`, `hudMode`, `startupSelection`, `cpuList`,
`cpuListWoW64`, `box64Preset`, and `desktopTheme`. Use `default` for `cpuList`
or `cpuListWoW64` to restore the automatic CPU selection. `hudMode` and
`startupSelection` accept values 0 through 2. Container changes are saved
immediately and apply on the next container launch.

## Per-executable short arrow taps

The optional short-arrow-tap filter is saved per executable in each container.
In the game pop-up menu, open **Input Controls**, change **Single-step short
arrow taps for this executable**, and confirm. It only affects the executable
used to launch that game. Directly launching a container desktop has no
executable scope, so this option is disabled there. The dialog shows the
normalized executable path. Confirming the dialog changes the current session
immediately and saves the choice with that container.

ADB can control the same setting independently by passing both `setting`
(`shortDpadTaps`) and `executable` (an absolute guest path or mapped DOS path):

```sh
adb shell am broadcast -n com.winlator/.AdbControlReceiver \
  --es action set --ei container_id 1 --es setting shortDpadTaps \
  --es executable '/storage/emulated/0/games/grizzly-gulch/Grizzly Gulch.exe' \
  --es value true
adb shell am broadcast -n com.winlator/.AdbControlReceiver \
  --es action get --ei container_id 1 --es setting shortDpadTaps \
  --es executable '/storage/emulated/0/games/grizzly-gulch/Grizzly Gulch.exe'
```

Use `false` to disable it. Supported values are `true`/`false`, `on`/`off`,
and `1`/`0`. Short taps send a 5 ms press immediately; a held key re-engages
after 200 ms and stays held until released. Settings apply on the next launch
of that executable when changed through ADB. The Input Controls dialog updates
the active session immediately. Grizzly Gulch in Container 1 was confirmed to
advance one choice per tap; the latest input-path change improved response,
though slight latency remains. Subsequent gameplay reports include occasional
unwanted movement and missed shooting presses. The 5 ms pulse is an experimental
menu workaround and is not accepted for complete gameplay; do not treat the
earlier menu result as shooting acceptance. The investigation is recorded in
the workspace's `artifacts/winlator/GRIZZLY_INPUT_INVESTIGATION.md`.

## Results and errors

The receiver logs its result or validation error under `WinlatorAdbControl`:

```sh
adb logcat -d -s WinlatorAdbControl:I WinlatorAdbControl:E
```

This interface is for local ADB shell use. It requires the privileged Android
`DUMP` permission and rejects unsupported setting names and malformed enum
values.

# Winlator TTS Accessibility

This is a fork of [Winlator](https://github.com/brunodev85/winlator) that adds
text-to-speech and accessibility improvements for Windows audio games on Android.
It is based on [Winlator's Android app source](https://github.com/brunodev85/winlator-app).

**This is a very early work in progress.** Some games work, some still have
problems, and changes can introduce regressions. Testing has been limited to
a small set of games and one Android device. This is an unofficial fork.

## Download

Get the latest APK from the
[Releases page](https://github.com/willbilec/winlator-tts-accessibility/releases).
The first published build is **Build 94**, version
`11.2-tts55-auto-game-dependencies`. It is an ARM64 debug build for Android 8.0
or later, intended for early testing.

The app uses Winlator's existing `com.winlator` package ID. Updating an existing
installation depends on its signing certificate. Back up your containers and
saves before trying this fork. If Android rejects the APK because the signatures
do not match, do not uninstall your existing app until your data is backed up.

## What this fork adds

- Windows SAPI speech using native SAPI and RHVoice, including 32-bit and 64-bit
  game support.
- Speech for games that use the NVDA controller interface, through a background
  Wine speech helper. Full NVDA does not need to run.
- Separate regular text-to-speech and NVDA-interface speech speed settings.
- An option to stop the bridge's screen-reader speech with Control.
- Automatic installation of bundled dependencies for recognized audio games
  when their executables are launched directly.
- Keyboard reset and recovery controls, plus quick-tap keyboard compatibility
  for Grizzly Gulch and Chillingham.
- Compatibility work for Super Liam and Breed Memorial.

Speech setup runs automatically during normal container startup. Game files and
licenses are not included. Install your game, launch its executable through
Winlator, and choose the game's speech mode where applicable. Restart the game
after changing its screen-reader mode. Restart the container after changing
speech speed settings.

## Current testing and limitations

Audible speech has been confirmed in Manamon 2, Super Liam, Arcadelux, and Breed
Memorial across development builds. Quick taps were confirmed in Chillingham
after its keyboard fix. These reports are not a guarantee that every game or
device works, or a complete retest of every feature in Build 94.

This fork provides game speech. It does not provide full Windows screen reading,
general Wine menu or dialog accessibility, or braille. Arcadelux still has
loading delays. Long-session keyboard recovery and some installer focus problems
need further testing. Fresh-container behavior has not had a complete live test.

If keys become stuck, open the in-game Back menu and try **Reset keyboard**.
The reset is intended to preserve the running game and keyboard lock state.
Report failures with the build number, device and Android version, game version,
launch method, and steps to reproduce them. Include relevant logs when possible,
after checking them for personal information.

## Source and building

The Android application is at `app/`; native speech and keyboard tools are at
`tools/`. Winlator's upstream source history and license are retained. Generated
APKs, build caches, private device data, and rollback backups are excluded.

Clone the repository, then fetch the required large CJK font from the release:

```sh
git clone https://github.com/willbilec/winlator-tts-accessibility.git
cd winlator-tts-accessibility
python tools/fetch-build-font.py
```

Use JDK 17, Android SDK 35, NDK `24.0.8215888`, and CMake `3.22.1`. Configure your
Android SDK path through `local.properties` or `ANDROID_HOME`, then run:

```sh
./gradlew testDebugUnitTest assembleDebug --no-daemon --console=plain
```

The font script requires Python 3 and verifies the download's SHA-256. The font
is a release asset because GitHub public forks cannot upload new LFS objects.
The downloadable APK already includes it.

On Windows, use `gradlew.bat`. The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

The native speech payload is already bundled as an Android asset. Gradle does
not rebuild it. See [the native tools guide](tools/README.md) before changing
native speech or keyboard code.

Developer notes: [audio-game dependencies](AUDIO_GAME_DEPENDENCIES.md) and
[ADB controls](ADB_CONTROL.md).

## Credits and license

Winlator was created by [BrunoSX](https://github.com/brunodev85). This fork builds
on Winlator, Wine, Box86/Box64, RHVoice, and the developers of their dependencies.
Winlator's upstream credits include:

- [GLIBC patches / Termux Pacman](https://github.com/termux-pacman/glibc-packages)
- [Wine](https://www.winehq.org/)
- [Box86 and Box64](https://github.com/ptitSeb)
- [Mesa](https://www.mesa3d.org/)
- [DXVK](https://github.com/doitsujin/dxvk)
- [VKD3D](https://gitlab.winehq.org/wine/vkd3d)
- [CNC DDraw](https://github.com/FunkyFr3sh/cnc-ddraw)
- [RHVoice](https://github.com/RHVoice/RHVoice)

The upstream application license is retained in [LICENSE](LICENSE). Bundled
third-party components retain their respective licenses.

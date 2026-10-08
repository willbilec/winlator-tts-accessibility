# Native speech and keyboard tools

These sources accompany the modified Android application. The directory layout
retains the native scripts' relative paths: `bridge/` and
`artifacts/winlator/native-sapi/` share this directory as their workspace root.

## Native speech payload

The active sources are `bridge/sapi_backend.h`,
`bridge/nvda_android_rpc_service.c`, `bridge/nvda_sapi_client.c`, and the
configuration and launcher sources in `artifacts/winlator/native-sapi/`.
`bridge/nvda_rpc_v1_s.c` is generated RPC source retained for compilation.

The current Android asset is `../app/src/main/assets/winlator-native-sapi-candidate.tzst`.
Android-only changes can reuse it. Native changes require rebuilding it before
the Android build.

The native builder requires Linux or WSL, Python 3, MinGW cross-compilers for
i686 and x86_64 Windows, and tar with zstd support. It also requires external
Microsoft SAPI and RHVoice packages staged locally. Downloads and extracted
runtimes are excluded from Git:

- `artifacts/winlator/native-sapi/runtime/x86/*/sapi.dll`
- `artifacts/winlator/native-sapi/runtime/x64/*/sapi.dll`
- `artifacts/winlator/native-sapi/runtime/x86/` and `runtime/x64/` must also
  contain the extracted speech-common `.manifest` files used by
  `extract-phone-converters.py` (one manifest for each architecture).
- `artifacts/winlator/native-sapi/rhvoice/packages/*.msi`

The staged runtime is Microsoft SAPI 5.4 from Windows 7 SP1; RHVoice packages
used for this build are RHVoice 1.6.0 x86/x64, English 2.8.2, and Alan 4.0.2.
See the [Winetricks SAPI recipe](https://github.com/Winetricks/winetricks/blob/master/src/winetricks)
and [RHVoice package documentation](https://github.com/RHVoice/RHVoice/blob/master/doc/en/Binaries.md).
`extract-runtime.sh` documents the extraction paths and expected local inputs.

From the repository root, after staging dependencies:

```sh
bash tools/artifacts/winlator/native-sapi/build-candidate.sh
cp tools/artifacts/winlator/native-sapi/winlator-native-sapi-candidate.tzst app/src/main/assets/winlator-native-sapi-candidate.tzst
./gradlew testDebugUnitTest assembleDebug --no-daemon --console=plain
```

Keep main-thread audio warmup before starting the COM worker, both SAPI
architectures, voice and phone-converter registration, launcher-owned NVDA
detection, and Super Liam's game-only built-in SAPI override.

## Keyboard helper

Sources and regression checks are in `bridge/grizzly-input/`. Its `build.sh`
compiles the helper and launcher with MinGW directly into
`../app/src/main/assets/grizzly-input/` before the Android build. Keep keyboard
lock state, normal long holds, and the running guest intact during recovery.

## Testing

`build-rpc-test.sh` and the Python diagnostics exercise the native bridge.
Several diagnostics use a locally staged Wine prefix and game paths; review
their defaults and configure an isolated prefix before running them. They are
development tools rather than a portable one-command test suite.

Build success, generated audio, and RPC return codes do not establish audible
speech. After native changes, test fresh 32-bit and 64-bit guest speech, then
affected games' speech, Control cancellation, windows, and keyboard input.

The published Build 100 is the existing tested debug APK. Publication changes
documentation and native script paths to match this repository layout. Gradle
does not rebuild the native browser either; run its builder from the repository
root with `bash tools/artifacts/winlator/file-browser/build.sh` after changes.
The browser diagnostic executable is packaged only in the Android test APK.
Embedded runtime assets are checked against source assets before uploading.

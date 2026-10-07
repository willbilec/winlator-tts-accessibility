#!/usr/bin/env bash
set -euo pipefail
here="$(cd -- "$(dirname -- "$0")" && pwd)"
export WINEPREFIX=/home/willb/winlator-sapi-diagnostic
export WINEARCH=win64
export WINEDEBUG=-all
wine=/usr/bin/wine64-stable
if [[ "${1:-}" == --trace ]]; then
    export WINEDLLOVERRIDES=sapi=n
    WINEDEBUG=+reg "$wine" "$here/sapi-diagnostic64.exe" > "$here/isolated-registry-trace.log" 2>&1
    exit
fi
if [[ "${1:-}" == --render ]]; then
    export WINEDLLOVERRIDES=sapi=n
    "$wine" "$here/sapi-diagnostic64.exe" --render > "$here/isolated-render-diagnostic.log" 2>&1
    cat "$here/isolated-render-diagnostic.log"
    exit
fi
if [[ "${1:-}" == --regression ]]; then
    export WINEDLLOVERRIDES=sapi=n
    # Only the dedicated diagnostic prefix is modified by this negative control.
    "$wine" reg.exe delete 'HKLM\SOFTWARE\Microsoft\Speech\PhoneConverters' /f > "$here/isolated-regression.log" 2>&1
    "$wine" "$here/sapi-diagnostic64.exe" >> "$here/isolated-regression.log" 2>&1
    grep -q 'voice default=8004503a' "$here/isolated-regression.log"
    "$wine" reg.exe import 'C:\WinlatorNativeSapi\payload\phone-converters.reg' >> "$here/isolated-regression.log" 2>&1
    "$wine" "$here/sapi-diagnostic64.exe" --render > "$here/isolated-regression-restored.log" 2>&1
    grep -q 'voice default=00000000' "$here/isolated-regression-restored.log"
    grep -q 'render Speak=00000000' "$here/isolated-regression-restored.log"
    echo 'PASS: removing converter data reproduces 8004503a; restoring it passes default lookup and synthesis.'
    exit
fi
mkdir -p "$WINEPREFIX"
"$wine" wineboot.exe -u > "$here/isolated-wine.log" 2>&1
mkdir -p "$WINEPREFIX/drive_c/WinlatorNativeSapi" "$WINEPREFIX/drive_c/NVDA-Bridge"
cp -r "$here/payload" "$WINEPREFIX/drive_c/WinlatorNativeSapi/"
cp "$here/payload/x64/sapi.dll" "$WINEPREFIX/drive_c/windows/system32/sapi.dll"
export WINEDLLOVERRIDES=sapi=n
"$wine" regsvr32.exe /s 'C:\windows\system32\sapi.dll' >> "$here/isolated-wine.log" 2>&1
"$wine" reg.exe import 'C:\WinlatorNativeSapi\payload\phone-converters.reg' >> "$here/isolated-wine.log" 2>&1
for package in RHVoice-x64-v1.6.0-setup.msi RHVoice-language-English-v2.8.2-setup.msi RHVoice-voice-English-Alan-v4.0.2-setup.msi; do
    package_path='C:\WinlatorNativeSapi\payload\packages\'"$package"
    log_path='C:\NVDA-Bridge\'"$package.log"
    "$wine" 'C:\windows\system32\msiexec.exe' /i "$package_path" /qn /norestart /l*v "$log_path" >> "$here/isolated-wine.log" 2>&1 || {
        status=$?; echo "MSI failed: $package status=$status"; exit 1;
    }
done
"$wine" 'C:\WinlatorNativeSapi\payload\configure-native-sapi.exe' --select-voice >> "$here/isolated-wine.log" 2>&1 || true
"$wine" "$here/sapi-diagnostic64.exe" > "$here/isolated-sapi-diagnostic.log" 2>&1
cat "$here/isolated-sapi-diagnostic.log"

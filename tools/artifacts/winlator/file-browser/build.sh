#!/usr/bin/env bash
set -euo pipefail
here="$(cd -- "$(dirname -- "$0")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
x86_64-w64-mingw32-gcc -std=c11 -O2 -Wall -Wextra -Werror -municode -mwindows \
  -static-libgcc "$here/browser.c" -o "$here/accessible-browser.exe" \
  -lcomctl32 -luser32 -lshell32 -lws2_32 -lole32
mkdir -p "$repo/../app/src/main/assets/file-browser"
cp "$here/accessible-browser.exe" "$repo/../app/src/main/assets/file-browser/accessible-browser.exe"
sha256sum "$here/accessible-browser.exe"
x86_64-w64-mingw32-gcc -std=c11 -O2 -Wall -Wextra -Werror -municode -mwindows \
  -static-libgcc "$here/launch-probe.c" -o "$here/browser-launch-probe.exe" -luser32
mkdir -p "$repo/../app/src/androidTest/assets/file-browser"
cp "$here/browser-launch-probe.exe" "$repo/../app/src/androidTest/assets/file-browser/browser-launch-probe.exe"

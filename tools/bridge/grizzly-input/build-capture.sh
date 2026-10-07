#!/usr/bin/env bash
set -euo pipefail
workspace_root="$(cd "$(dirname "$0")/../.." && pwd)"
capture_dir="$workspace_root/work/bavisoft-raw-capture"
mkdir -p "$capture_dir"
i686-w64-mingw32-gcc "$workspace_root/bridge/grizzly-input/grizzly-input.c" \
    -o "$capture_dir/grizzly-input.dll" -O2 -s -shared -static-libgcc \
    -DRAW_INPUT_KEYBOARD -DRAW_INPUT_DRAIN -ldxguid -luser32
i686-w64-mingw32-gcc "$workspace_root/bridge/grizzly-input/grizzly-input-launcher.c" \
    -o "$capture_dir/capture-launcher.exe" -O2 -s -mwindows \
    '-DDEFAULT_GAME_PATH="E:/chillingham/Chillingham.exe"' -ladvapi32

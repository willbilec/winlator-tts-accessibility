#!/usr/bin/env bash
set -euo pipefail
workspace_root="$(cd "$(dirname "$0")/../.." && pwd)"
asset_dir="$workspace_root/../app/src/main/assets/grizzly-input"
mkdir -p "$asset_dir"
i686-w64-mingw32-gcc "$workspace_root/bridge/grizzly-input/grizzly-input.c" \
    -o "$asset_dir/grizzly-input.dll" -O2 -s -shared \
    -DRAW_INPUT_KEYBOARD -DRAW_INPUT_DRAIN -DNO_INPUT_TRACE -ldxguid -luser32
i686-w64-mingw32-gcc "$workspace_root/bridge/grizzly-input/grizzly-input-launcher.c" \
    -o "$asset_dir/grizzly-input-launcher.exe" -O2 -s -mwindows \
    -ladvapi32

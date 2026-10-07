#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
artifact_dir="$(dirname -- "$script_dir")"
source_file="$script_dir/unixlib-espeak.c"
archive="$artifact_dir/winlator-espeak-tts-fixed.tzst"
wine_source="${WINE_SOURCE:-/home/willb/proton-build-tts}"
output_dir="$artifact_dir/build/unixlibs"
build_dir="$(mktemp -d)"
trap 'rm -rf -- "$build_dir"' EXIT

mkdir -p "$build_dir/include/espeak-ng" "$build_dir/archive" "$output_dir"
tar --zstd -xf "$archive" -C "$build_dir/archive"
curl -fsSL \
    https://raw.githubusercontent.com/espeak-ng/espeak-ng/master/src/include/espeak-ng/speak_lib.h \
    -o "$build_dir/include/espeak-ng/speak_lib.h"

build_one() {
    local bits="$1"
    local wine_arch="$2"
    local output="$3"
    local library_dir="$build_dir/archive/opt/wine/lib/wine/$wine_arch-unix"
    local -a extra_flags=()
    if [[ "${TTS_TRACE:-0}" == 1 ]]; then extra_flags+=(-DTTS_TRACE); fi

    ln -s "$library_dir/libespeak-ng.so.1" "$build_dir/libespeak-ng.so"
    gcc "-m$bits" -shared -fPIC -O2 \
        "${extra_flags[@]}" \
        -D__WINESRC__ -DWINE_UNIX_LIB \
        -I"$build_dir/include" \
        -I"$wine_source/include" \
        -I"$wine_source/dlls/protontts" \
        "$source_file" \
        -L"$build_dir" -Wl,-rpath,'$ORIGIN' \
        -lespeak-ng -lpthread \
        -o "$output_dir/$output"
    rm "$build_dir/libespeak-ng.so"
}

build_one 64 x86_64 protontts-x86_64.so
build_one 32 i386 protontts-i386.so

file "$output_dir"/protontts-*.so
readelf -d "$output_dir/protontts-x86_64.so" | grep -E 'NEEDED|RUNPATH'

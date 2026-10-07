#!/usr/bin/env bash
set -euo pipefail

artifact_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
archive="$artifact_dir/winlator-espeak-tts-fixed.tzst"
scratch="$(mktemp -d)"
trap 'rm -rf -- "$scratch"' EXIT

tar --zstd -xf "$archive" -C "$scratch"
cp "$artifact_dir/build/unixlibs/protontts-x86_64.so" "$scratch/opt/wine/lib/wine/x86_64-unix/protontts.so"
cp "$artifact_dir/build/unixlibs/protontts-i386.so" "$scratch/opt/wine/lib/wine/i386-unix/protontts.so"
cp "$artifact_dir/build/windows/sapi-x86_64.dll" "$scratch/opt/wine/lib/wine/x86_64-windows/sapi.dll"
cp "$artifact_dir/build/windows/sapi-i386.dll" "$scratch/opt/wine/lib/wine/i386-windows/sapi.dll"
if [[ ! -e "$artifact_dir/build/winlator-espeak-tts-before-android.tzst" ]]; then
    cp "$archive" "$artifact_dir/build/winlator-espeak-tts-before-android.tzst"
fi
tar --zstd -cf "$archive.new" -C "$scratch" .
mv "$archive.new" "$archive"
sha256sum "$archive"

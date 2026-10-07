#!/usr/bin/env bash
set -euo pipefail
here="$(cd -- "$(dirname -- "$0")" && pwd)"
tools=/home/willb/tts-extract-tools
for package in /home/willb/cabextract_*.deb /home/willb/libmspack0t64_*.deb; do
    dpkg-deb -x "$package" "$tools"
done
export LD_LIBRARY_PATH="$tools/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
mkdir -p "$here/runtime/x86" "$here/runtime/x64"
echo 'e5449839955a22fc4dd596291aff1433b998f9797e1c784232226aba1f8abd97  windows6.1-KB976932-X86.exe
f4d1d418d91b1619688a482680ee032ffd2b65e420c6d2eaecf8aa3762aa64c8  windows6.1-KB976932-X64.exe' | (cd "$here" && sha256sum -c -)
"$tools/usr/bin/cabextract" -q -d "$here/runtime/x86" -F 'x86_microsoft-windows-speechcommon_31bf3856ad364e35_6.1.7601.17514_none_d809b28230ecfe46/sapi.dll' "$here/windows6.1-KB976932-X86.exe"
"$tools/usr/bin/cabextract" -q -d "$here/runtime/x64" -F 'amd64_microsoft-windows-speechcommon_31bf3856ad364e35_6.1.7601.17514_none_34284e05e94a6f7c/sapi.dll' "$here/windows6.1-KB976932-X64.exe"
"$tools/usr/bin/cabextract" -q -d "$here/runtime/x86" -F 'x86_microsoft-windows-speechcommon_31bf3856ad364e35_6.1.7601.17514_none_d809b28230ecfe46.manifest' "$here/windows6.1-KB976932-X86.exe"
"$tools/usr/bin/cabextract" -q -d "$here/runtime/x64" -F 'amd64_microsoft-windows-speechcommon_31bf3856ad364e35_6.1.7601.17514_none_34284e05e94a6f7c.manifest' "$here/windows6.1-KB976932-X64.exe"
find "$here/runtime" -name sapi.dll -exec file {} \;

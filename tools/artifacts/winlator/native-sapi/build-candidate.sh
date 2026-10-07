#!/usr/bin/env bash
set -euo pipefail
here="$(cd -- "$(dirname -- "$0")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
payload="$here/payload"
mkdir -p "$payload/x86" "$payload/x64" "$payload/packages"
cp "$here"/runtime/x86/*/sapi.dll "$payload/x86/sapi.dll"
cp "$here"/runtime/x64/*/sapi.dll "$payload/x64/sapi.dll"
cp "$here"/rhvoice/packages/*.msi "$payload/packages/"
python3 "$here/extract-phone-converters.py"
for bits in 32 64; do
    if [[ "$bits" == 32 ]]; then compiler=i686-w64-mingw32-gcc; else compiler=x86_64-w64-mingw32-gcc; fi
    "$compiler" -O2 -Wall -Wextra -Werror -shared -static-libgcc \
        "$repo/bridge/nvda_sapi_client.c" "$repo/bridge/nvdaControllerClient.def" \
        -Wl,--enable-stdcall-fixup -lole32 -lsapi -lwinmm -o "$payload/nvdaControllerClient${bits}.dll"
    if [[ "$bits" == 32 ]]; then
    "$compiler" -O2 -Wall -Wextra -Werror -Wno-missing-field-initializers -DNATIVE_SAPI -mwindows -static-libgcc \
        "$repo/bridge/nvda_android_rpc_service.c" "$repo/bridge/nvda_rpc_v1_s.c" \
        -lrpcrt4 -lole32 -lsapi -luser32 -lwinmm -lws2_32 -o "$payload/nvda_sapi_rpc_service${bits}.exe"
    fi
    "$compiler" -O2 -Wall -Wextra -Werror -static-libgcc \
        "$repo/bridge/sapi_acceptance.c" -lole32 -lsapi \
        -o "$payload/sapi_acceptance${bits}.exe"
done
x86_64-w64-mingw32-gcc -O2 -Wall -Wextra -Werror -static-libgcc \
    "$here/configure.c" -ladvapi32 -lole32 -lsapi -o "$payload/configure-native-sapi.exe"
i686-w64-mingw32-gcc -O2 -Wall -Wextra -Werror -static-libgcc \
    "$here/configure.c" -ladvapi32 -lole32 -lsapi -o "$payload/configure-native-sapi32.exe"
x86_64-w64-mingw32-gcc -O2 -Wall -Wextra -Werror -mwindows -static-libgcc \
    "$here/nvda-launch.c" -luser32 -ladvapi32 -o "$payload/nvda-launch.exe"
(cd "$payload" && sha256sum x86/sapi.dll x64/sapi.dll packages/*.msi *.exe *.dll phone-converters.reg > SHA256SUMS)
tar --zstd -cf "$here/winlator-native-sapi-candidate.tzst" -C "$here" payload
echo "Candidate: $here/winlator-native-sapi-candidate.tzst"

#!/usr/bin/env bash
set -euo pipefail
here="$(cd -- "$(dirname -- "$0")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
build="$here/runtime/rpc-test"
mkdir -p "$build"
x86_64-w64-mingw32-widl -m64 -h -H "$build/nvda_rpc_v1.h" "$repo/bridge/nvda_rpc_v1.idl"
x86_64-w64-mingw32-widl -m64 -Oif -s -o "$build/nvda_rpc_v1_s.c" "$repo/bridge/nvda_rpc_v1.idl"
x86_64-w64-mingw32-gcc -O2 -Wall -Wextra -Werror -Wno-missing-field-initializers \
    -DNATIVE_SAPI -DBRIDGE_TEST_RENDER -mwindows -static-libgcc \
    "$repo/bridge/nvda_android_rpc_service.c" "$build/nvda_rpc_v1_s.c" \
    -lrpcrt4 -lole32 -lsapi -luser32 -lwinmm -lws2_32 -o "$build/nvda_sapi_rpc_test64.exe"
x86_64-w64-mingw32-gcc -O2 -Wall -Wextra -Werror -Wno-missing-field-initializers \
    -DNATIVE_SAPI -DBRIDGE_TEST_RENDER -mconsole -static-libgcc \
    "$repo/bridge/nvda_android_rpc_service.c" "$build/nvda_rpc_v1_s.c" \
    -lrpcrt4 -lole32 -lsapi -luser32 -lwinmm -lws2_32 -o "$build/nvda_sapi_rpc_test_console64.exe"
x86_64-w64-mingw32-gcc -O2 -Wall -Wextra -Werror -Wno-cast-function-type -DACTIVE_CANCEL_TEST -static-libgcc \
    "$repo/bridge/probe.c" -o "$build/probe.exe"
cp "$repo/work/tolk/libs/x64/nvdaControllerClient64.dll" "$build/nvdaControllerClient.dll"
x86_64-w64-mingw32-gcc -O2 -Wall -Wextra -Werror -Wno-cast-function-type \
    -DACTIVE_CANCEL_TEST -DEXTERNAL_CONTROL_TEST -DNO_CANCEL -static-libgcc \
    "$repo/bridge/probe.c" -o "$build/external-control-probe.exe"
echo "Built stock-client RPC regression in $build"

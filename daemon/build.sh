#!/bin/sh
# Builds remapd for Android as static binaries (no NDK needed):
#   daemon/remapd         32-bit ARM, runs on 32- and 64-bit ARM devices
#   daemon/remapd-arm64   64-bit ARM
# Needs zig: https://ziglang.org/download/ or `pip install ziglang`.
set -e
cd "$(dirname "$0")"
if command -v zig >/dev/null 2>&1; then
    ZIG="zig"
else
    ZIG="python3 -m ziglang"
fi
$ZIG cc -target arm-linux-musleabihf -mcpu=cortex_a7 -static -Os -Wall -Wextra -o remapd remapd.c -Wl,-s
$ZIG cc -target aarch64-linux-musl -static -Os -Wall -Wextra -o remapd-arm64 remapd.c -Wl,-s
echo "Built daemon/remapd (32-bit ARM) and daemon/remapd-arm64."

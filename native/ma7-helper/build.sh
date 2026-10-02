#!/bin/sh
# Cross-compiles the 32-bit MA-7 helper with MinGW-w64 (apt: gcc-mingw-w64-i686).
# The result is written straight to the jar resources, from where it is bundled as
#   src/main/resources/opendoja/audio/mld/ma7/ma7_helper.exe
# --no-insert-timestamp makes the build reproducible (same source + toolchain = same bytes).
set -e
cd "$(dirname "$0")"
OUT=../../src/main/resources/opendoja/audio/mld/ma7
mkdir -p "$OUT"
i686-w64-mingw32-gcc -O2 -Wall -mwindows -static -Wl,--no-insert-timestamp \
    -o "$OUT/ma7_helper.exe" ma7_helper.c
echo "Built $OUT/ma7_helper.exe"

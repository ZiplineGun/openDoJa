# ma7_helper — 32-bit host process for the Yamaha MA-7 DLLs

## Why this exists

The Yamaha MA-7 emulator DLLs (`M7_EmuSmw7.dll`, `SMAFMMS7EMU.dll`,
`M7_EmuHw.dll`, `EMOJIMGR.dll`) are x86-only, and openDoJa runs on a 64-bit
JVM. Like foo_mmf does for foobar2000, openDoJa hosts
the DLLs in a small 32-bit helper process and talks to it from the 64-bit
side — here over the helper's stdin/stdout instead of a named pipe, since
`java.lang.ProcessBuilder` makes that trivial and openDoJa never needs more
than one Java process talking to one helper at a time.

The DLL call sequence in `ma7_helper.c` (load order, the 72-byte
`Mapi_EmuInitialize` configuration, device reset calls, `Mapi_Melody_*`
open/standby/start sequence, tail/quiet detection heuristic for end-of-song)
is a MA-7-only port of `foo_mmf`'s `src/yamaha.cpp`, which is a working,
field-tested reference for driving exactly this DLL set. `foo_mmf` is MIT
licensed; its copyright and permission notice is reproduced in the header of
`ma7_helper.c`. MA-5 support,
foo_mmf's reverb/chorus toggles and multi-song batching were dropped since
openDoJa only needs single-stream MA-7 playback.

## Protocol

See the comment block at the top of `ma7_helper.c`. In short: 16-byte
little-endian headers (`magic, version, command, payloadBytes`) framing
`OPEN` (song bytes + DLL folder + volume/loop count → length/rate/channels),
`READ` (tempo/transpose/volume + block count → interleaved float32 PCM +
an EOF flag), `SEEK` (milliseconds) and `QUIT`. Any failure is reported as
an `ERROR` frame with an ASCII message, after which the helper exits.

## Building

```sh
# Debian/Ubuntu: apt install gcc-mingw-w64-i686
native/ma7-helper/build.sh
```

This cross-compiles `ma7_helper.exe` (a small, statically-linked, PE32/x86
console binary depending only on `KERNEL32.dll` and `msvcrt.dll`) straight into
`src/main/resources/opendoja/audio/mld/ma7/ma7_helper.exe`, from where it is
bundled into the openDoJa jar and extracted to a temp directory on first use
(see `MA7Locator`). The build is reproducible (`--no-insert-timestamp`): the
same source and toolchain give the same bytes, so the checked-in binary can be
verified by rebuilding it. A prebuilt copy is checked in so the Java build does
not require MinGW.

To use a locally built executable instead, put it next to the DLLs as
`.opendoja/bridge/ma7/ma7_helper.exe` (or point `helper=` in `bridge.ini` at it).

## Verification status

Verified on real Windows: audio is produced and the master volume can be
changed. Those two are the only paths that have been explicitly checked so far;
`SEEK`, looping and repeated open/close are exercised by the Java side but have
no dedicated test.

Wine is **not** usable as a stand-in: under Wine 9 `Mapi_EmuInitialize`
consistently returns `-3`, even with the exact `foo_mmf` initialisation
sequence, Xvfb, a PulseAudio server, a Windows 2000/XP compatibility mode or a
prior `CoInitializeEx`. The DLLs link against `DSOUND.dll`/`WINMM.dll`, so they
probably probe real audio-driver behaviour during initialisation. (Loading of
all four DLLs, the `Mapi_*` exports, the stdin/stdout framing and error
propagation do work under Wine.)

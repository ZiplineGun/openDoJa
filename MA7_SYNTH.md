# MA-7 DLL synth backend

The `ma7` MLD synth backend sits alongside the FueTrek and MA3 software
synthesizers: it plays through the real Yamaha MA-7 emulator DLLs, using the same
approach as foo_mmf (a foobar2000 component that plays `.mld`/`.mmf` files
through these DLLs).

## Setup

Everything lives in one folder next to the openDoJa jar, the *bridge folder*:

```
<folder of the openDoJa jar>/
  openDoJa.jar
  .opendoja/
    storage/ext0/            (existing)
    bridge/
      ma7/                   <- named after the synth id
        bridge.ini           <- settings (created on first use)
        M7_EmuSmw7.dll
        SMAFMMS7EMU.dll
        M7_EmuHw.dll
        EMOJIMGR.dll
        ma7_helper.exe       <- optional, replaces the helper bundled in the jar
```

1. Get the MA-7 DLLs (`M7_EmuSmw7.dll`, `SMAFMMS7EMU.dll`, `M7_EmuHw.dll`,
   `EMOJIMGR.dll`) — not redistributed here — and put them in `.opendoja/bridge/ma7/`.
   If that folder does not exist yet, select the MA-7 synth once: openDoJa creates the
   folder and a commented `bridge.ini` for you and prints where it expects the DLLs.
2. Pick "MA-7 DLL" from the launcher's Synth menu, or set `-Dopendoja.mldSynth=ma7`.

If the DLLs are not found, the command line shows, for example:

```
ma7-helper: No DLLs found in D:\openDoJa\.opendoja\bridge\ma7
ma7-helper: required: M7_EmuSmw7.dll, SMAFMMS7EMU.dll, M7_EmuHw.dll, EMOJIMGR.dll
ma7-helper: put them in that folder, or set dll_dir in D:\openDoJa\.opendoja\bridge\ma7\bridge.ini (or -Dopendoja.ma7DllDir=<folder>)
```

This only runs on Windows (the DLLs are 32-bit Windows binaries); openDoJa
itself can stay on a 64-bit JVM, since the DLLs are hosted in a bundled
32-bit helper process (`native/ma7-helper`, see its README for the protocol,
build steps, and — importantly — the current verification status).

## bridge.ini

Plain `key=value` text. `;` or `#` at the start of a line is a comment, values are taken
literally (Windows paths need no escaping; surrounding double quotes are optional), keys are
case-insensitive, and `[section]` lines are accepted but ignored. A relative path is resolved
against the bridge folder. An empty value means "not set".

| key       | `-D` override            | default                                              |
|-----------|--------------------------|------------------------------------------------------|
| `dll_dir` | `-Dopendoja.ma7DllDir`   | the bridge folder itself                             |
| `helper`  | `-Dopendoja.ma7Helper`   | `ma7_helper.exe` in the bridge folder, else the copy bundled in the jar |
| `volume`  | `-Dopendoja.ma7Volume`   | `64` (Yamaha master volume, 0–127)                   |

Precedence is `-D` property, then `bridge.ini`, then the default. `dll_dir` is the only way to
point at DLLs elsewhere (for example an existing Yamaha install); there is no other search path.

`-Dopendoja.bridgeRoot=<folder>` moves the whole `.opendoja/bridge` folder. This is mainly for
running from a build directory such as `target/classes`, where `.opendoja` would be deleted by a
clean build.

## Adding another bridge

A bridge is `MLDSynth` id + folder `.opendoja/bridge/<id>/` + helper `native/<id>-helper`.
`opendoja.audio.mld.bridge.BridgeConfig` provides the folder, the `bridge.ini` template, the
`-D` > ini > default lookup and the missing-DLL message (`<id>-helper: No DLLs found in
<dll_dir>`); a new backend only supplies its id, its required DLL names and its ini template
(see `MA7Locator`). Each bridge has its own helper executable.

## How it fits into openDoJa's audio pipeline

`opendoja.audio.mld.ma7` implements the same `SamplerProvider`/`Sampler`
backend interface as `fuetrek`/`ma3`, but the shape of the work is different:
those are note-driven software synthesizers that `MLDPlayer` feeds one
`keyOn`/`keyOff`/`programChange` call at a time. The Yamaha DLLs, on the
other hand, are a whole-song sequencer: you load the raw file once, and they
parse and play it themselves. So here:

* `MA7Sampler` (the note-driven half) is essentially a stub — the DLLs don't
  take individual note events, so there's nothing meaningful to forward.
* `MA7MLDPlaybackEngine` (the synth-owned "overlay audio" hook that
  `fuetrek`/`ma3` use for sound-effect resources) does the real work: it
  streams PCM from the DLL, overlaying it on `MA7Sampler`'s silence, using
  the frame position `MLDPlayer` passes in to keep the DLL's own song
  position aligned with `MLDPlayer`'s (which keeps running its own event
  clock over the same file regardless of the chosen backend).
* `MLD.getRawData()` is a small new accessor (the DLLs need the original
  encoded bytes; the rest of openDoJa's decoder only keeps the parsed form).

See the class-level Javadoc on `MA7MLDPlaybackEngine` for how discontinuities
(seeks) are detected and resynced, and the one known trade-off: when a
cue-point loop's *own* Yamaha-side playthrough finishes but openDoJa still
expects the loop to continue indefinitely (the common case for background
music), the engine restarts the DLL from the very beginning of the file
rather than precisely re-looping the cue-to-cue segment, so a file with a
distinct intro will replay its intro on every loop instead of skipping it
after the first pass.

## Status

Verified on real Windows: MLD playback through the DLLs and master volume
changes (`volume` / `-Dopendoja.ma7Volume`). Seeking, looping and the
cue-point-loop trade-off described above are implemented but have not been
checked as thoroughly. Wine cannot be used for testing (`Mapi_EmuInitialize`
fails there); see `native/ma7-helper/README.md`.

Notes:

* `bridge.ini` is read each time a song is opened, but the helper processes
  are pooled (idle ones are closed after 60 seconds) and a running helper keeps
  the DLLs it already loaded. After changing `dll_dir` or `helper`, wait a
  minute or restart openDoJa.
* `MLD` now keeps a copy of the original bytes of every MLD built from a
  `byte[]` (`MLD.getRawData()`); MLD files are small, so the cost is negligible.

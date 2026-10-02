/**
 * Yamaha MA-7 backend for MLD playback.
 *
 * <p>Unlike the {@code fuetrek} and {@code ma3} backends, which are note-driven
 * software synthesizers fed by {@code MLDPlayer}, the Yamaha MA-7 emulator DLLs
 * are whole-song sequencers: they parse the SMAF/MLD file themselves and hand
 * back 10 ms PCM blocks. The DLLs are also x86-only, so they are hosted in a
 * 32-bit helper process ({@code ma7_helper.exe}, see {@code native/ma7-helper})
 * that is driven over stdin/stdout, following the approach of foo_mmf.</p>
 *
 * <p>The synth-owned {@link opendoja.audio.mld.MLDPlaybackEngine} is therefore
 * the component that produces audio: {@code MLDPlayer} keeps handling timing,
 * sync events, looping and end-of-playback, while the MA-7 engine overlays the
 * DLL's output, re-synchronising it to {@code MLDPlayer}'s frame position.</p>
 */
package opendoja.audio.mld.ma7;

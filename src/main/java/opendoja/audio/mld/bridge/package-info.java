/**
 * Shared plumbing for the synth backends that drive a vendor's MLD player DLLs through a
 * 32-bit helper process ("bridge").
 *
 * <p>Each bridge has its own folder {@code .opendoja/bridge/<id>/} (the id is the
 * {@code MLDSynth} id) holding {@code bridge.ini}, the DLLs and optionally a replacement
 * helper executable; {@link opendoja.audio.mld.bridge.BridgeConfig} reads it. The helper
 * itself is one executable per bridge ({@code native/<id>-helper}).</p>
 */
package opendoja.audio.mld.bridge;

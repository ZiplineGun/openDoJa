package opendoja.audio.mld.ma7;

import opendoja.audio.mld.MLD;
import opendoja.audio.mld.MLDPlaybackEngine;
import opendoja.audio.mld.Sampler;

import java.util.Arrays;

/**
 * Note-level {@link Sampler} facade for the MA-7 backend.
 *
 * <p>The Yamaha engine sequences the whole file itself, so the note, program,
 * pan, volume and pitch calls that {@code MLDPlayer} derives from the parsed
 * MLD are intentionally ignored here. All audio is produced by
 * {@link MA7MLDPlaybackEngine#render}, which is called right after
 * {@link #render} with {@code MLDPlayer}'s absolute frame position and can
 * therefore keep the DLL's song position aligned across seeks and restarts.
 * This sampler honours only the erase/add contract of {@code render}: it
 * contributes silence.</p>
 */
final class MA7Sampler implements Sampler
{
    private final float sampleRate;

    MA7Sampler(float sampleRate)
    {
        this.sampleRate = sampleRate;
    }

    @Override
    public MLDPlaybackEngine createPlaybackEngine(MLD mld, float sampleRate)
    {
        return new MA7MLDPlaybackEngine(mld, sampleRate);
    }

    /** This sampler never owns a note; liveness is reported by the engine. */
    @Override
    public boolean isFinished()
    {
        return true;
    }

    @Override
    public void render(float[] samples, int offset, int frames)
    {
        this.render(samples, offset, frames, 1.0f, 1.0f, true, true);
    }

    @Override
    public void render(float[] samples, int offset, int frames,
        float amplitude)
    {
        this.render(samples, offset, frames, amplitude, amplitude, true, true);
    }

    @Override
    public void render(float[] samples, int offset, int frames, float left,
        float right)
    {
        this.render(samples, offset, frames, left, right, true, true);
    }

    @Override
    public void render(float[] samples, int offset, int frames, float left,
        float right, boolean erase, boolean clamp)
    {
        if (samples == null)
            throw new NullPointerException("A sample buffer is required.");
        if (frames < 0)
            throw new IllegalArgumentException("Invalid frames.");
        if (offset < 0 || offset + frames * 2 > samples.length)
            throw new ArrayIndexOutOfBoundsException(
                "Invalid range in sample buffer.");
        if (erase)
            Arrays.fill(samples, offset, offset + frames * 2, 0.0f);
    }

    @Override
    public float sampleRate()
    {
        return this.sampleRate;
    }

    @Override
    public void reset()
    {
    }

    @Override
    public void stopAll()
    {
    }

    @Override
    public void sysEx(byte[] message)
    {
    }

    @Override
    public void bankChange(int channel, int bank)
    {
    }

    @Override
    public void drumEnable(int channel, boolean enable)
    {
    }

    @Override
    public void keyOff(int channel, int key)
    {
    }

    @Override
    public void keyOn(int channel, int key, float velocity)
    {
    }

    @Override
    public void masterTune(float semitones)
    {
    }

    @Override
    public void masterVolume(float volume)
    {
    }

    @Override
    public void panpot(int channel, float panpot)
    {
    }

    @Override
    public void pitchBend(int channel, float semitones)
    {
    }

    @Override
    public void pitchBendRange(int channel, float range)
    {
    }

    @Override
    public void programChange(int channel, int program)
    {
    }

    @Override
    public void volume(int channel, float volume)
    {
    }
}

package opendoja.audio.mld.ma7;

import opendoja.audio.mld.Sampler;
import opendoja.audio.mld.SamplerProvider;

/**
 * {@link SamplerProvider} for the Yamaha MA-7 DLL backend. Creating a provider
 * is cheap; the helper process is only started when a sequence is played.
 */
public final class MA7SamplerProvider implements SamplerProvider
{
    /** MA-7 renders natively at 44.1 kHz (48 kHz is also supported). */
    public static final float SAMPLE_RATE = 44100.0f;

    /** Yamaha master volume (0-127) used when neither {@code -Dopendoja.ma7Volume} nor bridge.ini sets one. */
    public static final int DEFAULT_VOLUME = 64;

    @Override
    public Sampler instance(float sampleRate)
    {
        if (Float.isNaN(sampleRate) || sampleRate <= 0.0f)
            throw new IllegalArgumentException("Invalid sampling rate.");
        if (sampleRate != 44100.0f && sampleRate != 48000.0f)
        {
            throw new IllegalArgumentException("The MA-7 DLL synth only " +
                "supports 44100 Hz and 48000 Hz output (got " + sampleRate +
                "). Adjust -Dopendoja.mldSampleRate.");
        }
        return new MA7Sampler(sampleRate);
    }
}

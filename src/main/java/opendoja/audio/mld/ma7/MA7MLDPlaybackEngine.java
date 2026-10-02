package opendoja.audio.mld.ma7;

import opendoja.audio.mld.MLD;
import opendoja.audio.mld.bridge.BridgeConfig;
import opendoja.audio.mld.MLDEvent;
import opendoja.audio.mld.MLDNormalizedEventDispatcher;
import opendoja.audio.mld.MLDPlaybackEngine;
import opendoja.audio.mld.MLDPlayer;
import opendoja.audio.mld.MLDPlayerTrack;
import opendoja.audio.mld.MLDTrackControlMode;
import opendoja.host.OpenDoJaLaunchArgs;
import opendoja.host.OpenDoJaLog;

import java.io.IOException;
import java.lang.ref.Cleaner;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Produces the actual audio of the MA-7 backend by overlaying PCM streamed
 * from the Yamaha DLLs (see {@link MA7Bridge}).
 *
 * <h2>Keeping two sequencers aligned</h2>
 * {@code MLDPlayer} keeps running its own event clock, and the DLL runs
 * another one over the same file. They start together and advance by the same
 * number of output frames per {@link #render} call, so they stay in step. The
 * frame position passed to {@code render} is used to detect discontinuities:
 * <ul>
 *   <li>{@code reset()} (start / repeat) and any jump of the frame position
 *       ({@code MLDPlayer.setTime}, which renders nothing while seeking)
 *       cause a native seek to the matching song position;</li>
 *   <li>the DLL is told the play-back rate as a tempo percentage (70-130%,
 *       the range Yamaha supports);</li>
 *   <li>when the DLL reaches the end of the song but {@code MLDPlayer} is
 *       cue-point looping indefinitely, the song restarts from its beginning
 *       (see limitations in the README).</li>
 * </ul>
 * The frame position keeps counting across a cue-point loop (only the track
 * offsets rewind), so a loop does not look like a seek.
 */
final class MA7MLDPlaybackEngine implements MLDPlaybackEngine
{
    private static final Cleaner CLEANER = Cleaner.create();

    /** 10 ms blocks fetched per helper round trip (about 40 ms of audio). */
    private static final int BLOCKS_PER_READ = 4;

    /** Extra frames {@code MLDPlayer} may keep pulling while audio is live. */
    private static final int LIVE_TAIL_FRAMES = 1024;

    /**
     * Owns the helper on behalf of an engine. It must not reference the
     * engine so the {@link Cleaner} can return the helper to the pool once
     * the engine is unreachable (there is no explicit close in the sampler
     * API).
     */
    private static final class Holder implements Runnable
    {
        volatile MA7Bridge bridge;

        @Override
        public void run()
        {
            MA7Bridge b = this.bridge;
            this.bridge = null;
            MA7Bridge.release(b);
        }
    }

    private final byte[] data;
    private final int sampleRate;
    private final int volume;
    private final Path helper;
    private final Path dllDirectory;
    private final Holder holder = new Holder();

    private float[] fifo = new float[0];
    private int fifoPos;
    private int fifoLen;
    private boolean eof;
    private boolean freshFromOpen;
    private boolean restartPending;
    private long nextFrame;
    private boolean loopEnabled = true;
    private float playbackRate = 1.0f;

    MA7MLDPlaybackEngine(MLD mld, float sampleRate)
    {
        MA7Locator.requireWindows();
        byte[] raw = mld.getRawData();
        if (raw == null)
            throw new IllegalStateException("The MA-7 synth needs the " +
                "original MLD bytes (MLD was decoded from a stream).");
        this.data = trimToDeclaredSize(raw);
        this.sampleRate = Math.round(sampleRate);
        try
        {
            BridgeConfig config = MA7Locator.loadConfig();
            this.volume = Math.max(0, Math.min(127,
                config.getInt("volume", OpenDoJaLaunchArgs.MA7_VOLUME,
                    MA7SamplerProvider.DEFAULT_VOLUME)));
            this.helper = MA7Locator.findHelper(config);
            this.dllDirectory = MA7Locator.findDllDirectory(config);
        }
        catch (RuntimeException e)
        {
            OpenDoJaLog.error(MA7MLDPlaybackEngine.class,
                "Cannot set up the MA-7 DLL synth", e);
            throw e;
        }
        CLEANER.register(this, this.holder);
        // Start eagerly so configuration problems surface immediately and the
        // (slow) DLL initialisation is not paid on the audio thread.
        this.ensureBridge();
    }

    /** MLD files begin with {@code melo} and a big-endian body length. */
    private static byte[] trimToDeclaredSize(byte[] d)
    {
        if (d.length >= 8 && d[0] == 'm' && d[1] == 'e' && d[2] == 'l' &&
            d[3] == 'o')
        {
            long n = ((d[4] & 0xffL) << 24) | ((d[5] & 0xffL) << 16) |
                ((d[6] & 0xffL) << 8) | (d[7] & 0xffL);
            if (n + 8 <= d.length)
                return Arrays.copyOf(d, (int)(n + 8));
        }
        return d;
    }

    // ---------------------------------------------------------- engine API

    @Override
    public MLDTrackControlMode trackControlMode()
    {
        return MLDTrackControlMode.NONE;
    }

    @Override
    public synchronized void reset()
    {
        // MLDPlayer restarts at frame 0; defer the native seek to render().
        this.restartPending = true;
        this.nextFrame = 0;
    }

    @Override
    public void handleExtB(MLDPlayer player, MLDPlayerTrack track,
        MLDEvent event)
    {
        this.observe(player);
        MLDNormalizedEventDispatcher.dispatch(player, track, event);
    }

    @Override
    public void handleResource(MLDPlayer player, MLDPlayerTrack track,
        MLDEvent event)
    {
        this.observe(player);
        player.advanceTrack(track);
    }

    /** Learn loop mode and play-back rate from the player (both public). */
    private synchronized void observe(MLDPlayer player)
    {
        this.loopEnabled = player.getLoopEnabled();
        long position = player.framePosition();
        if (position >= this.sampleRate / 20)
        {
            // getTime() = position / sampleRate * playbackRate
            double rate = player.getTime() * this.sampleRate / position;
            if (rate > 0.0 && !Double.isInfinite(rate))
                this.playbackRate = Math.round(rate * 100.0) / 100.0f;
        }
    }

    @Override
    public synchronized void render(float[] samples, int offset, int frames,
        float left, float right, boolean clamp, long framePosition)
    {
        if (frames <= 0)
            return;
        try
        {
            if (this.restartPending || framePosition != this.nextFrame)
                this.resync(framePosition);

            int out = offset;
            int remaining = frames;
            boolean looped = false;
            while (remaining > 0)
            {
                if (this.fifoLen == 0 && !this.fetch())
                {
                    // Song and tail are over. Cue-point loops keep sounding.
                    if (this.eof && this.loopEnabled && !looped)
                    {
                        looped = true;
                        this.seekNative(0);
                        continue;
                    }
                    break;
                }
                int n = Math.min(remaining, this.fifoLen);
                int src = this.fifoPos * 2;
                for (int i = 0; i < n; i++)
                {
                    float l = samples[out] + this.fifo[src++] * left;
                    float r = samples[out + 1] + this.fifo[src++] * right;
                    if (clamp)
                    {
                        l = Math.max(-1.0f, Math.min(1.0f, l));
                        r = Math.max(-1.0f, Math.min(1.0f, r));
                    }
                    samples[out++] = l;
                    samples[out++] = r;
                }
                this.fifoPos += n;
                this.fifoLen -= n;
                remaining -= n;
            }
            this.nextFrame = framePosition + frames;

            // Finished for good: give the helper back for other sounds.
            if (this.eof && this.fifoLen == 0 && !this.loopEnabled)
                this.releaseBridge();
        }
        catch (IOException e)
        {
            this.dropBridge();
            OpenDoJaLog.error(MA7MLDPlaybackEngine.class,
                "MA-7 playback failed", e);
            throw new IllegalStateException("MA-7 playback failed: " +
                e.getMessage(), e);
        }
    }

    @Override
    public synchronized boolean hasLiveAudio(long framePosition)
    {
        return this.restartPending || this.loopEnabled || this.fifoLen > 0 ||
            !this.eof;
    }

    @Override
    public synchronized int framesUntilSilence(long framePosition)
    {
        return this.hasLiveAudio(framePosition) ? LIVE_TAIL_FRAMES : 0;
    }

    // ------------------------------------------------------------ streaming

    private int tempoPercent()
    {
        return Math.max(70, Math.min(130, Math.round(this.playbackRate * 100)));
    }

    /** Bring the native song to the position matching {@code framePosition}. */
    private void resync(long framePosition) throws IOException
    {
        this.restartPending = false;
        this.ensureBridge();
        long ms = Math.round(framePosition * 1000.0 / this.sampleRate *
            this.playbackRate);
        this.seekNative(ms);
    }

    private void seekNative(long ms) throws IOException
    {
        this.ensureBridge();
        this.fifoPos = this.fifoLen = 0;
        this.eof = false;
        if (ms == 0 && this.freshFromOpen)
            return; // just opened: already at the beginning
        this.holder.bridge.seek(ms);
        this.freshFromOpen = false;
    }

    /** Pull the next chunk from the helper. @return whether frames arrived. */
    private boolean fetch() throws IOException
    {
        if (this.eof)
            return false;
        this.ensureBridge();
        MA7Bridge.ReadResult result = this.holder.bridge.read(
            this.tempoPercent(), 0, this.volume, BLOCKS_PER_READ);
        this.freshFromOpen = false;
        this.fifo = result.pcm();
        this.fifoPos = 0;
        this.fifoLen = result.frames();
        if (result.eof() || result.frames() == 0)
            this.eof = true;
        return this.fifoLen > 0;
    }

    private void ensureBridge()
    {
        if (this.holder.bridge != null)
            return;
        MA7Bridge bridge = null;
        try
        {
            bridge = MA7Bridge.acquire(this.helper);
            bridge.open(this.dllDirectory, this.data, this.sampleRate,
                this.volume, 1);
            this.holder.bridge = bridge;
            this.freshFromOpen = true;
            this.eof = false;
            this.fifoPos = this.fifoLen = 0;
        }
        catch (IOException e)
        {
            if (bridge != null)
                bridge.close();
            OpenDoJaLog.error(MA7MLDPlaybackEngine.class,
                "Cannot start the MA-7 DLL synth (helper=" + this.helper +
                ", dllDirectory=" + this.dllDirectory + ")", e);
            throw new IllegalStateException("Cannot start the MA-7 DLL " +
                "synth: " + e.getMessage(), e);
        }
        OpenDoJaLog.debug(MA7MLDPlaybackEngine.class,
            () -> "MA-7 helper opened song (" + this.data.length + " bytes)");
    }

    private void releaseBridge()
    {
        MA7Bridge b = this.holder.bridge;
        this.holder.bridge = null;
        MA7Bridge.release(b);
    }

    private void dropBridge()
    {
        MA7Bridge b = this.holder.bridge;
        this.holder.bridge = null;
        if (b != null)
            b.close();
        this.fifoPos = this.fifoLen = 0;
        this.restartPending = true;
    }
}

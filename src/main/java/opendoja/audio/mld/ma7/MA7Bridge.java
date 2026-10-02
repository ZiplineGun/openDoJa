package opendoja.audio.mld.ma7;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import opendoja.host.OpenDoJaLog;

/**
 * Client for {@code ma7_helper.exe}: one 32-bit child process hosting the
 * Yamaha MA-7 DLLs, spoken to over stdin/stdout. The wire format is
 * documented at the top of {@code native/ma7-helper/ma7_helper.c}.
 *
 * <p>Instances are not thread-safe; callers must serialise access. Helpers
 * are expensive to start (DLL initialisation), so idle ones are pooled.</p>
 */
final class MA7Bridge
{
    private static final int MAGIC = 0x48374d41;
    private static final int VERSION = 1;
    private static final int CMD_OPEN = 1;
    private static final int CMD_READ = 2;
    private static final int CMD_SEEK = 3;
    private static final int CMD_QUIT = 4;
    private static final int CMD_ERROR = 0xffff;
    private static final int MAX_REPLY = 4 * 1024 * 1024;

    /** A request that takes longer than this kills the helper. */
    private static final long REQUEST_LIMIT_NANOS = 30_000_000_000L;
    private static final long IDLE_LIMIT_NANOS = 60_000_000_000L;
    private static final int POOL_LIMIT = 2;

    private static final Set<MA7Bridge> LIVE = ConcurrentHashMap.newKeySet();
    private static final ArrayDeque<MA7Bridge> POOL = new ArrayDeque<>();
    private static Thread watchdog;

    /** Result of {@link #open}. */
    record OpenInfo(int lengthMs, int sampleRate)
    {
    }

    /** Result of {@link #read}: interleaved stereo floats in [-1, 1]. */
    record ReadResult(float[] pcm, int frames, boolean eof)
    {
    }

    private final Process process;
    private final OutputStream out;
    private final DataInputStream in;
    private volatile long deadline;
    private volatile long idleSince;
    private boolean broken;

    private MA7Bridge(Process process)
    {
        this.process = process;
        this.out = process.getOutputStream();
        this.in = new DataInputStream(
            new BufferedInputStream(process.getInputStream(), 1 << 16));
    }

    // ---------------------------------------------------------------- pool

    /** Take an idle helper from the pool, or start a new one. */
    static MA7Bridge acquire(Path helper) throws IOException
    {
        synchronized (POOL)
        {
            MA7Bridge pooled;
            while ((pooled = POOL.pollFirst()) != null)
            {
                if (pooled.isAlive())
                    return pooled;
                pooled.close();
            }
        }
        return launch(helper);
    }

    /** Hand a helper back once its owner no longer needs it. */
    static void release(MA7Bridge bridge)
    {
        if (bridge == null)
            return;
        if (!bridge.isAlive())
        {
            bridge.close();
            return;
        }
        bridge.idleSince = System.nanoTime();
        synchronized (POOL)
        {
            if (POOL.size() < POOL_LIMIT)
            {
                POOL.addFirst(bridge);
                return;
            }
        }
        bridge.close();
    }

    private static MA7Bridge launch(Path helper) throws IOException
    {
        MA7Locator.requireWindows();
        OpenDoJaLog.debug(MA7Bridge.class, () -> "Starting MA-7 helper: " + helper);
        ProcessBuilder builder = new ProcessBuilder(helper.toString());
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process;
        try
        {
            process = builder.start();
        }
        catch (IOException e)
        {
            OpenDoJaLog.error(MA7Bridge.class,
                "Cannot start the MA-7 helper process " + helper, e);
            throw e;
        }
        MA7Bridge bridge = new MA7Bridge(process);
        LIVE.add(bridge);
        ensureWatchdog();
        return bridge;
    }

    private static synchronized void ensureWatchdog()
    {
        if (watchdog != null)
            return;
        watchdog = new Thread(MA7Bridge::watch, "opendoja-ma7-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void watch()
    {
        while (true)
        {
            try
            {
                Thread.sleep(500);
            }
            catch (InterruptedException e)
            {
                return;
            }
            long now = System.nanoTime();
            for (MA7Bridge bridge : LIVE)
            {
                long limit = bridge.deadline;
                if (limit != 0 && now - limit > 0)
                    bridge.process.destroyForcibly(); // unblocks the reader
            }
            synchronized (POOL)
            {
                Iterator<MA7Bridge> it = POOL.iterator();
                while (it.hasNext())
                {
                    MA7Bridge idle = it.next();
                    if (!idle.isAlive() || now - idle.idleSince > IDLE_LIMIT_NANOS)
                    {
                        it.remove();
                        idle.close();
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- requests

    boolean isAlive()
    {
        return !this.broken && this.process.isAlive();
    }

    /**
     * Load a song into the helper (starting the DLLs if needed) and start it
     * from the beginning.
     */
    OpenInfo open(Path dllDirectory, byte[] file, int sampleRate, int volume,
        int loopCount) throws IOException
    {
        byte[] folder = dllDirectory.toString()
            .getBytes(StandardCharsets.UTF_16LE);
        ByteBuffer payload = ByteBuffer.allocate(20 + folder.length +
            file.length).order(ByteOrder.LITTLE_ENDIAN);
        payload.putInt(file.length).putInt(folder.length / 2)
            .putInt(sampleRate).putInt(volume).putInt(loopCount);
        payload.put(folder).put(file);
        ByteBuffer reply = this.request(CMD_OPEN, payload.array(), CMD_OPEN);
        if (reply.remaining() != 12)
            throw new IOException("Invalid MA-7 open reply");
        int length = reply.getInt();
        int rate = reply.getInt();
        int channels = reply.getInt();
        if (rate != sampleRate || channels != 2 || length <= 0)
            throw new IOException("Unsupported MA-7 audio format");
        return new OpenInfo(length, rate);
    }

    /** Render up to {@code blocks} 10 ms blocks. */
    ReadResult read(int tempo, int transpose, int volume, int blocks)
        throws IOException
    {
        ByteBuffer payload = ByteBuffer.allocate(16)
            .order(ByteOrder.LITTLE_ENDIAN);
        payload.putInt(tempo).putInt(transpose).putInt(volume).putInt(blocks);
        ByteBuffer reply = this.request(CMD_READ, payload.array(), CMD_READ);
        if (reply.remaining() < 4 || (reply.remaining() - 4) % 8 != 0)
            throw new IOException("Invalid MA-7 audio block");
        boolean eof = reply.getInt() != 0;
        int frames = reply.remaining() / 8;
        float[] pcm = new float[frames * 2];
        reply.asFloatBuffer().get(pcm);
        return new ReadResult(pcm, frames, eof);
    }

    /** Move the song to {@code milliseconds} (musical time, at 100% tempo). */
    void seek(long milliseconds) throws IOException
    {
        ByteBuffer payload = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN);
        payload.putLong(Math.max(0L, milliseconds));
        if (this.request(CMD_SEEK, payload.array(), CMD_SEEK).remaining() != 0)
            throw new IOException("Invalid MA-7 seek reply");
    }

    private ByteBuffer request(int command, byte[] payload, int expected)
        throws IOException
    {
        if (!this.isAlive())
            throw new IOException("The MA-7 helper is not running");
        this.deadline = System.nanoTime() + REQUEST_LIMIT_NANOS;
        try
        {
            ByteBuffer header = ByteBuffer.allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(MAGIC).putInt(VERSION).putInt(command)
                .putInt(payload.length);
            this.out.write(header.array());
            this.out.write(payload);
            this.out.flush();

            byte[] head = new byte[16];
            this.in.readFully(head);
            ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            int magic = h.getInt();
            int version = h.getInt();
            int cmd = h.getInt();
            int bytes = h.getInt();
            if (magic != MAGIC || version != VERSION || bytes < 0 ||
                bytes > MAX_REPLY)
                throw new IOException("Invalid MA-7 helper response");
            byte[] body = new byte[bytes];
            this.in.readFully(body);
            if (cmd == CMD_ERROR)
                throw new IOException("MA-7 helper: " +
                    new String(body, StandardCharsets.US_ASCII));
            if (cmd != expected)
                throw new IOException("Unexpected MA-7 helper response");
            return ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        }
        catch (IOException e)
        {
            this.broken = true;
            OpenDoJaLog.error(MA7Bridge.class,
                "MA-7 helper request (command " + command + ") failed" +
                (this.process.isAlive() ? "" : ", exit code " +
                    this.process.exitValue()), e);
            this.close();
            if (e instanceof EOFException)
                throw new IOException("The MA-7 helper exited unexpectedly " +
                    "(missing DLL dependency or timeout?)", e);
            throw e;
        }
        finally
        {
            this.deadline = 0;
        }
    }

    /** Ask the helper to quit and make sure it is gone. */
    void close()
    {
        this.broken = true;
        LIVE.remove(this);
        synchronized (POOL)
        {
            POOL.remove(this);
        }
        try
        {
            ByteBuffer header = ByteBuffer.allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(MAGIC).putInt(VERSION).putInt(CMD_QUIT).putInt(0);
            this.out.write(header.array());
            this.out.flush();
        }
        catch (IOException ignored)
        {
        }
        try
        {
            this.out.close();
        }
        catch (IOException ignored)
        {
        }
        // The helper also exits by itself when stdin closes; this is a backstop.
        this.process.destroyForcibly();
    }
}

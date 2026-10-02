package opendoja.audio.mld.ma7;

import opendoja.audio.mld.bridge.BridgeConfig;
import opendoja.host.OpenDoJaLaunchArgs;
import opendoja.host.OpenDoJaLog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;

/**
 * Finds the Yamaha MA-7 DLL folder and the 32-bit helper executable.
 *
 * <p>Everything lives in the bridge folder {@code .opendoja/bridge/ma7/} next to the openDoJa
 * jar (see {@link BridgeConfig}); there is no other search path.</p>
 */
final class MA7Locator
{
    /** Same as {@code MLDSynth.MA7.id}: names the folder {@code .opendoja/bridge/ma7}. */
    static final String ID = "ma7";

    static final String[] REQUIRED_DLLS = {
        // SMAFMMS7EMU.dll implicitly links against EMOJIMGR.dll, so it must
        // sit in the same folder even though it is never called directly.
        "M7_EmuSmw7.dll", "SMAFMMS7EMU.dll", "M7_EmuHw.dll", "EMOJIMGR.dll"
    };

    private static final String HELPER_NAME = "ma7_helper.exe";

    private static final String HELPER_RESOURCE =
        "/opendoja/audio/mld/ma7/" + HELPER_NAME;

    private static final String INI_TEMPLATE = """
        ; openDoJa MA-7 bridge settings.
        ;
        ; Put the Yamaha MA-7 DLLs (M7_EmuSmw7.dll, SMAFMMS7EMU.dll, M7_EmuHw.dll,
        ; EMOJIMGR.dll) in this folder, then pick "MA-7 DLL" in the launcher's Synth
        ; menu (or start with -Dopendoja.mldSynth=ma7).
        ;
        ; A -Dopendoja.ma7* system property overrides the matching key below.
        ; Relative paths are relative to this folder.

        ; Folder containing the DLLs. Empty = this folder.
        ; (-Dopendoja.ma7DllDir)
        dll_dir=

        ; 32-bit helper executable. Empty = ma7_helper.exe in this folder if present,
        ; otherwise the copy bundled in the jar.
        ; (-Dopendoja.ma7Helper)
        helper=

        ; Yamaha master volume, 0-127.
        ; (-Dopendoja.ma7Volume)
        volume=%d
        """.formatted(MA7SamplerProvider.DEFAULT_VOLUME);

    private static Path cachedBundledHelper;

    private MA7Locator()
    {
    }

    static void requireWindows()
    {
        String os = System.getProperty("os.name", "");
        if (!os.toLowerCase(java.util.Locale.ROOT).startsWith("windows"))
        {
            throw new IllegalStateException("The MA-7 DLL synth needs Windows " +
                "(the Yamaha emulator DLLs are 32-bit Windows binaries).");
        }
    }

    /** Load {@code .opendoja/bridge/ma7/bridge.ini}, creating a template on first use. */
    static BridgeConfig loadConfig()
    {
        return BridgeConfig.load(ID, INI_TEMPLATE);
    }

    /**
     * The folder that contains the MA-7 DLLs: {@code -Dopendoja.ma7DllDir}, else
     * {@code dll_dir} from {@code bridge.ini}, else the bridge folder itself. Prints
     * {@code ma7-helper: No DLLs found in <dll_dir>} to the command line and throws when the
     * DLLs are not there.
     */
    static Path findDllDirectory(BridgeConfig config)
    {
        return config.requireDllDirectory("dll_dir", OpenDoJaLaunchArgs.MA7_DLL_DIR,
            REQUIRED_DLLS);
    }

    /**
     * Locate the 32-bit helper: {@code -Dopendoja.ma7Helper}, else {@code helper} from
     * {@code bridge.ini}, else {@code ma7_helper.exe} in the bridge folder, else the copy
     * bundled in the jar (extracted to the temp directory once).
     */
    static Path findHelper(BridgeConfig config)
    {
        Path configured = config.getPath("helper", OpenDoJaLaunchArgs.MA7_HELPER);
        if (configured != null)
        {
            if (!Files.isRegularFile(configured))
                throw new IllegalStateException(config.label() + ": helper '" +
                    configured + "' does not exist.");
            return configured;
        }
        Path beside = config.dir().resolve(HELPER_NAME);
        if (Files.isRegularFile(beside))
            return beside.toAbsolutePath();
        synchronized (MA7Locator.class)
        {
            if (cachedBundledHelper != null && Files.isRegularFile(cachedBundledHelper))
                return cachedBundledHelper;
            Path extracted = extractBundledHelper();
            OpenDoJaLog.info(MA7Locator.class,
                () -> "Using bundled MA-7 helper at " + extracted);
            return cachedBundledHelper = extracted;
        }
    }

    private static Path extractBundledHelper()
    {
        try (InputStream in = MA7Locator.class.getResourceAsStream(
            HELPER_RESOURCE))
        {
            if (in == null)
            {
                throw new IllegalStateException("The bundled ma7_helper.exe is " +
                    "missing from this build. Build it with " +
                    "native/ma7-helper/build.sh or set -D" +
                    OpenDoJaLaunchArgs.MA7_HELPER + "=<path> / helper= in " +
                    BridgeConfig.INI_NAME + ".");
            }
            byte[] bytes = in.readAllBytes();
            CRC32 crc = new CRC32();
            crc.update(bytes);
            Path dir = Path.of(System.getProperty("java.io.tmpdir"),
                "opendoja-ma7");
            Files.createDirectories(dir);
            Path target = dir.resolve("ma7_helper-" + bytes.length + "-" +
                Long.toHexString(crc.getValue()) + ".exe");
            if (Files.isRegularFile(target) && Files.size(target) == bytes.length)
                return target;
            Path temp = Files.createTempFile(dir, "ma7_helper", ".tmp");
            Files.write(temp, bytes);
            try
            {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            }
            catch (IOException e)
            {
                // Another instance may be running the same helper already.
                Files.deleteIfExists(temp);
                if (!Files.isRegularFile(target))
                    throw e;
            }
            return target;
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Cannot extract ma7_helper.exe: " +
                e.getMessage(), e);
        }
    }
}

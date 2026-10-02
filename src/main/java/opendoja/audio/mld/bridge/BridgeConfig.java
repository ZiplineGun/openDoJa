package opendoja.audio.mld.bridge;

import opendoja.host.OpenDoJaLog;
import opendoja.host.OpenDoJaPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Settings and file locations of one native bridge (a 32-bit helper process that hosts a vendor's
 * MLD player DLLs, see {@code native/<id>-helper}).
 *
 * <p>Every bridge owns one folder, {@code .opendoja/bridge/<id>/} (see
 * {@link OpenDoJaPaths#bridgeDir}), named after its {@code MLDSynth} id. It holds:</p>
 * <ul>
 *   <li>{@value #INI_NAME} - the settings read here,</li>
 *   <li>the vendor DLLs (the default of {@code dll_dir} is this very folder),</li>
 *   <li>optionally a {@code <id>_helper.exe} that replaces the one bundled in the jar.</li>
 * </ul>
 *
 * <p>A setting is looked up in this order: the {@code -Dopendoja.*} system property, then the
 * {@value #INI_NAME} entry, then the caller's default. The ini file is plain
 * {@code key=value} text: {@code ;} or {@code #} at the start of a line begin a comment, values
 * are taken literally (so Windows paths need no escaping, and surrounding double quotes are
 * optional), keys are case-insensitive, and {@code [section]} lines are accepted but ignored.
 * A relative path in the ini is resolved against the bridge folder.</p>
 */
public final class BridgeConfig
{
    public static final String INI_NAME = "bridge.ini";

    private final String id;
    private final Path dir;
    private final Path iniFile;
    private final Map<String, String> values;

    private BridgeConfig(String id, Path dir, Map<String, String> values)
    {
        this.id = id;
        this.dir = dir;
        this.iniFile = dir.resolve(INI_NAME);
        this.values = values;
    }

    /**
     * Load the configuration of bridge {@code id}. If the bridge folder or its ini file does not
     * exist yet, they are created and the ini is filled with {@code template}, so the user has a
     * documented file to edit and an obvious place to put the DLLs.
     */
    public static BridgeConfig load(String id, String template)
    {
        Path dir = OpenDoJaPaths.bridgeDir(id);
        Path ini = dir.resolve(INI_NAME);
        if (!Files.isRegularFile(ini))
            writeTemplate(dir, ini, template);
        Map<String, String> values = Files.isRegularFile(ini) ? parse(ini) : new HashMap<>();
        return new BridgeConfig(id, dir, values);
    }

    /** Test hook: build a configuration from ini text without touching the file system. */
    static BridgeConfig fromText(String id, Path dir, String text)
    {
        return new BridgeConfig(id, dir, parseText(text, "<memory>"));
    }

    public String id()
    {
        return this.id;
    }

    /** The bridge folder ({@code .opendoja/bridge/<id>}). */
    public Path dir()
    {
        return this.dir;
    }

    public Path iniFile()
    {
        return this.iniFile;
    }

    /** Name used as the prefix of command-line messages, e.g. {@code ma7-helper}. */
    public String label()
    {
        return this.id + "-helper";
    }

    // ------------------------------------------------------------ lookups

    /** A setting's text and whether it came from the {@code -D} property (else the ini). */
    private record Found(String text, boolean fromProperty)
    {
    }

    private Found find(String key, String property)
    {
        String fromProperty = System.getProperty(property);
        if (fromProperty != null && !fromProperty.isBlank())
        {
            String value = fromProperty.trim();
            OpenDoJaLog.debug(BridgeConfig.class,
                () -> this.id + ": " + key + "=" + value + " (-D" + property + ")");
            return new Found(value, true);
        }
        String fromIni = this.values.get(key.toLowerCase(Locale.ROOT));
        if (fromIni != null && !fromIni.isBlank())
        {
            OpenDoJaLog.debug(BridgeConfig.class,
                () -> this.id + ": " + key + "=" + fromIni + " (" + INI_NAME + ")");
            return new Found(fromIni, false);
        }
        return null;
    }

    /** The setting as text, or {@code null} when neither the property nor the ini sets it. */
    public String get(String key, String property)
    {
        Found found = find(key, property);
        return found == null ? null : found.text();
    }

    public int getInt(String key, String property, int defaultValue)
    {
        String text = get(key, property);
        if (text == null)
            return defaultValue;
        try
        {
            return Integer.parseInt(text);
        }
        catch (NumberFormatException e)
        {
            OpenDoJaLog.warn(BridgeConfig.class, this.id + ": '" + key +
                "' is not a number (" + text + "); using " + defaultValue);
            return defaultValue;
        }
    }

    /**
     * The setting as a path, or {@code null} when unset. A value from the ini is resolved
     * against the bridge folder; a value from the system property against the working directory.
     */
    public Path getPath(String key, String property)
    {
        Found found = find(key, property);
        if (found == null)
            return null;
        Path path = Path.of(found.text());
        if (!path.isAbsolute() && !found.fromProperty())
            path = this.dir.resolve(path);
        return path.toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------- DLLs

    /**
     * Resolve the DLL folder ({@code dirKey}/{@code dirProperty}, default: the bridge folder
     * itself) and check that all {@code requiredDlls} are in it.
     *
     * <p>On failure the problem is printed to the command line (standard error) as
     * {@code <id>-helper: No DLLs found in <dll_dir>} and an
     * {@link IllegalStateException} with that same first line is thrown.</p>
     */
    public Path requireDllDirectory(String dirKey, String dirProperty, String... requiredDlls)
    {
        Path configured = getPath(dirKey, dirProperty);
        Path dllDir = configured != null ? configured : this.dir;
        List<String> missing = new ArrayList<>();
        for (String name : requiredDlls)
        {
            if (!Files.isRegularFile(dllDir.resolve(name)))
                missing.add(name);
        }
        if (missing.isEmpty())
        {
            OpenDoJaLog.info(BridgeConfig.class, () -> this.id + ": using DLLs from " + dllDir);
            return dllDir;
        }
        String headline = missing.size() == requiredDlls.length
            ? label() + ": No DLLs found in " + dllDir
            : label() + ": Missing DLLs in " + dllDir + ": " + String.join(", ", missing);
        System.err.println(headline);
        System.err.println(label() + ": required: " + String.join(", ", requiredDlls));
        System.err.println(label() + ": put them in that folder, or set " + dirKey + " in " +
            this.iniFile + " (or -D" + dirProperty + "=<folder>)");
        throw new IllegalStateException(headline);
    }

    // ------------------------------------------------------------ ini I/O

    private static void writeTemplate(Path dir, Path ini, String template)
    {
        try
        {
            Files.createDirectories(dir);
            String text = template.replace("\r\n", "\n").replace("\n", System.lineSeparator());
            Files.writeString(ini, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            OpenDoJaLog.info(BridgeConfig.class, () -> "Created " + ini);
        }
        catch (java.nio.file.FileAlreadyExistsException ignored)
        {
            // Raced with another instance; the file is there now.
        }
        catch (IOException | RuntimeException e)
        {
            OpenDoJaLog.warn(BridgeConfig.class, "Cannot create " + ini, e);
        }
    }

    private static Map<String, String> parse(Path ini)
    {
        try
        {
            String text = new String(Files.readAllBytes(ini), StandardCharsets.UTF_8);
            return parseText(text, ini.toString());
        }
        catch (IOException e)
        {
            OpenDoJaLog.warn(BridgeConfig.class, "Cannot read " + ini, e);
            return new HashMap<>();
        }
    }

    private static Map<String, String> parseText(String text, String origin)
    {
        Map<String, String> map = new HashMap<>();
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') // Notepad's UTF-8 BOM
            text = text.substring(1);
        int lineNumber = 0;
        for (String raw : text.split("\r\n|\r|\n", -1))
        {
            lineNumber++;
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == ';' || line.charAt(0) == '#' ||
                line.charAt(0) == '[')
            {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0)
            {
                final int n = lineNumber;
                OpenDoJaLog.warn(BridgeConfig.class,
                    () -> origin + ":" + n + ": ignored (expected key=value)");
                continue;
            }
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(eq + 1).trim();
            if (value.length() >= 2 && value.charAt(0) == '"' &&
                value.charAt(value.length() - 1) == '"')
            {
                value = value.substring(1, value.length() - 1).trim();
            }
            map.put(key, value);
        }
        return map;
    }
}

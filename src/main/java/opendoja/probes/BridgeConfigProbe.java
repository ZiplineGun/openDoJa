package opendoja.probes;

import opendoja.audio.mld.bridge.BridgeConfig;
import opendoja.host.OpenDoJaLaunchArgs;
import opendoja.host.OpenDoJaPaths;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Checks {@link BridgeConfig}: the {@code .opendoja/bridge/<id>} location, template creation,
 * ini parsing, {@code -D} &gt; ini &gt; default precedence and the command-line message printed
 * when the DLLs are missing. Runs without any DLLs.
 */
public final class BridgeConfigProbe {
    private static final String PROP = "opendoja.probeDllDir";
    private static final String TEMPLATE = "; template\ndll_dir=\nvolume=64\n";
    private static final String[] DLLS = {"A.dll", "B.dll"};

    private BridgeConfigProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("opendoja-bridge-probe");
        String oldRoot = System.getProperty(OpenDoJaLaunchArgs.BRIDGE_ROOT);
        try {
            System.setProperty(OpenDoJaLaunchArgs.BRIDGE_ROOT, root.toString());
            System.clearProperty(PROP);
            verifyLocation(root);
            verifyTemplateIsCreatedOnce(root);
            verifyIniSyntax(root);
            verifyPrecedence(root);
            verifyDllCheck(root);
            System.out.println("Bridge config probe OK");
        } finally {
            if (oldRoot == null) {
                System.clearProperty(OpenDoJaLaunchArgs.BRIDGE_ROOT);
            } else {
                System.setProperty(OpenDoJaLaunchArgs.BRIDGE_ROOT, oldRoot);
            }
            System.clearProperty(PROP);
            deleteTree(root);
        }
    }

    private static void verifyLocation(Path root) {
        check(OpenDoJaPaths.bridgeDir("ma7").equals(root.resolve("ma7").toAbsolutePath().normalize()),
                "bridgeDir(id) should be <bridgeRoot>/<id>");
        String saved = System.getProperty(OpenDoJaLaunchArgs.BRIDGE_ROOT);
        System.clearProperty(OpenDoJaLaunchArgs.BRIDGE_ROOT);
        try {
            check(OpenDoJaPaths.bridgeRoot().equals(OpenDoJaPaths.hostDataRoot().resolve("bridge")),
                    "default bridge root should be .opendoja/bridge");
        } finally {
            System.setProperty(OpenDoJaLaunchArgs.BRIDGE_ROOT, saved);
        }
    }

    private static void verifyTemplateIsCreatedOnce(Path root) throws IOException {
        BridgeConfig first = BridgeConfig.load("probe1", TEMPLATE);
        Path ini = root.resolve("probe1").resolve(BridgeConfig.INI_NAME);
        check(first.iniFile().equals(ini.toAbsolutePath().normalize()), "iniFile() location");
        check(Files.isRegularFile(ini), "first load should create bridge.ini from the template");
        check(first.getInt("volume", PROP + "Vol", 1) == 64, "template values should be readable");
        check(first.get("dll_dir", PROP) == null, "an empty template value counts as unset");

        Files.writeString(ini, "volume=10\n");
        BridgeConfig second = BridgeConfig.load("probe1", TEMPLATE);
        check(second.getInt("volume", PROP + "Vol", 1) == 10, "an existing bridge.ini must not be overwritten");
    }

    private static void verifyIniSyntax(Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("probe2"));
        String text = "\uFEFF; comment\r\n# another\r\n[ma7]\r\n"
                + "  Dll_Dir = \"C:\\Yamaha\\MCP MA7\"  \r\n"
                + "volume=abc\r\n"
                + "garbage line\r\n"
                + "helper=sub\\x.exe\r\n";
        Files.writeString(dir.resolve(BridgeConfig.INI_NAME), text, StandardCharsets.UTF_8);
        BridgeConfig config = BridgeConfig.load("probe2", TEMPLATE);
        check("C:\\Yamaha\\MCP MA7".equals(config.get("dll_dir", PROP)),
                "BOM, CRLF, sections, case-insensitive keys, quotes and backslashes should parse");
        check(config.getInt("volume", PROP + "Vol", 7) == 7, "a non-numeric value should fall back to the default");
        check(config.getPath("helper", PROP + "Helper").equals(dir.resolve("sub\\x.exe").toAbsolutePath().normalize()),
                "a relative ini path should resolve against the bridge folder");
    }

    private static void verifyPrecedence(Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("probe3"));
        Files.writeString(dir.resolve(BridgeConfig.INI_NAME), "dll_dir=fromIni\nvolume=20\n");
        BridgeConfig config = BridgeConfig.load("probe3", TEMPLATE);
        check(config.getInt("volume", PROP + "Vol", 99) == 20, "ini should beat the default");
        check(config.getPath("dll_dir", PROP).equals(dir.resolve("fromIni").toAbsolutePath().normalize()),
                "ini path value");
        System.setProperty(PROP, "relativeFromProperty");
        System.setProperty(PROP + "Vol", "33");
        try {
            check(config.getInt("volume", PROP + "Vol", 99) == 33, "-D should beat the ini");
            check(config.getPath("dll_dir", PROP).equals(Path.of("relativeFromProperty").toAbsolutePath().normalize()),
                    "a -D path is relative to the working directory");
        } finally {
            System.clearProperty(PROP);
            System.clearProperty(PROP + "Vol");
        }
    }

    private static void verifyDllCheck(Path root) throws IOException {
        // No DLLs at all: default dll_dir is the bridge folder itself.
        BridgeConfig config = BridgeConfig.load("probe4", TEMPLATE);
        String err = captureStderr(() -> {
            try {
                config.requireDllDirectory("dll_dir", PROP, DLLS);
                throw new IllegalStateException("should have failed without DLLs");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().equals("probe4-helper: No DLLs found in " + config.dir())) {
                    throw expected;
                }
            }
        });
        String firstLine = err.lines().findFirst().orElse("");
        check(firstLine.equals("probe4-helper: No DLLs found in " + config.dir()),
                "first stderr line should be '<id>-helper: No DLLs found in <dll_dir>', was: " + firstLine);

        // Only some DLLs present.
        Files.writeString(config.dir().resolve("A.dll"), "x");
        String partial = captureStderr(() -> {
            try {
                config.requireDllDirectory("dll_dir", PROP, DLLS);
                throw new IllegalStateException("should have failed with B.dll missing");
            } catch (IllegalStateException expected) {
                // message checked via stderr below
            }
        });
        check(partial.lines().findFirst().orElse("").equals(
                "probe4-helper: Missing DLLs in " + config.dir() + ": B.dll"), "partial set message: " + partial);

        // Everything present.
        Files.writeString(config.dir().resolve("B.dll"), "x");
        check(config.requireDllDirectory("dll_dir", PROP, DLLS).equals(config.dir()),
                "the bridge folder is the default DLL folder");

        // dll_dir pointing elsewhere (relative to the bridge folder).
        Path other = Files.createDirectories(root.resolve("probe5").resolve("dlls"));
        Files.writeString(other.resolve("A.dll"), "x");
        Files.writeString(other.resolve("B.dll"), "x");
        Files.writeString(root.resolve("probe5").resolve(BridgeConfig.INI_NAME), "dll_dir=dlls\n");
        BridgeConfig elsewhere = BridgeConfig.load("probe5", TEMPLATE);
        check(elsewhere.requireDllDirectory("dll_dir", PROP, DLLS).equals(other.toAbsolutePath().normalize()),
                "dll_dir from the ini should be honoured");
    }

    private static String captureStderr(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}

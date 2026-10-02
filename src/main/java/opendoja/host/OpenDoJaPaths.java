package opendoja.host;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

public final class OpenDoJaPaths {
    private OpenDoJaPaths() {
    }

    public static Path hostDataRoot() {
        return artifactDirectory().resolve(".opendoja").normalize();
    }

    /**
     * Folder that holds one sub-folder per native bridge ({@code ma7}, ...). Defaults to
     * {@code .opendoja/bridge}; {@link OpenDoJaLaunchArgs#BRIDGE_ROOT} overrides it.
     */
    public static Path bridgeRoot() {
        String override = OpenDoJaLaunchArgs.get(OpenDoJaLaunchArgs.BRIDGE_ROOT, "");
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim()).toAbsolutePath().normalize();
        }
        return hostDataRoot().resolve("bridge").normalize();
    }

    /**
     * Folder for one bridge, named after its {@code MLDSynth} id: it holds {@code bridge.ini},
     * the vendor DLLs and optionally a replacement helper executable.
     */
    public static Path bridgeDir(String id) {
        return bridgeRoot().resolve(id).normalize();
    }

    public static Path artifactDirectory() {
        try {
            URL location = OpenDoJaPaths.class.getProtectionDomain().getCodeSource().getLocation();
            if (location != null) {
                Path path = Path.of(location.toURI()).toAbsolutePath().normalize();
                if (Files.isRegularFile(path)) {
                    Path parent = path.getParent();
                    return parent == null ? path : parent;
                }
                if (Files.isDirectory(path)) {
                    return path;
                }
            }
        } catch (URISyntaxException | IllegalArgumentException | SecurityException ignored) {
        }
        return Path.of("").toAbsolutePath().normalize();
    }
}

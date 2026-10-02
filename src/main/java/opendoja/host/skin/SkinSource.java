package opendoja.host.skin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Read-only view over the files of one skin. A skin lives either in a directory below
 * {@code .opendoja/skin} or, for the built-in skin, below a classpath prefix.
 */
interface SkinSource {
    /** Opens a file relative to the skin root, or returns {@code null} when it does not exist. */
    InputStream open(String relativePath) throws IOException;

    String describe();

    static SkinSource directory(Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        return new SkinSource() {
            @Override
            public InputStream open(String relativePath) throws IOException {
                String safe = sanitize(relativePath);
                if (safe == null) {
                    return null;
                }
                Path resolved = normalizedRoot.resolve(safe).normalize();
                // Never let a skin read files outside its own folder.
                if (!resolved.startsWith(normalizedRoot) || !Files.isRegularFile(resolved)) {
                    return null;
                }
                return Files.newInputStream(resolved);
            }

            @Override
            public String describe() {
                return normalizedRoot.toString();
            }
        };
    }

    static SkinSource classpath(String prefix) {
        String normalizedPrefix = prefix.endsWith("/") ? prefix : prefix + "/";
        return new SkinSource() {
            @Override
            public InputStream open(String relativePath) {
                String safe = sanitize(relativePath);
                if (safe == null) {
                    return null;
                }
                return SkinSource.class.getClassLoader().getResourceAsStream(normalizedPrefix + safe);
            }

            @Override
            public String describe() {
                return "classpath:" + normalizedPrefix;
            }
        };
    }

    /** Rejects absolute paths and parent-directory segments; returns a forward-slash path. */
    private static String sanitize(String relativePath) {
        if (relativePath == null) {
            return null;
        }
        String path = relativePath.strip().replace('\\', '/');
        if (path.isEmpty() || path.startsWith("/") || path.contains(":")) {
            return null;
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                return null;
            }
        }
        return path;
    }
}

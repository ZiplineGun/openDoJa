package opendoja.host.skin;

import opendoja.host.OpenDoJaLaunchArgs;
import opendoja.host.OpenDoJaLog;
import opendoja.host.OpenDoJaPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

/**
 * Discovers, loads and remembers UI skins.
 *
 * <p>User skins live in {@code <artifact dir>/.opendoja/skin/<skin id>/skin.ini} together with their images.
 * The built-in skin ({@link #DEFAULT_ID}) ships inside the jar and is always available as a fallback.
 */
public final class UiSkinRepository {
    public static final String DEFAULT_ID = "default";

    /** Classpath folder holding the built-in skin's {@code skin.ini} and images. */
    private static final String BUILT_IN_RESOURCE_ROOT = "opendoja/images/icons/n900i/";

    /**
     * Same node as {@code Preferences.userNodeForPackage(OpenDoJaLauncher.class)} so the launcher and the
     * separately started game process share the selection without depending on each other's packages.
     */
    private static final String PREFERENCES_NODE = "opendoja/launcher";
    private static final String PREFERENCES_KEY = "uiSkin";

    private UiSkinRepository() {
    }

    /** One selectable skin. */
    public record Entry(String id, String displayName) {
    }

    /** {@code .opendoja/skin} next to the jar. The folder may not exist yet. */
    public static Path skinRoot() {
        return OpenDoJaPaths.hostDataRoot().resolve("skin");
    }

    /** Lists the built-in skin first, then every user folder that contains a {@code skin.ini}. */
    public static List<Entry> list() {
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(DEFAULT_ID, displayNameOf(SkinSource.classpath(BUILT_IN_RESOURCE_ROOT), "Default")));
        Path root = skinRoot();
        if (!Files.isDirectory(root)) {
            return entries;
        }
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve(UiSkinLoader.INI_NAME)))
                    .sorted(Comparator.comparing(dir -> dir.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(dir -> {
                        String id = dir.getFileName().toString();
                        if (DEFAULT_ID.equalsIgnoreCase(id)) {
                            OpenDoJaLog.warn(UiSkinRepository.class,
                                    "Ignoring skin folder '" + id + "': the id is reserved for the built-in skin");
                            return;
                        }
                        entries.add(new Entry(id, displayNameOf(SkinSource.directory(dir), id)));
                    });
        } catch (IOException e) {
            OpenDoJaLog.warn(UiSkinRepository.class, "Failed to scan skin folder " + root, e);
        }
        return entries;
    }

    /** Loads a skin by id. Unknown or broken skins fall back to the built-in one with a warning. */
    public static UiSkin load(String id) {
        String requested = id == null ? "" : id.strip();
        if (!requested.isEmpty() && !DEFAULT_ID.equals(requested)) {
            UiSkin skin = loadUserSkin(requested);
            if (skin != null) {
                return skin;
            }
        }
        return loadBuiltIn();
    }

    /**
     * The skin to use at startup: {@code -Dopendoja.uiSkin} if set, otherwise the remembered selection,
     * otherwise {@link #DEFAULT_ID}.
     */
    public static String selectedId() {
        String override = OpenDoJaLaunchArgs.get(OpenDoJaLaunchArgs.UI_SKIN, "");
        if (override != null && !override.isBlank()) {
            return override.strip();
        }
        try {
            String stored = preferences().get(PREFERENCES_KEY, DEFAULT_ID);
            return stored == null || stored.isBlank() ? DEFAULT_ID : stored.strip();
        } catch (RuntimeException e) {
            return DEFAULT_ID;
        }
    }

    /** Remembers the selection for the next launch (launcher and game process share it). */
    public static void saveSelectedId(String id) {
        try {
            preferences().put(PREFERENCES_KEY, id == null || id.isBlank() ? DEFAULT_ID : id.strip());
        } catch (RuntimeException e) {
            OpenDoJaLog.warn(UiSkinRepository.class, "Failed to remember UI skin selection", e);
        }
    }

    private static UiSkin loadBuiltIn() {
        try {
            return UiSkinLoader.load(DEFAULT_ID, SkinSource.classpath(BUILT_IN_RESOURCE_ROOT));
        } catch (IOException e) {
            // The built-in skin is part of the jar; failing here is a packaging bug.
            throw new IllegalStateException("Built-in UI skin is missing", e);
        }
    }

    private static UiSkin loadUserSkin(String id) {
        if (id.contains("/") || id.contains("\\") || id.contains("..")) {
            OpenDoJaLog.warn(UiSkinRepository.class, "Invalid skin id '" + id + "'; using the default skin");
            return null;
        }
        Path directory = skinRoot().resolve(id);
        try {
            return UiSkinLoader.load(id, SkinSource.directory(directory));
        } catch (IOException | RuntimeException e) {
            OpenDoJaLog.warn(UiSkinRepository.class,
                    "Failed to load skin '" + id + "' from " + directory + "; using the default skin", e);
            return null;
        }
    }

    private static String displayNameOf(SkinSource source, String fallback) {
        String name = UiSkinLoader.readDisplayName(source);
        return name == null ? fallback : name;
    }

    private static Preferences preferences() {
        return Preferences.userRoot().node(PREFERENCES_NODE);
    }
}

package opendoja.host.skin;

import opendoja.host.OpenDoJaLog;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a {@link UiSkin} from a {@code skin.ini} plus the images it references. */
final class UiSkinLoader {
    static final String INI_NAME = "skin.ini";

    private static final int DEFAULT_BAR_HEIGHT = 27;
    private static final int DEFAULT_SIDE_WIDTH = 2;
    private static final int DEFAULT_BAR_ARGB = 0xFFD7D8DB;
    private static final int DEFAULT_TEXT_ARGB = 0xFF1F2229;
    private static final int DEFAULT_FONT_SIZE = 12;
    private static final int MAX_BAR_HEIGHT = 1024;
    private static final int MAX_SIDE_WIDTH = 512;
    private static final Pattern SOFT_KEY_SECTION = Pattern.compile("softkey[._\\- ]?([12])");

    private final String skinId;
    private final SkinSource source;
    private final Map<String, BufferedImage> imageCache = new HashMap<>();

    private UiSkinLoader(String skinId, SkinSource source) {
        this.skinId = skinId;
        this.source = source;
    }

    /** @throws IOException when {@code skin.ini} is missing or unreadable */
    static UiSkin load(String skinId, SkinSource source) throws IOException {
        return new UiSkinLoader(skinId, source).load();
    }

    /** Reads only the display name of a skin without decoding any image. Returns {@code null} if unreadable. */
    static String readDisplayName(SkinSource source) {
        try {
            IniDocument document = readDocument(source);
            IniDocument.Section section = document.first("skin");
            String name = section == null ? null : section.get("name");
            return name == null || name.isBlank() ? null : name.strip();
        } catch (IOException e) {
            return null;
        }
    }

    private static IniDocument readDocument(SkinSource source) throws IOException {
        try (InputStream in = source.open(INI_NAME)) {
            if (in == null) {
                throw new FileNotFoundException(INI_NAME + " not found in " + source.describe());
            }
            return IniDocument.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private UiSkin load() throws IOException {
        IniDocument document = readDocument(source);

        IniDocument.Section meta = document.first("skin");
        String name = meta == null ? null : meta.get("name");
        if (name == null || name.isBlank()) {
            name = skinId;
        }

        IniDocument.Section layout = document.first("layout");
        int topHeight = DEFAULT_BAR_HEIGHT;
        int bottomHeight = DEFAULT_BAR_HEIGHT;
        int sideWidth = DEFAULT_SIDE_WIDTH;
        int sideArgb = DEFAULT_BAR_ARGB;
        int topArgb = DEFAULT_BAR_ARGB;
        int bottomArgb = DEFAULT_BAR_ARGB;
        BufferedImage topImage = null;
        BufferedImage bottomImage = null;
        if (layout != null) {
            topHeight = clamp(intValue(layout, "top_bar_height", topHeight), 0, MAX_BAR_HEIGHT);
            bottomHeight = clamp(intValue(layout, "bottom_bar_height", bottomHeight), 0, MAX_BAR_HEIGHT);
            sideWidth = clamp(intValue(layout, "side_width", sideWidth), 0, MAX_SIDE_WIDTH);
            sideArgb = colorValue(layout, "side_color", sideArgb);
            topArgb = colorValue(layout, "top_bg_color", topArgb);
            bottomArgb = colorValue(layout, "bottom_bg_color", bottomArgb);
            topImage = imageValue(layout, "top_bg_image");
            bottomImage = imageValue(layout, "bottom_bg_image");
        }

        List<UiSkin.Icon> icons = new ArrayList<>();
        UiSkin.SoftKeyLabel[] softKeys = defaultSoftKeys();
        for (IniDocument.Section section : document.sections()) {
            String sectionName = section.name();
            if (sectionName.equals("icon") || sectionName.startsWith("icon.")) {
                UiSkin.Icon icon = parseIcon(section);
                if (icon != null) {
                    icons.add(icon);
                }
                continue;
            }
            Matcher matcher = SOFT_KEY_SECTION.matcher(sectionName);
            if (matcher.matches()) {
                int index = Integer.parseInt(matcher.group(1)) - 1;
                softKeys[index] = parseSoftKey(section, softKeys[index]);
            }
        }

        return new UiSkin(skinId, name, topHeight, bottomHeight, sideWidth, sideArgb,
                topArgb, topImage, bottomArgb, bottomImage, icons, softKeys);
    }

    private UiSkin.Icon parseIcon(IniDocument.Section section) {
        String rawType = section.get("type");
        UiSkin.IconType type = UiSkin.IconType.fromId(rawType);
        if (type == null) {
            warn(section, "unsupported icon type '" + rawType + "'; use type = none to show it as a static image");
            return null;
        }
        UiSkin.Bar bar = enumValue(section, "bar", UiSkin.Bar.TOP);
        UiSkin.Anchor anchor = enumValue(section, "anchor", UiSkin.Anchor.LEFT);
        int x = intValue(section, "x", 0);
        int y = intValue(section, "y", 0);
        List<BufferedImage> images = imageListValue(section);
        if (images.stream().allMatch(java.util.Objects::isNull)) {
            // Missing or unreadable files were already reported while loading.
            warn(section, "icon has no usable images; skipped");
            return null;
        }
        return new UiSkin.Icon(type, bar, anchor, x, y, images);
    }

    private UiSkin.SoftKeyLabel parseSoftKey(IniDocument.Section section, UiSkin.SoftKeyLabel fallback) {
        UiSkin.Anchor anchor = enumValue(section, "anchor", fallback.anchor());
        UiSkin.TextAlign align = enumValue(section, "align", fallback.align());
        int x = intValue(section, "x", fallback.x());
        Integer y = section.get("y") == null || section.get("y").isBlank()
                ? fallback.y()
                : Integer.valueOf(intValue(section, "y", 0));
        int fontSize = clamp(intValue(section, "font_size", fallback.fontSize()), 1, 200);
        int argb = colorValue(section, "color", fallback.argbColor());
        BufferedImage bg = imageValue(section, "bg_image");
        int bgX = intValue(section, "bg_x", 0);
        int bgY = intValue(section, "bg_y", 0);
        return new UiSkin.SoftKeyLabel(anchor, align, x, y, fontSize, argb, bg, bgX, bgY);
    }

    private static UiSkin.SoftKeyLabel[] defaultSoftKeys() {
        return new UiSkin.SoftKeyLabel[]{
                new UiSkin.SoftKeyLabel(UiSkin.Anchor.LEFT, UiSkin.TextAlign.LEFT, 6, null,
                        DEFAULT_FONT_SIZE, DEFAULT_TEXT_ARGB, null, 0, 0),
                new UiSkin.SoftKeyLabel(UiSkin.Anchor.RIGHT, UiSkin.TextAlign.RIGHT, 6, null,
                        DEFAULT_FONT_SIZE, DEFAULT_TEXT_ARGB, null, 0, 0)
        };
    }

    // ---- value helpers -------------------------------------------------------------------------------------

    private int intValue(IniDocument.Section section, String key, int fallback) {
        String raw = section.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            warn(section, "invalid integer for '" + key + "': " + raw);
            return fallback;
        }
    }

    /** Accepts {@code #RRGGBB}, {@code #AARRGGBB}, {@code 0x...} and bare hex. */
    private int colorValue(IniDocument.Section section, String key, int fallback) {
        String raw = section.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String hex = raw.strip();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        } else if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        try {
            if (hex.length() == 6) {
                return 0xFF000000 | Integer.parseInt(hex, 16);
            }
            if (hex.length() == 8) {
                return (int) Long.parseLong(hex, 16);
            }
        } catch (NumberFormatException ignored) {
            // Falls through to the warning below.
        }
        warn(section, "invalid color for '" + key + "': " + raw + " (expected #RRGGBB or #AARRGGBB)");
        return fallback;
    }

    private <E extends Enum<E>> E enumValue(IniDocument.Section section, String key, E fallback) {
        String raw = section.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        for (E constant : fallback.getDeclaringClass().getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(raw.strip())) {
                return constant;
            }
        }
        warn(section, "invalid value for '" + key + "': " + raw);
        return fallback;
    }

    private BufferedImage imageValue(IniDocument.Section section, String key) {
        String raw = section.get(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return readImage(section, raw.strip());
    }

    /** Reads {@code images} (or {@code image}) as a comma-separated list; empty entries keep their position. */
    private List<BufferedImage> imageListValue(IniDocument.Section section) {
        String raw = section.getAny("images", "image");
        List<BufferedImage> images = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return images;
        }
        for (String part : raw.split(",", -1)) {
            String name = part.strip();
            images.add(name.isEmpty() ? null : readImage(section, name));
        }
        return images;
    }

    private BufferedImage readImage(IniDocument.Section section, String name) {
        String cacheKey = name.replace('\\', '/');
        if (imageCache.containsKey(cacheKey)) {
            return imageCache.get(cacheKey);
        }
        BufferedImage result = null;
        try (InputStream in = source.open(cacheKey)) {
            if (in == null) {
                warn(section, "image not found: " + name);
            } else {
                BufferedImage decoded = ImageIO.read(in);
                if (decoded == null) {
                    warn(section, "unreadable image: " + name);
                } else {
                    result = toArgb(decoded);
                }
            }
        } catch (IOException e) {
            warn(section, "failed to read image " + name + ": " + e.getMessage());
        }
        imageCache.put(cacheKey, result);
        return result;
    }

    private static BufferedImage toArgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        BufferedImage converted = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = converted.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return converted;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void warn(IniDocument.Section section, String message) {
        OpenDoJaLog.warn(UiSkinLoader.class,
                () -> "Skin '" + skinId + "' [" + section.name().toLowerCase(Locale.ROOT) + "]: " + message);
    }
}

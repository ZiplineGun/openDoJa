package opendoja.host.skin;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Immutable description of the host window chrome: bar sizes, backgrounds, status icons and soft-key labels.
 *
 * <p>All coordinates are absolute, unscaled pixels. The bar origin is the top-left corner of the bar the
 * element belongs to. The renderer multiplies everything by the host scale with nearest-neighbor sampling,
 * so skins are authored at 1x.
 */
public final class UiSkin {
    public static final int SOFT_KEY_COUNT = 2;

    /** Roles an icon can play. {@link #NONE} is a plain decoration without any behavior. */
    public enum IconType {
        NONE("none"),
        BATTERY("battery"),
        SIGNAL_ANTENNA("signal_antenna"),
        SIGNAL("signal"),
        NO_SIGNAL("no_signal"),
        I_APPLI("iappli");

        private final String id;

        IconType(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** Parses a type name; {@code -} and spaces are treated like {@code _}. Returns {@code null} if unknown. */
        public static IconType fromId(String raw) {
            if (raw == null) {
                return null;
            }
            String normalized = raw.strip().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            if (normalized.isEmpty()) {
                return NONE;
            }
            return switch (normalized) {
                case "none" -> NONE;
                case "battery" -> BATTERY;
                case "signal_antenna", "antenna" -> SIGNAL_ANTENNA;
                case "signal" -> SIGNAL;
                case "no_signal", "nosignal", "out_of_range" -> NO_SIGNAL;
                case "iappli", "i_appli" -> I_APPLI;
                default -> null;
            };
        }
    }

    public enum Bar {
        TOP,
        BOTTOM
    }

    /** Whether {@code x} is measured from the left edge of the bar or (inverted) from the right edge. */
    public enum Anchor {
        LEFT,
        RIGHT
    }

    public enum TextAlign {
        LEFT,
        CENTER,
        RIGHT
    }

    /**
     * One placed icon.
     *
     * @param images state images; the meaning of the index depends on {@code type}
     *               (battery: level 0-3, signal: strength 1-3 at index 0-2, iappli: normal/standby/dx/dx-standby).
     *               Entries may be {@code null} when a listed file could not be loaded.
     */
    public record Icon(IconType type, Bar bar, Anchor anchor, int x, int y, List<BufferedImage> images) {
        public Icon {
            // List.copyOf would reject the null entries that stand for images that failed to load.
            images = Collections.unmodifiableList(new ArrayList<>(images == null ? List.of() : images));
        }
    }

    /**
     * Placement of one soft-key label.
     *
     * <p>With {@link Anchor#LEFT} the reference point is {@code x} pixels from the bar's left edge; with
     * {@link Anchor#RIGHT} it is {@code x} pixels from the bar's right edge. {@code align} says how the text
     * sits relative to that reference point. {@code y} is the top of the text box; {@code null} centers the text
     * vertically. The background image is placed at ({@code backgroundX}, {@code backgroundY}) with the same
     * anchor rule, using the image's left edge (LEFT) or right edge (RIGHT).
     */
    public record SoftKeyLabel(
            Anchor anchor,
            TextAlign align,
            int x,
            Integer y,
            int fontSize,
            int argbColor,
            BufferedImage backgroundImage,
            int backgroundX,
            int backgroundY) {
    }

    private final String id;
    private final String name;
    private final int topBarHeight;
    private final int bottomBarHeight;
    private final int sideWidth;
    private final int sideArgb;
    private final int topBackgroundArgb;
    private final BufferedImage topBackgroundImage;
    private final int bottomBackgroundArgb;
    private final BufferedImage bottomBackgroundImage;
    private final List<Icon> topIcons;
    private final List<Icon> bottomIcons;
    private final SoftKeyLabel[] softKeys;

    UiSkin(String id, String name, int topBarHeight, int bottomBarHeight, int sideWidth, int sideArgb,
           int topBackgroundArgb, BufferedImage topBackgroundImage,
           int bottomBackgroundArgb, BufferedImage bottomBackgroundImage,
           List<Icon> icons, SoftKeyLabel[] softKeys) {
        this.id = id;
        this.name = name;
        this.topBarHeight = topBarHeight;
        this.bottomBarHeight = bottomBarHeight;
        this.sideWidth = sideWidth;
        this.sideArgb = sideArgb;
        this.topBackgroundArgb = topBackgroundArgb;
        this.topBackgroundImage = topBackgroundImage;
        this.bottomBackgroundArgb = bottomBackgroundArgb;
        this.bottomBackgroundImage = bottomBackgroundImage;
        List<Icon> top = new ArrayList<>();
        List<Icon> bottom = new ArrayList<>();
        for (Icon icon : icons) {
            (icon.bar() == Bar.TOP ? top : bottom).add(icon);
        }
        this.topIcons = List.copyOf(top);
        this.bottomIcons = List.copyOf(bottom);
        this.softKeys = softKeys.clone();
    }

    /** Folder name of the skin (or {@link UiSkinRepository#DEFAULT_ID} for the built-in one). */
    public String id() {
        return id;
    }

    /** Display name from {@code [skin] name}, falling back to the id. */
    public String name() {
        return name;
    }

    public int topBarHeight() {
        return topBarHeight;
    }

    public int bottomBarHeight() {
        return bottomBarHeight;
    }

    /** Width of the left and right connector strips between the bars. */
    public int sideWidth() {
        return sideWidth;
    }

    public int sideArgb() {
        return sideArgb;
    }

    public int backgroundArgb(Bar bar) {
        return bar == Bar.TOP ? topBackgroundArgb : bottomBackgroundArgb;
    }

    /** Background image drawn at the bar's top-left corner in original size, or {@code null}. */
    public BufferedImage backgroundImage(Bar bar) {
        return bar == Bar.TOP ? topBackgroundImage : bottomBackgroundImage;
    }

    /** Icons of one bar in file order (later entries are drawn on top). */
    public List<Icon> icons(Bar bar) {
        return bar == Bar.TOP ? topIcons : bottomIcons;
    }

    /** @param index 0 for soft key 1, 1 for soft key 2 */
    public SoftKeyLabel softKey(int index) {
        return softKeys[index];
    }
}

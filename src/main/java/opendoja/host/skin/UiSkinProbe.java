package opendoja.host.skin;

import com.nttdocomo.ui.Frame;
import opendoja.host.ExternalFrameLayout;
import opendoja.host.ExternalFrameRenderer;
import opendoja.host.LaunchConfig;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Headless checks for the UI skin loader and renderer. Optional argument: a directory to write sample PNGs to.
 */
public final class UiSkinProbe {
    private static final int RED = 0xFFFF0000;
    private static final int GREEN_BG = 0xFF00FF00;
    private static final int BLUE = 0xFF0000FF;
    private static final int ICON_GREEN = 0xFF00A000;
    private static final int YELLOW = 0xFFFFFF00;
    private static final int MAGENTA = 0xFFFF00FF;
    private static final int WHITE = 0xFFFFFFFF;

    private UiSkinProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path outputDir = args.length > 0 ? Path.of(args[0]) : null;
        checkBuiltIn(outputDir);
        Path temp = Files.createTempDirectory("uiskin-probe");
        try {
            checkCustomSkin(temp, outputDir);
            checkIni();
            checkSourceSandbox(temp);
        } finally {
            try (Stream<Path> walk = Files.walk(temp)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
        System.out.println("UI skin probe OK");
    }

    private static void checkBuiltIn(Path outputDir) throws IOException {
        UiSkin skin = UiSkinRepository.load(UiSkinRepository.DEFAULT_ID);
        check(skin.id().equals(UiSkinRepository.DEFAULT_ID), "built-in id");
        check(skin.topBarHeight() == 27 && skin.bottomBarHeight() == 27, "built-in bars should be 27px");
        check(skin.sideWidth() == 2 && skin.sideArgb() == 0xFFD7D8DB, "built-in side strip");
        check(skin.icons(UiSkin.Bar.TOP).size() == 5, "built-in icon count: " + skin.icons(UiSkin.Bar.TOP).size());
        check(skin.softKey(0).backgroundImage() != null && skin.softKey(1).backgroundImage() != null,
                "built-in soft keys should use soft_label_bg.png");
        check(UiSkinRepository.list().get(0).id().equals(UiSkinRepository.DEFAULT_ID), "default listed first");
        check(UiSkinRepository.load("does-not-exist").id().equals(UiSkinRepository.DEFAULT_ID),
                "unknown skin should fall back to default");
        check(UiSkinRepository.load("../evil").id().equals(UiSkinRepository.DEFAULT_ID),
                "path-like skin id should fall back to default");

        ExternalFrameRenderer renderer = new ExternalFrameRenderer(true, skin, LaunchConfig.IAppliType.I_APPLI);
        ExternalFrameLayout layout = renderer.layoutFor(240, 240, 1);
        check(layout.preferredSize().width == 244 && layout.preferredSize().height == 294,
                "built-in outer size: " + layout.preferredSize());
        if (outputDir != null) {
            Frame frame = new Frame() {
            };
            frame.setSoftLabel(Frame.SOFT_KEY_1, "Menu");
            frame.setSoftLabel(Frame.SOFT_KEY_2, "Back");
            write(render(renderer, frame, 240, 240, 3), outputDir.resolve("builtin_x3.png"));
        }
    }

    private static void checkCustomSkin(Path temp, Path outputDir) throws IOException {
        Path dir = Files.createDirectories(temp.resolve("test"));
        solid(dir.resolve("red.png"), 2, 2, RED);
        solid(dir.resolve("green.png"), 2, 2, ICON_GREEN);
        solid(dir.resolve("blue.png"), 2, 2, BLUE);
        solid(dir.resolve("yellow.png"), 2, 2, YELLOW);
        solid(dir.resolve("white4.png"), 4, 4, WHITE);
        solid(dir.resolve("label.png"), 6, 6, MAGENTA);
        Files.writeString(dir.resolve("skin.ini"), String.join("\n",
                "[skin]",
                "name = Probe Skin",
                "[layout]",
                "top_bar_height = 10   ; inline comment",
                "bottom_bar_height = 12",
                "side_width = 3",
                "side_color = #FF0000",
                "top_bg_color = #00FF00",
                "bottom_bg_color = #0000FF",
                "top_bg_image = white4.png",
                "[icon]",
                "type = battery",
                "anchor = right",
                "x = 1",
                "y = 2",
                "images = red.png, green.png, blue.png, yellow.png",
                "[icon]",
                "type = none",
                "x = 5",
                "y = 1",
                "image = red.png",
                "[icon.second]",
                "type = None",
                "x = 7",
                "y = 1",
                "image = green.png",
                "[icon]",
                "type = unread_mail",
                "image = red.png",
                "[icon]",
                "type = none",
                "bar = bottom",
                "x = 0",
                "y = 0",
                "image = does-not-exist.png",
                "[softkey.1]",
                "x = 2",
                "y = 3",
                "font_size = 12",
                "color = #FFFFFF",
                "bg_image = label.png",
                "bg_x = 2",
                "bg_y = 1",
                "[softkey.2]",
                "anchor = right",
                "align = right",
                "x = 2",
                "y = 3",
                "font_size = 11",
                "color = #FFFFFF",
                "bg_image = label.png",
                "bg_x = 2",
                "bg_y = 1",
                ""));

        UiSkin skin = UiSkinLoader.load("test", SkinSource.directory(dir));
        check("Probe Skin".equals(skin.name()), "skin name");
        check("Probe Skin".equals(UiSkinLoader.readDisplayName(SkinSource.directory(dir))), "display name");
        check(skin.topBarHeight() == 10 && skin.bottomBarHeight() == 12 && skin.sideWidth() == 3, "layout numbers");
        check(skin.icons(UiSkin.Bar.TOP).size() == 3,
                "unsupported types and icons without images must be skipped: " + skin.icons(UiSkin.Bar.TOP).size());
        check(skin.icons(UiSkin.Bar.BOTTOM).isEmpty(), "icon with a missing image is dropped");

        ExternalFrameRenderer renderer = new ExternalFrameRenderer(true, skin, LaunchConfig.IAppliType.I_APPLI);
        ExternalFrameLayout layout = renderer.layoutFor(20, 20, 1);
        check(layout.preferredSize().width == 26 && layout.preferredSize().height == 42,
                "custom outer size: " + layout.preferredSize());
        check(layout.screenArea().x == 3 && layout.screenArea().y == 10, "screen origin follows the skin");

        // No labels: neither text nor label backgrounds may appear.
        Frame empty = new Frame() {
        };
        BufferedImage plain = render(renderer, empty, 20, 20, 1);
        check(plain.getRGB(1, 15) == RED, "left side strip color");
        check(plain.getRGB(24, 15) == RED, "right side strip color");
        check(plain.getRGB(10, 5) == GREEN_BG, "top bar background color");
        check(plain.getRGB(0, 0) == WHITE && plain.getRGB(3, 3) == WHITE && plain.getRGB(4, 4) == GREEN_BG,
                "background image sits at the bar's top-left in original size");
        check(plain.getRGB(23, 2) == YELLOW && plain.getRGB(24, 3) == YELLOW,
                "anchor=right places the battery 1px from the right edge");
        check(plain.getRGB(22, 2) == GREEN_BG && plain.getRGB(25, 2) == GREEN_BG, "battery is exactly 2px wide");
        check(plain.getRGB(5, 1) == RED && plain.getRGB(6, 2) == RED, "absolute-position icon (type none)");
        check(plain.getRGB(7, 1) == ICON_GREEN, "second type-none icon");
        check(plain.getRGB(10, 35) == BLUE, "bottom bar background color");
        check(plain.getRGB(4, 34) == BLUE && plain.getRGB(20, 34) == BLUE,
                "label backgrounds must be hidden while there is no text");

        Frame labeled = new Frame() {
        };
        labeled.setSoftLabel(Frame.SOFT_KEY_1, "AB");
        BufferedImage one = render(renderer, labeled, 20, 20, 1);
        int bottomY = 30;
        check(one.getRGB(2, bottomY + 1) == MAGENTA, "soft key 1 background appears with its text");
        check(one.getRGB(18, bottomY + 1) == BLUE, "soft key 2 background stays hidden without text");
        check(countColor(one, 2, bottomY + 1, 6, 6, WHITE) > 0, "soft key 1 text is drawn in the configured color");

        Frame right = new Frame() {
        };
        right.setSoftLabel(Frame.SOFT_KEY_2, "AB");
        BufferedImage two = render(renderer, right, 20, 20, 1);
        // anchor=right, bg_x=2, 6px wide -> occupies x = 26-2-6 .. 26-2-1
        check(two.getRGB(18, bottomY + 1) == MAGENTA && two.getRGB(23, bottomY + 1) == MAGENTA,
                "right-anchored label background");
        check(two.getRGB(2, bottomY + 1) == BLUE, "soft key 1 background hidden without text");
        int maxWhite = maxColorX(two, 0, bottomY, 26, 12, WHITE);
        check(maxWhite >= 19 && maxWhite <= 23, "align=right keeps text left of the anchor point: " + maxWhite);

        // Integer scaling must be nearest-neighbor: hard 3x3 blocks, no blended pixels.
        BufferedImage scaled = render(renderer, empty, 20, 20, 3);
        check(scaled.getWidth() == 78 && scaled.getHeight() == 126, "x3 output size");
        // The 2x2 battery at (23,2) becomes a 6x6 block covering x 69..74, y 6..11.
        for (int y = 6; y < 12; y++) {
            for (int x = 69; x < 75; x++) {
                check(scaled.getRGB(x, y) == YELLOW, "x3 block (" + x + "," + y + ") must be solid");
            }
        }
        check(scaled.getRGB(68, 6) == GREEN_BG && scaled.getRGB(75, 6) == GREEN_BG
                && scaled.getRGB(69, 5) == GREEN_BG && scaled.getRGB(69, 12) == GREEN_BG,
                "x3 edges must be hard (no blended pixels)");

        if (outputDir != null) {
            Frame demo = new Frame() {
            };
            demo.setSoftLabel(Frame.SOFT_KEY_1, "AB");
            demo.setSoftLabel(Frame.SOFT_KEY_2, "CD");
            write(render(renderer, demo, 20, 20, 8), outputDir.resolve("custom_x8.png"));
        }
    }

    private static void checkIni() {
        IniDocument doc = IniDocument.parse("\uFEFF; c\n# c\n[A.B]\nKey = #D7D8DB ; tail\nq = \"x y\"\n[a.b]\nk=2\n");
        check(doc.sections().size() == 2, "duplicate sections are kept");
        check("#D7D8DB".equals(doc.sections().get(0).get("key")), "# is not an inline comment; ; is");
        check("x y".equals(doc.sections().get(0).get("q")), "quotes are stripped");
    }

    private static void checkSourceSandbox(Path temp) throws IOException {
        Files.writeString(temp.resolve("secret.txt"), "x");
        Path skinDir = Files.createDirectories(temp.resolve("sandbox"));
        SkinSource source = SkinSource.directory(skinDir);
        check(source.open("../secret.txt") == null, "parent traversal must be rejected");
        check(source.open("/etc/passwd") == null, "absolute paths must be rejected");
        check(source.open("C:/x.png") == null, "drive paths must be rejected");
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private static BufferedImage render(ExternalFrameRenderer renderer, Frame frame, int w, int h, int scale) {
        ExternalFrameLayout layout = renderer.layoutFor(w, h, scale);
        BufferedImage image = new BufferedImage(layout.preferredSize().width, layout.preferredSize().height,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            renderer.paint(g, frame, null, w, h, scale);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void solid(Path file, int w, int h, int argb) throws IOException {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setRGB(x, y, argb);
            }
        }
        ImageIO.write(image, "png", file.toFile());
    }

    private static int countColor(BufferedImage image, int x0, int y0, int w, int h, int argb) {
        int count = 0;
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                if (image.getRGB(x, y) == argb) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int maxColorX(BufferedImage image, int x0, int y0, int w, int h, int argb) {
        int max = -1;
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                if (image.getRGB(x, y) == argb) {
                    max = Math.max(max, x);
                }
            }
        }
        return max;
    }

    private static void write(BufferedImage image, Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        ImageIO.write(image, "png", file.toFile());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}

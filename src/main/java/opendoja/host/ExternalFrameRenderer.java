package opendoja.host;

import com.nttdocomo.ui.Frame;
import opendoja.host.skin.UiSkin;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ExternalFrameRenderer {
    private static final int[] SOFT_KEYS = {
            Frame.SOFT_KEY_1,
            Frame.SOFT_KEY_2
    };

    private final List<HostOverlayRenderer> overlays = new CopyOnWriteArrayList<>();
    private final LaunchConfig.IAppliType iAppliType;
    private final boolean standbyLaunch;
    private volatile UiSkin skin;
    private volatile boolean enabled;

    public ExternalFrameRenderer(boolean enabled, UiSkin skin, LaunchConfig.IAppliType iAppliType) {
        this(enabled, skin, iAppliType, com.nttdocomo.ui.IApplication.LAUNCHED_FROM_MENU);
    }

    public ExternalFrameRenderer(boolean enabled, UiSkin skin, LaunchConfig.IAppliType iAppliType, int launchType) {
        this.enabled = enabled;
        this.skin = Objects.requireNonNull(skin, "skin");
        this.iAppliType = iAppliType == null ? LaunchConfig.IAppliType.I_APPLI : iAppliType;
        this.standbyLaunch = launchType == com.nttdocomo.ui.IApplication.LAUNCHED_AS_CONCIERGE;
    }

    public UiSkin skin() {
        return skin;
    }

    /** Swaps the skin. Callers must re-query {@link #layoutFor} afterwards because the bar sizes may differ. */
    public void setSkin(UiSkin skin) {
        this.skin = Objects.requireNonNull(skin, "skin");
    }

    boolean enabled() {
        return enabled;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    void addOverlay(HostOverlayRenderer overlay) {
        overlays.add(Objects.requireNonNull(overlay, "overlay"));
    }

    void removeOverlay(HostOverlayRenderer overlay) {
        overlays.remove(overlay);
    }

    public ExternalFrameLayout layoutFor(int viewportWidth, int viewportHeight, int scale) {
        return layoutFor(viewportWidth, viewportHeight, (double) Math.max(1, scale));
    }

    public ExternalFrameLayout layoutFor(int viewportWidth, int viewportHeight, double scale) {
        return layoutFor(viewportWidth, viewportHeight, scale, skin);
    }

    private ExternalFrameLayout layoutFor(int viewportWidth, int viewportHeight, double scale, UiSkin skin) {
        double clampedScale = Math.max(0.01d, scale);
        String rotation = OpenDoJaLaunchArgs.displayRotation();
        int rotatedViewportWidth = swapsDimensions(rotation) ? viewportHeight : viewportWidth;
        int rotatedViewportHeight = swapsDimensions(rotation) ? viewportWidth : viewportHeight;
        if (!enabled) {
            Rectangle screenArea = new Rectangle(0, 0, rotatedViewportWidth, rotatedViewportHeight);
            Rectangle drawArea = new Rectangle(0, 0, rotatedViewportWidth, rotatedViewportHeight);
            return new ExternalFrameLayout(false, clampedScale, screenArea, drawArea, new Rectangle(), new Rectangle(),
                    new Rectangle(), new Rectangle(), new Rectangle(), new Rectangle(),
                    scaledDimension(rotatedViewportWidth, rotatedViewportHeight, clampedScale));
        }

        int sideWidth = skin.sideWidth();
        int topHeight = skin.topBarHeight();
        int bottomHeight = skin.bottomBarHeight();
        int outerWidth = rotatedViewportWidth + sideWidth * 2;
        int outerHeight = rotatedViewportHeight + topHeight + bottomHeight;

        Rectangle screenArea = new Rectangle(sideWidth, topHeight, rotatedViewportWidth, rotatedViewportHeight);
        Rectangle drawArea = new Rectangle(screenArea);
        Rectangle topBar = new Rectangle(0, 0, outerWidth, topHeight);
        Rectangle bottomBar = new Rectangle(0, topHeight + rotatedViewportHeight, outerWidth, bottomHeight);
        Rectangle leftConnector = new Rectangle(0, topHeight, sideWidth, rotatedViewportHeight);
        Rectangle rightConnector = new Rectangle(sideWidth + rotatedViewportWidth, topHeight, sideWidth, rotatedViewportHeight);

        // Skins position elements absolutely inside the bars, so the whole bar is the usable area.
        return new ExternalFrameLayout(true, clampedScale, screenArea, drawArea, topBar, bottomBar,
                leftConnector, rightConnector, new Rectangle(topBar), new Rectangle(bottomBar),
                scaledDimension(outerWidth, outerHeight, clampedScale));
    }

    public void paint(Graphics2D graphics, Frame frame, BufferedImage drawImage, int viewportWidth, int viewportHeight, double scale) {
        UiSkin activeSkin = this.skin;
        ExternalFrameLayout layout = layoutFor(viewportWidth, viewportHeight, scale, activeSkin);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.scale(layout.scale(), layout.scale());
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
            g.setColor(Color.BLACK);
            Dimension logicalSize = logicalSize(layout);
            g.fillRect(0, 0, logicalSize.width, logicalSize.height);
            if (layout.enabled()) {
                paintChrome(g, layout, activeSkin, frame);
            }
            paintDrawArea(g, layout.screenArea(), layout.drawArea(), drawImage);
            paintOverlays(g, layout, frame, drawImage);
        } finally {
            g.dispose();
        }
    }

    private static Dimension scaledDimension(int width, int height, double scale) {
        return new Dimension(scaleDimension(width, scale), scaleDimension(height, scale));
    }

    private static int scaleDimension(int value, double scale) {
        return Math.max(1, (int) Math.ceil(value * scale - 0.000001d));
    }

    private static Dimension logicalSize(ExternalFrameLayout layout) {
        if (!layout.enabled()) {
            return new Dimension(layout.screenArea().width, layout.screenArea().height);
        }
        int width = layout.topBar().width;
        int height = layout.bottomBar().y + layout.bottomBar().height;
        return new Dimension(width, height);
    }

    private void paintChrome(Graphics2D g, ExternalFrameLayout layout, UiSkin skin, Frame frame) {
        g.setColor(new Color(skin.sideArgb(), true));
        fill(g, layout.leftConnector());
        fill(g, layout.rightConnector());

        paintBar(g, layout.topBar(), skin, UiSkin.Bar.TOP);
        paintBar(g, layout.bottomBar(), skin, UiSkin.Bar.BOTTOM);
        paintSoftKeys(g, layout.bottomBar(), skin, frame);
    }

    private static void fill(Graphics2D g, Rectangle area) {
        if (area.width > 0 && area.height > 0) {
            g.fillRect(area.x, area.y, area.width, area.height);
        }
    }

    private void paintDrawArea(Graphics2D g, Rectangle screenArea, Rectangle drawArea, BufferedImage drawImage) {
        g.setColor(Color.BLACK);
        g.fillRect(screenArea.x, screenArea.y, screenArea.width, screenArea.height);
        if (drawImage != null) {
            paintRotatedImage(g, drawArea, drawImage);
        }
    }

    private void paintRotatedImage(Graphics2D g, Rectangle drawArea, BufferedImage drawImage) {
        String rotation = OpenDoJaLaunchArgs.displayRotation();
        if (OpenDoJaLaunchArgs.DISPLAY_ROTATION_NONE.equals(rotation)) {
            g.drawImage(drawImage, drawArea.x, drawArea.y, drawArea.width, drawArea.height, null);
            return;
        }
        Graphics2D rotatedGraphics = (Graphics2D) g.create();
        try {
            AffineTransform transform = new AffineTransform();
            if (OpenDoJaLaunchArgs.DISPLAY_ROTATION_LEFT.equals(rotation)) {
                transform.translate(drawArea.x, drawArea.y + drawArea.height);
                transform.rotate(-Math.PI / 2d);
                transform.scale(
                        drawArea.height / (double) Math.max(1, drawImage.getWidth()),
                        drawArea.width / (double) Math.max(1, drawImage.getHeight()));
            } else if (OpenDoJaLaunchArgs.DISPLAY_ROTATION_RIGHT.equals(rotation)) {
                transform.translate(drawArea.x + drawArea.width, drawArea.y);
                transform.rotate(Math.PI / 2d);
                transform.scale(
                        drawArea.height / (double) Math.max(1, drawImage.getWidth()),
                        drawArea.width / (double) Math.max(1, drawImage.getHeight()));
            } else {
                transform.translate(drawArea.x + drawArea.width, drawArea.y + drawArea.height);
                transform.rotate(Math.PI);
                transform.scale(
                        drawArea.width / (double) Math.max(1, drawImage.getWidth()),
                        drawArea.height / (double) Math.max(1, drawImage.getHeight()));
            }
            rotatedGraphics.drawImage(drawImage, transform, null);
        } finally {
            rotatedGraphics.dispose();
        }
    }

    private static boolean swapsDimensions(String rotation) {
        return OpenDoJaLaunchArgs.DISPLAY_ROTATION_LEFT.equals(rotation)
                || OpenDoJaLaunchArgs.DISPLAY_ROTATION_RIGHT.equals(rotation);
    }

    /** Draws the bar background (color, then image at the top-left corner) and its icons, clipped to the bar. */
    private void paintBar(Graphics2D g, Rectangle bar, UiSkin skin, UiSkin.Bar which) {
        if (bar.width <= 0 || bar.height <= 0) {
            return;
        }
        Graphics2D barGraphics = (Graphics2D) g.create();
        try {
            barGraphics.clipRect(bar.x, bar.y, bar.width, bar.height);
            barGraphics.setColor(new Color(skin.backgroundArgb(which), true));
            barGraphics.fillRect(bar.x, bar.y, bar.width, bar.height);
            BufferedImage background = skin.backgroundImage(which);
            if (background != null) {
                barGraphics.drawImage(background, bar.x, bar.y, null);
            }
            for (UiSkin.Icon icon : skin.icons(which)) {
                BufferedImage image = iconImage(icon);
                if (image == null) {
                    continue;
                }
                int drawX = anchoredLeft(bar, icon.anchor(), icon.x(), image.getWidth());
                barGraphics.drawImage(image, drawX, bar.y + icon.y(), null);
            }
        } finally {
            barGraphics.dispose();
        }
    }

    /** Left edge of an element {@code width} wide placed at {@code offset} from the anchored edge of {@code bar}. */
    private static int anchoredLeft(Rectangle bar, UiSkin.Anchor anchor, int offset, int width) {
        return anchor == UiSkin.Anchor.RIGHT
                ? bar.x + bar.width - offset - width
                : bar.x + offset;
    }

    private void paintSoftKeys(Graphics2D g, Rectangle bar, UiSkin skin, Frame frame) {
        if (bar.width <= 0 || bar.height <= 0) {
            return;
        }
        if (frame == null || !frame.isSoftLabelVisible()) {
            return;
        }
        for (int i = 0; i < SOFT_KEYS.length; i++) {
            String label = frame.getSoftLabel(SOFT_KEYS[i]);
            // Neither the label nor its background image is drawn while there is no text.
            if (label == null || label.isBlank()) {
                continue;
            }
            paintSoftKeyLabel(g, bar, skin.softKey(i), label);
        }
    }

    private void paintSoftKeyLabel(Graphics2D g, Rectangle bar, UiSkin.SoftKeyLabel spec, String label) {
        Graphics2D labelGraphics = (Graphics2D) g.create();
        try {
            labelGraphics.clipRect(bar.x, bar.y, bar.width, bar.height);
            BufferedImage background = spec.backgroundImage();
            if (background != null) {
                int bgX = anchoredLeft(bar, spec.anchor(), spec.backgroundX(), background.getWidth());
                labelGraphics.drawImage(background, bgX, bar.y + spec.backgroundY(), null);
            }
            com.nttdocomo.ui.Font font = softKeyFont(spec.fontSize());
            int textWidth = font.stringWidth(label);
            int reference = spec.anchor() == UiSkin.Anchor.RIGHT
                    ? bar.x + bar.width - spec.x()
                    : bar.x + spec.x();
            int textX = switch (spec.align()) {
                case LEFT -> reference;
                case CENTER -> reference - textWidth / 2;
                case RIGHT -> reference - textWidth;
            };
            int top = spec.y() != null ? spec.y() : (bar.height - font.getHeight()) / 2;
            font.drawHostString(labelGraphics, label, textX, bar.y + top + font.getAscent(), spec.argbColor());
        } finally {
            labelGraphics.dispose();
        }
    }

    /** Picks the supported font size closest to the requested one (the smaller one on a tie). */
    private static com.nttdocomo.ui.Font softKeyFont(int requestedSize) {
        int selected = -1;
        for (int size : com.nttdocomo.ui.Font.getSupportedFontSizes()) {
            if (selected < 0 || Math.abs(size - requestedSize) < Math.abs(selected - requestedSize)) {
                selected = size;
            }
        }
        return com.nttdocomo.ui.Font.getFont(
                com.nttdocomo.ui.Font.FACE_SYSTEM | com.nttdocomo.ui.Font.STYLE_PLAIN, selected);
    }

    /** Resolves which state image an icon shows right now, or {@code null} when it should be hidden. */
    private BufferedImage iconImage(UiSkin.Icon icon) {
        List<BufferedImage> images = icon.images();
        return switch (icon.type()) {
            case NONE -> imageAt(images, 0);
            case BATTERY -> imageAt(images, clampIndex(resolveBatteryLevel(), images));
            case SIGNAL_ANTENNA -> hasInternetConnectivity() ? imageAt(images, 0) : null;
            case SIGNAL -> hasInternetConnectivity()
                    ? imageAt(images, clampIndex(resolveSignalStrength() - 1, images))
                    : null;
            case NO_SIGNAL -> hasInternetConnectivity() ? null : imageAt(images, 0);
            case I_APPLI -> iAppliImage(images);
        };
    }

    /** Image order is normal, standby, dx, dx-standby; missing states fall back to the closest earlier one. */
    private BufferedImage iAppliImage(List<BufferedImage> images) {
        int index = (iAppliType == LaunchConfig.IAppliType.I_APPLI_DX ? 2 : 0) + (standbyLaunch ? 1 : 0);
        int[] candidates = {index, index & ~1, index & 1, 0};
        for (int candidate : candidates) {
            BufferedImage image = imageAt(images, candidate);
            if (image != null) {
                return image;
            }
        }
        return null;
    }

    private static BufferedImage imageAt(List<BufferedImage> images, int index) {
        return index >= 0 && index < images.size() ? images.get(index) : null;
    }

    private static int clampIndex(int index, List<BufferedImage> images) {
        return Math.max(0, Math.min(index, images.size() - 1));
    }

    private int resolveSignalStrength() {
        // TODO: Map host Wi-Fi strength into the handset signal levels.
        return 3;
    }

    private boolean hasInternetConnectivity() {
        // TODO: Replace this stub with host connectivity detection and use no_signal when offline.
        return true;
    }

    private int resolveBatteryLevel() {
        // TODO: Detect host battery percentage when available and map it into 0-3.
        return 3;
    }

    private void paintOverlays(Graphics2D g, ExternalFrameLayout layout, Frame frame, BufferedImage drawImage) {
        for (HostOverlayRenderer overlay : overlays) {
            overlay.paint(g, layout, frame, drawImage);
        }
    }
}

package rps.ui;

import rps.ui.theme.Theme;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.Dimension;
import java.awt.Image;
import java.io.File;
import java.net.URL;

/**
 * Square logo slot. Looks for images/rps-logo.png or images/rps-logo.jpg (classpath
 * first, then a filesystem fallback so it still works when launched from the project
 * root without a packaged jar) — deliberately NOT the pre-existing images/logo.jpg,
 * which turned out to be the Conrad Pune hotel's logo left over in the project
 * template, not this business's. Falls back to the brand name in brand colour if no
 * matching file is found — the header must never look broken, and must never show
 * someone else's trademark by accident.
 */
public final class BrandLogo extends JLabel {

    private static final String[] CANDIDATES = {"images/rps-logo.png", "images/rps-logo.jpg"};

    public BrandLogo(int size) {
        super("", SwingConstants.CENTER);
        ImageIcon icon = loadIcon(size);
        if (icon != null) {
            // Only an actual image is forced into a square box; text below sizes to its content.
            setPreferredSize(new Dimension(size, size));
            setIcon(icon);
        } else {
            setText("RPS");
            setFont(Theme.FONT_BRAND.deriveFont((float) Math.round(size * 0.42)));
            setForeground(Theme.PRIMARY);
        }
    }

    private static ImageIcon loadIcon(int size) {
        for (String name : CANDIDATES) {
            ImageIcon icon = tryClasspath(name);
            if (icon == null) icon = tryFilesystem(name);
            if (icon != null && icon.getIconWidth() > 0) {
                Image scaled = icon.getImage().getScaledInstance(size, size, Image.SCALE_SMOOTH);
                return new ImageIcon(scaled);
            }
        }
        return null;
    }

    private static ImageIcon tryClasspath(String name) {
        URL url = BrandLogo.class.getClassLoader().getResource(name);
        return url == null ? null : new ImageIcon(url);
    }

    private static ImageIcon tryFilesystem(String name) {
        File f = new File(name);
        return f.exists() ? new ImageIcon(f.getAbsolutePath()) : null;
    }
}

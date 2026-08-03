package com.nux.ui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;

/**
 * Saves the app's own scene as a PNG — for documentation and bug reports.
 *
 * <p>The conversion is done by hand rather than with {@code SwingFXUtils} because
 * {@code javafx.swing} is not a dependency; {@code java.desktop} already is (the AWT
 * tray backend), so {@code ImageIO} is available.
 */
public final class Screenshot {

    private Screenshot() {}

    /** Must be called on the FX thread. Returns false if nothing could be written. */
    public static boolean save(Scene scene, Path file) {
        if (scene == null || scene.getWidth() < 1 || scene.getHeight() < 1) {
            return false;
        }
        WritableImage image = scene.snapshot(null);
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        PixelReader pixels = image.getPixelReader();
        if (pixels == null || width < 1 || height < 1) {
            return false;
        }
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setRGB(x, y, pixels.getArgb(x, y));
            }
        }
        try {
            if (file.getParent() != null) {
                java.nio.file.Files.createDirectories(file.getParent());
            }
            return ImageIO.write(out, "png", file.toFile());
        } catch (IOException e) {
            return false;
        }
    }
}

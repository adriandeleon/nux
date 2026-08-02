package com.nux.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * A minimal 270° arc gauge drawn on a Canvas: fraction 0..1, a big value text, a title.
 * Color moves green → amber → red by thresholds; {@code highIsBad} inverts them (for load).
 */
public final class ArcGauge extends StackPane {

    private static final double SIZE = 170;
    private static final Color OK = Color.web("#2da44e");
    private static final Color WARN = Color.web("#d4a72c");
    private static final Color BAD = Color.web("#cf222e");
    private static final Color TRACK = Color.web("#6e7781", 0.25);
    private static final Color TEXT = Color.web("#c9d1d9");
    private static final Color MUTED = Color.web("#8b949e");

    private final Canvas canvas = new Canvas(SIZE, SIZE);
    private final String title;
    private final boolean highIsBad;

    public ArcGauge(String title, boolean highIsBad) {
        this.title = title;
        this.highIsBad = highIsBad;
        getChildren().add(canvas);
        clear();
    }

    /** No data: gray track and a dash. */
    public void clear() {
        draw(Double.NaN, "—");
    }

    public void set(double fraction, String valueText) {
        draw(Math.clamp(fraction, 0, 1), valueText);
    }

    private void draw(double fraction, String valueText) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, SIZE, SIZE);

        double pad = 14;
        double d = SIZE - 2 * pad;
        double startAngle = -135; // gauge opens downward, sweeping clockwise 270°
        double fullExtent = -270;

        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineWidth(11);
        g.setStroke(TRACK);
        g.strokeArc(pad, pad, d, d, startAngle, fullExtent, ArcType.OPEN);

        if (!Double.isNaN(fraction)) {
            g.setStroke(colorFor(fraction));
            g.strokeArc(pad, pad, d, d, startAngle, fullExtent * fraction, ArcType.OPEN);
        }

        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(TEXT);
        g.setFont(Font.font("System", FontWeight.BOLD, 26));
        g.fillText(valueText, SIZE / 2, SIZE / 2 + 4);
        g.setFill(MUTED);
        g.setFont(Font.font("System", FontWeight.NORMAL, 13));
        g.fillText(title, SIZE / 2, SIZE / 2 + 30);
    }

    private Color colorFor(double fraction) {
        double goodness = highIsBad ? 1 - fraction : fraction;
        if (goodness > 0.5) {
            return OK;
        }
        if (goodness > 0.2) {
            return WARN;
        }
        return BAD;
    }
}

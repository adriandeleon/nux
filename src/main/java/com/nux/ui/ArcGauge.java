package com.nux.ui;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import com.nux.protocol.UpsStatus.Severity;

/**
 * A minimal 270° arc gauge drawn on a Canvas: fraction 0..1, a big value text, a title.
 * Color moves green → amber → red by thresholds; {@code highIsBad} inverts them (for load).
 */
public final class ArcGauge extends StackPane {

    private static final double SIZE = 170;

    private final Canvas canvas = new Canvas(SIZE, SIZE);
    private final String title;
    private final boolean highIsBad;
    // The last reading, so a theme change can repaint it in the new colours.
    private double lastFraction = Double.NaN;
    private String lastText = "—";
    private Severity lastSeverity;
    private final ChangeListener<Theme.Palette> repaint = (o, was, now) -> draw(lastFraction, lastText, lastSeverity);

    public ArcGauge(String title, boolean highIsBad) {
        this.title = title;
        this.highIsBad = highIsBad;
        getChildren().add(canvas);
        setAccessibleRole(javafx.scene.AccessibleRole.TEXT);
        Theme.paletteProperty().addListener(new WeakChangeListener<>(repaint));
        clear();
    }

    /** No data: gray track and a dash. */
    public void clear() {
        draw(Double.NaN, "—", null);
    }

    public void set(double fraction, String valueText) {
        draw(Math.clamp(fraction, 0, 1), valueText, null);
    }

    /** Like {@link #set(double, String)}, but coloured by a judgement made elsewhere. */
    public void set(double fraction, String valueText, Severity severity) {
        draw(Math.clamp(fraction, 0, 1), valueText, severity);
    }

    private void draw(double fraction, String valueText, Severity severity) {
        lastFraction = fraction;
        lastText = valueText;
        lastSeverity = severity;
        Theme.Palette p = Theme.palette();
        // A Canvas is invisible to screen readers; say what it shows.
        setAccessibleText(title + ": " + valueText);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, SIZE, SIZE);

        double pad = 14;
        double d = SIZE - 2 * pad;
        double startAngle = -135; // gauge opens downward, sweeping clockwise 270°
        double fullExtent = -270;

        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineWidth(11);
        g.setStroke(p.track());
        g.strokeArc(pad, pad, d, d, startAngle, fullExtent, ArcType.OPEN);

        if (!Double.isNaN(fraction)) {
            g.setStroke(severity != null ? colorFor(severity, p) : colorFor(fraction, p));
            g.strokeArc(pad, pad, d, d, startAngle, fullExtent * fraction, ArcType.OPEN);
        }

        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(p.text());
        g.setFont(Font.font("System", FontWeight.BOLD, 26));
        g.fillText(valueText, SIZE / 2, SIZE / 2 + 4);
        g.setFill(p.muted());
        g.setFont(Font.font("System", FontWeight.NORMAL, 13));
        g.fillText(title, SIZE / 2, SIZE / 2 + 30);
    }

    private static Color colorFor(Severity severity, Theme.Palette p) {
        return switch (severity) {
            case OK -> p.ok();
            case WARNING -> p.warn();
            case CRITICAL -> p.bad();
        };
    }

    private Color colorFor(double fraction, Theme.Palette p) {
        double goodness = highIsBad ? 1 - fraction : fraction;
        if (goodness > 0.5) {
            return p.ok();
        }
        if (goodness > 0.2) {
            return p.warn();
        }
        return p.bad();
    }
}

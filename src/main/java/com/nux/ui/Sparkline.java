package com.nux.ui;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * A minimal line sparkline on a Canvas. Fixed scale when min/max are given
 * (e.g. 0–100 for percentages), else autoscaled to the data with padding — but never
 * to less than {@link #withMinSpan its minimum span}, and with the scale's bounds
 * printed, so a 1 V wobble doesn't fill the strip like a surge. NaN samples break the
 * line into segments.
 */
public final class Sparkline extends StackPane {

    private static final double LABEL_GUTTER = 24;

    private final Canvas canvas;
    private final Double fixedMin;
    private final Double fixedMax;
    private double minSpan;
    private double[] lastValues = new double[0]; // repainted as-is on a theme change
    private final ChangeListener<Theme.Palette> repaint = (o, was, now) -> update(lastValues);

    public Sparkline(double width, double height, Double fixedMin, Double fixedMax) {
        this.canvas = new Canvas(width, height);
        this.fixedMin = fixedMin;
        this.fixedMax = fixedMax;
        getChildren().add(canvas);
        Theme.paletteProperty().addListener(new WeakChangeListener<>(repaint));
        update(new double[0]);
    }

    /** The smallest range an autoscaled line may stretch to fill, in data units. */
    public Sparkline withMinSpan(double span) {
        this.minSpan = span;
        return this;
    }

    public void update(double[] values) {
        lastValues = values;
        Theme.Palette p = Theme.palette();
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.clearRect(0, 0, w, h);
        g.setStroke(p.track());
        g.setLineWidth(1);
        g.strokeLine(0, h - 0.5, w, h - 0.5);

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int finite = 0;
        for (double v : values) {
            if (!Double.isNaN(v)) {
                min = Math.min(min, v);
                max = Math.max(max, v);
                finite++;
            }
        }
        if (finite == 0) {
            return;
        }
        if (fixedMin != null) {
            min = fixedMin;
        }
        if (fixedMax != null) {
            max = fixedMax;
        }
        boolean autoscaled = fixedMin == null && fixedMax == null;
        if (autoscaled && max - min < minSpan) {
            double mid = (min + max) / 2;
            min = mid - minSpan / 2;
            max = mid + minSpan / 2;
        }
        if (max - min < 1e-9) { // flat data: park the line mid-height instead of dividing by ~0
            min -= 1;
            max += 1;
        } else if (autoscaled) {
            double pad = (max - min) * 0.1;
            min -= pad;
            max += pad;
        }
        // An autoscaled line leaves a gutter on the right for its scale, so a rising line
        // can never draw over the label at the top.
        double plotW = autoscaled ? w - LABEL_GUTTER : w;
        if (autoscaled) {
            g.setFill(p.scale());
            g.setFont(Font.font(9));
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(String.format("%.0f", max), w, 9);
            g.fillText(String.format("%.0f", min), w, h - 3);
        }

        double stepX = values.length > 1 ? plotW / (values.length - 1) : 0;
        g.setStroke(p.line());
        g.setLineWidth(1.5);
        boolean penDown = false;
        double prevX = 0;
        double prevY = 0;
        for (int i = 0; i < values.length; i++) {
            double v = values[i];
            if (Double.isNaN(v)) {
                penDown = false;
                continue;
            }
            double x = values.length > 1 ? i * stepX : plotW / 2;
            double y = h - 2 - (v - min) / (max - min) * (h - 4);
            if (penDown) {
                g.strokeLine(prevX, prevY, x, y);
            }
            prevX = x;
            prevY = y;
            penDown = true;
        }
    }
}

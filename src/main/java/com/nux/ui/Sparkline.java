package com.nux.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

/**
 * A minimal line sparkline on a Canvas. Fixed scale when min/max are given
 * (e.g. 0–100 for percentages), else autoscaled to the data with padding.
 * NaN samples break the line into segments.
 */
public final class Sparkline extends StackPane {

    private static final Color LINE = Color.web("#58a6ff");
    private static final Color BASELINE = Color.web("#6e7781", 0.25);

    private final Canvas canvas;
    private final Double fixedMin;
    private final Double fixedMax;

    public Sparkline(double width, double height, Double fixedMin, Double fixedMax) {
        this.canvas = new Canvas(width, height);
        this.fixedMin = fixedMin;
        this.fixedMax = fixedMax;
        getChildren().add(canvas);
        update(new double[0]);
    }

    public void update(double[] values) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.clearRect(0, 0, w, h);
        g.setStroke(BASELINE);
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
        if (max - min < 1e-9) { // flat data: park the line mid-height instead of dividing by ~0
            min -= 1;
            max += 1;
        } else if (fixedMin == null && fixedMax == null) {
            double pad = (max - min) * 0.1;
            min -= pad;
            max += pad;
        }

        double stepX = values.length > 1 ? w / (values.length - 1) : 0;
        g.setStroke(LINE);
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
            double x = values.length > 1 ? i * stepX : w / 2;
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

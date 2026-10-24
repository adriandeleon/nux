package com.nux.ui;

import static com.nux.i18n.Messages.tr;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import com.nux.store.HistorySample;

/**
 * A resizable line chart over time: y-axis labels, a few time ticks, and gaps where
 * the series has no value. Canvas-based so it stays cheap with thousands of points.
 */
public final class TimeChart extends Pane {

    private static final Color LINE = Color.web("#58a6ff");
    private static final Color AXIS = Color.web("#6e7781", 0.5);
    private static final Color TEXT = Color.web("#8b949e");
    private static final double PAD_LEFT = 46;
    private static final double PAD_BOTTOM = 22;
    private static final double PAD_TOP = 10;
    private static final double PAD_RIGHT = 8;

    private final Canvas canvas = new Canvas();
    private final String title;
    private final String unit;
    private final Double fixedMin;
    private final Double fixedMax;

    private List<HistorySample> samples = List.of();
    private java.util.function.ToDoubleFunction<HistorySample> value = s -> Double.NaN;

    public TimeChart(String title, String unit, Double fixedMin, Double fixedMax) {
        this.title = title;
        this.unit = unit;
        this.fixedMin = fixedMin;
        this.fixedMax = fixedMax;
        getChildren().add(canvas);
        setMinHeight(120);
        setPrefHeight(160);
        widthProperty().addListener((o, a, b) -> redraw());
        heightProperty().addListener((o, a, b) -> redraw());
    }

    public void setData(List<HistorySample> samples, java.util.function.ToDoubleFunction<HistorySample> value) {
        this.samples = samples;
        this.value = value;
        redraw();
    }

    private void redraw() {
        double w = getWidth();
        double h = getHeight();
        canvas.setWidth(w);
        canvas.setHeight(h);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        if (w < 60 || h < 40) {
            return;
        }
        g.setFont(Font.font(11));

        double plotW = w - PAD_LEFT - PAD_RIGHT;
        double plotH = h - PAD_TOP - PAD_BOTTOM;
        g.setStroke(AXIS);
        g.setLineWidth(1);
        g.strokeLine(PAD_LEFT, PAD_TOP, PAD_LEFT, PAD_TOP + plotH);
        g.strokeLine(PAD_LEFT, PAD_TOP + plotH, PAD_LEFT + plotW, PAD_TOP + plotH);

        g.setFill(TEXT);
        g.fillText(title + (unit.isEmpty() ? "" : " (" + unit + ")"), PAD_LEFT, PAD_TOP - 1);

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int finite = 0;
        for (HistorySample s : samples) {
            double v = value.applyAsDouble(s);
            if (!Double.isNaN(v)) {
                min = Math.min(min, v);
                max = Math.max(max, v);
                finite++;
            }
        }
        if (finite == 0) {
            g.fillText(tr("history.noData"), PAD_LEFT + 8, PAD_TOP + plotH / 2);
            return;
        }
        if (fixedMin != null) {
            min = fixedMin;
        }
        if (fixedMax != null) {
            max = fixedMax;
        }
        if (max - min < 1e-9) {
            min -= 1;
            max += 1;
        }

        for (int i = 0; i <= 2; i++) {
            double frac = i / 2.0;
            double y = PAD_TOP + plotH - frac * plotH;
            double v = min + frac * (max - min);
            g.setStroke(AXIS);
            g.strokeLine(PAD_LEFT - 3, y, PAD_LEFT, y);
            g.setFill(TEXT);
            g.fillText(String.format("%.0f", v), 6, y + 4);
        }

        long first = samples.getFirst().epochSecond();
        long last = samples.getLast().epochSecond();
        long span = Math.max(1, last - first);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(span > 86400 ? "MM-dd" : "HH:mm")
                .withZone(ZoneId.systemDefault());
        for (int i = 0; i <= 2; i++) {
            double frac = i / 2.0;
            double x = PAD_LEFT + frac * plotW;
            g.setFill(TEXT);
            String label = fmt.format(Instant.ofEpochSecond(first + (long) (frac * span)));
            g.fillText(label, Math.min(x, PAD_LEFT + plotW - 30), h - 6);
        }

        g.setStroke(LINE);
        g.setLineWidth(1.5);
        boolean pen = false;
        double prevX = 0;
        double prevY = 0;
        for (HistorySample s : samples) {
            double v = value.applyAsDouble(s);
            if (Double.isNaN(v)) {
                pen = false;
                continue;
            }
            double x = PAD_LEFT + (s.epochSecond() - first) / (double) span * plotW;
            double y = PAD_TOP + plotH - (v - min) / (max - min) * plotH;
            if (pen) {
                g.strokeLine(prevX, prevY, x, y);
            }
            prevX = x;
            prevY = y;
            pen = true;
        }
    }
}

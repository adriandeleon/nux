package com.nux.ui;

import static com.nux.i18n.Messages.tr;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.ToDoubleFunction;

import javafx.geometry.VPos;
import javafx.scene.AccessibleRole;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import com.nux.analysis.EnergyEstimate;
import com.nux.protocol.UpsStatus;
import com.nux.store.HistorySample;

/**
 * A resizable line chart over time: y-axis labels, time ticks, gaps where the series has
 * no value <em>or the app wasn't running</em>, shaded on-battery stretches, and a
 * readout of the nearest sample under the pointer. Canvas-based so it stays cheap with
 * thousands of points.
 */
public final class TimeChart extends Pane {

    private static final Color LINE = Color.web("#58a6ff");
    private static final Color AXIS = Color.web("#6e7781", 0.5);
    private static final Color TEXT = Color.web("#8b949e");
    private static final Color ON_BATTERY = Color.web("#d4a72c", 0.16);
    private static final Color READOUT_BG = Color.web("#161b22", 0.92);
    private static final Color READOUT_FG = Color.web("#c9d1d9");
    private static final double PAD_LEFT = 46;
    private static final double PAD_BOTTOM = 22;
    private static final double PAD_TOP = 22; // room for the title above the top gridline
    private static final double PAD_RIGHT = 12;
    private static final int TIME_TICKS = 4;

    private final Canvas canvas = new Canvas();
    private final String title;
    private final String unit;
    private final Double fixedMin;
    private final Double fixedMax;

    private List<HistorySample> samples = List.of();
    private ToDoubleFunction<HistorySample> value = s -> Double.NaN;
    private double hoverX = Double.NaN;

    public TimeChart(String title, String unit, Double fixedMin, Double fixedMax) {
        this.title = title;
        this.unit = unit;
        this.fixedMin = fixedMin;
        this.fixedMax = fixedMax;
        getChildren().add(canvas);
        setMinHeight(120);
        setPrefHeight(160);
        setAccessibleRole(AccessibleRole.TEXT);
        setAccessibleText(title);
        widthProperty().addListener((o, a, b) -> redraw());
        heightProperty().addListener((o, a, b) -> redraw());
        setOnMouseMoved(e -> {
            hoverX = e.getX();
            redraw();
        });
        setOnMouseExited(e -> {
            hoverX = Double.NaN;
            redraw();
        });
    }

    public void setData(List<HistorySample> samples, ToDoubleFunction<HistorySample> value) {
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
        if (w < 60 || h < 60) {
            return;
        }
        g.setFont(Font.font(11));
        g.setTextBaseline(VPos.BASELINE);

        double plotW = w - PAD_LEFT - PAD_RIGHT;
        double plotH = h - PAD_TOP - PAD_BOTTOM;
        String label = title + (unit.isEmpty() ? "" : " (" + unit + ")");
        g.setFill(TEXT);
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText(label, PAD_LEFT, 13);

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
            drawAxes(g, plotW, plotH);
            g.setFill(TEXT);
            g.fillText(tr("history.noData"), PAD_LEFT + 8, PAD_TOP + plotH / 2);
            setAccessibleText(label + ": " + tr("history.noData"));
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

        long first = samples.getFirst().epochSecond();
        long last = samples.getLast().epochSecond();
        long span = Math.max(1, last - first);

        // On-battery stretches first, so the axes and the line draw over them.
        g.setFill(ON_BATTERY);
        for (int i = 0; i < samples.size(); i++) {
            HistorySample s = samples.get(i);
            if (!UpsStatus.parse(s.status()).onBattery()) {
                continue;
            }
            long end = i + 1 < samples.size() ? samples.get(i + 1).epochSecond() : s.epochSecond();
            if (end - s.epochSecond() > EnergyEstimate.MAX_GAP_SECONDS) {
                end = s.epochSecond(); // don't shade a gap where nothing was recorded
            }
            double x0 = xOf(s.epochSecond(), first, span, plotW);
            double x1 = Math.max(x0 + 2, xOf(end, first, span, plotW));
            g.fillRect(x0, PAD_TOP, x1 - x0, plotH);
        }

        drawAxes(g, plotW, plotH);
        g.setTextAlign(TextAlignment.RIGHT);
        for (int i = 0; i <= 2; i++) {
            double frac = i / 2.0;
            double y = PAD_TOP + plotH - frac * plotH;
            double v = min + frac * (max - min);
            g.setStroke(AXIS);
            g.strokeLine(PAD_LEFT - 3, y, PAD_LEFT, y);
            g.setFill(TEXT);
            g.fillText(String.format("%.0f", v), PAD_LEFT - 6, y + 4);
        }

        DateTimeFormatter fmt = timeFormat(span);
        for (int i = 0; i <= TIME_TICKS; i++) {
            double frac = i / (double) TIME_TICKS;
            double x = PAD_LEFT + frac * plotW;
            g.setTextAlign(i == 0 ? TextAlignment.LEFT : i == TIME_TICKS ? TextAlignment.RIGHT : TextAlignment.CENTER);
            g.setFill(TEXT);
            g.fillText(fmt.format(Instant.ofEpochSecond(first + (long) (frac * span))), x, h - 6);
        }

        g.setStroke(LINE);
        g.setLineWidth(1.5);
        boolean pen = false;
        double prevX = 0;
        double prevY = 0;
        long prevEpoch = Long.MIN_VALUE;
        for (HistorySample s : samples) {
            double v = value.applyAsDouble(s);
            // A long silence means Nux wasn't running: break the line rather than draw a
            // confident straight segment through hours nobody measured.
            if (Double.isNaN(v) || s.epochSecond() - prevEpoch > EnergyEstimate.MAX_GAP_SECONDS) {
                pen = false;
            }
            prevEpoch = s.epochSecond();
            if (Double.isNaN(v)) {
                continue;
            }
            double x = xOf(s.epochSecond(), first, span, plotW);
            double y = PAD_TOP + plotH - (v - min) / (max - min) * plotH;
            if (pen) {
                g.strokeLine(prevX, prevY, x, y);
            } else {
                // A segment's first point gets a dot, so a lone sample between gaps shows.
                g.setFill(LINE);
                g.fillOval(x - 1.5, y - 1.5, 3, 3);
            }
            prevX = x;
            prevY = y;
            pen = true;
        }

        HistorySample latest = samples.getLast();
        setAccessibleText(label + ": " + format(value.applyAsDouble(latest)));
        drawReadout(g, first, span, plotW, plotH, min, max);
    }

    /** The nearest sample under the pointer: a guide line, a dot and "time · value". */
    private void drawReadout(GraphicsContext g, long first, long span, double plotW, double plotH, double min, double max) {
        if (Double.isNaN(hoverX) || hoverX < PAD_LEFT || hoverX > PAD_LEFT + plotW) {
            return;
        }
        long t = first + Math.round((hoverX - PAD_LEFT) / plotW * span);
        HistorySample nearest = nearest(t);
        double v = value.applyAsDouble(nearest);
        double x = xOf(nearest.epochSecond(), first, span, plotW);
        g.setStroke(AXIS);
        g.setLineWidth(1);
        g.strokeLine(x, PAD_TOP, x, PAD_TOP + plotH);
        if (!Double.isNaN(v)) {
            double y = PAD_TOP + plotH - (v - min) / (max - min) * plotH;
            g.setFill(LINE);
            g.fillOval(x - 3, y - 3, 6, 6);
        }
        String text = timeFormat(0).format(Instant.ofEpochSecond(nearest.epochSecond()))
                + " · " + format(v) + (unit.isEmpty() || Double.isNaN(v) ? "" : " " + unit);
        double boxW = text.length() * 6.4 + 12;
        double boxX = Math.min(Math.max(PAD_LEFT, x - boxW / 2), PAD_LEFT + plotW - boxW);
        g.setFill(READOUT_BG);
        g.fillRoundRect(boxX, PAD_TOP + 2, boxW, 18, 6, 6);
        g.setFill(READOUT_FG);
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(text, boxX + boxW / 2, PAD_TOP + 15);
    }

    private HistorySample nearest(long epoch) {
        int lo = 0;
        int hi = samples.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (samples.get(mid).epochSecond() < epoch) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if (lo > 0 && epoch - samples.get(lo - 1).epochSecond() < samples.get(lo).epochSecond() - epoch) {
            lo--;
        }
        return samples.get(lo);
    }

    private void drawAxes(GraphicsContext g, double plotW, double plotH) {
        g.setStroke(AXIS);
        g.setLineWidth(1);
        g.strokeLine(PAD_LEFT, PAD_TOP, PAD_LEFT, PAD_TOP + plotH);
        g.strokeLine(PAD_LEFT, PAD_TOP + plotH, PAD_LEFT + plotW, PAD_TOP + plotH);
    }

    private static double xOf(long epoch, long first, long span, double plotW) {
        return PAD_LEFT + (epoch - first) / (double) span * plotW;
    }

    /** Dates once the range covers more than a day; a span of 0 means "a single instant". */
    private static DateTimeFormatter timeFormat(long span) {
        String pattern = span == 0 ? "MM-dd HH:mm" : span > 2 * 86400 ? "MM-dd" : span > 86400 ? "MM-dd HH:mm" : "HH:mm";
        return DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault());
    }

    private static String format(double v) {
        return Double.isNaN(v) ? "—" : v == Math.rint(v) ? String.format("%.0f", v) : String.format("%.1f", v);
    }
}

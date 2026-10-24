package com.nux.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

/**
 * Thins a long series for drawing: per time bucket (about one per pixel column) it keeps
 * the lowest and highest point, in time order. A 30-day chart of one sample a minute is
 * ~43,000 points across ~700 pixels; this draws ~1,400 and still shows every spike and
 * dip, which plain every-Nth-point sampling would skip. Pure.
 */
public final class Decimate {

    private Decimate() {}

    public static <T> List<T> minMax(
            List<T> items, ToLongFunction<T> time, ToDoubleFunction<T> value, int buckets) {
        if (buckets <= 0 || items.size() <= 2L * buckets) {
            return items;
        }
        long first = time.applyAsLong(items.getFirst());
        long span = Math.max(1, time.applyAsLong(items.getLast()) - first);
        List<T> out = new ArrayList<>(2 * buckets + 2);
        int bucket = -1;
        T low = null;
        T high = null;
        T any = null; // keeps an all-NaN bucket visible, so a no-data gap stays a gap
        for (T item : items) {
            int b = (int) Math.min(buckets - 1, (time.applyAsLong(item) - first) * buckets / span);
            if (b != bucket) {
                flush(out, low, high, any, time);
                bucket = b;
                low = high = any = null;
            }
            double v = value.applyAsDouble(item);
            if (any == null) {
                any = item;
            }
            if (!Double.isNaN(v)) {
                if (low == null || v < value.applyAsDouble(low)) {
                    low = item;
                }
                if (high == null || v > value.applyAsDouble(high)) {
                    high = item;
                }
            }
        }
        flush(out, low, high, any, time);
        return out;
    }

    private static <T> void flush(List<T> out, T low, T high, T any, ToLongFunction<T> time) {
        if (low == null) {
            if (any != null) {
                out.add(any);
            }
            return;
        }
        if (low == high) {
            out.add(low);
        } else if (time.applyAsLong(low) <= time.applyAsLong(high)) {
            out.add(low);
            out.add(high);
        } else {
            out.add(high);
            out.add(low);
        }
    }
}

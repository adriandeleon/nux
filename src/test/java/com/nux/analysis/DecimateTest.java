package com.nux.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class DecimateTest {

    private record P(long t, double v) {}

    private static List<P> series(int n, java.util.function.IntToDoubleFunction v) {
        List<P> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new P(i * 60L, v.applyAsDouble(i)));
        }
        return out;
    }

    @Test
    void aShortSeriesIsLeftAlone() {
        List<P> small = series(100, i -> i);
        assertSame(small, Decimate.minMax(small, P::t, P::v, 700));
    }

    @Test
    void aLongSeriesShrinksToAboutTwoPointsAPixelAndKeepsItsSpikes() {
        // 30 days at one a minute, flat except one spike and one dip.
        List<P> month = series(43_200, i -> i == 20_000 ? 95 : i == 30_000 ? 3 : 20);
        List<P> drawn = Decimate.minMax(month, P::t, P::v, 700);
        assertTrue(drawn.size() <= 1400, "drew " + drawn.size());
        assertTrue(drawn.stream().anyMatch(p -> p.v() == 95), "the spike survives");
        assertTrue(drawn.stream().anyMatch(p -> p.v() == 3), "the dip survives");
        for (int i = 1; i < drawn.size(); i++) {
            assertTrue(drawn.get(i).t() >= drawn.get(i - 1).t(), "still in time order");
        }
        assertEquals(month.getFirst(), drawn.getFirst());
    }

    @Test
    void aStretchWithNoReadingsStaysAGap() {
        List<P> withHole = series(10_000, i -> i >= 4000 && i < 6000 ? Double.NaN : 50);
        List<P> drawn = Decimate.minMax(withHole, P::t, P::v, 200);
        assertTrue(drawn.stream().anyMatch(p -> Double.isNaN(p.v())), "the NaN run still breaks the line");
    }
}

package com.nux.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HistoryTest {

    @Test
    void keepsInsertionOrderBelowCapacity() {
        History h = new History(4);
        h.add(1);
        h.add(2);
        h.add(3);
        assertArrayEquals(new double[] {1, 2, 3}, h.values());
    }

    @Test
    void wrapsDroppingOldest() {
        History h = new History(3);
        h.add(1);
        h.add(2);
        h.add(3);
        h.add(4);
        h.add(5);
        assertArrayEquals(new double[] {3, 4, 5}, h.values());
    }

    @Test
    void nanSamplesAreKeptAsGaps() {
        History h = new History(3);
        h.add(1);
        h.add(Double.NaN);
        h.add(2);
        double[] v = h.values();
        assertEquals(1, v[0]);
        assertTrue(Double.isNaN(v[1]));
        assertEquals(2, v[2]);
    }

    @Test
    void clearEmptiesAndAllowsReuse() {
        History h = new History(2);
        h.add(1);
        h.add(2);
        h.add(3); // wrapped
        h.clear();
        assertTrue(h.isEmpty());
        h.add(9);
        assertArrayEquals(new double[] {9}, h.values());
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new History(0));
    }
}

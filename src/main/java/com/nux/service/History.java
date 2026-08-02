package com.nux.service;

/**
 * A fixed-capacity ring buffer of samples for sparklines. NaN is a legal sample
 * (a gap — e.g. the UPS didn't report the variable that poll) and renders as a break
 * in the line. Not thread-safe; FX-thread confined.
 */
public final class History {

    private final double[] data;
    private int size;
    private int head; // index of the oldest sample once the ring has wrapped

    public History(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.data = new double[capacity];
    }

    public void add(double value) {
        if (size < data.length) {
            data[(head + size) % data.length] = value;
            size++;
        } else {
            data[head] = value;
            head = (head + 1) % data.length;
        }
    }

    /** Samples oldest → newest; length is the current fill, not the capacity. */
    public double[] values() {
        double[] out = new double[size];
        for (int i = 0; i < size; i++) {
            out[i] = data[(head + i) % data.length];
        }
        return out;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        size = 0;
        head = 0;
    }
}

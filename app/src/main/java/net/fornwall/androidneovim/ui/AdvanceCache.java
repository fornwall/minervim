package net.fornwall.androidneovim.ui;

import java.util.Arrays;

/**
 * Advances of drawn clusters for one text style: a bounded, allocation-free, two-way set-associative table
 * whose sets keep the most recently used entry first, so a screen of distinct CJK characters still fits.
 */
final class AdvanceCache {
    private static final int SET_BITS = 10;
    private static final int CAPACITY = 2 << SET_BITS;
    private final String[] keys = new String[CAPACITY];
    private final float[] values = new float[CAPACITY];

    /** The cached advance of text, or NaN. */
    float get(String text) {
        int slot = slot(text);
        if (text.equals(keys[slot])) return values[slot];
        if (!text.equals(keys[slot + 1])) return Float.NaN;
        float advance = values[slot + 1];
        put(text, advance);
        return advance;
    }

    /** Caches the advance of text, which get() did not find, evicting the less recently used entry of its set. */
    void put(String text, float advance) {
        int slot = slot(text);
        keys[slot + 1] = keys[slot];
        values[slot + 1] = values[slot];
        keys[slot] = text;
        values[slot] = advance;
    }

    void clear() { Arrays.fill(keys, null); }

    static int set(String text) { return (text.hashCode() * 0x9e3779b9) >>> (32 - SET_BITS); }
    private static int slot(String text) { return set(text) << 1; }
}

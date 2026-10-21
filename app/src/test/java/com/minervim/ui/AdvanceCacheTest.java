package com.minervim.ui;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class AdvanceCacheTest {
    @Test public void consecutiveCjkCharactersStayCached() {
        AdvanceCache cache = new AdvanceCache();
        for (int pass = 0; pass < 2; pass++) {
            int misses = 0;
            for (char c = '一'; c < '一' + 500; c++) {
                String text = String.valueOf(c);
                if (Float.isNaN(cache.get(text))) { misses++; cache.put(text, c); }
                else assertEquals(c, cache.get(text), 0);
            }
            assertEquals(pass == 0 ? 500 : 0, misses);
        }
        cache.clear();
        assertTrue(Float.isNaN(cache.get("一")));
    }

    @Test public void setsKeepTheTwoMostRecentlyUsedEntries() {
        List<String> same = new ArrayList<>();
        for (char c = '㐀'; same.size() < 3; c++) {
            if (AdvanceCache.set(String.valueOf(c)) == AdvanceCache.set("a")) same.add(String.valueOf(c));
        }
        AdvanceCache cache = new AdvanceCache();
        cache.put("a", 1);
        cache.put(same.get(0), 2);
        assertEquals(1, cache.get("a"), 0);
        cache.put(same.get(1), 3);
        assertEquals(1, cache.get("a"), 0);
        assertTrue(Float.isNaN(cache.get(same.get(0))));
        assertEquals(3, cache.get(same.get(1)), 0);
        cache.put(same.get(2), 4);
        assertTrue(Float.isNaN(cache.get("a")));
        assertEquals(4, cache.get(same.get(2)), 0);
        assertEquals(3, cache.get(same.get(1)), 0);
    }
}

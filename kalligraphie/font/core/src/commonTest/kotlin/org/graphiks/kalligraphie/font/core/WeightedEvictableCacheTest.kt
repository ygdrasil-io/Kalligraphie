package org.graphiks.kalligraphie.font.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WeightedEvictableCacheTest {
    @Test
    fun evictsTheLeastRecentlyUsedResultWhenTheByteBudgetIsExceeded() {
        val cache = WeightedEvictableCache<String, String>(maximumRetainedBytes = 2)

        cache.put(key = "a", value = "first", retainedBytes = 1)
        cache.put(key = "b", value = "second", retainedBytes = 1)
        assertEquals("first", cache.get("a"))

        cache.put(key = "c", value = "third", retainedBytes = 1)

        assertNull(cache.get("b"))
        assertEquals("first", cache.get("a"))
        assertEquals("third", cache.get("c"))
    }

    @Test
    fun doesNotRetainAResultThatExceedsTheByteBudget() {
        val cache = WeightedEvictableCache<String, String>(maximumRetainedBytes = 2)

        cache.put(key = "large", value = "payload", retainedBytes = 3)

        assertNull(cache.get("large"))
    }

    @Test
    fun evictsBeforeLongWeightOverflowCanBypassTheMaximumBudget() {
        val weight = Long.MAX_VALUE / 2L + 1L
        val cache = WeightedEvictableCache<String, String>(maximumRetainedBytes = Long.MAX_VALUE)

        cache.put(key = "a", value = "first", retainedBytes = weight)
        cache.put(key = "b", value = "second", retainedBytes = weight)

        assertNull(cache.get("a"))
        assertEquals("second", cache.get("b"))
    }
}

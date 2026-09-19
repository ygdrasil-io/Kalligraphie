package org.graphiks.kalligraphie.conformance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CanonicalUnitTest {
    @Test
    fun aDesignUnitRouteIsAlreadyCanonical() {
        val scale = CanonicalScale(1.0)
        assertEquals(42.0, scale.toCanonical(42.0))
        assertEquals(-3.0, scale.toCanonical(-3.0))
    }

    @Test
    fun aSixtyFourthPixelRouteConvertsToDesignUnits() {
        val scale = CanonicalScale(64.0)
        assertEquals(1.0, scale.toCanonical(64.0))
        assertEquals(2.5, scale.toCanonical(160.0))
    }

    @Test
    fun rejectsNonPositiveAndNonFiniteScales() {
        assertFailsWith<IllegalArgumentException> { CanonicalScale(0.0) }
        assertFailsWith<IllegalArgumentException> { CanonicalScale(-1.0) }
        assertFailsWith<IllegalArgumentException> { CanonicalScale(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { CanonicalScale(Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { CanonicalScale(Double.NEGATIVE_INFINITY) }
    }

    @Test
    fun rejectsNonFiniteRouteValues() {
        val scale = CanonicalScale(1.0)
        assertFailsWith<IllegalArgumentException> { scale.toCanonical(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { scale.toCanonical(Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { scale.toCanonical(Double.NEGATIVE_INFINITY) }
    }

    @Test
    fun rejectsConversionThatOverflowsToNonFinite() {
        val scale = CanonicalScale(Double.MIN_VALUE)
        assertFailsWith<IllegalArgumentException> { scale.toCanonical(Double.MAX_VALUE) }
    }
}

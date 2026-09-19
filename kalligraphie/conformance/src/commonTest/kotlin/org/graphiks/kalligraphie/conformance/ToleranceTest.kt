package org.graphiks.kalligraphie.conformance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToleranceTest {
    @Test
    fun declaresACrossPlatformToleranceInTheCanonicalUnit() {
        val tolerance = Tolerance.declare(
            quantity = ComparisonQuantity.ADVANCE,
            scope = ToleranceScope.CROSS_PLATFORM,
            unit = ToleranceUnit.Canonical(CanonicalUnit.FONT_DESIGN_UNIT),
            maxDeviation = 0.5,
            justification = "Half a design unit is below one ink unit at the reference size.",
        )
        assertEquals(ComparisonQuantity.ADVANCE, tolerance.quantity)
        assertEquals(0.5, tolerance.maxDeviation)
    }

    @Test
    fun permitsARouteNativeUnitForIntraPlatformComparison() {
        val tolerance = Tolerance.declare(
            quantity = ComparisonQuantity.CARET_FRACTIONAL_POSITION,
            scope = ToleranceScope.INTRA_PLATFORM,
            unit = ToleranceUnit.RouteNative("sixty-fourth-pixel"),
            maxDeviation = 1.0,
            justification = "One sixty-fourth of a pixel on the same route.",
        )
        assertEquals(ToleranceScope.INTRA_PLATFORM, tolerance.scope)
    }

    @Test
    fun rejectsARouteNativeUnitForCrossPlatformComparison() {
        assertFailsWith<IllegalArgumentException> {
            Tolerance.declare(
                quantity = ComparisonQuantity.ADVANCE,
                scope = ToleranceScope.CROSS_PLATFORM,
                unit = ToleranceUnit.RouteNative("sixty-fourth-pixel"),
                maxDeviation = 1.0,
                justification = "not comparable across routes",
            )
        }
    }

    @Test
    fun rejectsNonNumericZeroAndUnjustifiedTolerances() {
        assertFailsWith<IllegalArgumentException> {
            Tolerance.declare(
                ComparisonQuantity.GLYPH_INDEX,
                ToleranceScope.CROSS_PLATFORM,
                ToleranceUnit.Canonical(CanonicalUnit.FONT_DESIGN_UNIT),
                1.0,
                "not applicable",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            Tolerance.declare(
                ComparisonQuantity.ADVANCE,
                ToleranceScope.CROSS_PLATFORM,
                ToleranceUnit.Canonical(CanonicalUnit.FONT_DESIGN_UNIT),
                0.0,
                "zero is not a tolerance",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            Tolerance.declare(
                ComparisonQuantity.ADVANCE,
                ToleranceScope.CROSS_PLATFORM,
                ToleranceUnit.Canonical(CanonicalUnit.FONT_DESIGN_UNIT),
                0.5,
                "   ",
            )
        }
    }
}

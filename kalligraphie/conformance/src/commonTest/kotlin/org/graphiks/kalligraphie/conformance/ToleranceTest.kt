package org.graphiks.kalligraphie.conformance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

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
    fun rejectsANonNumericQuantity() {
        assertFailsWith<IllegalArgumentException> {
            Tolerance.declare(
                ComparisonQuantity.GLYPH_INDEX,
                ToleranceScope.CROSS_PLATFORM,
                ToleranceUnit.Canonical(CanonicalUnit.FONT_DESIGN_UNIT),
                1.0,
                "not applicable",
            )
        }
    }

    @Test
    fun rejectsAZeroDeviation() {
        assertFailsWith<IllegalArgumentException> {
            Tolerance.declare(
                ComparisonQuantity.ADVANCE,
                ToleranceScope.CROSS_PLATFORM,
                ToleranceUnit.Canonical(CanonicalUnit.FONT_DESIGN_UNIT),
                0.0,
                "zero is not a tolerance",
            )
        }
    }

    @Test
    fun rejectsANegativeOrNonFiniteDeviation() {
        val invalidDeviations = listOf(
            -0.5,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
        )
        for (deviation in invalidDeviations) {
            assertFailsWith<IllegalArgumentException> {
                Tolerance.declare(
                    ComparisonQuantity.ADVANCE,
                    ToleranceScope.CROSS_PLATFORM,
                    ToleranceUnit.Canonical(CanonicalUnit.FONT_DESIGN_UNIT),
                    deviation,
                    "deviation must be finite and strictly positive",
                )
            }
        }
    }

    @Test
    fun rejectsABlankJustification() {
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

    @Test
    fun treatsContentIdenticalTolerancesAsEqual() {
        val first = Tolerance.declare(
            ComparisonQuantity.ADVANCE,
            ToleranceScope.CROSS_PLATFORM,
            ToleranceUnit.Canonical(CanonicalUnit.FONT_DESIGN_UNIT),
            0.5,
            "Half a design unit.",
        )
        val second = Tolerance.declare(
            ComparisonQuantity.ADVANCE,
            ToleranceScope.CROSS_PLATFORM,
            ToleranceUnit.Canonical(CanonicalUnit.FONT_DESIGN_UNIT),
            0.5,
            "Half a design unit.",
        )
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(first.toString(), second.toString())

        val different = Tolerance.declare(
            ComparisonQuantity.ADVANCE,
            ToleranceScope.CROSS_PLATFORM,
            ToleranceUnit.Canonical(CanonicalUnit.FONT_DESIGN_UNIT),
            0.25,
            "Quarter of a design unit.",
        )
        assertNotEquals(first, different)
    }

    @Test
    fun rejectsABlankRouteNativeUnitId() {
        assertFailsWith<IllegalArgumentException> {
            ToleranceUnit.RouteNative("   ")
        }
    }
}

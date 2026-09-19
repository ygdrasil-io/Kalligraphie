package org.graphiks.kalligraphie.conformance

import kotlin.test.Test
import kotlin.test.assertEquals

class ComparisonClassTest {
    @Test
    fun bindsBoundaryQuantitiesToBitIdentical() {
        assertEquals(ComparisonClass.BIT_IDENTICAL, ComparisonQuantity.CODEPOINT_BOUNDARY.declaredClass())
        assertEquals(ComparisonClass.BIT_IDENTICAL, ComparisonQuantity.GLYPH_INDEX.declaredClass())
        assertEquals(ComparisonClass.BIT_IDENTICAL, ComparisonQuantity.DIAGNOSTIC_ORDER.declaredClass())
    }

    @Test
    fun bindsDecisionQuantitiesToStructurallyIdentical() {
        assertEquals(ComparisonClass.STRUCTURALLY_IDENTICAL, ComparisonQuantity.LINE_BREAK_DECISION.declaredClass())
        assertEquals(ComparisonClass.STRUCTURALLY_IDENTICAL, ComparisonQuantity.FALLBACK_CANDIDATE_ORDER.declaredClass())
    }

    @Test
    fun bindsGeometryQuantitiesToNumericTolerance() {
        assertEquals(ComparisonClass.NUMERIC_TOLERANCE, ComparisonQuantity.ADVANCE.declaredClass())
        assertEquals(ComparisonClass.NUMERIC_TOLERANCE, ComparisonQuantity.CARET_FRACTIONAL_POSITION.declaredClass())
    }

    @Test
    fun degradesBitIdenticalToStructuralAcrossImplementationProfiles() {
        val sameProfile = ComparisonScope(sameImplementationProfile = true)
        val crossProfile = ComparisonScope(sameImplementationProfile = false)
        assertEquals(ComparisonClass.BIT_IDENTICAL, ComparisonQuantity.GLYPH_INDEX.effectiveClass(sameProfile))
        assertEquals(ComparisonClass.STRUCTURALLY_IDENTICAL, ComparisonQuantity.GLYPH_INDEX.effectiveClass(crossProfile))
        assertEquals(ComparisonClass.STRUCTURALLY_IDENTICAL, ComparisonQuantity.BIDI_RESOLUTION.effectiveClass(crossProfile))
        assertEquals(ComparisonClass.NUMERIC_TOLERANCE, ComparisonQuantity.ADVANCE.effectiveClass(crossProfile))
    }

    @Test
    fun declaresTheComparisonClassOfEveryQuantity() {
        val expected = mapOf(
            ComparisonQuantity.CODEPOINT_BOUNDARY to ComparisonClass.BIT_IDENTICAL,
            ComparisonQuantity.GRAPHEME_BOUNDARY to ComparisonClass.BIT_IDENTICAL,
            ComparisonQuantity.CLUSTER_INDEX to ComparisonClass.BIT_IDENTICAL,
            ComparisonQuantity.GLYPH_INDEX to ComparisonClass.BIT_IDENTICAL,
            ComparisonQuantity.CARET_INTEGER_POSITION to ComparisonClass.BIT_IDENTICAL,
            ComparisonQuantity.LINE_INDEX to ComparisonClass.BIT_IDENTICAL,
            ComparisonQuantity.FRAGMENT_INDEX to ComparisonClass.BIT_IDENTICAL,
            ComparisonQuantity.DIAGNOSTIC_IDENTITY to ComparisonClass.BIT_IDENTICAL,
            ComparisonQuantity.DIAGNOSTIC_ORDER to ComparisonClass.BIT_IDENTICAL,
            ComparisonQuantity.LINE_BREAK_DECISION to ComparisonClass.STRUCTURALLY_IDENTICAL,
            ComparisonQuantity.FALLBACK_CANDIDATE_ORDER to ComparisonClass.STRUCTURALLY_IDENTICAL,
            ComparisonQuantity.BIDI_RESOLUTION to ComparisonClass.STRUCTURALLY_IDENTICAL,
            ComparisonQuantity.ADVANCE to ComparisonClass.NUMERIC_TOLERANCE,
            ComparisonQuantity.ORIGIN to ComparisonClass.NUMERIC_TOLERANCE,
            ComparisonQuantity.BOX to ComparisonClass.NUMERIC_TOLERANCE,
            ComparisonQuantity.CARET_FRACTIONAL_POSITION to ComparisonClass.NUMERIC_TOLERANCE,
        )
        assertEquals(ComparisonQuantity.entries.toSet(), expected.keys)
        ComparisonQuantity.entries.forEach { quantity ->
            assertEquals(expected.getValue(quantity), quantity.declaredClass(), quantity.name)
        }
    }
}

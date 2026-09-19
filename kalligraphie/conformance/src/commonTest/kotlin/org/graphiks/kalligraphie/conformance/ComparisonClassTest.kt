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
}

package org.graphiks.kalligraphie.conformance

import kotlin.jvm.JvmInline

/** Comparison rule that governs how one portable outcome is compared across executions. */
public enum class ComparisonClass {
    /** Exact equality of integers or bytes; any difference fails within a same implementation profile. */
    BIT_IDENTICAL,

    /** Same decision or ordering, not necessarily byte-equal. */
    STRUCTURALLY_IDENTICAL,

    /** Floating geometry compared under a declared tolerance in the canonical unit. */
    NUMERIC_TOLERANCE,
}

/** Observable portable quantity compared by the conformance contract. */
public enum class ComparisonQuantity {
    /** Code point index reported at a text boundary. */
    CODEPOINT_BOUNDARY,

    /** Grapheme cluster index reported at a text boundary. */
    GRAPHEME_BOUNDARY,

    /** Index of a shaped cluster within a run. */
    CLUSTER_INDEX,

    /** Index of a glyph within a shaped run. */
    GLYPH_INDEX,

    /** Integer caret position within a text run. */
    CARET_INTEGER_POSITION,

    /** Index of a line within a laid-out paragraph. */
    LINE_INDEX,

    /** Index of a fragment within a line. */
    FRAGMENT_INDEX,

    /** Identity of a reported diagnostic. */
    DIAGNOSTIC_IDENTITY,

    /** Emitted order of typed diagnostics, which is deterministic and therefore bit-identical within a profile. */
    DIAGNOSTIC_ORDER,

    /** Line break decision made for a break candidate. */
    LINE_BREAK_DECISION,

    /**
     * Resolved ordering of fallback candidates, whose tie-breaking is
     * implementation-defined and therefore only structurally identical.
     */
    FALLBACK_CANDIDATE_ORDER,

    /** Resolved bidirectional level or direction. */
    BIDI_RESOLUTION,

    /** Glyph advance geometry. */
    ADVANCE,

    /** Placement origin geometry. */
    ORIGIN,

    /** Bounding box geometry. */
    BOX,

    /** Fractional caret position geometry. */
    CARET_FRACTIONAL_POSITION,
}

/** Execution context of a comparison. */
@JvmInline
public value class ComparisonScope(
    /** Whether both compared executions are backed by the same portable implementation profile. */
    public val sameImplementationProfile: Boolean,
)

/** Comparison class intrinsically declared by [this] quantity, before profile conditioning. */
public fun ComparisonQuantity.declaredClass(): ComparisonClass = when (this) {
    ComparisonQuantity.CODEPOINT_BOUNDARY,
    ComparisonQuantity.GRAPHEME_BOUNDARY,
    ComparisonQuantity.CLUSTER_INDEX,
    ComparisonQuantity.GLYPH_INDEX,
    ComparisonQuantity.CARET_INTEGER_POSITION,
    ComparisonQuantity.LINE_INDEX,
    ComparisonQuantity.FRAGMENT_INDEX,
    ComparisonQuantity.DIAGNOSTIC_IDENTITY,
    ComparisonQuantity.DIAGNOSTIC_ORDER,
    -> ComparisonClass.BIT_IDENTICAL

    ComparisonQuantity.LINE_BREAK_DECISION,
    ComparisonQuantity.FALLBACK_CANDIDATE_ORDER,
    ComparisonQuantity.BIDI_RESOLUTION,
    -> ComparisonClass.STRUCTURALLY_IDENTICAL

    ComparisonQuantity.ADVANCE,
    ComparisonQuantity.ORIGIN,
    ComparisonQuantity.BOX,
    ComparisonQuantity.CARET_FRACTIONAL_POSITION,
    -> ComparisonClass.NUMERIC_TOLERANCE
}

/**
 * Comparison class effectively applied to [this] quantity in [scope].
 *
 * A bit-identical quantity compared across different implementation profiles is only
 * structurally identical, because the underlying implementation is not the same.
 */
public fun ComparisonQuantity.effectiveClass(scope: ComparisonScope): ComparisonClass {
    val declared = declaredClass()
    return if (!scope.sameImplementationProfile && declared == ComparisonClass.BIT_IDENTICAL) {
        ComparisonClass.STRUCTURALLY_IDENTICAL
    } else {
        declared
    }
}

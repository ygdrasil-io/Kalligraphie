package org.graphiks.kalligraphie.conformance

import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.sortedDiagnostics

/** Outcome of comparing two diagnostic sequences under [ComparisonClass.BIT_IDENTICAL]. */
public sealed interface DiagnosticComparison {
    /** Both emitted sequences are byte-for-byte identical. */
    public data object Match : DiagnosticComparison

    /**
     * Both sequences carry the same diagnostics but in a different emitted order.
     *
     * Diagnostic ordering is bit-identical, so a non-canonical producer is a divergence even
     * when the diagnostic set is the same.
     */
    public data class OrderDivergence(
        public val canonical: List<FontDiagnostic>,
    ) : DiagnosticComparison

    /** The diagnostic sets differ; both sides are reported in canonical order. */
    public data class Mismatch(
        public val expected: List<FontDiagnostic>,
        public val actual: List<FontDiagnostic>,
    ) : DiagnosticComparison
}

/** Compares [expected] and [actual] under the bit-identical diagnostic contract. */
public fun compareDiagnostics(
    expected: List<FontDiagnostic>,
    actual: List<FontDiagnostic>,
): DiagnosticComparison {
    if (expected == actual) return DiagnosticComparison.Match
    val canonicalExpected = expected.sortedDiagnostics()
    val canonicalActual = actual.sortedDiagnostics()
    return if (canonicalExpected == canonicalActual) {
        DiagnosticComparison.OrderDivergence(canonicalExpected)
    } else {
        DiagnosticComparison.Mismatch(canonicalExpected, canonicalActual)
    }
}

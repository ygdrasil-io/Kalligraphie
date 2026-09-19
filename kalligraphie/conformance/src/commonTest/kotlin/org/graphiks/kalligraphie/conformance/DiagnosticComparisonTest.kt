package org.graphiks.kalligraphie.conformance

import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontDiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DiagnosticComparisonTest {
    private fun diagnostic(code: String, message: String): FontDiagnostic = FontDiagnostic(
        code = code,
        severity = FontDiagnosticSeverity.ERROR,
        location = FontDiagnosticLocation.Source,
        message = message,
    )

    @Test
    fun matchesIdenticalSequences() {
        val a = diagnostic("a", "first")
        val b = diagnostic("b", "second")
        assertIs<DiagnosticComparison.Match>(compareDiagnostics(listOf(a, b), listOf(a, b)))
    }

    @Test
    fun reportsOrderDivergenceWhenTheSameDiagnosticsAreEmittedInAnotherOrder() {
        val a = diagnostic("a", "first")
        val b = diagnostic("b", "second")
        val result = compareDiagnostics(listOf(a, b), listOf(b, a))
        assertIs<DiagnosticComparison.OrderDivergence>(result)
    }

    @Test
    fun reportsMismatchWhenTheDiagnosticSetsDiffer() {
        val a = diagnostic("a", "first")
        val b = diagnostic("b", "second")
        val result = compareDiagnostics(listOf(a), listOf(a, b))
        val mismatch = assertIs<DiagnosticComparison.Mismatch>(result)
        assertEquals(listOf(a), mismatch.expected)
        assertEquals(listOf(a, b), mismatch.actual)
    }
}

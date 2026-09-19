package org.graphiks.kalligraphie.conformance.corpus

/** Observable portable result of one executed scenario. */
public data class ConformanceObservation(
    /** Scenario that produced this observation. */
    public val scenarioId: String,
    /** Canonical fingerprint of the producing platform capability identity. */
    public val capabilityFingerprint: String,
    /** Decoded Unicode scalar values, in logical order. */
    public val scalars: List<Int>,
    /** Source units consumed by each scalar, in logical order. */
    public val sourceUnitWidths: List<Int>,
    /** Emitted diagnostic codes, in emitted order. */
    public val diagnosticCodes: List<String>,
) {
    /**
     * Observables independent of the producing platform: every field except the capability
     * fingerprint. Two platforms that agree here produce the same portable outcome, and the only
     * admissible difference is the explicitly declared capability identity.
     */
    public fun observableEnvelope(): String = buildString {
        append("scenario=").append(scenarioId).appendLine()
        append("scalars=").append(scalars.joinToString(",")).appendLine()
        append("widths=").append(sourceUnitWidths.joinToString(",")).appendLine()
        append("diagnostics=").append(diagnosticCodes.joinToString(","))
    }
}

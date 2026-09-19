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
)

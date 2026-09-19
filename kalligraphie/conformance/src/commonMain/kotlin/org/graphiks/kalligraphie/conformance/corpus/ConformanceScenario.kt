package org.graphiks.kalligraphie.conformance.corpus

import org.graphiks.kalligraphie.conformance.PortableCapability

/** Kind of observable portable behaviour exercised by a corpus scenario. */
public enum class ConformanceScenarioKind {
    /** UTF-8 or UTF-16 decoding into a canonical text snapshot. */
    DECODING,
}

/** Declarative description of one corpus scenario. */
public data class ConformanceScenario(
    /** Stable scenario identifier. */
    public val id: String,
    /** Human-readable description of the observable behaviour. */
    public val description: String,
    /** Kind of behaviour exercised. */
    public val kind: ConformanceScenarioKind,
    /** Capabilities that must be present for this scenario to produce an observation. */
    public val requiredCapabilities: Set<PortableCapability> = emptySet(),
) {
    init {
        require(id.isNotBlank()) { "A scenario requires a non-blank identifier." }
        require(description.isNotBlank()) { "A scenario requires a non-blank description." }
    }
}

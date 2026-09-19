package org.graphiks.kalligraphie.conformance.corpus

/** Canonical portable corpus executed by the reference oracle on every compiled target. */
public object StandardConformanceCorpus {
    /** Scenarios every reference platform must reproduce deterministically. */
    public val scenarios: List<ConformanceScenario> = listOf(
        ConformanceScenario("ascii", "One ASCII scalar decodes to one source unit.", ConformanceScenarioKind.DECODING),
        ConformanceScenario("multibyte-utf8", "Multi-byte UTF-8 scalars keep exact source widths.", ConformanceScenarioKind.DECODING),
        ConformanceScenario("surrogate-utf16", "A UTF-16 surrogate pair decodes to one scalar.", ConformanceScenarioKind.DECODING),
        ConformanceScenario("malformed-utf8", "A malformed UTF-8 maximal subpart becomes U+FFFD with a diagnostic.", ConformanceScenarioKind.DECODING),
    )
}

package org.graphiks.kalligraphie.conformance.corpus

import org.graphiks.kalligraphie.conformance.PortableCapability

/** One bounded, justified divergence between reference platforms. */
public data class ConformanceDivergence(
    /** Platform that diverges from the reference. */
    public val platformId: String,
    /** Capability whose availability diverges. */
    public val capability: PortableCapability,
    /** Human-readable statement of the bounded divergence. */
    public val description: String,
    /** Justification tied to the contract, never a tolerance chosen to hide a regression. */
    public val justification: String,
) {
    init {
        require(platformId.isNotBlank()) { "A divergence requires a platform identifier." }
        require(description.isNotBlank()) { "A divergence requires a description." }
        require(justification.isNotBlank()) { "A divergence requires a justification." }
    }
}

/**
 * Divergences between reference platforms that are documented and bounded rather than portable.
 *
 * Apple and Android do not yet provide portable Unicode analysis, shaping or end-to-end layout;
 * the contract therefore records these as explicit capability divergences instead of silently
 * different results. Decoding observables remain identical on every reference platform.
 */
public object ReferenceDivergenceRecord {
    /** Every known bounded divergence. */
    public val divergences: List<ConformanceDivergence> = listOf(
        ConformanceDivergence("ios", PortableCapability.UNICODE_ANALYSIS, "Unicode analysis is not portable on Apple yet.", "Portable Unicode analysis is owned by the dedicated analysis workstream."),
        ConformanceDivergence("ios", PortableCapability.SHAPING, "Shaping is not portable on Apple yet.", "Portable shaping is owned by the dedicated HarfBuzz bindings workstream."),
        ConformanceDivergence("ios", PortableCapability.END_TO_END_LAYOUT, "End-to-end layout cannot execute on Apple without portable analysis and shaping.", "End-to-end layout depends on portable analysis and shaping."),
        ConformanceDivergence("android", PortableCapability.UNICODE_ANALYSIS, "Unicode analysis is not portable on Android yet.", "Portable Unicode analysis is owned by the dedicated analysis workstream."),
        ConformanceDivergence("android", PortableCapability.SHAPING, "Shaping is not portable on Android yet.", "Portable shaping is owned by the dedicated HarfBuzz bindings workstream."),
        ConformanceDivergence("android", PortableCapability.END_TO_END_LAYOUT, "End-to-end layout cannot execute on Android without portable analysis and shaping.", "End-to-end layout depends on portable analysis and shaping."),
    )
}

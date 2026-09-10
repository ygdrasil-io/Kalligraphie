package org.graphiks.kalligraphie.api

/**
 * Stable identity of a font provider domain.
 *
 * Tokens issued by different domains are never comparable, even when their textual values are
 * identical. The value is an opaque namespace and has no ordering semantics.
 */
public data class FontProviderId(
    /** Non-empty opaque provider namespace. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "Font provider identifier must not be blank." }
    }

    /** Compatibility namespace for catalogues created by older single-token providers. */
    public companion object {
        /** Shared domain used only by the legacy one-argument generation constructor. */
        public val legacy: FontProviderId = FontProviderId("legacy")
    }
}

/**
 * Opaque identity of an immutable catalogue generation in one provider domain.
 *
 * A generation identifies the exact set of face records and provider state captured by a
 * [FontCatalogSnapshot]. It is equality-comparable but intentionally unordered. Asset keys may
 * be reopened only through a live resolver carrying the same [provider] and [value].
 */
public data class FontCatalogGeneration(
    /** Provider domain that issued this generation token. */
    public val provider: FontProviderId,
    /** Provider-defined, non-empty generation token. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "Font catalog generation must not be blank." }
    }

    /**
     * Creates a generation in the compatibility provider domain.
     *
     * New providers must use the two-argument constructor so independent token spaces cannot
     * collide.
     */
    public constructor(value: String) : this(FontProviderId.legacy, value)
}

/**
 * Capabilities declared by one stable face record.
 *
 * These values are a conservative eligibility prefilter. They never replace shaping or final
 * materialization validation, because an OpenType substitution can produce a glyph whose route
 * differs from the source character mapping.
 */
public data class FontFaceCapabilities(
    /** Whether the face can map Unicode scalar values to glyph identifiers. */
    public val characterMapping: Boolean,
    /** Whether the face supplies data usable by the selected shaping pipeline. */
    public val shaping: Boolean,
    /** Whether the face can produce a portable outline route. */
    public val outline: Boolean,
    /** Whether the face declares a portable paint-graph route. */
    public val paintGraph: Boolean = false,
    /** Whether the face declares a decoded portable bitmap route. */
    public val bitmap: Boolean = false,
    /** Whether the face declares a separately negotiated native-handle route. */
    public val nativeHandle: Boolean = false,
)

/**
 * Immutable record describing a face captured by a [FontCatalogSnapshot].
 *
 * [id] is stable for the face's source and index, while [capabilities] describes only the
 * capabilities the captured provider actually makes available. The record owns no resource and
 * can be retained after resolvers and assets have closed.
 */
public data class FontFaceRecord(
    /** Stable semantic identity of the face. */
    public val id: FontFaceId,
    /** Descriptive metadata captured with the generation. */
    public val metadata: FontFaceMetadata,
    /** Conservative capabilities available through this catalogue generation. */
    public val capabilities: FontFaceCapabilities,
)

/**
 * One policy-ordered font candidate.
 *
 * Candidate order is the list order in [FontResolutionPolicySnapshot]; it is consequently a
 * total deterministic order rather than a score subject to platform-dependent tie breaking.
 */
public data class FontResolutionCandidate(
    /** Face selected when every earlier candidate is unusable for one fallback unit. */
    public val faceId: FontFaceId,
)

/**
 * Immutable, versioned policy for resolving fallback fonts in one catalogue generation.
 *
 * [candidates] is the complete total order. [lastResortFace] must be its final element and is
 * therefore explicit in both successful diagnostics and typed exhaustion results. The policy
 * contains values only, is safe to share between threads, and never opens or retains an asset.
 *
 * @param generation exact catalogue generation to which all candidates belong.
 * @param policyId stable semantic policy identifier.
 * @param version version of the observable ordering rules.
 * @param candidates complete non-empty candidate order.
 * @param lastResortFace explicitly declared final candidate.
 */
public class FontResolutionPolicySnapshot(
    /** Exact catalogue generation used by this policy. */
    public val generation: FontCatalogGeneration,
    /** Stable semantic policy identifier. */
    public val policyId: String,
    /** Version of the policy's observable ordering rules. */
    public val version: String,
    candidates: List<FontResolutionCandidate>,
    /** Explicit final candidate used only after all preceding candidates fail. */
    public val lastResortFace: FontFaceId,
) {
    /** Complete deterministic candidate order. */
    public val candidates: List<FontResolutionCandidate> = candidates.immutableListSnapshot()

    init {
        require(policyId.isNotBlank()) { "Font resolution policy id must not be blank." }
        require(version.isNotBlank()) { "Font resolution policy version must not be blank." }
        require(this.candidates.isNotEmpty()) { "Font resolution policy must declare at least one candidate." }
        require(this.candidates.map(FontResolutionCandidate::faceId).distinct().size == this.candidates.size) {
            "Font resolution policy candidates must not repeat a face."
        }
        require(this.candidates.last().faceId == lastResortFace) {
            "The explicit last-resort face must be the final candidate."
        }
    }
}

/** One script, language, and BiDi itemization fragment inside an atomic fallback unit. */
public data class FallbackShapingFragment(
    /** Non-empty snapshot-bound source range covered by this fragment. */
    public val range: TextRange,
    /** ISO 15924 script passed to shaping. */
    public val script: OpenTypeScript,
    /** Explicit BCP 47 language passed to shaping. */
    public val language: String,
    /** UAX #9 embedding level used for shaping direction. */
    public val bidiLevel: Int,
) {
    init {
        require(range.start < range.endExclusive) { "Fallback shaping fragment range must not be empty." }
        require(language.isNotBlank()) { "Fallback shaping fragment language must not be blank." }
        require(bidiLevel in 0..126) { "Fallback shaping fragment BiDi level must be between 0 and 126." }
    }
}

/**
 * Atomic source range assigned to exactly one font during fallback.
 *
 * A unit is derived from Unicode analysis and therefore includes a complete extended grapheme
 * cluster, variation sequence, and emoji ZWJ sequence. [range] is snapshot-bound and is never
 * split between two candidates. [fragments] preserves every script, language, and BiDi boundary
 * inside the unit as an ordered adjacent partition of that complete range.
 */
public class FallbackUnit(
    /** Complete non-empty snapshot-bound source range of the indivisible unit. */
    public val range: TextRange,
    fragments: List<FallbackShapingFragment>,
) {
    /** Immutable snapshot of the complete ordered shaping-item partition of [range]. */
    public val fragments: List<FallbackShapingFragment> = fragments.immutableListSnapshot()

    init {
        require(range.start < range.endExclusive) { "Fallback unit range must not be empty." }
        require(this.fragments.isNotEmpty()) { "Fallback unit must contain at least one shaping fragment." }
        require(this.fragments.first().range.start == range.start) {
            "Fallback unit fragments must start at the unit range start."
        }
        require(this.fragments.last().range.endExclusive == range.endExclusive) {
            "Fallback unit fragments must end at the unit range end."
        }
        require(this.fragments.zipWithNext().all { (left, right) -> left.range.endExclusive == right.range.start }) {
            "Fallback unit fragments must be ordered and adjacent."
        }
    }
}

/**
 * Immutable resolution result for one fallback operation.
 *
 * Every [shapedRuns] entry was shaped with the matching instance in [instances]. In renderable
 * mode the resolver validates final glyph routes before publishing this value, but a subsequent
 * line-layout step creates the certificates attached to positioned glyphs. The value owns no
 * asset or resolver and is safe to retain concurrently.
 */
public class FontFallbackResolution(
    /** Atomic units in logical source order. */
    units: List<FallbackUnit>,
    /** Contiguous shaped runs in logical source order. */
    shapedRuns: List<ShapedGlyphRun>,
    /** Immutable instances actually used by [shapedRuns]. */
    instances: List<FontInstance>,
    /** Canonically ordered recoverable diagnostics, including last-resort selection. */
    diagnostics: List<FontDiagnostic> = emptyList(),
) {
    /** Atomic units in logical source order. */
    public val units: List<FallbackUnit> = units.immutableListSnapshot()
    /** Contiguous shaped runs in logical source order. */
    public val shapedRuns: List<ShapedGlyphRun> = shapedRuns.immutableListSnapshot()
    /** Immutable font instances actually used by the resolution. */
    public val instances: List<FontInstance> = instances.immutableListSnapshot()
    /** Canonically ordered recoverable diagnostics. */
    public val diagnostics: List<FontDiagnostic> = diagnostics.sortedDiagnostics()
}

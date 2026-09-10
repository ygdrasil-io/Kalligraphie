package org.graphiks.kalligraphie.api

/** Phase in which a fallback decision was observed. */
public enum class FontFallbackStage {
    /** Resolving the catalogue face. */
    FaceResolution,
    /** Creating the requested font instance. */
    Instantiation,
    /** Checking Unicode character and variation-sequence coverage. */
    Cmap,
    /** Shaping the complete unit in its text context. */
    Shaping,
    /** Certifying final glyph representations. */
    Materialization,
}

/** Machine-readable cause of a local rejection or successful last-resort selection. */
public enum class FontFallbackReason {
    /** The face cannot be resolved with the required capabilities. */
    FaceUnavailable,
    /** The requested instance cannot be created from this face. */
    InstantiationFailed,
    /** A visible scalar has no character mapping. */
    MissingVisibleCoverage,
    /** A requested Unicode variation sequence is unsupported. */
    VariationSequenceUnsupported,
    /** Shaping produced a missing glyph for visible text. */
    MissingGlyph,
    /** Shaping could not project the requested source context. */
    ContextCannotBeProjected,
    /** A face-local shaping failure occurred without evidence of a context-projection failure. */
    ShapingFailed,
    /** The requested representation profile or glyph route is unavailable. */
    RepresentationUnavailable,
    /** The acquired asset or representation does not match the requested identity. */
    AssetIncompatible,
    /** A face-local failure prevented materializing a final glyph. */
    GlyphMaterializationFailed,
    /** A temporary asset could not be closed after a candidate attempt. */
    AssetCloseFailed,
    /** The declared last resort succeeded; this is an event, not an earlier rejection cause. */
    LastResortSelected,
}

/** Role and outcome of the face in the captured fallback policy. */
public enum class FontFallbackLastResortState {
    /** The face is an ordinary candidate. */
    NotLastResort,
    /** The explicit last resort was successfully selected. */
    Selected,
    /** An attempt using the explicit last resort was rejected. */
    Rejected,
}

/**
 * Immutable, source-local fallback decision. Collections are defensively captured, including
 * through [copy]; the value retains no font resources. [range] identifies the complete [unit],
 * while [contributingFragments] identifies its shaping fragments involved in the decision.
 */
public class FontFallbackDiagnostic(
    /** Source revision to which every range belongs. */
    public val textVersion: TextVersion,
    /** Complete rejected or selected atomic source range. */
    public val range: TextRange,
    /** Atomic fallback unit, including its original itemization. */
    public val unit: FallbackUnit,
    contributingFragments: List<FallbackShapingFragment>,
    /** Stable candidate face identity. */
    public val faceId: FontFaceId,
    /** Attempted representation, or null before representation negotiation. */
    public val representationProfile: GlyphRepresentationProfile?,
    /** Zero-based position in the captured candidate policy. */
    public val candidateRank: Int,
    /** Zero-based requested profile position, or null without a profile attempt. */
    public val profileRank: Int?,
    /** Phase that observed the decision. */
    public val stage: FontFallbackStage,
    /** Typed cause, independent of human-readable messages. */
    public val reason: FontFallbackReason,
    /** Whether this decision rejected or selected the declared last resort. */
    public val lastResortState: FontFallbackLastResortState,
) {
    /** Immutable contributing shaping fragments preserving source localization. */
    public val contributingFragments: List<FallbackShapingFragment> = contributingFragments.immutableListSnapshot()

    init {
        require(range.start.belongsTo(textVersion)) { "Fallback diagnostic range must belong to its text version." }
        require(range == unit.range) { "Fallback diagnostic range must identify its complete unit." }
        require(candidateRank >= 0 && (profileRank == null || profileRank >= 0)) { "Fallback ranks must be non-negative." }
        require((profileRank == null) == (representationProfile == null)) { "Fallback profile and rank must be supplied together." }
        require(this.contributingFragments.isNotEmpty()) { "Fallback diagnostics must preserve contributing fragments." }
        require((unit.fragments + this.contributingFragments).all { fragment ->
            fragment.range.start.belongsTo(textVersion) && fragment.range.start >= range.start &&
                fragment.range.endExclusive <= range.endExclusive
        }) { "Fallback fragments must belong to the diagnostic version and unit." }
    }

    /** Copies this decision while defensively capturing any replacement fragments. */
    public fun copy(
        textVersion: TextVersion = this.textVersion,
        range: TextRange = this.range,
        unit: FallbackUnit = this.unit,
        contributingFragments: List<FallbackShapingFragment> = this.contributingFragments,
        faceId: FontFaceId = this.faceId,
        representationProfile: GlyphRepresentationProfile? = this.representationProfile,
        candidateRank: Int = this.candidateRank,
        profileRank: Int? = this.profileRank,
        stage: FontFallbackStage = this.stage,
        reason: FontFallbackReason = this.reason,
        lastResortState: FontFallbackLastResortState = this.lastResortState,
    ): FontFallbackDiagnostic = FontFallbackDiagnostic(
        textVersion, range, unit, contributingFragments, faceId, representationProfile,
        candidateRank, profileRank, stage, reason, lastResortState,
    )

    /** Compares source localization, complete unit itemization and the semantic decision. */
    override fun equals(other: Any?): Boolean = other is FontFallbackDiagnostic &&
        textVersion == other.textVersion && range == other.range && unit.fragments == other.unit.fragments &&
        contributingFragments == other.contributingFragments && semanticKey() == other.semanticKey()

    /** Hashes the source localization and semantic decision consistently with [equals]. */
    override fun hashCode(): Int = listOf(textVersion, range, unit.fragments, contributingFragments, semanticKey()).hashCode()

    /** Describes the decision without exposing opaque source offsets. */
    override fun toString(): String = "FontFallbackDiagnostic(textVersion=$textVersion, range=$range, " +
        "fragments=${unit.fragments}, contributingFragments=$contributingFragments, key=${semanticKey()})"
}

/** Immutable occurrences sharing one semantic fallback decision key. */
public class FontFallbackDiagnosticGroup(members: List<FontFallbackDiagnostic>) {
    /** Original occurrences, preserving all versions and ranges in canonical order. */
    public val members: List<FontFallbackDiagnostic> = members.canonicalFallbackDiagnostics()

    init {
        require(this.members.isNotEmpty()) { "Fallback diagnostic groups must not be empty." }
        require(this.members.map { it.semanticKey() }.distinct().size == 1) { "Fallback groups must share one semantic key." }
    }
}

/**
 * Groups decisions by face, profile, ranks, stage, reason and last-resort state, never by source
 * version or range. No occurrence is discarded. Versions retain first-appearance order because
 * [TextVersion] is opaque; within a version members sort by range, stage and candidate/profile ranks.
 * Both the returned list and each group's members are immutable snapshots.
 */
public fun Iterable<FontFallbackDiagnostic>.aggregateFallbackDiagnostics(): List<FontFallbackDiagnosticGroup> =
    canonicalFallbackDiagnostics().groupBy { it.semanticKey() }.values.map(::FontFallbackDiagnosticGroup).immutableListSnapshot()

internal fun Iterable<FontFallbackDiagnostic>.canonicalFallbackDiagnostics(): List<FontFallbackDiagnostic> =
    groupBy { it.textVersion }.values.flatMap { members ->
        members.sortedWith(compareBy<FontFallbackDiagnostic> { it.range.start.ordinal }
            .thenBy { it.range.endExclusive.ordinal }.thenBy { it.stage.ordinal }
            .thenBy { it.candidateRank }.thenBy { it.profileRank ?: -1 })
    }.immutableListSnapshot()

private fun FontFallbackDiagnostic.semanticKey(): List<Any?> =
    listOf(faceId, representationProfile, candidateRank, profileRank, stage, reason, lastResortState)

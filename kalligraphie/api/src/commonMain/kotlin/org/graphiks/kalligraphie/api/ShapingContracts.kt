package org.graphiks.kalligraphie.api

import kotlin.jvm.JvmInline

/** Direction explicitly supplied to an OpenType shaping operation. */
public enum class ShapingDirection {
    /** Left-to-right shaping; the associated resolved BiDi level must be even. */
    LEFT_TO_RIGHT,

    /** Right-to-left shaping; the associated resolved BiDi level must be odd. */
    RIGHT_TO_LEFT,

    /** Top-to-bottom shaping; the UAX #9 embedding level remains metadata, not a progression axis. */
    TOP_TO_BOTTOM,
}

/**
 * Opaque local token identifying a HarfBuzz cluster within one shaped run.
 *
 * Tokens are allocated by the shaping backend for one request only. Their numeric values
 * have no relation to [TextIndex], source offsets, or any private text-index representation.
 */
@JvmInline
public value class ShaperClusterToken(
    /** Non-negative local token value. */
    public val value: Int,
) {
    init {
        require(value >= 0) { "Shaper cluster tokens must be non-negative." }
    }
}

/** ISO 15924 script tag passed explicitly to an OpenType shaper. */
public class OpenTypeScript(value: String) {
    /** Four ASCII letters normalized to ISO 15924 title case. */
    public val value: String = value.canonicalScriptTag()

    /** Compares canonical ISO 15924 script tags. */
    override fun equals(other: Any?): Boolean = other is OpenTypeScript && value == other.value

    /** Returns a stable hash of the canonical script tag. */
    override fun hashCode(): Int = value.hashCode()

    /** Returns a diagnostic form containing the canonical script tag. */
    override fun toString(): String = "OpenTypeScript($value)"
}

/**
 * Immutable explicit OpenType feature setting.
 *
 * The tag is normalized to lowercase ASCII. Feature selection is applied only by backends
 * that accept the setting; requests for non-deterministic features are rejected by the
 * backend before native shaping begins.
 */
public class OpenTypeFeature(
    tag: String,
    /** Unsigned OpenType feature value represented in a signed Kotlin [Int]. */
    public val value: Int,
) {
    /** Canonical lowercase four-character OpenType feature tag. */
    public val tag: String = tag.canonicalOpenTypeTag()

    /** Compares canonical feature tags and their requested values. */
    override fun equals(other: Any?): Boolean = other is OpenTypeFeature && tag == other.tag && value == other.value

    /** Returns a stable hash of the feature tag and value. */
    override fun hashCode(): Int = 31 * tag.hashCode() + value

    /** Returns a diagnostic form containing the canonical feature tag and value. */
    override fun toString(): String = "OpenTypeFeature(tag=$tag, value=$value)"
}

/** How a versioned [ShapingFeaturePolicy] selects its baseline feature behavior. */
public enum class ShapingFeaturePolicyApplication {
    /**
     * Delegates baseline feature selection to the identified, pinned shaping backend.
     *
     * This does not claim to enumerate choices that the shaping engine derives from its
     * version, font tables, script, language, or text. Explicit [OpenTypeFeature] overrides
     * remain separate and are applied to every request carrying this policy.
     */
    PINNED_BACKEND_DEFAULTS,
}

/**
 * Immutable, versioned policy for baseline OpenType feature selection.
 *
 * A policy identifies the behavior that applies before the request's explicit
 * [OpenTypeFeature] overrides. [policyId] and [version] are stable replay inputs: a backend
 * must reject a policy it does not implement instead of silently substituting its own
 * defaults. The object owns no resources and is immutable, so it is safe to share between
 * threads.
 */
public class ShapingFeaturePolicy(
    policyId: String,
    version: String,
    /** Baseline-selection semantics identified by this policy. */
    public val application: ShapingFeaturePolicyApplication,
) {
    /** Stable, non-empty identifier for this policy family. */
    public val policyId: String = policyId.also {
        require(it.isNotBlank()) { "Shaping feature-policy identifiers must not be blank." }
    }

    /** Stable, non-empty version within [policyId]. */
    public val version: String = version.also {
        require(it.isNotBlank()) { "Shaping feature-policy versions must not be blank." }
    }

    /** Compares the stable policy identifier, version, and baseline semantics. */
    override fun equals(other: Any?): Boolean =
        other is ShapingFeaturePolicy &&
            policyId == other.policyId &&
            version == other.version &&
            application == other.application

    /** Returns a stable hash of the complete policy replay input. */
    override fun hashCode(): Int = 31 * (31 * policyId.hashCode() + version.hashCode()) + application.hashCode()

    /** Returns a diagnostic form containing the stable policy replay input. */
    override fun toString(): String = "ShapingFeaturePolicy($policyId@$version, $application)"
}

/**
 * Portable semantic identity of one deterministic shaping configuration.
 *
 * Equality means that two backends implement the same shaping semantics independently of the
 * operating system, processor architecture, or native artifact that distributes the engine.
 * This value is suitable for continuations, replay signatures, run coalescence, and result-cache
 * keys. It never authorizes sharing a native resource between backend instances.
 */
public data class ShapingSemanticIdentity(
    /** Stable shaping-backend implementation identifier. */
    public val backendId: String,
    /** Stable identifier of the shaping engine used by the backend. */
    public val engineId: String,
    /** Version of [engineId] whose shaping behavior is represented. */
    public val engineVersion: String,
    /** Explicit shaping-engine shaper selected by the backend. */
    public val shaperId: String,
    /** Explicit, versioned baseline feature policy implemented by the backend. */
    public val featurePolicy: ShapingFeaturePolicy,
    /** Versioned fingerprint of every remaining fixed shaping configuration input. */
    public val configurationFingerprint: String,
) {
    init {
        require(backendId.isNotBlank()) { "Backend identifier must not be blank." }
        require(engineId.isNotBlank()) { "Shaping engine identifier must not be blank." }
        require(engineVersion.isNotBlank()) { "Shaping engine version must not be blank." }
        require(shaperId.isNotBlank()) { "Shaper identifier must not be blank." }
        require(configurationFingerprint.isNotBlank()) { "Configuration fingerprint must not be blank." }
    }
}

/**
 * Diagnostic provenance of the native distribution used by a shaping backend.
 *
 * Provenance distinguishes platform artifacts and build chains without changing portable shaping
 * equivalence. It is retained in shaped results for audits and diagnostics, but must not be used
 * for continuation compatibility, replay, coalescence, or result-cache decisions.
 */
public data class ShapingDistributionProvenance(
    /** Normalized operating-system identifier of the distributed artifact. */
    public val operatingSystem: String,
    /** Normalized processor-architecture identifier of the distributed artifact. */
    public val architecture: String,
    /** Coordinate and embedded-library path identifying the distributed artifact. */
    public val artifactId: String,
    /** Lowercase SHA-256 digest of the library identified by [artifactId]. */
    public val artifactSha256: String,
    /** Stable upstream project identifier for the native shaping engine source. */
    public val sourceProject: String,
    /** Immutable upstream source revision used by the distributed artifact. */
    public val sourceRevision: String,
    /** Stable identity of the toolchain and build route that produced the artifact. */
    public val buildChainIdentity: String,
) {
    init {
        require(operatingSystem.isNotBlank()) { "Distribution operating system must not be blank." }
        require(architecture.isNotBlank()) { "Distribution architecture must not be blank." }
        require(artifactId.isNotBlank()) { "Native artifact identifier must not be blank." }
        require(artifactSha256.matches(SHA256_HEX)) { "Native artifact SHA-256 must be a lowercase hexadecimal digest." }
        require(sourceProject.isNotBlank()) { "Native source project must not be blank." }
        require(sourceRevision.isNotBlank()) { "Native source revision must not be blank." }
        require(buildChainIdentity.isNotBlank()) { "Native build-chain identity must not be blank." }
    }
}

/**
 * Exact source span attributed to one native shaping distribution.
 *
 * A run's spans remain in logical source order. Adjacent spans with equal [provenance] are
 * normalized into one span, allowing derived fragments to retain only distributions whose
 * original source ranges intersect the fragment.
 */
public data class ShapingProvenanceSpan(
    /** Half-open original source range shaped by [provenance]. */
    public val range: TextRange,
    /** Native distribution that shaped [range]. */
    public val provenance: ShapingDistributionProvenance,
)

/**
 * Complete identity reported by a shaping backend and retained by shaped results.
 *
 * [semantic] is the only component suitable for portable equivalence decisions. [provenance]
 * records which native distribution executed the operation for diagnostics and audits. The seven
 * legacy properties, their positional constructor, [copy], and [component1] through [component7]
 * remain available for source compatibility.
 */
public class ShapingBackendIdentity private constructor(
    /** Stable backend implementation identifier. */
    public val backendId: String,
    /** Native shaping-engine version reported by the loaded library. */
    public val nativeVersion: String,
    /** Immutable source revision embedded in the selected native artifact. */
    public val nativeSourceRevision: String,
    /** Pinned artifact coordinate, classifier, and embedded-library path selected at runtime. */
    public val nativeArtifactId: String,
    /** Lowercase SHA-256 digest of the library identified by [nativeArtifactId]. */
    public val nativeArtifactSha256: String,
    /** Explicit, versioned baseline feature policy implemented by this backend. */
    public val featurePolicy: ShapingFeaturePolicy,
    /** Versioned fingerprint of the backend's fixed shaping configuration. */
    public val configurationFingerprint: String,
    /** Portable shaping semantics implemented by this backend. */
    public val semantic: ShapingSemanticIdentity,
    /** Native distribution provenance observed for this backend instance. */
    public val provenance: ShapingDistributionProvenance,
    private val hasExplicitSemantics: Boolean,
) {
    /**
     * Creates an identity with explicit portable semantics and native distribution provenance.
     *
     * Backends that know their engine and shaper must use this constructor so equivalent builds
     * can compare portably across distributions.
     */
    public constructor(
        semantic: ShapingSemanticIdentity,
        provenance: ShapingDistributionProvenance,
    ) : this(
        backendId = semantic.backendId,
        nativeVersion = semantic.engineVersion,
        nativeSourceRevision = provenance.sourceRevision,
        nativeArtifactId = provenance.artifactId,
        nativeArtifactSha256 = provenance.artifactSha256,
        featurePolicy = semantic.featurePolicy,
        configurationFingerprint = semantic.configurationFingerprint,
        semantic = semantic,
        provenance = provenance,
        hasExplicitSemantics = true,
    )

    /**
     * Creates an identity from the legacy seven-field representation.
     *
     * Because that representation does not identify an engine or shaper explicitly, its portable
     * semantic identity conservatively includes every native provenance field. Two legacy values
     * are therefore portable-equivalent only when all seven legacy fields match.
     *
     * @param backendId stable shaping-backend implementation identifier.
     * @param nativeVersion shaping-engine version represented by the legacy identity.
     * @param nativeSourceRevision immutable source revision of the native artifact.
     * @param nativeArtifactId coordinate and embedded path of the native artifact.
     * @param nativeArtifactSha256 lowercase SHA-256 digest of the native artifact.
     * @param featurePolicy explicit baseline feature policy implemented by the backend.
     * @param configurationFingerprint versioned fixed shaping configuration fingerprint.
     */
    public constructor(
        backendId: String,
        nativeVersion: String,
        nativeSourceRevision: String,
        nativeArtifactId: String,
        nativeArtifactSha256: String,
        featurePolicy: ShapingFeaturePolicy,
        configurationFingerprint: String,
    ) : this(
        backendId = backendId,
        nativeVersion = nativeVersion,
        nativeSourceRevision = nativeSourceRevision,
        nativeArtifactId = nativeArtifactId,
        nativeArtifactSha256 = nativeArtifactSha256,
        featurePolicy = featurePolicy,
        configurationFingerprint = configurationFingerprint,
        semantic = ShapingSemanticIdentity(
            backendId = backendId,
            engineId = backendId,
            engineVersion = nativeVersion,
            shaperId = "legacy-unspecified",
            featurePolicy = featurePolicy,
            configurationFingerprint = legacySemanticFingerprint(
                configurationFingerprint,
                nativeSourceRevision,
                nativeArtifactId,
                nativeArtifactSha256,
            ),
        ),
        provenance = ShapingDistributionProvenance(
            operatingSystem = "unspecified",
            architecture = "unspecified",
            artifactId = nativeArtifactId,
            artifactSha256 = nativeArtifactSha256,
            sourceProject = "unspecified",
            sourceRevision = nativeSourceRevision,
            buildChainIdentity = "unspecified",
        ),
        hasExplicitSemantics = false,
    )

    /**
     * Copies this identity through its legacy seven-field surface.
     *
     * An explicitly semantic identity retains its engine and shaper while corresponding semantic
     * or provenance fields are updated. A legacy identity remains conservative after every copy.
     */
    public fun copy(
        backendId: String = this.backendId,
        nativeVersion: String = this.nativeVersion,
        nativeSourceRevision: String = this.nativeSourceRevision,
        nativeArtifactId: String = this.nativeArtifactId,
        nativeArtifactSha256: String = this.nativeArtifactSha256,
        featurePolicy: ShapingFeaturePolicy = this.featurePolicy,
        configurationFingerprint: String = this.configurationFingerprint,
    ): ShapingBackendIdentity = if (hasExplicitSemantics) {
        ShapingBackendIdentity(
            ShapingSemanticIdentity(
                backendId = backendId,
                engineId = semantic.engineId,
                engineVersion = nativeVersion,
                shaperId = semantic.shaperId,
                featurePolicy = featurePolicy,
                configurationFingerprint = configurationFingerprint,
            ),
            provenance.copy(
                artifactId = nativeArtifactId,
                artifactSha256 = nativeArtifactSha256,
                sourceRevision = nativeSourceRevision,
            ),
        )
    } else {
        ShapingBackendIdentity(
            backendId,
            nativeVersion,
            nativeSourceRevision,
            nativeArtifactId,
            nativeArtifactSha256,
            featurePolicy,
            configurationFingerprint,
        )
    }

    /** Copies this identity with explicitly selected portable semantics. */
    public fun copy(
        semantic: ShapingSemanticIdentity,
        provenance: ShapingDistributionProvenance = this.provenance,
    ): ShapingBackendIdentity = ShapingBackendIdentity(semantic, provenance)

    /**
     * Creates a structured identity with the same portable semantics and another provenance.
     *
     * This operation also supports a legacy receiver: the returned identity retains the same
     * conservative [semantic] instance, adopts [provenance], and exposes seven-field getters
     * derived from those structured components. The receiver and its original legacy getters
     * remain unchanged.
     */
    public fun copy(provenance: ShapingDistributionProvenance): ShapingBackendIdentity =
        ShapingBackendIdentity(semantic, provenance)

    /** Returns [backendId] for legacy destructuring. */
    public operator fun component1(): String = backendId

    /** Returns [nativeVersion] for legacy destructuring. */
    public operator fun component2(): String = nativeVersion

    /** Returns [nativeSourceRevision] for legacy destructuring. */
    public operator fun component3(): String = nativeSourceRevision

    /** Returns [nativeArtifactId] for legacy destructuring. */
    public operator fun component4(): String = nativeArtifactId

    /** Returns [nativeArtifactSha256] for legacy destructuring. */
    public operator fun component5(): String = nativeArtifactSha256

    /** Returns [featurePolicy] for legacy destructuring. */
    public operator fun component6(): ShapingFeaturePolicy = featurePolicy

    /** Returns [configurationFingerprint] for legacy destructuring. */
    public operator fun component7(): String = configurationFingerprint

    /** Compares the complete semantic identity and native distribution provenance. */
    override fun equals(other: Any?): Boolean =
        this === other || other is ShapingBackendIdentity && semantic == other.semantic && provenance == other.provenance

    /** Returns a hash of the complete semantic identity and native distribution provenance. */
    override fun hashCode(): Int = 31 * semantic.hashCode() + provenance.hashCode()

    /** Returns a diagnostic representation of the semantic identity and provenance. */
    override fun toString(): String = "ShapingBackendIdentity(semantic=$semantic, provenance=$provenance)"
}

private fun legacySemanticFingerprint(
    configurationFingerprint: String,
    nativeSourceRevision: String,
    nativeArtifactId: String,
    nativeArtifactSha256: String,
): String = buildString {
    append(configurationFingerprint.length).append(':').append(configurationFingerprint)
    append('|').append(nativeSourceRevision.length).append(':').append(nativeSourceRevision)
    append('|').append(nativeArtifactId.length).append(':').append(nativeArtifactId)
    append('|').append(nativeArtifactSha256.length).append(':').append(nativeArtifactSha256)
}

/** Resource dimension enforced for one explicit shaping operation. */
public enum class ShapingResourceLimit {
    /** Unicode scalars admitted from the request range. */
    SCALARS,

    /** Glyphs emitted by the shaping engine before a portable run is published. */
    GLYPHS,
}

/**
 * Immutable resource profile for one [ShapingRequest].
 *
 * Reaching a limit or observing cancellation never publishes a partial [ShapedGlyphRun].
 * [cancellationCheckInterval] bounds scalar and glyph iterations between cooperative
 * observations outside one native shaping call; it does not alter successful shaping semantics.
 */
public class ShapingResourceProfile(
    /** Maximum Unicode scalars accepted from the complete shaping context, including the item. */
    public val maxScalars: Int = Int.MAX_VALUE,
    /** Maximum shaped glyphs accepted before portable output is allocated. */
    public val maxGlyphs: Int = Int.MAX_VALUE,
    /** Positive interval between cooperative cancellation observations. */
    public val cancellationCheckInterval: Int = 256,
) {
    init {
        require(maxScalars >= 0) { "Shaping scalar budget must be non-negative." }
        require(maxGlyphs >= 0) { "Shaping glyph budget must be non-negative." }
        require(cancellationCheckInterval > 0) { "Shaping cancellation interval must be positive." }
    }

    /** Standard profile accepting every request representable by the public contracts. */
    public companion object {
        public val unbounded: ShapingResourceProfile = ShapingResourceProfile()
    }
}

/**
 * Fully explicit, immutable input to one relative shaping operation.
 *
 * The snapshot and every range must share one [TextVersion]. Direction, script, language,
 * resolved BiDi level, boundary flags, feature policy, and feature overrides are never inferred
 * by this contract. Collections are captured immutably, so requests may be shared between
 * threads when their [font] implementation supports concurrent reads.
 *
 * [contextRange] provides surrounding text for joining decisions without authorizing glyphs
 * outside [itemRange]. A ligature crossing an item boundary must be shaped as a larger item;
 * it cannot be published with truncated source provenance.
 */
public class ShapingRequest(
    /** Immutable canonical text snapshot containing both the item and its shaping context. */
    public val snapshot: TextSnapshot,
    /** Half-open scalar range whose glyphs and provenance may be published. */
    public val itemRange: TextRange,
    /** Surrounding scalar range used for contextual shaping; it must contain [itemRange]. */
    public val contextRange: TextRange,
    /** Concrete font instance supplying owned OpenType data to a backend. */
    public val font: FontInstance,
    /** Explicit shaping direction compatible with [bidiLevel]. */
    public val direction: ShapingDirection,
    /** Explicit ISO 15924 script. */
    public val script: OpenTypeScript,
    /**
     * Explicit language tag forwarded to the shaping engine.
     *
     * commonMain checks only basic tag syntax: non-empty alphanumeric subtags separated by
     * single hyphens. It neither applies the BCP 47 registry nor canonicalizes casing,
     * aliases, or extensions.
     */
    public val language: String,
    /** Resolved UAX #9 embedding level, from 0 through 126. */
    public val bidiLevel: Int,
    /** Whether the item begins the real text context; true requires matching start boundaries. */
    public val bot: Boolean,
    /** Whether the item ends the real text context; true requires matching end boundaries. */
    public val eot: Boolean,
    /** Explicit, versioned baseline feature policy the selected backend must implement. */
    public val featurePolicy: ShapingFeaturePolicy,
    features: List<OpenTypeFeature>,
    graphemeClusters: List<TextRange>,
    /** Resource profile enforced before and while this run is shaped. */
    public val resourceProfile: ShapingResourceProfile = ShapingResourceProfile.unbounded,
    /** Cooperative cancellation signal observed before portable output is published. */
    public val cancellationToken: CancellationToken = CancellationToken.none,
) {
    /** Compatibility alias for the range whose glyphs are published, excluding surrounding context. */
    @Deprecated("Use itemRange", ReplaceWith("itemRange"))
    public val range: TextRange get() = itemRange

    /**
     * Creates an item with no additional surrounding text, preserving the original named `range`
     * constructor. Use the primary constructor to retain joining context across item boundaries.
     */
    public constructor(
        snapshot: TextSnapshot,
        range: TextRange,
        font: FontInstance,
        direction: ShapingDirection,
        script: OpenTypeScript,
        language: String,
        bidiLevel: Int,
        bot: Boolean,
        eot: Boolean,
        featurePolicy: ShapingFeaturePolicy,
        features: List<OpenTypeFeature>,
        graphemeClusters: List<TextRange>,
        resourceProfile: ShapingResourceProfile = ShapingResourceProfile.unbounded,
        cancellationToken: CancellationToken = CancellationToken.none,
    ) : this(
        snapshot, range, range, font, direction, script, language, bidiLevel, bot, eot,
        featurePolicy, features, graphemeClusters, resourceProfile, cancellationToken,
    )

    /** Immutable feature overrides applied after [featurePolicy] in caller-specified deterministic order. */
    public val features: List<OpenTypeFeature> = features.immutableListSnapshot()

    /**
     * Immutable logical partition of [itemRange] induced by its extended grapheme clusters.
     *
     * A script or BiDi itemization boundary can divide an extended grapheme cluster, so an entry
     * may be only a fragment of that cluster. Such a fragment is a shaping boundary only and
     * never authorizes a consumer caret inside the complete extended grapheme cluster.
     */
    public val graphemeClusters: List<TextRange> = graphemeClusters.immutableListSnapshot()

    init {
        require(snapshot.contains(itemRange) && snapshot.contains(contextRange)) {
            "Shaping item and context must belong to the supplied snapshot."
        }
        require(contextRange.start <= itemRange.start && contextRange.endExclusive >= itemRange.endExclusive) {
            "Shaping context must contain the complete item."
        }
        require(!bot || itemRange.start == contextRange.start) { "BOT must identify the real context start." }
        require(!eot || itemRange.endExclusive == contextRange.endExclusive) { "EOT must identify the real context end." }
        require(language.hasBasicLanguageTagSyntax()) { "Language must use non-empty alphanumeric subtags separated by single hyphens." }
        require(bidiLevel in 0..126) { "BiDi level must be between 0 and 126." }
        require(direction.matches(bidiLevel)) { "Shaping direction must agree with the resolved BiDi level." }
        require(features.map(OpenTypeFeature::tag).distinct().size == features.size) {
            "Shaping features must not repeat a tag."
        }
        requireTextPartition(itemRange, this.graphemeClusters, "Grapheme clusters")
    }
}

/** Safety information reported by a shaping engine for a produced glyph. */
public data class ShapingSafetyFlags(
    /** A break adjacent to this glyph is unsafe for shaping continuity. */
    public val unsafeToBreak: Boolean,
    /** Concatenating this glyph's context can change shaping. */
    public val unsafeToConcat: Boolean,
)

/**
 * Relative glyph output from one shaping operation.
 *
 * Advances and offsets are portable layout units relative to the run origin; no final line
 * coordinate is present. A glyph can relate to several local [clusterTokens], permitting
 * a many-to-many projection through the run's cluster mapping.
 */
public class ShapedGlyph(
    /** Glyph selected by the shaping engine. */
    public val glyphId: GlyphId,
    /** Signed horizontal advance relative to the run origin. */
    public val xAdvance: LayoutUnit,
    /** Signed vertical advance relative to the run origin. */
    public val yAdvance: LayoutUnit,
    /** Signed horizontal placement offset relative to the glyph advance. */
    public val xOffset: LayoutUnit,
    /** Signed vertical placement offset relative to the glyph advance. */
    public val yOffset: LayoutUnit,
    /** Engine safety flags associated with this glyph. */
    public val safetyFlags: ShapingSafetyFlags,
    clusterTokens: List<ShaperClusterToken>,
) {
    /** Immutable local cluster tokens related to this glyph. */
    public val clusterTokens: List<ShaperClusterToken> = clusterTokens.immutableListSnapshot()

    /**
     * Sole cluster token for HarfBuzz monotone-character output.
     *
     * This convenience accessor throws if a future backend exposes a true multi-cluster
     * glyph; callers requiring the general relation use [clusterTokens].
     */
    public val clusterToken: ShaperClusterToken
        get() = clusterTokens.single()

    init {
        require(this.clusterTokens.isNotEmpty()) { "Every shaped glyph must relate to a cluster." }
    }
}

/**
 * One local shaping cluster and the complete source span that produced it.
 *
 * [sourceRange] may cover multiple Unicode scalars for a ligature and one scalar may map to
 * several glyphs. [scalarRanges] always retains the scalar mapping independently of
 * [admissibleGraphemeBoundaries]. A HarfBuzz cluster is not presumed to be a grapheme:
 * a cluster covering only part of a combining sequence can expose zero or one admissible
 * grapheme boundary.
 */
public class ShaperCluster(
    /** Local token allocated for one shaping request. */
    public val token: ShaperClusterToken,
    /** Complete half-open source range contributing to this cluster. */
    public val sourceRange: TextRange,
    scalarRanges: List<TextRange>,
    admissibleGraphemeBoundaries: List<TextIndex>,
) {
    /** Immutable logical partition of [sourceRange] into contributing scalar ranges. */
    public val scalarRanges: List<TextRange> = scalarRanges.immutableListSnapshot()

    /**
     * Grapheme boundaries from the request partition that lie in [sourceRange].
     *
     * This list can be empty when the shaping cluster covers only a fragment of an extended
     * grapheme cluster; it never manufactures scalar boundaries as grapheme boundaries.
     */
    public val admissibleGraphemeBoundaries: List<TextIndex> = admissibleGraphemeBoundaries.immutableListSnapshot()

    init {
        requireTextPartition(sourceRange, this.scalarRanges, "Cluster scalar ranges")
        require(this.admissibleGraphemeBoundaries.all { boundary ->
            boundary.sharesVersionWith(sourceRange.start) &&
                boundary.compareTo(sourceRange.start) >= 0 &&
                boundary.compareTo(sourceRange.endExclusive) <= 0
        }) { "Admissible grapheme boundaries must lie within the cluster source range." }
        require(this.admissibleGraphemeBoundaries.zipWithNext().all { (left, right) -> left.compareTo(right) < 0 }) {
            "Admissible grapheme boundaries must be strictly increasing."
        }
    }
}

/** Availability state of font-provided GDEF ligature caret positions. */
public enum class GdefLigatureCaretState {
    /** The font provided no caret positions for the ligature glyph. */
    ABSENT,

    /** The font provided a complete, valid set of caret positions. */
    AVAILABLE,

    /** The font provided positions that do not match the required internal boundaries. */
    INCONSISTENT,
}

/**
 * GDEF ligature-caret fact associated with one glyph in a shaped run.
 *
 * [logicalSourceBoundaries] identifies the editable internal grapheme boundaries in logical
 * source order. [positions] is aligned index-for-index with those boundaries when [state] is
 * [GdefLigatureCaretState.AVAILABLE]. Every position is a signed layout-unit offset from the
 * glyph origin on the shaping baseline: its sign and origin stay in the font coordinate system,
 * while a right-to-left backend reverses GDEF's increasing-coordinate sequence before publishing
 * it in logical source order. Instances are immutable and safe to share between threads.
 * Consumers must use their documented deterministic fallback when [state] is not
 * [GdefLigatureCaretState.AVAILABLE].
 */
public class GdefLigatureCaretFact(
    /** Zero-based glyph index in the enclosing [ShapedGlyphRun]. */
    public val glyphIndex: Int,
    /** Audited availability of font-provided caret data. */
    public val state: GdefLigatureCaretState,
    logicalSourceBoundaries: List<TextIndex>,
    positions: List<LayoutUnit> = emptyList(),
) {
    /** Immutable editable internal grapheme boundaries, in logical source order. */
    public val logicalSourceBoundaries: List<TextIndex> = logicalSourceBoundaries.immutableListSnapshot()

    /** Immutable positions supplied by GDEF when they are complete and valid. */
    public val positions: List<LayoutUnit> = positions.immutableListSnapshot()

    init {
        require(glyphIndex >= 0) { "Ligature caret glyph index must be non-negative." }
        require(this.logicalSourceBoundaries.isNotEmpty()) {
            "Ligature caret facts must identify at least one editable internal grapheme boundary."
        }
        require(this.logicalSourceBoundaries.zipWithNext().all { (left, right) -> left.compareTo(right) < 0 }) {
            "Ligature caret source boundaries must be strictly increasing in logical order."
        }
        require(state != GdefLigatureCaretState.AVAILABLE || this.positions.size == this.logicalSourceBoundaries.size) {
            "Available GDEF caret data must provide exactly one position per editable internal grapheme boundary."
        }
        require(state == GdefLigatureCaretState.AVAILABLE || this.positions.isEmpty()) {
            "Unavailable or inconsistent GDEF caret data must not publish positions."
        }
    }
}

/**
 * Queryable many-to-many projections between source ranges, local clusters, and glyphs.
 *
 * Instances are derived from an immutable [ShapedGlyphRun] and are safe to share between
 * threads. Query ranges must be bound to the same text version; violations are programming
 * errors and fail deterministically instead of being coerced to another text revision.
 */
public class ShapingMappings internal constructor(
    private val range: TextRange,
    private val clusters: List<ShaperCluster>,
    private val glyphs: List<ShapedGlyph>,
) {
    /** Returns local clusters whose source spans overlap [sourceRange], in logical order. */
    public fun clustersForSource(sourceRange: TextRange): List<ShaperClusterToken> {
        requireSameTextVersion(sourceRange, range)
        return clusters
            .filter { cluster -> rangesOverlap(sourceRange, cluster.sourceRange) }
            .map(ShaperCluster::token)
            .immutableListSnapshot()
    }

    /** Returns one scalar source range per scalar contributing to [token], in logical order. */
    public fun sourcesForCluster(token: ShaperClusterToken): List<TextRange> {
        val cluster = clusterFor(token)
        return cluster.scalarRanges
    }

    /** Returns glyph indexes related to [token], in produced glyph order. */
    public fun glyphsForCluster(token: ShaperClusterToken): List<Int> =
        glyphs.indices.filter { index -> token in glyphs[index].clusterTokens }.immutableListSnapshot()

    /** Returns local clusters related to a produced glyph index, in its declared order. */
    public fun clustersForGlyph(glyphIndex: Int): List<ShaperClusterToken> {
        require(glyphIndex in glyphs.indices) { "Glyph index lies outside the shaped run." }
        return glyphs[glyphIndex].clusterTokens
    }

    private fun clusterFor(token: ShaperClusterToken): ShaperCluster =
        clusters.firstOrNull { it.token == token }
            ?: throw IllegalArgumentException("Cluster token does not belong to the shaped run.")
}

/**
 * Immutable, relative shaping result.
 *
 * A run may come directly from one backend operation or be assembled from compatible source
 * contributions. It has no final line coordinates and can therefore be positioned only by a later
 * layout layer. Its clusters partition [range], preserve source-to-cluster-to-glyph relations,
 * retain the request's true grapheme partition and explicit feature replay inputs, and carry no
 * native resource; it is safe to share across threads indefinitely.
 */
public class ShapedGlyphRun private constructor(
    /** Source range shaped by this run. */
    public val range: TextRange,
    /** Exact font instance identity used for shaping. */
    public val fontInstanceKey: FontInstanceKey,
    /**
     * Portable shaping semantics and the primary native provenance for this run.
     *
     * The primary provenance is the first contributing distribution and is retained as the
     * source-compatible singular diagnostic route. It does not imply that every glyph was
     * produced by that distribution; [distributionProvenances] is the complete provenance set.
     */
    backendIdentity: ShapingBackendIdentity,
    /** Explicit direction used by the backend. */
    public val direction: ShapingDirection,
    /** Explicit ISO 15924 script used by the backend. */
    public val script: OpenTypeScript,
    /** Explicit language tag passed to the backend without common canonicalization. */
    public val language: String,
    /** Resolved UAX #9 level used by the backend. */
    public val bidiLevel: Int,
    /** Whether the shaped context began at the supplied text boundary. */
    public val bot: Boolean,
    /** Whether the shaped context ended at the supplied text boundary. */
    public val eot: Boolean,
    /** Explicit, versioned baseline feature policy used by the backend. */
    public val featurePolicy: ShapingFeaturePolicy,
    features: List<OpenTypeFeature>,
    graphemeClusters: List<TextRange>,
    glyphs: List<ShapedGlyph>,
    clusters: List<ShaperCluster>,
    ligatureCaretFacts: List<GdefLigatureCaretFact>,
    provenanceSpans: List<ShapingProvenanceSpan>,
) {
    /**
     * Creates a directly shaped run through the historical public constructor.
     *
     * The supplied [backendIdentity] is the sole distribution contributor across [range].
     * Omitting [ligatureCaretFacts] retains the original Kotlin default-constructor contract.
     *
     * @param range source range shaped by this run.
     * @param fontInstanceKey exact font instance identity used for shaping.
     * @param backendIdentity exact semantic and diagnostic identity published by the backend.
     * @param direction explicit direction used by the backend.
     * @param script explicit ISO 15924 script used by the backend.
     * @param language explicit language tag passed to the backend.
     * @param bidiLevel resolved UAX #9 level used by the backend.
     * @param bot whether the shaped context began at the supplied text boundary.
     * @param eot whether the shaped context ended at the supplied text boundary.
     * @param featurePolicy explicit baseline feature policy used by the backend.
     * @param features immutable feature overrides used after [featurePolicy].
     * @param graphemeClusters logical grapheme partition of [range].
     * @param glyphs glyphs in shaping-engine output order.
     * @param clusters shaping clusters in logical source order.
     * @param ligatureCaretFacts optional GDEF caret facts for ligature glyphs.
     */
    public constructor(
        range: TextRange,
        fontInstanceKey: FontInstanceKey,
        backendIdentity: ShapingBackendIdentity,
        direction: ShapingDirection,
        script: OpenTypeScript,
        language: String,
        bidiLevel: Int,
        bot: Boolean,
        eot: Boolean,
        featurePolicy: ShapingFeaturePolicy,
        features: List<OpenTypeFeature>,
        graphemeClusters: List<TextRange>,
        glyphs: List<ShapedGlyph>,
        clusters: List<ShaperCluster>,
        ligatureCaretFacts: List<GdefLigatureCaretFact> = emptyList(),
    ) : this(
        range = range,
        fontInstanceKey = fontInstanceKey,
        backendIdentity = backendIdentity,
        direction = direction,
        script = script,
        language = language,
        bidiLevel = bidiLevel,
        bot = bot,
        eot = eot,
        featurePolicy = featurePolicy,
        features = features,
        graphemeClusters = graphemeClusters,
        glyphs = glyphs,
        clusters = clusters,
        ligatureCaretFacts = ligatureCaretFacts,
        provenanceSpans = listOf(ShapingProvenanceSpan(range, backendIdentity.provenance)),
    )

    public companion object {
        /**
         * Creates a run assembled from exact native-distribution source contributions.
         *
         * [provenanceSpans] must form a complete ordered partition of [range]. Adjacent equal
         * contributions are normalized. [backendIdentity] must already carry the provenance of the
         * first normalized span. A caller that deliberately changes the primary provenance must
         * explicitly create that identity with `copy(provenance = ...)` before invoking this
         * factory. Rebasing a legacy identity produces a structured identity that preserves its
         * conservative semantics; it never mutates the original legacy value.
         *
         * @param range complete logical source range represented by the assembled run.
         * @param fontInstanceKey exact font instance identity shared by all contributions.
         * @param backendIdentity portable semantics and initial primary diagnostic provenance.
         * @param direction explicit direction shared by all contributions.
         * @param script explicit ISO 15924 script shared by all contributions.
         * @param language explicit language tag shared by all contributions.
         * @param bidiLevel resolved UAX #9 level shared by all contributions.
         * @param bot whether the assembled context begins at its supplied text boundary.
         * @param eot whether the assembled context ends at its supplied text boundary.
         * @param featurePolicy explicit baseline feature policy shared by all contributions.
         * @param features immutable feature overrides shared by all contributions.
         * @param graphemeClusters logical grapheme partition of [range].
         * @param glyphs glyphs in shaping-engine output order.
         * @param clusters shaping clusters in logical source order.
         * @param ligatureCaretFacts GDEF caret facts for ligature glyphs.
         * @param provenanceSpans exact ordered source contribution partition of [range].
         */
        public fun withProvenanceSpans(
            range: TextRange,
            fontInstanceKey: FontInstanceKey,
            backendIdentity: ShapingBackendIdentity,
            direction: ShapingDirection,
            script: OpenTypeScript,
            language: String,
            bidiLevel: Int,
            bot: Boolean,
            eot: Boolean,
            featurePolicy: ShapingFeaturePolicy,
            features: List<OpenTypeFeature>,
            graphemeClusters: List<TextRange>,
            glyphs: List<ShapedGlyph>,
            clusters: List<ShaperCluster>,
            ligatureCaretFacts: List<GdefLigatureCaretFact>,
            provenanceSpans: List<ShapingProvenanceSpan>,
        ): ShapedGlyphRun = ShapedGlyphRun(
            range = range,
            fontInstanceKey = fontInstanceKey,
            backendIdentity = backendIdentity,
            direction = direction,
            script = script,
            language = language,
            bidiLevel = bidiLevel,
            bot = bot,
            eot = eot,
            featurePolicy = featurePolicy,
            features = features,
            graphemeClusters = graphemeClusters,
            glyphs = glyphs,
            clusters = clusters,
            ligatureCaretFacts = ligatureCaretFacts,
            provenanceSpans = provenanceSpans,
        )
    }

    /**
     * Immutable logical source partition identifying the exact distribution for each span.
     *
     * Adjacent spans with equal provenance are normalized. A non-empty run is covered without
     * gaps or overlap; an empty run retains one empty span carrying its direct provenance.
     */
    public val provenanceSpans: List<ShapingProvenanceSpan> =
        normalizedProvenanceSpans(range, provenanceSpans)

    /**
     * Portable shaping semantics and the primary native provenance for this run.
     *
     * The exact supplied identity instance is retained, preserving every legacy property and
     * component. Its provenance must match the first item in [provenanceSpans].
     */
    public val backendIdentity: ShapingBackendIdentity = backendIdentity

    /**
     * Immutable ordered set of every native distribution that contributed to this run.
     *
     * This is a derived view of [provenanceSpans]: first source contribution order is retained and
     * duplicate provenances are removed.
     */
    public val distributionProvenances: List<ShapingDistributionProvenance> =
        this.provenanceSpans.map(ShapingProvenanceSpan::provenance).distinct().immutableListSnapshot()

    /** Immutable feature overrides used by the backend after [featurePolicy]. */
    public val features: List<OpenTypeFeature> = features.immutableListSnapshot()

    /**
     * Immutable logical partition induced by the request extended grapheme clusters.
     *
     * An itemization boundary may make an entry a proper fragment of an extended grapheme
     * cluster. The original analysis remains the authority for legal editable boundaries.
     */
    public val graphemeClusters: List<TextRange> = graphemeClusters.immutableListSnapshot()

    /** Immutable glyphs in shaping-engine output order. */
    public val glyphs: List<ShapedGlyph> = glyphs.immutableListSnapshot()

    /** Immutable clusters in logical source order. */
    public val clusters: List<ShaperCluster> = clusters.immutableListSnapshot()

    /** Immutable GDEF caret facts for ligature glyphs where inspection was required. */
    public val ligatureCaretFacts: List<GdefLigatureCaretFact> = ligatureCaretFacts.immutableListSnapshot()

    /** Immutable query facade for text, cluster, and glyph relationships. */
    public val mappings: ShapingMappings = ShapingMappings(range, this.clusters, this.glyphs)

    init {
        require(this.backendIdentity.provenance == this.provenanceSpans.first().provenance) {
            "Run backend identity must carry the first provenance span's distribution."
        }
        require(bidiLevel in 0..126) { "BiDi level must be between 0 and 126." }
        require(direction.matches(bidiLevel)) { "Shaped run direction must agree with its BiDi level." }
        require(language.hasBasicLanguageTagSyntax()) { "Shaped run language has invalid basic tag syntax." }
        require(this.features.map(OpenTypeFeature::tag).distinct().size == this.features.size) {
            "Shaped run features must not repeat a tag."
        }
        requireTextPartition(range, this.graphemeClusters, "Run grapheme clusters")
        requireTextPartition(range, this.clusters.map(ShaperCluster::sourceRange), "Shaping clusters")
        val graphemeBoundaries = this.graphemeClusters.flatMap { cluster -> listOf(cluster.start, cluster.endExclusive) }.toSet()
        require(this.clusters.all { cluster -> cluster.admissibleGraphemeBoundaries.all(graphemeBoundaries::contains) }) {
            "Cluster grapheme boundaries must come from the run grapheme partition."
        }
        val definedTokens = this.clusters.map(ShaperCluster::token).toSet()
        require(definedTokens.size == this.clusters.size) {
            "Shaping clusters must not repeat a local token."
        }
        require(this.glyphs.all { glyph -> glyph.clusterTokens.all(definedTokens::contains) }) {
            "Every glyph cluster token must be declared by the shaped run."
        }
        require(this.ligatureCaretFacts.map(GdefLigatureCaretFact::glyphIndex).all(this.glyphs.indices::contains)) {
            "Ligature caret facts must identify glyphs in the shaped run."
        }
        require(this.ligatureCaretFacts.map(GdefLigatureCaretFact::glyphIndex).distinct().size == this.ligatureCaretFacts.size) {
            "A shaped glyph must have at most one ligature caret fact."
        }
        val clustersByToken = this.clusters.associateBy(ShaperCluster::token)
        this.ligatureCaretFacts.forEach { fact ->
            val relatedClusters = this.glyphs[fact.glyphIndex].clusterTokens.map(clustersByToken::getValue)
            val firstSourceBoundary = relatedClusters.minWith { left, right ->
                left.sourceRange.start.compareTo(right.sourceRange.start)
            }.sourceRange.start
            val lastSourceBoundary = relatedClusters.maxWith { left, right ->
                left.sourceRange.endExclusive.compareTo(right.sourceRange.endExclusive)
            }.sourceRange.endExclusive
            val expectedBoundaries = relatedClusters
                .flatMap(ShaperCluster::admissibleGraphemeBoundaries)
                .filter { boundary ->
                    boundary.sharesVersionWith(firstSourceBoundary) &&
                        boundary.compareTo(firstSourceBoundary) > 0 &&
                        boundary.compareTo(lastSourceBoundary) < 0
                }
                .distinct()
                .sortedWith { left, right -> left.compareTo(right) }
            require(fact.logicalSourceBoundaries == expectedBoundaries) {
                "Ligature caret facts must identify exactly the internal grapheme boundaries of their glyph clusters."
            }
        }
    }
}

/** Portable backend boundary for explicit OpenType shaping. */
public interface ShapingBackend {
    /** Portable shaping semantics and diagnostic native provenance of this backend. */
    public val identity: ShapingBackendIdentity

    /**
     * Shapes [request] into relative glyph output without assigning line coordinates.
     *
     * Backends return typed failures for unavailable font data, unsupported platform
     * capability, rejected non-deterministic features, and native shaping failures. The
     * operation retains no caller-owned resources and is safe for concurrent calls when the
     * backend's identity is successfully opened.
     */
    public fun shape(request: ShapingRequest): FontOperationResult<ShapedGlyphRun>

    /**
     * Releases resources owned by this backend after its caller has finished shaping.
     *
     * A backend that owns no releasable resource returns success. Implementations with native
     * state must make closure idempotent and linearizable: shaping admitted before closure may
     * finish, while shaping admitted after its close transition returns
     * [FontError.ResourceClosed]. Closing never closes a caller-owned [FontInstance], catalog,
     * resolver, or asset. Callers own a successfully opened backend and must invoke this method
     * once they no longer need it; concurrent calls are safe.
     */
    public fun close(): FontOperationResult<Unit> = FontOperationResult.Success(Unit)
}

private fun String.canonicalScriptTag(): String {
    require(length == 4 && all { character -> character in 'A'..'Z' || character in 'a'..'z' }) {
        "ISO 15924 scripts must contain four ASCII letters."
    }
    return buildString(4) {
        append(this@canonicalScriptTag[0].uppercaseChar())
        append(this@canonicalScriptTag.substring(1).lowercase())
    }
}

private fun String.canonicalOpenTypeTag(): String {
    require(length == 4 && all { it.code in 0x20..0x7E }) { "OpenType feature tags must contain four printable ASCII characters." }
    return lowercase()
}

private fun String.hasBasicLanguageTagSyntax(): Boolean =
    isNotBlank() &&
        all { character -> character.isLetterOrDigit() || character == '-' } &&
        !startsWith('-') &&
        !endsWith('-') &&
        !contains("--")

private fun ShapingDirection.matches(level: Int): Boolean =
    when (this) {
        ShapingDirection.LEFT_TO_RIGHT -> level % 2 == 0
        ShapingDirection.RIGHT_TO_LEFT -> level % 2 != 0
        ShapingDirection.TOP_TO_BOTTOM -> true
    }

private fun normalizedProvenanceSpans(
    owner: TextRange,
    spans: List<ShapingProvenanceSpan>,
): List<ShapingProvenanceSpan> {
    require(spans.isNotEmpty()) { "Run provenance spans must identify at least one distribution." }
    val normalized = mutableListOf<ShapingProvenanceSpan>()
    spans.forEach { span ->
        val previous = normalized.lastOrNull()
        if (
            previous != null &&
            previous.provenance == span.provenance &&
            previous.range.endExclusive == span.range.start
        ) {
            normalized[normalized.lastIndex] = ShapingProvenanceSpan(
                TextRange(previous.range.start, span.range.endExclusive),
                span.provenance,
            )
        } else {
            normalized += span
        }
    }
    if (owner.start == owner.endExclusive) {
        require(normalized.size == 1 && normalized.single().range == owner) {
            "An empty run must retain one empty provenance span."
        }
    } else {
        requireTextPartition(owner, normalized.map(ShapingProvenanceSpan::range), "Run provenance spans")
    }
    return normalized.immutableListSnapshot()
}

private fun requireTextPartition(owner: TextRange, ranges: List<TextRange>, label: String) {
    if (owner.start == owner.endExclusive) {
        require(ranges.isEmpty()) { "$label must be empty for an empty range." }
        return
    }
    require(ranges.isNotEmpty()) { "$label must cover the complete range." }
    var expectedStart = owner.start
    ranges.forEach { item ->
        require(item.start != item.endExclusive) { "$label must not contain empty ranges." }
        requireSameTextVersion(item, owner)
        require(item.start == expectedStart) { "$label must be contiguous and ordered." }
        require(item.endExclusive.compareTo(owner.endExclusive) <= 0) { "$label must stay within the owner range." }
        expectedStart = item.endExclusive
    }
    require(expectedStart == owner.endExclusive) { "$label must cover the complete range." }
}

private fun requireSameTextVersion(first: TextRange, second: TextRange) {
    require(first.start.sharesVersionWith(second.start)) { "Text ranges must belong to the same version." }
}

private fun rangesOverlap(first: TextRange, second: TextRange): Boolean =
    first.start.compareTo(second.endExclusive) < 0 && second.start.compareTo(first.endExclusive) < 0

private val SHA256_HEX: Regex = Regex("[0-9a-f]{64}")

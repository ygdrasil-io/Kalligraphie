package org.graphiks.kalligraphie.api

/** A finite point in the portable horizontal layout coordinate system. */
public data class LayoutPoint(
    /** Horizontal coordinate; values grow toward the physical right. */
    public val x: LayoutUnit,
    /** Vertical coordinate; values grow toward the physical bottom. */
    public val y: LayoutUnit,
)

/** A finite displacement in the portable horizontal layout coordinate system. */
public data class LayoutVector(
    /** Horizontal displacement. */
    public val x: LayoutUnit,
    /** Vertical displacement. */
    public val y: LayoutUnit,
)

/** A finite line segment in portable layout coordinates. */
public data class LayoutSegment(
    /** First endpoint of the segment. */
    public val start: LayoutPoint,
    /** Second endpoint of the segment. */
    public val end: LayoutPoint,
)

/**
 * Axis-aligned portable layout geometry.
 *
 * Bounds describe layout extents only. They never describe renderer ink, device pixels,
 * hinting, or rasterization coverage.
 */
public data class LayoutRect(
    /** Inclusive physical left edge. */
    public val left: LayoutUnit,
    /** Inclusive physical top edge. */
    public val top: LayoutUnit,
    /** Exclusive physical right edge. */
    public val right: LayoutUnit,
    /** Exclusive physical bottom edge. */
    public val bottom: LayoutUnit,
) {
    init {
        require(left <= right) { "Layout rectangle left edge must not follow its right edge." }
        require(top <= bottom) { "Layout rectangle top edge must not follow its bottom edge." }
    }
}

/**
 * Explicit vertical metrics for one horizontal, non-wrapped editable line.
 *
 * Both metrics are distances from the baseline `(0, 0)`: ascent extends upward and descent
 * extends downward in the physical `y`-down coordinate system. Values are supplied by the
 * consumer because the current portable font contract does not publish vertical line metrics.
 */
public class LineVerticalMetrics(
    /** Non-negative distance from the baseline to the line top. */
    public val ascent: LayoutUnit,
    /** Non-negative distance from the baseline to the line bottom. */
    public val descent: LayoutUnit,
) {
    /** Strictly positive line-box height, equal to [ascent] plus [descent]. */
    public val height: LayoutUnit = LayoutUnit((ascent.value.toDouble() + descent.value.toDouble()).toFloat())

    init {
        require(ascent.value >= 0f) { "Line ascent must be non-negative." }
        require(descent.value >= 0f) { "Line descent must be non-negative." }
        require(height.value > 0f) { "Line height must be strictly positive." }
    }

    /** Compares the explicit ascent and descent distances. */
    override fun equals(other: Any?): Boolean =
        other is LineVerticalMetrics && ascent == other.ascent && descent == other.descent

    /** Returns a stable hash of the explicit vertical metrics. */
    override fun hashCode(): Int = 31 * ascent.hashCode() + descent.hashCode()

    /** Returns a diagnostic form containing ascent and descent only. */
    override fun toString(): String = "LineVerticalMetrics(ascent=$ascent, descent=$descent)"
}

/** Selects the logical side of a text boundary represented by a caret. */
public enum class CaretAffinity {
    /** The caret belongs to content following its logical text boundary. */
    DOWNSTREAM,

    /** The caret belongs to content preceding its logical text boundary. */
    UPSTREAM,
}

/**
 * Snapshot-bound logical caret anchor.
 *
 * A position identifies logical text and an affinity, but may correspond to more than one
 * [CaretCandidate] when bidirectional geometry is ambiguous. It is immutable and carries no
 * document-persistent anchor semantics.
 */
public data class CaretPosition(
    /** Snapshot-bound text boundary. */
    public val index: TextIndex,
    /** Logical side selected at that boundary. */
    public val affinity: CaretAffinity,
)

/** Classifies how a candidate reaches its logical source boundary. */
public enum class CaretBoundaryEdge {
    /** Candidate lies at a run's logical start edge. */
    LOGICAL_START,

    /** Candidate lies at a run's logical end edge. */
    LOGICAL_END,

    /** Candidate lies inside a run, including a validated ligature stop. */
    INTERNAL,
}

/** Classifies a concrete BiDi caret candidate against the line's explicit base direction. */
public enum class CaretStrength {
    /** The owning run has the same direction as the editable line's base direction. */
    STRONG,

    /** The owning run has the opposite direction from the editable line's base direction. */
    WEAK,
}

/**
 * One concrete, layout-local caret geometry.
 *
 * Candidates are immutable but local to one [EditableLine]. Consumers obtain them from
 * [EditableLine.caretCandidates], [EditableLine.hitTest], or visual navigation and must not
 * retain them as document anchors or reuse them with another line.
 */
public class CaretCandidate(
    /** Logical text position represented by this concrete geometry. */
    public val position: CaretPosition,
    /** Vertical caret segment from the line top to its bottom. */
    public val geometry: LayoutSegment,
    /** Zero-based visual traversal order within the line. */
    public val visualOrder: Int,
    /** Visual positioned-run order that owns this candidate, or [NO_POSITIONED_RUN] for an empty line. */
    public val visualRunOrder: Int,
    /** UAX #9 embedding level of the owning shaped run. */
    public val bidiLevel: Int,
    /** Explicit shaping direction of the owning shaped run. */
    public val direction: ShapingDirection,
    /** Strong or weak role determined from the line's explicit base direction. */
    public val strength: CaretStrength,
    /** Source-boundary relationship of this candidate within the owning run. */
    public val edge: CaretBoundaryEdge,
) {
    init {
        require(visualOrder >= 0) { "Caret visual order must be non-negative." }
        require(visualRunOrder >= NO_POSITIONED_RUN) {
            "Caret visual run order must identify a positioned run or the documented empty-line sentinel."
        }
        require(bidiLevel in 0..126) { "Caret BiDi level must be between 0 and 126." }
        require(direction.matchesBidiLevel(bidiLevel)) { "Caret direction must agree with its BiDi level." }
        require(geometry.start.x == geometry.end.x) { "Editable-line carets must be vertical segments." }
        require(geometry.start.y <= geometry.end.y) { "Caret geometry must progress from line top to bottom." }
    }

    /** Sentinel values used when no positioned run can own a candidate. */
    public companion object {
        /** Visual run order used by both candidates of an explicitly empty line. */
        public const val NO_POSITIONED_RUN: Int = -1
    }
}

/** Chooses a next logical caret boundary in decoded scalar order. */
public enum class LogicalNavigationDirection {
    /** Move toward the following logical caret boundary. */
    FORWARD,

    /** Move toward the preceding logical caret boundary. */
    BACKWARD,
}

/** Chooses a next concrete caret in physical visual traversal order. */
public enum class VisualNavigationDirection {
    /** Move toward the next candidate in visual traversal order. */
    FORWARD,

    /** Move toward the previous candidate in visual traversal order. */
    BACKWARD,
}

/** Route whose successful validation proves a final glyph can be materialized. */
public enum class GlyphMaterializationRoute {
    /** The final glyph was validated as an outline accepted by the requested profile. */
    OUTLINE,

    /** The final glyph was validated as a glyph without ink. */
    EMPTY,
}

/**
 * Immutable record that one final positioned glyph passed the requested outline route.
 *
 * A certificate contains no render asset, outline payload, native handle, or borrowed resource.
 * Its validity is limited to the exact [assetKey] and [glyphId] synchronously inspected while
 * the borrowed resolver was open. This public value records a trusted layouter result; its
 * constructor is not a cryptographic authenticity mechanism for manually constructed values.
 */
public data class GlyphMaterializationCertificate(
    /** Exact font instance, variant, and outline profile used for validation. */
    public val assetKey: FontRenderAssetKey,
    /** Final glyph identifier whose route was validated. */
    public val glyphId: GlyphId,
    /** Successfully validated route. */
    public val route: GlyphMaterializationRoute,
)

/**
 * Final placement of one shaped glyph with direct source-cluster relationships.
 *
 * [origin] includes the shaped glyph's placement offsets and is relative to the editable
 * line baseline `(0, 0)`. [sourceClusters] duplicates no relation by index: it directly owns
 * the immutable cluster values associated with [shapedGlyph].
 */
public class PositionedGlyph(
    /** Relative shaping output retained without modification, or synthesized for derived content. */
    public val shapedGlyph: ShapedGlyph,
    sourceClusters: List<ShaperCluster>,
    /** Final glyph origin relative to the line baseline. */
    public val origin: LayoutPoint,
    /** Final glyph advance in the physical horizontal coordinate system. */
    public val advance: LayoutVector,
    /** Exact render asset key in renderable mode, or `null` in layout-only mode. */
    public val renderAssetKey: FontRenderAssetKey?,
    /** Trusted outline-route validation record in renderable mode, or `null` in layout-only mode. */
    public val materializationCertificate: GlyphMaterializationCertificate?,
    /**
     * Typed provenance of this final glyph.
     *
     * [GlyphProvenance.Direct] defaults to the complete source range mapped by
     * [sourceClusters]. The invariants below guarantee that provenance never
     * invents a document position: a derived glyph names exactly the real
     * transformed source range, and a synthetic glyph anchors at a boundary of
     * one of its mapped source clusters.
     */
    provenance: GlyphProvenance = GlyphProvenance.Direct(
        TextRange(sourceClusters.first().sourceRange.start, sourceClusters.last().sourceRange.endExclusive),
    ),
) {
    /** Immutable source clusters directly related to [shapedGlyph]. */
    public val sourceClusters: List<ShaperCluster> = sourceClusters.immutableListSnapshot()

    /** Complete source range mapped by the clusters of this glyph. */
    public val mappedSourceRange: TextRange =
        TextRange(this.sourceClusters.first().sourceRange.start, this.sourceClusters.last().sourceRange.endExclusive)

    /** Typed provenance of this final glyph. */
    public val provenance: GlyphProvenance = provenance.also {
        when (it) {
            is GlyphProvenance.Direct -> require(it.sourceRange == mappedSourceRange) {
                "A direct glyph must name its complete mapped source range."
            }
            is GlyphProvenance.Derived -> require(it.sourceRange == mappedSourceRange) {
                "A derived glyph must name exactly its transformed source range."
            }
            is GlyphProvenance.Synthetic -> requireSyntheticAnchor(it)
        }
    }

    private fun requireSyntheticAnchor(synthetic: GlyphProvenance.Synthetic) {
        require(this.sourceClusters.size == 1) {
            "A synthetic glyph must anchor at exactly one mapped source cluster."
        }
        val mapped = this.sourceClusters.single().sourceRange
        require(
            mapped.start.sharesVersionWith(synthetic.anchor) &&
                synthetic.anchor >= mapped.start &&
                synthetic.anchor <= mapped.endExclusive,
        ) {
            "A synthetic glyph must anchor at a real boundary of its mapped source cluster."
        }
    }

    init {
        require(this.sourceClusters.map(ShaperCluster::token) == shapedGlyph.clusterTokens) {
            "Positioned glyph clusters must match the shaped glyph relation exactly."
        }
        require(advance == LayoutVector(shapedGlyph.xAdvance, shapedGlyph.yAdvance)) {
            "A positioned glyph must preserve its shaped horizontal and vertical advances exactly."
        }
        require(materializationCertificate == null || materializationCertificate.glyphId == shapedGlyph.glyphId) {
            "A glyph materialization certificate must name the positioned glyph."
        }
        require((renderAssetKey == null) == (materializationCertificate == null)) {
            "A positioned glyph must carry both its render asset key and certificate, or neither."
        }
        require(materializationCertificate == null || materializationCertificate.assetKey == renderAssetKey) {
            "A positioned glyph certificate must use its exact render asset key."
        }
    }
}

/**
 * One shaped run after final placement in physical visual order.
 *
 * The run retains its relative [sourceRun] and does not reinterpret source indexes. Its glyphs
 * carry their final origins, advances, and direct text-to-cluster-to-glyph relationships.
 */
public class PositionedGlyphRun(
    /** Relative shaping output that this positioned run preserves. */
    public val sourceRun: ShapedGlyphRun,
    /** Zero-based visual order of this run in its line. */
    public val visualOrder: Int,
    /** Exact render asset key shared by every glyph in renderable mode, otherwise `null`. */
    public val renderAssetKey: FontRenderAssetKey?,
    glyphs: List<PositionedGlyph>,
) {
    /** Exact font instance that shaped every glyph in this positioned run. */
    public val fontInstanceKey: FontInstanceKey
        get() = sourceRun.fontInstanceKey

    /** Immutable final glyphs in this run's produced visual glyph order. */
    public val glyphs: List<PositionedGlyph> = glyphs.immutableListSnapshot()

    /** Final glyphs whose provenance is [GlyphProvenance.Direct] or [GlyphProvenance.Derived]. */
    private val sourceGlyphs: List<PositionedGlyph>
        get() = this.glyphs.filter { glyph -> glyph.provenance !is GlyphProvenance.Synthetic }

    init {
        require(visualOrder >= 0) { "Positioned run visual order must be non-negative." }
        require(this.glyphs.size >= sourceRun.glyphs.size) {
            "Positioned runs must contain one final placement per shaped glyph."
        }
        val sourceTokenSequence = sourceRun.glyphs.map { glyph -> glyph.clusterTokens }
        var sourceCursor = 0
        this.sourceGlyphs.forEach { glyph ->
            val tokens = glyph.shapedGlyph.clusterTokens
            if (sourceCursor < sourceTokenSequence.size && sourceTokenSequence[sourceCursor] == tokens) {
                sourceCursor += 1
            }
        }
        require(sourceCursor == sourceTokenSequence.size) {
            "Direct and derived positioned glyphs must preserve the shaped run glyph order and cluster relations exactly."
        }
        require(this.glyphs.all { glyph ->
            val expectedClusters = glyph.shapedGlyph.clusterTokens.map { token ->
                sourceRun.clusters.single { cluster -> cluster.token == token }
            }
            glyph.sourceClusters == expectedClusters
        }) {
            "Positioned glyph relations must retain the exact source clusters owned by the shaped run."
        }
        require(renderAssetKey == null || renderAssetKey.fontInstanceKey == sourceRun.fontInstanceKey) {
            "A positioned run render asset must use the shaped run font instance."
        }
        require(this.glyphs.all { it.renderAssetKey == renderAssetKey }) {
            "Every positioned glyph must use exactly its positioned run render asset key."
        }
    }
}

/** Severity of a deterministic editable-line diagnostic. */
public enum class EditableLineDiagnosticSeverity {
    /** A recoverable fallback or degraded font fact was applied deterministically. */
    WARNING,

    /** The line could not be published. */
    ERROR,
}

/** Structured diagnostic emitted while positioning one editable line. */
public data class EditableLineDiagnostic(
    /** Stable machine-readable diagnostic code. */
    public val code: String,
    /** Severity of this diagnostic. */
    public val severity: EditableLineDiagnosticSeverity,
    /** Human-readable, stable explanation of the observed condition. */
    public val message: String,
    /** Related snapshot-bound text range when the condition has a source span. */
    public val sourceRange: TextRange? = null,
    /** Related final glyph when the condition has one. */
    public val glyphId: GlyphId? = null,
) {
    init {
        require(code.isNotBlank()) { "Editable-line diagnostic codes must not be blank." }
        require(message.isNotBlank()) { "Editable-line diagnostic messages must not be blank." }
    }
}

/**
 * Explicit handling of `U+00AD SOFT HYPHEN` scalars for one finalized line.
 *
 * A soft hyphen is a real source scalar but the source publishes no visible
 * hyphen: a line either materializes a visible hyphen-in-disguise at a
 * selected soft-hyphen break boundary, or suppresses the scalar entirely.
 * [materializedBoundaries] names the snapshot boundary of each soft hyphen
 * whose visible hyphen is published; every other soft hyphen in the line is
 * suppressed. A suppressed soft hyphen keeps its real scalar position and its
 * caret boundaries, and publishes a zero-advance Direct-provenance glyph.
 * This value owns no resource and is safe to share between threads.
 */
public class SoftHyphenLinePolicy(
    /**
     * Visible-hyphen boundaries in logical source order.
     *
     * Each boundary must be a snapshot boundary immediately following a
     * soft-hyphen scalar; the request that uses this policy validates the
     * boundaries against its own source snapshot and cluster partition.
     */
    materializedBoundaries: List<TextIndex>,
) {
    /** Immutable visible-hyphen boundaries in logical source order. */
    public val materializedBoundaries: List<TextIndex> = materializedBoundaries.immutableListSnapshot()

    init {
        require(this.materializedBoundaries.zipWithNext().all { (left, right) -> left.compareTo(right) < 0 }) {
            "Soft-hyphen materialized boundaries must be strictly ordered."
        }
    }
}

/**
 * Ellipsis truncation applied to one finalized line.
 *
 * The line keeps its complete source range and cluster partition: scalars in
 * [hiddenRange] publish zero-advance suppressed glyphs, and exactly one
 * synthetic ellipsis marker glyph is anchored at the truncation boundary
 * ([hiddenRange.start] for [EllipsisSide.INLINE_END] and [EllipsisSide.MIDDLE],
 * [hiddenRange.endExclusive] for [EllipsisSide.INLINE_START]).
 */
public class LineEllipsisPolicy(
    /** Side at which the synthetic ellipsis marker is anchored. */
    public val side: EllipsisSide,
    /** Exact source range hidden by this truncation. */
    public val hiddenRange: TextRange,
) {
    init {
        require(hiddenRange.start.sharesVersionWith(hiddenRange.endExclusive)) {
            "Ellipsis hidden range must use one text revision."
        }
    }
}

/**
 * Automatic hyphenation breaks materialized with a visible hyphen on one line.
 *
 * Every boundary names a real snapshot boundary inside a word at which an
 * automatic service produced a break; the visible hyphen is synthetic content
 * anchored at that boundary. No boundary creates a document position.
 */
public class AutomaticHyphenBreaks(
    /** Materialized automatic-break boundaries in logical source order. */
    materializedBoundaries: List<TextIndex>,
) {
    /** Immutable automatic-break boundaries in logical source order. */
    public val materializedBoundaries: List<TextIndex> = materializedBoundaries.immutableListSnapshot()

    init {
        require(this.materializedBoundaries.zipWithNext().all { (left, right) -> left.compareTo(right) < 0 }) {
            "Automatic hyphenation materialized boundaries must be strictly ordered."
        }
    }
}

/** Typed reason an editable line could not be published. */
public sealed interface EditableLineError {
    /** Stable machine-readable error code. */
    public val code: String

    /** Human-readable error explanation. */
    public val message: String

    /** Invalid or incompatible portable line inputs. */
    public data class InvalidInput(
        override val message: String,
    ) : EditableLineError {
        override val code: String = "layout.invalid-editable-line-input"
    }

    /** A finite public layout coordinate could not be produced. */
    public data class GeometryOverflow(
        override val message: String,
    ) : EditableLineError {
        override val code: String = "layout.geometry-overflow"
    }

    /** A borrowed font asset failed while validating the requested renderable route. */
    public data class FontMaterializationFailure(
        /** Underlying typed font failure. */
        public val fontError: FontError,
    ) : EditableLineError {
        override val code: String = "layout.font-materialization-failure"
        override val message: String = fontError.message
    }

    /** The JVM reference shaper could not open or produce a complete relative glyph run. */
    public data class ShapingFailure(
        /** Underlying typed font or native-shaping failure. */
        public val fontError: FontError,
    ) : EditableLineError {
        override val code: String = "layout.shaping-failure"
        override val message: String = fontError.message
    }

    /** Every deterministic candidate was exhausted before a complete line could be published. */
    public data class FontResolutionFailure(
        /** Underlying typed fallback-resolution failure. */
        public val fontError: FontError,
    ) : EditableLineError {
        override val code: String = "layout.font-resolution-failure"
        override val message: String = fontError.message
    }
}

/** Result of synchronously positioning and optionally certifying one editable line. */
public sealed interface EditableLineResult {
    /** Fully positioned line with immutable diagnostics. */
    public class Success(
        /** Published editable line. */
        public val line: EditableLine,
    ) : EditableLineResult

    /** No line was published because a typed failure occurred. */
    public class Failure(
        /** Typed failure that prevented publication. */
        public val error: EditableLineError,
        diagnostics: List<EditableLineDiagnostic>,
    ) : EditableLineResult {
        /** Immutable diagnostics produced before failure. */
        public val diagnostics: List<EditableLineDiagnostic> = diagnostics.immutableListSnapshot()
    }

    /** No line was published because synchronous font work observed cancellation. */
    public class Cancelled(
        diagnostics: List<EditableLineDiagnostic> = emptyList(),
    ) : EditableLineResult {
        /** Immutable diagnostics produced before cancellation. */
        public val diagnostics: List<EditableLineDiagnostic> = diagnostics.immutableListSnapshot()
    }
}

/**
 * Selects whether a line is positioned only or synchronously proven renderable as outlines.
 *
 * [Renderable] borrows its resolver. The layouter neither closes nor retains that resolver;
 * callers retain ownership and must keep it open for the duration of the synchronous call.
 */
public sealed interface EditableLineMaterialization {
    /** Position geometry, carets, selection, and hit-testing without acquiring a font asset. */
    public data object LayoutOnly : EditableLineMaterialization

    /** Acquire a temporary render asset and validate every final glyph through the outline route. */
    public class Renderable(
        /** Borrowed resolver used only during the synchronous layout call. */
        public val resolver: FontAssetResolverHandle,
        /** Explicit font render variant to validate. */
        public val variant: FontRenderVariantKey,
        /** Bounded outline profile required for every non-empty final glyph. */
        public val outlineProfile: OutlineProfile,
    ) : EditableLineMaterialization
}

/**
 * Complete input for resolving and laying out one horizontal editable line from multiple fonts.
 *
 * The request captures one immutable catalogue generation, a policy bound to that generation,
 * and an explicit portable shaping backend. The line resolver derives indivisible fallback units
 * from [unicodeAnalysis], never mixes fonts within one unit, and produces a normal
 * [EditableLineResult]. In renderable mode [materialization] must carry a live resolver for the
 * same generation; the caller owns and closes that resolver. This value itself owns no resource
 * and is safe to share concurrently while all referenced snapshots remain immutable.
 */
public class MultiFontEditableLineRequest(
    /** Immutable source snapshot analyzed by [unicodeAnalysis]. */
    public val snapshot: TextSnapshot,
    /** Complete immutable Unicode analysis for the line's snapshot revision. */
    public val unicodeAnalysis: UnicodeAnalysis,
    /** Captured catalogue used for face records and face resolution. */
    public val fontCatalog: FontCatalogSnapshot,
    /** Immutable total-order policy for this catalogue generation. */
    public val resolutionPolicy: FontResolutionPolicySnapshot,
    /** Geometric instance parameters applied to every selected face. */
    public val fontInstanceDescriptor: FontInstanceDescriptor,
    /** Backend used for provisional and final shaping attempts. */
    public val shapingBackend: ShapingBackend,
    /** Explicit paragraph base direction. */
    public val baseDirection: BaseDirection,
    /** Explicit line-box metrics supplied by the consumer. */
    public val verticalMetrics: LineVerticalMetrics,
    /** Explicit layout-only or outline-renderable publication mode. */
    public val materialization: EditableLineMaterialization,
    features: List<OpenTypeFeature> = emptyList(),
    /** Cooperative cancellation signal observed between bounded fallback attempts. */
    public val cancellationToken: CancellationToken = CancellationToken.none,
) {
    /** Immutable deterministic OpenType feature overrides. */
    public val features: List<OpenTypeFeature> = features.immutableListSnapshot()

    init {
        require(snapshot.range == unicodeAnalysis.range) {
            "Font fallback source snapshot and Unicode analysis must cover the same range."
        }
        require(fontCatalog.generation == resolutionPolicy.generation) {
            "Font catalog and resolution policy must use the same generation."
        }
        require(resolutionPolicy.candidates.all { candidate -> candidate.faceId in fontCatalog.faces.map(FontFaceRecord::id) }) {
            "Every resolution policy candidate must belong to the captured font catalog."
        }
        if (materialization is EditableLineMaterialization.Renderable) {
            require(materialization.resolver.generation == fontCatalog.generation) {
                "Renderable materialization resolver must belong to the captured font catalog generation."
            }
        }
        require(features.map(OpenTypeFeature::tag).distinct().size == features.size) {
            "OpenType features must not repeat a tag."
        }
    }
}

/**
 * Complete portable input to one horizontal, non-wrapped editable-line operation.
 *
 * The analysis must cover its entire snapshot range. Runs must be contiguous in logical source
 * order, preserve exactly the analyzed extended-grapheme partition, have zero vertical advance,
 * and use a key declared in [fontInstances]. Their direction, level, script, language, and the line's
 * [baseDirection] stay explicit rather than inferred from text or a left-to-right default. An
 * empty line needs no font instance because it has no glyph or render-asset route. The
 * request retains no resource except the borrowed resolver named by [materialization]. Contract
 * incompatibilities are programming errors reported by construction preconditions.
 */
public class EditableLineRequest(
    /** Complete immutable Unicode analysis for the line's snapshot revision. */
    public val unicodeAnalysis: UnicodeAnalysis,
    shapedGlyphRuns: List<ShapedGlyphRun>,
    /** Explicit base direction used to classify strong and weak BiDi caret candidates. */
    public val baseDirection: ShapingDirection,
    /** Explicit UAX #9 level paired with [baseDirection] for an empty line. */
    public val emptyLineBidiLevel: Int? = null,
    /**
     * Compatibility primary instance for a non-empty line; it must also appear in [fontInstances].
     *
     * Empty lines may omit it because they publish no glyphs, routes, or certificates.
     */
    public val font: FontInstance? = null,
    fontInstances: List<FontInstance> = listOfNotNull(font),
    /** Explicit line-box metrics supplied by the consumer. */
    public val verticalMetrics: LineVerticalMetrics,
    /** Explicit layout-only or outline-renderable publication mode. */
    public val materialization: EditableLineMaterialization,
    /**
     * Explicit soft-hyphen handling, or `null` for the legacy raw shaping of
     * `U+00AD` scalars.
     *
     * The paragraph route always supplies a policy; direct single-line callers
     * that previously shaped soft hyphens without policy get `null` and keep
     * the previous behavior.
     */
    public val softHyphenPolicy: SoftHyphenLinePolicy? = null,
    /**
     * Immutable source snapshot used by derived-content policies such as soft
     * hyphen handling, or `null` when no policy needs source scalars.
     *
     * A non-null [softHyphenPolicy] requires a snapshot; the pair is validated
     * during construction. The request owns no resource and keeps no more than
     * the immutable snapshot reference.
     */
    public val snapshot: TextSnapshot? = null,
    /** Explicit tab stops, alignment, and justification applied before final placement. */
    public val positioning: ParagraphPositioningPolicy? = null,
    /** Exact inline extent available to this line for alignment and justification spacing. */
    public val targetInlineExtent: LayoutUnit? = null,
    /** Whether this line is the final paragraph line, relaxing justification spacing. */
    public val isLastLine: Boolean = false,
    /** Automatic hyphenation boundaries materialized with a visible hyphen on this line. */
    public val automaticHyphenBreaks: AutomaticHyphenBreaks? = null,    /** Ellipsis truncation applied to this finalized line, or `null` when none applies. */
    public val ellipsis: LineEllipsisPolicy? = null,    /** Definitions bound to `U+FFFC` object replacement scalars inside this line. */
    public val inlineObjects: InlineObjectSnapshot? = null,
    /** Cooperative cancellation signal observed only during renderable font materialization. */
    public val cancellationToken: CancellationToken = CancellationToken.none,
) {
    /** Immutable shaped runs in contiguous logical source order. */
    public val shapedGlyphRuns: List<ShapedGlyphRun> = shapedGlyphRuns.immutableListSnapshot()

    /** Immutable instances used by the shaped runs in this request. */
    public val fontInstances: List<FontInstance> = fontInstances.immutableListSnapshot()

    init {
        require(this.shapedGlyphRuns.isNotEmpty() || unicodeAnalysis.range.start == unicodeAnalysis.range.endExclusive) {
            "A non-empty editable line requires shaped glyph runs."
        }
        if (this.shapedGlyphRuns.isEmpty()) {
            require(emptyLineBidiLevel != null) {
                "An empty editable line requires an explicit BiDi level."
            }
            require(baseDirection.matchesBidiLevel(emptyLineBidiLevel)) {
                "Empty line base direction must agree with its explicit BiDi level."
            }
        } else {
            require(emptyLineBidiLevel == null) {
                "An explicit empty-line BiDi level is valid only for an empty line."
            }
        }
        requireContiguousRunPartition(unicodeAnalysis.range, this.shapedGlyphRuns)
        require(this.fontInstances.map(FontInstance::key).distinct().size == this.fontInstances.size) {
            "Editable line font instances must not repeat a key."
        }
        if (this.shapedGlyphRuns.isNotEmpty()) {
            require(font != null) {
                "A non-empty editable line requires a compatibility primary font instance."
            }
            require(font.key in this.fontInstances.map(FontInstance::key)) {
                "The compatibility primary font instance must appear in the request font instances."
            }
        }
        require(this.shapedGlyphRuns.all { run -> run.fontInstanceKey in this.fontInstances.map(FontInstance::key) }) {
            "Every shaped run must use one request font instance key."
        }
        require(this.shapedGlyphRuns.all { run -> run.glyphs.all { glyph -> glyph.yAdvance.value == 0f } }) {
            "Horizontal editable lines reject non-zero vertical glyph advances."
        }
        require(softHyphenPolicy == null || snapshot != null) {
            "Soft-hyphen handling requires the line source snapshot."
        }
        require(softHyphenPolicy == null || snapshotContainsRange(snapshot!!, unicodeAnalysis.range)) {
            "Soft-hyphen source snapshot must contain the line analysis range."
        }
        softHyphenPolicy?.materializedBoundaries?.forEach { boundary ->
            require(boundary.sharesVersionWith(unicodeAnalysis.range.start)) {
                "Soft-hyphen materialized boundaries must use the line source revision."
            }
        }
        require(this.shapedGlyphRuns.all { run -> unicodeAnalysis.logicalBidiRuns.any { bidi ->
            containsRange(bidi.range, run.range) && bidi.level == run.bidiLevel
        } }) {
            "Every shaped run must stay within one analyzed logical BiDi run of the same level."
        }
        require(this.shapedGlyphRuns.all { run -> scriptContextSupports(run, unicodeAnalysis.scriptLanguageRuns) }) {
            "Every shaped run must use one analyzed script-language context, allowing only Common and Inherited scalars within an indivisible grapheme relation."
        }
        require(this.shapedGlyphRuns.all { run ->
            val analyzedPartition = graphemeFragments(run.range, unicodeAnalysis.graphemeClusters)
            run.graphemeClusters == analyzedPartition
        }) {
            "Every shaped run must preserve the analyzed grapheme-fragment partition restricted to its source range."
        }
    }
}

private fun scriptContextSupports(run: ShapedGlyphRun, scripts: List<ScriptLanguageRun>): Boolean {
    val intersecting = scripts.filter { script -> overlapsRange(script.range, run.range) }
    return intersecting.isNotEmpty() && intersecting.all { script ->
        script.language == run.language &&
            (script.script == run.script.value || script.script == COMMON_SCRIPT || script.script == INHERITED_SCRIPT)
    }
}

private const val COMMON_SCRIPT: String = "Zyyy"
private const val INHERITED_SCRIPT: String = "Zinh"

/** Portable contract implemented by a pure editable-line layout module. */
public interface EditableLineLayouter {
    /**
     * Positions [request] into one complete horizontal editable line.
     *
     * A successful result is immutable and contains no font handle. In renderable mode the
     * operation temporarily acquires and always closes a render asset; it returns a typed
     * failure or cancellation without publishing a partial line if any final glyph cannot be
     * certified through [GlyphMaterializationRoute.OUTLINE] or [GlyphMaterializationRoute.EMPTY].
     */
    public fun layout(request: EditableLineRequest): EditableLineResult
}

/**
 * Immutable editable horizontal line and its exact editing operations.
 *
 * Glyph positions are relative to baseline `(0, 0)` in physical `x`-right/`y`-down coordinates.
 * The line is thread-safe because it owns immutable snapshots only and retains no font asset,
 * renderer object, platform object, or mutable text storage.
 */
public class EditableLine(
    /** Complete snapshot-bound source range covered by this line. */
    public val range: TextRange,
    /** Explicit base direction used to classify its concrete BiDi caret candidates. */
    public val baseDirection: ShapingDirection,
    /** Explicit line-box metrics used by carets and selections. */
    public val verticalMetrics: LineVerticalMetrics,
    positionedGlyphRuns: List<PositionedGlyphRun>,
    caretCandidates: List<CaretCandidate>,
    inlineObjects: List<PositionedInlineObject> = emptyList(),
    diagnostics: List<EditableLineDiagnostic> = emptyList(),
) {
    /** Positioned shaped runs in physical visual order. */
    public val positionedGlyphRuns: List<PositionedGlyphRun> = positionedGlyphRuns.immutableListSnapshot()

    /** Concrete caret geometries in physical visual traversal order. */
    public val allCaretCandidates: List<CaretCandidate> = caretCandidates.immutableListSnapshot()

    /** Immutable positioned inline objects in logical source order. */
    public val positionedInlineObjects: List<PositionedInlineObject> = inlineObjects.immutableListSnapshot()

    /** Immutable recoverable diagnostics emitted while positioning this line. */
    public val diagnostics: List<EditableLineDiagnostic> = diagnostics.immutableListSnapshot()

    init {
        require(this.positionedGlyphRuns.map(PositionedGlyphRun::visualOrder) == this.positionedGlyphRuns.indices.toList()) {
            "Positioned glyph runs must use contiguous visual order."
        }
        require(this.positionedGlyphRuns.all { containsRange(range, it.sourceRun.range) }) {
            "Positioned glyph runs must stay within the editable line range."
        }
        requireContiguousPositionedRunPartition(range, this.positionedGlyphRuns)
        require(this.positionedInlineObjects.zipWithNext().all { (left, right) -> left.sourceRange.start < right.sourceRange.start }) {
            "Positioned inline objects must be ordered by logical source range."
        }
        require(this.positionedInlineObjects.all { placedObject ->
            containsRange(range, placedObject.sourceRange) &&
                placedObject.sourceRange.start < placedObject.sourceRange.endExclusive
        }) { "Positioned inline objects must stay within the editable line range." }
        require(this.allCaretCandidates.isNotEmpty()) { "Editable lines must publish at least one caret candidate." }
        require(this.allCaretCandidates.map(CaretCandidate::visualOrder) == this.allCaretCandidates.indices.toList()) {
            "Caret candidates must use contiguous visual order."
        }
        require(this.allCaretCandidates.zipWithNext().all { (left, right) -> compareCaretVisualPosition(left, right) <= 0 }) {
            "Caret candidates must be published in deterministic physical visual order."
        }
        val expectedCaretTop = LayoutUnit(-verticalMetrics.ascent.value)
        val expectedCaretBottom = verticalMetrics.descent
        require(this.allCaretCandidates.all { candidate ->
            candidate.geometry.start.y == expectedCaretTop && candidate.geometry.end.y == expectedCaretBottom
        }) { "Every caret geometry must span exactly the editable line vertical metrics." }
        require(this.allCaretCandidates.all { candidate ->
            candidate.position.index.sharesVersionWith(range.start) &&
                candidate.position.index.compareTo(range.start) >= 0 &&
                candidate.position.index.compareTo(range.endExclusive) <= 0
        }) { "Caret candidates must stay within the editable line range." }
        require(this.allCaretCandidates.all { candidate ->
            candidate.strength == if (candidate.direction == baseDirection) CaretStrength.STRONG else CaretStrength.WEAK
        }) { "Caret strength must be derived from the editable line base direction." }
        if (this.positionedGlyphRuns.isEmpty()) {
            require(range.start == range.endExclusive) { "Only an empty source range may omit positioned runs." }
            require(this.allCaretCandidates.all { it.visualRunOrder == CaretCandidate.NO_POSITIONED_RUN }) {
                "Empty-line caret candidates must use the documented no-run sentinel."
            }
        } else {
            require(this.allCaretCandidates.all { candidate -> candidate.visualRunOrder in this.positionedGlyphRuns.indices }) {
                "Every caret candidate must identify an existing positioned run."
            }
            require(this.allCaretCandidates.all { candidate ->
                val run = this.positionedGlyphRuns[candidate.visualRunOrder].sourceRun
                candidate.bidiLevel == run.bidiLevel &&
                    candidate.direction == run.direction &&
                    candidate.position.index >= run.range.start &&
                    candidate.position.index <= run.range.endExclusive &&
                    when (candidate.edge) {
                        CaretBoundaryEdge.LOGICAL_START -> candidate.position.index == run.range.start
                        CaretBoundaryEdge.LOGICAL_END -> candidate.position.index == run.range.endExclusive
                        CaretBoundaryEdge.INTERNAL ->
                            candidate.position.index > run.range.start && candidate.position.index < run.range.endExclusive
                    }
            }) { "Caret candidates must agree with their owning positioned run and source edge." }
            require(this.allCaretCandidates.groupBy { it.visualRunOrder to it.position.index }.values.all { it.size == 1 }) {
                "A positioned run must not publish duplicate candidates for one source boundary."
            }
            val publishedBoundaries = this.allCaretCandidates.map { it.position.index }.toSet()
            require(this.positionedGlyphRuns.all { run ->
                endpointCandidateIsPublishedWhenLegal(
                    run = run,
                    edge = CaretBoundaryEdge.LOGICAL_START,
                    publishedBoundaries = publishedBoundaries,
                    candidates = this.allCaretCandidates,
                ) && endpointCandidateIsPublishedWhenLegal(
                    run = run,
                    edge = CaretBoundaryEdge.LOGICAL_END,
                    publishedBoundaries = publishedBoundaries,
                    candidates = this.allCaretCandidates,
                )
            }) {
                "Every positioned run must publish each of its legal logical endpoint candidates."
            }
        }
    }

    /** Returns all concrete geometries valid at [index], in physical visual order. */
    public fun caretCandidates(index: TextIndex): List<CaretCandidate> {
        require(index.sharesVersionWith(range.start)) { "Caret indexes must belong to the editable line version." }
        return allCaretCandidates.filter { it.position.index == index }.immutableListSnapshot()
    }

    /**
     * Moves [position] to the next permitted text boundary in decoded scalar order.
     *
     * Logical movement never follows physical visual order. It returns `null` at the requested
     * edge and rejects positions that do not belong to this line.
     */
    public fun nextLogical(
        position: CaretPosition,
        direction: LogicalNavigationDirection,
    ): CaretPosition? {
        require(allCaretCandidates.any { it.position == position }) { "Logical navigation requires a line-local caret position." }
        val boundaries = allCaretCandidates.map { it.position.index }.distinct().sortedWith(TextIndex::compareTo)
        val current = boundaries.indexOf(position.index)
        val target = when (direction) {
            LogicalNavigationDirection.FORWARD -> boundaries.getOrNull(current + 1)
            LogicalNavigationDirection.BACKWARD -> boundaries.getOrNull(current - 1)
        } ?: return null
        return allCaretCandidates.firstOrNull { it.position.index == target && it.position.affinity == position.affinity }?.position
            ?: allCaretCandidates.first { it.position.index == target }.position
    }

    /**
     * Moves [candidate] to the next concrete geometry in physical visual order.
     *
     * The candidate must be the exact object returned by this line; candidates cannot be reused
     * across layouts even when their logical index happens to compare equal.
     */
    public fun nextVisual(
        candidate: CaretCandidate,
        direction: VisualNavigationDirection,
    ): CaretCandidate? {
        val current = allCaretCandidates.indexOfFirst { it === candidate }
        require(current >= 0) { "Visual navigation requires a candidate returned by this editable line." }
        return when (direction) {
            VisualNavigationDirection.FORWARD -> allCaretCandidates.getOrNull(current + 1)
            VisualNavigationDirection.BACKWARD -> allCaretCandidates.getOrNull(current - 1)
        }
    }

    /**
     * Finds the closest concrete caret to [point] using the normative deterministic tie-break.
     *
     * Candidates are compared by distance to their vertical segment, then visual order, then
     * logical [TextIndex], then [CaretAffinity.DOWNSTREAM] before [CaretAffinity.UPSTREAM].
     */
    public fun hitTest(point: LayoutPoint): CaretCandidate =
        allCaretCandidates.minWith { left, right ->
            val distance = squaredDistanceToSegment(point, left.geometry).compareTo(squaredDistanceToSegment(point, right.geometry))
            if (distance != 0) return@minWith distance
            val visual = left.visualOrder.compareTo(right.visualOrder)
            if (visual != 0) return@minWith visual
            val index = left.position.index.compareTo(right.position.index)
            if (index != 0) return@minWith index
            affinityRank(left.position.affinity).compareTo(affinityRank(right.position.affinity))
        }

    /**
     * Returns non-empty selection rectangles in physical visual-run order.
     *
     * [anchor] and [focus] must be valid line-local caret positions. Geometry uses line metrics,
     * legal caret boundaries, and the signed glyph pen path when a run has no two legal caret
     * boundaries. It never queries glyph ink bounds or renderer state.
     */
    public fun selectionGeometry(anchor: CaretPosition, focus: CaretPosition): List<LayoutRect> {
        require(allCaretCandidates.any { it.position == anchor }) { "Selection anchor must be a line-local caret position." }
        require(allCaretCandidates.any { it.position == focus }) { "Selection focus must be a line-local caret position." }
        if (anchor.index == focus.index) return emptyList()
        val start = if (anchor.index < focus.index) anchor.index else focus.index
        val endExclusive = if (anchor.index < focus.index) focus.index else anchor.index
        return positionedGlyphRuns.mapNotNull { run ->
            val selectedStart = maxIndex(start, run.sourceRun.range.start)
            val selectedEnd = minIndex(endExclusive, run.sourceRun.range.endExclusive)
            if (selectedStart >= selectedEnd) return@mapNotNull null
            val caretCoordinates = allCaretCandidates.asSequence()
                .filter { candidate ->
                    candidate.visualRunOrder == run.visualOrder &&
                        candidate.position.index >= selectedStart &&
                        candidate.position.index <= selectedEnd
                }
                .map { candidate -> candidate.geometry.start.x }
                .toList()
            val coordinates = if (caretCoordinates.size >= 2) {
                caretCoordinates
            } else {
                caretCoordinates + run.glyphs.flatMap { glyph ->
                    listOf(glyph.origin.x, LayoutUnit(glyph.origin.x.value + glyph.advance.x.value))
                }
            }
            val left = coordinates.minOrNull() ?: return@mapNotNull null
            val right = coordinates.maxOrNull() ?: return@mapNotNull null
            if (left == right) null else LayoutRect(left, lineTop(), right, lineBottom())
        }.immutableListSnapshot()
    }

    private fun lineTop(): LayoutUnit = LayoutUnit(-verticalMetrics.ascent.value)

    private fun lineBottom(): LayoutUnit = verticalMetrics.descent
}

private fun snapshotContainsRange(snapshot: TextSnapshot, range: TextRange): Boolean =
    range.start.sharesVersionWith(snapshot.range.start) &&
        range.start >= snapshot.range.start &&
        range.endExclusive <= snapshot.range.endExclusive

private fun requireContiguousRunPartition(range: TextRange, runs: List<ShapedGlyphRun>) {    if (range.start == range.endExclusive) {
        require(runs.isEmpty()) { "An empty editable line must not contain shaped runs." }
        return
    }
    require(runs.isNotEmpty()) { "A non-empty editable line requires shaped runs." }
    var next = range.start
    runs.forEach { run ->
        require(run.range.start == next) { "Shaped runs must be contiguous in logical source order." }
        require(run.range.endExclusive <= range.endExclusive) { "Shaped runs must stay within the analysis range." }
        next = run.range.endExclusive
    }
    require(next == range.endExclusive) { "Shaped runs must cover the complete analysis range." }
}

private fun requireContiguousPositionedRunPartition(range: TextRange, runs: List<PositionedGlyphRun>) {
    if (range.start == range.endExclusive) {
        require(runs.isEmpty()) { "An empty editable line must not contain positioned runs." }
        return
    }
    val logicalRuns = runs.sortedWith { left, right -> left.sourceRun.range.start.compareTo(right.sourceRun.range.start) }
    require(logicalRuns.isNotEmpty()) { "A non-empty editable line requires positioned runs." }
    var next = range.start
    logicalRuns.forEach { run ->
        require(run.sourceRun.range.start == next) {
            "Positioned runs must form one complete, non-overlapping logical source partition."
        }
        next = run.sourceRun.range.endExclusive
    }
    require(next == range.endExclusive) {
        "Positioned runs must cover the complete editable line source range."
    }
}

private fun containsRange(owner: TextRange, item: TextRange): Boolean =
    item.start.sharesVersionWith(owner.start) &&
        item.start >= owner.start &&
        item.endExclusive <= owner.endExclusive

private fun overlapsRange(left: TextRange, right: TextRange): Boolean =
    left.start.sharesVersionWith(right.start) &&
        left.start < right.endExclusive &&
        right.start < left.endExclusive

private fun graphemeFragments(range: TextRange, clusters: List<TextRange>): List<TextRange> =
    clusters.mapNotNull { cluster ->
        val start = maxIndex(range.start, cluster.start)
        val end = minIndex(range.endExclusive, cluster.endExclusive)
        if (start < end) TextRange(start, end) else null
    }

private fun endpointCandidateIsPublishedWhenLegal(
    run: PositionedGlyphRun,
    edge: CaretBoundaryEdge,
    publishedBoundaries: Set<TextIndex>,
    candidates: List<CaretCandidate>,
): Boolean {
    val index = when (edge) {
        CaretBoundaryEdge.LOGICAL_START -> run.sourceRun.range.start
        CaretBoundaryEdge.LOGICAL_END -> run.sourceRun.range.endExclusive
        CaretBoundaryEdge.INTERNAL -> error("Only positioned-run endpoints are supported.")
    }
    return index !in publishedBoundaries || candidates.any { candidate ->
        candidate.visualRunOrder == run.visualOrder && candidate.edge == edge
    }
}

private fun ShapingDirection.matchesBidiLevel(level: Int): Boolean =
    when (this) {
        ShapingDirection.LEFT_TO_RIGHT -> level % 2 == 0
        ShapingDirection.RIGHT_TO_LEFT -> level % 2 != 0
    }

private fun affinityRank(affinity: CaretAffinity): Int = when (affinity) {
    CaretAffinity.DOWNSTREAM -> 0
    CaretAffinity.UPSTREAM -> 1
}

private fun compareCaretVisualPosition(left: CaretCandidate, right: CaretCandidate): Int {
    val x = left.geometry.start.x.compareTo(right.geometry.start.x)
    if (x != 0) return x
    val run = left.visualRunOrder.compareTo(right.visualRunOrder)
    if (run != 0) return run
    val index = left.position.index.compareTo(right.position.index)
    if (index != 0) return index
    return affinityRank(left.position.affinity).compareTo(affinityRank(right.position.affinity))
}

private fun squaredDistanceToSegment(point: LayoutPoint, segment: LayoutSegment): Double {
    val xDistance = point.x.value.toDouble() - segment.start.x.value.toDouble()
    val vertical = when {
        point.y < segment.start.y -> segment.start.y.value.toDouble() - point.y.value.toDouble()
        point.y > segment.end.y -> point.y.value.toDouble() - segment.end.y.value.toDouble()
        else -> 0.0
    }
    return xDistance * xDistance + vertical * vertical
}

private fun maxIndex(first: TextIndex, second: TextIndex): TextIndex = if (first >= second) first else second

private fun minIndex(first: TextIndex, second: TextIndex): TextIndex = if (first <= second) first else second

package org.graphiks.kalligraphie.api

/** Physical progression of lines and glyphs in a paragraph. */
public enum class WritingMode {
    /** Lines progress downward and their inline content progresses from left to right or right to left. */
    HORIZONTAL_TB,

    /** Glyphs progress from top to bottom and successive lines progress towards physical left. */
    VERTICAL_RL,

    /** Glyphs progress from top to bottom and successive lines progress towards physical right. */
    VERTICAL_LR,
}

/**
 * Orientation policy for glyphs in a vertical writing mode.
 *
 * [MIXED] applies the versioned Unicode `Vertical_Orientation` default to each extended
 * grapheme cluster. The explicit alternatives override that default for every cluster in the
 * paragraph. This policy has no geometric effect in [WritingMode.HORIZONTAL_TB].
 */
public enum class TextOrientation {
    /** Use the Unicode default: CJK is normally upright while ordinary Latin text is sideways. */
    MIXED,

    /** Keep every cluster upright. */
    UPRIGHT,

    /** Rotate every cluster clockwise. */
    SIDEWAYS,
}

/** Policy used when a selected font has no usable OpenType vertical metrics. */
public enum class VerticalMetricsPolicy {
    /** Reject vertical composition unless every final glyph has metrics from `vhea` and `vmtx`. */
    REQUIRE_FONT_METRICS,

    /** Use the versioned portable one-em synthesis and publish a diagnostic for the affected font. */
    SYNTHESIZE_IF_UNAVAILABLE,
}

/**
 * Physical constraints for composing one paragraph in a rectangular region.
 *
 * Coordinates use the portable `x`-right, `y`-down paragraph space. [region] must have
 * strictly positive width and height, and [lineMetrics] fixes the compatible line-box rhythm
 * across the inline baseline. [writingMode] determines how that logical rhythm is projected into
 * the physical region. This immutable value owns no renderer or platform resource and is safe to
 * share concurrently.
 */
public open class ParagraphConstraints(
    /** Finite, non-empty physical region available to the paragraph. */
    public val region: LayoutRect,
    /** Explicit vertical metrics used for every line box in this region. */
    public val lineMetrics: LineVerticalMetrics,
    /** Logical writing mode used to project inline and block axes into [region]. */
    public val writingMode: WritingMode = WritingMode.HORIZONTAL_TB,
) {
    /** Exact physical width used when validating a continuation. */
    public val width: LayoutUnit = LayoutUnit(region.right.value - region.left.value)

    /** Exact physical height available for complete line boxes. */
    public val height: LayoutUnit = LayoutUnit(region.bottom.value - region.top.value)

    init {
        require(region.left < region.right) { "A horizontal paragraph region must have positive width." }
        require(region.top < region.bottom) { "A horizontal paragraph region must have positive height." }
    }

    /** Compares the physical region and line rhythm. */
    override fun equals(other: Any?): Boolean =
        other is ParagraphConstraints &&
            region == other.region &&
            lineMetrics == other.lineMetrics &&
            writingMode == other.writingMode

    /** Returns a stable hash of the physical region and line rhythm. */
    override fun hashCode(): Int = 31 * (31 * region.hashCode() + lineMetrics.hashCode()) + writingMode.hashCode()

    /** Returns a diagnostic form containing the physical region and line rhythm. */
    override fun toString(): String =
        "ParagraphConstraints(region=$region, lineMetrics=$lineMetrics, writingMode=$writingMode)"
}

/**
 * Compatibility constraints for a horizontal top-to-bottom paragraph.
 *
 * New consumers should use [ParagraphConstraints] so their request states its writing mode
 * explicitly. This subtype preserves source compatibility for existing horizontal integrations.
 */
public class HorizontalParagraphConstraints(
    region: LayoutRect,
    lineMetrics: LineVerticalMetrics,
) : ParagraphConstraints(region, lineMetrics, WritingMode.HORIZONTAL_TB)

/** Side of the inline axis at which an ellipsis truncation is anchored. */
public enum class EllipsisSide {
    /** Anchor at the logical inline start of the truncated line. */
    INLINE_START,

    /** Anchor between two visible sequences, hiding the middle of the line. */
    MIDDLE,

    /** Anchor at the logical inline end of the truncated line. */
    INLINE_END,
}

/** Policy applied when complete source coverage does not fit in the supplied region. */
public sealed interface OverflowPolicy {
    /**
     * Publish complete lines only and return an exact immutable continuation
     * for the remainder when the region is exhausted. Truncation is never
     * applied in this mode: the published prefix plus the returned
     * [LayoutContinuation] partitions the complete requested source range.
     */
    public data object Continue : OverflowPolicy

    /**
     * Publish a truncated line whose hidden content keeps an explicit
     * relationship with its source range, plus a synthetic ellipsis marker.
     *
     * The marker is synthetic content anchored at a real snapshot boundary and
     * never creates a document position. Hidden scalars keep their real
     * bounds, caret positions, and cluster relations: visibility is a final
     * glyph property, not a text property.
     */
    public class Ellipsis(
        /** Side of the logical inline axis at which truncation is anchored. */
        public val side: EllipsisSide,
        /** Unicode scalar value of the ellipsis marker, normalized to `U+2026`. */
        public val marker: Int = 0x2026,
    ) : OverflowPolicy {
        init {
            require(marker == 0x2026) { "Ellipsis markers must use the Unicode horizontal ellipsis scalar." }
        }

        /** Compares the complete truncation behavior. */
        override fun equals(other: Any?): Boolean =
            other is Ellipsis && side == other.side && marker == other.marker

        /** Returns a stable hash of the complete truncation behavior. */
        override fun hashCode(): Int = 31 * side.hashCode() + marker

        /** Returns a diagnostic form containing the complete truncation behavior. */
        override fun toString(): String = "OverflowPolicy.Ellipsis(side=$side, marker=$marker)"
    }
}

/**
 * Typographic extents derived from the actual content of one final line.
 *
 * These distances are independent of the composition [LineLayout.lineBox] and glyph
 * [LineLayout.designInkBounds]. They contain no device rounding or rasterization state.
 */
public data class LineContentMetrics(
    /** Non-negative content extent above the final baseline. */
    public val ascent: LayoutUnit,
    /** Non-negative content extent below the final baseline. */
    public val descent: LayoutUnit,
    /** Non-negative physical inline advance occupied by final positioned content. */
    public val inlineAdvance: LayoutUnit,
) {
    init {
        require(ascent.value >= 0f) { "Content ascent must be non-negative." }
        require(descent.value >= 0f) { "Content descent must be non-negative." }
        require(inlineAdvance.value >= 0f) { "Content inline advance must be non-negative." }
    }
}

/**
 * One complete final line projected into paragraph coordinates.
 *
 * [line] supplies immutable line-local glyphs and carets relative to baseline `(0, 0)`.
 * Construction snapshots and translates those values by [baseline]; the published glyph
 * origins and caret segments are therefore unambiguous physical paragraph coordinates.
 * [contentMetrics], [lineBox], and [designInkBounds] remain deliberately distinct. The value
 * publishes one or more geometric [fragments] without re-running shaping or BiDi. When no
 * fragments are supplied, construction derives one fragment spanning the complete inline line
 * box for rectangular-layout compatibility. Flattened glyph, caret, and inline-object accessors
 * are derived solely from that immutable fragment sequence.
 *
 * It retains no font handle, renderer object, platform object, or mutable caller collection and
 * can be shared across threads with its source snapshot revision.
 *
 * Contract violations, non-finite translated coordinates, or a line box inconsistent with the
 * line's vertical metrics are programming errors reported by [IllegalArgumentException].
 */
public class LineLayout(
    line: EditableLine,
    /** Absolute baseline origin in physical paragraph coordinates. */
    public val baseline: LayoutPoint,
    /** Metrics derived from actual final typographic content. */
    public val contentMetrics: LineContentMetrics,
    /** Complete composition and hit-testing box in paragraph coordinates. */
    public val lineBox: LayoutRect,
    /** Union of final glyph design bounds in paragraph coordinates. */
    public val designInkBounds: LayoutBounds,
    /**
     * Optional geometric projection of the already-resolved line.
     *
     * Every supplied item must already use final paragraph coordinates and preserve the source
     * line's visual ordering. `null` derives the single rectangular compatibility fragment.
     */
    fragments: List<LineFragment>? = null,
) {
    /** Complete snapshot-bound half-open source range covered by this final line. */
    public val range: TextRange = line.range

    /** Explicit direction used to classify the line's final BiDi carets. */
    public val baseDirection: ShapingDirection = line.baseDirection

    /** Line-box metrics used to produce the final physical line geometry. */
    public val verticalMetrics: LineVerticalMetrics = line.verticalMetrics

    /** Physical writing mode used for this line's geometry and editing operations. */
    public val writingMode: WritingMode = line.writingMode

    private val translatedGlyphRuns: List<PositionedGlyphRun> = line.positionedGlyphRuns
        .map { run -> run.translatedBy(baseline) }
        .immutableListSnapshot()

    private val translatedCaretCandidates: List<CaretCandidate> = line.allCaretCandidates
        .map { candidate -> candidate.translatedBy(baseline) }
        .immutableListSnapshot()

    /** Immutable recoverable diagnostics produced while finalizing the line. */
    public val diagnostics: List<EditableLineDiagnostic> = line.diagnostics.immutableListSnapshot()

    private val translatedInlineObjects: List<PositionedInlineObject> = line.positionedInlineObjects
        .map { objectItem -> objectItem.translatedBy(baseline) }
        .immutableListSnapshot()

    /** Immutable geometric projections in logical inline progression order. */
    public val fragments: List<LineFragment> = (fragments ?: listOf(
        LineFragment(
            availableInterval = InlineInterval(0f, lineBox.inlineExtent(writingMode)),
            positionedGlyphRuns = translatedGlyphRuns,
            caretCandidates = translatedCaretCandidates,
            positionedInlineObjects = translatedInlineObjects,
        ),
    )).immutableListSnapshot()

    /** Final runs flattened from [fragments] in physical visual order. */
    public val positionedGlyphRuns: List<PositionedGlyphRun> = this.fragments
        .flatMap(LineFragment::positionedGlyphRuns)
        .immutableListSnapshot()

    /** Final caret candidates flattened from [fragments] in visual order. */
    public val allCaretCandidates: List<CaretCandidate> = this.fragments
        .flatMap(LineFragment::caretCandidates)
        .immutableListSnapshot()

    /** Final positioned inline objects flattened from [fragments]. */
    public val positionedInlineObjects: List<PositionedInlineObject> = this.fragments
        .flatMap(LineFragment::positionedInlineObjects)
        .immutableListSnapshot()

    init {
        require(lineBox.left < lineBox.right && lineBox.top < lineBox.bottom) {
            "A final line box must have positive width and height."
        }
        when (writingMode) {
            WritingMode.HORIZONTAL_TB -> {
                require(lineBox.left == baseline.x) {
                    "A horizontal final line baseline origin must begin at its line-box left edge."
                }
                require(lineBox.top == LayoutUnit(baseline.y.value - verticalMetrics.ascent.value)) {
                    "A horizontal final line box top must equal its baseline minus line ascent."
                }
                require(lineBox.bottom == LayoutUnit(baseline.y.value + verticalMetrics.descent.value)) {
                    "A horizontal final line box bottom must equal its baseline plus line descent."
                }
            }

            WritingMode.VERTICAL_RL,
            WritingMode.VERTICAL_LR,
            -> {
                require(lineBox.top == baseline.y) {
                    "A vertical final line baseline origin must begin at its line-box top edge."
                }
                require(lineBox.left == LayoutUnit(baseline.x.value - verticalMetrics.ascent.value)) {
                    "A vertical final line box left must equal its baseline minus line ascent."
                }
                require(lineBox.right == LayoutUnit(baseline.x.value + verticalMetrics.descent.value)) {
                    "A vertical final line box right must equal its baseline plus line descent."
                }
            }
        }
        require(designInkBounds.minX <= designInkBounds.maxX && designInkBounds.minY <= designInkBounds.maxY) {
            "Final design ink bounds must be ordered in paragraph coordinates."
        }
        require(this.fragments.isNotEmpty()) { "A final line must publish at least one geometric fragment." }
        require(this.fragments.all { fragment -> fragment.availableInterval.endExclusive <= lineBox.inlineExtent(writingMode) }) {
            "Every line fragment must stay within the line-box inline extent."
        }
        require(this.fragments.zipWithNext().all { (left, right) ->
            left.availableInterval.endExclusive <= right.availableInterval.start
        }) {
            "Line fragments must be ordered and disjoint in logical inline progression."
        }
        require(positionedGlyphRuns.zipWithNext().all { (left, right) -> left.visualOrder <= right.visualOrder }) {
            "Line fragments must preserve the original line visual run order."
        }
        require(allCaretCandidates.zipWithNext().all { (left, right) -> left.visualOrder <= right.visualOrder }) {
            "Line fragments must preserve the original line caret order."
        }
        require(positionedGlyphRuns.all { run ->
            run.sourceRun.range.start >= range.start && run.sourceRun.range.endExclusive <= range.endExclusive
        }) {
            "Every fragment glyph run must originate from the logical line range."
        }
        require(allCaretCandidates.all { candidate ->
            candidate.position.index >= range.start && candidate.position.index <= range.endExclusive
        }) {
            "Every fragment caret must originate from the logical line range."
        }
        require(positionedInlineObjects.all { item ->
            item.sourceRange.start >= range.start && item.sourceRange.endExclusive <= range.endExclusive
        }) {
            "Every fragment inline object must originate from the logical line range."
        }
        require(positionedGlyphRuns.preserveGlyphSemanticsOf(line.positionedGlyphRuns)) {
            "Line fragments must preserve every glyph and its original logical run semantics exactly."
        }
        require(allCaretCandidates.preserveCaretSemanticsOf(line.allCaretCandidates)) {
            "Line fragments must preserve every original logical caret exactly."
        }
        require(positionedInlineObjects.preserveInlineObjectSemanticsOf(line.positionedInlineObjects)) {
            "Line fragments must preserve every original inline object exactly."
        }
    }

    /**
     * Returns non-empty selection rectangles clipped independently to this line's flow fragments.
     *
     * Both positions must be represented by this line. Geometry remains in final paragraph
     * coordinates and never bridges an unavailable inline gap.
     */
    public fun selectionGeometry(anchor: CaretPosition, focus: CaretPosition): List<LayoutRect> {
        require(allCaretCandidates.any { candidate -> candidate.position == anchor }) {
            "Selection anchor must be a line-local caret position."
        }
        require(allCaretCandidates.any { candidate -> candidate.position == focus }) {
            "Selection focus must be a line-local caret position."
        }
        if (anchor.index == focus.index) return emptyList()
        val selectedStart = if (anchor.index < focus.index) anchor.index else focus.index
        val selectedEnd = if (anchor.index < focus.index) focus.index else anchor.index
        return selectionGeometryBetween(selectedStart, selectedEnd)
    }

    /**
     * Maps a physical point to the nearest concrete caret on this already-resolved logical line.
     *
     * Equal distances are resolved by original visual order, logical index, then downstream
     * affinity. A point inside an unavailable flow gap therefore selects a deterministic edge
     * candidate without manufacturing a caret in that gap.
     */
    public fun hitTest(point: LayoutPoint): CaretCandidate = fragments
        .flatMap(LineFragment::caretCandidates)
        .minWith { left, right ->
            val distance = paragraphSquaredDistanceToSegment(point, left.geometry)
                .compareTo(paragraphSquaredDistanceToSegment(point, right.geometry))
            if (distance != 0) return@minWith distance
            val visual = left.visualOrder.compareTo(right.visualOrder)
            if (visual != 0) return@minWith visual
            val index = left.position.index.compareTo(right.position.index)
            if (index != 0) return@minWith index
            paragraphAffinityRank(left.position.affinity).compareTo(paragraphAffinityRank(right.position.affinity))
        }

    internal fun selectionGeometryBetween(selectedStart: TextIndex, selectedEnd: TextIndex): List<LayoutRect> = fragments
        .flatMap { fragment ->
            val fragmentClip = fragment.clipRect(this)
            fragment.positionedGlyphRuns.mapNotNull { run ->
                val runStart = maxParagraphIndex(selectedStart, run.sourceRun.range.start)
                val runEnd = minParagraphIndex(selectedEnd, run.sourceRun.range.endExclusive)
                if (runStart >= runEnd) return@mapNotNull null
                val caretCoordinates = fragment.caretCandidates.asSequence()
                    .filter { candidate ->
                        candidate.visualRunOrder == run.visualOrder &&
                            candidate.position.index >= runStart &&
                            candidate.position.index <= runEnd
                    }
                    .map { candidate ->
                        when (writingMode) {
                            WritingMode.HORIZONTAL_TB -> candidate.geometry.start.x
                            WritingMode.VERTICAL_RL,
                            WritingMode.VERTICAL_LR,
                            -> candidate.geometry.start.y
                        }
                    }
                    .toList()
                val coordinates = if (caretCoordinates.size >= 2) {
                    caretCoordinates
                } else {
                    caretCoordinates + run.glyphs.flatMap { glyph ->
                        when (writingMode) {
                            WritingMode.HORIZONTAL_TB ->
                                listOf(glyph.origin.x, LayoutUnit(glyph.origin.x.value + glyph.advance.x.value))

                            WritingMode.VERTICAL_RL,
                            WritingMode.VERTICAL_LR,
                            -> listOf(glyph.origin.y, LayoutUnit(glyph.origin.y.value + glyph.advance.y.value))
                        }
                    }
                }
                val start = coordinates.minOrNull() ?: return@mapNotNull null
                val end = coordinates.maxOrNull() ?: return@mapNotNull null
                if (start == end) {
                    null
                } else {
                    when (writingMode) {
                        WritingMode.HORIZONTAL_TB ->
                            LayoutRect(start, lineBox.top, end, lineBox.bottom).intersection(fragmentClip)

                        WritingMode.VERTICAL_RL,
                        WritingMode.VERTICAL_LR,
                        -> LayoutRect(lineBox.left, start, lineBox.right, end).intersection(fragmentClip)
                    }
                }
            } + fragment.positionedInlineObjects.mapNotNull { objectItem ->
                val objectRange = objectItem.sourceRange
                if (selectedStart >= objectRange.endExclusive || selectedEnd <= objectRange.start) {
                    return@mapNotNull null
                }
                objectItem.rect.intersection(fragmentClip)
            }
        }
        .filter { rectangle -> rectangle.left < rectangle.right && rectangle.top < rectangle.bottom }
        .immutableListSnapshot()
}

/**
 * Immutable base contract for a complete set of paragraph lines and editing operations.
 *
 * The constructor defensively captures [lines], verifies that they form the complete ordered
 * partition of [range], validates a terminal empty line against [lineBreakAnalysis], and binds
 * the result to [snapshot]'s [TextVersion]. Implementations of the abstract editorial operations
 * must use only the published final candidates and visual-run rectangles, retain no borrowed
 * native or renderer resource, and be safe for concurrent reads.
 */
public abstract class ParagraphLayout protected constructor(
    snapshot: TextSnapshot,
    /** Complete UAX #14 analysis that proves any terminal empty physical line is mandatory. */
    lineBreakAnalysis: LineBreakAnalysis,
    /** Complete half-open source range covered by [lines]. */
    public val range: TextRange,
    lines: List<LineLayout>,
) {
    /** Exact immutable text revision to which every line and caret belongs. */
    public val version: TextVersion = snapshot.version

    /** Complete final lines in physical block-progression order. */
    public val lines: List<LineLayout> = lines.immutableListSnapshot()

    init {
        require(snapshot.contains(range)) { "A paragraph layout range must belong to its source snapshot." }
        require(lineBreakAnalysis.range == snapshot.range) {
            "Paragraph line-break analysis must cover the complete source snapshot revision."
        }
        requireCompleteLinePartition(
            range,
            this.lines,
            hasMandatoryTerminalBreak(lineBreakAnalysis, range.endExclusive),
        )
        require(this.lines.map(LineLayout::writingMode).distinct().size <= 1) {
            "A paragraph layout must use one writing mode for every final line."
        }
        require(this.lines.zipWithNext().all { (first, second) ->
            when (first.writingMode) {
                WritingMode.HORIZONTAL_TB -> first.lineBox.bottom <= second.lineBox.top
                WritingMode.VERTICAL_RL -> first.lineBox.left >= second.lineBox.right
                WritingMode.VERTICAL_LR -> first.lineBox.right <= second.lineBox.left
            }
        }) {
            "Paragraph lines must be published in non-overlapping physical block-progression order."
        }
    }

    /**
     * Moves from [position] to the next editable boundary in logical scalar order.
     *
     * The current position must belong to this exact layout revision. Movement crosses line
     * boundaries and returns `null` only at the requested paragraph edge.
     */
    public abstract fun nextLogical(
        position: CaretPosition,
        direction: LogicalNavigationDirection,
    ): CaretPosition?

    /**
     * Moves from an actual published [candidate] in physical visual traversal order.
     *
     * Implementations reject reconstructed or foreign candidates even when their logical
     * position compares equal, preventing ambiguous BiDi geometry from crossing layouts.
     */
    public abstract fun nextVisual(
        candidate: CaretCandidate,
        direction: VisualNavigationDirection,
    ): CaretCandidate?

    /**
     * Returns every final concrete candidate for [position] in deterministic visual order.
     *
     * The returned list is an immutable snapshot and may contain multiple candidates at an
     * ambiguous BiDi boundary.
     */
    public abstract fun caretCandidates(position: CaretPosition): List<CaretCandidate>

    /**
     * Returns only non-empty visual-run rectangles between [anchor] and [focus].
     *
     * Geometry is in paragraph coordinates and never fills gaps between disjoint BiDi segments
     * or consults glyph ink, a renderer, device pixels, or platform state. Results are ordered by
     * physical line in its block-progression order, then by [PositionedGlyphRun.visualOrder] within that line;
     * reversing anchor and focus does not reverse this geometry order.
     */
    public fun selectionGeometry(anchor: CaretPosition, focus: CaretPosition): List<LayoutRect> {
        require(lines.any { line -> line.allCaretCandidates.any { candidate -> candidate.position == anchor } }) {
            "Selection anchor must be a paragraph-local caret position."
        }
        require(lines.any { line -> line.allCaretCandidates.any { candidate -> candidate.position == focus } }) {
            "Selection focus must be a paragraph-local caret position."
        }
        if (anchor.index == focus.index) return emptyList()
        val selectedStart = if (anchor.index < focus.index) anchor.index else focus.index
        val selectedEnd = if (anchor.index < focus.index) focus.index else anchor.index
        return lines.flatMap { line -> line.selectionGeometryBetween(selectedStart, selectedEnd) }.immutableListSnapshot()
    }

    /**
     * Maps a physical paragraph [point] to one deterministic final caret candidate.
     *
     * Line selection minimizes block-axis distance to each closed line-box span; equal distance
     * selects the earlier physical line. Within that line candidates are ordered by squared
     * distance to their axis-aligned segment, then
     * [CaretCandidate.visualOrder], logical [TextIndex], and [CaretAffinity.DOWNSTREAM] before
     * [CaretAffinity.UPSTREAM].
     */
    public fun hitTest(point: LayoutPoint): CaretCandidate {
        require(lines.isNotEmpty()) { "Hit testing requires a paragraph with a final line." }
        val line = lines.withIndex().minWith { left, right ->
            val distance = blockDistanceToRect(point, left.value.lineBox, left.value.writingMode)
                .compareTo(blockDistanceToRect(point, right.value.lineBox, right.value.writingMode))
            if (distance != 0) distance else left.index.compareTo(right.index)
        }.value
        return line.hitTest(point)
    }
}

/** Whether a successful result covers all requested source or leaves an exact remainder. */
public enum class CoverageStatus {
    /** Every requested source boundary is represented by complete final lines. */
    COMPLETE,

    /** Only a complete prefix is published and a compatible continuation owns the remainder. */
    PARTIAL,

    /** A truncated line was published: hidden content is described by the result truncation. */
    TRUNCATED,
}

/**
 * Resource-free identity of the line materialization mode relevant to continuation replay.
 *
 * Unlike [EditableLineMaterialization], this value never retains a borrowed resolver handle.
 */
public sealed interface ParagraphMaterializationIdentity {
    /** Layout geometry is produced without synchronously validating glyph materialization. */
    public data object LayoutOnly : ParagraphMaterializationIdentity

    /** Exact render variant and representation requirements required for synchronous final validation. */
    public data class Renderable(
        /** Complete geometry-neutral visual selection that must be replayed. */
        public val renderVariant: FontRenderVariantSnapshot,
        /** Ordered immutable representation requirements that must be replayed. */
        public val requirements: FontAccessRequirementsSnapshot,
    ) : ParagraphMaterializationIdentity {
        /** Stable key of [renderVariant] retained for identity-oriented consumers. */
        public val variant: FontRenderVariantKey
            get() = renderVariant.key

        init {
            require(requirements.mode == FontAccessRequirementsSnapshot.Mode.RENDERABLE) {
                "Renderable paragraph materialization identity requires RENDERABLE requirements."
            }
        }

        /** Compatibility constructor for a default-variant outline-only identity. */
        public constructor(
            variant: FontRenderVariantKey,
            outlineProfile: OutlineProfile,
        ) : this(
            renderVariant = paragraphRenderVariantSnapshot(variant),
            requirements = FontAccessRequirementsSnapshot.renderable(outlineProfile),
        )
    }

    /** Factories that discard borrowed operational capability after capturing immutable identity. */
    public companion object {
        /**
         * Captures only the configuration identity of [materialization].
         *
         * A renderable resolver is inspected neither here nor later retained by the returned
         * value; the live capability must be supplied separately to [ParagraphLayouter.layout].
         */
        public fun from(materialization: EditableLineMaterialization): ParagraphMaterializationIdentity =
            materialization.toParagraphIdentity()
    }
}

private fun paragraphRenderVariantSnapshot(variant: FontRenderVariantKey): FontRenderVariantSnapshot {
    require(variant == FontRenderVariantKey.default) {
        "A non-default FontRenderVariantKey lacks the palette and foreground context required for paragraph replay."
    }
    return FontRenderVariantSnapshot.default
}

/**
 * Immutable capability for resuming an incompletely covered paragraph request.
 *
 * The continuation records the original [TextVersion], exact [originalSourceRange] and
 * [remainingSourceRange], compatible physical region origin, rectangle width and line metrics, and every configuration
 * identity that can affect observable line breaking or final glyph geometry. The remainder is an
 * exact suffix of [originalSourceRange]; it is empty only when a required terminal empty physical
 * line remains. A partial layout prefix plus this value partitions the complete source requested
 * by the call that created it. It stores no text
 * history, incremental-edit state, borrowed resolver, native handle, renderer, or platform
 * object. Collections are defensively captured, making this value safe for concurrent reads.
 *
 * Create continuations only with [create]; a resumed [ParagraphLayoutRequest] rejects any
 * incompatible version, remaining range, geometry, Unicode data, font policy, shaping backend,
 * feature set, or materialization identity.
 */
public class LayoutContinuation private constructor(
    /** Original immutable source revision. */
    public val originalVersion: TextVersion,
    /** Complete source range requested by the call that produced this continuation. */
    public val originalSourceRange: TextRange,
    /** Exact unconsumed suffix, including its original end boundary. */
    public val remainingSourceRange: TextRange,
    /** Exact horizontal inline extent required by a compatible horizontal resumed request. */
    public val regionWidth: LayoutUnit,
    /** Exact physical left origin required by a compatible horizontal resumed request. */
    public val regionLeft: LayoutUnit,
    /** Exact physical top at which the first resumed line must be placed. */
    public val resumptionRegionTop: LayoutUnit,
    /** Writing mode whose block axis determines the next resumable column or line. */
    public val writingMode: WritingMode,
    /** Exact physical block-axis cursor at which the resumed request must begin. */
    public val resumptionBlockCursor: LayoutUnit,
    /** Exact physical inline extent required by a compatible resumed request. */
    public val inlineExtent: LayoutUnit,
    /** Exact line rhythm required by a compatible resumed request. */
    public val lineMetrics: LineVerticalMetrics,
    /** Explicit paragraph direction that produced the covered prefix. */
    public val baseDirection: BaseDirection,
    /** Explicit language used for analysis and shaping. */
    public val language: String,
    /** Unicode data release used for segmentation, BiDi, and line breaking. */
    public val unicodeData: UnicodeDataIdentity,
    /** Immutable font catalogue generation used for fallback. */
    public val fontCatalogGeneration: FontCatalogGeneration,
    /** Stable font-resolution policy family. */
    public val resolutionPolicyId: String,
    /** Exact font-resolution policy version. */
    public val resolutionPolicyVersion: String,
    /** Font instance geometry applied to selected faces. */
    public val fontInstanceDescriptor: FontInstanceDescriptor,
    /** Pinned shaping backend and configuration identity. */
    public val shapingBackendIdentity: ShapingBackendIdentity,
    /** Baseline OpenType feature policy used for shaping. */
    public val featurePolicy: ShapingFeaturePolicy,
    features: List<OpenTypeFeature>,
    /** Resource-free identity of the requested publication mode. */
    public val materializationIdentity: ParagraphMaterializationIdentity,
    /** Overflow behavior whose remainder this value represents. */
    public val overflowPolicy: OverflowPolicy,
    /** Alignment, justification, and tab configuration used for the covered prefix. */
    public val positioning: ParagraphPositioningPolicy,
    /** Hyphenation behavior used for the covered prefix. */
    public val hyphenationMode: HyphenationMode,
    /** Resource-free identity of the automatic hyphenation service, if one was supplied. */
    public val hyphenationServiceIdentity: HyphenationServiceIdentity?,
    /** Resource-free inline-object associations used for the covered prefix. */
    public val inlineObjects: InlineObjectSnapshot?,
    /** Unicode cluster orientation policy used for the covered prefix. */
    public val textOrientation: TextOrientation,
    /** Policy used when selected faces lack OpenType vertical metrics. */
    public val verticalMetricsPolicy: VerticalMetricsPolicy,
) {
    /** Immutable deterministic OpenType feature overrides required for replay. */
    public val features: List<OpenTypeFeature> = features.immutableListSnapshot()

    /** Returns whether [request] can consume this continuation without changing observable layout. */
    public fun isCompatibleWith(request: ParagraphLayoutRequest): Boolean =
            request.snapshot.version == originalVersion &&
            request.sourceRange == remainingSourceRange &&
            request.constraints.writingMode == writingMode &&
            request.constraints.region.top == resumptionRegionTop &&
            request.constraints.let { constraints ->
                when (writingMode) {
                    WritingMode.HORIZONTAL_TB ->
                        constraints.width == regionWidth && constraints.region.left == regionLeft
                    WritingMode.VERTICAL_RL ->
                        constraints.height == inlineExtent && constraints.region.right == resumptionBlockCursor
                    WritingMode.VERTICAL_LR ->
                        constraints.height == inlineExtent && constraints.region.left == resumptionBlockCursor
                }
            } &&
            request.constraints.lineMetrics == lineMetrics &&
            request.baseDirection == baseDirection &&
            request.language == language &&
            request.lineBreakAnalysis.unicodeData == unicodeData &&
            request.fontCatalog.generation == fontCatalogGeneration &&
            request.resolutionPolicy.policyId == resolutionPolicyId &&
            request.resolutionPolicy.version == resolutionPolicyVersion &&
            request.fontInstanceDescriptor == fontInstanceDescriptor &&
            request.shapingBackend.identity == shapingBackendIdentity &&
            request.featurePolicy == featurePolicy &&
            request.features == features &&
            request.materializationIdentity == materializationIdentity &&
            request.overflowPolicy == overflowPolicy &&
            request.positioning == positioning &&
            request.hyphenationMode == hyphenationMode &&
            request.hyphenationService?.identity == hyphenationServiceIdentity &&
            request.inlineObjects == inlineObjects &&
            request.textOrientation == textOrientation &&
            request.verticalMetricsPolicy == verticalMetricsPolicy

    internal fun incompatibilitySummary(request: ParagraphLayoutRequest): String = buildList {
        if (request.snapshot.version != originalVersion) add("snapshot version")
        if (request.sourceRange != remainingSourceRange) add("source range")
        if (request.constraints.writingMode != writingMode) add("writing mode")
        if (request.constraints.region.top != resumptionRegionTop) add("region top")
        when (writingMode) {
            WritingMode.HORIZONTAL_TB -> {
                if (request.constraints.width != regionWidth) add("region width")
                if (request.constraints.region.left != regionLeft) add("region left")
            }

            WritingMode.VERTICAL_RL -> {
                if (request.constraints.height != inlineExtent) add("inline extent")
                if (request.constraints.region.right != resumptionBlockCursor) add("block cursor")
            }

            WritingMode.VERTICAL_LR -> {
                if (request.constraints.height != inlineExtent) add("inline extent")
                if (request.constraints.region.left != resumptionBlockCursor) add("block cursor")
            }
        }
        if (request.constraints.lineMetrics != lineMetrics) add("line metrics")
        if (request.baseDirection != baseDirection) add("base direction")
        if (request.language != language) add("language")
        if (request.lineBreakAnalysis.unicodeData != unicodeData) add("Unicode data")
        if (request.fontCatalog.generation != fontCatalogGeneration) add("font catalog")
        if (request.resolutionPolicy.policyId != resolutionPolicyId) add("resolution policy")
        if (request.resolutionPolicy.version != resolutionPolicyVersion) add("resolution-policy version")
        if (request.fontInstanceDescriptor != fontInstanceDescriptor) add("font instance")
        if (request.shapingBackend.identity != shapingBackendIdentity) add("shaping backend")
        if (request.featurePolicy != featurePolicy) add("feature policy")
        if (request.features != features) add("features")
        if (request.materializationIdentity != materializationIdentity) add("materialization")
        if (request.overflowPolicy != overflowPolicy) add("overflow policy")
        if (request.positioning != positioning) add("positioning")
        if (request.hyphenationMode != hyphenationMode) add("hyphenation mode")
        if (request.hyphenationService?.identity != hyphenationServiceIdentity) add("hyphenation service")
        if (request.inlineObjects != inlineObjects) add("inline objects")
        if (request.textOrientation != textOrientation) add("text orientation")
        if (request.verticalMetricsPolicy != verticalMetricsPolicy) add("vertical metrics policy")
    }.joinToString()

    /** Factories that capture compatibility inputs from validated paragraph requests. */
    public companion object {
        /**
         * Captures an exact unconsumed suffix of [request].
         *
         * [remainingSourceRange] must be a suffix of the current request range and may equal the
         * full range when the region cannot publish even one complete line. An empty remainder is
         * valid only to resume the required terminal physical empty line of an empty paragraph or
         * a source range ending at a mandatory line-break boundary. [resumptionRegionTop] is the
         * physical top at which the resumed request must begin; callers that create a capability
         * outside composition use the current request top.
         */
        public fun create(
            request: ParagraphLayoutRequest,
            remainingSourceRange: TextRange,
            resumptionRegionTop: LayoutUnit = request.constraints.region.top,
            resumptionBlockCursor: LayoutUnit = when (request.constraints.writingMode) {
                WritingMode.HORIZONTAL_TB -> resumptionRegionTop
                WritingMode.VERTICAL_RL -> request.constraints.region.right
                WritingMode.VERTICAL_LR -> request.constraints.region.left
            },
        ): LayoutContinuation {
            require(remainingSourceRange.start.sharesVersionWith(request.sourceRange.start)) {
                "A continuation remainder must use the request text version."
            }
            require(
                remainingSourceRange.start >= request.sourceRange.start &&
                    remainingSourceRange.endExclusive == request.sourceRange.endExclusive,
            ) {
                "A continuation remainder must be an exact suffix of the request source range."
            }
            val terminalEmptyLineRequired = request.sourceRange.start == request.sourceRange.endExclusive ||
                request.lineBreakAnalysis.opportunities.any { opportunity ->
                    opportunity.boundary == request.sourceRange.endExclusive && opportunity.kind == LineBreakKind.MANDATORY
                }
            require(remainingSourceRange.start < remainingSourceRange.endExclusive || terminalEmptyLineRequired) {
                "An empty continuation remainder requires a terminal physical empty line."
            }
            return LayoutContinuation(
                originalVersion = request.snapshot.version,
                originalSourceRange = request.sourceRange,
                remainingSourceRange = remainingSourceRange,
                regionWidth = request.constraints.width,
                regionLeft = request.constraints.region.left,
                resumptionRegionTop = resumptionRegionTop,
                writingMode = request.constraints.writingMode,
                resumptionBlockCursor = resumptionBlockCursor,
                inlineExtent = when (request.constraints.writingMode) {
                    WritingMode.HORIZONTAL_TB -> request.constraints.width
                    WritingMode.VERTICAL_RL,
                    WritingMode.VERTICAL_LR,
                    -> request.constraints.height
                },
                lineMetrics = request.constraints.lineMetrics,
                baseDirection = request.baseDirection,
                language = request.language,
                unicodeData = request.lineBreakAnalysis.unicodeData,
                fontCatalogGeneration = request.fontCatalog.generation,
                resolutionPolicyId = request.resolutionPolicy.policyId,
                resolutionPolicyVersion = request.resolutionPolicy.version,
                fontInstanceDescriptor = request.fontInstanceDescriptor,
                shapingBackendIdentity = request.shapingBackend.identity,
                featurePolicy = request.featurePolicy,
                features = request.features,
                materializationIdentity = request.materializationIdentity,
                overflowPolicy = request.overflowPolicy,
                positioning = request.positioning,
                hyphenationMode = request.hyphenationMode,
                hyphenationServiceIdentity = request.hyphenationService?.identity,
                inlineObjects = request.inlineObjects,
                textOrientation = request.textOrientation,
                verticalMetricsPolicy = request.verticalMetricsPolicy,
            )
        }
    }
}

/**
 * Complete immutable input to pure paragraph composition.
 *
 * The request binds [sourceRange] to [snapshot], requires complete Unicode and line-break
 * analyses for that same revision, and captures all font, shaping, feature, materialization,
 * cancellation, and geometry inputs required for deterministic replay. The shaping backend is
 * borrowed for composition. A renderable resolver is deliberately absent and is supplied only to
 * the synchronous [ParagraphLayouter.layout] call. All caller collections are defensively copied.
 * Invalid ranges or mismatched identities are programming errors reported during construction.
 */
public class ParagraphLayoutRequest(
    /** Immutable canonical source revision. */
    public val snapshot: TextSnapshot,
    /** Half-open source range to cover, or the exact remainder of [continuation]. */
    public val sourceRange: TextRange = snapshot.range,
    /** Complete Unicode analysis reusable across line-finalization attempts. */
    public val unicodeAnalysis: UnicodeAnalysis,
    /** Complete legal line-break analysis tied to [unicodeAnalysis]. */
    public val lineBreakAnalysis: LineBreakAnalysis,
    /** Physical rectangular region, line rhythm, and writing-mode projection. */
    public val constraints: ParagraphConstraints,
    /** Explicit base direction; it is never inferred from source text. */
    public val baseDirection: BaseDirection,
    /** Explicit language used by Unicode analysis and shaping. */
    public val language: String,
    /** Versioned baseline feature behavior required from [shapingBackend]. */
    public val featurePolicy: ShapingFeaturePolicy,
    features: List<OpenTypeFeature> = emptyList(),
    /** Immutable catalogue generation used for deterministic fallback. */
    public val fontCatalog: FontCatalogSnapshot,
    /** Immutable total-order fallback policy for [fontCatalog]. */
    public val resolutionPolicy: FontResolutionPolicySnapshot,
    /** Geometric parameters applied to every selected font face. */
    public val fontInstanceDescriptor: FontInstanceDescriptor,
    /** Borrowed portable backend used for provisional and final shaping. */
    public val shapingBackend: ShapingBackend,
    /** Resource-free identity of layout-only or outline-validated publication. */
    public val materializationIdentity: ParagraphMaterializationIdentity,
    /** Only supported behavior when complete source coverage exceeds the region. */
    public val overflowPolicy: OverflowPolicy = OverflowPolicy.Continue,
    /** Exact prior result capability when this request resumes partial coverage. */
    public val continuation: LayoutContinuation? = null,
    /** Explicit tab stops, alignment, and justification applied to every composed line. */
    public val positioning: ParagraphPositioningPolicy = ParagraphPositioningPolicy(),
    /** Hyphenation mode applied to line selection and final line content. */
    public val hyphenationMode: HyphenationMode = HyphenationMode.MANUAL,
    /** Immutable versioned service used by [HyphenationMode.AUTO], or `null` when absent. */
    public val hyphenationService: HyphenationService? = null,
    /** Definitions bound to `U+FFFC` object replacement scalars inside the requested range. */
    public val inlineObjects: InlineObjectSnapshot? = null,
    /** Orientation policy applied to grapheme clusters in vertical writing modes. */
    public val textOrientation: TextOrientation = TextOrientation.MIXED,
    /** Explicit policy for a resolved face that lacks usable OpenType vertical metrics. */
    public val verticalMetricsPolicy: VerticalMetricsPolicy = VerticalMetricsPolicy.SYNTHESIZE_IF_UNAVAILABLE,
    /** Cooperative signal observed between bounded composition operations. */
    public val cancellationToken: CancellationToken = CancellationToken.none,
) {
    /** Immutable deterministic OpenType feature overrides in caller-specified order. */
    public val features: List<OpenTypeFeature> = features.immutableListSnapshot()

    init {
        require(snapshot.contains(sourceRange)) { "Paragraph source range must belong to the supplied snapshot." }
        require(unicodeAnalysis.range == snapshot.range) {
            "Paragraph Unicode analysis must cover the complete supplied snapshot revision."
        }
        require(lineBreakAnalysis.range == unicodeAnalysis.range && lineBreakAnalysis.unicodeData == unicodeAnalysis.unicodeData) {
            "Paragraph line-break and Unicode analyses must cover the same revision and Unicode data."
        }
        require(language.isNotBlank()) { "Paragraph language must not be blank." }
        require(unicodeAnalysis.scriptLanguageRuns.all { run -> run.language == language }) {
            "Paragraph language must match every analyzed script-language run."
        }
        require(featurePolicy == shapingBackend.identity.featurePolicy) {
            "Paragraph feature policy must be implemented by the selected shaping backend."
        }
        require(this.features.map(OpenTypeFeature::tag).distinct().size == this.features.size) {
            "Paragraph shaping features must not repeat a tag."
        }
        require(fontCatalog.generation == resolutionPolicy.generation) {
            "Paragraph font catalog and resolution policy must use the same generation."
        }
        require(resolutionPolicy.candidates.all { candidate -> candidate.faceId in fontCatalog.faces.map(FontFaceRecord::id) }) {
            "Every paragraph font candidate must belong to the captured font catalog."
        }
        inlineObjects?.entries?.forEach { entry ->
            require(entry.index.sharesVersionWith(snapshot.range.start)) {
                "Inline object entries must belong to the supplied snapshot version."
            }
            val objectRange = snapshot.scalarRanges(snapshot.range).firstOrNull { range -> range.start == entry.index }
            require(objectRange != null && snapshot.scalarValues(objectRange).single() == OBJECT_REPLACEMENT_SCALAR) {
                "Every inline object entry must identify a U+FFFC object replacement scalar in the supplied snapshot."
            }
            require(objectRange.start >= sourceRange.start && objectRange.endExclusive <= sourceRange.endExclusive) {
                "Every inline object entry must lie inside the requested source range."
            }
        }
        require(continuation == null || continuation.isCompatibleWith(this)) {
            "Paragraph continuation is incompatible with: ${continuation?.incompatibilitySummary(this)}."
        }
    }
}

private const val OBJECT_REPLACEMENT_SCALAR: Int = 0xFFFC

/** Typed reason paragraph composition could not publish any partial line. */
public sealed interface ParagraphLayoutError {
    /** Stable machine-readable error code. */
    public val code: String

    /** Human-readable deterministic explanation. */
    public val message: String

    /** Invalid or incompatible portable paragraph inputs detected during composition. */
    public data class InvalidInput(
        override val message: String,
    ) : ParagraphLayoutError {
        override val code: String = "layout.invalid-paragraph-input"
    }

    /** Typed font or shaping failure that prevented publication of the current complete line. */
    public data class FontFailure(
        /** Underlying portable font failure. */
        public val fontError: FontError,
    ) : ParagraphLayoutError {
        override val code: String = "layout.paragraph-font-failure"
        override val message: String = fontError.message
    }

    /** A finite final paragraph coordinate could not be produced. */
    public data class GeometryOverflow(
        override val message: String,
    ) : ParagraphLayoutError {
        override val code: String = "layout.paragraph-geometry-overflow"
    }
}

/**
 * Typed outcome of one synchronous paragraph composition call.
 *
 * Failure and cancellation variants deliberately contain no [LineLayout] or [ParagraphLayout],
 * so a current partial line can never escape. Diagnostic collections are immutable snapshots,
 * and successful values contain only complete final lines.
 */
public sealed interface ParagraphLayoutResult {
    /** Complete-line publication with explicit source coverage. */
    public class Success(
        /** Immutable paragraph containing only complete final lines. */
        public val layout: ParagraphLayout,
        /** Complete or partial status for the requested range. */
        public val coverageStatus: CoverageStatus,
        /** Exact remainder capability for partial coverage, otherwise `null`. */
        public val continuation: LayoutContinuation? = null,
        /** Explicit hidden source content when [coverageStatus] is [CoverageStatus.TRUNCATED]. */
        public val truncation: ParagraphTruncation? = null,
    ) : ParagraphLayoutResult {
        init {
            require((coverageStatus == CoverageStatus.TRUNCATED) == (truncation != null)) {
                "Truncated paragraph coverage must publish an explicit hidden-range description."
            }
            require((coverageStatus == CoverageStatus.PARTIAL) == (continuation != null)) {
                "Only partial paragraph coverage may publish a continuation."
            }
            if (continuation != null) {
                require(layout.version == continuation.originalVersion) {
                    "A partial paragraph and its continuation must use the same source revision."
                }
                require(layout.range.start == continuation.originalSourceRange.start) {
                    "A partial paragraph must begin at the original requested source boundary."
                }
                require(layout.range.endExclusive == continuation.remainingSourceRange.start) {
                    "A partial paragraph must end exactly where its continuation begins."
                }
                require(continuation.remainingSourceRange.endExclusive == continuation.originalSourceRange.endExclusive) {
                    "A partial paragraph continuation must retain the original requested end boundary."
                }
            }
        }
    }

    /** No paragraph was published because a typed failure prevented a complete current line. */
    public class Failure(
        /** Typed failure that prevented publication. */
        public val error: ParagraphLayoutError,
        diagnostics: List<EditableLineDiagnostic> = emptyList(),
    ) : ParagraphLayoutResult {
        /** Immutable diagnostics produced before the failed current line was discarded. */
        public val diagnostics: List<EditableLineDiagnostic> = diagnostics.immutableListSnapshot()
    }

    /** No paragraph was published because cooperative cancellation was observed. */
    public class Cancelled(
        diagnostics: List<EditableLineDiagnostic> = emptyList(),
    ) : ParagraphLayoutResult {
        /** Immutable diagnostics produced before cancellation discarded the current line. */
        public val diagnostics: List<EditableLineDiagnostic> = diagnostics.immutableListSnapshot()
    }
}

/**
 * Explicit relationship between a truncated paragraph line and its hidden source content.
 *
 * [hiddenRange] is the exact source range removed from visibility, [anchor] is
 * the real snapshot boundary at which the synthetic ellipsis marker was
 * anchored, and [side] records where the marker was placed in the logical
 * inline axis. Truncation never creates a [TextIndex], caret candidate, or
 * editable position: reading, copying, and hit testing still consult the
 * complete [TextSnapshot].
 */
public class ParagraphTruncation(
    /** Exact source range hidden by this truncation. */
    public val hiddenRange: TextRange,
    /** Real snapshot boundary anchoring the synthetic ellipsis marker. */
    public val anchor: TextIndex,
    /** Logical inline side at which the marker was placed. */
    public val side: EllipsisSide,
) {
    init {
        require(hiddenRange.start.sharesVersionWith(anchor)) {
            "Truncation hidden range and anchor must use the same text revision."
        }
    }
}

/** Portable boundary implemented by a pure, renderer-independent paragraph layout module. */
public interface ParagraphLayouter {
    /**
     * Composes [request] synchronously into complete immutable lines using [materialization].
     *
     * [materialization] is the only parameter allowed to carry a live resolver. Its immutable
     * identity must equal [ParagraphLayoutRequest.materializationIdentity], and a renderable
     * resolver must use the request catalog generation. Implementations borrow it only for this
     * call and must neither close nor retain it. They publish neither a current partial line nor
     * a native/platform resource: interruption returns [ParagraphLayoutResult.Cancelled], and
     * any failure while finalizing a line returns [ParagraphLayoutResult.Failure].
     */
    public fun layout(
        request: ParagraphLayoutRequest,
        materialization: EditableLineMaterialization,
    ): ParagraphLayoutResult
}

/**
 * Portable boundary for placing one already-resolved logical line through a consumer flow region.
 *
 * The implementation queries [region] in logical axes, selects and finalizes the source line once
 * against the sum of its stable intervals, and only then creates geometric fragments. It borrows
 * [materialization] synchronously, retains no region or font resource, and returns a typed flow
 * failure without partial geometry when refinement or placement cannot be exact.
 */
public interface FlowParagraphLayouter {
    /**
     * Composes the first complete logical line of [request] at [blockStart] in [region].
     *
     * This line-level operation requires the selected line to cover the request range; region-chain
     * continuation and multi-line pagination are separate higher-level operations.
     */
    public fun layoutLine(
        request: ParagraphLayoutRequest,
        materialization: EditableLineMaterialization,
        region: FlowRegion,
        blockStart: Float = 0f,
    ): FlowCompositionResult<ParagraphFragment>

    /**
     * Composes one source-consecutive paragraph fragment in the next usable region of [chain].
     *
     * The operation may skip regions that answer [FlowRegionResult.EndOfRegion], and it follows
     * strictly progressing [FlowRegionResult.Empty] answers before placing complete lines. A
     * partial success carries an exact [FlowContinuation]; callers resume by passing its remaining
     * range as [ParagraphLayoutRequest.sourceRange]. [inputIdentity] proves the immutable text and
     * typography revisions and is required even for the first fragment so a reusable continuation
     * can be issued. Only source-preserving [OverflowPolicy.Continue] is accepted; truncating
     * policies fail before a region is queried. [maximumLines] may bound publication inside a
     * region after complete lines while preserving an exact same-region continuation. The borrowed
     * [materialization] is never retained. Supplying [flowConfiguration] attaches the complete
     * structured provenance required to aggregate the result into an incremental [FlowLayoutState].
     */
    public fun layoutFragment(
        request: ParagraphLayoutRequest,
        materialization: EditableLineMaterialization,
        chain: FlowChain,
        inputIdentity: FlowCompositionInputIdentity,
        continuation: FlowContinuation? = null,
        maximumLines: Int? = null,
        flowConfiguration: FlowLayoutConfigurationSignature? = null,
    ): FlowCompositionResult<ParagraphFragment>
}

private fun PositionedGlyphRun.translatedBy(baseline: LayoutPoint): PositionedGlyphRun =
    PositionedGlyphRun(
        sourceRun = sourceRun,
        visualOrder = visualOrder,
        renderAssetKey = renderAssetKey,
        glyphs = glyphs.map { glyph ->
            PositionedGlyph(
                shapedGlyph = glyph.shapedGlyph,
                sourceClusters = glyph.sourceClusters,
                origin = glyph.origin.translatedBy(baseline),
                advance = glyph.advance,
                transform = glyph.transform,
                renderAssetKey = glyph.renderAssetKey,
                materializationCertificate = glyph.materializationCertificate,
                provenance = glyph.provenance,
            )
        },
    )

private fun PositionedInlineObject.translatedBy(baseline: LayoutPoint): PositionedInlineObject = PositionedInlineObject(
    sourceRange = sourceRange,
    definition = definition,
    rect = LayoutRect(
        left = LayoutUnit(rect.left.value + baseline.x.value),
        top = LayoutUnit(rect.top.value + baseline.y.value),
        right = LayoutUnit(rect.right.value + baseline.x.value),
        bottom = LayoutUnit(rect.bottom.value + baseline.y.value),
    ),
)

private fun CaretCandidate.translatedBy(baseline: LayoutPoint): CaretCandidate =
    CaretCandidate(
        position = position,
        geometry = LayoutSegment(
            start = geometry.start.translatedBy(baseline),
            end = geometry.end.translatedBy(baseline),
        ),
        visualOrder = visualOrder,
        visualRunOrder = visualRunOrder,
        bidiLevel = bidiLevel,
        direction = direction,
        strength = strength,
        edge = edge,
    )

private fun LayoutPoint.translatedBy(offset: LayoutPoint): LayoutPoint =
    LayoutPoint(LayoutUnit(x.value + offset.x.value), LayoutUnit(y.value + offset.y.value))

private fun LayoutRect.inlineExtent(writingMode: WritingMode): Float = when (writingMode) {
    WritingMode.HORIZONTAL_TB -> right.value - left.value
    WritingMode.VERTICAL_RL,
    WritingMode.VERTICAL_LR,
    -> bottom.value - top.value
}

private fun LineFragment.clipRect(line: LineLayout): LayoutRect = when (line.writingMode) {
    WritingMode.HORIZONTAL_TB -> LayoutRect(
        left = LayoutUnit(line.lineBox.left.value + availableInterval.start),
        top = line.lineBox.top,
        right = LayoutUnit(line.lineBox.left.value + availableInterval.endExclusive),
        bottom = line.lineBox.bottom,
    )

    WritingMode.VERTICAL_RL,
    WritingMode.VERTICAL_LR,
    -> LayoutRect(
        left = line.lineBox.left,
        top = LayoutUnit(line.lineBox.top.value + availableInterval.start),
        right = line.lineBox.right,
        bottom = LayoutUnit(line.lineBox.top.value + availableInterval.endExclusive),
    )
}

private fun LayoutRect.intersection(other: LayoutRect): LayoutRect {
    val intersectionLeft = if (left > other.left) left else other.left
    val intersectionTop = if (top > other.top) top else other.top
    val intersectionRight = if (right < other.right) right else other.right
    val intersectionBottom = if (bottom < other.bottom) bottom else other.bottom
    return LayoutRect(
        left = if (intersectionLeft < intersectionRight) intersectionLeft else intersectionRight,
        top = if (intersectionTop < intersectionBottom) intersectionTop else intersectionBottom,
        right = intersectionRight,
        bottom = intersectionBottom,
    )
}

private fun List<PositionedGlyphRun>.preserveGlyphSemanticsOf(
    originalRuns: List<PositionedGlyphRun>,
): Boolean {
    val projected = flatMap { run -> run.glyphs.map { glyph -> run to glyph } }
    val original = originalRuns.flatMap { run -> run.glyphs.map { glyph -> run to glyph } }
    return projected.size == original.size && projected.zip(original).all { (actual, expected) ->
        val (actualRun, actualGlyph) = actual
        val (expectedRun, expectedGlyph) = expected
        actualRun.visualOrder == expectedRun.visualOrder &&
            actualRun.sourceRun.range.start >= expectedRun.sourceRun.range.start &&
            actualRun.sourceRun.range.endExclusive <= expectedRun.sourceRun.range.endExclusive &&
            actualRun.sourceRun.fontInstanceKey == expectedRun.sourceRun.fontInstanceKey &&
            actualRun.sourceRun.backendIdentity == expectedRun.sourceRun.backendIdentity &&
            actualRun.sourceRun.direction == expectedRun.sourceRun.direction &&
            actualRun.sourceRun.script == expectedRun.sourceRun.script &&
            actualRun.sourceRun.language == expectedRun.sourceRun.language &&
            actualRun.sourceRun.bidiLevel == expectedRun.sourceRun.bidiLevel &&
            actualRun.sourceRun.featurePolicy == expectedRun.sourceRun.featurePolicy &&
            actualRun.sourceRun.features == expectedRun.sourceRun.features &&
            actualGlyph.shapedGlyph === expectedGlyph.shapedGlyph &&
            actualGlyph.sourceClusters == expectedGlyph.sourceClusters &&
            actualGlyph.advance == expectedGlyph.advance &&
            actualGlyph.transform == expectedGlyph.transform &&
            actualGlyph.renderAssetKey == expectedGlyph.renderAssetKey &&
            actualGlyph.materializationCertificate == expectedGlyph.materializationCertificate &&
            actualGlyph.provenance == expectedGlyph.provenance
    }
}

private fun List<CaretCandidate>.preserveCaretSemanticsOf(
    original: List<CaretCandidate>,
): Boolean =
    original.all { expected -> any { actual -> actual.sameCaretSemanticsAs(expected, preserveAffinity = true) } } &&
        all { actual -> original.any { expected -> actual.sameCaretSemanticsAs(expected, preserveAffinity = false) } }

private fun CaretCandidate.sameCaretSemanticsAs(
    original: CaretCandidate,
    preserveAffinity: Boolean,
): Boolean =
    position.index == original.position.index &&
        (!preserveAffinity || position.affinity == original.position.affinity) &&
        visualOrder == original.visualOrder &&
        visualRunOrder == original.visualRunOrder &&
        bidiLevel == original.bidiLevel &&
        direction == original.direction &&
        strength == original.strength &&
        edge == original.edge

private fun List<PositionedInlineObject>.preserveInlineObjectSemanticsOf(
    original: List<PositionedInlineObject>,
): Boolean = size == original.size && zip(original).all { (actual, expected) ->
    actual.sourceRange == expected.sourceRange && actual.definition == expected.definition
}

private fun blockDistanceToRect(point: LayoutPoint, rect: LayoutRect, writingMode: WritingMode): Double = when (writingMode) {
    WritingMode.HORIZONTAL_TB -> when {
        point.y < rect.top -> rect.top.value.toDouble() - point.y.value.toDouble()
        point.y > rect.bottom -> point.y.value.toDouble() - rect.bottom.value.toDouble()
        else -> 0.0
    }

    WritingMode.VERTICAL_RL,
    WritingMode.VERTICAL_LR,
    -> when {
        point.x < rect.left -> rect.left.value.toDouble() - point.x.value.toDouble()
        point.x > rect.right -> point.x.value.toDouble() - rect.right.value.toDouble()
        else -> 0.0
    }
}

private fun paragraphSquaredDistanceToSegment(point: LayoutPoint, segment: LayoutSegment): Double {
    val px = point.x.value.toDouble()
    val py = point.y.value.toDouble()
    val ax = segment.start.x.value.toDouble()
    val ay = segment.start.y.value.toDouble()
    val bx = segment.end.x.value.toDouble()
    val by = segment.end.y.value.toDouble()
    val dx = bx - ax
    val dy = by - ay
    val denominator = dx * dx + dy * dy
    val parameter = if (denominator == 0.0) 0.0 else ((px - ax) * dx + (py - ay) * dy) / denominator
    val clamped = parameter.coerceIn(0.0, 1.0)
    val xDistance = px - (ax + clamped * dx)
    val yDistance = py - (ay + clamped * dy)
    return xDistance * xDistance + yDistance * yDistance
}

private fun paragraphAffinityRank(affinity: CaretAffinity): Int = when (affinity) {
    CaretAffinity.DOWNSTREAM -> 0
    CaretAffinity.UPSTREAM -> 1
}

private fun maxParagraphIndex(first: TextIndex, second: TextIndex): TextIndex = if (first >= second) first else second

private fun minParagraphIndex(first: TextIndex, second: TextIndex): TextIndex = if (first <= second) first else second

private fun EditableLineMaterialization.toParagraphIdentity(): ParagraphMaterializationIdentity = when (this) {
    EditableLineMaterialization.LayoutOnly -> ParagraphMaterializationIdentity.LayoutOnly
    is EditableLineMaterialization.Renderable -> ParagraphMaterializationIdentity.Renderable(renderVariant, requirements)
}

private fun hasMandatoryTerminalBreak(
    lineBreakAnalysis: LineBreakAnalysis,
    boundary: TextIndex,
): Boolean = lineBreakAnalysis.opportunities.any { opportunity ->
    opportunity.boundary == boundary && opportunity.kind == LineBreakKind.MANDATORY
}

private fun requireCompleteLinePartition(
    range: TextRange,
    lines: List<LineLayout>,
    terminalEmptyLineIsMandatory: Boolean,
) {
    if (range.start == range.endExclusive) {
        require(lines.size <= 1 && lines.all { line -> line.range == range }) {
            "An empty paragraph range may publish at most one matching empty line."
        }
        return
    }
    require(lines.isNotEmpty()) { "A non-empty paragraph layout requires complete final lines." }
    var expectedStart = range.start
    var terminalEmptyLineSeen = false
    lines.forEachIndexed { index, line ->
        require(line.range.start.sharesVersionWith(range.start)) {
            "Every paragraph line must use the layout source revision."
        }
        if (line.range.start == line.range.endExclusive) {
            require(
                !terminalEmptyLineSeen &&
                    index == lines.lastIndex &&
                    expectedStart == range.endExclusive &&
                    line.range == TextRange(range.endExclusive, range.endExclusive) &&
                    terminalEmptyLineIsMandatory,
            ) {
                "Only one terminal empty line proven by a mandatory source break may follow complete non-empty paragraph coverage."
            }
            terminalEmptyLineSeen = true
            return@forEachIndexed
        }
        require(!terminalEmptyLineSeen && line.range.start == expectedStart && line.range.start < line.range.endExclusive) {
            "Paragraph lines must be non-empty, contiguous, and ordered in logical source order."
        }
        require(line.range.endExclusive <= range.endExclusive) {
            "Paragraph lines must stay within the layout source range."
        }
        expectedStart = line.range.endExclusive
    }
    require(expectedStart == range.endExclusive) {
        "Paragraph lines must cover the complete published source range."
    }
}

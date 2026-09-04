package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.api.AutomaticHyphenBreaks
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.BidiRun
import org.graphiks.kalligraphie.api.CaretCandidate
import org.graphiks.kalligraphie.api.CaretPosition
import org.graphiks.kalligraphie.api.CoverageStatus
import org.graphiks.kalligraphie.api.EditableLine
import org.graphiks.kalligraphie.api.EditableLineDiagnostic
import org.graphiks.kalligraphie.api.EditableLineDiagnosticSeverity
import org.graphiks.kalligraphie.api.EditableLineError
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineRequest
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.EllipsisSide
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GdefLigatureCaretFact
import org.graphiks.kalligraphie.api.HyphenationMode
import org.graphiks.kalligraphie.api.LayoutBounds
import org.graphiks.kalligraphie.api.LayoutContinuation
import org.graphiks.kalligraphie.api.LayoutPoint
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.ParagraphLayoutRequest
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.ParagraphLayouter
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.ParagraphPositioningPolicy
import org.graphiks.kalligraphie.api.HyphenationService
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineBreakAnalysis
import org.graphiks.kalligraphie.api.LineBreakKind
import org.graphiks.kalligraphie.api.LineContentMetrics
import org.graphiks.kalligraphie.api.LineEllipsisPolicy
import org.graphiks.kalligraphie.api.LineLayout
import org.graphiks.kalligraphie.api.LogicalNavigationDirection
import org.graphiks.kalligraphie.api.OverflowPolicy
import org.graphiks.kalligraphie.api.ParagraphLayout
import org.graphiks.kalligraphie.api.ParagraphLayoutError
import org.graphiks.kalligraphie.api.ParagraphMaterializationIdentity
import org.graphiks.kalligraphie.api.ParagraphTruncation
import org.graphiks.kalligraphie.api.ScriptLanguageRun
import org.graphiks.kalligraphie.api.ShapedGlyph
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShaperCluster
import org.graphiks.kalligraphie.api.ShaperClusterToken
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.SoftHyphenLinePolicy
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.UnicodeAnalysis
import org.graphiks.kalligraphie.api.VisualNavigationDirection

/** One finalized line and its physical placement, before content/ink metric enrichment. */
internal class ComposedParagraphLine(
    val line: EditableLine,
    val baseline: LayoutPoint,
    val lineBox: LayoutRect,
    val inlineAdvance: LayoutUnit,
    val fontInstances: List<FontInstance> = emptyList(),
) {
    init {
        require(lineBox.left == baseline.x)
        require(lineBox.top == LayoutUnit(baseline.y.value - line.verticalMetrics.ascent.value))
        require(lineBox.bottom == LayoutUnit(baseline.y.value + line.verticalMetrics.descent.value))
    }
}

/** Pure complete-line output consumed by the later paragraph geometry layer. */
internal sealed interface ParagraphCompositionResult {
    class Success(
        lines: List<ComposedParagraphLine>,
        val remainingSourceRange: TextRange?,
        val hasUnplacedTrailingEmptyLine: Boolean = false,
        val truncation: ParagraphTruncation? = null,
    ) : ParagraphCompositionResult {
        val lines: List<ComposedParagraphLine> = lines.immutableSnapshot()
    }

    class Failure(
        val error: EditableLineError,
        diagnostics: List<EditableLineDiagnostic> = emptyList(),
    ) : ParagraphCompositionResult {
        val diagnostics: List<EditableLineDiagnostic> = diagnostics.immutableSnapshot()
    }

    class Cancelled(
        diagnostics: List<EditableLineDiagnostic> = emptyList(),
    ) : ParagraphCompositionResult {
        val diagnostics: List<EditableLineDiagnostic> = diagnostics.immutableSnapshot()
    }
}

/**
 * Pure portable [ParagraphLayouter] that finalizes complete paragraph lines and editing geometry.
 *
 * It retains no font instance, renderer, platform state, or mutable text after a call returns.
 * Published glyphs, carets, boxes, and ink bounds are all finite paragraph coordinates bound to
 * the request snapshot. Font and geometry failures are reported through [ParagraphLayoutResult].
 */
public object ParagraphComposer : ParagraphLayouter {
    /**
     * Finalizes complete paragraph lines from the versioned analyses and borrowed backend in
     * [request]. [materialization] is borrowed synchronously and must match the resource-free
     * identity captured by the request. Success publishes only immutable glyph, caret, metric,
     * and geometry values in paragraph coordinates; no font instance, resolver, backend, native
     * handle, or partial current line is retained. Invalid input, font failure, finite-coordinate
     * overflow, and cancellation are returned through [ParagraphLayoutResult].
     */
    override fun layout(
        request: ParagraphLayoutRequest,
        materialization: EditableLineMaterialization,
    ): ParagraphLayoutResult = try {
        when (val composition = compose(request, materialization)) {
            is ParagraphCompositionResult.Failure -> ParagraphLayoutResult.Failure(
                composition.error.toParagraphError(),
                composition.diagnostics,
            )

            is ParagraphCompositionResult.Cancelled -> ParagraphLayoutResult.Cancelled(composition.diagnostics)
            is ParagraphCompositionResult.Success -> projectComposition(request, composition)
        }
    } catch (overflow: ParagraphGeometryOverflowException) {
        ParagraphLayoutResult.Failure(
            ParagraphLayoutError.GeometryOverflow(overflow.message ?: "Paragraph geometry overflowed."),
        )
    }

    internal fun compose(
        request: ParagraphLayoutRequest,
        materialization: EditableLineMaterialization,
    ): ParagraphCompositionResult {
        if (materialization.identity() != request.materializationIdentity) {
            return ParagraphCompositionResult.Failure(
                EditableLineError.InvalidInput("Paragraph materialization does not match the captured request identity."),
            )
        }
        if (materialization is EditableLineMaterialization.Renderable &&
            materialization.resolver.generation != request.fontCatalog.generation
        ) {
            return ParagraphCompositionResult.Failure(
                EditableLineError.InvalidInput(
                    "Renderable paragraph materialization resolver must belong to the captured font catalog generation.",
                ),
            )
        }
        if (request.cancellationToken.isCancellationRequested()) return ParagraphCompositionResult.Cancelled()

        val sourceClusters = request.unicodeAnalysis.graphemeClusters.filter { cluster ->
            cluster.start >= request.sourceRange.start && cluster.endExclusive <= request.sourceRange.endExclusive
        }
        if (request.sourceRange.start != request.sourceRange.endExclusive &&
            (sourceClusters.isEmpty() || sourceClusters.first().start != request.sourceRange.start ||
                sourceClusters.last().endExclusive != request.sourceRange.endExclusive)
        ) {
            return ParagraphCompositionResult.Failure(
                EditableLineError.InvalidInput("Paragraph source ranges must begin and end at extended grapheme boundaries."),
            )
        }

        val provisionalAnalysis = analysisForLine(request, request.sourceRange, resetLineTrailingWhitespace = false)
        val provisionalRuns = when (
            val resolved = FontFallbackResolver.resolveRange(
                request = request,
                sourceRange = request.sourceRange,
                shapingContextRange = request.sourceRange,
                unicodeAnalysis = provisionalAnalysis,
                materialization = materialization,
            )
        ) {
            is FontOperationResult.Success -> resolved.value.shapedRuns
            is FontOperationResult.Failure -> return ParagraphCompositionResult.Failure(
                EditableLineError.FontResolutionFailure(resolved.error),
                resolved.diagnostics.map(::fontDiagnostic),
            )
            is FontOperationResult.Cancelled -> return ParagraphCompositionResult.Cancelled(
                resolved.diagnostics.map(::fontDiagnostic),
            )
        }

        val placed = mutableListOf<ComposedParagraphLine>()
        val region = request.constraints.region
        val metrics = request.constraints.lineMetrics
        var lineTop = region.top

        fun fullLineFits(): Boolean =
            lineTop.value.toDouble() + metrics.height.value.toDouble() <= region.bottom.value.toDouble()

        if (request.sourceRange.start == request.sourceRange.endExclusive) {
            if (!fullLineFits()) {
                return ParagraphCompositionResult.Success(emptyList(), null, hasUnplacedTrailingEmptyLine = true)
            }
            return when (val empty = emptyLine(request, request.sourceRange, materialization)) {
                is EditableLineResult.Success -> ParagraphCompositionResult.Success(
                    listOf(place(empty.line, region, lineTop)),
                    remainingSourceRange = null,
                )
                is EditableLineResult.Failure -> ParagraphCompositionResult.Failure(empty.error, empty.diagnostics)
                is EditableLineResult.Cancelled -> ParagraphCompositionResult.Cancelled(empty.diagnostics)
            }
        }

        var lineStart = request.sourceRange.start
        while (lineStart < request.sourceRange.endExclusive) {
            if (request.cancellationToken.isCancellationRequested()) return ParagraphCompositionResult.Cancelled()
            if (!fullLineFits()) {
                return ParagraphCompositionResult.Success(placed, TextRange(lineStart, request.sourceRange.endExclusive))
            }

            when (
                val selected = selectFinalLine(
                    request = request,
                    start = lineStart,
                    candidates = candidatesForLine(request, lineStart),
                    sourceClusters = sourceClusters,
                    provisionalRuns = provisionalRuns,
                    materialization = materialization,
                )
            ) {
                is FinalizationResult.Success -> {
                    val ellipsisPolicy = request.overflowPolicy as? OverflowPolicy.Ellipsis
                    val middleWanted = ellipsisPolicy?.side == EllipsisSide.MIDDLE && lineStart == request.sourceRange.start
                    if ((!selected.fits || middleWanted) && ellipsisPolicy != null) {
                        val truncated = truncateCurrentLine(
                            request = request,
                            lineStart = lineStart,
                            candidates = candidatesForLine(request, lineStart),
                            sourceClusters = sourceClusters,
                            provisionalRuns = provisionalRuns,
                            materialization = materialization,
                        )
                        if (truncated != null) {
                            placed += place(truncated.line, region, lineTop, truncated.fontInstances)
                            return ParagraphCompositionResult.Success(
                                lines = placed,
                                remainingSourceRange = null,
                                truncation = truncated.truncation,
                            )
                        }
                    }
                    check(selected.line.range.endExclusive > lineStart) {
                        "Paragraph composition must strictly advance at every selected line."
                    }
                    placed += place(selected.line, region, lineTop, selected.fontInstances)
                    lineStart = selected.line.range.endExclusive
                }
                is FinalizationResult.Failure -> return ParagraphCompositionResult.Failure(selected.error, selected.diagnostics)
                is FinalizationResult.Cancelled -> return ParagraphCompositionResult.Cancelled(selected.diagnostics)
            }
            lineTop = finiteUnit(lineTop.value.toDouble() + metrics.height.value.toDouble(), "paragraph line top")
        }

        val trailingEmptyRequired = request.lineBreakAnalysis.opportunities.any { opportunity ->
            opportunity.boundary == request.sourceRange.endExclusive && opportunity.kind == LineBreakKind.MANDATORY
        }
        if (trailingEmptyRequired) {
            if (!fullLineFits()) {
                return ParagraphCompositionResult.Success(placed, null, hasUnplacedTrailingEmptyLine = true)
            }
            val emptyRange = TextRange(request.sourceRange.endExclusive, request.sourceRange.endExclusive)
            when (val empty = emptyLine(request, emptyRange, materialization)) {
                is EditableLineResult.Success -> placed += place(empty.line, region, lineTop)
                is EditableLineResult.Failure -> return ParagraphCompositionResult.Failure(empty.error, empty.diagnostics)
                is EditableLineResult.Cancelled -> return ParagraphCompositionResult.Cancelled(empty.diagnostics)
            }
        }
        return ParagraphCompositionResult.Success(placed, remainingSourceRange = null)
    }

    private fun projectComposition(
        request: ParagraphLayoutRequest,
        composition: ParagraphCompositionResult.Success,
    ): ParagraphLayoutResult {
        val projectedLines = mutableListOf<LineLayout>()
        composition.lines.forEach { composed ->
            if (request.cancellationToken.isCancellationRequested()) return ParagraphLayoutResult.Cancelled()
            when (val projected = projectLine(composed, request.cancellationToken)) {
                is ProjectedLine.Success -> projectedLines += projected.line
                is ProjectedLine.Failure -> return ParagraphLayoutResult.Failure(projected.error, projected.diagnostics)
                is ProjectedLine.Cancelled -> return ParagraphLayoutResult.Cancelled(projected.diagnostics)
            }
        }
        val remaining = composition.remainingSourceRange ?: composition.takeIf { it.hasUnplacedTrailingEmptyLine }
            ?.let { TextRange(request.sourceRange.endExclusive, request.sourceRange.endExclusive) }
        val continuation = remaining?.let { remainingRange ->
            LayoutContinuation.create(
                request = request,
                remainingSourceRange = remainingRange,
                resumptionRegionTop = composition.lines.lastOrNull()?.lineBox?.bottom ?: request.constraints.region.top,
            )
        }
        val range = if (remaining == null) {
            request.sourceRange
        } else {
            TextRange(request.sourceRange.start, remaining.start)
        }
        val layout = FinalParagraphLayout(request.snapshot, request.lineBreakAnalysis, range, projectedLines)
        return if (composition.truncation != null) {
            ParagraphLayoutResult.Success(
                layout = layout,
                coverageStatus = CoverageStatus.TRUNCATED,
                truncation = composition.truncation,
            )
        } else {
            ParagraphLayoutResult.Success(
                layout = layout,
                coverageStatus = if (continuation == null) CoverageStatus.COMPLETE else CoverageStatus.PARTIAL,
                continuation = continuation,
            )
        }
    }

    private fun projectLine(
        composed: ComposedParagraphLine,
        cancellationToken: org.graphiks.kalligraphie.api.CancellationToken,
    ): ProjectedLine {
        if (cancellationToken.isCancellationRequested()) return ProjectedLine.Cancelled()
        val instances = composed.fontInstances.associateBy(FontInstance::key)
        val glyphBounds = mutableListOf<LayoutBounds>()
        composed.line.positionedGlyphRuns.forEach { run ->
            val instance = instances[run.fontInstanceKey]
                ?: return ProjectedLine.Failure(
                    ParagraphLayoutError.FontFailure(
                        FontError.InvalidFontData("No resolved font instance matches a final positioned glyph run."),
                    ),
                )
            run.glyphs.forEach { glyph ->
                if (cancellationToken.isCancellationRequested()) return ProjectedLine.Cancelled()
                when (val metrics = instance.metrics(glyph.shapedGlyph.glyphId)) {
                    is FontOperationResult.Success -> metrics.value.scaledBounds
                        .takeUnless { it == LayoutBounds.empty }
                        ?.let { bounds ->
                            glyphBounds += translatedGlyphBounds(
                                LayoutPoint(
                                    finiteUnit(composed.baseline.x.value.toDouble() + glyph.origin.x.value.toDouble(), "glyph paragraph origin x"),
                                    finiteUnit(composed.baseline.y.value.toDouble() + glyph.origin.y.value.toDouble(), "glyph paragraph origin y"),
                                ),
                                bounds,
                            )
                        }
                    is FontOperationResult.Failure -> return ProjectedLine.Failure(
                        ParagraphLayoutError.FontFailure(metrics.error),
                        metrics.diagnostics.map(::fontDiagnostic),
                    )
                    is FontOperationResult.Cancelled -> return ProjectedLine.Cancelled(
                        metrics.diagnostics.map(::fontDiagnostic),
                    )
                }
            }
        }
        if (cancellationToken.isCancellationRequested()) return ProjectedLine.Cancelled()
        val inkBounds = glyphBounds.unionOrBaseline(composed.baseline)
        val contentMetrics = LineContentMetrics(
            ascent = finiteUnit(
                maxOf(0.0, composed.baseline.y.value.toDouble() - inkBounds.minY.value.toDouble()),
                "line content ascent",
            ),
            descent = finiteUnit(
                maxOf(0.0, inkBounds.maxY.value.toDouble() - composed.baseline.y.value.toDouble()),
                "line content descent",
            ),
            inlineAdvance = composed.inlineAdvance,
        )
        return try {
            ProjectedLine.Success(
                LineLayout(
                    line = composed.line,
                    baseline = composed.baseline,
                    contentMetrics = contentMetrics,
                    lineBox = composed.lineBox,
                    designInkBounds = inkBounds,
                ),
            )
        } catch (invalidGeometry: IllegalArgumentException) {
            ProjectedLine.Failure(
                ParagraphLayoutError.GeometryOverflow(
                    "Final line geometry could not be represented with finite layout coordinates: " +
                        (invalidGeometry.message ?: "invalid final line geometry."),
                ),
            )
        }
    }

    private fun translatedGlyphBounds(origin: LayoutPoint, bounds: LayoutBounds): LayoutBounds = LayoutBounds(
        minX = finiteUnit(origin.x.value.toDouble() + bounds.minX.value.toDouble(), "glyph ink min x"),
        minY = finiteUnit(origin.y.value.toDouble() - bounds.maxY.value.toDouble(), "glyph ink min y"),
        maxX = finiteUnit(origin.x.value.toDouble() + bounds.maxX.value.toDouble(), "glyph ink max x"),
        maxY = finiteUnit(origin.y.value.toDouble() - bounds.minY.value.toDouble(), "glyph ink max y"),
    )

    private fun List<LayoutBounds>.unionOrBaseline(baseline: LayoutPoint): LayoutBounds {
        if (isEmpty()) {
            return LayoutBounds(baseline.x, baseline.y, baseline.x, baseline.y)
        }
        return LayoutBounds(
            minX = minOf { it.minX },
            minY = minOf { it.minY },
            maxX = maxOf { it.maxX },
            maxY = maxOf { it.maxY },
        )
    }

    private sealed interface ProjectedLine {
        data class Success(val line: LineLayout) : ProjectedLine
        class Failure(
            val error: ParagraphLayoutError,
            diagnostics: List<EditableLineDiagnostic> = emptyList(),
        ) : ProjectedLine {
            val diagnostics: List<EditableLineDiagnostic> = diagnostics.immutableSnapshot()
        }

        class Cancelled(
            diagnostics: List<EditableLineDiagnostic> = emptyList(),
        ) : ProjectedLine {
            val diagnostics: List<EditableLineDiagnostic> = diagnostics.immutableSnapshot()
        }
    }

    private fun candidatesForLine(request: ParagraphLayoutRequest, start: TextIndex): List<TextIndex> {
        val opportunities = request.lineBreakAnalysis.opportunities.filter { opportunity ->
            opportunity.boundary > start && opportunity.boundary <= request.sourceRange.endExclusive
        }
        val firstMandatory = opportunities.firstOrNull { it.kind == LineBreakKind.MANDATORY }
        val terminal = firstMandatory?.boundary ?: request.sourceRange.endExclusive
        val automatic = if (request.hyphenationMode == HyphenationMode.AUTO) {
            automaticCandidatesInSegment(request, start, terminal)
        } else {
            emptyList()
        }
        return buildList {
            opportunities.takeWhile { opportunity -> opportunity.boundary <= terminal }.forEach { add(it.boundary) }
            addAll(automatic)
            if (lastOrNull() != terminal) add(terminal)
        }.distinct().sortedWith(TextIndex::compareTo)
    }

    /** Service candidates strictly inside the segment, when the service serves the language. */
    private fun automaticCandidatesInSegment(request: ParagraphLayoutRequest, start: TextIndex, terminal: TextIndex): List<TextIndex> {
        val service = request.hyphenationService ?: return emptyList()
        if (!service.identity.languagesSnapshot.contains(request.language)) return emptyList()
        val textScalars = request.snapshot.scalars
        val startOrdinal = snapshotOrdinal(request.snapshot, start)
        val terminalOrdinal = snapshotOrdinal(request.snapshot, terminal)
        val result = mutableListOf<TextIndex>()
        var cursor = startOrdinal
        while (cursor < terminalOrdinal) {
            if (!textScalars[cursor].isHyphenationLetter()) {
                cursor += 1
                continue
            }
            var wordEnd = cursor
            while (wordEnd < terminalOrdinal && textScalars[wordEnd].isHyphenationLetter()) wordEnd += 1
            if (wordEnd - cursor >= MIN_WORD_SCALARS) {
                val word = textScalars.subList(cursor, wordEnd).toList()
                service.hyphenation(word, request.language).forEach { offset ->
                    if (offset > 0 && offset < word.size) {
                        result += request.snapshot.textIndexAtScalarBoundary(cursor + offset)
                    }
                }
            }
            cursor = wordEnd
        }
        return result
    }

    private fun selectFinalLine(
        request: ParagraphLayoutRequest,
        start: TextIndex,
        candidates: List<TextIndex>,
        sourceClusters: List<TextRange>,
        provisionalRuns: List<ShapedGlyphRun>,
        materialization: EditableLineMaterialization,
    ): FinalizationResult {
        require(candidates.isNotEmpty())
        candidates.asReversed().forEach { boundary ->
            val finalized = finalizeLine(
                request,
                TextRange(start, boundary),
                sourceClusters,
                provisionalRuns,
                materialization,
            )
            when (finalized) {
                is FinalizationResult.Success -> {
                    val fits = ExactEditableLineLayouter.inlineAdvance(finalized.line).value <= request.constraints.width.value
                    if (fits || boundary == candidates.first()) return finalized.copy(fits = fits)
                }
                is FinalizationResult.Failure -> return finalized
                is FinalizationResult.Cancelled -> return finalized
            }
        }
        error("The first complete legal line candidate must always be selectable.")
    }

    private fun finalizeLine(
        request: ParagraphLayoutRequest,
        lineRange: TextRange,
        sourceClusters: List<TextRange>,
        provisionalRuns: List<ShapedGlyphRun>,
        materialization: EditableLineMaterialization,
        ellipsis: LineEllipsisPolicy? = null,
    ): FinalizationResult {
        val finalAnalysis = analysisForLine(request, lineRange, resetLineTrailingWhitespace = true)
        val finalRuns = mutableListOf<ShapedGlyphRun>()
        val instances = mutableListOf<FontInstance>()
        val diagnostics = mutableListOf<EditableLineDiagnostic>()
        if (request.hyphenationMode == HyphenationMode.AUTO && request.hyphenationService == null) {
            diagnostics += hyphenationServiceAbsentDiagnostic()
        }
        finalShapingRanges(request, lineRange, sourceClusters, provisionalRuns).forEach { contextRange ->
            if (request.cancellationToken.isCancellationRequested()) return FinalizationResult.Cancelled(diagnostics)
            when (
                val resolved = FontFallbackResolver.resolveRange(
                    request = request,
                    sourceRange = contextRange,
                    shapingContextRange = lineRange,
                    unicodeAnalysis = finalAnalysis,
                    materialization = materialization,
                )
            ) {
                is FontOperationResult.Success -> {
                    finalRuns += resolved.value.shapedRuns
                    instances += resolved.value.instances
                    diagnostics += resolved.value.diagnostics.map(::fontDiagnostic)
                }
                is FontOperationResult.Failure -> return FinalizationResult.Failure(
                    EditableLineError.FontResolutionFailure(resolved.error),
                    diagnostics + resolved.diagnostics.map(::fontDiagnostic),
                )
                is FontOperationResult.Cancelled -> return FinalizationResult.Cancelled(
                    diagnostics + resolved.diagnostics.map(::fontDiagnostic),
                )
            }
        }
        val uniqueInstances = instances.distinctBy(FontInstance::key)
        return when (
            val positioned = ExactEditableLineLayouter.layout(
                EditableLineRequest(
                    unicodeAnalysis = finalAnalysis,
                    shapedGlyphRuns = coalesceRuns(finalRuns),
                    baseDirection = request.baseDirection.shapingDirection(),
                    font = uniqueInstances.firstOrNull(),
                    fontInstances = uniqueInstances,
                    verticalMetrics = request.constraints.lineMetrics,
                    materialization = materialization,
                    softHyphenPolicy = lineSoftHyphenPolicy(request, lineRange),
                    snapshot = request.snapshot,
                    positioning = request.positioning,
                    targetInlineExtent = request.constraints.width,
                    isLastLine = lineRange.endExclusive == request.sourceRange.endExclusive,
                    automaticHyphenBreaks = lineAutomaticBreaks(request, lineRange),
                    ellipsis = ellipsis,
                    inlineObjects = request.inlineObjects,
                    cancellationToken = request.cancellationToken,
                ),
            )
        ) {
            is EditableLineResult.Success -> FinalizationResult.Success(
                line = EditableLine(
                    range = positioned.line.range,
                    baseDirection = positioned.line.baseDirection,
                    verticalMetrics = positioned.line.verticalMetrics,
                    positionedGlyphRuns = positioned.line.positionedGlyphRuns,
                    caretCandidates = positioned.line.allCaretCandidates,
                    inlineObjects = positioned.line.positionedInlineObjects,
                    diagnostics = positioned.line.diagnostics + diagnostics,
                ),
                fontInstances = uniqueInstances,
                fits = true,
            )
            is EditableLineResult.Failure -> FinalizationResult.Failure(positioned.error, diagnostics + positioned.diagnostics)
            is EditableLineResult.Cancelled -> FinalizationResult.Cancelled(diagnostics + positioned.diagnostics)
        }
    }

    private fun coalesceRuns(runs: List<ShapedGlyphRun>): List<ShapedGlyphRun> {
        val result = mutableListOf<ShapedGlyphRun>()
        runs.forEach { run ->
            val previous = result.lastOrNull()
            if (previous != null && canCoalesce(previous, run)) {
                result[result.lastIndex] = coalesce(previous, run)
            } else {
                result += run
            }
        }
        return result
    }

    private fun canCoalesce(left: ShapedGlyphRun, right: ShapedGlyphRun): Boolean =
        left.range.endExclusive == right.range.start &&
            left.fontInstanceKey == right.fontInstanceKey &&
            left.backendIdentity == right.backendIdentity &&
            left.direction == right.direction &&
            left.script == right.script &&
            left.language == right.language &&
            left.bidiLevel == right.bidiLevel &&
            left.featurePolicy == right.featurePolicy &&
            left.features == right.features

    private fun coalesce(left: ShapedGlyphRun, right: ShapedGlyphRun): ShapedGlyphRun {
        val nextToken = (left.clusters.maxOfOrNull { cluster -> cluster.token.value } ?: -1) + 1
        val rightTokens = right.clusters.mapIndexed { index, cluster -> cluster.token to ShaperClusterToken(nextToken + index) }.toMap()
        val remappedRightGlyphs = right.glyphs.map { glyph ->
            ShapedGlyph(
                glyphId = glyph.glyphId,
                xAdvance = glyph.xAdvance,
                yAdvance = glyph.yAdvance,
                xOffset = glyph.xOffset,
                yOffset = glyph.yOffset,
                safetyFlags = glyph.safetyFlags,
                clusterTokens = glyph.clusterTokens.map(rightTokens::getValue),
            )
        }
        val remappedRightClusters = right.clusters.map { cluster ->
            ShaperCluster(
                token = rightTokens.getValue(cluster.token),
                sourceRange = cluster.sourceRange,
                scalarRanges = cluster.scalarRanges,
                admissibleGraphemeBoundaries = cluster.admissibleGraphemeBoundaries,
            )
        }
        val rtl = left.direction == ShapingDirection.RIGHT_TO_LEFT
        val leftFacts = left.ligatureCaretFacts.map { fact -> fact.shifted(if (rtl) right.glyphs.size else 0) }
        val rightFacts = right.ligatureCaretFacts.map { fact -> fact.shifted(if (rtl) 0 else left.glyphs.size) }
        return ShapedGlyphRun(
            range = TextRange(left.range.start, right.range.endExclusive),
            fontInstanceKey = left.fontInstanceKey,
            backendIdentity = left.backendIdentity,
            direction = left.direction,
            script = left.script,
            language = left.language,
            bidiLevel = left.bidiLevel,
            bot = left.bot,
            eot = right.eot,
            featurePolicy = left.featurePolicy,
            features = left.features,
            graphemeClusters = left.graphemeClusters + right.graphemeClusters,
            glyphs = if (rtl) remappedRightGlyphs + left.glyphs else left.glyphs + remappedRightGlyphs,
            clusters = left.clusters + remappedRightClusters,
            ligatureCaretFacts = leftFacts + rightFacts,
        )
    }

    private fun GdefLigatureCaretFact.shifted(glyphOffset: Int): GdefLigatureCaretFact =
        GdefLigatureCaretFact(
            glyphIndex = glyphOffset + glyphIndex,
            state = state,
            logicalSourceBoundaries = logicalSourceBoundaries,
            positions = positions,
        )

    /** Splits at provisionally certified safe boundaries and expands only unsafe edge contexts. */
    private fun finalShapingRanges(
        request: ParagraphLayoutRequest,
        lineRange: TextRange,
        sourceClusters: List<TextRange>,
        provisionalRuns: List<ShapedGlyphRun>,
    ): List<TextRange> {
        val lineClusters = sourceClusters.filter { cluster ->
            cluster.start >= lineRange.start && cluster.endExclusive <= lineRange.endExclusive
        }
        if (lineClusters.size <= 1) return listOf(lineRange)

        var startContextEnd = lineRange.start
        if (lineRange.start != request.sourceRange.start) {
            var index = 0
            startContextEnd = lineClusters.first().endExclusive
            while (index + 1 < lineClusters.size) {
                val nextSafety = safetyFor(lineClusters[index + 1], provisionalRuns)
                if (!nextSafety.unsafeToBreak && !nextSafety.unsafeToConcat) break
                index += 1
                startContextEnd = lineClusters[index].endExclusive
            }
        }

        var endContextStart = lineRange.endExclusive
        if (lineRange.endExclusive != request.sourceRange.endExclusive) {
            var index = lineClusters.lastIndex
            endContextStart = lineClusters[index].start
            val nextCluster = sourceClusters.firstOrNull { cluster -> cluster.start == lineRange.endExclusive }
            var boundaryUnsafe = nextCluster?.let { safetyFor(it, provisionalRuns).unsafeToBreak } == true
            while (index > 0) {
                val currentSafety = safetyFor(lineClusters[index], provisionalRuns)
                if (!boundaryUnsafe && !currentSafety.unsafeToBreak && !currentSafety.unsafeToConcat) break
                index -= 1
                endContextStart = lineClusters[index].start
                boundaryUnsafe = false
            }
        }

        if (startContextEnd >= endContextStart) return listOf(lineRange)
        return buildList {
            var cursor = lineRange.start
            if (startContextEnd > cursor) {
                add(TextRange(cursor, startContextEnd))
                cursor = startContextEnd
            }
            if (endContextStart > cursor) {
                add(TextRange(cursor, endContextStart))
                cursor = endContextStart
            }
            if (lineRange.endExclusive > cursor) add(TextRange(cursor, lineRange.endExclusive))
        }
    }

    private fun safetyFor(range: TextRange, runs: List<ShapedGlyphRun>): UnitSafety {
        var unsafeToBreak = false
        var unsafeToConcat = false
        runs.filter { run -> overlaps(run.range, range) }.forEach { run ->
            val clusters = run.clusters.associateBy { it.token }
            run.glyphs.forEach { glyph ->
                val overlapsRange = glyph.clusterTokens.any { token ->
                    clusters[token]?.sourceRange?.let { cluster -> overlaps(cluster, range) } == true
                }
                if (overlapsRange) {
                    unsafeToBreak = unsafeToBreak || glyph.safetyFlags.unsafeToBreak
                    unsafeToConcat = unsafeToConcat || glyph.safetyFlags.unsafeToConcat
                }
            }
        }
        return UnitSafety(unsafeToBreak, unsafeToConcat)
    }

    private fun analysisForLine(
        request: ParagraphLayoutRequest,
        range: TextRange,
        resetLineTrailingWhitespace: Boolean,
    ): UnicodeAnalysis {
        if (range.start == range.endExclusive) {
            return UnicodeAnalysis(range, request.unicodeAnalysis.unicodeData, emptyList(), emptyList(), emptyList(), emptyList())
        }
        val graphemes = request.unicodeAnalysis.graphemeClusters.mapNotNull { intersection(it, range) }
        val scripts = request.unicodeAnalysis.scriptLanguageRuns.mapNotNull { source ->
            intersection(source.range, range)?.let { clipped -> ScriptLanguageRun(clipped, source.script, source.language) }
        }
        val baseLevel = if (request.baseDirection == BaseDirection.LEFT_TO_RIGHT) 0 else 1
        val levels = request.snapshot.scalarRanges(range).map { scalarRange ->
            val paragraphLevel = request.unicodeAnalysis.logicalBidiRuns.first { bidi -> overlaps(bidi.range, scalarRange) }.level
            MutableSourceLevel(scalarRange, paragraphLevel, bidiClass(request.snapshot.scalarValues(scalarRange).single()))
        }.toMutableList()

        // UAX #9 §5.2 retained-X9 model, before applying the per-line L1 reset.
        levels.forEachIndexed { index, item ->
            if (item.bidiClass.isRetainedX9()) item.level = levels.getOrNull(index - 1)?.level ?: baseLevel
        }
        if (resetLineTrailingWhitespace) applyL1(levels, baseLevel)

        val logical = mutableListOf<BidiRun>()
        levels.forEach { item ->
            val previous = logical.lastOrNull()
            if (previous != null && previous.level == item.level && previous.range.endExclusive == item.range.start) {
                logical[logical.lastIndex] = BidiRun(TextRange(previous.range.start, item.range.endExclusive), item.level)
            } else {
                logical += BidiRun(item.range, item.level)
            }
        }
        return UnicodeAnalysis(
            range = range,
            unicodeData = request.unicodeAnalysis.unicodeData,
            graphemeClusters = graphemes,
            scriptLanguageRuns = scripts,
            logicalBidiRuns = logical,
            visualBidiRuns = reorderVisualRuns(logical),
        )
    }

    private fun applyL1(levels: MutableList<MutableSourceLevel>, baseLevel: Int) {
        levels.indices.forEach { index ->
            if (levels[index].bidiClass == L1BidiClass.B || levels[index].bidiClass == L1BidiClass.S) {
                levels[index].level = baseLevel
                var preceding = index - 1
                while (preceding >= 0 && levels[preceding].bidiClass.isL1SequenceMember()) {
                    levels[preceding].level = baseLevel
                    preceding -= 1
                }
            }
        }
        var trailing = levels.lastIndex
        while (trailing >= 0 && levels[trailing].bidiClass.isL1SequenceMember()) {
            levels[trailing].level = baseLevel
            trailing -= 1
        }
    }

    private fun reorderVisualRuns(logical: List<BidiRun>): List<BidiRun> {
        val reordered = logical.toMutableList()
        val maximum = logical.maxOfOrNull(BidiRun::level) ?: return emptyList()
        val lowestOdd = logical.map(BidiRun::level).filter { it % 2 != 0 }.minOrNull() ?: return reordered
        for (level in maximum downTo lowestOdd) {
            var start = 0
            while (start < reordered.size) {
                while (start < reordered.size && reordered[start].level < level) start++
                var end = start
                while (end < reordered.size && reordered[end].level >= level) end++
                if (start < end) reordered.subList(start, end).reverse()
                start = end
            }
        }
        return reordered
    }

    private data class TruncatedLine(
        val line: EditableLine,
        val fontInstances: List<FontInstance>,
        val truncation: ParagraphTruncation,
    )

    /**
     * Publishes a single truncated line for the complete requested source range.
     *
     * The truncation anchor [b0] (and [b1] for middle truncation) is chosen by
     * measuring shaped prefix and suffix candidates with a synthetic ellipsis
     * marker. Hidden content is described explicitly by [ParagraphTruncation];
     * the published line keeps the complete source range with suppressed glyphs
     * for the hidden scalars. Returns `null` when no candidate configuration
     * can publish a marker within the region, in which case the caller keeps
     * the ordinary over-wide line behavior.
     */
    private fun truncateCurrentLine(
        request: ParagraphLayoutRequest,
        lineStart: TextIndex,
        candidates: List<TextIndex>,
        sourceClusters: List<TextRange>,
        provisionalRuns: List<ShapedGlyphRun>,
        materialization: EditableLineMaterialization,
    ): TruncatedLine? {
        val ellipsis = request.overflowPolicy as? OverflowPolicy.Ellipsis ?: return null
        if (request.sourceRange.start != request.sourceRange.endExclusive &&
            (request.sourceRange.start != lineStart)
        ) {
            return null
        }
        val terminal = candidates.lastOrNull() ?: return null
        val width = request.constraints.width.value.toDouble()
        val prefixWidths = mutableMapOf<TextIndex, Double>()
        val prefixInstances = mutableMapOf<TextIndex, List<FontInstance>>()
        val measureBoundaries = (sourceClusters.map { it.endExclusive } + lineStart).distinct().sortedWith(TextIndex::compareTo)
            .filter { boundary -> boundary > lineStart && boundary <= terminal }
        measureBoundaries.asReversed().forEach { boundary ->
            when (val finalized = finalizeLine(request, TextRange(lineStart, boundary), sourceClusters, provisionalRuns, materialization)) {
                is FinalizationResult.Success -> {
                    prefixWidths[boundary] = ExactEditableLineLayouter.inlineAdvance(finalized.line).value.toDouble()
                    prefixInstances[boundary] = finalized.fontInstances
                }
                else -> Unit
            }
        }
        val markerWidth = widthOfEllipsisMarker(prefixInstances.values.firstOrNull().orEmpty())
        val terminalWidth = prefixWidths[terminal] ?: return null
        val side = ellipsis.side
        return when (side) {
            EllipsisSide.INLINE_END -> {
                val b0 = prefixWidths.entries
                    .lastOrNull { (boundary, w) -> w + markerWidth <= width }?.key ?: return null
                truncateWithPolicy(request, sourceClusters, provisionalRuns, materialization,
                    TextRange(b0, terminal), side, markerWidth, width, prefixInstances)
            }
            EllipsisSide.INLINE_START -> {
                val suffixCandidates = measureBoundaries
                var chosen = TextRange(lineStart, lineStart)
                var suffixFound = false
                suffixCandidates.asReversed().forEach { boundary ->
                    if (suffixFound) return@forEach
                    val suffixRange = TextRange(boundary, terminal)
                    if (suffixRange.start == suffixRange.endExclusive) return@forEach
                    if (prefixWidths[boundary] != null) {
                        val suffixWidth = terminalWidth - (prefixWidths[boundary] ?: 0.0)
                        if (suffixWidth + markerWidth <= width) {
                            chosen = suffixRange
                            suffixFound = true
                        }
                    }
                }
                if (!suffixFound) return null
                truncateWithPolicy(request, sourceClusters, provisionalRuns, materialization,
                    TextRange(lineStart, chosen.start), side, markerWidth, width, prefixInstances)
            }
            EllipsisSide.MIDDLE -> {
                var suffixStart: TextIndex? = null
                var suffixWidth = 0.0
                measureBoundaries.asReversed().forEach { boundary ->
                    if (suffixStart != null) return@forEach
                    val suffixRange = TextRange(boundary, terminal)
                    if (suffixRange.start == suffixRange.endExclusive) return@forEach
                    val w = prefixWidths[boundary]?.let { terminalWidth - it } ?: 0.0
                    if (w + markerWidth <= width) {
                        suffixStart = boundary
                        suffixWidth = w
                    }
                }
                val startB0 = suffixStart ?: return null
                val b0 = prefixWidths.entries
                    .sortedWith { left, right -> left.key.compareTo(right.key) }
                    .lastOrNull { (_, w) -> w + markerWidth + suffixWidth <= width }?.key ?: return null
                truncateWithPolicy(request, sourceClusters, provisionalRuns, materialization,
                    TextRange(b0, startB0), side, markerWidth, width, prefixInstances)
            }
        }
    }

    private fun truncateWithPolicy(
        request: ParagraphLayoutRequest,
        sourceClusters: List<TextRange>,
        provisionalRuns: List<ShapedGlyphRun>,
        materialization: EditableLineMaterialization,
        hiddenRange: TextRange,
        side: EllipsisSide,
        markerWidth: Double,
        width: Double,
        prefixInstances: Map<TextIndex, List<FontInstance>>,
    ): TruncatedLine? {
        val fullRange = request.sourceRange
        val finalized = finalizeLine(
            request = request,
            lineRange = fullRange,
            sourceClusters = sourceClusters,
            provisionalRuns = provisionalRuns,
            materialization = materialization,
            ellipsis = LineEllipsisPolicy(side, hiddenRange),
        )
        return when (finalized) {
            is FinalizationResult.Success -> {
                val anchor = when (side) {
                    EllipsisSide.INLINE_START -> hiddenRange.endExclusive
                    EllipsisSide.INLINE_END, EllipsisSide.MIDDLE -> hiddenRange.start
                }
                TruncatedLine(finalized.line, finalized.fontInstances, ParagraphTruncation(hiddenRange, anchor, side))
            }
            else -> null
        }
    }

    private fun widthOfEllipsisMarker(instances: List<FontInstance>): Double {
        val instance = instances.firstOrNull() ?: return 0.0
        val glyph = (instance.resolveGlyph(0x2026) as? FontOperationResult.Success)?.value
        if (glyph != null && glyph.glyphId.value != 0) {
            val adv = (instance.metrics(glyph.glyphId) as? FontOperationResult.Success)?.value?.advanceWidth
            if (adv != null) return adv.value.toDouble()
        }
        val dot = (instance.resolveGlyph(0x2E) as? FontOperationResult.Success)?.value?.glyphId
        val dotAdv = dot?.let { (instance.metrics(it) as? FontOperationResult.Success)?.value?.advanceWidth }
        return if (dotAdv != null) dotAdv.value.toDouble() * 3.0 else 0.0
    }

    private fun emptyLine(
        request: ParagraphLayoutRequest,
        range: TextRange,
        materialization: EditableLineMaterialization,
    ): EditableLineResult = ExactEditableLineLayouter.layout(
        EditableLineRequest(
            unicodeAnalysis = UnicodeAnalysis(
                range,
                request.unicodeAnalysis.unicodeData,
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
            ),
            shapedGlyphRuns = emptyList(),
            baseDirection = request.baseDirection.shapingDirection(),
            emptyLineBidiLevel = if (request.baseDirection == BaseDirection.LEFT_TO_RIGHT) 0 else 1,
            verticalMetrics = request.constraints.lineMetrics,
            materialization = materialization,
            softHyphenPolicy = SoftHyphenLinePolicy(emptyList()),
            snapshot = request.snapshot,
            positioning = request.positioning,
            targetInlineExtent = request.constraints.width,
            isLastLine = true,
            cancellationToken = request.cancellationToken,
        ),
    )

    /**
     * Returns the soft-hyphen handling for a finalized line.
     *
     * A visible hyphen is published only when the line ends exactly after a
     * soft-hyphen scalar and content follows that boundary in the requested
     * source range, i.e. the soft hyphen was used as a wrap opportunity.
     * Every other soft hyphen stays suppressed.
     */
    /**
     * Returns automatic hyphen breaks materialized on a finalized line.
     *
     * A break is materialized when the line ends exactly at an automatic
     * service candidate inside a word and content follows in the requested
     * source range.
     */
    private fun lineAutomaticBreaks(request: ParagraphLayoutRequest, lineRange: TextRange): AutomaticHyphenBreaks? {
        if (request.hyphenationMode != HyphenationMode.AUTO) return null
        if (lineRange.start == lineRange.endExclusive) return null
        val service = request.hyphenationService ?: return null
        val end = lineRange.endExclusive
        if (end == request.sourceRange.endExclusive) return null
        if (!service.identity.languagesSnapshot.contains(request.language)) return null
        val endOrdinal = snapshotOrdinal(request.snapshot, end)
        val textScalars = request.snapshot.scalars
        if (endOrdinal <= 0 || endOrdinal > textScalars.size || !textScalars[endOrdinal - 1].isHyphenationLetter()) {
            return null
        }
        var wordStart = endOrdinal - 1
        while (wordStart > 0 && textScalars[wordStart - 1].isHyphenationLetter()) wordStart -= 1
        var wordEnd = endOrdinal
        val paragraphEnd = snapshotOrdinal(request.snapshot, request.sourceRange.endExclusive)
        while (wordEnd < paragraphEnd && textScalars[wordEnd].isHyphenationLetter()) wordEnd += 1
        val word = textScalars.subList(wordStart, wordEnd).toList()
        if (word.size < MIN_WORD_SCALARS) return null
        val breaks = service.hyphenation(word, request.language)
        return if (endOrdinal - wordStart in breaks) AutomaticHyphenBreaks(listOf(end)) else null
    }

    private fun automaticBreakCandidates(request: ParagraphLayoutRequest, start: TextIndex, endExclusive: TextIndex): Set<TextIndex> {
        val service = request.hyphenationService ?: return emptySet()
        val textScalars = request.snapshot.scalars
        val startOrdinal = snapshotOrdinal(request.snapshot, start)
        val endOrdinal = snapshotOrdinal(request.snapshot, endExclusive)
        val candidates = mutableSetOf<TextIndex>()
        var cursor = startOrdinal
        while (cursor < endOrdinal) {
            if (!textScalars[cursor].isHyphenationLetter()) {
                cursor += 1
                continue
            }
            var wordEnd = cursor
            while (wordEnd < endOrdinal && textScalars[wordEnd].isHyphenationLetter()) wordEnd += 1
            if (wordEnd - cursor >= MIN_WORD_SCALARS && service.identity.languagesSnapshot.contains(request.language)) {
                val word = textScalars.subList(cursor, wordEnd).toList()
                service.hyphenation(word, request.language).forEach { offset ->
                    if (offset > 0 && offset < word.size) {
                        candidates += request.snapshot.textIndexAtScalarBoundary(cursor + offset)
                    }
                }
            }
            cursor = wordEnd
        }
        return candidates
    }

    private fun snapshotOrdinal(snapshot: org.graphiks.kalligraphie.api.TextSnapshot, boundary: TextIndex): Int {
        require(boundary.sharesVersionWith(snapshot.range.start)) { "Hyphenation boundaries must use the paragraph source revision." }
        return (0..snapshot.scalars.size).firstOrNull { index -> snapshot.textIndexAtScalarBoundary(index) == boundary }
            ?: throw IllegalArgumentException("Hyphenation boundary does not belong to the paragraph snapshot.")
    }

    private fun Int.isHyphenationLetter(): Boolean =
        this in 0x41..0x5A || this in 0x61..0x7A || this in 0xC0..0x24F

    private fun hyphenationServiceAbsentDiagnostic(): EditableLineDiagnostic = EditableLineDiagnostic(
        code = "layout.hyphenation-service-absent",
        severity = EditableLineDiagnosticSeverity.WARNING,
        message = "Automatic hyphenation was requested without an available versioned hyphenation service; the layout stays valid without automatic césure.",
    )

    private fun lineSoftHyphenPolicy(request: ParagraphLayoutRequest, lineRange: TextRange): SoftHyphenLinePolicy {
        if (lineRange.start == lineRange.endExclusive) return SoftHyphenLinePolicy(emptyList())
        val end = lineRange.endExclusive
        if (end == request.sourceRange.endExclusive) return SoftHyphenLinePolicy(emptyList())
        return if (request.snapshot.scalarPreceding(end) == SOFT_HYPHEN_SCALAR) {
            SoftHyphenLinePolicy(listOf(end))
        } else {
            SoftHyphenLinePolicy(emptyList())
        }
    }

    private fun place(
        line: EditableLine,
        region: LayoutRect,
        top: LayoutUnit,
        fontInstances: List<FontInstance> = emptyList(),
    ): ComposedParagraphLine {
        val baseline = LayoutPoint(
            region.left,
            finiteUnit(top.value.toDouble() + line.verticalMetrics.ascent.value.toDouble(), "paragraph baseline"),
        )
        val bottom = finiteUnit(top.value.toDouble() + line.verticalMetrics.height.value.toDouble(), "paragraph line bottom")
        return ComposedParagraphLine(
            line = line,
            baseline = baseline,
            lineBox = LayoutRect(region.left, top, region.right, bottom),
            inlineAdvance = ExactEditableLineLayouter.inlineAdvance(line),
            fontInstances = fontInstances,
        )
    }

    private fun finiteUnit(value: Double, label: String): LayoutUnit {
        val narrowed = value.toFloat()
        if (!value.isFinite() || !narrowed.isFinite()) {
            throw ParagraphGeometryOverflowException("$label overflowed finite layout coordinates.")
        }
        return LayoutUnit(narrowed)
    }

    private fun intersection(left: TextRange, right: TextRange): TextRange? {
        val start = if (left.start >= right.start) left.start else right.start
        val end = if (left.endExclusive <= right.endExclusive) left.endExclusive else right.endExclusive
        return if (start < end) TextRange(start, end) else null
    }

    private fun overlaps(left: TextRange, right: TextRange): Boolean =
        left.start < right.endExclusive && right.start < left.endExclusive

    private fun BaseDirection.shapingDirection(): ShapingDirection = when (this) {
        BaseDirection.LEFT_TO_RIGHT -> ShapingDirection.LEFT_TO_RIGHT
        BaseDirection.RIGHT_TO_LEFT -> ShapingDirection.RIGHT_TO_LEFT
    }

    private fun EditableLineMaterialization.identity(): ParagraphMaterializationIdentity = when (this) {
        EditableLineMaterialization.LayoutOnly -> ParagraphMaterializationIdentity.LayoutOnly
        is EditableLineMaterialization.Renderable -> ParagraphMaterializationIdentity.Renderable(variant, outlineProfile)
    }

    private fun bidiClass(scalar: Int): L1BidiClass = when (scalar) {
        0x000A, 0x000D, in 0x001C..0x001E, 0x0085, 0x2029 -> L1BidiClass.B
        0x0009, 0x000B, 0x001F -> L1BidiClass.S
        0x000C, 0x0020, 0x1680, in 0x2000..0x200A, 0x2028, 0x205F, 0x3000 -> L1BidiClass.WS
        0x202A, 0x202B, 0x202C, 0x202D, 0x202E -> L1BidiClass.X9_FORMAT
        0x2066, 0x2067, 0x2068, 0x2069 -> L1BidiClass.ISOLATE_FORMAT
        else -> if (scalar.isUnicode16BoundaryNeutral()) L1BidiClass.BN else L1BidiClass.OTHER
    }

    /** Exact Unicode 16.0 DerivedBidiClass=BN ranges used by retained-X9 L1 processing. */
    private fun Int.isUnicode16BoundaryNeutral(): Boolean =
        this in 0x0000..0x0008 ||
            this in 0x000E..0x001B ||
            this in 0x007F..0x0084 ||
            this in 0x0086..0x009F ||
            this == 0x00AD ||
            this == 0x180E ||
            this in 0x200B..0x200D ||
            this in 0x2060..0x2065 ||
            this in 0x206A..0x206F ||
            this in 0xFDD0..0xFDEF ||
            this == 0xFEFF ||
            this in 0xFFF0..0xFFF8 ||
            this in 0xFFFE..0xFFFF ||
            this in 0x1BCA0..0x1BCA3 ||
            this in 0x1D173..0x1D17A ||
            this in 0x1FFFE..0x1FFFF ||
            this in 0x2FFFE..0x2FFFF ||
            this in 0x3FFFE..0x3FFFF ||
            this in 0x4FFFE..0x4FFFF ||
            this in 0x5FFFE..0x5FFFF ||
            this in 0x6FFFE..0x6FFFF ||
            this in 0x7FFFE..0x7FFFF ||
            this in 0x8FFFE..0x8FFFF ||
            this in 0x9FFFE..0x9FFFF ||
            this in 0xAFFFE..0xAFFFF ||
            this in 0xBFFFE..0xBFFFF ||
            this in 0xCFFFE..0xCFFFF ||
            this in 0xDFFFE..0xE0001 ||
            this in 0xE0002..0xE007F ||
            this in 0xE0080..0xE00FF ||
            this in 0xE01F0..0xE0FFF ||
            this in 0xEFFFE..0xEFFFF ||
            this in 0xFFFFE..0xFFFFF ||
            this in 0x10FFFE..0x10FFFF

    private data class UnitSafety(val unsafeToBreak: Boolean, val unsafeToConcat: Boolean)
    private data class MutableSourceLevel(val range: TextRange, var level: Int, val bidiClass: L1BidiClass)

    private enum class L1BidiClass {
        B,
        S,
        WS,
        BN,
        X9_FORMAT,
        ISOLATE_FORMAT,
        OTHER,
        ;

        fun isRetainedX9(): Boolean = this == BN || this == X9_FORMAT

        fun isL1SequenceMember(): Boolean =
            this == WS || this == BN || this == X9_FORMAT || this == ISOLATE_FORMAT
    }

    private sealed interface FinalizationResult {
        data class Success(
            val line: EditableLine,
            val fontInstances: List<FontInstance>,
            val fits: Boolean,
        ) : FinalizationResult
        data class Failure(val error: EditableLineError, val diagnostics: List<EditableLineDiagnostic>) : FinalizationResult
        data class Cancelled(val diagnostics: List<EditableLineDiagnostic>) : FinalizationResult
    }
}

/** Final immutable paragraph projection whose editing operations use paragraph-coordinate values. */
private class FinalParagraphLayout(
    snapshot: org.graphiks.kalligraphie.api.TextSnapshot,
    lineBreakAnalysis: LineBreakAnalysis,
    range: TextRange,
    lines: List<LineLayout>,
) : ParagraphLayout(snapshot, lineBreakAnalysis, range, lines) {
    override fun nextLogical(
        position: CaretPosition,
        direction: LogicalNavigationDirection,
    ): CaretPosition? {
        require(candidatesAt(position).isNotEmpty()) { "Logical navigation requires a paragraph-local caret position." }
        val boundaries = allCandidates().map { it.position.index }.distinct().sortedWith(TextIndex::compareTo)
        val current = boundaries.indexOf(position.index)
        val target = when (direction) {
            LogicalNavigationDirection.FORWARD -> boundaries.getOrNull(current + 1)
            LogicalNavigationDirection.BACKWARD -> boundaries.getOrNull(current - 1)
        } ?: return null
        return allCandidates().firstOrNull { candidate ->
            candidate.position.index == target && candidate.position.affinity == position.affinity
        }?.position ?: allCandidates().first { it.position.index == target }.position
    }

    override fun nextVisual(
        candidate: CaretCandidate,
        direction: VisualNavigationDirection,
    ): CaretCandidate? {
        val candidates = allCandidates()
        val current = candidates.indexOfFirst { it === candidate }
        require(current >= 0) { "Visual navigation requires a candidate returned by this paragraph." }
        return when (direction) {
            VisualNavigationDirection.FORWARD -> candidates.getOrNull(current + 1)
            VisualNavigationDirection.BACKWARD -> candidates.getOrNull(current - 1)
        }
    }

    override fun caretCandidates(position: CaretPosition): List<CaretCandidate> {
        require(position.index.sharesVersionWith(range.start)) { "Caret positions must use the paragraph source revision." }
        val candidates = candidatesAt(position)
        require(candidates.isNotEmpty()) { "Caret candidates require a paragraph-local caret position." }
        return candidates.immutableSnapshot()
    }

    private fun candidatesAt(position: CaretPosition): List<CaretCandidate> =
        allCandidates().filter { it.position == position }

    private fun allCandidates(): List<CaretCandidate> = lines.flatMap(LineLayout::allCaretCandidates)
}

private fun EditableLineError.toParagraphError(): ParagraphLayoutError = when (this) {
    is EditableLineError.InvalidInput -> ParagraphLayoutError.InvalidInput(message)
    is EditableLineError.GeometryOverflow -> ParagraphLayoutError.GeometryOverflow(message)
    is EditableLineError.FontMaterializationFailure -> ParagraphLayoutError.FontFailure(fontError)
    is EditableLineError.ShapingFailure -> ParagraphLayoutError.FontFailure(fontError)
    is EditableLineError.FontResolutionFailure -> ParagraphLayoutError.FontFailure(fontError)
}

private class ParagraphGeometryOverflowException(message: String) : IllegalStateException(message)

    private fun <Element> Iterable<Element>.immutableSnapshot(): List<Element> = ParagraphImmutableList(toList())

private class ParagraphImmutableList<Element>(source: List<Element>) : AbstractMutableList<Element>() {
    private val elements: List<Element> = source.toList()

    override val size: Int
        get() = elements.size

    override fun get(index: Int): Element = elements[index]

    override fun add(index: Int, element: Element): Unit = immutableMutation()

    override fun removeAt(index: Int): Element = immutableMutation()

    override fun set(index: Int, element: Element): Element = immutableMutation()

    private fun <Value> immutableMutation(): Value = throw UnsupportedOperationException("Immutable paragraph composition snapshot.")
}

/** Unicode scalar value of `U+00AD SOFT HYPHEN`. */
private const val SOFT_HYPHEN_SCALAR: Int = 0x00AD

/** Minimum scalars required to consider automatic hyphenation of a word. */
private const val MIN_WORD_SCALARS: Int = 6
package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.api.CaretCandidate
import org.graphiks.kalligraphie.api.CaretAffinity
import org.graphiks.kalligraphie.api.CaretPosition
import org.graphiks.kalligraphie.api.EditableLine
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.FlowChain
import org.graphiks.kalligraphie.api.FlowCompositionDiagnostic
import org.graphiks.kalligraphie.api.FlowCompositionError
import org.graphiks.kalligraphie.api.FlowCompositionInputIdentity
import org.graphiks.kalligraphie.api.FlowCompositionResult
import org.graphiks.kalligraphie.api.FlowContinuation
import org.graphiks.kalligraphie.api.FlowFragmentationCommitment
import org.graphiks.kalligraphie.api.FlowFragmentProvenance
import org.graphiks.kalligraphie.api.FlowLayoutConfigurationSignature
import org.graphiks.kalligraphie.api.FlowLayoutState
import org.graphiks.kalligraphie.api.FlowParagraphLayouter
import org.graphiks.kalligraphie.api.FlowRegion
import org.graphiks.kalligraphie.api.FlowRegionResult
import org.graphiks.kalligraphie.api.FragmentationConstraintKind
import org.graphiks.kalligraphie.api.InlineInterval
import org.graphiks.kalligraphie.api.InlineObjectSnapshot
import org.graphiks.kalligraphie.api.LayoutBounds
import org.graphiks.kalligraphie.api.LayoutPoint
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutSegment
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineBand
import org.graphiks.kalligraphie.api.LineFragment
import org.graphiks.kalligraphie.api.LineLayout
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.NoProgressReason
import org.graphiks.kalligraphie.api.OverflowPolicy
import org.graphiks.kalligraphie.api.ParagraphConstraints
import org.graphiks.kalligraphie.api.ParagraphFragment
import org.graphiks.kalligraphie.api.ParagraphLayoutError
import org.graphiks.kalligraphie.api.ParagraphLayoutRequest
import org.graphiks.kalligraphie.api.PositionedGlyph
import org.graphiks.kalligraphie.api.PositionedGlyphRun
import org.graphiks.kalligraphie.api.PositionedInlineObject
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.WritingMode
import org.graphiks.kalligraphie.api.queryFlowRegion

/**
 * Pure flow-region and flow-chain adapter over the exact paragraph line finalizer.
 *
 * Line composition finalizes one logical line against the sum of stable logical intervals. Chain
 * composition repeats that primitive for complete lines in one region and returns an exact
 * continuation at its source and region boundary. Fragment boundaries only translate and split
 * already-positioned visual content at existing shaping-cluster boundaries; they never restart
 * shaping or UAX #9 resolution.
 */
public object FlowParagraphComposer : FlowParagraphLayouter {
    private const val IMPLEMENTATION_REFINEMENT_LIMIT: Int = 32
    private const val IMPLEMENTATION_EMPTY_TRANSITION_LIMIT: Int = 32

    /**
     * Places an already-prepared source range as exactly one complete logical line in [region].
     *
     * The region is queried in the writing mode from [request], beginning at [blockStart]. Stable
     * intervals are refined before shaping output is finalized, and the returned fragment covers
     * the complete request range or no fragment is published. Rectangular paragraph continuations
     * and ranges requiring more than one line are rejected as typed paragraph failures. Region
     * protocol violations, cancellation, shaping failures, and lack of usable space are likewise
     * returned through [FlowCompositionResult.Failure].
     *
     * [materialization] and [region] are borrowed synchronously and are never retained. This
     * stateless operation is safe for concurrent calls when the supplied capabilities satisfy
     * their own concurrency contracts.
     *
     * @param request complete Unicode, line-break, font, shaping, and positioning inputs.
     * @param materialization requested layout-only or profile-certified publication capability.
     * @param region application-owned geometry provider queried for the line band.
     * @param blockStart finite non-negative logical block-axis offset at which placement begins.
     * @return one final [ParagraphFragment] on success, otherwise a typed composition failure.
     */
    override fun layoutLine(
        request: ParagraphLayoutRequest,
        materialization: EditableLineMaterialization,
        region: FlowRegion,
        blockStart: Float,
    ): FlowCompositionResult<ParagraphFragment> {
        if (request.continuation != null) {
            return paragraphFailure("Line-level flow composition does not consume paragraph continuations.")
        }
        return when (val attempt = composeLine(request, materialization, region, blockStart)) {
            is LineAttempt.Failure -> attempt.failure
            LineAttempt.EndOfRegion -> noSpaceForFirstUnit(request)
            is LineAttempt.Placed -> {
                if (attempt.line.range != request.sourceRange) {
                    paragraphFailure(
                        "Line-level flow composition requires the request range to fit one complete logical line.",
                    )
                } else {
                    FlowCompositionResult.Success(
                        ParagraphFragment(
                            paragraphRange = request.sourceRange,
                            laidOutRange = request.sourceRange,
                            isFirstFragment = true,
                            isLastFragment = true,
                            lines = listOf(attempt.line),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Composes the next source-consecutive paragraph fragment through [chain].
     *
     * Composition begins at [continuation]'s exact region and block cursor when supplied, otherwise
     * at the first usable region. Only complete logical lines are published. [maximumLines] bounds
     * publication after line finalization while preserving exact continuation, fragmentation, and
     * region state; `null` permits the selected region fragment to run to its natural boundary.
     * A positive bound is required. [flowConfiguration] attaches the complete portable provenance
     * required when the fragment will be retained in [FlowLayoutState].
     *
     * The method verifies text and typography identity, complete replay inputs, chain and region
     * revision identity, writing mode, cursor, fragmentation policy, and source suffix before a
     * continuation is consumed. Unsupported overflow, incompatible or foreign continuations,
     * cancellation, invalid region responses, shaping failures, and lack of progress are returned
     * as typed [FlowCompositionResult.Failure] values. No partial fragment is published on failure.
     *
     * The composer is stateless. [request], [materialization], and [chain] are borrowed only for
     * this call; returned fragments and continuations retain no region provider, font resource,
     * renderer, page, shaping backend, or native handle. Concurrent calls are safe when borrowed
     * capabilities satisfy their own concurrency contracts.
     *
     * @param request prepared paragraph request whose source range is the exact suffix to compose.
     * @param materialization requested layout-only or profile-certified publication capability.
     * @param chain ordered application-owned flow regions and fragmentation constraints.
     * @param inputIdentity exact text and typography revisions used to prepare [request].
     * @param continuation structured replay state for [request], or `null` for initial composition.
     * @param maximumLines optional positive limit on complete lines published by this call.
     * @param flowConfiguration optional complete configuration provenance for incremental state.
     * @return the next immutable [ParagraphFragment], or a typed failure without publication.
     * @throws IllegalArgumentException when [maximumLines] is non-null and not positive.
     */
    override fun layoutFragment(
        request: ParagraphLayoutRequest,
        materialization: EditableLineMaterialization,
        chain: FlowChain,
        inputIdentity: FlowCompositionInputIdentity,
        continuation: FlowContinuation?,
        maximumLines: Int?,
        flowConfiguration: FlowLayoutConfigurationSignature?,
    ): FlowCompositionResult<ParagraphFragment> {
        require(maximumLines == null || maximumLines > 0) {
            "A bounded flow fragment must allow at least one complete line."
        }
        if (request.continuation != null) {
            return paragraphFailure("Flow-chain composition does not consume rectangular paragraph continuations.")
        }
        if (inputIdentity.textVersion != request.snapshot.version) {
            return FlowCompositionResult.Failure(FlowCompositionError.TextIdentityMismatch)
        }
        if (request.overflowPolicy != OverflowPolicy.Continue) {
            return FlowCompositionResult.Failure(
                FlowCompositionError.UnsupportedOverflowPolicy(request.overflowPolicy),
            )
        }
        if (request.cancellationToken.isCancellationRequested()) {
            return FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
        }
        if (continuation != null) {
            when (val validation = chain.validateContinuation(continuation, request, inputIdentity)) {
                is FlowCompositionResult.Failure -> return validation
                is FlowCompositionResult.Success -> Unit
            }
        }

        val paragraphRange = continuation?.paragraphRange ?: request.sourceRange
        val fragmentationCommitment = continuation?.fragmentationCommitment
        val initiallyRelaxed = continuation?.relaxedConstraints.orEmpty()
        val relaxed = initiallyRelaxed.toMutableList()
        val newlyRelaxed = mutableListOf<FragmentationConstraintKind>()
        val constraints = chain.fragmentationConstraints
        if (
            constraints.keepWithNext &&
            FragmentationConstraintKind.KEEP_WITH_NEXT !in relaxed
        ) {
            relaxed += FragmentationConstraintKind.KEEP_WITH_NEXT
            newlyRelaxed += FragmentationConstraintKind.KEEP_WITH_NEXT
        }
        val startRegionIndex = continuation?.regionIndex ?: 0
        val startBlockOffset = continuation?.nextBlockOffset ?: 0f
        var regionIndex = startRegionIndex
        var blockOffset = startBlockOffset
        val isFirstFragment = request.sourceRange.start == paragraphRange.start
        val candidates = mutableListOf<RegionCandidate>()
        val needsUnboundedFeasibilityProbe = fragmentationCommitment == null && maximumLines != null && (
            constraints.keepTogether && isFirstFragment && FragmentationConstraintKind.KEEP_TOGETHER !in relaxed ||
                constraints.minLinesAtEnd > maximumLines && FragmentationConstraintKind.MIN_LINES_AT_END !in relaxed ||
                constraints.minLinesAtStart > maximumLines && FragmentationConstraintKind.MIN_LINES_AT_START !in relaxed
            )
        val feasibilityLineLimit = if (needsUnboundedFeasibilityProbe) null else maximumLines

        while (true) {
            candidates.firstOrNull { candidate ->
                candidate.satisfies(constraints, relaxed, isFirstFragment, fragmentationCommitment)
            }?.let { selected ->
                return publishFragment(
                    request = request,
                    chain = chain,
                    inputIdentity = inputIdentity,
                    paragraphRange = paragraphRange,
                    candidate = selected,
                    maximumLines = maximumLines,
                    relaxedBefore = initiallyRelaxed,
                    newlyRelaxed = newlyRelaxed,
                    activeCommitment = fragmentationCommitment,
                    flowConfiguration = flowConfiguration,
                )
            }

            while (regionIndex < chain.regions.size) {
                when (
                    val composition = composeRegion(
                        request,
                        materialization,
                        chain.regions[regionIndex],
                        blockOffset,
                        feasibilityLineLimit,
                    )
                ) {
                    is RegionComposition.Failure -> return composition.failure
                    is RegionComposition.Success -> if (composition.lines.isNotEmpty()) {
                        val candidate = RegionCandidate(composition, regionIndex)
                        candidates += candidate
                        if (candidate.satisfies(constraints, relaxed, isFirstFragment, fragmentationCommitment)) {
                            return publishFragment(
                                request = request,
                                chain = chain,
                                inputIdentity = inputIdentity,
                                paragraphRange = paragraphRange,
                                candidate = candidate,
                                maximumLines = maximumLines,
                                relaxedBefore = initiallyRelaxed,
                                newlyRelaxed = newlyRelaxed,
                                activeCommitment = fragmentationCommitment,
                                flowConfiguration = flowConfiguration,
                            )
                        }
                    }
                }
                regionIndex += 1
                blockOffset = 0f
            }

            if (candidates.isEmpty()) return noSpaceForFirstUnit(request)
            val nextRelaxation = listOf(
                FragmentationConstraintKind.KEEP_TOGETHER,
                FragmentationConstraintKind.MIN_LINES_AT_END,
                FragmentationConstraintKind.MIN_LINES_AT_START,
            ).firstOrNull { constraint ->
                constraint !in relaxed && when (constraint) {
                    FragmentationConstraintKind.KEEP_WITH_NEXT -> false
                    FragmentationConstraintKind.KEEP_TOGETHER -> constraints.keepTogether && isFirstFragment
                    FragmentationConstraintKind.MIN_LINES_AT_END -> constraints.minLinesAtEnd > 1
                    FragmentationConstraintKind.MIN_LINES_AT_START -> constraints.minLinesAtStart > 1
                }
            } ?: return noSpaceForFirstUnit(request)
            relaxed += nextRelaxation
            newlyRelaxed += nextRelaxation
        }
    }

    private fun composeRegion(
        request: ParagraphLayoutRequest,
        materialization: EditableLineMaterialization,
        region: FlowRegion,
        initialBlockOffset: Float,
        maximumLines: Int?,
    ): RegionComposition {
        val lines = mutableListOf<LineLayout>()
        val lineBlockEnds = mutableListOf<Float>()
        var remainingStart = request.sourceRange.start
        var blockOffset = initialBlockOffset
        var emptyLineRequired = request.sourceRange.start == request.sourceRange.endExclusive
        while (emptyLineRequired || remainingStart < request.sourceRange.endExclusive) {
            val remaining = TextRange(remainingStart, request.sourceRange.endExclusive)
            when (val attempt = composeLine(request.withSourceRange(remaining), materialization, region, blockOffset)) {
                is LineAttempt.Failure -> return RegionComposition.Failure(attempt.failure)
                LineAttempt.EndOfRegion -> return RegionComposition.Success(lines, lineBlockEnds, blockOffset, false)
                is LineAttempt.Placed -> {
                    if (
                        attempt.line.range.start != remainingStart ||
                        !emptyLineRequired && attempt.line.range.endExclusive <= remainingStart
                    ) {
                        return RegionComposition.Failure(
                            paragraphFailure("Every flow line must make exact consecutive source progress."),
                        )
                    }
                    lines += attempt.line
                    remainingStart = attempt.line.range.endExclusive
                    blockOffset = attempt.blockStart + attempt.blockExtent
                    lineBlockEnds += blockOffset
                    emptyLineRequired = attempt.hasUnplacedTrailingEmptyLine
                    val physicalParagraphComplete =
                        remainingStart == request.sourceRange.endExclusive && !emptyLineRequired
                    if (maximumLines != null && lines.size >= maximumLines && !physicalParagraphComplete) {
                        return RegionComposition.Success(
                            lines,
                            lineBlockEnds,
                            blockOffset,
                            false,
                            stoppedByLineLimit = true,
                        )
                    }
                }
            }
        }
        return RegionComposition.Success(lines, lineBlockEnds, blockOffset, true, stoppedByLineLimit = false)
    }

    private fun publishFragment(
        request: ParagraphLayoutRequest,
        chain: FlowChain,
        inputIdentity: FlowCompositionInputIdentity,
        paragraphRange: TextRange,
        candidate: RegionCandidate,
        maximumLines: Int?,
        relaxedBefore: List<FragmentationConstraintKind>,
        newlyRelaxed: List<FragmentationConstraintKind>,
        activeCommitment: FlowFragmentationCommitment?,
        flowConfiguration: FlowLayoutConfigurationSignature?,
    ): FlowCompositionResult<ParagraphFragment> {
        val lines = maximumLines?.let(candidate.composition.lines::take) ?: candidate.composition.lines
        val laidOutRange = TextRange(lines.first().range.start, lines.last().range.endExclusive)
        val stoppedInsideRegion =
            candidate.composition.stoppedByLineLimit || lines.size < candidate.composition.lines.size
        val publicationIsComplete = candidate.composition.isComplete && !stoppedInsideRegion
        val continuation = if (publicationIsComplete) {
            null
        } else {
            val nextIndex = if (stoppedInsideRegion || candidate.regionIndex + 1 >= chain.regions.size) {
                candidate.regionIndex
            } else {
                candidate.regionIndex + 1
            }
            val nextOffset = if (stoppedInsideRegion) {
                candidate.composition.lineBlockEnds[lines.lastIndex]
            } else if (nextIndex == candidate.regionIndex) {
                candidate.composition.nextBlockOffset
            } else {
                0f
            }
            chain.createContinuation(
                inputIdentity = inputIdentity,
                request = request,
                paragraphRange = paragraphRange,
                remainingSourceRange = TextRange(laidOutRange.endExclusive, request.sourceRange.endExclusive),
                regionIndex = nextIndex,
                writingMode = request.constraints.writingMode,
                nextBlockOffset = nextOffset.coerceAtMost(chain.regions[nextIndex].logicalBlockExtent(request.constraints.writingMode)),
                relaxedConstraints = relaxedBefore + newlyRelaxed,
                fragmentationCommitment = when {
                    activeCommitment != null -> {
                        val remaining = activeCommitment.remainingLineCount - lines.size
                        if (remaining > 0) activeCommitment.copy(remainingLineCount = remaining) else null
                    }
                    lines.size < candidate.composition.lines.size -> FlowFragmentationCommitment(
                        regionIndex = candidate.regionIndex,
                        regionIdentity = chain.regions[candidate.regionIndex].identity,
                        remainingLineCount = candidate.composition.lines.size - lines.size,
                    )
                    else -> null
                },
            )
        }
        val diagnostics = newlyRelaxed.map { constraint ->
            FlowCompositionDiagnostic.FragmentationRelaxed(constraint, paragraphRange, candidate.regionIndex)
        }
        return FlowCompositionResult.Success(
            ParagraphFragment(
                paragraphRange = paragraphRange,
                laidOutRange = laidOutRange,
                isFirstFragment = laidOutRange.start == paragraphRange.start,
                isLastFragment = continuation == null,
                lines = lines,
                continuation = continuation,
                diagnostics = diagnostics,
                flowProvenance = flowConfiguration?.let { configuration ->
                    FlowFragmentProvenance(
                        inputIdentity = inputIdentity,
                        flowCompositionIdentity = chain.compositionIdentity,
                        regionIndex = candidate.regionIndex,
                        regionIdentity = chain.regions[candidate.regionIndex].identity,
                        paragraphRange = paragraphRange,
                        laidOutRange = laidOutRange,
                        configuration = configuration,
                    )
                },
            ),
            diagnostics,
        )
    }

    private fun composeLine(
        request: ParagraphLayoutRequest,
        materialization: EditableLineMaterialization,
        region: FlowRegion,
        blockStart: Float,
    ): LineAttempt {
        if (request.cancellationToken.isCancellationRequested()) {
            return LineAttempt.Failure(FlowCompositionResult.Failure(FlowCompositionError.Cancelled))
        }
        var currentBlockStart = blockStart
        var bandExtent = request.constraints.lineMetrics.height.value
        var previousBand: LineBand? = null
        var previousIntervals: List<InlineInterval>? = null
        var refinements = 0
        var emptyTransitions = 0
        val seen = mutableSetOf<RefinementFingerprint>()

        while (true) {
            if (region.maximumRefinements <= 0) {
                return LineAttempt.Failure(
                    FlowCompositionResult.Failure(
                        FlowCompositionError.FlowRegionRefinementLimitExceeded(region.maximumRefinements),
                    ),
                )
            }
            if (currentBlockStart.toDouble() + bandExtent.toDouble() > region.logicalBlockExtent(request.constraints.writingMode)) {
                return LineAttempt.EndOfRegion
            }
            val effectiveRefinementLimit = minOf(region.maximumRefinements, IMPLEMENTATION_REFINEMENT_LIMIT)
            if (refinements >= effectiveRefinementLimit) {
                return LineAttempt.Failure(
                    FlowCompositionResult.Failure(
                        FlowCompositionError.FlowRegionRefinementLimitExceeded(effectiveRefinementLimit),
                    ),
                )
            }
            refinements += 1
            val band = LineBand(currentBlockStart, bandExtent)
            val precedingBand = previousBand
            if (
                precedingBand != null &&
                precedingBand.blockStart == band.blockStart &&
                band.blockExtent < precedingBand.blockExtent
            ) {
                return LineAttempt.Failure(
                    FlowCompositionResult.Failure(
                        FlowCompositionError.ShrinkingFlowLineBand(precedingBand, band),
                    ),
                )
            }
            val queried = stableQuery(region, request.constraints.writingMode, band)
            val regionResult = when (queried) {
                is FlowCompositionResult.Success -> queried.value
                is FlowCompositionResult.Failure -> return LineAttempt.Failure(queried)
            }
            when (regionResult) {
                is FlowRegionResult.Empty -> {
                    emptyTransitions += 1
                    if (emptyTransitions > IMPLEMENTATION_EMPTY_TRANSITION_LIMIT) {
                        return LineAttempt.Failure(
                            FlowCompositionResult.Failure(
                                FlowCompositionError.FlowRegionRefinementLimitExceeded(
                                    IMPLEMENTATION_EMPTY_TRANSITION_LIMIT,
                                ),
                            ),
                        )
                    }
                    currentBlockStart = regionResult.nextBlockOffset
                    previousBand = null
                    previousIntervals = null
                    bandExtent = request.constraints.lineMetrics.height.value
                    refinements = 0
                    seen.clear()
                    continue
                }

                FlowRegionResult.EndOfRegion -> return LineAttempt.EndOfRegion
                is FlowRegionResult.AvailableIntervals -> {
                    val intervals = regionResult.intervals
                    if (previousIntervals != null && !intervals.areCoveredByUnionOf(previousIntervals)) {
                        return LineAttempt.Failure(
                            FlowCompositionResult.Failure(
                                FlowCompositionError.NonMonotoneFlowRegion(
                                    checkNotNull(precedingBand),
                                    band,
                                ),
                            ),
                        )
                    }
                    previousBand = band
                    val totalInlineExtent = intervals.sumOf { interval ->
                        interval.endExclusive.toDouble() - interval.start.toDouble()
                    }
                    if (!totalInlineExtent.isFinite() || totalInlineExtent <= 0.0 || !totalInlineExtent.toFloat().isFinite()) {
                        return LineAttempt.Failure(
                            geometryOverflow("The total flow inline extent overflowed finite layout coordinates."),
                        )
                    }
                    val composition = when (
                        val composed = composePackableLine(
                            request.withSingleLineExtent(totalInlineExtent.toFloat()),
                            materialization,
                            intervals,
                        )
                    ) {
                        is FlowCompositionResult.Success -> composed.value
                        is FlowCompositionResult.Failure -> return LineAttempt.Failure(composed)
                    }
                    val candidate = composition.lines.singleOrNull()
                        ?: return LineAttempt.Failure(
                            paragraphFailure("Flow line composition did not produce exactly one complete candidate line."),
                        )
                    val measured = when (
                        val projection = ParagraphComposer.projectLine(candidate, request.cancellationToken)
                    ) {
                        is ParagraphComposer.ProjectedLine.Success -> projection.line
                        is ParagraphComposer.ProjectedLine.Failure -> return LineAttempt.Failure(
                            FlowCompositionResult.Failure(
                                FlowCompositionError.ParagraphFailure(projection.error),
                            ),
                        )

                        is ParagraphComposer.ProjectedLine.Cancelled ->
                            return LineAttempt.Failure(
                                FlowCompositionResult.Failure(FlowCompositionError.Cancelled),
                            )
                    }
                    val requiredMetrics = requiredBlockMetrics(candidate.line, measured.designInkBounds, measured.baseline)
                    val requiredBandExtent = requiredMetrics.extent
                    if (!requiredBandExtent.isFinite()) {
                        return LineAttempt.Failure(
                            geometryOverflow("The refined flow line band overflowed finite layout coordinates."),
                        )
                    }
                    val fingerprint = RefinementFingerprint(
                        bandExtent = bandExtent,
                        intervals = intervals.map { it.start to it.endExclusive },
                        lineRange = candidate.line.range,
                    )
                    if (requiredBandExtent > bandExtent) {
                        if (!seen.add(fingerprint)) {
                            return LineAttempt.Failure(
                                FlowCompositionResult.Failure(
                                    FlowCompositionError.FlowRegionRefinementCycle(band),
                                ),
                            )
                        }
                        previousIntervals = intervals
                        bandExtent = requiredBandExtent
                        continue
                    }
                    return when (val published = publishLine(request, region, band, intervals, candidate, requiredMetrics)) {
                        is FlowCompositionResult.Failure -> LineAttempt.Failure(published)
                        is FlowCompositionResult.Success -> LineAttempt.Placed(
                            published.value,
                            band.blockStart,
                            band.blockExtent,
                            composition.hasUnplacedTrailingEmptyLine,
                        )
                    }
                }
            }
        }
    }

    private fun composePackableLine(
        request: ParagraphLayoutRequest,
        materialization: EditableLineMaterialization,
        intervals: List<InlineInterval>,
    ): FlowCompositionResult<ParagraphCompositionResult.Success> {
        val initial = composeBoundedCandidate(request, materialization, maximumEndExclusive = null)
        val initialSuccess = when (initial) {
            is FlowCompositionResult.Success -> initial.value
            is FlowCompositionResult.Failure -> return initial
        }
        val initialLine = initialSuccess.lines.single()
        when (val packing = probePacking(initialLine.line, intervals, request.constraints.writingMode)) {
            PackingProbe.Complete -> return initial
            is PackingProbe.Failure -> {
                var firstLogicalFailure: PackingProbe.Failure = packing
                val logicalPrefixEnds = logicalPackingPrefixEnds(
                    request.unicodeAnalysis.graphemeClusters,
                    initialLine.line.range,
                )
                logicalPrefixEnds.forEach { prefixEnd ->
                    val prefix = when (
                        val composed = composeBoundedCandidate(request, materialization, prefixEnd)
                    ) {
                        is FlowCompositionResult.Success -> composed
                        is FlowCompositionResult.Failure -> return composed
                    }
                    when (
                        val prefixPacking = probePacking(
                            prefix.value.lines.single().line,
                            intervals,
                            request.constraints.writingMode,
                        )
                    ) {
                        PackingProbe.Complete -> return prefix
                        is PackingProbe.Failure -> firstLogicalFailure = prefixPacking
                    }
                }
                return FlowCompositionResult.Failure(firstLogicalFailure.error)
            }
        }
    }

    private fun composeBoundedCandidate(
        request: ParagraphLayoutRequest,
        materialization: EditableLineMaterialization,
        maximumEndExclusive: TextIndex?,
    ): FlowCompositionResult<ParagraphCompositionResult.Success> = when (
        val composed = ParagraphComposer.composeFirstLineBounded(
            request,
            materialization,
            maximumEndExclusive = maximumEndExclusive,
        )
    ) {
        is ParagraphCompositionResult.Success -> if (composed.lines.size == 1) {
            FlowCompositionResult.Success(composed)
        } else {
            FlowCompositionResult.Failure(
                FlowCompositionError.ParagraphFailure(
                    ParagraphLayoutError.InvalidInput(
                        "Flow line composition did not produce exactly one complete candidate line.",
                    ),
                ),
            )
        }
        is ParagraphCompositionResult.Failure -> FlowCompositionResult.Failure(
            FlowCompositionError.ParagraphFailure(composed.error.toParagraphError()),
        )
        is ParagraphCompositionResult.Cancelled -> FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
    }

    private fun probePacking(
        line: EditableLine,
        intervals: List<InlineInterval>,
        writingMode: WritingMode,
    ): PackingProbe {
        var fragmentIndex = 0
        var cursor = intervals.first().start.toDouble()
        var precedingEnd = 0.0
        line.positionedGlyphRuns.forEach { run ->
            run.atomicGlyphGroups().forEach { group ->
                val originalStart = group.first().penStart(writingMode)
                val width = group.sumOf { glyph -> glyph.inlineAdvance(writingMode) }
                val leading = (originalStart - precedingEnd).coerceAtLeast(0.0)
                if (leading > 0.0) {
                    val advanced = advanceWhitespace(intervals, fragmentIndex, cursor, leading)
                    fragmentIndex = advanced.first
                    cursor = advanced.second
                }
                val groupRange = group.sourceRange()
                val objectItem = line.positionedInlineObjects.firstOrNull { item ->
                    group.any { glyph -> rangesOverlap(glyph.mappedSourceRange, item.sourceRange) }
                }
                val maximum = intervals.maxOf { interval ->
                    interval.endExclusive.toDouble() - interval.start.toDouble()
                }
                if (width <= maximum) {
                    while (
                        fragmentIndex < intervals.size &&
                        cursor + width > intervals[fragmentIndex].endExclusive.toDouble()
                    ) {
                        fragmentIndex += 1
                        if (fragmentIndex < intervals.size) cursor = intervals[fragmentIndex].start.toDouble()
                    }
                } else {
                    fragmentIndex = intervals.size
                }
                if (fragmentIndex >= intervals.size) {
                    return PackingProbe.Failure(
                        FlowCompositionError.NoProgress(
                            objectItem?.sourceRange ?: groupRange,
                            if (objectItem == null) NoProgressReason.CLUSTER_DOES_NOT_FIT
                            else NoProgressReason.INLINE_OBJECT_DOES_NOT_FIT,
                        ),
                    )
                }
                cursor += width
                precedingEnd = originalStart + width
            }
        }
        return PackingProbe.Complete
    }

    private fun stableQuery(
        region: FlowRegion,
        writingMode: WritingMode,
        band: LineBand,
    ): FlowCompositionResult<FlowRegionResult> {
        val first = safeQuery(region, writingMode, band)
        if (first is FlowCompositionResult.Failure) return first
        val second = safeQuery(region, writingMode, band)
        if (second is FlowCompositionResult.Failure) return second
        val firstValue = (first as FlowCompositionResult.Success).value
        val secondValue = (second as FlowCompositionResult.Success).value
        val firstFingerprint = firstValue.fingerprint()
        val secondFingerprint = secondValue.fingerprint()
        if (firstFingerprint == secondFingerprint) {
            return FlowCompositionResult.Success(firstValue)
        }
        val third = safeQuery(region, writingMode, band)
        if (third is FlowCompositionResult.Failure) return third
        val thirdValue = (third as FlowCompositionResult.Success).value
        return if (thirdValue.fingerprint() in setOf(firstFingerprint, secondFingerprint)) {
            FlowCompositionResult.Failure(FlowCompositionError.FlowRegionRefinementCycle(band))
        } else {
            FlowCompositionResult.Failure(FlowCompositionError.UnstableFlowRegion(band))
        }
    }

    private fun safeQuery(
        region: FlowRegion,
        writingMode: WritingMode,
        band: LineBand,
    ): FlowCompositionResult<FlowRegionResult> = try {
        queryFlowRegion(region, writingMode, band)
    } catch (failure: RuntimeException) {
        FlowCompositionResult.Failure(
            FlowCompositionError.FlowRegionQueryFailure(band, failure::class.simpleName),
        )
    }

    private fun publishLine(
        request: ParagraphLayoutRequest,
        region: FlowRegion,
        acceptedBand: LineBand,
        intervals: List<InlineInterval>,
        candidate: ComposedParagraphLine,
        requiredMetrics: RequiredBlockMetrics,
    ): FlowCompositionResult<LineLayout> {
        val placed = try {
            candidate.atFlowPosition(region.bounds, acceptedBand, requiredMetrics.fill(acceptedBand.blockExtent))
        } catch (overflow: IllegalArgumentException) {
            return geometryOverflow("The final flow line position overflowed finite layout coordinates.")
        }
        val projectedFragments = when (
            val result = fragmentLine(
                placed.line,
                placed.baseline,
                intervals,
                request.constraints.writingMode,
            )
        ) {
            is FragmentProjection.Success -> result.fragments
            is FragmentProjection.Failure -> return FlowCompositionResult.Failure(result.error)
        }
        val projected = try {
            ParagraphComposer.projectLine(placed, request.cancellationToken, projectedFragments)
        } catch (overflow: ParagraphGeometryOverflowException) {
            return geometryOverflow(overflow.message ?: "Final flow geometry overflowed.")
        }
        return when (projected) {
            is ParagraphComposer.ProjectedLine.Success -> FlowCompositionResult.Success(projected.line)

            is ParagraphComposer.ProjectedLine.Failure -> when (val error = projected.error) {
                is ParagraphLayoutError.GeometryOverflow -> geometryOverflow(error.message)
                else -> FlowCompositionResult.Failure(FlowCompositionError.ParagraphFailure(error))
            }

            is ParagraphComposer.ProjectedLine.Cancelled ->
                FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
        }
    }

    private fun fragmentLine(
        line: EditableLine,
        baseline: LayoutPoint,
        intervals: List<InlineInterval>,
        writingMode: WritingMode,
    ): FragmentProjection {
        val glyphsByFragment = intervals.indices.map { mutableListOf<AllocatedGlyph>() }
        val allocations = mutableListOf<Allocation>()
        var fragmentIndex = 0
        var cursor = intervals.first().start.toDouble()
        var precedingEnd = 0.0

        line.positionedGlyphRuns.forEach { run ->
            run.atomicGlyphGroups().forEach { group ->
                val originalStart = group.first().penStart(writingMode)
                val width = group.sumOf { glyph -> glyph.inlineAdvance(writingMode) }
                val leading = (originalStart - precedingEnd).coerceAtLeast(0.0)
                if (leading > 0.0) {
                    val advanced = advanceWhitespace(intervals, fragmentIndex, cursor, leading)
                    fragmentIndex = advanced.first
                    cursor = advanced.second
                }
                val objectItem = line.positionedInlineObjects.firstOrNull { item ->
                    group.any { glyph -> rangesOverlap(glyph.mappedSourceRange, item.sourceRange) }
                }
                val maximum = intervals.maxOf { interval -> interval.endExclusive.toDouble() - interval.start.toDouble() }
                if (width > maximum) {
                    val range = objectItem?.sourceRange ?: group.sourceRange()
                    return FragmentProjection.Failure(
                        FlowCompositionError.NoProgress(
                            range,
                            if (objectItem == null) NoProgressReason.CLUSTER_DOES_NOT_FIT
                            else NoProgressReason.INLINE_OBJECT_DOES_NOT_FIT,
                        ),
                    )
                }
                while (fragmentIndex < intervals.size && cursor + width > intervals[fragmentIndex].endExclusive.toDouble()) {
                    fragmentIndex += 1
                    if (fragmentIndex < intervals.size) cursor = intervals[fragmentIndex].start.toDouble()
                }
                if (fragmentIndex >= intervals.size) {
                    val range = objectItem?.sourceRange ?: group.sourceRange()
                    return FragmentProjection.Failure(
                        FlowCompositionError.NoProgress(
                            range,
                            if (objectItem == null) NoProgressReason.CLUSTER_DOES_NOT_FIT
                            else NoProgressReason.INLINE_OBJECT_DOES_NOT_FIT,
                        ),
                    )
                }
                val translation = cursor - originalStart
                group.forEach { glyph ->
                    glyphsByFragment[fragmentIndex] += AllocatedGlyph(run, glyph.translatedInline(translation, baseline, writingMode))
                }
                allocations += Allocation(
                    fragmentIndex = fragmentIndex,
                    originalStart = originalStart,
                    originalEnd = originalStart + width,
                    translation = translation,
                    objectRange = objectItem?.sourceRange,
                    sourceRange = group.sourceRange(),
                )
                cursor += width
                precedingEnd = originalStart + width
            }
        }

        if (allocations.isEmpty()) {
            allocations += Allocation(0, 0.0, 0.0, intervals.first().start.toDouble(), null, null)
        }
        val runs = glyphsByFragment.map { allocated -> allocated.toProjectedRuns() }
        val carets = intervals.indices.map { mutableListOf<CaretCandidate>() }
        line.allCaretCandidates.forEach { candidate ->
            val inline = candidate.inlineCoordinate(writingMode)
            val affinityAllocations = allocations.filter { allocation ->
                when (candidate.position.affinity) {
                    CaretAffinity.DOWNSTREAM -> allocation.sourceRange?.start == candidate.position.index
                    CaretAffinity.UPSTREAM -> allocation.sourceRange?.endExclusive == candidate.position.index
                }
            }
            val allocation = (affinityAllocations.ifEmpty { allocations })
                .minWith(compareBy<Allocation>({ it.distanceFrom(inline) }, { it.fragmentIndex }))
            carets[allocation.fragmentIndex] += candidate.translatedInline(allocation.translation, baseline, writingMode)
        }
        allocations.zipWithNext().forEach transition@{ (preceding, following) ->
            if (preceding.fragmentIndex == following.fragmentIndex) return@transition
            val precedingRange = preceding.sourceRange
            val followingRange = following.sourceRange
            val boundary = when {
                precedingRange != null && followingRange != null &&
                    precedingRange.endExclusive == followingRange.start -> precedingRange.endExclusive
                precedingRange != null && followingRange != null &&
                    followingRange.endExclusive == precedingRange.start -> followingRange.endExclusive
                else -> null
            } ?: return@transition
            val boundaryCandidates = line.allCaretCandidates.filter { candidate ->
                candidate.position.index == boundary
            }
            if (boundaryCandidates.isEmpty()) return@transition
            listOf(preceding, following).forEach edge@{ allocation ->
                val range = checkNotNull(allocation.sourceRange)
                val affinity = when (boundary) {
                    range.start -> CaretAffinity.DOWNSTREAM
                    range.endExclusive -> CaretAffinity.UPSTREAM
                    else -> return@edge
                }
                val originalEdge = if (affinity == CaretAffinity.UPSTREAM) {
                    allocation.originalEnd
                } else {
                    allocation.originalStart
                }
                val template = boundaryCandidates.minBy { candidate ->
                    kotlin.math.abs(candidate.inlineCoordinate(writingMode) - originalEdge)
                }
                val projected = template.translatedInline(
                    allocation.translation,
                    baseline,
                    writingMode,
                    affinity,
                )
                val target = carets[allocation.fragmentIndex]
                if (target.none { candidate ->
                        candidate.position == projected.position && candidate.geometry == projected.geometry
                    }
                ) {
                    target += projected
                }
            }
        }
        carets.forEach { candidates -> candidates.sortBy(CaretCandidate::visualOrder) }
        val objects = intervals.indices.map { mutableListOf<PositionedInlineObject>() }
        line.positionedInlineObjects.forEach { item ->
            val allocation = allocations.firstOrNull { it.objectRange == item.sourceRange }
                ?: return FragmentProjection.Failure(
                    FlowCompositionError.NoProgress(item.sourceRange, NoProgressReason.INLINE_OBJECT_DOES_NOT_FIT),
                )
            objects[allocation.fragmentIndex] += item.translatedInline(allocation.translation, baseline, writingMode)
        }
        return FragmentProjection.Success(intervals.indices.map { index ->
            LineFragment(intervals[index], runs[index], carets[index], objects[index])
        })
    }

    private fun List<AllocatedGlyph>.toProjectedRuns(): List<PositionedGlyphRun> {
        if (isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<AllocatedGlyph>>()
        forEach { item ->
            val previous = groups.lastOrNull()
            if (previous != null && previous.last().sourceRun === item.sourceRun) previous += item
            else groups += mutableListOf(item)
        }
        return groups.map { group ->
            val sourceRun = group.first().sourceRun
            val glyphs = group.map(AllocatedGlyph::glyph)
            PositionedGlyphRun(
                sourceRun = sourceRun.sliceFor(glyphs),
                visualOrder = sourceRun.visualOrder,
                renderAssetKey = sourceRun.renderAssetKey,
                glyphs = glyphs,
            )
        }
    }

    private fun PositionedGlyphRun.sliceFor(glyphs: List<PositionedGlyph>): ShapedGlyphRun {
        val tokens = glyphs.flatMap { glyph -> glyph.shapedGlyph.clusterTokens }.toSet()
        val clusters = sourceRun.clusters.filter { cluster -> cluster.token in tokens }
        val range = TextRange(clusters.first().sourceRange.start, clusters.last().sourceRange.endExclusive)
        val sourceGlyphIndexes = sourceRun.glyphs.indices.filter { index ->
            sourceRun.glyphs[index].clusterTokens.any(tokens::contains)
        }
        val shaped = sourceGlyphIndexes.map(sourceRun.glyphs::get)
        val oldToNew = sourceGlyphIndexes.withIndex().associate { (newIndex, oldIndex) -> oldIndex to newIndex }
        val facts = sourceRun.ligatureCaretFacts.mapNotNull { fact ->
            oldToNew[fact.glyphIndex]?.let { newIndex ->
                org.graphiks.kalligraphie.api.GdefLigatureCaretFact(
                    glyphIndex = newIndex,
                    state = fact.state,
                    logicalSourceBoundaries = fact.logicalSourceBoundaries,
                    positions = fact.positions,
                )
            }
        }
        return ShapedGlyphRun(
            range = range,
            fontInstanceKey = sourceRun.fontInstanceKey,
            backendIdentity = sourceRun.backendIdentity,
            direction = sourceRun.direction,
            script = sourceRun.script,
            language = sourceRun.language,
            bidiLevel = sourceRun.bidiLevel,
            bot = sourceRun.bot && range.start == sourceRun.range.start,
            eot = sourceRun.eot && range.endExclusive == sourceRun.range.endExclusive,
            featurePolicy = sourceRun.featurePolicy,
            features = sourceRun.features,
            graphemeClusters = sourceRun.graphemeClusters.filter { cluster ->
                cluster.start >= range.start && cluster.endExclusive <= range.endExclusive
            },
            glyphs = shaped,
            clusters = clusters,
            ligatureCaretFacts = facts,
        )
    }

    private fun PositionedGlyphRun.atomicGlyphGroups(): List<List<PositionedGlyph>> {
        val groups = mutableListOf<MutableList<PositionedGlyph>>()
        val graphemes = mutableListOf<MutableSet<TextRange>>()
        glyphs.forEach { glyph ->
            val related = sourceRun.graphemeClusters.filter { grapheme ->
                glyph.sourceClusters.any { cluster -> rangesOverlap(grapheme, cluster.sourceRange) }
            }.toSet()
            if (groups.isNotEmpty() && graphemes.last().any(related::contains)) {
                groups.last() += glyph
                graphemes.last() += related
            } else {
                groups += mutableListOf(glyph)
                graphemes += related.toMutableSet()
            }
        }
        return groups
    }

    private fun requiredBlockMetrics(
        line: EditableLine,
        ink: LayoutBounds,
        baseline: LayoutPoint,
    ): RequiredBlockMetrics {
        var before = line.verticalMetrics.ascent.value.toDouble()
        var after = line.verticalMetrics.descent.value.toDouble()
        when (line.writingMode) {
            WritingMode.HORIZONTAL_TB -> {
                before = maxOf(before, baseline.y.value.toDouble() - ink.minY.value.toDouble())
                after = maxOf(after, ink.maxY.value.toDouble() - baseline.y.value.toDouble())
                line.positionedInlineObjects.forEach { item ->
                    before = maxOf(before, -item.rect.top.value.toDouble())
                    after = maxOf(after, item.rect.bottom.value.toDouble())
                }
            }

            WritingMode.VERTICAL_RL,
            WritingMode.VERTICAL_LR,
            -> {
                before = maxOf(before, baseline.x.value.toDouble() - ink.minX.value.toDouble())
                after = maxOf(after, ink.maxX.value.toDouble() - baseline.x.value.toDouble())
                line.positionedInlineObjects.forEach { item ->
                    before = maxOf(before, -item.rect.left.value.toDouble())
                    after = maxOf(after, item.rect.right.value.toDouble())
                }
            }
        }
        return RequiredBlockMetrics(before.toFloat(), after.toFloat())
    }

    private fun ParagraphLayoutRequest.withSingleLineExtent(inlineExtent: Float): ParagraphLayoutRequest {
        val blockExtent = constraints.lineMetrics.height
        val zero = LayoutUnit(0f)
        val inline = LayoutUnit(inlineExtent)
        val syntheticBounds = when (constraints.writingMode) {
            WritingMode.HORIZONTAL_TB -> LayoutRect(zero, zero, inline, blockExtent)
            WritingMode.VERTICAL_RL,
            WritingMode.VERTICAL_LR,
            -> LayoutRect(zero, zero, blockExtent, inline)
        }
        return ParagraphLayoutRequest(
            snapshot = snapshot,
            sourceRange = sourceRange,
            unicodeAnalysis = unicodeAnalysis,
            lineBreakAnalysis = lineBreakAnalysis,
            constraints = ParagraphConstraints(syntheticBounds, constraints.lineMetrics, constraints.writingMode),
            baseDirection = baseDirection,
            language = language,
            featurePolicy = featurePolicy,
            features = features,
            fontCatalog = fontCatalog,
            resolutionPolicy = resolutionPolicy,
            fontInstanceDescriptor = fontInstanceDescriptor,
            shapingBackend = shapingBackend,
            materializationIdentity = materializationIdentity,
            overflowPolicy = overflowPolicy,
            positioning = positioning,
            hyphenationMode = hyphenationMode,
            hyphenationService = hyphenationService,
            inlineObjects = inlineObjects,
            textOrientation = textOrientation,
            verticalMetricsPolicy = verticalMetricsPolicy,
            cancellationToken = cancellationToken,
        )
    }

    private fun ParagraphLayoutRequest.withSourceRange(sourceRange: TextRange): ParagraphLayoutRequest =
        ParagraphLayoutRequest(
            snapshot = snapshot,
            sourceRange = sourceRange,
            unicodeAnalysis = unicodeAnalysis,
            lineBreakAnalysis = lineBreakAnalysis,
            constraints = constraints,
            baseDirection = baseDirection,
            language = language,
            featurePolicy = featurePolicy,
            features = features,
            fontCatalog = fontCatalog,
            resolutionPolicy = resolutionPolicy,
            fontInstanceDescriptor = fontInstanceDescriptor,
            shapingBackend = shapingBackend,
            materializationIdentity = materializationIdentity,
            overflowPolicy = overflowPolicy,
            positioning = positioning,
            hyphenationMode = hyphenationMode,
            hyphenationService = hyphenationService,
            inlineObjects = inlineObjects?.let { snapshot ->
                InlineObjectSnapshot(snapshot.entries.filter { entry ->
                    entry.index >= sourceRange.start && entry.index < sourceRange.endExclusive
                })
            },
            textOrientation = textOrientation,
            verticalMetricsPolicy = verticalMetricsPolicy,
            cancellationToken = cancellationToken,
        )

    private fun ComposedParagraphLine.atFlowPosition(
        bounds: LayoutRect,
        band: LineBand,
        metrics: LineVerticalMetrics,
    ): ComposedParagraphLine {
        val refinedLine = line.withVerticalMetrics(metrics)
        return when (refinedLine.writingMode) {
            WritingMode.HORIZONTAL_TB -> {
                val top = finite(bounds.top.value.toDouble() + band.blockStart.toDouble(), "horizontal flow line top")
                val baseline = LayoutPoint(
                    bounds.left,
                    finite(top.value.toDouble() + metrics.ascent.value.toDouble(), "horizontal flow baseline"),
                )
                ComposedParagraphLine(
                    refinedLine,
                    baseline,
                    LayoutRect(
                        bounds.left,
                        top,
                        bounds.right,
                        finite(baseline.y.value.toDouble() + metrics.descent.value.toDouble(), "horizontal flow line bottom"),
                    ),
                    inlineAdvance,
                    fontInstances,
                )
            }

            WritingMode.VERTICAL_RL -> {
                val right = finite(bounds.right.value.toDouble() - band.blockStart.toDouble(), "vertical-rl flow line right")
                val baseline = LayoutPoint(
                    finite(right.value.toDouble() - metrics.descent.value.toDouble(), "vertical-rl flow baseline"),
                    bounds.top,
                )
                ComposedParagraphLine(
                    refinedLine,
                    baseline,
                    LayoutRect(
                        finite(baseline.x.value.toDouble() - metrics.ascent.value.toDouble(), "vertical-rl flow line left"),
                        bounds.top,
                        right,
                        bounds.bottom,
                    ),
                    inlineAdvance,
                    fontInstances,
                )
            }

            WritingMode.VERTICAL_LR -> {
                val left = finite(bounds.left.value.toDouble() + band.blockStart.toDouble(), "vertical-lr flow line left")
                val baseline = LayoutPoint(
                    finite(left.value.toDouble() + metrics.ascent.value.toDouble(), "vertical-lr flow baseline"),
                    bounds.top,
                )
                ComposedParagraphLine(
                    refinedLine,
                    baseline,
                    LayoutRect(
                        left,
                        bounds.top,
                        finite(baseline.x.value.toDouble() + metrics.descent.value.toDouble(), "vertical-lr flow line right"),
                        bounds.bottom,
                    ),
                    inlineAdvance,
                    fontInstances,
                )
            }
        }
    }

    private fun EditableLine.withVerticalMetrics(metrics: LineVerticalMetrics): EditableLine {
        if (verticalMetrics == metrics) return this
        val refinedCarets = allCaretCandidates.map { candidate ->
            val geometry = when (writingMode) {
                WritingMode.HORIZONTAL_TB -> LayoutSegment(
                    LayoutPoint(candidate.geometry.start.x, LayoutUnit(-metrics.ascent.value)),
                    LayoutPoint(candidate.geometry.end.x, metrics.descent),
                )

                WritingMode.VERTICAL_RL,
                WritingMode.VERTICAL_LR,
                -> LayoutSegment(
                    LayoutPoint(LayoutUnit(-metrics.ascent.value), candidate.geometry.start.y),
                    LayoutPoint(metrics.descent, candidate.geometry.end.y),
                )
            }
            CaretCandidate(
                candidate.position,
                geometry,
                candidate.visualOrder,
                candidate.visualRunOrder,
                candidate.bidiLevel,
                candidate.direction,
                candidate.strength,
                candidate.edge,
            )
        }
        return EditableLine(
            range,
            baseDirection,
            metrics,
            writingMode,
            positionedGlyphRuns,
            refinedCarets,
            positionedInlineObjects,
            diagnostics,
        )
    }

    private fun PositionedGlyph.penStart(writingMode: WritingMode): Double = when (writingMode) {
        WritingMode.HORIZONTAL_TB -> origin.x.value.toDouble() - shapedGlyph.xOffset.value.toDouble()
        WritingMode.VERTICAL_RL,
        WritingMode.VERTICAL_LR,
        -> origin.y.value.toDouble() - shapedGlyph.yOffset.value.toDouble()
    }

    private fun PositionedGlyph.inlineAdvance(writingMode: WritingMode): Double = when (writingMode) {
        WritingMode.HORIZONTAL_TB -> advance.x.value.toDouble()
        WritingMode.VERTICAL_RL,
        WritingMode.VERTICAL_LR,
        -> advance.y.value.toDouble()
    }

    private fun PositionedGlyph.translatedInline(
        inlineTranslation: Double,
        baseline: LayoutPoint,
        writingMode: WritingMode,
    ): PositionedGlyph {
        val translatedOrigin = when (writingMode) {
            WritingMode.HORIZONTAL_TB -> LayoutPoint(
                finite(origin.x.value.toDouble() + inlineTranslation + baseline.x.value.toDouble(), "fragment glyph x"),
                finite(origin.y.value.toDouble() + baseline.y.value.toDouble(), "fragment glyph y"),
            )

            WritingMode.VERTICAL_RL,
            WritingMode.VERTICAL_LR,
            -> LayoutPoint(
                finite(origin.x.value.toDouble() + baseline.x.value.toDouble(), "vertical fragment glyph x"),
                finite(origin.y.value.toDouble() + inlineTranslation + baseline.y.value.toDouble(), "vertical fragment glyph y"),
            )
        }
        return PositionedGlyph(
            shapedGlyph,
            sourceClusters,
            translatedOrigin,
            advance,
            transform,
            renderAssetKey,
            materializationCertificate,
            provenance,
        )
    }

    private fun CaretCandidate.translatedInline(
        inlineTranslation: Double,
        baseline: LayoutPoint,
        writingMode: WritingMode,
        affinity: CaretAffinity = position.affinity,
    ): CaretCandidate {
        fun point(value: LayoutPoint): LayoutPoint = when (writingMode) {
            WritingMode.HORIZONTAL_TB -> LayoutPoint(
                finite(value.x.value.toDouble() + inlineTranslation + baseline.x.value.toDouble(), "fragment caret x"),
                finite(value.y.value.toDouble() + baseline.y.value.toDouble(), "fragment caret y"),
            )

            WritingMode.VERTICAL_RL,
            WritingMode.VERTICAL_LR,
            -> LayoutPoint(
                finite(value.x.value.toDouble() + baseline.x.value.toDouble(), "vertical fragment caret x"),
                finite(value.y.value.toDouble() + inlineTranslation + baseline.y.value.toDouble(), "vertical fragment caret y"),
            )
        }
        return CaretCandidate(
            CaretPosition(position.index, affinity),
            LayoutSegment(point(geometry.start), point(geometry.end)),
            visualOrder,
            visualRunOrder,
            bidiLevel,
            direction,
            strength,
            edge,
        )
    }

    private fun PositionedInlineObject.translatedInline(
        inlineTranslation: Double,
        baseline: LayoutPoint,
        writingMode: WritingMode,
    ): PositionedInlineObject = when (writingMode) {
        WritingMode.HORIZONTAL_TB -> PositionedInlineObject(
            sourceRange,
            definition,
            LayoutRect(
                finite(rect.left.value.toDouble() + inlineTranslation + baseline.x.value.toDouble(), "fragment object left"),
                finite(rect.top.value.toDouble() + baseline.y.value.toDouble(), "fragment object top"),
                finite(rect.right.value.toDouble() + inlineTranslation + baseline.x.value.toDouble(), "fragment object right"),
                finite(rect.bottom.value.toDouble() + baseline.y.value.toDouble(), "fragment object bottom"),
            ),
        )

        WritingMode.VERTICAL_RL,
        WritingMode.VERTICAL_LR,
        -> PositionedInlineObject(
            sourceRange,
            definition,
            LayoutRect(
                finite(rect.left.value.toDouble() + baseline.x.value.toDouble(), "vertical fragment object left"),
                finite(rect.top.value.toDouble() + inlineTranslation + baseline.y.value.toDouble(), "vertical fragment object top"),
                finite(rect.right.value.toDouble() + baseline.x.value.toDouble(), "vertical fragment object right"),
                finite(rect.bottom.value.toDouble() + inlineTranslation + baseline.y.value.toDouble(), "vertical fragment object bottom"),
            ),
        )
    }

    private fun CaretCandidate.inlineCoordinate(writingMode: WritingMode): Double = when (writingMode) {
        WritingMode.HORIZONTAL_TB -> geometry.start.x.value.toDouble()
        WritingMode.VERTICAL_RL,
        WritingMode.VERTICAL_LR,
        -> geometry.start.y.value.toDouble()
    }

    private fun List<PositionedGlyph>.sourceRange(): TextRange {
        val starts = map { glyph -> glyph.mappedSourceRange.start }.sortedWith(TextIndex::compareTo)
        val ends = map { glyph -> glyph.mappedSourceRange.endExclusive }.sortedWith(TextIndex::compareTo)
        return TextRange(starts.first(), ends.last())
    }

    private fun advanceWhitespace(
        intervals: List<InlineInterval>,
        startIndex: Int,
        startCursor: Double,
        amount: Double,
    ): Pair<Int, Double> {
        var index = startIndex
        var cursor = startCursor
        var remaining = amount
        while (remaining > 0.0 && index < intervals.size) {
            val available = intervals[index].endExclusive.toDouble() - cursor
            if (remaining <= available) return index to (cursor + remaining)
            remaining -= available
            index += 1
            if (index < intervals.size) cursor = intervals[index].start.toDouble()
        }
        return index to cursor
    }

    private fun FlowRegionResult.fingerprint(): RegionAnswerFingerprint = when (this) {
        is FlowRegionResult.AvailableIntervals -> RegionAnswerFingerprint.Available(intervals)
        is FlowRegionResult.Empty -> RegionAnswerFingerprint.Empty(nextBlockOffset)
        FlowRegionResult.EndOfRegion -> RegionAnswerFingerprint.EndOfRegion
    }

    private fun List<InlineInterval>.areCoveredByUnionOf(previous: List<InlineInterval>): Boolean = all current@ { current ->
        var coveredThrough = current.start
        previous.forEach { old ->
            if (old.endExclusive <= coveredThrough) return@forEach
            if (old.start > coveredThrough) return@current false
            coveredThrough = old.endExclusive
            if (coveredThrough >= current.endExclusive) return@current true
        }
        false
    }

    private fun noSpaceForFirstUnit(request: ParagraphLayoutRequest): FlowCompositionResult.Failure {
        val objectEntry = request.inlineObjects?.entries?.firstOrNull { it.index == request.sourceRange.start }
        if (objectEntry != null) {
            val end = request.snapshot.scalarRanges(request.sourceRange).first().endExclusive
            return FlowCompositionResult.Failure(
                FlowCompositionError.NoProgress(
                    TextRange(request.sourceRange.start, end),
                    NoProgressReason.INLINE_OBJECT_DOES_NOT_FIT,
                ),
            )
        }
        val range = request.unicodeAnalysis.graphemeClusters.firstOrNull { cluster -> cluster.start == request.sourceRange.start }
            ?: request.sourceRange
        return FlowCompositionResult.Failure(
            FlowCompositionError.NoProgress(range, NoProgressReason.CLUSTER_DOES_NOT_FIT),
        )
    }

    private fun paragraphFailure(message: String): FlowCompositionResult.Failure = FlowCompositionResult.Failure(
        FlowCompositionError.ParagraphFailure(ParagraphLayoutError.InvalidInput(message)),
    )

    private fun geometryOverflow(message: String): FlowCompositionResult.Failure = FlowCompositionResult.Failure(
        FlowCompositionError.GeometryOverflow(message),
    )

    private fun finite(value: Double, label: String): LayoutUnit {
        val narrowed = value.toFloat()
        if (!value.isFinite() || !narrowed.isFinite()) throw IllegalArgumentException("$label overflowed.")
        return LayoutUnit(narrowed)
    }

    private fun rangesOverlap(left: TextRange, right: TextRange): Boolean =
        left.start < right.endExclusive && right.start < left.endExclusive

    private fun FlowRegion.logicalBlockExtent(writingMode: WritingMode): Float = when (writingMode) {
        WritingMode.HORIZONTAL_TB -> bounds.bottom.value - bounds.top.value
        WritingMode.VERTICAL_RL,
        WritingMode.VERTICAL_LR,
        -> bounds.right.value - bounds.left.value
    }

    private sealed interface LineAttempt {
        data class Placed(
            val line: LineLayout,
            val blockStart: Float,
            val blockExtent: Float,
            val hasUnplacedTrailingEmptyLine: Boolean,
        ) : LineAttempt

        data object EndOfRegion : LineAttempt

        data class Failure(val failure: FlowCompositionResult.Failure) : LineAttempt
    }

    private sealed interface RegionComposition {
        data class Success(
            val lines: List<LineLayout>,
            val lineBlockEnds: List<Float>,
            val nextBlockOffset: Float,
            val isComplete: Boolean,
            val stoppedByLineLimit: Boolean = false,
        ) : RegionComposition

        data class Failure(val failure: FlowCompositionResult.Failure) : RegionComposition
    }

    private data class RegionCandidate(
        val composition: RegionComposition.Success,
        val regionIndex: Int,
    ) {
        fun satisfies(
            constraints: org.graphiks.kalligraphie.api.FragmentationConstraints,
            relaxed: List<FragmentationConstraintKind>,
            isFirstFragment: Boolean,
            commitment: FlowFragmentationCommitment?,
        ): Boolean = commitment?.let { accepted ->
            regionIndex == accepted.regionIndex && composition.lines.isNotEmpty()
        } ?: ((composition.isComplete && isFirstFragment) ||
            ((!constraints.keepTogether || !isFirstFragment || FragmentationConstraintKind.KEEP_TOGETHER in relaxed) &&
                (constraints.minLinesAtEnd <= composition.lines.size ||
                    FragmentationConstraintKind.MIN_LINES_AT_END in relaxed) &&
                (constraints.minLinesAtStart <= composition.lines.size ||
                    FragmentationConstraintKind.MIN_LINES_AT_START in relaxed)))
    }

    private data class RefinementFingerprint(
        val bandExtent: Float,
        val intervals: List<Pair<Float, Float>>,
        val lineRange: TextRange,
    )

    private sealed interface RegionAnswerFingerprint {
        data class Available(val intervals: List<InlineInterval>) : RegionAnswerFingerprint
        data class Empty(val nextBlockOffset: Float) : RegionAnswerFingerprint
        data object EndOfRegion : RegionAnswerFingerprint
    }

    private data class RequiredBlockMetrics(val before: Float, val after: Float) {
        val extent: Float = before + after

        fun fill(acceptedExtent: Float): LineVerticalMetrics {
            require(acceptedExtent.isFinite() && acceptedExtent >= extent) {
                "The accepted flow line band cannot contain its required block-axis metrics."
            }
            if (acceptedExtent == extent) {
                return LineVerticalMetrics(LayoutUnit(before), LayoutUnit(after))
            }
            val trailing = (acceptedExtent.toDouble() - before.toDouble()).toFloat()
            require(trailing.isFinite() && trailing >= after) {
                "The accepted flow line band cannot contain its required block-axis metrics."
            }
            val filled = LineVerticalMetrics(LayoutUnit(before), LayoutUnit(trailing))
            require(filled.height.value == acceptedExtent) {
                "The accepted flow line band cannot be represented by exact block-axis metrics."
            }
            return filled
        }
    }

    private data class AllocatedGlyph(val sourceRun: PositionedGlyphRun, val glyph: PositionedGlyph)

    private data class Allocation(
        val fragmentIndex: Int,
        val originalStart: Double,
        val originalEnd: Double,
        val translation: Double,
        val objectRange: TextRange?,
        val sourceRange: TextRange?,
    ) {
        fun distanceFrom(position: Double): Double = when {
            position < originalStart -> originalStart - position
            position > originalEnd -> position - originalEnd
            else -> 0.0
        }
    }

    private sealed interface FragmentProjection {
        data class Success(val fragments: List<LineFragment>) : FragmentProjection
        data class Failure(val error: FlowCompositionError) : FragmentProjection
    }

    private sealed interface PackingProbe {
        data object Complete : PackingProbe
        data class Failure(val error: FlowCompositionError) : PackingProbe
    }
}

internal fun logicalPackingPrefixEnds(
    graphemeClusters: List<TextRange>,
    lineRange: TextRange,
): List<TextIndex> {
    val firstCandidate = graphemeClusters.lowerBound { cluster ->
        cluster.endExclusive > lineRange.start
    }
    val afterLastCandidate = graphemeClusters.lowerBound { cluster ->
        cluster.endExclusive >= lineRange.endExclusive
    }
    if (firstCandidate >= afterLastCandidate) return emptyList()
    return graphemeClusters.subList(firstCandidate, afterLastCandidate)
        .asReversed()
        .map(TextRange::endExclusive)
}

private inline fun <T> List<T>.lowerBound(predicate: (T) -> Boolean): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = low + (high - low) / 2
        if (predicate(this[middle])) high = middle else low = middle + 1
    }
    return low
}

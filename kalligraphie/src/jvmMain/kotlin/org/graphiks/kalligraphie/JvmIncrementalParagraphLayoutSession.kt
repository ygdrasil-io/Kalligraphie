@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.shaping.JvmPreparedFontCachePolicy
import org.graphiks.kalligraphie.shaping.JvmPreparedFontCacheUsage
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditorOperationContext
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.IncrementalLayoutError
import org.graphiks.kalligraphie.api.IncrementalLayoutRequest
import org.graphiks.kalligraphie.api.IncrementalLayoutResult
import org.graphiks.kalligraphie.api.LayoutContractResult
import org.graphiks.kalligraphie.api.LayoutContinuation
import org.graphiks.kalligraphie.api.LayoutContinuationSignature
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutStateHandle
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineBreakKind
import org.graphiks.kalligraphie.api.LineBreakAnalysisOutcome
import org.graphiks.kalligraphie.api.LineOverscan
import org.graphiks.kalligraphie.api.OverflowPolicy
import org.graphiks.kalligraphie.api.HyphenationMode
import org.graphiks.kalligraphie.api.HyphenationService
import org.graphiks.kalligraphie.api.InlineObjectSnapshot
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.ParagraphLayoutError
import org.graphiks.kalligraphie.api.ParagraphPositioningPolicy
import org.graphiks.kalligraphie.api.ParagraphConstraints
import org.graphiks.kalligraphie.api.ParagraphMaterializationIdentity
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextOrientation
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.api.UnicodeAnalysisOutcome
import org.graphiks.kalligraphie.api.VerticalMetricsPolicy
import org.graphiks.kalligraphie.api.WritingMode
import org.graphiks.kalligraphie.api.createIncrementalLayoutRequest
import org.graphiks.kalligraphie.layout.IncrementalComputationTail
import org.graphiks.kalligraphie.layout.IncrementalComputedLine
import org.graphiks.kalligraphie.layout.IncrementalMaterializationTarget
import org.graphiks.kalligraphie.layout.IncrementalParagraphComputation
import org.graphiks.kalligraphie.layout.IncrementalParagraphComputer
import org.graphiks.kalligraphie.layout.IncrementalParagraphLayoutEngine
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import org.graphiks.kalligraphie.unicode.JvmLineBreakAnalyzer
import org.graphiks.kalligraphie.unicode.JvmUnicodeAnalyzer

/**
 * JVM-specific inputs layered on a validated portable [IncrementalLayoutRequest].
 *
 * [baseDirection], [language], [materialization], and [overflowPolicy] are the explicit fields
 * needed by the existing JVM paragraph route but not carried by the portable incremental
 * contract. The materialization is borrowed only for the synchronous session call; a resolver is
 * never retained in the published layout state. The request itself is immutable provided a
 * resolver inside [materialization] and the cancellation token in [request] support concurrent
 * access.
 *
 * @throws IllegalArgumentException when [language] is blank.
 */
public class JvmIncrementalParagraphLayoutRequest(
    /** Validated target input, exact requested range, overscan, prior state, delta, and cancellation. */
    public val request: IncrementalLayoutRequest,
    /** Explicit UAX #9 paragraph base direction. */
    public val baseDirection: BaseDirection,
    /** Explicit BCP 47 language used by Unicode analysis and shaping. */
    public val language: String,
    /** Layout-only or synchronously profile-certified materialization borrowed for this call. */
    public val materialization: EditableLineMaterialization = EditableLineMaterialization.LayoutOnly,
    /** Complete-line overflow behavior forwarded to the JVM paragraph composer. */
    public val overflowPolicy: OverflowPolicy = OverflowPolicy.Continue,
    /** Tab stops, alignment, and justification applied to every computed line. */
    public val positioning: ParagraphPositioningPolicy = ParagraphPositioningPolicy(),
    /** Hyphenation mode forwarded to line selection and final line content. */
    public val hyphenationMode: HyphenationMode = HyphenationMode.MANUAL,
    /** Immutable versioned service used by [HyphenationMode.AUTO], or `null` when absent. */
    public val hyphenationService: HyphenationService? = null,
    /** Definitions bound to `U+FFFC` object replacement scalars inside the window. */
    public val inlineObjects: InlineObjectSnapshot? = null,
    /** Unicode orientation policy applied to extended grapheme clusters in vertical composition. */
    public val textOrientation: TextOrientation = TextOrientation.MIXED,
    /** Policy used when a resolved vertical glyph has no usable `vhea` or `vmtx` metric. */
    public val verticalMetricsPolicy: VerticalMetricsPolicy = VerticalMetricsPolicy.SYNTHESIZE_IF_UNAVAILABLE,
) {
    init {
        require(language.isNotBlank()) { "Incremental paragraph language must not be blank." }
    }
}

/**
 * Single-writer JVM incremental paragraph layout session backed by one HarfBuzz instance.
 *
 * [layout], [currentLayout], test-gate publication, and [close] are serialized on the session.
 * Every successful publication is an immutable concurrent-read value produced through the
 * portable incremental engine and the complete JVM paragraph route. A stale completion returns
 * [IncrementalLayoutResult.Obsolete], cancellation publishes nothing, and both outcomes preserve
 * the latest complete publication. Published state contains only resource-free checkpoints;
 * borrowed materialization resolvers and temporary paragraph work remain confined to a call.
 * Each line considers the exact candidate segment through the next mandatory UAX #14 boundary;
 * when no mandatory boundary remains, signed glyph advances require conservative consideration
 * through document end. This may shape a long soft-wrapped suffix, but never invents a terminal
 * boundary that could change which complete line is selected.
 *
 * The session owns its HarfBuzz backend. [close] is idempotent and is linearized with layout and
 * publication, so a racing close happens wholly before or after a layout attempt. Calls to
 * [layout] after close fail with [IllegalStateException]; [currentLayout] remains readable because
 * closing native work does not invalidate an already published immutable result.
 */
public class JvmIncrementalParagraphLayoutSession private constructor(
    private val backend: ShapingBackend,
    private val engine: IncrementalParagraphLayoutEngine,
) : AutoCloseable {
    private val inspectPreparedFontCache = JvmHarfBuzzShapingBackend.preparedFontCacheUsageInspector(backend)

    /**
     * Immutable prepared-font accounting, independent of layout and render-asset budgets.
     * Readable before the first layout and after [close]; closing a session returns zero usage.
     */
    public val preparedFontCacheUsage: JvmPreparedFontCacheUsage
        get() = inspectPreparedFontCache()

    private var nextGeneration: Long = 0L
    private var latestAttempt: Long = 0L
    private var publication: IncrementalLayoutResult.Success? = null
    private var publicationMetadata: PublishedWorkMetadata? = null
    private var closed: Boolean = false

    /**
     * Computes and atomically publishes one complete incremental result.
     *
     * Calls are serialized. Validation and composition failures are returned as typed incremental
     * failures, cancellation returns [IncrementalLayoutResult.Cancelled], and no non-successful
     * attempt changes [currentLayout].
     *
     * @throws IllegalStateException when the session has already been closed.
     */
    @Synchronized
    public fun layout(request: JvmIncrementalParagraphLayoutRequest): IncrementalLayoutResult {
        check(!closed) { "The JVM incremental paragraph layout session is closed." }
        val generation = ++nextGeneration
        latestAttempt = generation
        val context = EditorOperationContext.create(
            request.request.operationProfile,
            request.request.cancellationToken,
        )
        context.sourceLimit(request.request.input.text)?.let {
            return IncrementalLayoutResult.Failure(IncrementalLayoutError.OperationLimitExceeded(it))
        }
        context.scalarLimit(request.request.input.text)?.let {
            return IncrementalLayoutResult.Failure(IncrementalLayoutError.OperationLimitExceeded(it))
        }
        if (context.isCancellationRequested()) return IncrementalLayoutResult.Cancelled
        val compositionConfiguration = JvmCompositionConfiguration.from(request)
        val portableRequest = when (
            val prepared = requestForEngine(request.request, compositionConfiguration)
        ) {
            is LayoutContractResult.Success -> prepared.value
            is LayoutContractResult.Failure -> return IncrementalLayoutResult.Failure(prepared.error)
        }
        var completedWork: ComputerWork? = null
        val computer = IncrementalParagraphComputer { target, overscan, portableRequest ->
            composeIncrementally(request, target, overscan, portableRequest, context).also { work ->
                if (work.computation is IncrementalParagraphComputation.Success) completedWork = work
            }.computation
        }
        val result = engine.layout(portableRequest, computer)
        if (request.request.cancellationToken.isCancellationRequested()) {
            return IncrementalLayoutResult.Cancelled
        }
        return publish(generation, result, completedWork, compositionConfiguration)
    }

    /** Returns the latest complete immutable publication atomically, or `null` before first success. */
    @Synchronized
    public fun currentLayout(): IncrementalLayoutResult.Success? = publication

    /**
     * Closes the owned HarfBuzz backend once.
     *
     * The operation is idempotent and serialized with computation and publication. Backend close
     * diagnostics cannot invalidate resource-free layouts that were already published.
     */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        backend.close()
    }

    /** Exercises the same generation gate as normal publication for deterministic stale-result tests. */
    @Synchronized
    internal fun publishForTesting(
        candidate: IncrementalLayoutResult.Success,
        generation: Long,
    ): IncrementalLayoutResult = publish(
        generation,
        candidate,
        completedWork = null,
        compositionConfiguration = null,
    )

    private fun publish(
        generation: Long,
        result: IncrementalLayoutResult,
        completedWork: ComputerWork?,
        compositionConfiguration: JvmCompositionConfiguration?,
    ): IncrementalLayoutResult {
        if (closed || generation < latestAttempt) return IncrementalLayoutResult.Obsolete
        if (result is IncrementalLayoutResult.Success) {
            publication = result
            publicationMetadata = completedWork?.let { work ->
                val configuration = compositionConfiguration ?: return@let null
                PublishedWorkMetadata(
                    state = result.layout.state,
                    compositionConfiguration = configuration,
                    lineTops = work.lineTops,
                )
            }
        }
        return result
    }

    private fun requestForEngine(
        request: IncrementalLayoutRequest,
        compositionConfiguration: JvmCompositionConfiguration,
    ): LayoutContractResult<IncrementalLayoutRequest> {
        val previous = request.previousState ?: return LayoutContractResult.Success(request)
        val activeState = publication?.layout?.state
        val metadata = publicationMetadata
        if (
            previous === activeState &&
            previous === metadata?.state &&
            compositionConfiguration == metadata.compositionConfiguration
        ) {
            return LayoutContractResult.Success(request)
        }
        return createIncrementalLayoutRequest(
            input = request.input,
            requestedRange = request.requestedRange,
            constraints = request.constraints,
            overscan = request.overscan,
            previousState = null,
            delta = request.delta,
            cancellationToken = request.cancellationToken,
            operationProfile = request.operationProfile,
        )
    }

    private fun composeIncrementally(
        sessionRequest: JvmIncrementalParagraphLayoutRequest,
        target: IncrementalMaterializationTarget,
        overscan: LineOverscan,
        request: IncrementalLayoutRequest,
        context: EditorOperationContext,
    ): ComputerWork {
        if (request.cancellationToken.isCancellationRequested()) {
            return ComputerWork(IncrementalParagraphComputation.Cancelled)
        }

        val snapshot = request.input.text
        val documentEnd = snapshot.range.endExclusive
        val unicodeAnalysis = when (
            val analyzed = JvmUnicodeAnalyzer.create().analyze(
                snapshot,
                UnicodeAnalysisRequest(sessionRequest.baseDirection, sessionRequest.language),
                context,
            )
        ) {
            is UnicodeAnalysisOutcome.Success -> analyzed.value
            is UnicodeAnalysisOutcome.LimitExceeded -> return ComputerWork(
                IncrementalParagraphComputation.Failure(
                    IncrementalLayoutError.OperationLimitExceeded(
                        org.graphiks.kalligraphie.api.EditorOperationLimitExceeded(
                            org.graphiks.kalligraphie.api.EditorOperationLimitKind.ANALYZED_SCALARS,
                            context.profile.maxAnalyzedScalars.toLong(),
                            analyzed.observed.toLong(),
                        ),
                    ),
                ),
            )
            UnicodeAnalysisOutcome.Cancelled -> return ComputerWork(IncrementalParagraphComputation.Cancelled)
        }
        if (request.cancellationToken.isCancellationRequested()) {
            return ComputerWork(IncrementalParagraphComputation.Cancelled)
        }
        val lineBreakAnalysis = when (
            val analyzed = JvmLineBreakAnalyzer.createBounded().analyze(snapshot, unicodeAnalysis, context)
        ) {
            is LineBreakAnalysisOutcome.Success -> analyzed.value
            is LineBreakAnalysisOutcome.LimitExceeded -> return ComputerWork(
                IncrementalParagraphComputation.Failure(
                    IncrementalLayoutError.OperationLimitExceeded(analyzed.limit),
                ),
            )
            LineBreakAnalysisOutcome.Cancelled -> return ComputerWork(IncrementalParagraphComputation.Cancelled)
        }
        if (request.cancellationToken.isCancellationRequested()) {
            return ComputerWork(IncrementalParagraphComputation.Cancelled)
        }
        val mandatoryBoundaries = lineBreakAnalysis.opportunities
            .filter { opportunity -> opportunity.kind == LineBreakKind.MANDATORY }
            .map { opportunity -> opportunity.boundary }
        val initialBlockCursor = reflowBlockCursor(target, request)
            ?: return ComputerWork(
                IncrementalParagraphComputation.Failure(
                    IncrementalLayoutError.InvalidRange(
                        "The requested checkpoint geometry is not owned by this session's current publication.",
                    ),
                ),
            )
        var lineStart = target.reflowStart
        var blockCursor = initialBlockCursor
        var targetCovered = false
        var remainingAfterOverscan = overscan.lineCount
        val computed = mutableListOf<IncrementalComputedLine>()
        val lineTops = mutableListOf<LineTop>()

        while (true) {
            if (request.cancellationToken.isCancellationRequested()) {
                return ComputerWork(IncrementalParagraphComputation.Cancelled)
            }
            val segmentEnd = mandatoryBoundaries.firstOrNull { boundary -> boundary > lineStart }
                ?: documentEnd
            val sourceRange = TextRange(lineStart, segmentEnd)
            val continuation = when (
                val prepared = continuationForWindow(sessionRequest, request, sourceRange, blockCursor, context)
            ) {
                WindowContinuation.NotRequired -> null
                is WindowContinuation.Success -> prepared.continuation
                is WindowContinuation.Failure -> return ComputerWork(
                    IncrementalParagraphComputation.Failure(prepared.error),
                )
                WindowContinuation.Cancelled -> return ComputerWork(IncrementalParagraphComputation.Cancelled)
            }
            val paragraphResult = JvmEditableParagraphFacade.layoutBorrowing(
                request = JvmEditableParagraphFacadeRequest(
                    snapshot = snapshot,
                    sourceRange = sourceRange,
                    constraints = oneLineConstraints(request.constraints, blockCursor),
                    baseDirection = sessionRequest.baseDirection,
                    language = sessionRequest.language,
                    fontCatalog = request.input.typography.fontCatalog,
                    resolutionPolicy = request.input.typography.resolutionPolicy,
                    fontInstanceDescriptor = request.input.typography.fontInstanceDescriptor,
                    features = request.input.typography.features,
                    materialization = sessionRequest.materialization,
                    overflowPolicy = sessionRequest.overflowPolicy,
                    positioning = sessionRequest.positioning,
                    hyphenationMode = sessionRequest.hyphenationMode,
                    hyphenationService = sessionRequest.hyphenationService,
                    inlineObjects = sessionRequest.inlineObjects,
                    textOrientation = sessionRequest.textOrientation,
                    verticalMetricsPolicy = sessionRequest.verticalMetricsPolicy,
                    continuation = continuation,
                    cancellationToken = request.cancellationToken,
                    operationProfile = context.profile,
                ),
                backend = backend,
                context = context,
            )
            if (request.cancellationToken.isCancellationRequested()) {
                return ComputerWork(IncrementalParagraphComputation.Cancelled)
            }
            val paragraph = when (paragraphResult) {
                is ParagraphLayoutResult.Success -> paragraphResult
                is ParagraphLayoutResult.Failure -> return ComputerWork(
                    IncrementalParagraphComputation.Failure(
                        paragraphError(paragraphResult.error),
                    ),
                )
                is ParagraphLayoutResult.Cancelled -> return ComputerWork(IncrementalParagraphComputation.Cancelled)
            }
            val line = paragraph.layout.lines.singleOrNull()
                ?: return ComputerWork(
                    IncrementalParagraphComputation.Failure(
                        IncrementalLayoutError.InvalidRange(
                            "The bounded JVM paragraph route must produce exactly one complete line per step.",
                        ),
                    ),
                )
            val nextBlockCursor = nextBlockCursor(line, request.constraints.writingMode)
            lineTops += LineTop(line.range.start, lineBlockCursor(line, request.constraints.writingMode))
            computed += IncrementalComputedLine(
                line = line,
                continuation = LayoutContinuationSignature(
                    boundary = line.range.endExclusive,
                    semanticValue = continuationSemantics(sessionRequest, request, nextBlockCursor),
                ),
            )

            if (
                !targetCovered &&
                lineCompletesTarget(
                    line = line.range,
                    requested = target.requestedRange,
                    documentEnd = documentEnd,
                    hasContinuation = paragraph.continuation != null,
                )
            ) {
                targetCovered = true
                if (remainingAfterOverscan == 0) {
                    return successWithTail(computed, lineTops, documentEnd, paragraph.continuation == null)
                }
            } else if (targetCovered) {
                remainingAfterOverscan -= 1
                if (remainingAfterOverscan == 0) {
                    return successWithTail(computed, lineTops, documentEnd, paragraph.continuation == null)
                }
            }

            val next = paragraph.continuation
                ?: return if (targetCovered) {
                    successWithTail(computed, lineTops, documentEnd, reachedDocumentEnd = true)
                } else {
                    ComputerWork(
                        IncrementalParagraphComputation.Failure(
                            IncrementalLayoutError.InvalidRange(
                                "The complete JVM paragraph route ended before covering the requested range.",
                            ),
                        ),
                    )
                }
            lineStart = next.remainingSourceRange.start
            blockCursor = next.resumptionBlockCursor
        }
    }

    private fun successWithTail(
        computed: List<IncrementalComputedLine>,
        lineTops: List<LineTop>,
        documentEnd: TextIndex,
        reachedDocumentEnd: Boolean,
    ): ComputerWork {
        val finalBoundary = computed.last().line.range.endExclusive
        val tail = if (reachedDocumentEnd || finalBoundary == documentEnd) {
            IncrementalComputationTail.MaterializedThroughDocumentEnd
        } else {
            IncrementalComputationTail.Unmaterialized(TextRange(finalBoundary, documentEnd))
        }
        return ComputerWork(
            computation = IncrementalParagraphComputation.Success(computed.toList(), tail),
            lineTops = lineTops.toList(),
        )
    }

    private fun reflowBlockCursor(
        target: IncrementalMaterializationTarget,
        request: IncrementalLayoutRequest,
    ): LayoutUnit? {
        if (target.reflowStart == request.input.text.range.start) {
            return initialBlockCursor(request.constraints)
        }
        val metadata = publicationMetadata ?: return null
        if (request.previousState !== metadata.state) return null
        return metadata.lineTops.firstNotNullOfOrNull { checkpoint ->
            val mapped = request.delta?.text?.mapSourceBoundaryToTarget(
                checkpoint.start,
                request.input.text,
                afterInsertion = true,
            ) ?: checkpoint.start.takeIf { start ->
                start.sharesVersionWith(request.input.text.range.start)
            }
            checkpoint.blockCursor.takeIf { mapped == target.reflowStart }
        }
    }

    private fun continuationForWindow(
        sessionRequest: JvmIncrementalParagraphLayoutRequest,
        request: IncrementalLayoutRequest,
        sourceRange: TextRange,
        blockCursor: LayoutUnit,
        context: EditorOperationContext,
    ): WindowContinuation {
        if (sourceRange.start == request.input.text.range.start) return WindowContinuation.NotRequired
        return when (
            val prepared = JvmEditableParagraphFacade.continuationBorrowing(
                request = JvmEditableParagraphFacadeRequest(
                    snapshot = request.input.text,
                    sourceRange = sourceRange,
                    constraints = oneLineConstraints(
                        request.constraints,
                        initialBlockCursor(request.constraints),
                    ),
                    baseDirection = sessionRequest.baseDirection,
                    language = sessionRequest.language,
                    fontCatalog = request.input.typography.fontCatalog,
                    resolutionPolicy = request.input.typography.resolutionPolicy,
                    fontInstanceDescriptor = request.input.typography.fontInstanceDescriptor,
                    features = request.input.typography.features,
                    materialization = sessionRequest.materialization,
                    overflowPolicy = sessionRequest.overflowPolicy,
                    positioning = sessionRequest.positioning,
                    hyphenationMode = sessionRequest.hyphenationMode,
                    hyphenationService = sessionRequest.hyphenationService,
                    inlineObjects = sessionRequest.inlineObjects,
                    textOrientation = sessionRequest.textOrientation,
                    verticalMetricsPolicy = sessionRequest.verticalMetricsPolicy,
                    cancellationToken = request.cancellationToken,
                    operationProfile = context.profile,
                ),
                backend = backend,
                remainingSourceRange = sourceRange,
                resumptionRegionTop = when (request.constraints.writingMode) {
                    WritingMode.HORIZONTAL_TB -> blockCursor
                    WritingMode.VERTICAL_RL,
                    WritingMode.VERTICAL_LR,
                    -> request.constraints.region.top
                },
                resumptionBlockCursor = blockCursor,
                context = context,
            )
        ) {
            is ParagraphContinuationPreparation.Success -> WindowContinuation.Success(prepared.continuation)
            is ParagraphContinuationPreparation.Failure -> WindowContinuation.Failure(
                paragraphError(prepared.result.error),
            )
            ParagraphContinuationPreparation.Cancelled -> WindowContinuation.Cancelled
        }
    }

    private fun paragraphError(error: ParagraphLayoutError): IncrementalLayoutError = when (error) {
        is ParagraphLayoutError.OperationLimitExceeded -> IncrementalLayoutError.OperationLimitExceeded(error.limit)
        else -> IncrementalLayoutError.ParagraphFailure(error)
    }

    private fun lineCompletesTarget(
        line: TextRange,
        requested: TextRange,
        documentEnd: TextIndex,
        hasContinuation: Boolean,
    ): Boolean = if (requested.start == requested.endExclusive) {
        if (requested.start == documentEnd) {
            line.start == documentEnd || !hasContinuation && line.endExclusive == documentEnd
        } else {
            requested.start >= line.start && requested.start < line.endExclusive
        }
    } else {
        line.endExclusive >= requested.endExclusive &&
            (requested.endExclusive != documentEnd || !hasContinuation)
    }

    private fun oneLineConstraints(
        constraints: ParagraphConstraints,
        blockCursor: LayoutUnit,
    ): ParagraphConstraints = when (constraints.writingMode) {
        WritingMode.HORIZONTAL_TB -> ParagraphConstraints(
            region = LayoutRect(
                left = constraints.region.left,
                top = blockCursor,
                right = constraints.region.right,
                bottom = LayoutUnit(blockCursor.value + constraints.lineMetrics.height.value),
            ),
            lineMetrics = constraints.lineMetrics,
            writingMode = constraints.writingMode,
        )

        WritingMode.VERTICAL_RL -> ParagraphConstraints(
            region = LayoutRect(
                left = LayoutUnit(blockCursor.value - constraints.lineMetrics.height.value),
                top = constraints.region.top,
                right = blockCursor,
                bottom = constraints.region.bottom,
            ),
            lineMetrics = constraints.lineMetrics,
            writingMode = constraints.writingMode,
        )

        WritingMode.VERTICAL_LR -> ParagraphConstraints(
            region = LayoutRect(
                left = blockCursor,
                top = constraints.region.top,
                right = LayoutUnit(blockCursor.value + constraints.lineMetrics.height.value),
                bottom = constraints.region.bottom,
            ),
            lineMetrics = constraints.lineMetrics,
            writingMode = constraints.writingMode,
        )
    }

    private fun initialBlockCursor(constraints: ParagraphConstraints): LayoutUnit = when (constraints.writingMode) {
        WritingMode.HORIZONTAL_TB -> constraints.region.top
        WritingMode.VERTICAL_RL -> constraints.region.right
        WritingMode.VERTICAL_LR -> constraints.region.left
    }

    private fun lineBlockCursor(
        line: org.graphiks.kalligraphie.api.LineLayout,
        writingMode: WritingMode,
    ): LayoutUnit = when (writingMode) {
        WritingMode.HORIZONTAL_TB -> line.lineBox.top
        WritingMode.VERTICAL_RL -> line.lineBox.right
        WritingMode.VERTICAL_LR -> line.lineBox.left
    }

    private fun nextBlockCursor(
        line: org.graphiks.kalligraphie.api.LineLayout,
        writingMode: WritingMode,
    ): LayoutUnit = when (writingMode) {
        WritingMode.HORIZONTAL_TB -> line.lineBox.bottom
        WritingMode.VERTICAL_RL -> line.lineBox.left
        WritingMode.VERTICAL_LR -> line.lineBox.right
    }

    private fun continuationSemantics(
        sessionRequest: JvmIncrementalParagraphLayoutRequest,
        request: IncrementalLayoutRequest,
        nextBlockCursor: LayoutUnit,
    ): String = buildString {
        append("block-cursor=").append(nextBlockCursor.value)
        append(";writing-mode=").append(request.constraints.writingMode)
        append(";left=").append(request.constraints.region.left.value)
        append(";width=").append(request.constraints.width.value)
        append(";metrics=").append(request.constraints.lineMetrics)
        append(";direction=").append(sessionRequest.baseDirection)
        append(";language=").append(sessionRequest.language)
        append(";catalog=").append(request.input.typography.fontCatalog.generation)
        append(";policy=").append(request.input.typography.resolutionPolicy.policyId)
        append('@').append(request.input.typography.resolutionPolicy.version)
        append(";instance=").append(request.input.typography.fontInstanceDescriptor)
        append(";backend=").append(backend.identity)
        append(";features=").append(request.input.typography.features)
        append(";materialization=").append(sessionRequest.materialization.identityForSession())
        append(";overflow=").append(sessionRequest.overflowPolicy)
        append(";positioning=").append(sessionRequest.positioning)
        append(";hyphenation-mode=").append(sessionRequest.hyphenationMode)
        append(";hyphenation-service=").append(sessionRequest.hyphenationService?.identity)
        append(";inline-objects=").append(sessionRequest.inlineObjects)
        append(";orientation=").append(sessionRequest.textOrientation)
        append(";vertical-metrics=").append(sessionRequest.verticalMetricsPolicy)
    }

    /** Opens the pinned JVM HarfBuzz backend and transfers its ownership to a new session. */
    public companion object {
        /**
         * Opens a reusable JVM incremental paragraph session.
         *
         * The cache budget is validated before opening native resources. Native-backend failure
         * or cancellation is returned without creating a session. A successful session must be
         * closed by its owner.
         * [preparedFontCachePolicy] independently bounds active and idle HarfBuzz fonts, including
         * native source copies and a versioned native estimate; it does not change the layout
         * cache budget or the operation-scoped render-asset pool.
         *
         * @throws IllegalArgumentException when [cacheBudgetBytes] is negative.
         */
        @JvmOverloads
        public fun open(
            cacheBudgetBytes: Long = DEFAULT_CACHE_BUDGET_BYTES,
            preparedFontCachePolicy: JvmPreparedFontCachePolicy = JvmPreparedFontCachePolicy.default,
        ):
            FontOperationResult<JvmIncrementalParagraphLayoutSession> =
            openWithBackendFactory(cacheBudgetBytes) { JvmHarfBuzzShapingBackend.open(preparedFontCachePolicy) }

        internal fun openWithBackendFactory(
            cacheBudgetBytes: Long,
            openBackend: () -> FontOperationResult<ShapingBackend>,
        ): FontOperationResult<JvmIncrementalParagraphLayoutSession> {
            val engine = IncrementalParagraphLayoutEngine(cacheBudgetBytes)
            return when (val opened = openBackend()) {
                is FontOperationResult.Success -> FontOperationResult.Success(
                    JvmIncrementalParagraphLayoutSession(opened.value, engine),
                    opened.diagnostics,
                )
                is FontOperationResult.Failure -> opened
                is FontOperationResult.Cancelled -> opened
            }
        }

        internal fun openOwnedBackend(
            backend: ShapingBackend,
            cacheBudgetBytes: Long = DEFAULT_CACHE_BUDGET_BYTES,
        ): JvmIncrementalParagraphLayoutSession = JvmIncrementalParagraphLayoutSession(
            backend,
            IncrementalParagraphLayoutEngine(cacheBudgetBytes),
        )

        private const val DEFAULT_CACHE_BUDGET_BYTES: Long = 4L * 1024L * 1024L
    }

    private sealed interface WindowContinuation {
        data object NotRequired : WindowContinuation
        class Success(val continuation: LayoutContinuation) : WindowContinuation
        class Failure(val error: IncrementalLayoutError) : WindowContinuation
        data object Cancelled : WindowContinuation
    }

    private data class LineTop(
        val start: TextIndex,
        val blockCursor: LayoutUnit,
    )

    private data class ComputerWork(
        val computation: IncrementalParagraphComputation,
        val lineTops: List<LineTop> = emptyList(),
    )

    private data class PublishedWorkMetadata(
        val state: LayoutStateHandle,
        val compositionConfiguration: JvmCompositionConfiguration,
        val lineTops: List<LineTop>,
    )

    private data class JvmCompositionConfiguration(
        val baseDirection: BaseDirection,
        val language: String,
        val materializationIdentity: ParagraphMaterializationIdentity,
        val overflowPolicy: OverflowPolicy,
        val positioning: ParagraphPositioningPolicy,
        val hyphenationMode: HyphenationMode,
        val hyphenationServiceIdentity: org.graphiks.kalligraphie.api.HyphenationServiceIdentity?,
        val inlineObjects: InlineObjectSnapshot?,
        val textOrientation: TextOrientation,
        val verticalMetricsPolicy: VerticalMetricsPolicy,
    ) {
        companion object {
            fun from(request: JvmIncrementalParagraphLayoutRequest): JvmCompositionConfiguration =
                JvmCompositionConfiguration(
                    baseDirection = request.baseDirection,
                    language = request.language,
                    materializationIdentity = ParagraphMaterializationIdentity.from(request.materialization),
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

private fun EditableLineMaterialization.identityForSession(): Any = when (this) {
    EditableLineMaterialization.LayoutOnly -> "layout-only"
    is EditableLineMaterialization.Renderable -> listOf(renderVariant, requirements)
}

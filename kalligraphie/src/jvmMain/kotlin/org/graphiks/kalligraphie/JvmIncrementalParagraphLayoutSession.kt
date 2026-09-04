package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
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
import org.graphiks.kalligraphie.api.LineOverscan
import org.graphiks.kalligraphie.api.OverflowPolicy
import org.graphiks.kalligraphie.api.HyphenationMode
import org.graphiks.kalligraphie.api.HyphenationService
import org.graphiks.kalligraphie.api.InlineObjectSnapshot
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.ParagraphPositioningPolicy
import org.graphiks.kalligraphie.api.ParagraphMaterializationIdentity
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
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
    /** Layout-only or synchronously outline-certified materialization borrowed for this call. */
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
 * Each line considers the exact J4 candidate segment through the next mandatory UAX #14 boundary;
 * when no mandatory boundary remains, signed glyph advances require conservative consideration
 * through document end. This may shape a long soft-wrapped suffix, but never invents a terminal
 * boundary that could change which complete line J4 selects.
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
        if (request.request.cancellationToken.isCancellationRequested()) {
            return IncrementalLayoutResult.Cancelled
        }
        val compositionConfiguration = JvmCompositionConfiguration.from(request)
        val portableRequest = when (
            val prepared = requestForEngine(request.request, compositionConfiguration)
        ) {
            is LayoutContractResult.Success -> prepared.value
            is LayoutContractResult.Failure -> return IncrementalLayoutResult.Failure(prepared.error)
        }
        var completedWork: ComputerWork? = null
        val computer = IncrementalParagraphComputer { target, overscan, portableRequest ->
            composeIncrementally(request, target, overscan, portableRequest).also { work ->
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
        )
    }

    private fun composeIncrementally(
        sessionRequest: JvmIncrementalParagraphLayoutRequest,
        target: IncrementalMaterializationTarget,
        overscan: LineOverscan,
        request: IncrementalLayoutRequest,
    ): ComputerWork {
        if (request.cancellationToken.isCancellationRequested()) {
            return ComputerWork(IncrementalParagraphComputation.Cancelled)
        }

        val snapshot = request.input.text
        val documentEnd = snapshot.range.endExclusive
        val unicodeAnalysis = JvmUnicodeAnalyzer.create().analyze(
            snapshot,
            UnicodeAnalysisRequest(sessionRequest.baseDirection, sessionRequest.language),
        )
        if (request.cancellationToken.isCancellationRequested()) {
            return ComputerWork(IncrementalParagraphComputation.Cancelled)
        }
        val lineBreakAnalysis = JvmLineBreakAnalyzer.create().analyze(snapshot, unicodeAnalysis)
        if (request.cancellationToken.isCancellationRequested()) {
            return ComputerWork(IncrementalParagraphComputation.Cancelled)
        }
        val mandatoryBoundaries = lineBreakAnalysis.opportunities
            .filter { opportunity -> opportunity.kind == LineBreakKind.MANDATORY }
            .map { opportunity -> opportunity.boundary }
        val initialTop = reflowTop(target, request)
            ?: return ComputerWork(
                IncrementalParagraphComputation.Failure(
                    IncrementalLayoutError.InvalidRange(
                        "The requested checkpoint geometry is not owned by this session's current publication.",
                    ),
                ),
            )
        var lineStart = target.reflowStart
        var lineTop = initialTop
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
            val continuation = continuationForWindow(sessionRequest, request, sourceRange, lineTop)
            if (lineStart != snapshot.range.start && continuation == null) {
                return if (request.cancellationToken.isCancellationRequested()) {
                    ComputerWork(IncrementalParagraphComputation.Cancelled)
                } else {
                    ComputerWork(
                        IncrementalParagraphComputation.Failure(
                            IncrementalLayoutError.InvalidRange(
                                "The JVM route could not create a continuation for the proven reflow checkpoint.",
                            ),
                        ),
                    )
                }
            }
            val paragraphResult = JvmEditableParagraphFacade.layoutBorrowing(
                request = JvmEditableParagraphFacadeRequest(
                    snapshot = snapshot,
                    sourceRange = sourceRange,
                    constraints = oneLineConstraints(request.constraints, lineTop),
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
                    continuation = continuation,
                    cancellationToken = request.cancellationToken,
                ),
                backend = backend,
            )
            if (request.cancellationToken.isCancellationRequested()) {
                return ComputerWork(IncrementalParagraphComputation.Cancelled)
            }
            val paragraph = when (paragraphResult) {
                is ParagraphLayoutResult.Success -> paragraphResult
                is ParagraphLayoutResult.Failure -> return ComputerWork(
                    IncrementalParagraphComputation.Failure(
                        IncrementalLayoutError.InvalidRange(
                            "JVM paragraph layout failed: ${paragraphResult.error.message}",
                        ),
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
            val nextTop = line.lineBox.bottom
            lineTops += LineTop(line.range.start, line.lineBox.top)
            computed += IncrementalComputedLine(
                line = line,
                continuation = LayoutContinuationSignature(
                    boundary = line.range.endExclusive,
                    semanticValue = continuationSemantics(sessionRequest, request, nextTop),
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
            lineTop = next.resumptionRegionTop
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

    private fun reflowTop(
        target: IncrementalMaterializationTarget,
        request: IncrementalLayoutRequest,
    ): LayoutUnit? {
        if (target.reflowStart == request.input.text.range.start) return request.constraints.region.top
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
            checkpoint.top.takeIf { mapped == target.reflowStart }
        }
    }

    private fun continuationForWindow(
        sessionRequest: JvmIncrementalParagraphLayoutRequest,
        request: IncrementalLayoutRequest,
        sourceRange: TextRange,
        top: LayoutUnit,
    ): LayoutContinuation? {
        if (sourceRange.start == request.input.text.range.start) return null
        return try {
            JvmEditableParagraphFacade.continuationBorrowing(
                request = JvmEditableParagraphFacadeRequest(
                    snapshot = request.input.text,
                    sourceRange = sourceRange,
                    constraints = oneLineConstraints(request.constraints, request.constraints.region.top),
                    baseDirection = sessionRequest.baseDirection,
                    language = sessionRequest.language,
                    fontCatalog = request.input.typography.fontCatalog,
                    resolutionPolicy = request.input.typography.resolutionPolicy,
                    fontInstanceDescriptor = request.input.typography.fontInstanceDescriptor,
                    features = request.input.typography.features,
                    materialization = sessionRequest.materialization,
                    overflowPolicy = sessionRequest.overflowPolicy,
                    cancellationToken = request.cancellationToken,
                ),
                backend = backend,
                remainingSourceRange = sourceRange,
                resumptionRegionTop = top,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
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
        constraints: HorizontalParagraphConstraints,
        top: LayoutUnit,
    ): HorizontalParagraphConstraints = HorizontalParagraphConstraints(
        region = LayoutRect(
            left = constraints.region.left,
            top = top,
            right = constraints.region.right,
            bottom = LayoutUnit(top.value + constraints.lineMetrics.height.value),
        ),
        lineMetrics = constraints.lineMetrics,
    )

    private fun continuationSemantics(
        sessionRequest: JvmIncrementalParagraphLayoutRequest,
        request: IncrementalLayoutRequest,
        nextTop: LayoutUnit,
    ): String = buildString {
        append("top=").append(nextTop.value)
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
    }

    /** Opens the pinned JVM HarfBuzz backend and transfers its ownership to a new session. */
    public companion object {
        /**
         * Opens a reusable JVM incremental paragraph session.
         *
         * The cache budget is validated before opening native resources. Native-backend failure
         * or cancellation is returned without creating a session. A successful session must be
         * closed by its owner.
         *
         * @throws IllegalArgumentException when [cacheBudgetBytes] is negative.
         */
        public fun open(cacheBudgetBytes: Long = DEFAULT_CACHE_BUDGET_BYTES):
            FontOperationResult<JvmIncrementalParagraphLayoutSession> =
            openWithBackendFactory(cacheBudgetBytes) { JvmHarfBuzzShapingBackend.open() }

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

    private data class LineTop(
        val start: TextIndex,
        val top: LayoutUnit,
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
    ) {
        companion object {
            fun from(request: JvmIncrementalParagraphLayoutRequest): JvmCompositionConfiguration =
                JvmCompositionConfiguration(
                    baseDirection = request.baseDirection,
                    language = request.language,
                    materializationIdentity = ParagraphMaterializationIdentity.from(request.materialization),
                    overflowPolicy = request.overflowPolicy,
                )
        }
    }
}

private fun EditableLineMaterialization.identityForSession(): Any = when (this) {
    EditableLineMaterialization.LayoutOnly -> "layout-only"
    is EditableLineMaterialization.Renderable -> listOf(variant, outlineProfile)
}

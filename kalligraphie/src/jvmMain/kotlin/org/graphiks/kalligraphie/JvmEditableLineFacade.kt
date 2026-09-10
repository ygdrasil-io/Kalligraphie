@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditableLineDiagnostic
import org.graphiks.kalligraphie.api.EditableLineDiagnosticSeverity
import org.graphiks.kalligraphie.api.EditableLineError
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineRequest
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.EditorOperationContext
import org.graphiks.kalligraphie.api.EditorOperationLimitExceeded
import org.graphiks.kalligraphie.api.EditorOperationLimitKind
import org.graphiks.kalligraphie.api.EditorOperationProfile
import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontDiagnosticSeverity
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.LineControlKind
import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.OpenTypeScript
import org.graphiks.kalligraphie.api.ParagraphPositioningPolicy
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShaperCluster
import org.graphiks.kalligraphie.api.ShaperClusterToken
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShapingFeaturePolicy
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.ShapingResourceProfile
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.UnicodeAnalysis
import org.graphiks.kalligraphie.api.UnicodeAnalysisOutcome
import org.graphiks.kalligraphie.api.UnicodeAnalysisProfile
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.api.toDiagnostic
import org.graphiks.kalligraphie.api.intersect
import org.graphiks.kalligraphie.layout.ExactEditableLineLayouter
import org.graphiks.kalligraphie.unicode.JvmUnicodeAnalyzer

/**
 * Complete explicit input to the JVM reference editable-line journey.
 *
 * The request describes exactly one complete, non-wrapped [snapshot]. Its base direction,
 * language, baseline feature policy, feature overrides, vertical metrics, and publication mode
 * are all explicit. A horizontal tab additionally requires [positioning]. Script and run
 * direction are resolved by the pinned Unicode analysis and then copied explicitly into every
 * HarfBuzz request; the facade never defaults text to LTR. Hard line separators and a tab without
 * positioning are rejected with an exact [EditableLineError.UnsupportedLineControl] before
 * Unicode analysis or shaping begins.
 * [materialization] borrows any resolver it contains only for the synchronous call. The request
 * captures its feature list and is safe to share between threads when its font and borrowed
 * resolver support concurrent calls.
 */
public class JvmEditableLineFacadeRequest(
    /** Complete immutable source snapshot to analyze, shape, and position. */
    public val snapshot: TextSnapshot,
    /** Single TrueType-derived font instance used to shape every resolved run. */
    public val font: FontInstance,
    /** Explicit UAX #9 paragraph base direction. */
    public val baseDirection: BaseDirection,
    /** Explicit BCP 47 language forwarded to Unicode analysis and each shaping run. */
    public val language: String,
    /** Versioned baseline feature policy that the pinned HarfBuzz backend must implement. */
    public val featurePolicy: ShapingFeaturePolicy,
    features: List<OpenTypeFeature>,
    /** Explicit vertical metrics for the horizontal line box. */
    public val verticalMetrics: LineVerticalMetrics,
    /** Explicit layout-only or profile-certifying publication mode. */
    public val materialization: EditableLineMaterialization,
    /** Explicit BiDi level required only when [snapshot] is empty. */
    public val emptyLineBidiLevel: Int? = null,
    /** Cooperative cancellation signal observed during analysis, shaping, and materialization. */
    public val cancellationToken: CancellationToken = CancellationToken.none,
    /** Resource profile enforced before Unicode analysis and any shaping work begins. */
    public val unicodeAnalysisProfile: UnicodeAnalysisProfile = UnicodeAnalysisProfile.unbounded,
    /** Resource profile enforced for each explicit HarfBuzz shaping run. */
    public val shapingResourceProfile: ShapingResourceProfile = ShapingResourceProfile.unbounded,
    /**
     * Explicit alignment and tab-stop policy, or `null` when the line contains no horizontal tab.
     *
     * A snapshot containing `U+0009 CHARACTER TABULATION` is rejected unless this policy is
     * present. Explicit and implicit stops are then resolved by [ParagraphPositioningPolicy].
     */
    public val positioning: ParagraphPositioningPolicy?,
    /** Shared finite resource policy for this complete analysis-through-publication operation. */
    public val operationProfile: EditorOperationProfile,
) {
    /**
     * Creates a request through the historical constructor that includes [positioning].
     *
     * The compatibility route remains unbounded until a caller chooses the primary constructor
     * and supplies an explicit [operationProfile].
     */
    public constructor(
        snapshot: TextSnapshot,
        font: FontInstance,
        baseDirection: BaseDirection,
        language: String,
        featurePolicy: ShapingFeaturePolicy,
        features: List<OpenTypeFeature>,
        verticalMetrics: LineVerticalMetrics,
        materialization: EditableLineMaterialization,
        emptyLineBidiLevel: Int? = null,
        cancellationToken: CancellationToken = CancellationToken.none,
        unicodeAnalysisProfile: UnicodeAnalysisProfile = UnicodeAnalysisProfile.unbounded,
        shapingResourceProfile: ShapingResourceProfile = ShapingResourceProfile.unbounded,
        positioning: ParagraphPositioningPolicy?,
    ) : this(
        snapshot,
        font,
        baseDirection,
        language,
        featurePolicy,
        features,
        verticalMetrics,
        materialization,
        emptyLineBidiLevel,
        cancellationToken,
        unicodeAnalysisProfile,
        shapingResourceProfile,
        positioning,
        EditorOperationProfile.unbounded,
    )

    /**
     * Creates a request through the original source- and binary-compatible constructor.
     *
     * This form has no positioning policy. It therefore remains suitable for lines without a
     * horizontal tab, while a line containing `U+0009 CHARACTER TABULATION` is rejected with
     * [EditableLineError.UnsupportedLineControl].
     */
    public constructor(
        snapshot: TextSnapshot,
        font: FontInstance,
        baseDirection: BaseDirection,
        language: String,
        featurePolicy: ShapingFeaturePolicy,
        features: List<OpenTypeFeature>,
        verticalMetrics: LineVerticalMetrics,
        materialization: EditableLineMaterialization,
        emptyLineBidiLevel: Int? = null,
        cancellationToken: CancellationToken = CancellationToken.none,
        unicodeAnalysisProfile: UnicodeAnalysisProfile = UnicodeAnalysisProfile.unbounded,
        shapingResourceProfile: ShapingResourceProfile = ShapingResourceProfile.unbounded,
    ) : this(
        snapshot = snapshot,
        font = font,
        baseDirection = baseDirection,
        language = language,
        featurePolicy = featurePolicy,
        features = features,
        verticalMetrics = verticalMetrics,
        materialization = materialization,
        emptyLineBidiLevel = emptyLineBidiLevel,
        cancellationToken = cancellationToken,
        unicodeAnalysisProfile = unicodeAnalysisProfile,
        shapingResourceProfile = shapingResourceProfile,
        positioning = null,
        operationProfile = EditorOperationProfile.unbounded,
    )

    /** Creates a non-tab request with an explicit complete-operation resource policy. */
    public constructor(
        snapshot: TextSnapshot,
        font: FontInstance,
        baseDirection: BaseDirection,
        language: String,
        featurePolicy: ShapingFeaturePolicy,
        features: List<OpenTypeFeature>,
        verticalMetrics: LineVerticalMetrics,
        materialization: EditableLineMaterialization,
        emptyLineBidiLevel: Int? = null,
        cancellationToken: CancellationToken = CancellationToken.none,
        unicodeAnalysisProfile: UnicodeAnalysisProfile = UnicodeAnalysisProfile.unbounded,
        shapingResourceProfile: ShapingResourceProfile = ShapingResourceProfile.unbounded,
        operationProfile: EditorOperationProfile,
    ) : this(
        snapshot,
        font,
        baseDirection,
        language,
        featurePolicy,
        features,
        verticalMetrics,
        materialization,
        emptyLineBidiLevel,
        cancellationToken,
        unicodeAnalysisProfile,
        shapingResourceProfile,
        null,
        operationProfile,
    )

    /** Immutable OpenType feature overrides applied in deterministic caller order. */
    public val features: List<OpenTypeFeature> = features.toList()

    init {
        require(language.isNotBlank()) { "Language must not be blank." }
        require(this.features.map(OpenTypeFeature::tag).distinct().size == this.features.size) {
            "OpenType feature overrides must not repeat a tag."
        }
        if (snapshot.scalars.isEmpty()) {
            require(emptyLineBidiLevel != null) { "An empty editable line requires an explicit BiDi level." }
            require(emptyLineBidiLevel in 0..126) { "Empty-line BiDi level must be between 0 and 126." }
            require(baseDirection.toShapingDirection().matches(emptyLineBidiLevel)) {
                "Empty-line BiDi level must agree with the explicit base direction."
            }
        } else {
            require(emptyLineBidiLevel == null) { "An empty-line BiDi level is valid only for an empty snapshot." }
        }
    }
}

/**
 * JVM-reference consumer facade for one exact editable Unicode line.
 *
 * The facade first rejects unsupported line controls, then executes the deterministic route:
 * ICU4J Unicode analysis, the embedded
 * hash-verified HarfBuzz JVM backend, and portable final-line layout. It returns a typed failure
 * when Unicode inputs are invalid or HarfBuzz cannot open or shape. Android and Apple adapters
 * are deliberately not selected by this JVM-only entry point. Each call delegates to a
 * short-lived [JvmEditableLineLayoutSession], so it owns no native handle after returning;
 * renderable mode borrows the resolver supplied in [JvmEditableLineFacadeRequest]. Consumers
 * laying out successive edits should instead open and reuse an explicit session.
 */
public object JvmEditableLineFacade {
    /**
     * Produces one editable line through the complete JVM reference route.
     *
     * Unsupported hard separators and tabs without a positioning policy return an exact typed
     * failure before the short-lived session or HarfBuzz backend is opened. The session repeats
     * that preflight after admitting the operation so its complete validation-through-publication
     * work stays under the lifecycle lease. All shaping requests explicitly receive resolved
     * script, direction, language, UAX #9 level, BOT/EOT flags, baseline policy, and feature
     * overrides. A successful line preserves shaped runs and their backend identities;
     * `RENDERABLE` publication additionally certifies every final glyph through the selected
     * certified representation profile.
     */
    public fun layout(request: JvmEditableLineFacadeRequest): EditableLineResult {
        val context = EditorOperationContext.create(request.operationProfile, request.cancellationToken)
        preflight(request, context)?.let { return it }
        val session = when (val opened = JvmEditableLineLayoutSession.open()) {
            is FontOperationResult.Success -> opened.value
            is FontOperationResult.Failure -> return shapingFailure(opened)
            is FontOperationResult.Cancelled -> return EditableLineResult.Cancelled(opened.diagnostics.toEditableDiagnostics())
        }
        return layoutWithOwnedSession(request, session, context)
    }

    internal fun layout(
        request: JvmEditableLineFacadeRequest,
        backend: ShapingBackend,
    ): EditableLineResult {
        val context = EditorOperationContext.create(request.operationProfile, request.cancellationToken)
        var result: EditableLineResult? = null
        var closeResult: FontOperationResult<Unit>? = null
        try {
            result = layoutBorrowing(request, backend, context)
        } finally {
            closeResult = backend.close()
        }
        return includeBackendCloseResult(checkNotNull(result), checkNotNull(closeResult))
    }

    internal fun layoutBorrowing(
        request: JvmEditableLineFacadeRequest,
        backend: ShapingBackend,
    ): EditableLineResult = layoutBorrowing(
        request,
        backend,
        EditorOperationContext.create(request.operationProfile, request.cancellationToken),
    )

    internal fun layoutBorrowing(
        request: JvmEditableLineFacadeRequest,
        backend: ShapingBackend,
        context: EditorOperationContext,
    ): EditableLineResult {
        val analysis = when (val analyzed = analyze(request, context)) {
            is FacadeUnicodeAnalysis.Success -> analyzed.analysis
            is FacadeUnicodeAnalysis.Result -> return analyzed.result
        }
        return layoutAnalyzed(request, analysis, context.boundedBackend(backend), context)
    }

    private fun layoutWithOwnedSession(
        request: JvmEditableLineFacadeRequest,
        session: JvmEditableLineLayoutSession,
        context: EditorOperationContext,
    ): EditableLineResult {
        var result: EditableLineResult? = null
        var closeResult: FontOperationResult<Unit>? = null
        try {
            result = session.layout(request, context)
        } finally {
            closeResult = session.close()
        }
        return includeBackendCloseResult(checkNotNull(result), checkNotNull(closeResult))
    }

    private fun analyze(
        request: JvmEditableLineFacadeRequest,
        context: EditorOperationContext,
    ): FacadeUnicodeAnalysis = try {
        preflight(request, context)?.let { return FacadeUnicodeAnalysis.Result(it) }
        when (
            val outcome = JvmUnicodeAnalyzer.create().analyze(
                snapshot = request.snapshot,
                request = UnicodeAnalysisRequest(request.baseDirection, request.language),
                profile = request.operationProfile.intersect(request.unicodeAnalysisProfile),
                cancellationToken = context.cancellationToken,
            )
        ) {
            is UnicodeAnalysisOutcome.Success -> FacadeUnicodeAnalysis.Success(outcome.value)
            is UnicodeAnalysisOutcome.LimitExceeded -> FacadeUnicodeAnalysis.Result(
                if (request.operationProfile.maxAnalyzedScalars <= request.unicodeAnalysisProfile.maxScalars) {
                    operationLimitFailure(
                        EditorOperationLimitExceeded(
                            EditorOperationLimitKind.ANALYZED_SCALARS,
                            request.operationProfile.maxAnalyzedScalars.toLong(),
                            outcome.observed.toLong(),
                        ),
                    )
                } else {
                    EditableLineResult.Failure(
                        EditableLineError.UnicodeAnalysisLimitExceeded(outcome.limit, outcome.observed),
                        emptyList(),
                    )
                },
            )

            UnicodeAnalysisOutcome.Cancelled -> FacadeUnicodeAnalysis.Result(EditableLineResult.Cancelled())
        }
    } catch (error: IllegalArgumentException) {
        FacadeUnicodeAnalysis.Result(invalidInput(error))
    }

    private fun preflight(
        request: JvmEditableLineFacadeRequest,
        context: EditorOperationContext,
    ): EditableLineResult? = try {
        context.sourceLimit(request.snapshot)?.let { return operationLimitFailure(it) }
        context.scalarLimit(request.snapshot)?.let { return operationLimitFailure(it) }
        unsupportedLineControl(request)?.let { return it }
        if (context.isCancellationRequested()) EditableLineResult.Cancelled() else null
    } catch (error: IllegalArgumentException) {
        invalidInput(error)
    }

    private fun unsupportedLineControl(
        request: JvmEditableLineFacadeRequest,
    ): EditableLineResult? {
        val snapshot = request.snapshot
        snapshot.scalars.forEachIndexed { index, scalar ->
            val (kind, length) = when (scalar) {
                0x000D -> if (snapshot.scalars.getOrNull(index + 1) == 0x000A) {
                    LineControlKind.CARRIAGE_RETURN_LINE_FEED to 2
                } else {
                    LineControlKind.CARRIAGE_RETURN to 1
                }
                0x000A -> LineControlKind.LINE_FEED to 1
                0x000B -> LineControlKind.VERTICAL_TAB to 1
                0x000C -> LineControlKind.FORM_FEED to 1
                0x0085 -> LineControlKind.NEXT_LINE to 1
                0x2028 -> LineControlKind.LINE_SEPARATOR to 1
                0x2029 -> LineControlKind.PARAGRAPH_SEPARATOR to 1
                0x0009 -> if (request.positioning == null) {
                    LineControlKind.HORIZONTAL_TAB to 1
                } else {
                    return@forEachIndexed
                }
                else -> return@forEachIndexed
            }
            return EditableLineResult.Failure(
                error = EditableLineError.UnsupportedLineControl(
                    kind = kind,
                    range = TextRange(
                        snapshot.textIndexAtScalarBoundary(index),
                        snapshot.textIndexAtScalarBoundary(index + length),
                    ),
                ),
                diagnostics = emptyList(),
            )
        }
        return null
    }

    private fun layoutAnalyzed(
        request: JvmEditableLineFacadeRequest,
        analysis: UnicodeAnalysis,
        backend: ShapingBackend,
        context: EditorOperationContext,
    ): EditableLineResult {
        return when (val shaped = shapeRuns(request, analysis, backend)) {
            is ShapingRunsResult.Success -> try {
                ExactEditableLineLayouter.layout(
                    EditableLineRequest(
                        unicodeAnalysis = analysis,
                        shapedGlyphRuns = shaped.runs,
                        baseDirection = request.baseDirection.toShapingDirection(),
                        emptyLineBidiLevel = request.emptyLineBidiLevel,
                        font = request.font,
                        verticalMetrics = request.verticalMetrics,
                        materialization = request.materialization,
                        snapshot = request.snapshot,
                        positioning = request.positioning,
                        cancellationToken = request.cancellationToken,
                    ),
                    context,
                )
            } catch (error: IllegalArgumentException) {
                invalidInput(error)
            }

            is ShapingRunsResult.Failure -> shapingFailure(shaped.result)
            is ShapingRunsResult.Cancelled -> EditableLineResult.Cancelled(shaped.result.diagnostics.toEditableDiagnostics())
        }
    }

    private fun shapeRuns(
        request: JvmEditableLineFacadeRequest,
        analysis: UnicodeAnalysis,
        backend: ShapingBackend,
    ): ShapingRunsResult {
        val runs = mutableListOf<ShapedGlyphRun>()
        for (plan in shapingPlans(analysis)) {
            for (segment in splitAtTabs(request.snapshot, plan)) {
                if (segment.isTab) {
                    runs += tabControlRun(request, analysis, backend, segment)
                    continue
                }
                val shaped = backend.shape(
                    ShapingRequest(
                        snapshot = request.snapshot,
                        itemRange = segment.range,
                        contextRange = segment.range,
                        font = request.font,
                        direction = segment.direction,
                        script = OpenTypeScript(segment.script),
                        language = segment.language,
                        bidiLevel = segment.bidiLevel,
                        bot = segment.range.start == analysis.range.start,
                        eot = segment.range.endExclusive == analysis.range.endExclusive,
                        featurePolicy = request.featurePolicy,
                        features = request.features,
                        graphemeClusters = segment.graphemeClusters,
                        resourceProfile = request.shapingResourceProfile,
                        cancellationToken = request.cancellationToken,
                    ),
                )
                when (shaped) {
                    is FontOperationResult.Success -> runs += shaped.value
                    is FontOperationResult.Failure -> return ShapingRunsResult.Failure(shaped)
                    is FontOperationResult.Cancelled -> return ShapingRunsResult.Cancelled(shaped)
                }
            }
        }
        return ShapingRunsResult.Success(runs)
    }

    private fun splitAtTabs(snapshot: TextSnapshot, plan: ShapingPlan): List<ShapingPlan> {
        val scalars = snapshot.scalarValues(plan.range)
        if (TAB_SCALAR !in scalars) return listOf(plan)
        val scalarRanges = snapshot.scalarRanges(plan.range)
        val segments = mutableListOf<ShapingPlan>()
        var next = plan.range.start
        scalarRanges.zip(scalars).forEach { (scalarRange, scalar) ->
            if (scalar != TAB_SCALAR) return@forEach
            if (next < scalarRange.start) {
                val range = TextRange(next, scalarRange.start)
                segments += plan.copy(range = range, graphemeClusters = graphemeFragments(range, plan.graphemeClusters))
            }
            segments += plan.copy(
                range = scalarRange,
                graphemeClusters = listOf(scalarRange),
                isTab = true,
            )
            next = scalarRange.endExclusive
        }
        if (next < plan.range.endExclusive) {
            val range = TextRange(next, plan.range.endExclusive)
            segments += plan.copy(range = range, graphemeClusters = graphemeFragments(range, plan.graphemeClusters))
        }
        return segments
    }

    private fun tabControlRun(
        request: JvmEditableLineFacadeRequest,
        analysis: UnicodeAnalysis,
        backend: ShapingBackend,
        plan: ShapingPlan,
    ): ShapedGlyphRun {
        val token = ShaperClusterToken(0)
        return ShapedGlyphRun(
            range = plan.range,
            fontInstanceKey = request.font.key,
            backendIdentity = backend.identity,
            direction = plan.direction,
            script = OpenTypeScript(plan.script),
            language = plan.language,
            bidiLevel = plan.bidiLevel,
            bot = plan.range.start == analysis.range.start,
            eot = plan.range.endExclusive == analysis.range.endExclusive,
            featurePolicy = request.featurePolicy,
            features = request.features,
            graphemeClusters = listOf(plan.range),
            glyphs = emptyList(),
            clusters = listOf(
                ShaperCluster(
                    token = token,
                    sourceRange = plan.range,
                    scalarRanges = listOf(plan.range),
                    admissibleGraphemeBoundaries = listOf(plan.range.start, plan.range.endExclusive),
                ),
            ),
        )
    }

    private fun shapingPlans(analysis: UnicodeAnalysis): List<ShapingPlan> {
        if (analysis.range.start == analysis.range.endExclusive) return emptyList()
        val plans = mutableListOf<ShapingPlan>()
        var scriptIndex = 0
        var bidiIndex = 0
        while (scriptIndex < analysis.scriptLanguageRuns.size && bidiIndex < analysis.logicalBidiRuns.size) {
            val script = analysis.scriptLanguageRuns[scriptIndex]
            val bidi = analysis.logicalBidiRuns[bidiIndex]
            val start = laterBoundary(script.range.start, bidi.range.start)
            val end = earlierBoundary(script.range.endExclusive, bidi.range.endExclusive)
            if (start < end) {
                val range = TextRange(start, end)
                val graphemeClusters = graphemeFragments(range, analysis.graphemeClusters)
                requirePartition(range, graphemeClusters)
                plans += ShapingPlan(
                    range = range,
                    script = script.script,
                    language = script.language,
                    bidiLevel = bidi.level,
                    direction = if (bidi.level % 2 == 0) ShapingDirection.LEFT_TO_RIGHT else ShapingDirection.RIGHT_TO_LEFT,
                    graphemeClusters = graphemeClusters,
                )
            }
            when {
                script.range.endExclusive < bidi.range.endExclusive -> scriptIndex += 1
                bidi.range.endExclusive < script.range.endExclusive -> bidiIndex += 1
                else -> {
                    scriptIndex += 1
                    bidiIndex += 1
                }
            }
        }
        require(plans.isNotEmpty()) { "Non-empty Unicode analysis must produce shaping plans." }
        return plans
    }

    private fun invalidInput(error: IllegalArgumentException): EditableLineResult.Failure =
        EditableLineResult.Failure(
            error = EditableLineError.InvalidInput(error.message ?: "Editable-line input is invalid."),
            diagnostics = emptyList(),
        )

    private fun shapingFailure(result: FontOperationResult.Failure): EditableLineResult.Failure {
        val error = result.error
        return if (error is org.graphiks.kalligraphie.api.FontError.EditorOperationLimitExceeded) {
            operationLimitFailure(error.exceeded, result.diagnostics.toEditableDiagnostics())
        } else {
            EditableLineResult.Failure(
                error = EditableLineError.ShapingFailure(error),
                diagnostics = result.diagnostics.toEditableDiagnostics(),
            )
        }
    }

    private fun operationLimitFailure(
        exceeded: EditorOperationLimitExceeded,
        diagnostics: List<EditableLineDiagnostic> = emptyList(),
    ): EditableLineResult.Failure = EditableLineResult.Failure(
        EditableLineError.OperationLimitExceeded(exceeded),
        diagnostics,
    )

    private fun includeBackendCloseResult(
        result: EditableLineResult,
        closeResult: FontOperationResult<Unit>,
    ): EditableLineResult = when (closeResult) {
        is FontOperationResult.Success -> result
        is FontOperationResult.Failure -> {
            val closeDiagnostics = closeResult.diagnostics
                .ifEmpty { listOf(closeResult.error.toDiagnostic()) }
                .toEditableDiagnostics()
            when (result) {
                is EditableLineResult.Success -> EditableLineResult.Failure(
                    error = EditableLineError.ShapingFailure(closeResult.error),
                    diagnostics = closeDiagnostics,
                )

                is EditableLineResult.Failure -> EditableLineResult.Failure(
                    error = result.error,
                    diagnostics = result.diagnostics + closeDiagnostics,
                )

                is EditableLineResult.Cancelled -> EditableLineResult.Cancelled(
                    diagnostics = result.diagnostics + closeDiagnostics,
                )
            }
        }

        is FontOperationResult.Cancelled -> {
            val closeDiagnostics = closeResult.diagnostics.toEditableDiagnostics()
            when (result) {
                is EditableLineResult.Success -> EditableLineResult.Cancelled(closeDiagnostics)
                is EditableLineResult.Failure -> EditableLineResult.Failure(
                    error = result.error,
                    diagnostics = result.diagnostics + closeDiagnostics,
                )

                is EditableLineResult.Cancelled -> EditableLineResult.Cancelled(
                    diagnostics = result.diagnostics + closeDiagnostics,
                )
            }
        }
    }
}

private sealed interface ShapingRunsResult {
    public data class Success(val runs: List<ShapedGlyphRun>) : ShapingRunsResult
    public data class Failure(val result: FontOperationResult.Failure) : ShapingRunsResult
    public data class Cancelled(val result: FontOperationResult.Cancelled) : ShapingRunsResult
}

private sealed interface FacadeUnicodeAnalysis {
    public class Success(val analysis: UnicodeAnalysis) : FacadeUnicodeAnalysis
    public class Result(val result: EditableLineResult) : FacadeUnicodeAnalysis
}

private data class ShapingPlan(
    val range: TextRange,
    val script: String,
    val language: String,
    val bidiLevel: Int,
    val direction: ShapingDirection,
    val graphemeClusters: List<TextRange>,
    val isTab: Boolean = false,
)

private const val TAB_SCALAR: Int = 0x0009

private fun BaseDirection.toShapingDirection(): ShapingDirection = when (this) {
    BaseDirection.LEFT_TO_RIGHT -> ShapingDirection.LEFT_TO_RIGHT
    BaseDirection.RIGHT_TO_LEFT -> ShapingDirection.RIGHT_TO_LEFT
}

private fun ShapingDirection.matches(level: Int): Boolean = when (this) {
    ShapingDirection.LEFT_TO_RIGHT -> level % 2 == 0
    ShapingDirection.RIGHT_TO_LEFT -> level % 2 != 0
    ShapingDirection.TOP_TO_BOTTOM -> true
}

private fun laterBoundary(first: TextIndex, second: TextIndex): TextIndex = if (first >= second) first else second

private fun earlierBoundary(first: TextIndex, second: TextIndex): TextIndex = if (first <= second) first else second

private fun contains(owner: TextRange, item: TextRange): Boolean =
    item.start >= owner.start && item.endExclusive <= owner.endExclusive

/**
 * Builds the contiguous fragment partition induced when an itemization range crosses an extended
 * grapheme cluster. The fragments are shaping boundaries only: consumers must still use the
 * complete analyzed clusters as their legal editing boundaries.
 */
private fun graphemeFragments(range: TextRange, clusters: List<TextRange>): List<TextRange> =
    clusters.mapNotNull { cluster ->
        val start = laterBoundary(range.start, cluster.start)
        val end = earlierBoundary(range.endExclusive, cluster.endExclusive)
        if (start < end) TextRange(start, end) else null
    }

private fun requirePartition(range: TextRange, clusters: List<TextRange>) {
    require(clusters.isNotEmpty()) { "Shaping plans must be partitioned into grapheme fragments." }
    var next = range.start
    clusters.forEach { cluster ->
        require(cluster.start == next && cluster.start < cluster.endExclusive) {
            "Unicode script and BiDi intersections must preserve a contiguous grapheme-fragment partition."
        }
        next = cluster.endExclusive
    }
    require(next == range.endExclusive) {
        "Unicode script and BiDi intersections must preserve a contiguous grapheme-fragment partition."
    }
}

private fun List<FontDiagnostic>.toEditableDiagnostics(): List<EditableLineDiagnostic> = map { diagnostic ->
    EditableLineDiagnostic(
        code = diagnostic.code,
        severity = diagnostic.severity.toEditableSeverity(),
        message = diagnostic.message,
        glyphId = (diagnostic.location as? FontDiagnosticLocation.Glyph)
            ?.let { org.graphiks.kalligraphie.api.GlyphId(it.glyphId) },
    )
}

private fun FontDiagnosticSeverity.toEditableSeverity(): EditableLineDiagnosticSeverity = when (this) {
    FontDiagnosticSeverity.INFO,
    FontDiagnosticSeverity.WARNING,
    -> EditableLineDiagnosticSeverity.WARNING

    FontDiagnosticSeverity.ERROR -> EditableLineDiagnosticSeverity.ERROR
}

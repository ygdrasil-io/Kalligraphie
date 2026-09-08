package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditableLineDiagnostic
import org.graphiks.kalligraphie.api.EditableLineDiagnosticSeverity
import org.graphiks.kalligraphie.api.EditableLineError
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineRequest
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontDiagnosticSeverity
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.OpenTypeScript
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShapingFeaturePolicy
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.UnicodeAnalysis
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.api.toDiagnostic
import org.graphiks.kalligraphie.layout.ExactEditableLineLayouter
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import org.graphiks.kalligraphie.unicode.JvmUnicodeAnalyzer

/**
 * Complete explicit input to the JVM reference editable-line journey.
 *
 * The request describes exactly one complete, non-wrapped [snapshot]. Its base direction,
 * language, baseline feature policy, feature overrides, vertical metrics, and publication mode
 * are all explicit. Script and run direction are resolved by the pinned Unicode analysis and
 * then copied explicitly into every HarfBuzz request; the facade never defaults text to LTR.
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
    /** Cooperative cancellation signal used only while materializing glyph representations. */
    public val cancellationToken: CancellationToken = CancellationToken.none,
) {
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
 * The facade executes the complete deterministic route: ICU4J Unicode analysis, the embedded
 * hash-verified HarfBuzz JVM backend, and portable final-line layout. It returns a typed failure
 * when Unicode inputs are invalid or HarfBuzz cannot open or shape. Android and Apple adapters
 * are deliberately not selected by this JVM-only entry point. It owns no native handle after a
 * call returns; renderable mode borrows the resolver supplied in [JvmEditableLineFacadeRequest].
 */
public object JvmEditableLineFacade {
    /**
     * Produces one editable line through the complete JVM reference route.
     *
     * All shaping requests explicitly receive resolved script, direction, language, UAX #9
     * level, BOT/EOT flags, baseline policy, and feature overrides. A successful line preserves
     * shaped runs and their backend identities; `RENDERABLE` publication additionally certifies
     * every final glyph through the selected certified representation profile.
     */
    public fun layout(request: JvmEditableLineFacadeRequest): EditableLineResult {
        val analysis = try {
            JvmUnicodeAnalyzer.create().analyze(
                snapshot = request.snapshot,
                request = UnicodeAnalysisRequest(request.baseDirection, request.language),
            )
        } catch (error: IllegalArgumentException) {
            return invalidInput(error)
        }
        val backend = when (val opened = JvmHarfBuzzShapingBackend.open()) {
            is FontOperationResult.Success -> opened.value
            is FontOperationResult.Failure -> return shapingFailure(opened)
            is FontOperationResult.Cancelled -> return EditableLineResult.Cancelled(opened.diagnostics.toEditableDiagnostics())
        }
        return layout(request, analysis, backend)
    }

    internal fun layout(
        request: JvmEditableLineFacadeRequest,
        backend: ShapingBackend,
    ): EditableLineResult {
        val analysis = try {
            JvmUnicodeAnalyzer.create().analyze(
                snapshot = request.snapshot,
                request = UnicodeAnalysisRequest(request.baseDirection, request.language),
            )
        } catch (error: IllegalArgumentException) {
            return invalidInput(error)
        }
        return layout(request, analysis, backend)
    }

    private fun layout(
        request: JvmEditableLineFacadeRequest,
        analysis: UnicodeAnalysis,
        backend: ShapingBackend,
    ): EditableLineResult {
        var layoutResult: EditableLineResult? = null
        var closeResult: FontOperationResult<Unit>? = null
        try {
            layoutResult = when (val shaped = shapeRuns(request, analysis, backend)) {
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
                            cancellationToken = request.cancellationToken,
                        ),
                    )
                } catch (error: IllegalArgumentException) {
                    invalidInput(error)
                }

                is ShapingRunsResult.Failure -> shapingFailure(shaped.result)
                is ShapingRunsResult.Cancelled -> EditableLineResult.Cancelled(shaped.result.diagnostics.toEditableDiagnostics())
            }
        } finally {
            closeResult = backend.close()
        }
        return includeBackendCloseResult(checkNotNull(layoutResult), checkNotNull(closeResult))
    }

    private fun shapeRuns(
        request: JvmEditableLineFacadeRequest,
        analysis: UnicodeAnalysis,
        backend: ShapingBackend,
    ): ShapingRunsResult {
        val runs = mutableListOf<ShapedGlyphRun>()
        for (plan in shapingPlans(analysis)) {
            when (val shaped = backend.shape(
                ShapingRequest(
                    snapshot = request.snapshot,
                    range = plan.range,
                    font = request.font,
                    direction = plan.direction,
                    script = OpenTypeScript(plan.script),
                    language = plan.language,
                    bidiLevel = plan.bidiLevel,
                    bot = plan.range.start == analysis.range.start,
                    eot = plan.range.endExclusive == analysis.range.endExclusive,
                    featurePolicy = request.featurePolicy,
                    features = request.features,
                    graphemeClusters = plan.graphemeClusters,
                ),
            )) {
                is FontOperationResult.Success -> runs += shaped.value
                is FontOperationResult.Failure -> return ShapingRunsResult.Failure(shaped)
                is FontOperationResult.Cancelled -> return ShapingRunsResult.Cancelled(shaped)
            }
        }
        return ShapingRunsResult.Success(runs)
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

    private fun shapingFailure(result: FontOperationResult.Failure): EditableLineResult.Failure =
        EditableLineResult.Failure(
            error = EditableLineError.ShapingFailure(result.error),
            diagnostics = result.diagnostics.toEditableDiagnostics(),
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

private data class ShapingPlan(
    val range: TextRange,
    val script: String,
    val language: String,
    val bidiLevel: Int,
    val direction: ShapingDirection,
    val graphemeClusters: List<TextRange>,
)

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

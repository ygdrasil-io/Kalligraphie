@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.api

/** Resource dimension bounded across one complete high-level editor layout operation. */
public enum class EditorOperationLimitKind {
    /** UTF-8 bytes or UTF-16 code units in the immutable source revision. */
    SOURCE_UNITS,

    /** Unicode scalars admitted for decoding and Unicode analysis. */
    ANALYZED_SCALARS,

    /** Deterministic scalar-conversion and boundary work performed by line breaking. */
    LINE_BREAK_WORK,

    /** Glyphs emitted by any one shaping-engine invocation. */
    GLYPHS_PER_RUN,

    /** Glyphs emitted by all native attempts and synthesized by final layout together. */
    TOTAL_GLYPHS,
}

/** Exact typed resource-limit observation that rejected one complete editor operation. */
public data class EditorOperationLimitExceeded(
    /** Resource dimension whose configured maximum was exceeded. */
    public val kind: EditorOperationLimitKind,
    /** Inclusive configured maximum for this resource dimension. */
    public val maximum: Long,
    /** First complete deterministic count known to exceed [maximum]. */
    public val observed: Long,
) {
    init {
        require(maximum >= 0L) { "Editor operation limit maximum must be non-negative." }
        require(observed > maximum) { "An exceeded editor operation limit must exceed its maximum." }
    }
}

/**
 * Immutable finite or explicitly unbounded resource policy for one editor operation.
 *
 * A high-level invocation creates one private operation budget from this value and uses the same
 * cooperative [CancellationToken] through decoding, Unicode analysis, line breaking, every
 * shaping and fallback attempt, final line construction, paragraph composition, incremental
 * invalidation, and flow composition. Limits and cancellation affect admission only: they never
 * participate in text, shaping, continuation, or cache identity, and a failure publishes no
 * partial result.
 *
 * [maxGlyphsPerRun] is checked before [maxTotalGlyphs] for one native result. Total glyph work
 * includes successful native results later rejected by fallback and synthetic glyphs reserved by
 * final layout; glyphless controls contribute zero. Cooperative checks happen outside monolithic
 * ICU and HarfBuzz calls, which are not preempted in flight.
 */
public class EditorOperationProfile(
    /** Maximum UTF-8 bytes or UTF-16 code units admitted from the complete source revision. */
    public val maxSourceUnits: Int = Int.MAX_VALUE,
    /** Maximum Unicode scalars admitted for decoding and analysis. */
    public val maxAnalyzedScalars: Int = Int.MAX_VALUE,
    /** Maximum deterministic line-break work units across the complete operation. */
    public val maxLineBreakWork: Long = Long.MAX_VALUE,
    /** Maximum glyphs accepted from any one shaping-engine invocation. */
    public val maxGlyphsPerRun: Int = Int.MAX_VALUE,
    /** Maximum native-attempt and synthetic glyphs across the complete operation. */
    public val maxTotalGlyphs: Long = Long.MAX_VALUE,
    /** Positive interval between cooperative observations in iterative work. */
    public val cancellationCheckInterval: Int = 256,
) {
    init {
        require(maxSourceUnits >= 0) { "Editor operation source-unit limit must be non-negative." }
        require(maxAnalyzedScalars >= 0) { "Editor operation scalar limit must be non-negative." }
        require(maxLineBreakWork >= 0L) { "Editor operation line-break limit must be non-negative." }
        require(maxGlyphsPerRun >= 0) { "Editor operation per-run glyph limit must be non-negative." }
        require(maxTotalGlyphs >= 0L) { "Editor operation total-glyph limit must be non-negative." }
        require(cancellationCheckInterval > 0) { "Editor operation cancellation interval must be positive." }
    }

    /** Existing canonical decoding profile projected from this operation policy. */
    public val textDecodingProfile: TextDecodingProfile
        get() = TextDecodingProfile(maxSourceUnits, maxAnalyzedScalars, cancellationCheckInterval)

    /** Existing Unicode-analysis profile projected from this operation policy. */
    public val unicodeAnalysisProfile: UnicodeAnalysisProfile
        get() = UnicodeAnalysisProfile(maxAnalyzedScalars, cancellationCheckInterval)

    /** Existing per-run shaping profile projected from this operation policy. */
    public val shapingResourceProfile: ShapingResourceProfile
        get() = ShapingResourceProfile(maxAnalyzedScalars, maxGlyphsPerRun, cancellationCheckInterval)

    /** Standard compatibility policy with no practical resource maximum. */
    public companion object {
        /** Unbounded policy used by source-compatible convenience routes. */
        public val unbounded: EditorOperationProfile = EditorOperationProfile()
    }
}

/**
 * @suppress
 *
 * Mutable counters and the single cancellation token owned by one high-level invocation.
 */
@KalligraphieInternalApi
public class EditorOperationContext private constructor(
    public val profile: EditorOperationProfile,
    public val cancellationToken: CancellationToken,
) {
    private var totalGlyphs: Long = 0L
    private var lineBreakWork: Long = 0L
    private var wrappedBackend: ShapingBackend? = null
    private var rawBackend: ShapingBackend? = null

    public fun sourceLimit(snapshot: TextSnapshot): EditorOperationLimitExceeded? {
        val observed = snapshot.textIndexToSource(snapshot.range.endExclusive).value.toLong()
        return exceeded(EditorOperationLimitKind.SOURCE_UNITS, profile.maxSourceUnits.toLong(), observed)
    }

    public fun scalarLimit(snapshot: TextSnapshot): EditorOperationLimitExceeded? = exceeded(
        EditorOperationLimitKind.ANALYZED_SCALARS,
        profile.maxAnalyzedScalars.toLong(),
        snapshot.scalars.size.toLong(),
    )

    public fun chargeLineBreakWork(amount: Long): EditorOperationLimitExceeded? {
        require(amount >= 0L) { "Line-break work charge must be non-negative." }
        lineBreakWork = saturatedAdd(lineBreakWork, amount)
        return exceeded(EditorOperationLimitKind.LINE_BREAK_WORK, profile.maxLineBreakWork, lineBreakWork)
    }

    public fun chargeGlyphs(amount: Long): EditorOperationLimitExceeded? {
        require(amount >= 0L) { "Glyph charge must be non-negative." }
        totalGlyphs = saturatedAdd(totalGlyphs, amount)
        return exceeded(EditorOperationLimitKind.TOTAL_GLYPHS, profile.maxTotalGlyphs, totalGlyphs)
    }

    public fun isCancellationRequested(): Boolean = cancellationToken.isCancellationRequested()

    public fun boundedBackend(backend: ShapingBackend): ShapingBackend {
        if (backend is OperationBoundedShapingBackend && backend.context === this) return backend
        val existing = wrappedBackend
        if (existing != null) {
            require(rawBackend === backend) { "One editor operation cannot span unrelated shaping backends." }
            return existing
        }
        rawBackend = backend
        return OperationBoundedShapingBackend(backend, this).also { wrappedBackend = it }
    }

    public companion object {
        public fun create(
            profile: EditorOperationProfile,
            cancellationToken: CancellationToken,
        ): EditorOperationContext = EditorOperationContext(profile, cancellationToken)
    }
}

@KalligraphieInternalApi
public fun EditorOperationProfile.intersect(profile: TextDecodingProfile): TextDecodingProfile =
    TextDecodingProfile(
        maxSourceUnits = minOf(maxSourceUnits, profile.maxSourceUnits),
        maxScalars = minOf(maxAnalyzedScalars, profile.maxScalars),
        cancellationCheckInterval = minOf(cancellationCheckInterval, profile.cancellationCheckInterval),
    )

@KalligraphieInternalApi
public fun EditorOperationProfile.intersect(profile: UnicodeAnalysisProfile): UnicodeAnalysisProfile =
    UnicodeAnalysisProfile(
        maxScalars = minOf(maxAnalyzedScalars, profile.maxScalars),
        cancellationCheckInterval = minOf(cancellationCheckInterval, profile.cancellationCheckInterval),
    )

@KalligraphieInternalApi
public fun EditorOperationProfile.intersect(profile: ShapingResourceProfile): ShapingResourceProfile =
    ShapingResourceProfile(
        maxScalars = minOf(maxAnalyzedScalars, profile.maxScalars),
        maxGlyphs = minOf(maxGlyphsPerRun, profile.maxGlyphs),
        cancellationCheckInterval = minOf(cancellationCheckInterval, profile.cancellationCheckInterval),
    )

private class OperationBoundedShapingBackend(
    private val delegate: ShapingBackend,
    val context: EditorOperationContext,
) : ShapingBackend {
    override val identity: ShapingBackendIdentity
        get() = delegate.identity

    override fun shape(request: ShapingRequest): FontOperationResult<ShapedGlyphRun> {
        if (context.isCancellationRequested()) return FontOperationResult.Cancelled()
        val boundedRequest = ShapingRequest(
            snapshot = request.snapshot,
            itemRange = request.itemRange,
            contextRange = request.contextRange,
            font = request.font,
            direction = request.direction,
            script = request.script,
            language = request.language,
            bidiLevel = request.bidiLevel,
            bot = request.bot,
            eot = request.eot,
            featurePolicy = request.featurePolicy,
            features = request.features,
            graphemeClusters = request.graphemeClusters,
            resourceProfile = context.profile.intersect(request.resourceProfile),
            cancellationToken = context.cancellationToken,
        )
        return when (val shaped = delegate.shape(boundedRequest)) {
            is FontOperationResult.Success -> {
                if (context.isCancellationRequested()) return FontOperationResult.Cancelled(shaped.diagnostics)
                val exceeded = context.chargeGlyphs(shaped.value.glyphs.size.toLong())
                if (exceeded == null) shaped else editorOperationLimitFailure(exceeded, shaped.diagnostics)
            }
            is FontOperationResult.Failure -> {
                val perRun = shaped.error as? FontError.ShapingResourceLimitExceeded
                when {
                    perRun?.limit == ShapingResourceLimit.GLYPHS &&
                        context.profile.maxGlyphsPerRun <= request.resourceProfile.maxGlyphs ->
                        editorOperationLimitFailure(
                            EditorOperationLimitExceeded(
                                EditorOperationLimitKind.GLYPHS_PER_RUN,
                                context.profile.maxGlyphsPerRun.toLong(),
                                perRun.observed.toLong(),
                            ),
                            shaped.diagnostics,
                        )
                    perRun?.limit == ShapingResourceLimit.SCALARS &&
                        context.profile.maxAnalyzedScalars <= request.resourceProfile.maxScalars ->
                        editorOperationLimitFailure(
                            EditorOperationLimitExceeded(
                                EditorOperationLimitKind.ANALYZED_SCALARS,
                                context.profile.maxAnalyzedScalars.toLong(),
                                perRun.observed.toLong(),
                            ),
                            shaped.diagnostics,
                        )
                    else -> shaped
                }
            }
            is FontOperationResult.Cancelled -> shaped
        }
    }
}

private fun editorOperationLimitFailure(
    exceeded: EditorOperationLimitExceeded,
    diagnostics: List<FontDiagnostic>,
): FontOperationResult.Failure {
    val error = FontError.EditorOperationLimitExceeded(exceeded)
    return FontOperationResult.Failure(error, diagnostics + error.toDiagnostic())
}

private fun saturatedAdd(left: Long, right: Long): Long =
    if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private fun exceeded(
    kind: EditorOperationLimitKind,
    maximum: Long,
    observed: Long,
): EditorOperationLimitExceeded? =
    if (observed > maximum) EditorOperationLimitExceeded(kind, maximum, observed) else null

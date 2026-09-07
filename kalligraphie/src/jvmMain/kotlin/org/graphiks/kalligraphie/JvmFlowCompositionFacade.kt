package org.graphiks.kalligraphie

import java.util.Collections
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.FlowCompositionError
import org.graphiks.kalligraphie.api.FlowCompositionResult
import org.graphiks.kalligraphie.api.FlowLayout
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.HyphenationMode
import org.graphiks.kalligraphie.api.HyphenationService
import org.graphiks.kalligraphie.api.IncrementalFlowLayoutRequest
import org.graphiks.kalligraphie.api.InlineObjectSnapshot
import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.OverflowPolicy
import org.graphiks.kalligraphie.api.ParagraphLayoutError
import org.graphiks.kalligraphie.api.ParagraphPositioningPolicy
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.TextOrientation
import org.graphiks.kalligraphie.api.VerticalMetricsPolicy
import org.graphiks.kalligraphie.layout.IncrementalFlowLayoutEngine
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend

/**
 * Immutable JVM preparation options for one portable [IncrementalFlowLayoutRequest].
 *
 * The portable request owns flow state, checkpoint mapping, requested coverage, and composition
 * identity. This adapter supplies only the ICU, UAX #14, font, and HarfBuzz inputs needed to
 * prepare a complete paragraph once per facade call. Borrowed materialization and native resources
 * are never retained by the returned [FlowLayout].
 *
 * @param features immutable OpenType feature overrides; defaults to the typography snapshot.
 */
public class JvmFlowCompositionRequest(
    /** Portable bounded flow request consumed by the layout engine. */
    public val request: IncrementalFlowLayoutRequest,
    /** Explicit UAX #9 paragraph base direction. */
    public val baseDirection: BaseDirection,
    /** Explicit BCP 47 language used by Unicode analysis and shaping. */
    public val language: String,
    /** Layout-only or synchronously profile-certified publication mode. */
    public val materialization: EditableLineMaterialization = EditableLineMaterialization.LayoutOnly,
    /** Flow composition requires source-preserving continuation overflow. */
    public val overflowPolicy: OverflowPolicy = OverflowPolicy.Continue,
    /** Tab stops, alignment, and justification applied to every flow line. */
    public val positioning: ParagraphPositioningPolicy = ParagraphPositioningPolicy(),
    /** Hyphenation policy applied by exact line selection. */
    public val hyphenationMode: HyphenationMode = HyphenationMode.MANUAL,
    /** Immutable service required by automatic hyphenation, when selected. */
    public val hyphenationService: HyphenationService? = null,
    /** Definitions for indivisible inline objects in the target snapshot. */
    public val inlineObjects: InlineObjectSnapshot? = null,
    /** Grapheme orientation policy for vertical flow composition. */
    public val textOrientation: TextOrientation = TextOrientation.MIXED,
    /** Missing vertical-metrics policy used by the shaping route. */
    public val verticalMetricsPolicy: VerticalMetricsPolicy = VerticalMetricsPolicy.SYNTHESIZE_IF_UNAVAILABLE,
    features: List<OpenTypeFeature> = request.input.typography.features,
) {
    /** Immutable OpenType feature overrides in deterministic caller order. */
    public val features: List<OpenTypeFeature> = Collections.unmodifiableList(features.toList())

    init {
        require(language.isNotBlank()) { "Flow composition language must not be blank." }
    }
}

/**
 * JVM consumer facade for bounded and incrementally extendable flow-chain composition.
 *
 * Each call prepares one complete paragraph through pinned ICU analysis, UAX #14 breaking, font
 * resolution, and HarfBuzz shaping. The portable [IncrementalFlowLayoutEngine] then derives every
 * suffix from that immutable analysis and owns all reflow and structured-checkpoint decisions.
 * The facade owns no page or renderer and publishes no value after cancellation or failure.
 */
public object JvmFlowCompositionFacade {
    /**
     * Materializes [request] synchronously and closes all facade-owned native resources.
     *
     * Invalid paragraph inputs, region protocols, font failures, and cancellation are returned as
     * typed [FlowCompositionResult.Failure] values.
     */
    public fun layout(request: JvmFlowCompositionRequest): FlowCompositionResult<FlowLayout> {
        if (request.request.cancellationToken.isCancellationRequested()) {
            return FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
        }
        val backend = when (val opened = JvmHarfBuzzShapingBackend.open()) {
            is FontOperationResult.Success -> opened.value
            is FontOperationResult.Failure -> return FlowCompositionResult.Failure(
                FlowCompositionError.ParagraphFailure(ParagraphLayoutError.FontFailure(opened.error)),
            )
            is FontOperationResult.Cancelled -> return FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
        }
        var result: FlowCompositionResult<FlowLayout>? = null
        var closeResult: FontOperationResult<Unit>? = null
        try {
            result = layoutBorrowing(request, backend)
        } finally {
            closeResult = backend.close()
        }
        return when (val closed = checkNotNull(closeResult)) {
            is FontOperationResult.Success -> checkNotNull(result)
            is FontOperationResult.Failure -> FlowCompositionResult.Failure(
                FlowCompositionError.ParagraphFailure(ParagraphLayoutError.FontFailure(closed.error)),
            )
            is FontOperationResult.Cancelled -> FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
        }
    }

    internal fun layoutBorrowing(
        request: JvmFlowCompositionRequest,
        backend: ShapingBackend,
    ): FlowCompositionResult<FlowLayout> {
        val portable = request.request
        if (portable.cancellationToken.isCancellationRequested()) {
            return FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
        }
        val paragraph = try {
            JvmEditableParagraphFacade.prepareParagraphRequestBorrowing(
                JvmEditableParagraphFacadeRequest(
                    snapshot = portable.input.text,
                    sourceRange = portable.input.text.range,
                    constraints = portable.constraints,
                    baseDirection = request.baseDirection,
                    language = request.language,
                    fontCatalog = portable.input.typography.fontCatalog,
                    resolutionPolicy = portable.input.typography.resolutionPolicy,
                    fontInstanceDescriptor = portable.input.typography.fontInstanceDescriptor,
                    features = request.features,
                    materialization = request.materialization,
                    overflowPolicy = request.overflowPolicy,
                    positioning = request.positioning,
                    hyphenationMode = request.hyphenationMode,
                    hyphenationService = request.hyphenationService,
                    inlineObjects = request.inlineObjects,
                    textOrientation = request.textOrientation,
                    verticalMetricsPolicy = request.verticalMetricsPolicy,
                    cancellationToken = portable.cancellationToken,
                ),
                backend,
            ) ?: return FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
        } catch (error: IllegalArgumentException) {
            return FlowCompositionResult.Failure(
                FlowCompositionError.ParagraphFailure(
                    ParagraphLayoutError.InvalidInput(error.message ?: "Flow paragraph input is invalid."),
                ),
            )
        }
        return IncrementalFlowLayoutEngine.layout(portable, paragraph, request.materialization)
    }
}

package org.graphiks.kalligraphie.layout

import kotlin.math.max

import org.graphiks.kalligraphie.api.EditableLineDiagnostic
import org.graphiks.kalligraphie.api.EditableLineDiagnosticSeverity
import org.graphiks.kalligraphie.api.EditableLineRequest
import org.graphiks.kalligraphie.api.EllipsisSide
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphProvenance
import org.graphiks.kalligraphie.api.GlyphProvenanceRole
import org.graphiks.kalligraphie.api.JustificationMode
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.ParagraphAlignment
import org.graphiks.kalligraphie.api.ParagraphPositioningPolicy
import org.graphiks.kalligraphie.api.ShapedGlyph
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShapingSafetyFlags
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShaperClusterToken
import org.graphiks.kalligraphie.api.TabAlignment
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSnapshot

/**
 * Portable builder refining shaped runs into the final glyph stream of one
 * editable line.
 *
 * All derived-content steps run here before positioning: soft hyphen and
 * automatic hyphenation substitution, tab glyph neutralization with synthetic
 * leader fill, kashida insertion, and justification advance overrides. Every
 * synthetic glyph anchors at a real snapshot boundary and every derived glyph
 * names a real source range; no document position is created.
 */
internal object LineContentPlan {
    fun build(
        request: EditableLineRequest,
        snapshot: TextSnapshot,
        diagnostics: MutableList<EditableLineDiagnostic>,
    ): List<RefinedRun> {
        val instances = request.fontInstances.associateBy(FontInstance::key)
        val softHyphens = request.softHyphenPolicy
        softHyphens?.materializedBoundaries?.forEach { boundary ->
            val ordinal = snapshot.ordinalOf(boundary)
            require(ordinal > 0 && snapshot.scalars[ordinal - 1] == SOFT_HYPHEN) {
                "A materialized soft-hyphen boundary must immediately follow a soft-hyphen scalar."
            }
        }
        val automatic = request.automaticHyphenBreaks?.materializedBoundaries.orEmpty()
        automatic.forEach { boundary ->
            val ordinal = snapshot.ordinalOf(boundary)
            require(ordinal > 0 && ordinal < snapshot.scalars.size) {
                "An automatic hyphen boundary must lie strictly inside the snapshot."
            }
        }
        return request.shapedGlyphRuns.map { run ->
            val instance = instances[run.fontInstanceKey]
            refineRun(request, snapshot, run, instance, softHyphens, automatic, diagnostics)
        }.let { runPlan -> applyEllipsis(request, snapshot, runPlan, diagnostics) }
            .let { runPlan -> applyKashidaSpacing(request, snapshot, runPlan, diagnostics) }
            .let { runPlan -> applyJustificationSpacing(request, snapshot, runPlan) }
    }

    /**
     * Applies ellipsis truncation to one line: hidden scalars publish
     * suppressed zero-advance glyphs and one synthetic marker glyph is
     * inserted at the anchor boundary of its glyph stream.
     */
    private fun applyEllipsis(
        request: EditableLineRequest,
        snapshot: TextSnapshot,
        runs: List<RefinedRun>,
        diagnostics: MutableList<EditableLineDiagnostic>,
    ): List<RefinedRun> {
        val ellipsis = request.ellipsis ?: return runs
        var anchor = ellipsis.hiddenRange.start
        var markerBefore = false
        when (ellipsis.side) {
            EllipsisSide.INLINE_START -> {
                anchor = ellipsis.hiddenRange.endExclusive
                markerBefore = true
            }
            EllipsisSide.INLINE_END, EllipsisSide.MIDDLE -> markerBefore = false
        }
        val inserted = mutableListOf<RefinedGlyph>()
        var markerAttached = false
        val result = runs.toMutableList()
        runs.forEachIndexed { runIndex, run ->
            val instance = request.fontInstances.firstOrNull { it.key == run.sourceRun.fontInstanceKey } ?: return@forEachIndexed
            val markerGlyphs = buildList {
                val resolved = (instance.resolveGlyph(ELLIPSIS_SCALAR) as? FontOperationResult.Success)?.value
                if (resolved != null && resolved.glyphId.value != 0) {
                    val advance = (instance.metrics(resolved.glyphId) as? FontOperationResult.Success)?.value?.advanceWidth
                    if (advance != null && advance.value > 0f) {
                        add(Triple(resolved.glyphId, advance, false))
                    }
                } else {
                    val dot = (instance.resolveGlyph(DOT_SCALAR) as? FontOperationResult.Success)?.value?.glyphId
                    if (dot != null) {
                        val adv = (instance.metrics(dot) as? FontOperationResult.Success)?.value?.advanceWidth
                        if (adv != null) {
                            repeat(3) { add(Triple(dot, adv, true)) }
                        }
                    }
                }
                if (isEmpty()) {
                    diagnostics += EditableLineDiagnostic(
                        code = "layout.ellipsis-marker-unavailable",
                        severity = EditableLineDiagnosticSeverity.WARNING,
                        message = "The selected face supplies neither an ellipsis nor a period glyph; the marker was suppressed deterministically.",
                    )
                }
            }
            val runClusters = run.sourceRun.clusters
            val hiddenInRun = runClusters.any { cluster ->
                val range = cluster.sourceRange
                overlapsOrInside(range, ellipsis.hiddenRange)
            }
            val markerInRun = runClusters.any { cluster ->
                cluster.sourceRange.start <= anchor && anchor <= cluster.sourceRange.endExclusive
            }
            if (!hiddenInRun && !markerInRun) return@forEachIndexed
            val rebuilt = mutableListOf<RefinedGlyph>()
            var anchorEmitted = false
            run.glyphs.forEach { refinedGlyph ->
                val glyph = refinedGlyph.shapedGlyph
                val mapped = mappedRange(snapshot, run.sourceRun, glyph)
                val hidden = rangesOverlap(mapped, ellipsis.hiddenRange)
                val endsAtAnchor = mapped.endExclusive == anchor
                val startsAtAnchor = mapped.start == anchor
                if (markerBefore && startsAtAnchor && !anchorEmitted) {
                    markerGlyphs.forEach { (gid, adv, _) ->
                        rebuilt += RefinedGlyph(
                            shapedGlyph = ShapedGlyph(
                                glyphId = gid,
                                xAdvance = adv,
                                yAdvance = LayoutUnit(0f),
                                xOffset = LayoutUnit(0f),
                                yOffset = LayoutUnit(0f),
                                safetyFlags = glyph.safetyFlags,
                                clusterTokens = listOf(glyph.clusterTokens.first()),
                            ),
                            provenance = GlyphProvenance.Synthetic(anchor, GlyphProvenanceRole.ELLIPSIS),
                        )
                    }
                    anchorEmitted = true
                }
                if (hidden) {
                    val suppressed = (instance.resolveGlyph(SPACE) as? FontOperationResult.Success)?.value?.glyphId ?: glyph.glyphId
                    rebuilt += RefinedGlyph(
                        shapedGlyph = zeroAdvanceShape(glyph, suppressed),
                        provenance = GlyphProvenance.Direct(mapped),
                    )
                } else {
                    rebuilt += RefinedGlyph(glyph, GlyphProvenance.Direct(mapped))
                }
                if (!markerBefore && endsAtAnchor && !anchorEmitted) {
                    markerGlyphs.forEach { (gid, adv, _) ->
                        rebuilt += RefinedGlyph(
                            shapedGlyph = ShapedGlyph(
                                glyphId = gid,
                                xAdvance = adv,
                                yAdvance = LayoutUnit(0f),
                                xOffset = LayoutUnit(0f),
                                yOffset = LayoutUnit(0f),
                                safetyFlags = glyph.safetyFlags,
                                clusterTokens = listOf(glyph.clusterTokens.first()),
                            ),
                            provenance = GlyphProvenance.Synthetic(anchor, GlyphProvenanceRole.ELLIPSIS),
                        )
                    }
                    anchorEmitted = true
                }
            }
            result[runIndex] = RefinedRun(run.sourceRun, rebuilt)
            if (anchorEmitted) markerAttached = true
        }
        if (!markerAttached) {
            diagnostics += EditableLineDiagnostic(
                code = "layout.ellipsis-anchor-unbound",
                severity = EditableLineDiagnosticSeverity.WARNING,
                message = "The ellipsis anchor boundary does not correspond to any shaped cluster; the marker was suppressed deterministically.",
            )
        }
        return result
    }

    private fun overlapsOrInside(range: TextRange, hidden: TextRange): Boolean =
        rangesOverlap(range, hidden) || (range.start.sharesVersionWith(hidden.start) &&
            range.start >= hidden.start && range.endExclusive <= hidden.endExclusive)

    private fun rangesOverlap(left: TextRange, right: TextRange): Boolean =
        left.start.sharesVersionWith(right.start) &&
            left.start < right.endExclusive && right.start < left.endExclusive

    /**
     * Inserts sized kashida glyphs into Arabic-script justified runs.
     *
     * The extra advance required by the target is distributed deterministically
     * over the eligible gaps; the remainder smaller than one tatweel advance is
     * left to the justification pass when it applies, and dropped otherwise.
     */
    private fun applyKashidaSpacing(
        request: EditableLineRequest,
        snapshot: TextSnapshot,
        runs: List<RefinedRun>,
        diagnostics: MutableList<EditableLineDiagnostic>,
    ): List<RefinedRun> {
        val target = request.targetInlineExtent ?: return runs
        val positioning = request.positioning ?: return runs
        if (positioning.alignment != ParagraphAlignment.JUSTIFY || request.isLastLine) return runs
        val mode = positioning.justificationMode
        if (mode != JustificationMode.KASHIDA && mode != JustificationMode.AUTO) return runs
        val eligible = runs.mapIndexed { index, run ->
            val arabic = run.sourceRun.script.value == ARABIC_SCRIPT
            (mode == JustificationMode.KASHIDA || arabic) to index
        }.filter { (isEligible, _) -> isEligible }
        if (eligible.isEmpty()) return runs
        val natural = runs.sumOf { run ->
            run.glyphs.sumOf { glyph -> glyph.shapedGlyph.xAdvance.value.toDouble() }
        }
        val extra = target.value.toDouble() - natural
        if (extra <= 0.0) return runs
        val totalGaps = eligible.sumOf { (_, runIndex) ->
            max(0, runs[runIndex].glyphs.size - 1)
        }
        if (totalGaps <= 0) return runs
        val perGapAdvance = extra / totalGaps
        val result = runs.toMutableList()
        eligible.forEach { (_, index) ->
            val run = runs[index]
            if (run.glyphs.size < 2) return@forEach
            val instance = request.fontInstances.firstOrNull { it.key == run.sourceRun.fontInstanceKey } ?: return@forEach
            val tatweel = (instance.resolveGlyph(KASHIDA_SCALAR) as? FontOperationResult.Success)?.value
            if (tatweel == null || tatweel.glyphId.value == 0) {
                diagnostics += kashidaUnavailable("The selected face has no kashida glyph; the Arabic line was justified with spaced base glyphs.")
                return@forEach
            }
            val advance = (instance.metrics(tatweel.glyphId) as? FontOperationResult.Success)?.value?.advanceWidth
            if (advance == null || advance.value <= 0f) {
                diagnostics += kashidaUnavailable("The selected face could not measure its kashida glyph.")
                return@forEach
            }
            val countPerGap = (perGapAdvance / advance.value.toDouble()).toInt()
            if (countPerGap <= 0) return@forEach
            val expanded = mutableListOf<RefinedGlyph>()
            run.glyphs.forEachIndexed { glyphIndex, glyph ->
                expanded += glyph
                if (glyphIndex < run.glyphs.lastIndex && glyph.provenance !is GlyphProvenance.Synthetic) {
                    val anchorToken = glyph.shapedGlyph.clusterTokens.firstOrNull()
                    if (anchorToken != null) {
                        val anchor = run.sourceRun.clusterFor(anchorToken).sourceRange.start
                        repeat(countPerGap) {
                            expanded += RefinedGlyph(
                                shapedGlyph = ShapedGlyph(
                                    glyphId = tatweel.glyphId,
                                    xAdvance = advance,
                                    yAdvance = LayoutUnit(0f),
                                    xOffset = LayoutUnit(0f),
                                    yOffset = LayoutUnit(0f),
                                    safetyFlags = glyph.shapedGlyph.safetyFlags,
                                    clusterTokens = listOf(anchorToken),
                                ),
                                provenance = GlyphProvenance.Synthetic(anchor, GlyphProvenanceRole.KASHIDA),
                            )
                        }
                    }
                }
            }
            result[index] = RefinedRun(run.sourceRun, expanded)
        }
        return result
    }

    private fun refineRun(
        request: EditableLineRequest,
        snapshot: TextSnapshot,
        run: ShapedGlyphRun,
        instance: FontInstance?,
        softHyphens: org.graphiks.kalligraphie.api.SoftHyphenLinePolicy?,
        automaticBreaks: List<TextIndex>,
        diagnostics: MutableList<EditableLineDiagnostic>,
    ): RefinedRun {
        val stream = mutableListOf<RefinedGlyph>()
        run.glyphs.forEach { glyph ->
            val mapped = mappedRange(snapshot, run, glyph)
            val scalars = snapshot.scalarValues(mapped)
            if (scalars.any { it == TAB } && instance != null) {
                val tabGlyph = neutralTabGlyph(glyph, scalars)
                stream += RefinedGlyph(tabGlyph, GlyphProvenance.Direct(mapped), tabMarker = true)
                return@forEach
            }
            if (scalars.any { it == SOFT_HYPHEN } && instance != null && softHyphens != null) {
                val materialized = mapped.endExclusive in softHyphens.materializedBoundaries
                val suppressed = suppressSoftHyphen(instance, glyph, diagnostics)
                val replacement = if (materialized) {
                    substituteHyphen(instance, glyph, diagnostics) ?: suppressed
                } else {
                    suppressed
                }
                stream += RefinedGlyph(
                    replacement,
                    if (materialized) {
                        GlyphProvenance.Derived(mapped, GlyphProvenanceRole.SOFT_HYPHEN)
                    } else {
                        GlyphProvenance.Direct(mapped)
                    },
                )
                return@forEach
            }
            if (mapped.endExclusive in automaticBreaks && instance != null) {
                val hyphen = substituteHyphen(instance, glyph, diagnostics) ?: glyph
                stream += RefinedGlyph(
                    hyphen,
                    GlyphProvenance.Derived(mapped, GlyphProvenanceRole.AUTOMATIC_HYPHEN),
                )
                return@forEach
            }
            stream += RefinedGlyph(glyph, GlyphProvenance.Direct(mapped))
        }
        if (instance != null && run.direction == ShapingDirection.LEFT_TO_RIGHT &&
            run.clusters.any { cluster ->
                snapshot.scalarValues(cluster.sourceRange).any { it == TAB }
            }
        ) {
            val space = (instance.resolveGlyph(SPACE) as? FontOperationResult.Success)?.value?.glyphId ?: GlyphId(0)
            val glyphTokens = run.glyphs.flatMap { glyph -> glyph.clusterTokens }.toSet()
            val markersNeeded = run.clusters.filter { cluster ->
                snapshot.scalarValues(cluster.sourceRange).any { it == TAB } &&
                    cluster.token !in glyphTokens
            }
            if (markersNeeded.isNotEmpty()) {
                val rebuilt = mutableListOf<RefinedGlyph>()
                val emitted = run.glyphs.toMutableList()
                run.clusters.forEach { cluster ->
                    if (cluster in markersNeeded) {
                        rebuilt += RefinedGlyph(
                            shapedGlyph = zeroAdvanceTab(space, cluster.token),
                            provenance = GlyphProvenance.Direct(cluster.sourceRange),
                            tabMarker = true,
                        )
                    } else {
                        val attached = emitted.filter { glyph -> cluster.token in glyph.clusterTokens }
                        emitted.removeAll(attached.toSet())
                        rebuilt += attached.map { glyph ->
                            RefinedGlyph(glyph, stream.firstOrNull { entry -> entry.shapedGlyph === glyph }?.provenance
                                ?: GlyphProvenance.Direct(mappedRange(snapshot, run, glyph)))
                        }
                    }
                }
                stream.clear()
                stream += rebuilt
            }
        }
        if (instance != null && request.inlineObjects != null && run.direction == ShapingDirection.LEFT_TO_RIGHT &&
            run.clusters.any { cluster -> snapshot.scalarValues(cluster.sourceRange).any { it == OBJECT_REPLACEMENT } }
        ) {
            val glyphTokens = run.glyphs.flatMap { glyph -> glyph.clusterTokens }.toSet()
            val objectClusters = run.clusters.filter { cluster ->
                snapshot.scalarValues(cluster.sourceRange).any { it == OBJECT_REPLACEMENT } &&
                    cluster.token !in glyphTokens
            }
            if (objectClusters.isNotEmpty()) {
                val space = (instance.resolveGlyph(SPACE) as? FontOperationResult.Success)?.value?.glyphId ?: GlyphId(0)
                val rebuilt = mutableListOf<RefinedGlyph>()
                val emitted = run.glyphs.toMutableList()
                run.clusters.forEach { cluster ->
                    if (cluster in objectClusters) {
                        val definition = request.inlineObjects?.definition(cluster.sourceRange.start)
                        rebuilt += RefinedGlyph(
                            shapedGlyph = zeroAdvanceShapeForObject(space, cluster.token, definition?.width ?: LayoutUnit(0f)),
                            provenance = GlyphProvenance.Direct(cluster.sourceRange),
                            inlineObjectWidth = definition?.width,
                        )
                    } else {
                        val attached = emitted.filter { glyph -> cluster.token in glyph.clusterTokens }
                        emitted.removeAll(attached.toSet())
                        rebuilt += attached.map { glyph ->
                            RefinedGlyph(glyph, GlyphProvenance.Direct(mappedRange(snapshot, run, glyph)))
                        }
                    }
                }
                stream.clear()
                stream += rebuilt
            }
        }
        return RefinedRun(run, stream)
    }

    private fun zeroAdvanceShapeForObject(glyphId: GlyphId, token: ShaperClusterToken, width: LayoutUnit): ShapedGlyph = ShapedGlyph(
        glyphId = glyphId,
        xAdvance = width,
        yAdvance = LayoutUnit(0f),
        xOffset = LayoutUnit(0f),
        yOffset = LayoutUnit(0f),
        safetyFlags = ShapingSafetyFlags(false, false),
        clusterTokens = listOf(token),
    )

    private fun kashidaUnavailable(reason: String): EditableLineDiagnostic = EditableLineDiagnostic(
        code = "layout.kashida-unavailable",
        severity = EditableLineDiagnosticSeverity.WARNING,
        message = reason,
    )

    private fun zeroAdvanceTab(glyphId: GlyphId, token: ShaperClusterToken): ShapedGlyph = ShapedGlyph(
        glyphId = glyphId,
        xAdvance = LayoutUnit(0f),
        yAdvance = LayoutUnit(0f),
        xOffset = LayoutUnit(0f),
        yOffset = LayoutUnit(0f),
        safetyFlags = ShapingSafetyFlags(false, false),
        clusterTokens = listOf(token),
    )

    private fun neutralTabGlyph(glyph: ShapedGlyph, scalars: List<Int>): ShapedGlyph = ShapedGlyph(
        glyphId = glyph.glyphId,
        xAdvance = LayoutUnit(0f),
        yAdvance = LayoutUnit(0f),
        xOffset = LayoutUnit(0f),
        yOffset = LayoutUnit(0f),
        safetyFlags = glyph.safetyFlags,
        clusterTokens = glyph.clusterTokens,
    )

    private fun applyJustificationSpacing(
        request: EditableLineRequest,
        snapshot: TextSnapshot,
        runs: List<RefinedRun>,
    ): List<RefinedRun> {
        val target = request.targetInlineExtent ?: return runs
        val positioning = request.positioning ?: return runs
        if (positioning.alignment != ParagraphAlignment.JUSTIFY &&
            !(request.isLastLine && positioning.lastLineAlignment == ParagraphAlignment.JUSTIFY)
        ) {
            return runs
        }
        if (request.isLastLine && positioning.lastLineAlignment != ParagraphAlignment.JUSTIFY) return runs
        val natural = runs.sumOf { run ->
            run.glyphs.sumOf { glyph -> glyph.shapedGlyph.xAdvance.value.toDouble() }
        }
        val extra = target.value.toDouble() - natural
        if (extra <= 0.0) return runs
        val mode = positioning.justificationMode
        val eligible = runs.mapIndexed { runIndex, run ->
            run.glyphs.mapIndexed { index, glyph ->
                val scalars = refScalars(snapshot, run, glyph)
                JustificationUnit(runIndex, index, scalars.any { it.isWhitespaceScalar() }, scalars.any { it.isCjkScalar() })
            }
        }
        val useCjk = mode == JustificationMode.INTER_CHARACTER ||
            mode == JustificationMode.AUTO && eligible.flatten().any { it.cjk }
        val units = eligible.flatten().filter { unit ->
            if (useCjk) unit.cjk else unit.whitespace
        }
        if (units.isEmpty()) return runs
        // A line-trailing whitespace unit does not receive spacing: the last visual glyph is
        // excluded when it is a whitespace unit.
        val lastRunIndex = runs.lastIndex
        val lastVisual = runs.lastOrNull()
            ?.let { run -> run.glyphs.lastOrNull()?.let { glyph -> RefinedGlyphIdentity(lastRunIndex, run.glyphs.size - 1, glyph) } }
        val hasTrailing = lastVisual != null && units.any { unit ->
            unit.runIndex == lastVisual.runIndex && unit.glyphIndex == lastVisual.glyphIndex
        }
        val applicable = if (hasTrailing) units.dropLast(1) else units
        if (applicable.isEmpty()) return runs
        val share = extra / applicable.size
        val result = runs.toMutableList()
        applicable.forEach { unit ->
            val run = result[unit.runIndex]
            val current = run.glyphs[unit.glyphIndex]
            val authorRange = mappedRange(snapshot, run.sourceRun, current.shapedGlyph)
            val shaped = current.shapedGlyph
            val updated = run.glyphs.toMutableList()
            updated[unit.glyphIndex] = RefinedGlyph(
                shapedGlyph = ShapedGlyph(
                    glyphId = shaped.glyphId,
                    xAdvance = LayoutUnit(shaped.xAdvance.value + share.toFloat()),
                    yAdvance = shaped.yAdvance,
                    xOffset = shaped.xOffset,
                    yOffset = shaped.yOffset,
                    safetyFlags = shaped.safetyFlags,
                    clusterTokens = shaped.clusterTokens,
                ),
                provenance = GlyphProvenance.Derived(authorRange, GlyphProvenanceRole.JUSTIFICATION_SPACING),
            )
            result[unit.runIndex] = RefinedRun(run.sourceRun, updated)
        }
        return result
    }

    private data class JustificationUnit(
        val runIndex: Int,
        val glyphIndex: Int,
        val whitespace: Boolean,
        val cjk: Boolean,
    )

    private data class RefinedGlyphIdentity(
        val runIndex: Int,
        val glyphIndex: Int,
        val glyph: RefinedGlyph,
    )

    private fun refScalars(snapshot: TextSnapshot, run: RefinedRun, glyph: RefinedGlyph): List<Int> =
        glyph.shapedGlyph.clusterTokens
            .map(run.sourceRun::clusterFor)
            .flatMap { cluster -> snapshot.scalarValues(cluster.sourceRange) }

    private fun substituteHyphen(
        instance: FontInstance,
        glyph: ShapedGlyph,
        diagnostics: MutableList<EditableLineDiagnostic>,
    ): ShapedGlyph? {
        val resolved = when (val result = instance.resolveGlyph(HYPHEN_MINUS)) {
            is FontOperationResult.Success -> result.value.glyphId
            is FontOperationResult.Failure -> return substitutionUnavailable(diagnostics, result)
            is FontOperationResult.Cancelled -> return substitutionUnavailable(diagnostics, result)
        }
        val advance = when (val metrics = instance.metrics(resolved)) {
            is FontOperationResult.Success -> metrics.value.advanceWidth
            is FontOperationResult.Failure -> return substitutionUnavailable(diagnostics, metrics)
            is FontOperationResult.Cancelled -> return substitutionUnavailable(diagnostics, metrics)
        }
        return ShapedGlyph(
            glyphId = resolved,
            xAdvance = advance,
            yAdvance = LayoutUnit(0f),
            xOffset = LayoutUnit(0f),
            yOffset = LayoutUnit(0f),
            safetyFlags = glyph.safetyFlags,
            clusterTokens = glyph.clusterTokens,
        )
    }

    private fun suppressSoftHyphen(
        instance: FontInstance,
        glyph: ShapedGlyph,
        diagnostics: MutableList<EditableLineDiagnostic>,
    ): ShapedGlyph {
        val space = when (val result = instance.resolveGlyph(SPACE)) {
            is FontOperationResult.Success -> result.value.glyphId
            is FontOperationResult.Failure -> {
                substitutionUnavailable(diagnostics, result)
                return zeroAdvanceShape(glyph, glyph.glyphId)
            }
            is FontOperationResult.Cancelled -> {
                substitutionUnavailable(diagnostics, result)
                return zeroAdvanceShape(glyph, glyph.glyphId)
            }
        }
        return zeroAdvanceShape(glyph, space)
    }

    private fun zeroAdvanceShape(template: ShapedGlyph, glyphId: GlyphId): ShapedGlyph = ShapedGlyph(
        glyphId = glyphId,
        xAdvance = LayoutUnit(0f),
        yAdvance = LayoutUnit(0f),
        xOffset = LayoutUnit(0f),
        yOffset = LayoutUnit(0f),
        safetyFlags = template.safetyFlags,
        clusterTokens = template.clusterTokens,
    )

    private fun <Result> substitutionUnavailable(
        diagnostics: MutableList<EditableLineDiagnostic>,
        result: FontOperationResult<Result>,
    ): ShapedGlyph? {
        diagnostics += EditableLineDiagnostic(
            code = "layout.hyphen-glyph-unavailable",
            severity = EditableLineDiagnosticSeverity.WARNING,
            message = "The selected face could not supply the hyphen substitution glyph; the hyphen was suppressed deterministically. " +
                (when (result) {
                    is FontOperationResult.Failure -> result.error.message
                    is FontOperationResult.Cancelled -> "Operation was cancelled."
                    is FontOperationResult.Success -> ""
                }),
        )
        return null
    }
}

internal fun mappedRange(snapshot: TextSnapshot, run: ShapedGlyphRun, glyph: ShapedGlyph): TextRange {
    val mapped = glyph.clusterTokens.map(run::clusterFor)
    return TextRange(mapped.first().sourceRange.start, mapped.last().sourceRange.endExclusive)
}

internal fun ShapedGlyphRun.clusterFor(token: ShaperClusterToken): org.graphiks.kalligraphie.api.ShaperCluster =
    clusters.first { cluster -> cluster.token == token }

private fun TextSnapshot.ordinalOf(boundary: TextIndex): Int {
    require(boundary.sharesVersionWith(range.start)) { "Boundary must belong to this snapshot version." }
    return (0..scalars.size).firstOrNull { index -> textIndexAtScalarBoundary(index) == boundary }
        ?: throw IllegalArgumentException("Boundary does not belong to this snapshot.")
}

private fun Int.isWhitespaceScalar(): Boolean =
    this == 0x0020 || this == 0x00A0 || this in 0x2000..0x200A || this == 0x3000

private fun Int.isCjkScalar(): Boolean =
    this in 0x3400..0x4DBF ||
        this in 0x4E00..0x9FFF ||
        this in 0xF900..0xFAFF ||
        this in 0x3040..0x30FF ||
        this in 0xAC00..0xD7AF

private const val SOFT_HYPHEN: Int = 0x00AD
private const val HYPHEN_MINUS: Int = 0x002D
private const val SPACE: Int = 0x0020
private const val TAB: Int = 0x0009
private const val KASHIDA_SCALAR: Int = 0x0640
private const val ARABIC_SCRIPT: String = "Arab"
internal const val ELLIPSIS_SCALAR: Int = 0x2026
internal const val DOT_SCALAR: Int = 0x002E
internal const val OBJECT_REPLACEMENT: Int = 0xFFFC
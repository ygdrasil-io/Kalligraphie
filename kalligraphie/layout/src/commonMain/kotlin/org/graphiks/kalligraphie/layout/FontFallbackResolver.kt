package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.FallbackUnit
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontDiagnosticSeverity
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontFaceCapabilities
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontFaceRecord
import org.graphiks.kalligraphie.api.FontFallbackResolution
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.MultiFontEditableLineRequest
import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.OpenTypeScript
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.ParagraphLayoutRequest
import org.graphiks.kalligraphie.api.ShaperCluster
import org.graphiks.kalligraphie.api.ShaperClusterToken
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.UnicodeAnalysis
import org.graphiks.kalligraphie.api.toDiagnostic

/** Resolves one captured line input into shaped runs without leaking temporary assets. */
internal object FontFallbackResolver {
    fun resolve(request: MultiFontEditableLineRequest): FontOperationResult<FontFallbackResolution> = resolve(
        ResolutionRequest(
            snapshot = request.snapshot,
            sourceRange = request.unicodeAnalysis.range,
            shapingContextRange = request.unicodeAnalysis.range,
            unicodeAnalysis = request.unicodeAnalysis,
            fontCatalog = request.fontCatalog,
            resolutionPolicy = request.resolutionPolicy,
            fontInstanceDescriptor = request.fontInstanceDescriptor,
            shapingBackend = request.shapingBackend,
            materialization = request.materialization,
            features = request.features,
            cancellationToken = request.cancellationToken,
        ),
    )

    /** Resolves one paragraph-local range without observing unrelated snapshot text. */
    fun resolveRange(
        request: ParagraphLayoutRequest,
        sourceRange: TextRange,
        shapingContextRange: TextRange,
        unicodeAnalysis: UnicodeAnalysis,
        materialization: EditableLineMaterialization,
    ): FontOperationResult<FontFallbackResolution> = resolve(
        ResolutionRequest(
            snapshot = request.snapshot,
            sourceRange = sourceRange,
            shapingContextRange = shapingContextRange,
            unicodeAnalysis = unicodeAnalysis,
            fontCatalog = request.fontCatalog,
            resolutionPolicy = request.resolutionPolicy,
            fontInstanceDescriptor = request.fontInstanceDescriptor,
            shapingBackend = request.shapingBackend,
            materialization = materialization,
            features = request.features,
            cancellationToken = request.cancellationToken,
        ),
    )

    private fun resolve(request: ResolutionRequest): FontOperationResult<FontFallbackResolution> {
        val units = fallbackUnits(request)
        if (units.isEmpty()) return FontOperationResult.Success(FontFallbackResolution(emptyList(), emptyList(), emptyList()))

        val requirements = requirementsFor(request.materialization)
        val records = request.fontCatalog.faces.associateBy(FontFaceRecord::id)
        val blacklist = mutableSetOf<RejectedCandidate>()
        val instances = mutableMapOf<FontFaceId, FontInstance>()
        val shapedGroups = mutableMapOf<GroupSignature, List<ShapedGlyphRun>>()
        val diagnostics = mutableListOf<FontDiagnostic>()
        var assignments = units.map { unit ->
            if (request.cancellationToken.isCancellationRequested()) {
                return FontOperationResult.Cancelled(diagnostics)
            }
            when (
                val selection = selectCandidate(
                    unit,
                    request.fontCatalog,
                    request.resolutionPolicy,
                    records,
                    requirements,
                    request,
                    instances,
                    blacklist,
                    diagnostics,
                )
            ) {
                is CandidateSelection.Selected -> selection.assigned
                CandidateSelection.Exhausted -> return unresolved(unit, diagnostics)
                is CandidateSelection.Cancelled -> return FontOperationResult.Cancelled(diagnostics + selection.diagnostics)
            }
        }

        while (true) {
            if (request.cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled(diagnostics)
            val shaped = mutableListOf<ShapedGlyphRun>()
            var rejected: List<AssignedUnit>? = null
            contiguousGroups(assignments).forEach { group ->
                if (rejected != null) return@forEach
                if (request.cancellationToken.isCancellationRequested()) {
                    return FontOperationResult.Cancelled(diagnostics)
                }
                val signature = GroupSignature.from(group)
                val cached = shapedGroups[signature]
                if (cached != null) {
                    shaped += cached
                    return@forEach
                }
                when (val attempted = shapeAndValidate(group, request)) {
                    is Attempt.Success -> {
                        shapedGroups[signature] = attempted.runs
                        shaped += attempted.runs
                    }
                    is Attempt.Rejected -> {
                        diagnostics += attempted.diagnostics
                        rejected = group
                    }

                    is Attempt.Cancelled -> return FontOperationResult.Cancelled(diagnostics + attempted.diagnostics)
                }
            }
            val rejectedGroup = rejected
            if (rejectedGroup == null) {
                assignments.filter { it.record.id == request.resolutionPolicy.lastResortFace }.forEach { assigned ->
                    diagnostics += FontDiagnostic(
                        code = "font.fallback-last-resort",
                        severity = FontDiagnosticSeverity.WARNING,
                        location = FontDiagnosticLocation.FaceId(assigned.record.id),
                        message = "The explicitly declared last-resort face ${assigned.record.id} was selected for an indivisible fallback unit.",
                    )
                }
                return FontOperationResult.Success(
                    FontFallbackResolution(
                        units = units,
                        shapedRuns = shaped,
                        instances = assignments.map(AssignedUnit::instance).distinctBy(FontInstance::key),
                        diagnostics = diagnostics,
                    ),
                )
            }

            rejectedGroup.forEach { assigned ->
                blacklist += RejectedCandidate(assigned.unit.range, assigned.record.id, requirements.outlineProfile)
                diagnostics += rejectedCandidateDiagnostic(
                    assigned.record.id,
                    "Shaping or final glyph materialization rejected the complete fallback unit.",
                )
                if (assigned.record.id == request.resolutionPolicy.lastResortFace) {
                    diagnostics += rejectedLastResortDiagnostic(assigned.record.id)
                }
            }
            assignments = assignments.map { assigned ->
                if (assigned in rejectedGroup) {
                    when (
                        val selection = selectCandidate(
                            assigned.unit,
                            request.fontCatalog,
                            request.resolutionPolicy,
                            records,
                            requirements,
                            request,
                            instances,
                            blacklist,
                            diagnostics,
                        )
                    ) {
                        is CandidateSelection.Selected -> selection.assigned
                        CandidateSelection.Exhausted -> return unresolved(assigned.unit, diagnostics)
                        is CandidateSelection.Cancelled -> return FontOperationResult.Cancelled(diagnostics + selection.diagnostics)
                    }
                } else {
                    assigned
                }
            }
        }
    }

    private fun selectCandidate(
        unit: FallbackUnit,
        catalog: FontCatalogSnapshot,
        policy: FontResolutionPolicySnapshot,
        records: Map<FontFaceId, FontFaceRecord>,
        requirements: FontAccessRequirementsSnapshot,
        request: ResolutionRequest,
        instances: MutableMap<FontFaceId, FontInstance>,
        blacklist: MutableSet<RejectedCandidate>,
        diagnostics: MutableList<FontDiagnostic>,
    ): CandidateSelection {
        policy.candidates.forEach { candidate ->
            if (request.cancellationToken.isCancellationRequested()) {
                return CandidateSelection.Cancelled(emptyList())
            }
            val record = records.getValue(candidate.faceId)
            val rejected = RejectedCandidate(unit.range, record.id, requirements.outlineProfile)
            if (rejected in blacklist || !supports(record.capabilities, requirements)) return@forEach
            val instance = instances[record.id] ?: run {
                val face = when (val resolved = catalog.resolveFace(record.id, requirements)) {
                    is FontOperationResult.Success -> resolved.value
                    is FontOperationResult.Failure -> {
                        blacklist += rejected
                        diagnostics += resolved.diagnostics + resolved.error.toDiagnostic()
                        diagnostics += rejectedCandidateDiagnostic(record.id, "Face resolution did not meet the required capabilities.")
                        if (record.id == policy.lastResortFace) diagnostics += rejectedLastResortDiagnostic(record.id)
                        return@forEach
                    }

                    is FontOperationResult.Cancelled -> return CandidateSelection.Cancelled(resolved.diagnostics)
                }
                when (val instantiated = face.instantiate(request.fontInstanceDescriptor)) {
                    is FontOperationResult.Success -> instantiated.value.also { instances[record.id] = it }
                    is FontOperationResult.Failure -> {
                        blacklist += rejected
                        diagnostics += instantiated.diagnostics + instantiated.error.toDiagnostic()
                        diagnostics += rejectedCandidateDiagnostic(record.id, "Face instantiation failed for the requested instance descriptor.")
                        if (record.id == policy.lastResortFace) diagnostics += rejectedLastResortDiagnostic(record.id)
                        return@forEach
                    }

                    is FontOperationResult.Cancelled -> return CandidateSelection.Cancelled(instantiated.diagnostics)
                }
            }
            if (unit.isGlyphless(request.snapshot)) {
                return CandidateSelection.Selected(AssignedUnit(unit, record, instance, glyphless = true))
            }
            when (val mapping = mapsAllRequiredScalars(unit, request, instance)) {
                ScalarMapping.Supported -> return CandidateSelection.Selected(AssignedUnit(unit, record, instance))
                is ScalarMapping.Cancelled -> return CandidateSelection.Cancelled(mapping.diagnostics)
                is ScalarMapping.Unsupported -> diagnostics += mapping.diagnostics
            }
            blacklist += rejected
            diagnostics += rejectedCandidateDiagnostic(record.id, "The complete fallback unit is not covered by the candidate character mapping.")
            if (record.id == policy.lastResortFace) diagnostics += rejectedLastResortDiagnostic(record.id)
        }
        return CandidateSelection.Exhausted
    }

    private fun mapsAllRequiredScalars(
        unit: FallbackUnit,
        request: ResolutionRequest,
        instance: FontInstance,
    ): ScalarMapping {
        var precedingScalar: Int? = null
        request.snapshot.scalarValues(unit.range).forEach { scalar ->
            if (request.cancellationToken.isCancellationRequested()) return ScalarMapping.Cancelled(emptyList())
            if (scalar.isVariationSelector()) {
                val base = precedingScalar ?: return ScalarMapping.Unsupported(emptyList())
                when (val result = instance.resolveGlyph(base, scalar)) {
                    is FontOperationResult.Success -> if (result.value.glyphId.value == 0) return ScalarMapping.Unsupported(emptyList())
                    is FontOperationResult.Failure -> return ScalarMapping.Unsupported(
                        result.diagnostics + result.error.toDiagnostic(),
                    )
                    is FontOperationResult.Cancelled -> return ScalarMapping.Cancelled(result.diagnostics)
                }
                precedingScalar = null
            } else {
                if (scalar !in IGNORED_MAPPING_SCALARS) {
                    when (val result = instance.resolveGlyph(scalar)) {
                        is FontOperationResult.Success -> if (result.value.glyphId.value == 0) return ScalarMapping.Unsupported(emptyList())
                        is FontOperationResult.Failure -> return ScalarMapping.Unsupported(
                            result.diagnostics + result.error.toDiagnostic(),
                        )
                        is FontOperationResult.Cancelled -> return ScalarMapping.Cancelled(result.diagnostics)
                    }
                }
                precedingScalar = scalar.takeUnless { it in IGNORED_MAPPING_SCALARS }
            }
        }
        return ScalarMapping.Supported
    }

    private fun shapeAndValidate(group: List<AssignedUnit>, request: ResolutionRequest): Attempt {
        val first = group.first()
        val fragments = shapingFragments(group, request)
        val shaped = mutableListOf<ShapedGlyphRun>()
        fragments.forEach { fragment ->
            if (request.cancellationToken.isCancellationRequested()) {
                return Attempt.Cancelled(emptyList())
            }
            if (first.glyphless) {
                shaped += zeroWidthControlRun(request, fragment, first.instance)
                return@forEach
            }
            val fragmentRun = when (
            val result = request.shapingBackend.shape(
                ShapingRequest(
                    snapshot = request.snapshot,
                    range = fragment.range,
                    font = first.instance,
                    direction = if (fragment.bidiLevel % 2 == 0) ShapingDirection.LEFT_TO_RIGHT else ShapingDirection.RIGHT_TO_LEFT,
                    script = org.graphiks.kalligraphie.api.OpenTypeScript(fragment.script),
                    language = fragment.language,
                    bidiLevel = fragment.bidiLevel,
                    bot = fragment.range.start == request.shapingContextRange.start,
                    eot = fragment.range.endExclusive == request.shapingContextRange.endExclusive,
                    featurePolicy = request.shapingBackend.identity.featurePolicy,
                    features = request.features,
                    graphemeClusters = graphemeFragments(fragment.range, request.unicodeAnalysis.graphemeClusters),
                ),
            )
        ) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return Attempt.Rejected(result.diagnostics + result.error.toDiagnostic())
            is FontOperationResult.Cancelled -> return Attempt.Cancelled(result.diagnostics)
        }
            if (fragmentRun.glyphs.any { it.glyphId.value == 0 }) {
                return Attempt.Rejected(listOf(rejectionDiagnostic("Shaping produced the missing-glyph identifier for a complete fallback unit.")))
            }
            val materialization = request.materialization
            if (materialization is EditableLineMaterialization.Renderable) {
                when (val validation = validateOutlines(fragmentRun, first.instance, materialization, request)) {
                    Validation.Valid -> Unit
                    is Validation.Rejected -> return Attempt.Rejected(validation.diagnostics)
                    is Validation.Cancelled -> return Attempt.Cancelled(validation.diagnostics)
                }
            }
            shaped += fragmentRun
        }
        return Attempt.Success(shaped)
    }

    private fun zeroWidthControlRun(
        request: ResolutionRequest,
        fragment: ShapingFragment,
        instance: FontInstance,
    ): ShapedGlyphRun {
        val scalarRanges = request.snapshot.scalarRanges(fragment.range)
        val graphemes = graphemeFragments(fragment.range, request.unicodeAnalysis.graphemeClusters)
        val boundaries = graphemes.flatMap { grapheme -> listOf(grapheme.start, grapheme.endExclusive) }.distinct()
        return ShapedGlyphRun(
            range = fragment.range,
            fontInstanceKey = instance.key,
            backendIdentity = request.shapingBackend.identity,
            direction = if (fragment.bidiLevel % 2 == 0) ShapingDirection.LEFT_TO_RIGHT else ShapingDirection.RIGHT_TO_LEFT,
            script = OpenTypeScript(fragment.script),
            language = fragment.language,
            bidiLevel = fragment.bidiLevel,
            bot = fragment.range.start == request.shapingContextRange.start,
            eot = fragment.range.endExclusive == request.shapingContextRange.endExclusive,
            featurePolicy = request.shapingBackend.identity.featurePolicy,
            features = request.features,
            graphemeClusters = graphemes,
            glyphs = emptyList(),
            clusters = scalarRanges.mapIndexed { index, scalarRange ->
                ShaperCluster(
                    token = ShaperClusterToken(index),
                    sourceRange = scalarRange,
                    scalarRanges = listOf(scalarRange),
                    admissibleGraphemeBoundaries = boundaries.filter { boundary ->
                        boundary >= scalarRange.start && boundary <= scalarRange.endExclusive
                    },
                )
            },
        )
    }

    private fun shapingFragments(
        group: List<AssignedUnit>,
        request: ResolutionRequest,
    ): List<ShapingFragment> {
        val first = group.first()
        val last = group.last()
        val groupRange = TextRange(first.unit.range.start, last.unit.range.endExclusive)
        return scriptFragments(groupRange, request.unicodeAnalysis.scriptLanguageRuns).flatMap { script ->
            request.unicodeAnalysis.logicalBidiRuns.mapNotNull { bidi ->
                intersection(script.range, bidi.range)?.let { range ->
                    ShapingFragment(range, script.script, script.language, bidi.level)
                }
            }
        }
    }

    private fun scriptFragments(
        range: TextRange,
        scripts: List<org.graphiks.kalligraphie.api.ScriptLanguageRun>,
    ): List<ScriptFragment> {
        val intersections = scripts.mapNotNull { script ->
            intersection(range, script.range)?.let { intersection -> ScriptFragment(intersection, script.script, script.language) }
        }
        val first = intersections.first()
        var fragmentStart = range.start
        var active = intersections.firstOrNull { fragment -> fragment.script.isExplicitScript() } ?: first
        val fragments = mutableListOf<ScriptFragment>()
        intersections.forEach { fragment ->
            if (fragment.script.isExplicitScript() &&
                (fragment.script != active.script || fragment.language != active.language)
            ) {
                fragments += ScriptFragment(TextRange(fragmentStart, fragment.range.start), active.script, active.language)
                fragmentStart = fragment.range.start
                active = fragment
            }
        }
        fragments += ScriptFragment(TextRange(fragmentStart, range.endExclusive), active.script, active.language)
        return fragments
    }

    private fun graphemeFragments(range: TextRange, graphemes: List<TextRange>): List<TextRange> =
        graphemes.mapNotNull { grapheme -> intersection(range, grapheme) }

    private fun validateOutlines(
        shaped: ShapedGlyphRun,
        instance: FontInstance,
        materialization: EditableLineMaterialization.Renderable,
        request: ResolutionRequest,
    ): Validation {
        val asset = when (
            val acquired = instance.acquireRenderAsset(
                resolver = materialization.resolver,
                variant = materialization.variant,
                requirements = FontAccessRequirementsSnapshot.renderable(materialization.outlineProfile),
            )
        ) {
            is FontOperationResult.Success -> acquired.value
            is FontOperationResult.Failure -> return Validation.Rejected(acquired.diagnostics + acquired.error.toDiagnostic())
            is FontOperationResult.Cancelled -> return Validation.Cancelled(acquired.diagnostics)
        }
        var validation: Validation = Validation.Valid
        try {
            if (asset.key.fontInstanceKey != instance.key || asset.key.generation != materialization.resolver.generation) {
                validation = Validation.Rejected(listOf(rejectionDiagnostic("Acquired render asset does not identify the shaped instance and generation.")))
            } else {
                shaped.glyphs.forEach { glyph ->
                    if (validation != Validation.Valid) return@forEach
                    when (val resolved = asset.resolveGlyph(FontGlyphRequest(glyph.glyphId), request.cancellationToken)) {
                        is FontOperationResult.Success -> when (val representation = resolved.value) {
                            GlyphRepresentation.Empty -> Unit
                            is GlyphRepresentation.Outline -> if (representation.outline.glyphId != glyph.glyphId.value) {
                                validation = Validation.Rejected(listOf(rejectionDiagnostic("Resolved outline does not match the final shaped glyph identifier.")))
                            }
                        }

                        is FontOperationResult.Failure -> validation = Validation.Rejected(
                            resolved.diagnostics + resolved.error.toDiagnostic(),
                        )
                        is FontOperationResult.Cancelled -> validation = Validation.Cancelled(resolved.diagnostics)
                    }
                }
            }
        } finally {
            when (val closed = asset.close()) {
                is FontOperationResult.Failure -> if (validation == Validation.Valid) {
                    validation = Validation.Rejected(closed.diagnostics + closed.error.toDiagnostic())
                }
                is FontOperationResult.Cancelled -> if (validation == Validation.Valid) {
                    validation = Validation.Cancelled(closed.diagnostics)
                }
                is FontOperationResult.Success -> Unit
            }
        }
        return validation
    }

    private fun fallbackUnits(request: ResolutionRequest): List<FallbackUnit> {
        val graphemeUnits = request.unicodeAnalysis.graphemeClusters.filter { cluster ->
            cluster.start >= request.sourceRange.start && cluster.endExclusive <= request.sourceRange.endExclusive
        }.map { cluster ->
            val script = request.unicodeAnalysis.scriptLanguageRuns.firstOrNull { contains(it.range, cluster) }
                ?: request.unicodeAnalysis.scriptLanguageRuns.first { overlaps(it.range, cluster) }
            val bidi = request.unicodeAnalysis.logicalBidiRuns.firstOrNull { contains(it.range, cluster) }
                ?: request.unicodeAnalysis.logicalBidiRuns.first { overlaps(it.range, cluster) }
            FallbackUnit(cluster, org.graphiks.kalligraphie.api.OpenTypeScript(script.script), script.language, bidi.level)
        }
        return graphemeUnits
    }

    private fun contiguousGroups(assignments: List<AssignedUnit>): List<List<AssignedUnit>> {
        val groups = mutableListOf<MutableList<AssignedUnit>>()
        assignments.forEach { assigned ->
            val previous = groups.lastOrNull()?.lastOrNull()
            if (
                previous != null &&
                previous.record.id == assigned.record.id &&
                previous.unit.script == assigned.unit.script &&
                previous.unit.language == assigned.unit.language &&
                previous.unit.bidiLevel == assigned.unit.bidiLevel &&
                previous.glyphless == assigned.glyphless &&
                previous.unit.range.endExclusive == assigned.unit.range.start
            ) {
                groups.last() += assigned
            } else {
                groups += mutableListOf(assigned)
            }
        }
        return groups
    }

    private fun supports(capabilities: FontFaceCapabilities, requirements: FontAccessRequirementsSnapshot): Boolean =
        capabilities.characterMapping && capabilities.shaping &&
            (requirements.mode != FontAccessRequirementsSnapshot.Mode.RENDERABLE || capabilities.outline)

    private fun requirementsFor(materialization: EditableLineMaterialization): FontAccessRequirementsSnapshot = when (materialization) {
        EditableLineMaterialization.LayoutOnly -> FontAccessRequirementsSnapshot.layoutOnly()
        is EditableLineMaterialization.Renderable -> FontAccessRequirementsSnapshot.renderable(materialization.outlineProfile)
    }

    private fun unresolved(
        unit: FallbackUnit,
        diagnostics: List<FontDiagnostic>,
    ): FontOperationResult.Failure {
        val error = FontError.UnrenderableFontResolution(
            message = "No policy candidate can shape and materialize the complete fallback unit.",
            location = FontDiagnosticLocation.Source,
        )
        return FontOperationResult.Failure(error, diagnostics + error.toDiagnostic())
    }

    private fun contains(owner: TextRange, item: TextRange): Boolean =
        item.start >= owner.start && item.endExclusive <= owner.endExclusive

    private fun overlaps(left: TextRange, right: TextRange): Boolean =
        left.start < right.endExclusive && right.start < left.endExclusive

    private fun intersection(left: TextRange, right: TextRange): TextRange? {
        val start = if (left.start.compareTo(right.start) >= 0) left.start else right.start
        val endExclusive = if (left.endExclusive.compareTo(right.endExclusive) <= 0) left.endExclusive else right.endExclusive
        return if (start.compareTo(endExclusive) < 0) TextRange(start, endExclusive) else null
    }

    private fun rejectionDiagnostic(message: String): FontDiagnostic = FontDiagnostic(
        code = "font.fallback-shaping-rejected",
        severity = FontDiagnosticSeverity.WARNING,
        location = FontDiagnosticLocation.Source,
        message = message,
    )

    private fun rejectedCandidateDiagnostic(faceId: FontFaceId, reason: String): FontDiagnostic = FontDiagnostic(
        code = "font.fallback-candidate-rejected",
        severity = FontDiagnosticSeverity.WARNING,
        location = FontDiagnosticLocation.FaceId(faceId),
        message = "Candidate $faceId was rejected: $reason",
    )

    private fun rejectedLastResortDiagnostic(faceId: FontFaceId): FontDiagnostic = FontDiagnostic(
        code = "font.fallback-last-resort-rejected",
        severity = FontDiagnosticSeverity.WARNING,
        location = FontDiagnosticLocation.FaceId(faceId),
        message = "The explicitly declared last-resort face $faceId was rejected.",
    )

    private data class AssignedUnit(
        val unit: FallbackUnit,
        val record: FontFaceRecord,
        val instance: FontInstance,
        val glyphless: Boolean = false,
    )

    private data class ShapingFragment(
        val range: TextRange,
        val script: String,
        val language: String,
        val bidiLevel: Int,
    )

    private data class ScriptFragment(
        val range: TextRange,
        val script: String,
        val language: String,
    )

    private data class RejectedCandidate(
        val range: TextRange,
        val faceId: FontFaceId,
        val profile: OutlineProfile?,
    )

    private data class GroupSignature(
        val assignments: List<GroupAssignment>,
    ) {
        companion object {
            fun from(group: List<AssignedUnit>): GroupSignature = GroupSignature(
                group.map { assigned -> GroupAssignment(assigned.unit.range, assigned.record.id, assigned.instance.key) },
            )
        }
    }

    private data class GroupAssignment(
        val range: TextRange,
        val faceId: FontFaceId,
        val instanceKey: org.graphiks.kalligraphie.api.FontInstanceKey,
    )

    private sealed interface CandidateSelection {
        data class Selected(val assigned: AssignedUnit) : CandidateSelection
        data object Exhausted : CandidateSelection
        data class Cancelled(val diagnostics: List<FontDiagnostic>) : CandidateSelection
    }

    private sealed interface ScalarMapping {
        data object Supported : ScalarMapping
        data class Unsupported(val diagnostics: List<FontDiagnostic>) : ScalarMapping
        data class Cancelled(val diagnostics: List<FontDiagnostic>) : ScalarMapping
    }

    private sealed interface Attempt {
        data class Success(val runs: List<ShapedGlyphRun>) : Attempt
        data class Rejected(val diagnostics: List<FontDiagnostic>) : Attempt
        data class Cancelled(val diagnostics: List<FontDiagnostic>) : Attempt
    }

    private sealed interface Validation {
        data object Valid : Validation
        data class Rejected(val diagnostics: List<FontDiagnostic>) : Validation
        data class Cancelled(val diagnostics: List<FontDiagnostic>) : Validation
    }

    private data class ResolutionRequest(
        val snapshot: TextSnapshot,
        val sourceRange: TextRange,
        val shapingContextRange: TextRange,
        val unicodeAnalysis: UnicodeAnalysis,
        val fontCatalog: FontCatalogSnapshot,
        val resolutionPolicy: FontResolutionPolicySnapshot,
        val fontInstanceDescriptor: FontInstanceDescriptor,
        val shapingBackend: ShapingBackend,
        val materialization: EditableLineMaterialization,
        val features: List<OpenTypeFeature>,
        val cancellationToken: CancellationToken,
    ) {
        init {
            require(sourceRange.start >= snapshot.range.start && sourceRange.endExclusive <= snapshot.range.endExclusive)
            require(shapingContextRange.start >= snapshot.range.start && shapingContextRange.endExclusive <= snapshot.range.endExclusive)
            require(sourceRange.start >= shapingContextRange.start && sourceRange.endExclusive <= shapingContextRange.endExclusive)
            require(unicodeAnalysis.range.start <= sourceRange.start && unicodeAnalysis.range.endExclusive >= sourceRange.endExclusive)
        }
    }

    private val IGNORED_MAPPING_SCALARS: Set<Int> = buildSet {
        add(0x200D)
    }

    private fun Int.isVariationSelector(): Boolean = this in 0xFE00..0xFE0F || this in 0xE0100..0xE01EF

    private fun FallbackUnit.isGlyphless(snapshot: TextSnapshot): Boolean =
        snapshot.scalarValues(range).all { scalar -> scalar in MANDATORY_LINE_CONTROLS || scalar == TAB_SCALAR || scalar == OBJECT_REPLACEMENT }

    private fun String.isExplicitScript(): Boolean = this != COMMON_SCRIPT && this != INHERITED_SCRIPT

    private const val COMMON_SCRIPT: String = "Zyyy"
    private const val INHERITED_SCRIPT: String = "Zinh"

    private val MANDATORY_LINE_CONTROLS: Set<Int> = setOf(0x000A, 0x000B, 0x000C, 0x000D, 0x0085, 0x2028, 0x2029)

    private const val TAB_SCALAR: Int = 0x0009
    private const val OBJECT_REPLACEMENT: Int = 0xFFFC
}

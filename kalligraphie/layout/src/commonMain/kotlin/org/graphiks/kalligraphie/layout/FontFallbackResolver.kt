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
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.GlyphRepresentationProfile
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.GlyphMaterializationRoute
import org.graphiks.kalligraphie.api.MultiFontEditableLineRequest
import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.OpenTypeScript
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
import org.graphiks.kalligraphie.api.WritingMode
import org.graphiks.kalligraphie.api.toDiagnostic

/** Resolves one captured line input into shaped runs without leaking temporary assets. */
internal object FontFallbackResolver {
    fun resolve(request: MultiFontEditableLineRequest): FontOperationResult<FontFallbackResolution> = resolve(
        request,
        GlyphMaterializationProofs(),
    )

    fun resolve(
        request: MultiFontEditableLineRequest,
        proofs: GlyphMaterializationProofs,
    ): FontOperationResult<FontFallbackResolution> = resolve(
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
            writingMode = WritingMode.HORIZONTAL_TB,
            cancellationToken = request.cancellationToken,
        ),
        proofs,
    )

    /** Resolves one paragraph-local range without observing unrelated snapshot text. */
    fun resolveRange(
        request: ParagraphLayoutRequest,
        sourceRange: TextRange,
        shapingContextRange: TextRange,
        unicodeAnalysis: UnicodeAnalysis,
        materialization: EditableLineMaterialization,
        proofs: GlyphMaterializationProofs = GlyphMaterializationProofs(),
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
            writingMode = request.constraints.writingMode,
            cancellationToken = request.cancellationToken,
        ),
        proofs,
    )

    private fun resolve(
        request: ResolutionRequest,
        proofs: GlyphMaterializationProofs,
    ): FontOperationResult<FontFallbackResolution> {
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
                when (val attempted = shapeAndValidate(group, request, proofs)) {
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
                blacklist += RejectedCandidate(assigned.unit.range, assigned.record.id, requirements)
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
            val rejected = RejectedCandidate(unit.range, record.id, requirements)
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

    private fun shapeAndValidate(
        group: List<AssignedUnit>,
        request: ResolutionRequest,
        proofs: GlyphMaterializationProofs,
    ): Attempt {
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
                    direction = request.shapingDirection(fragment.bidiLevel),
                    script = org.graphiks.kalligraphie.api.OpenTypeScript(fragment.script),
                    language = fragment.language,
                    bidiLevel = fragment.bidiLevel,
                    bot = fragment.range.start == request.shapingContextRange.start,
                    eot = fragment.range.endExclusive == request.shapingContextRange.endExclusive,
                    featurePolicy = request.shapingBackend.identity.featurePolicy,
                    features = request.effectiveFeatures(),
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
                when (val validation = validateMaterialization(fragmentRun, first.instance, materialization, request, proofs)) {
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
            direction = request.shapingDirection(fragment.bidiLevel),
            script = OpenTypeScript(fragment.script),
            language = fragment.language,
            bidiLevel = fragment.bidiLevel,
            bot = fragment.range.start == request.shapingContextRange.start,
            eot = fragment.range.endExclusive == request.shapingContextRange.endExclusive,
            featurePolicy = request.shapingBackend.identity.featurePolicy,
            features = request.effectiveFeatures(),
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

    private fun validateMaterialization(
        shaped: ShapedGlyphRun,
        instance: FontInstance,
        materialization: EditableLineMaterialization.Renderable,
        request: ResolutionRequest,
        proofs: GlyphMaterializationProofs,
    ): Validation {
        if (proofs.find(instance, materialization, shaped.glyphs.map { it.glyphId }) != null) {
            return Validation.Valid
        }
        val asset = when (
            val acquired = instance.acquireMaterializationAsset(materialization)
        ) {
            is FontOperationResult.Success -> acquired.value
            is FontOperationResult.Failure -> return Validation.Rejected(acquired.diagnostics + acquired.error.toDiagnostic())
            is FontOperationResult.Cancelled -> return Validation.Cancelled(acquired.diagnostics)
        }
        val routes = mutableMapOf<org.graphiks.kalligraphie.api.GlyphId, GlyphMaterializationRoute>()
        var validation: Validation = Validation.Valid
        try {
            val variantSnapshot = asset.key.variantSnapshot ?: FontRenderVariantSnapshot.default
            if (
                asset.key.fontInstanceKey != instance.key ||
                asset.key.generation != materialization.resolver.generation ||
                asset.key.variant != materialization.renderVariant.key ||
                variantSnapshot != materialization.renderVariant ||
                asset.key.representationProfile !in materialization.requirements.acceptedProfiles
            ) {
                validation = Validation.Rejected(
                    listOf(rejectionDiagnostic("Acquired render asset does not identify the shaped instance, visual variant, accepted profile, and generation.")),
                )
            } else {
                shaped.glyphs.forEach { glyph ->
                    if (validation != Validation.Valid) return@forEach
                    if (routes.containsKey(glyph.glyphId)) return@forEach
                    when (val resolved = asset.resolveGlyph(FontGlyphRequest(glyph.glyphId), request.cancellationToken)) {
                        is FontOperationResult.Success -> when (val representation = resolved.value) {
                            GlyphRepresentation.Empty -> routes[glyph.glyphId] = GlyphMaterializationRoute.EMPTY
                            is GlyphRepresentation.Outline -> if (
                                asset.key.representationProfile !is org.graphiks.kalligraphie.api.OutlineProfile ||
                                representation.outline.glyphId != glyph.glyphId.value
                            ) {
                                validation = Validation.Rejected(listOf(rejectionDiagnostic("Resolved outline does not match the certified profile and final shaped glyph identifier.")))
                            } else {
                                routes[glyph.glyphId] = GlyphMaterializationRoute.OUTLINE
                            }
                            is GlyphRepresentation.Paint -> if (asset.key.representationProfile !is org.graphiks.kalligraphie.api.PaintGraphProfile) {
                                validation = Validation.Rejected(listOf(rejectionDiagnostic("Resolved paint graph does not match the certified paint profile.")))
                            } else {
                                routes[glyph.glyphId] = GlyphMaterializationRoute.PAINT_GRAPH
                            }
                            is GlyphRepresentation.Bitmap -> if (
                                asset.key.representationProfile !is org.graphiks.kalligraphie.api.BitmapProfile ||
                                representation.bitmap.glyphId != glyph.glyphId
                            ) {
                                validation = Validation.Rejected(listOf(rejectionDiagnostic("Resolved bitmap does not match the certified bitmap profile and final shaped glyph identifier.")))
                            } else {
                                routes[glyph.glyphId] = GlyphMaterializationRoute.BITMAP
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
        if (validation == Validation.Valid) proofs.record(asset.key, routes)
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
            (requirements.mode != FontAccessRequirementsSnapshot.Mode.RENDERABLE ||
                requirements.acceptedProfiles.any { profile -> capabilities.supportsRepresentation(profile) })

    private fun FontFaceCapabilities.supportsRepresentation(profile: GlyphRepresentationProfile): Boolean = when (profile) {
        is org.graphiks.kalligraphie.api.OutlineProfile -> outline
        is org.graphiks.kalligraphie.api.PaintGraphProfile -> paintGraph
        is org.graphiks.kalligraphie.api.BitmapProfile -> bitmap
        is org.graphiks.kalligraphie.api.NativeHandleProfile -> nativeHandle
    }

    private fun requirementsFor(materialization: EditableLineMaterialization): FontAccessRequirementsSnapshot = when (materialization) {
        EditableLineMaterialization.LayoutOnly -> FontAccessRequirementsSnapshot.layoutOnly()
        is EditableLineMaterialization.Renderable -> materialization.requirements
    }

    private fun FontInstance.acquireMaterializationAsset(
        materialization: EditableLineMaterialization.Renderable,
    ): FontOperationResult<org.graphiks.kalligraphie.api.FontRenderAssetHandle> =
        if (materialization.renderVariant == FontRenderVariantSnapshot.default) {
            acquireRenderAsset(
                resolver = materialization.resolver,
                variant = materialization.variant,
                requirements = materialization.requirements,
            )
        } else {
            acquireRenderAsset(
                resolver = materialization.resolver,
                renderVariant = materialization.renderVariant,
                requirements = materialization.requirements,
            )
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
        val requirements: FontAccessRequirementsSnapshot,
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
        val writingMode: WritingMode,
        val cancellationToken: CancellationToken,
    ) {
        init {
            require(sourceRange.start >= snapshot.range.start && sourceRange.endExclusive <= snapshot.range.endExclusive)
            require(shapingContextRange.start >= snapshot.range.start && shapingContextRange.endExclusive <= snapshot.range.endExclusive)
            require(sourceRange.start >= shapingContextRange.start && sourceRange.endExclusive <= shapingContextRange.endExclusive)
            require(unicodeAnalysis.range.start <= sourceRange.start && unicodeAnalysis.range.endExclusive >= sourceRange.endExclusive)
        }
    }

    private fun ResolutionRequest.shapingDirection(bidiLevel: Int): ShapingDirection = when (writingMode) {
        WritingMode.HORIZONTAL_TB -> if (bidiLevel % 2 == 0) ShapingDirection.LEFT_TO_RIGHT else ShapingDirection.RIGHT_TO_LEFT
        WritingMode.VERTICAL_RL,
        WritingMode.VERTICAL_LR,
        -> ShapingDirection.TOP_TO_BOTTOM
    }

    private fun ResolutionRequest.effectiveFeatures(): List<OpenTypeFeature> = when (writingMode) {
        WritingMode.HORIZONTAL_TB -> features
        WritingMode.VERTICAL_RL,
        WritingMode.VERTICAL_LR,
        -> features.filterNot { feature -> feature.tag == VERTICAL_ALTERNATES || feature.tag == VERTICAL_ROTATION }
            .plus(OpenTypeFeature(VERTICAL_ALTERNATES, 1))
            .plus(OpenTypeFeature(VERTICAL_ROTATION, 1))
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
    private const val VERTICAL_ALTERNATES: String = "vert"
    private const val VERTICAL_ROTATION: String = "vrt2"
}

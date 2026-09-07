package org.graphiks.kalligraphie.font.core

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogGeneration
import org.graphiks.kalligraphie.api.FontDataInterpretationVersion
import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontFaceMetadata
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontInstanceKey
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.OpenTypeFontData
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderAssetKey
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMetrics
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.PaintGraphProfile
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.GlyphRepresentationKey
import org.graphiks.kalligraphie.api.GlyphRepresentationProfileKey
import org.graphiks.kalligraphie.api.GlyphResolution
import org.graphiks.kalligraphie.api.sortedDiagnostics
import org.graphiks.kalligraphie.api.toDiagnostic
import org.graphiks.kalligraphie.font.glyph.OutlineMaterializer
import org.graphiks.kalligraphie.font.sfnt.ColrCpalReader
import org.graphiks.kalligraphie.font.sfnt.ColrCpalV0Data
import org.graphiks.kalligraphie.font.sfnt.ColrCpalV0Limits
import org.graphiks.kalligraphie.font.sfnt.ColrV0Layer
import org.graphiks.kalligraphie.font.sfnt.EbdtFormatOneData
import org.graphiks.kalligraphie.font.sfnt.EbdtFormatOneReader
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.SvgGlyphPaint
import org.graphiks.kalligraphie.font.sfnt.SvgOpenTypeData
import org.graphiks.kalligraphie.font.sfnt.SvgOpenTypeReader
import org.graphiks.kalligraphie.font.sfnt.slice

internal class TrueTypeFace(
    private val faceId: FontFaceId,
    private val generation: FontCatalogGeneration,
    private val parsedFont: ParsedTrueTypeFont,
    private val resource: PreparedFontResource,
    private val outlineRouteSupported: Boolean,
    private val paintGraphSupported: Boolean,
    private val svgRouteSupported: Boolean,
    private val bitmapRouteSupported: Boolean,
) : FontFace {
    override val metadata: FontFaceMetadata = parsedFont.metadata
    override val id: FontFaceId = faceId

    override fun instantiate(descriptor: FontInstanceDescriptor): FontOperationResult<FontInstance> {
        if (descriptor.layoutSize.value <= 0f) {
            return failure(
                FontError.InvalidInstanceDescriptor(
                    message = "Font instance layout size must be finite and positive.",
                    location = FontDiagnosticLocation.FaceId(id),
                ),
            )
        }
        if (
            descriptor.geometry.normalizedAxes.isNotEmpty() ||
            descriptor.geometry.syntheticBold ||
            descriptor.geometry.syntheticItalic
        ) {
            return failure(
                FontError.InvalidInstanceDescriptor(
                    message = "Variation axes and synthetic geometry are not supported by this TrueType face.",
                    location = FontDiagnosticLocation.FaceId(id),
                ),
            )
        }
        return FontOperationResult.Success(
            TrueTypeFontInstance(
                key = instanceKey(descriptor),
                descriptor = descriptor,
                resource = resource,
                faceId = id,
                generation = generation,
                parsedFont = parsedFont,
                outlineRouteSupported = outlineRouteSupported,
                paintGraphSupported = paintGraphSupported,
                svgRouteSupported = svgRouteSupported,
                bitmapRouteSupported = bitmapRouteSupported,
            ),
        )
    }

    private fun instanceKey(descriptor: FontInstanceDescriptor): FontInstanceKey =
        FontInstanceKey(
            face = id,
            interpretation = FontDataInterpretationVersion(
                pipelineId = "org.graphiks.kalligraphie.true-type",
                version = "1",
            ),
            layoutSize = descriptor.layoutSize,
            geometry = descriptor.geometry,
        )
}

internal data class TrueTypeFontInstance(
    override val key: FontInstanceKey,
    private val descriptor: FontInstanceDescriptor,
    private val resource: PreparedFontResource,
    private val faceId: FontFaceId,
    private val generation: FontCatalogGeneration,
    private val parsedFont: ParsedTrueTypeFont,
    private val outlineRouteSupported: Boolean,
    private val paintGraphSupported: Boolean,
    private val svgRouteSupported: Boolean,
    private val bitmapRouteSupported: Boolean,
) : FontInstance {
    override fun resolveGlyph(codePoint: Int): FontOperationResult<GlyphResolution> {
        return resource.preparedFont.resolveGlyph(codePoint)
    }

    override fun resolveGlyph(
        codePoint: Int,
        variationSelector: Int,
    ): FontOperationResult<GlyphResolution> =
        resource.preparedFont.resolveGlyph(codePoint, variationSelector)

    override fun metrics(glyphId: GlyphId): FontOperationResult<GlyphMetrics> =
        resource.preparedFont.readGlyphMetrics(glyphId, descriptor.layoutSize.value)

    override fun verticalMetrics(glyphId: GlyphId): FontOperationResult<org.graphiks.kalligraphie.api.VerticalGlyphMetrics> =
        resource.preparedFont.readVerticalGlyphMetrics(glyphId, descriptor.layoutSize.value)

    override fun copyOpenTypeData(): FontOperationResult<OpenTypeFontData> =
        FontOperationResult.Success(OpenTypeFontData(faceId, resource.preparedFont.copySourceBytes()))

    override fun acquireRenderAsset(
        resolver: FontAssetResolverHandle,
        variant: FontRenderVariantKey,
        requirements: FontAccessRequirementsSnapshot,
    ): FontOperationResult<FontRenderAssetHandle> =
        if (variant == FontRenderVariantKey.default) {
            acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, requirements)
        } else {
            failure(
                FontError.UnsupportedRepresentationProfile(
                    "A full render-variant snapshot is required for a non-default embedded asset.",
                    FontDiagnosticLocation.FaceId(faceId),
                ),
            )
        }

    override fun acquireRenderAsset(
        resolver: FontAssetResolverHandle,
        renderVariant: FontRenderVariantSnapshot,
        requirements: FontAccessRequirementsSnapshot,
    ): FontOperationResult<FontRenderAssetHandle> {
        if (requirements.mode != FontAccessRequirementsSnapshot.Mode.RENDERABLE) {
            return failure(FontError.UnsupportedRepresentationProfile("A renderable access mode is required.", FontDiagnosticLocation.FaceId(faceId)))
        }
        val profiles = requirements.acceptedProfiles.filter(::isSupportedProfile)
        if (profiles.isEmpty()) {
            return failure(
                FontError.UnsupportedRepresentationProfile(
                    "No accepted representation profile is supported by this embedded font.",
                    FontDiagnosticLocation.FaceId(faceId),
                ),
            )
        }
        if (resolver !is EmbeddedFontAssetResolver) {
            return failure(FontError.InvalidFontData("Resolver was not opened by the embedded TrueType catalog.", FontDiagnosticLocation.FaceId(faceId)))
        }
        if (resolver.generation != generation) {
            return failure(FontError.IncompatibleCatalogGeneration("Resolver generation does not match the font instance generation.", FontDiagnosticLocation.FaceId(faceId)))
        }
        val lease = resolver.acquireAssetLease(faceId)
            ?: return failure(FontError.ResourceClosed("Asset resolver is closed."))
        var leaseTransferred = false
        return try {
            var firstFailure: FontOperationResult.Failure? = null
            for (profile in profiles) {
                val outcome = when (profile) {
                is org.graphiks.kalligraphie.api.OutlineProfile -> {
                    if (renderVariant != FontRenderVariantSnapshot.default || profile.schemaVersion != 1) {
                        failure(
                            FontError.UnsupportedRepresentationProfile(
                                "Schema version 1 outline assets accept only the default render variant.",
                                FontDiagnosticLocation.FaceId(faceId),
                            ),
                        )
                    } else {
                        FontOperationResult.Success(
                            TrueTypeRenderAssetHandle(
                                faceId = faceId,
                                resourceLease = lease,
                                key = FontRenderAssetKey(key, renderVariant.key, profile, resolver.generation),
                            ),
                        )
                    }
                }

                is PaintGraphProfile -> {
                    if (profile.schemaVersion != 1) {
                        failure(FontError.UnsupportedRepresentationProfile("Only paint-graph schema version 1 is supported.", FontDiagnosticLocation.FaceId(faceId)))
                    } else if (svgRouteSupported) {
                        if (renderVariant != FontRenderVariantSnapshot.default) {
                            failure(
                                FontError.UnsupportedRepresentationProfile(
                                    "SVG-in-OpenType paint assets accept only the default render variant.",
                                    FontDiagnosticLocation.FaceId(faceId),
                                ),
                            )
                        } else {
                            when (val svgData = readSvgOpenType(profile)) {
                                is FontOperationResult.Success -> FontOperationResult.Success(
                                    SvgOpenTypeRenderAssetHandle(
                                        faceId = faceId,
                                        resourceLease = lease,
                                        key = FontRenderAssetKey(
                                            fontInstanceKey = key,
                                            variant = renderVariant.key,
                                            representationProfile = profile,
                                            generation = resolver.generation,
                                        ),
                                        profile = profile,
                                        svgData = svgData.value,
                                        glyphCount = parsedFont.metadata.glyphCount,
                                    ),
                                )

                                is FontOperationResult.Failure -> svgData
                                is FontOperationResult.Cancelled -> svgData
                            }
                        }
                    } else {
                        when (val colorData = readColrCpalV0(profile)) {
                            is FontOperationResult.Success -> {
                                val paletteIndex = renderVariant.cpalPaletteIndex ?: 0
                                if (paletteIndex !in 0 until colorData.value.paletteCount) {
                                    failure(
                                        FontError.UnsupportedRepresentationProfile(
                                            "The selected CPAL palette is unavailable in this font.",
                                            FontDiagnosticLocation.FaceId(faceId),
                                        ),
                                    )
                                } else {
                                    FontOperationResult.Success(
                                        ColrV0RenderAssetHandle(
                                            faceId = faceId,
                                            resourceLease = lease,
                                            key = FontRenderAssetKey(
                                                fontInstanceKey = key,
                                                variant = renderVariant.key,
                                                representationProfile = profile,
                                                generation = resolver.generation,
                                                variantSnapshot = renderVariant.takeUnless { it == FontRenderVariantSnapshot.default },
                                            ),
                                            profile = profile,
                                            colorData = colorData.value,
                                            paletteIndex = paletteIndex,
                                            foregroundColor = renderVariant.foregroundColor ?: GlyphColor(0, 0, 0),
                                        ),
                                    )
                                }
                            }

                            is FontOperationResult.Failure -> colorData
                            is FontOperationResult.Cancelled -> colorData
                        }
                    }
                }

                is BitmapProfile -> {
                    if (renderVariant != FontRenderVariantSnapshot.default || profile.schemaVersion != 1) {
                        failure(
                            FontError.UnsupportedRepresentationProfile(
                                "Schema version 1 EBDT bitmap assets accept only the default render variant.",
                                FontDiagnosticLocation.FaceId(faceId),
                            ),
                        )
                    } else {
                        when (val bitmapData = readEbdtFormatOne(profile)) {
                            is FontOperationResult.Success -> FontOperationResult.Success(
                                EbdtFormatOneRenderAssetHandle(
                                    faceId = faceId,
                                    resourceLease = lease,
                                    key = FontRenderAssetKey(
                                        fontInstanceKey = key,
                                        variant = renderVariant.key,
                                        representationProfile = profile,
                                        generation = resolver.generation,
                                    ),
                                    bitmapData = bitmapData.value,
                                ),
                            )

                            is FontOperationResult.Failure -> bitmapData
                            is FontOperationResult.Cancelled -> bitmapData
                        }
                    }
                }

                    else -> failure(
                        FontError.UnsupportedRepresentationProfile(
                            "The embedded TrueType provider supports only outline, COLR version 0 or SVG-in-OpenType paint, and EBDT format 1 bitmap profiles.",
                            FontDiagnosticLocation.FaceId(faceId),
                        ),
                    )
                }
                when (outcome) {
                    is FontOperationResult.Success -> {
                        leaseTransferred = true
                        return outcome
                    }

                    is FontOperationResult.Failure -> if (firstFailure == null) firstFailure = outcome
                    is FontOperationResult.Cancelled -> return outcome
                }
            }
            firstFailure ?: failure(
                FontError.UnsupportedRepresentationProfile(
                    "No accepted representation profile can be certified by this embedded font.",
                    FontDiagnosticLocation.FaceId(faceId),
                ),
            )
        } finally {
            if (!leaseTransferred) lease.release()
        }
    }

    private fun isSupportedProfile(profile: org.graphiks.kalligraphie.api.GlyphRepresentationProfile): Boolean =
        when (profile) {
            is org.graphiks.kalligraphie.api.OutlineProfile ->
                profile.schemaVersion == 1 && outlineRouteSupported
            is PaintGraphProfile -> profile.schemaVersion == 1 && (paintGraphSupported || svgRouteSupported)
            is BitmapProfile ->
                profile.schemaVersion == 1 && bitmapRouteSupported
            else -> false
        }

    private fun readColrCpalV0(profile: PaintGraphProfile): FontOperationResult<ColrCpalV0Data> {
        val colrRecord = parsedFont.tableRecords["COLR"]
            ?: return failure(FontError.UnsupportedRepresentationProfile("The font has no COLR table.", FontDiagnosticLocation.FaceId(faceId)))
        val cpalRecord = parsedFont.tableRecords["CPAL"]
            ?: return failure(FontError.UnsupportedRepresentationProfile("The font has no CPAL table.", FontDiagnosticLocation.FaceId(faceId)))
        val totalBytes = colrRecord.length + cpalRecord.length
        if (totalBytes < 0L || totalBytes > profile.limits.maxSourceBytes.toLong()) {
            return failure(FontError.ResourceLimitExceeded("COLR and CPAL source-byte limit exceeded.", FontDiagnosticLocation.FaceId(faceId)))
        }
        val sourceBytes = resource.preparedFont.copySourceBytes()
        val colr = slice(sourceBytes, colrRecord)
            ?: return failure(FontError.InvalidFontData("COLR table exceeds embedded source bytes.", FontDiagnosticLocation.Table("COLR")))
        val cpal = slice(sourceBytes, cpalRecord)
            ?: return failure(FontError.InvalidFontData("CPAL table exceeds embedded source bytes.", FontDiagnosticLocation.Table("CPAL")))
        return ColrCpalReader.read(
            colrTable = colr,
            cpalTable = cpal,
            limits = ColrCpalV0Limits(
                maxPalettes = profile.limits.maxPalettes,
                maxPaletteEntries = profile.limits.maxPaletteEntries,
                maxColorRecords = profile.limits.maxColorRecords,
                maxDecodedPaletteBytes = profile.limits.maxDecodedPaletteBytes,
                maxBaseGlyphRecords = profile.limits.maxBaseGlyphRecords,
                maxLayerRecords = profile.limits.maxLayerRecords,
            ),
            glyphCount = parsedFont.metadata.glyphCount,
        )
    }

    private fun readEbdtFormatOne(profile: BitmapProfile): FontOperationResult<EbdtFormatOneData> {
        val eblcRecord = parsedFont.tableRecords["EBLC"]
            ?: return failure(FontError.UnsupportedRepresentationProfile("The font has no EBLC table.", FontDiagnosticLocation.FaceId(faceId)))
        val ebdtRecord = parsedFont.tableRecords["EBDT"]
            ?: return failure(FontError.UnsupportedRepresentationProfile("The font has no EBDT table.", FontDiagnosticLocation.FaceId(faceId)))
        val sourceBytes = resource.preparedFont.copySourceBytes()
        val eblc = slice(sourceBytes, eblcRecord)
            ?: return failure(FontError.InvalidFontData("EBLC table exceeds embedded source bytes.", FontDiagnosticLocation.Table("EBLC")))
        val ebdt = slice(sourceBytes, ebdtRecord)
            ?: return failure(FontError.InvalidFontData("EBDT table exceeds embedded source bytes.", FontDiagnosticLocation.Table("EBDT")))
        return EbdtFormatOneReader.read(eblc, ebdt, parsedFont.metadata.glyphCount, profile)
    }

    private fun readSvgOpenType(profile: PaintGraphProfile): FontOperationResult<SvgOpenTypeData> {
        val svgRecord = parsedFont.tableRecords["SVG "]
            ?: return failure(FontError.UnsupportedRepresentationProfile("The font has no SVG table.", FontDiagnosticLocation.FaceId(faceId)))
        if (svgRecord.length > profile.limits.maxSourceBytes.toLong()) {
            return failure(FontError.ResourceLimitExceeded("SVG source-byte limit exceeded.", FontDiagnosticLocation.Table("SVG ")))
        }
        val svg = slice(resource.preparedFont.copySourceBytes(), svgRecord)
            ?: return failure(FontError.InvalidFontData("SVG table exceeds embedded source bytes.", FontDiagnosticLocation.Table("SVG ")))
        return SvgOpenTypeReader.read(svg, parsedFont.metadata.glyphCount, profile)
    }

}

internal class TrueTypeRenderAssetHandle(
    override val faceId: FontFaceId,
    private var resourceLease: PreparedFontResourceLease?,
    override val key: FontRenderAssetKey,
) : FontRenderAssetHandle {
    private val profile: org.graphiks.kalligraphie.api.OutlineProfile = requireNotNull(key.outlineProfile) {
        "TrueTypeRenderAssetHandle requires an outline asset key."
    }
    private val lifecycle = FontHandleLifecycle(::releaseResourceLease)

    override fun detach(): FontOperationResult<FontRenderAssetHandle> {
        val lease = lifecycle.acquireLease()
            ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            val detachedResourceLease = resourceLease?.resource?.acquireLease()
                ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            FontOperationResult.Success(
                TrueTypeRenderAssetHandle(
                    faceId = faceId,
                    resourceLease = detachedResourceLease,
                    key = key.copy(representationProfile = profile.copy()),
                ),
            )
        } finally {
            lease.release()
        }
    }

    override fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation> =
        resolveGlyph(request, CancellationToken.none)

    override fun resolveGlyph(
        request: FontGlyphRequest,
        cancellationToken: CancellationToken,
    ): FontOperationResult<GlyphRepresentation> {
        val lease = lifecycle.acquireLease()
            ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            if (cancellationToken.isCancellationRequested()) {
                return FontOperationResult.Cancelled()
            }
            val preparedFont = resourceLease?.preparedFont
                ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            val resource = resourceLease?.resource
                ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            val glyphId = GlyphId(request.glyphId)
            val representationKey = GlyphRepresentationKey(
                assetKey = key,
                glyphId = glyphId,
                variant = key.variant,
                profile = GlyphRepresentationProfileKey.outline(profile),
            )
            resource.cachedOutline(representationKey)?.let { cached -> return cached }
            val outline = when (val result = preparedFont.readGlyphOutline(glyphId, profile, cancellationToken)) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            if (cancellationToken.isCancellationRequested()) {
                return FontOperationResult.Cancelled()
            }
            when (val materialized = OutlineMaterializer.materialize(outline, profile, cancellationToken)) {
                is FontOperationResult.Success -> {
                    if (cancellationToken.isCancellationRequested()) FontOperationResult.Cancelled()
                    else materialized.also { success -> resource.cacheOutline(representationKey, success) }
                }

                is FontOperationResult.Failure -> materialized
                is FontOperationResult.Cancelled -> materialized
            }
        } finally {
            lease.release()
        }
    }

    override fun close(): FontOperationResult<Unit> {
        lifecycle.close()
        return FontOperationResult.Success(Unit)
    }

    private fun releaseResourceLease() {
        resourceLease?.release()
        resourceLease = null
    }
}

/** Asset handle for the normalized, profile-certified SVG-in-OpenType paint route. */
internal class SvgOpenTypeRenderAssetHandle(
    override val faceId: FontFaceId,
    private var resourceLease: PreparedFontResourceLease?,
    override val key: FontRenderAssetKey,
    private val profile: PaintGraphProfile,
    private val svgData: SvgOpenTypeData,
    private val glyphCount: Int,
) : FontRenderAssetHandle {
    private val lifecycle = FontHandleLifecycle(::releaseResourceLease)

    override fun detach(): FontOperationResult<FontRenderAssetHandle> {
        val lease = lifecycle.acquireLease()
            ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            val detachedResourceLease = resourceLease?.resource?.acquireLease()
                ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            FontOperationResult.Success(
                SvgOpenTypeRenderAssetHandle(
                    faceId = faceId,
                    resourceLease = detachedResourceLease,
                    key = key.copy(representationProfile = profile),
                    profile = profile,
                    svgData = svgData,
                    glyphCount = glyphCount,
                ),
            )
        } finally {
            lease.release()
        }
    }

    override fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation> =
        resolveGlyph(request, CancellationToken.none)

    override fun resolveGlyph(
        request: FontGlyphRequest,
        cancellationToken: CancellationToken,
    ): FontOperationResult<GlyphRepresentation> {
        val lease = lifecycle.acquireLease()
            ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            if (resourceLease == null) return failure(FontError.ResourceClosed("Render asset is closed."))
            if (request.glyphId !in 0 until glyphCount) return failure(FontError.GlyphOutOfRange(request.glyphId))
            val representation = when (val paint = svgData.glyphPaint(GlyphId(request.glyphId))) {
                null,
                SvgGlyphPaint.Empty,
                -> GlyphRepresentation.Empty

                is SvgGlyphPaint.Paint -> GlyphRepresentation.Paint(paint.paint)
            }
            if (cancellationToken.isCancellationRequested()) FontOperationResult.Cancelled()
            else FontOperationResult.Success(representation)
        } finally {
            lease.release()
        }
    }

    override fun close(): FontOperationResult<Unit> {
        lifecycle.close()
        return FontOperationResult.Success(Unit)
    }

    private fun releaseResourceLease() {
        resourceLease?.release()
        resourceLease = null
    }
}

/** Asset handle for the explicitly supported COLR version 0 and CPAL version 0 paint route. */
internal class ColrV0RenderAssetHandle(
    override val faceId: FontFaceId,
    private var resourceLease: PreparedFontResourceLease?,
    override val key: FontRenderAssetKey,
    private val profile: PaintGraphProfile,
    private val colorData: ColrCpalV0Data,
    private val paletteIndex: Int,
    private val foregroundColor: GlyphColor,
) : FontRenderAssetHandle {
    private val palette: List<GlyphColor> = colorData.palette(paletteIndex)
    private val lifecycle = FontHandleLifecycle(::releaseResourceLease)

    override fun detach(): FontOperationResult<FontRenderAssetHandle> {
        val lease = lifecycle.acquireLease()
            ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            val detachedResourceLease = resourceLease?.resource?.acquireLease()
                ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            FontOperationResult.Success(
                ColrV0RenderAssetHandle(
                    faceId = faceId,
                    resourceLease = detachedResourceLease,
                    key = key.copy(representationProfile = profile),
                    profile = profile,
                    colorData = colorData,
                    paletteIndex = paletteIndex,
                    foregroundColor = foregroundColor,
                ),
            )
        } finally {
            lease.release()
        }
    }

    override fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation> =
        resolveGlyph(request, CancellationToken.none)

    override fun resolveGlyph(
        request: FontGlyphRequest,
        cancellationToken: CancellationToken,
    ): FontOperationResult<GlyphRepresentation> {
        val lease = lifecycle.acquireLease()
            ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            val preparedFont = resourceLease?.preparedFont
                ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            val layers = colorData.layersFor(GlyphId(request.glyphId))
            val materializedGlyphIds = if (layers.isEmpty()) {
                listOf(ColrV0Layer(GlyphId(request.glyphId), ColrV0Layer.foregroundColorIndex))
            } else {
                layers
            }
            val nodes = ArrayList<GlyphPaintNode>(materializedGlyphIds.size + 1)
            for (layer in materializedGlyphIds) {
                if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
                val outline = when (val result = preparedFont.readGlyphOutline(layer.glyphId, profile.outlineProfile, cancellationToken)) {
                    is FontOperationResult.Success -> result.value
                    is FontOperationResult.Failure -> return result
                    is FontOperationResult.Cancelled -> return result
                }
                val representation = when (val result = OutlineMaterializer.materialize(outline, profile.outlineProfile, cancellationToken)) {
                    is FontOperationResult.Success -> result.value
                    is FontOperationResult.Failure -> return result
                    is FontOperationResult.Cancelled -> return result
                }
                val outlineIr = (representation as? GlyphRepresentation.Outline)?.outline ?: continue
                val color = if (layer.paletteIndex == ColrV0Layer.foregroundColorIndex) foregroundColor else palette[layer.paletteIndex]
                nodes += GlyphPaintNode.SolidOutline(outlineIr, color)
            }
            if (nodes.isEmpty()) return FontOperationResult.Success(GlyphRepresentation.Empty)
            val root = if (nodes.size == 1) {
                0
            } else {
                nodes += GlyphPaintNode.Group((nodes.indices).toList())
                nodes.lastIndex
            }
            val paint = GlyphPaintIR(schemaVersion = profile.schemaVersion, rootNode = root, nodes = nodes)
            if (!profile.accepts(paint)) {
                return failure(
                    FontError.ResourceLimitExceeded(
                        "COLR version 0 paint graph exceeds the selected profile.",
                        FontDiagnosticLocation.Glyph(request.glyphId),
                    ),
                )
            }
            FontOperationResult.Success(GlyphRepresentation.Paint(paint))
        } finally {
            lease.release()
        }
    }

    override fun close(): FontOperationResult<Unit> {
        lifecycle.close()
        return FontOperationResult.Success(Unit)
    }

    private fun releaseResourceLease() {
        resourceLease?.release()
        resourceLease = null
    }
}

/** Asset handle for the explicitly supported EBLC index-format 1 / EBDT image-format 1 route. */
internal class EbdtFormatOneRenderAssetHandle(
    override val faceId: FontFaceId,
    private var resourceLease: PreparedFontResourceLease?,
    override val key: FontRenderAssetKey,
    private val bitmapData: EbdtFormatOneData,
) : FontRenderAssetHandle {
    private val lifecycle = FontHandleLifecycle(::releaseResourceLease)

    override fun detach(): FontOperationResult<FontRenderAssetHandle> {
        val lease = lifecycle.acquireLease()
            ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            val detachedResourceLease = resourceLease?.resource?.acquireLease()
                ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            FontOperationResult.Success(
                EbdtFormatOneRenderAssetHandle(
                    faceId = faceId,
                    resourceLease = detachedResourceLease,
                    key = key,
                    bitmapData = bitmapData,
                ),
            )
        } finally {
            lease.release()
        }
    }

    override fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation> =
        resolveGlyph(request, CancellationToken.none)

    override fun resolveGlyph(
        request: FontGlyphRequest,
        cancellationToken: CancellationToken,
    ): FontOperationResult<GlyphRepresentation> {
        val lease = lifecycle.acquireLease()
            ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            when (val decoded = bitmapData.decode(GlyphId(request.glyphId), cancellationToken)) {
                is FontOperationResult.Success -> FontOperationResult.Success(
                    decoded.value?.let(GlyphRepresentation::Bitmap) ?: GlyphRepresentation.Empty,
                    decoded.diagnostics,
                )

                is FontOperationResult.Failure -> decoded
                is FontOperationResult.Cancelled -> decoded
            }
        } finally {
            lease.release()
        }
    }

    override fun close(): FontOperationResult<Unit> {
        lifecycle.close()
        return FontOperationResult.Success(Unit)
    }

    private fun releaseResourceLease() {
        resourceLease?.release()
        resourceLease = null
    }
}

internal fun failure(error: FontError, diagnostics: List<FontDiagnostic> = listOf(error.toDiagnostic())): FontOperationResult.Failure =
    FontOperationResult.Failure(error, diagnostics.sortedDiagnostics())

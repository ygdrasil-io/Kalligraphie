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
import org.graphiks.kalligraphie.font.sfnt.SvgOpenTypeReader
import org.graphiks.kalligraphie.font.sfnt.slice

internal class TrueTypeFace(
    private val faceId: FontFaceId,
    private val generation: FontCatalogGeneration,
    private val parsedFont: ParsedTrueTypeFont,
    private val resource: PreparedFontResource,
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

private data class TrueTypeFontInstance(
    override val key: FontInstanceKey,
    private val descriptor: FontInstanceDescriptor,
    private val resource: PreparedFontResource,
    private val faceId: FontFaceId,
    private val generation: FontCatalogGeneration,
    private val parsedFont: ParsedTrueTypeFont,
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
        val profile = requirements.acceptedProfiles.firstOrNull(::canMaterialize)
            ?: return failure(
                FontError.UnsupportedRepresentationProfile(
                    "None of the requested representation profiles can be materialized by this font.",
                    FontDiagnosticLocation.FaceId(faceId),
                ),
            )
        if (resolver !is EmbeddedFontAssetResolver) {
            return failure(FontError.InvalidFontData("Resolver was not opened by the embedded TrueType catalog.", FontDiagnosticLocation.FaceId(faceId)))
        }
        if (resolver.generation != generation) {
            return failure(FontError.IncompatibleCatalogGeneration("Resolver generation does not match the font instance generation.", FontDiagnosticLocation.FaceId(faceId)))
        }
        val lease = resolver.acquireAssetLease(faceId)
            ?: return failure(FontError.ResourceClosed("Asset resolver is closed."))
        return try {
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
                    } else if (parsedFont.tableRecords.containsKey("COLR") && parsedFont.tableRecords.containsKey("CPAL")) {
                        acquireColrV0Asset(lease, resolver, renderVariant, profile)
                    } else if (parsedFont.tableRecords.containsKey("SVG ")) {
                        acquireSvgAsset(lease, resolver, renderVariant, profile)
                    } else {
                        failure(FontError.UnsupportedRepresentationProfile("The font has no supported paint table.", FontDiagnosticLocation.FaceId(faceId)))
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
                        "The embedded TrueType provider supports only outline, COLR version 0 paint, SVG version 0 paint, and EBDT format 1 bitmap profiles.",
                        FontDiagnosticLocation.FaceId(faceId),
                    ),
                )
            }
            if (outcome !is FontOperationResult.Success) lease.release()
            outcome
        } catch (throwable: Throwable) {
            lease.release()
            throw throwable
        }
    }

    private fun acquireColrV0Asset(
        lease: PreparedFontResourceLease,
        resolver: EmbeddedFontAssetResolver,
        renderVariant: FontRenderVariantSnapshot,
        profile: PaintGraphProfile,
    ): FontOperationResult<FontRenderAssetHandle> = when (val colorData = readColrCpalV0(profile)) {
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

    private fun acquireSvgAsset(
        lease: PreparedFontResourceLease,
        resolver: EmbeddedFontAssetResolver,
        renderVariant: FontRenderVariantSnapshot,
        profile: PaintGraphProfile,
    ): FontOperationResult<FontRenderAssetHandle> {
        if (renderVariant != FontRenderVariantSnapshot.default) {
            return failure(
                FontError.UnsupportedRepresentationProfile(
                    "Schema version 1 SVG paint assets accept only the default render variant.",
                    FontDiagnosticLocation.FaceId(faceId),
                ),
            )
        }
        val svgRecord = parsedFont.tableRecords["SVG "]
            ?: return failure(FontError.UnsupportedRepresentationProfile("The font has no SVG table.", FontDiagnosticLocation.FaceId(faceId)))
        if (svgRecord.length > profile.limits.maxSourceBytes.toLong()) {
            return failure(FontError.ResourceLimitExceeded("SVG source-byte limit exceeded.", FontDiagnosticLocation.Table("SVG ")))
        }
        val svg = slice(resource.preparedFont.copySourceBytes(), svgRecord)
            ?: return failure(FontError.InvalidFontData("SVG table exceeds embedded source bytes.", FontDiagnosticLocation.Table("SVG ")))
        return FontOperationResult.Success(
            SvgOpenTypeRenderAssetHandle(
                faceId = faceId,
                resourceLease = lease,
                key = FontRenderAssetKey(key, renderVariant.key, profile, resolver.generation),
                profile = profile,
                svgTable = svg,
            ),
        )
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

    private fun canMaterialize(profile: org.graphiks.kalligraphie.api.GlyphRepresentationProfile): Boolean = when (profile) {
        is org.graphiks.kalligraphie.api.OutlineProfile -> profile.schemaVersion == 1 &&
            parsedFont.tableRecords.containsKey("glyf") && parsedFont.tableRecords.containsKey("loca")
        is PaintGraphProfile -> profile.schemaVersion == 1 &&
            ((parsedFont.tableRecords.containsKey("COLR") && parsedFont.tableRecords.containsKey("CPAL")) ||
                parsedFont.tableRecords.containsKey("SVG "))
        is BitmapProfile -> profile.schemaVersion == 1 &&
            parsedFont.tableRecords.containsKey("EBLC") && parsedFont.tableRecords.containsKey("EBDT")
        else -> false
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
            val outline = when (val result = preparedFont.readGlyphOutline(GlyphId(request.glyphId), profile, cancellationToken)) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            if (cancellationToken.isCancellationRequested()) {
                return FontOperationResult.Cancelled()
            }
            OutlineMaterializer.materialize(outline, profile, cancellationToken)
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

/** Asset handle for the explicitly supported OpenType SVG version-0 route. */
internal class SvgOpenTypeRenderAssetHandle(
    override val faceId: FontFaceId,
    private var resourceLease: PreparedFontResourceLease?,
    override val key: FontRenderAssetKey,
    private val profile: PaintGraphProfile,
    svgTable: ByteArray,
) : FontRenderAssetHandle {
    private val svgTable: ByteArray = svgTable.copyOf()
    private val lifecycle = FontHandleLifecycle(::releaseResourceLease)

    override fun detach(): FontOperationResult<FontRenderAssetHandle> {
        val lease = lifecycle.acquireLease()
            ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            val detachedResourceLease = resourceLease?.resource?.acquireLease()
                ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            FontOperationResult.Success(
                SvgOpenTypeRenderAssetHandle(faceId, detachedResourceLease, key, profile, svgTable),
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
            when (val result = SvgOpenTypeReader.readGlyph(svgTable, GlyphId(request.glyphId), profile, cancellationToken)) {
                is FontOperationResult.Success -> FontOperationResult.Success(
                    result.value?.let(GlyphRepresentation::Paint) ?: GlyphRepresentation.Empty,
                    result.diagnostics,
                )

                is FontOperationResult.Failure -> result
                is FontOperationResult.Cancelled -> result
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

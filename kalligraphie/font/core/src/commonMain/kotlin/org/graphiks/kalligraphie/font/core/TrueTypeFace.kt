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
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMetrics
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.GlyphResolution
import org.graphiks.kalligraphie.api.sortedDiagnostics
import org.graphiks.kalligraphie.api.toDiagnostic
import org.graphiks.kalligraphie.font.glyph.OutlineMaterializer
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont

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
    ): FontOperationResult<FontRenderAssetHandle> {
        val outlineProfile = requirements.outlineProfile
        if (requirements.mode != FontAccessRequirementsSnapshot.Mode.RENDERABLE || outlineProfile == null || outlineProfile.schemaVersion != 1) {
            return failure(
                FontError.UnsupportedRepresentationProfile(
                    "A schemaVersion=1 renderable outline profile is required.",
                    FontDiagnosticLocation.FaceId(faceId),
                ),
            )
        }
        if (variant != FontRenderVariantKey.default) {
            return failure(
                FontError.UnsupportedRepresentationProfile(
                    "Variant render assets are not supported by this embedded TrueType face.",
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
        return try {
            val handle = TrueTypeRenderAssetHandle(
                faceId = faceId,
                resourceLease = lease,
                key = FontRenderAssetKey(
                    fontInstanceKey = key,
                    variant = variant,
                    outlineProfile = outlineProfile,
                    generation = resolver.generation,
                ),
            )
            FontOperationResult.Success(handle)
        } catch (throwable: Throwable) {
            lease.release()
            throw throwable
        }
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

internal fun failure(error: FontError, diagnostics: List<FontDiagnostic> = listOf(error.toDiagnostic())): FontOperationResult.Failure =
    FontOperationResult.Failure(error, diagnostics.sortedDiagnostics())

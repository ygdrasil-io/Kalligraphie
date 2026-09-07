package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class GlyphRepresentationContractsTest {
    @Test
    fun paintProfilesRejectUndeclaredCompositionBeforeCertification() {
        val outline = GlyphOutlineIR(
            glyphId = 0,
            unitsPerEm = 1_000,
            bounds = DesignBounds.empty,
            commands = emptyList(),
        )
        val paint = GlyphPaintIR(
            schemaVersion = 1,
            rootNode = 1,
            nodes = listOf(
                GlyphPaintNode.SolidOutline(outline, GlyphColor(0, 0, 0)),
                GlyphPaintNode.Group(children = listOf(0)),
            ),
        )
        val profile = PaintGraphProfile(
            acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE, GlyphPaintNodeKind.GROUP),
            acceptedCompositionModes = emptyList(),
            limits = PaintGraphLimits(maxNodes = 4, maxReferences = 4, maxDepth = 4),
            outlineProfile = outlineProfile(),
        )

        assertEquals(false, profile.accepts(paint))
    }

    @Test
    fun renderVariantsKeepPaletteAndForegroundSelectionInTheirIdentity() {
        val paletteZero = FontRenderVariantSnapshot(
            cpalPaletteIndex = 0,
            foregroundColor = GlyphColor(1, 2, 3),
        )
        val paletteOne = FontRenderVariantSnapshot(
            cpalPaletteIndex = 1,
            foregroundColor = GlyphColor(1, 2, 3),
        )

        assertNotEquals(paletteZero.key, paletteOne.key)
    }

    @Test
    fun portableProfileKeysKeepEveryPaintAndBitmapResourceBoundInTheirIdentity() {
        val outline = outlineProfile()
        val paint = PaintGraphProfile(
            acceptedNodeKinds = listOf(GlyphPaintNodeKind.PATH),
            acceptedCompositionModes = emptyList(),
            limits = PaintGraphLimits(maxNodes = 2, maxReferences = 0, maxDepth = 1, maxSvgPathCommands = 2),
            outlineProfile = outline,
        )
        val stricterPaint = PaintGraphProfile(
            acceptedNodeKinds = listOf(GlyphPaintNodeKind.PATH),
            acceptedCompositionModes = emptyList(),
            limits = PaintGraphLimits(maxNodes = 2, maxReferences = 0, maxDepth = 1, maxSvgPathCommands = 1),
            outlineProfile = outline,
        )
        val bitmap = BitmapProfile(
            strike = BitmapStrike(16, 16),
            acceptedPixelFormats = listOf(BitmapPixelFormat.ALPHA_8),
            acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
            limits = BitmapLimits(1, 16, 16, 256, 128, 256),
        )
        val stricterBitmap = BitmapProfile(
            strike = BitmapStrike(16, 16),
            acceptedPixelFormats = listOf(BitmapPixelFormat.ALPHA_8),
            acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
            limits = BitmapLimits(1, 16, 16, 256, 128, 256, maxTotalCompressedBytes = 64),
        )

        assertNotEquals(
            GlyphRepresentationProfileKey.paintGraph(paint),
            GlyphRepresentationProfileKey.paintGraph(stricterPaint),
        )
        assertNotEquals(
            GlyphRepresentationProfileKey.bitmap(bitmap),
            GlyphRepresentationProfileKey.bitmap(stricterBitmap),
        )
    }

    @Test
    fun paintGraphsRejectReferenceCyclesBeforePublication() {
        assertFailsWith<IllegalArgumentException> {
            GlyphPaintIR(
                schemaVersion = 1,
                rootNode = 0,
                nodes = listOf(
                    GlyphPaintNode.Group(children = listOf(0)),
                ),
            )
        }
    }

    @Test
    fun paintGraphsRejectCyclesOutsideThePublishedRoot() {
        val outline = GlyphOutlineIR(
            glyphId = 0,
            unitsPerEm = 1_000,
            bounds = DesignBounds.empty,
            commands = emptyList(),
        )

        assertFailsWith<IllegalArgumentException> {
            GlyphPaintIR(
                schemaVersion = 1,
                rootNode = 0,
                nodes = listOf(
                    GlyphPaintNode.SolidOutline(outline, GlyphColor(0, 0, 0)),
                    GlyphPaintNode.Group(children = listOf(2)),
                    GlyphPaintNode.Group(children = listOf(1)),
                ),
            )
        }
    }

    @Test
    fun representationKeysKeepPaletteVariantsInSeparateCacheDomains() {
        val profile = outlineProfile()
        val assetKey = FontRenderAssetKey(
            fontInstanceKey = instanceKey(),
            variant = FontRenderVariantKey.default,
            outlineProfile = profile,
            generation = FontCatalogGeneration(FontProviderId("embedded"), "generation-1"),
        )

        val defaultKey = GlyphRepresentationKey(
            assetKey = assetKey,
            glyphId = GlyphId(12),
            variant = FontRenderVariantKey.default,
            profile = GlyphRepresentationProfileKey.outline(profile),
        )
        val paletteKey = GlyphRepresentationKey(
            assetKey = assetKey.copy(variant = FontRenderVariantKey("cpal:1")),
            glyphId = GlyphId(12),
            variant = FontRenderVariantKey("cpal:1"),
            profile = GlyphRepresentationProfileKey.outline(profile),
        )

        assertNotEquals(defaultKey, paletteKey)
    }

    @Test
    fun renderAssetIdentityCannotCrossRepresentationProfiles() {
        val outline = outlineProfile()
        val paint = PaintGraphProfile(
            acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE),
            acceptedCompositionModes = emptyList(),
            limits = PaintGraphLimits(maxNodes = 4, maxReferences = 0, maxDepth = 1),
            outlineProfile = outline,
        )
        val generation = FontCatalogGeneration(FontProviderId("embedded"), "generation-1")
        val outlineKey = FontRenderAssetKey(instanceKey(), FontRenderVariantKey.default, outline, generation)
        val paintKey = FontRenderAssetKey(
            fontInstanceKey = instanceKey(),
            variant = FontRenderVariantKey.default,
            representationProfile = paint,
            generation = generation,
        )

        assertNotEquals(outlineKey, paintKey)
    }

    @Test
    fun bitmapRepresentationOwnsItsDecodedPixels() {
        val decodedPixels = byteArrayOf(0, 127, -1, 64)
        val bitmap = BitmapGlyphIR(
            glyphId = GlyphId(3),
            strike = BitmapStrike(16, 16),
            width = 2,
            height = 2,
            originX = 0,
            originY = 2,
            metrics = BitmapGlyphMetrics(advanceX = 3, advanceY = 0),
            pixelFormat = BitmapPixelFormat.ALPHA_8,
            colorSpace = GlyphColorSpace.SRGB,
            decodedPixels = decodedPixels,
        )
        decodedPixels[0] = 42

        assertContentEquals(byteArrayOf(0, 127, -1, 64), bitmap.copyDecodedPixels())
    }

    @Test
    fun certificateCannotBeReusedForAnotherGlyph() {
        val profile = outlineProfile()
        val assetKey = FontRenderAssetKey(
            fontInstanceKey = instanceKey(),
            variant = FontRenderVariantKey.default,
            outlineProfile = profile,
            generation = FontCatalogGeneration(FontProviderId("embedded"), "generation-1"),
        )
        val certificate = GlyphMaterializationCertificate(
            assetKey = assetKey,
            glyphId = GlyphId(12),
            route = GlyphMaterializationRoute.OUTLINE,
        )

        assertFalse(certificate.matches(assetKey, GlyphId(13)))
    }

    @Test
    fun paintCertificateCannotBeReusedWithAnOutlineProfile() {
        val generation = FontCatalogGeneration(FontProviderId("embedded"), "generation-1")
        val paintProfile = PaintGraphProfile(
            acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE),
            acceptedCompositionModes = emptyList(),
            limits = PaintGraphLimits(maxNodes = 1, maxReferences = 0, maxDepth = 1),
            outlineProfile = outlineProfile(),
        )
        val paintAsset = FontRenderAssetKey(instanceKey(), FontRenderVariantKey.default, paintProfile, generation)
        val outlineAsset = FontRenderAssetKey(instanceKey(), FontRenderVariantKey.default, outlineProfile(), generation)
        val certificate = GlyphMaterializationCertificate(
            assetKey = paintAsset,
            glyphId = GlyphId(12),
            route = GlyphMaterializationRoute.PAINT_GRAPH,
        )

        assertFalse(certificate.matches(outlineAsset, GlyphId(12)))
    }

    @Test
    fun generationsWithTheSameTokenRemainDistinctAcrossProviderDomains() {
        val left = FontCatalogGeneration(FontProviderId("provider-a"), "generation-7")
        val right = FontCatalogGeneration(FontProviderId("provider-b"), "generation-7")

        assertNotEquals(left, right)
    }

    @Test
    fun renderableRequirementsRejectNativeOnlyProfilesWhenPortableDataIsRequired() {
        assertFailsWith<IllegalArgumentException> {
            FontAccessRequirementsSnapshot.renderable(
                acceptedProfiles = listOf(
                    NativeHandleProfile(
                        bridgeKind = "platform-font",
                        bridgeVersion = "1",
                    ),
                ),
                portableDataRequired = true,
            )
        }
    }

    @Test
    fun certificationRejectsAProviderPaintGraphThatExceedsTheSelectedProfile() {
        val profile = PaintGraphProfile(
            acceptedNodeKinds = listOf(GlyphPaintNodeKind.PATH),
            acceptedCompositionModes = emptyList(),
            limits = PaintGraphLimits(
                maxNodes = 1,
                maxReferences = 0,
                maxDepth = 1,
                maxPaths = 1,
                maxSvgPathCommands = 1,
            ),
            outlineProfile = outlineProfile(),
        )
        val asset = object : FontRenderAssetHandle {
            override val key: FontRenderAssetKey = FontRenderAssetKey(
                fontInstanceKey = instanceKey(),
                variant = FontRenderVariantKey.default,
                representationProfile = profile,
                generation = FontCatalogGeneration(FontProviderId("contract-test"), "generation-1"),
            )
            override val faceId: FontFaceId = key.fontInstanceKey.face

            override fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation> =
                FontOperationResult.Success(
                    GlyphRepresentation.Paint(
                        GlyphPaintIR(
                            schemaVersion = 1,
                            rootNode = 0,
                            nodes = listOf(
                                GlyphPaintNode.Path(
                                    GlyphPaintPath(
                                        listOf(
                                            GlyphPaintPathCommand.MoveTo(0.0, 0.0),
                                            GlyphPaintPathCommand.LineTo(1.0, 1.0),
                                        ),
                                    ),
                                    GlyphPaintBrush.Solid(GlyphColor(0, 0, 0)),
                                ),
                            ),
                        ),
                    ),
                )

            override fun close(): FontOperationResult<Unit> = FontOperationResult.Success(Unit)
        }

        val result = asset.resolveGlyphCertified(FontGlyphRequest(GlyphId(42)))

        assertIs<FontError.UnsupportedRepresentationProfile>(
            assertIs<FontOperationResult.Failure>(result).error,
        )
    }

    private fun instanceKey(): FontInstanceKey =
        FontInstanceKey(
            face = FontFaceId(
                FontSourceId.Portable(FontContentDigest("f".repeat(64))),
                faceIndex = 0,
            ),
            interpretation = FontDataInterpretationVersion("test", "1"),
            layoutSize = LayoutUnit(12f),
        )

    private fun outlineProfile(): OutlineProfile =
        OutlineProfile(
            maxBytes = 1_024,
            maxContours = 32,
            maxPoints = 128,
            maxCompositeDepth = 8,
            maxCompositeComponents = 32,
        )
}

package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        val paletteVariant = FontRenderVariantSnapshot(cpalPaletteIndex = 1)
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
            assetKey = assetKey.copy(
                variant = paletteVariant.key,
                variantSnapshot = paletteVariant,
            ),
            glyphId = GlyphId(12),
            variant = paletteVariant.key,
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
    fun certificateRejectsARouteThatDoesNotMatchItsAssetProfile() {
        val paintProfile = PaintGraphProfile(
            acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE),
            acceptedCompositionModes = emptyList(),
            limits = PaintGraphLimits(maxNodes = 1, maxReferences = 0, maxDepth = 1),
            outlineProfile = outlineProfile(),
        )
        val assetKey = FontRenderAssetKey(
            fontInstanceKey = instanceKey(),
            variant = FontRenderVariantKey.default,
            representationProfile = paintProfile,
            generation = FontCatalogGeneration(FontProviderId("embedded"), "generation-1"),
        )

        assertFailsWith<IllegalArgumentException> {
            GlyphMaterializationCertificate(
                assetKey = assetKey,
                glyphId = GlyphId(12),
                route = GlyphMaterializationRoute.OUTLINE,
            )
        }
    }

    @Test
    fun paintProfileRejectsAnOutlineThatExceedsItsNestedOutlineLimits() {
        val outline = GlyphOutlineIR(
            glyphId = 12,
            unitsPerEm = 1_000,
            bounds = DesignBounds.empty,
            commands = listOf(
                GlyphOutlineIR.Command.MoveTo(0, 0),
                GlyphOutlineIR.Command.Close,
                GlyphOutlineIR.Command.MoveTo(1, 1),
                GlyphOutlineIR.Command.Close,
            ),
        )
        val profile = PaintGraphProfile(
            acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE),
            acceptedCompositionModes = emptyList(),
            limits = PaintGraphLimits(maxNodes = 1, maxReferences = 0, maxDepth = 1),
            outlineProfile = OutlineProfile(
                maxBytes = Int.MAX_VALUE,
                maxContours = 1,
                maxPoints = 2,
                maxCompositeDepth = 1,
                maxCompositeComponents = 1,
            ),
        )
        val paint = GlyphPaintIR(
            schemaVersion = 1,
            rootNode = 0,
            nodes = listOf(GlyphPaintNode.SolidOutline(outline, GlyphColor(0, 0, 0))),
        )

        assertFalse(profile.accepts(paint))
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

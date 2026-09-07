package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class GlyphRepresentationContractsTest {
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

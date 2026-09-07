package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.BitmapGlyphIR
import org.graphiks.kalligraphie.api.BitmapLimits
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphColorSpace
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMaterializationRoute
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EbdtFormatOneGlyphRepresentationTest {
    @Test
    fun decodesSkiaEbdtFormatOneGrinningFaceAtTheExplicitSixteenPixelStrike() {
        val catalog = success(Kalligraphie.embedded(fixtureBytes(), FontSourceProvenance("Skia EBDT format 1")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile()))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))

        try {
            val glyph = success(instance.resolveGlyph(0x1F600)).glyphId
            assertEquals(GlyphId(3), glyph)
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val bitmap = assertIs<GlyphRepresentation.Bitmap>(success(asset.resolveGlyph(FontGlyphRequest(glyph)))).bitmap

                assertBitmap(bitmap)
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun rejectsTheWholeSelectedStrikeBeforePublishingAnAssetWhenItsPixelsExceedTheProfileLimit() {
        val catalog = success(Kalligraphie.embedded(fixtureBytes(), FontSourceProvenance("Skia EBDT format 1")))
        val requirements = FontAccessRequirementsSnapshot.renderable(
            listOf(bitmapProfile(maxWidth = 12)),
        )
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))

        try {
            val failure = assertIs<FontOperationResult.Failure>(
                instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements),
            )

            assertIs<FontError.ResourceLimitExceeded>(failure.error)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun rejectsTheWholeSelectedStrikeWhenItsAggregateDecodedPixelBudgetIsExceeded() {
        val catalog = success(Kalligraphie.embedded(fixtureBytes(), FontSourceProvenance("Skia EBDT format 1")))
        val requirements = FontAccessRequirementsSnapshot.renderable(
            listOf(bitmapProfile(maxTotalDecodedBytes = 168)),
        )
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))

        try {
            val failure = assertIs<FontOperationResult.Failure>(
                instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements),
            )

            assertIs<FontError.ResourceLimitExceeded>(failure.error)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun certifiesTheBitmapRouteAndResolvesTheExactCertificateWithoutASecondRouteNegotiation() {
        val catalog = success(Kalligraphie.embedded(fixtureBytes(), FontSourceProvenance("Skia EBDT format 1")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile()))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))

        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val certified = success(asset.resolveGlyphCertified(FontGlyphRequest(GlyphId(3))))
                assertEquals(GlyphMaterializationRoute.BITMAP, certified.certificate.route)
                assertIs<GlyphRepresentation.Bitmap>(certified.representation)

                val resolved = success(asset.resolveCertifiedGlyph(certified.certificate))
                assertIs<GlyphRepresentation.Bitmap>(resolved)
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun reopensTheSameCertifiedBitmapRouteFromItsLiveGenerationResolver() {
        val catalog = success(Kalligraphie.embedded(fixtureBytes(), FontSourceProvenance("Skia EBDT format 1")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile()))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))

        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            val certificate = try {
                success(asset.resolveGlyphCertified(FontGlyphRequest(GlyphId(3)))).certificate
            } finally {
                asset.close()
            }
            val reopenedResult = resolver.reopen(certificate.assetKey)
            val reopened = success(reopenedResult)
            try {
                assertBitmap(assertIs<GlyphRepresentation.Bitmap>(success(reopened.resolveCertifiedGlyph(certificate))).bitmap)
            } finally {
                reopened.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun selectsTheFirstProfileThatTheFaceCanActuallyMaterializeInDeclaredPreferenceOrder() {
        val catalog = success(Kalligraphie.embedded(fixtureBytes(), FontSourceProvenance("Skia EBDT format 1")))
        val requirements = FontAccessRequirementsSnapshot.renderable(
            listOf(
                OutlineProfile(
                    maxBytes = 1_024,
                    maxContours = 8,
                    maxPoints = 64,
                    maxCompositeDepth = 2,
                    maxCompositeComponents = 2,
                ),
                bitmapProfile(),
            ),
        )
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))

        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                assertEquals(bitmapProfile(), asset.key.representationProfile)
                assertIs<GlyphRepresentation.Bitmap>(success(asset.resolveGlyph(FontGlyphRequest(GlyphId(3)))))
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    private fun assertBitmap(bitmap: BitmapGlyphIR) {
        assertEquals(BitmapStrike(16, 16), bitmap.strike)
        assertEquals(13, bitmap.width)
        assertEquals(13, bitmap.height)
        assertEquals(0, bitmap.originX)
        assertEquals(13, bitmap.originY)
        assertEquals(12, bitmap.metrics.advanceX)
        assertEquals(0, bitmap.metrics.advanceY)
        assertEquals(BitmapPixelFormat.ALPHA_8, bitmap.pixelFormat)
        assertEquals(GlyphColorSpace.SRGB, bitmap.colorSpace)
        assertContentEquals(expectedPixels(), bitmap.copyDecodedPixels())
    }

    private fun bitmapProfile(
        maxWidth: Int = 16,
        maxTotalDecodedBytes: Int = 4_096,
    ): BitmapProfile = BitmapProfile(
        strike = BitmapStrike(16, 16),
        acceptedPixelFormats = listOf(BitmapPixelFormat.ALPHA_8),
        acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
        limits = BitmapLimits(
            maxStrikes = 3,
            maxWidth = maxWidth,
            maxHeight = 16,
            maxPixels = 256,
            maxCompressedBytes = 64,
            maxDecodedBytes = 256,
            maxTotalCompressedBytes = 4_096,
            maxTotalDecodedBytes = maxTotalDecodedBytes,
        ),
    )

    private fun expectedPixels(): ByteArray =
        listOf(
            ".............",
            "....#####....",
            "..#########..",
            ".##########..",
            ".###########.",
            ".###########.",
            "############.",
            ".###########.",
            ".###########.",
            ".###########.",
            "..#########..",
            "...#######...",
            ".....##......",
        ).flatMap { row -> row.map { pixel -> if (pixel == '#') 255.toByte() else 0 } }.toByteArray()

    private fun fixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/skia-ebdt-format1/ebdt_fmt1.ttf")) {
            "Skia EBDT format 1 fixture is missing"
        }.use { input -> input.readBytes() }

    private fun <T> success(result: FontOperationResult<T>): T = when (result) {
        is FontOperationResult.Success -> result.value
        is FontOperationResult.Failure -> error("Unexpected font failure ${result.error.code}: ${result.error.message}")
        is FontOperationResult.Cancelled -> error("Unexpected font cancellation")
    }
}

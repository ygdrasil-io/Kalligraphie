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
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
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

    private fun bitmapProfile(maxWidth: Int = 16): BitmapProfile = BitmapProfile(
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

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}

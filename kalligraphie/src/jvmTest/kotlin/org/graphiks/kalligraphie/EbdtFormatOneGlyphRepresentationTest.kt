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
import org.graphiks.kalligraphie.api.FontMaterializationCachePolicy
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
import kotlin.test.assertFalse
import kotlin.test.assertIs

class EbdtFormatOneGlyphRepresentationTest {
    @Test
    fun doesNotAdvertiseBitmapCapabilityWhenTheEbdtHeaderUsesAnUnsupportedVersion() {
        val corrupted = fixtureBytes().also { bytes ->
            bytes[tableOffset(bytes, "EBDT") + 3] = 1
        }

        val catalog = success(Kalligraphie.embedded(corrupted, FontSourceProvenance("Unsupported EBDT header")))

        assertFalse(catalog.faces.single().capabilities.bitmap)
    }

    @Test
    fun doesNotAdvertiseBitmapCapabilityWhenAnEblcSubtableUsesAnUnsupportedFormat() {
        val corrupted = fixtureBytes().also { bytes ->
            val indexFormatOffset = firstEblcIndexSubtableOffset(bytes)
            bytes[indexFormatOffset] = 0
            bytes[indexFormatOffset + 1] = 2
        }

        val catalog = success(Kalligraphie.embedded(corrupted, FontSourceProvenance("Unsupported EBLC index format")))

        assertFalse(catalog.faces.single().capabilities.bitmap)
    }

    @Test
    fun decodesSkiaEbdtFormatOneGrinningFaceAtTheExplicitSixteenPixelStrike() {
        val catalog = success(
            Kalligraphie.embedded(
                fixtureBytes(),
                FontSourceProvenance("Skia EBDT format 1"),
                FontMaterializationCachePolicy(maxEvictableBytesPerFace = 10_000),
            ),
        )
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
                val warmBitmap = assertIs<GlyphRepresentation.Bitmap>(success(asset.resolveGlyph(FontGlyphRequest(glyph)))).bitmap
                repeat(5) { index ->
                    val pressureRequirements = FontAccessRequirementsSnapshot.renderable(
                        listOf(bitmapProfile(maxBitmapTableBytes = 16_384 + index + 1)),
                    )
                    val pressureAsset = success(
                        instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, pressureRequirements),
                    )
                    try {
                        assertIs<GlyphRepresentation.Bitmap>(success(pressureAsset.resolveGlyph(FontGlyphRequest(glyph))))
                    } finally {
                        pressureAsset.close()
                    }
                }
                val afterPressureBitmap = assertIs<GlyphRepresentation.Bitmap>(success(asset.resolveGlyph(FontGlyphRequest(glyph)))).bitmap

                assertBitmap(bitmap)
                assertEquals(bitmap, warmBitmap)
                assertEquals(bitmap, afterPressureBitmap)
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
    fun reopensTheExactBitmapAssetAfterTheOriginalHandleCloses() {
        val catalog = success(Kalligraphie.embedded(fixtureBytes(), FontSourceProvenance("Skia EBDT format 1")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile()))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))

        try {
            val original = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            val key = original.key
            original.close()

            val reopened = success(resolver.reopen(key))
            try {
                val bitmap = assertIs<GlyphRepresentation.Bitmap>(
                    success(reopened.resolveGlyph(FontGlyphRequest(GlyphId(3)))),
                ).bitmap
                assertBitmap(bitmap)
            } finally {
                reopened.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun rejectsTheWholeBitmapTableBeforePublishingAnAssetWhenItsSourceBytesExceedTheProfileLimit() {
        val catalog = success(Kalligraphie.embedded(fixtureBytes(), FontSourceProvenance("Skia EBDT format 1")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile(maxBitmapTableBytes = 1)))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))

        try {
            val result = instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements)
            assertIs<FontError.ResourceLimitExceeded>(assertIs<FontOperationResult.Failure>(result).error)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun rejectsTheSelectedStrikeBeforePublishingAnAssetWhenAggregateRecordsExceedTheProfileLimit() {
        val catalog = success(Kalligraphie.embedded(fixtureBytes(), FontSourceProvenance("Skia EBDT format 1")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile(maxRecordCount = 0)))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))

        try {
            val result = instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements)
            assertIs<FontError.ResourceLimitExceeded>(assertIs<FontOperationResult.Failure>(result).error)
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
        maxBitmapTableBytes: Int = 16_384,
        maxRecordCount: Int = 16,
    ): BitmapProfile = BitmapProfile(
        strike = BitmapStrike(16, 16),
        acceptedPixelFormats = listOf(BitmapPixelFormat.ALPHA_8),
        acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
        limits = BitmapLimits(
            maxStrikes = 3,
            maxIndexSubtables = 16,
            maxRecordCount = maxRecordCount,
            maxIndexTableBytes = 16_384,
            maxBitmapTableBytes = maxBitmapTableBytes,
            maxWidth = maxWidth,
            maxHeight = 16,
            maxPixels = 256,
            maxCompressedBytes = 64,
            maxTotalCompressedBytes = 1_024,
            maxDecodedBytes = 256,
            maxTotalDecodedBytes = 1_024,
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

    private fun tableOffset(font: ByteArray, tag: String): Int {
        val tableCount = readUInt16(font, 4)
        repeat(tableCount) { index ->
            val recordOffset = 12 + index * 16
            if ((0 until 4).all { tagIndex -> font[recordOffset + tagIndex].toInt().toChar() == tag[tagIndex] }) {
                return readUInt32(font, recordOffset + 8)
            }
        }
        error("Missing $tag table in EBDT fixture.")
    }

    private fun firstEblcIndexSubtableOffset(font: ByteArray): Int {
        val eblcOffset = tableOffset(font, "EBLC")
        val indexSubtableArrayOffset = readUInt32(font, eblcOffset + 8)
        val additionalOffset = readUInt32(font, eblcOffset + indexSubtableArrayOffset + 4)
        return eblcOffset + indexSubtableArrayOffset + additionalOffset
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readUInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}

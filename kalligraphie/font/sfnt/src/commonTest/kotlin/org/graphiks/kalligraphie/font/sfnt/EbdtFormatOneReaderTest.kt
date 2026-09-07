package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.BitmapLimits
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphColorSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EbdtFormatOneReaderTest {
    @Test
    fun rejectsUnsupportedIndexAndImageFormatsBeforeReturningRouteData() {
        val unsupportedIndex = formatOneTables(indexFormat = 2, imageFormat = 1)
        val unsupportedImage = formatOneTables(indexFormat = 1, imageFormat = 2)

        assertIs<FontError.UnsupportedRepresentationProfile>(
            error(EbdtFormatOneReader.read(unsupportedIndex.first, unsupportedIndex.second, glyphCount = 1, profile = profile())),
        )
        assertIs<FontError.UnsupportedRepresentationProfile>(
            error(EbdtFormatOneReader.read(unsupportedImage.first, unsupportedImage.second, glyphCount = 1, profile = profile())),
        )
    }

    @Test
    fun rejectsAggregateCompressedBytesBeforeReturningAnyBitmapRouteData() {
        val tables = formatOneTables(recordCount = 2)

        assertIs<FontError.ResourceLimitExceeded>(
            error(
                EbdtFormatOneReader.read(
                    eblcTable = tables.first,
                    ebdtTable = tables.second,
                    glyphCount = 2,
                    profile = profile(maxRecordCount = 2, maxTotalCompressedBytes = 11),
                ),
            ),
        )
    }

    @Test
    fun reportsTruncatedEbdtHeaderAsInvalidFontData() {
        val result = EbdtFormatOneReader.read(
            eblcTable = eblcHeader(strikeCount = 0),
            ebdtTable = ByteArray(3),
            glyphCount = 1,
            profile = profile(),
        )

        assertEquals("font.ebdt.truncated", failure(result).error.code)
    }

    @Test
    fun reportsZeroPpemStrikeAsInvalidFontDataInsteadOfThrowing() {
        val eblc = ByteArray(56).also { bytes ->
            bytes.writeUInt32(0, VERSION_TWO)
            bytes.writeUInt32(4, 1u)
            bytes.writeUInt16(8 + 40, 0)
            bytes.writeUInt16(8 + 42, 0)
            bytes[8 + 44] = 0
            bytes[8 + 45] = 0
            bytes[8 + 46] = 1
        }

        val result = EbdtFormatOneReader.read(
            eblcTable = eblc,
            ebdtTable = ebdtHeader(),
            glyphCount = 1,
            profile = profile(),
        )

        assertEquals("font.eblc.invalid-strike", failure(result).error.code)
    }

    private fun profile(
        maxRecordCount: Int = 1,
        maxTotalCompressedBytes: Int = 64,
    ): BitmapProfile = BitmapProfile(
        strike = BitmapStrike(16, 16),
        acceptedPixelFormats = listOf(BitmapPixelFormat.ALPHA_8),
        acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
        limits = BitmapLimits(
            maxStrikes = 1,
            maxIndexSubtables = 1,
            maxRecordCount = maxRecordCount,
            maxIndexTableBytes = 1_024,
            maxBitmapTableBytes = 1_024,
            maxWidth = 16,
            maxHeight = 16,
            maxPixels = 256,
            maxCompressedBytes = 64,
            maxTotalCompressedBytes = maxTotalCompressedBytes,
            maxDecodedBytes = 256,
            maxTotalDecodedBytes = 256,
        ),
    )

    private fun eblcHeader(strikeCount: Int): ByteArray = ByteArray(8).also { bytes ->
        bytes.writeUInt32(0, VERSION_TWO)
        bytes.writeUInt32(4, strikeCount.toUInt())
    }

    private fun ebdtHeader(): ByteArray = ByteArray(4).also { bytes -> bytes.writeUInt32(0, VERSION_TWO) }

    private fun formatOneTables(
        indexFormat: Int = 1,
        imageFormat: Int = 1,
        recordCount: Int = 1,
    ): Pair<ByteArray, ByteArray> {
        val recordLength = 6
        val eblc = ByteArray(72 + (recordCount + 1) * 4).also { bytes ->
            bytes.writeUInt32(0, VERSION_TWO)
            bytes.writeUInt32(4, 1u)
            bytes.writeUInt32(8, 56u)
            bytes.writeUInt32(16, 1u)
            bytes.writeUInt16(48, 0)
            bytes.writeUInt16(50, recordCount - 1)
            bytes[52] = 16
            bytes[53] = 16
            bytes[54] = 1
            bytes.writeUInt16(56, 0)
            bytes.writeUInt16(58, recordCount - 1)
            bytes.writeUInt32(60, 8u)
            bytes.writeUInt16(64, indexFormat)
            bytes.writeUInt16(66, imageFormat)
            bytes.writeUInt32(68, 4u)
            repeat(recordCount + 1) { offset ->
                bytes.writeUInt32(72 + offset * 4, (offset * recordLength).toUInt())
            }
        }
        val ebdt = ByteArray(4 + recordCount * recordLength).also { bytes ->
            bytes.writeUInt32(0, VERSION_TWO)
            repeat(recordCount) { record ->
                val offset = 4 + record * recordLength
                bytes[offset] = 1
                bytes[offset + 1] = 1
                bytes[offset + 4] = 1
                bytes[offset + 5] = 0x80.toByte()
            }
        }
        return eblc to ebdt
    }

    private fun failure(result: FontOperationResult<EbdtFormatOneData>): FontOperationResult.Failure =
        assertIs<FontOperationResult.Failure>(result).also { failure ->
            assertIs<FontError.FontDataFailure>(failure.error)
        }

    private fun error(result: FontOperationResult<EbdtFormatOneData>): FontError =
        assertIs<FontOperationResult.Failure>(result).error
}

private const val VERSION_TWO: UInt = 0x00020000u

private fun ByteArray.writeUInt16(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

private fun ByteArray.writeUInt32(offset: Int, value: UInt) {
    this[offset] = (value shr 24).toByte()
    this[offset + 1] = (value shr 16).toByte()
    this[offset + 2] = (value shr 8).toByte()
    this[offset + 3] = value.toByte()
}

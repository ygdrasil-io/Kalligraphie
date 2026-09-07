package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.BitmapGlyphIR
import org.graphiks.kalligraphie.api.BitmapGlyphMetrics
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphColorSpace
import org.graphiks.kalligraphie.api.GlyphId

/**
 * Fully validated EBDT version 2.0 / EBLC version 2.0 bitmap route.
 *
 * This value supports only EBLC index-subtable format 1 with EBDT image format 1 and one-bit,
 * byte-aligned rows. PNG, JPEG, color, composite, big-metrics, and other index formats are
 * rejected while opening the route. It retains only the selected strike's validated records and
 * never exposes raw EBDT bytes to a consumer.
 */
public class EbdtFormatOneData internal constructor(
    private val strike: BitmapStrike,
    private val glyphCount: Int,
    private val records: Map<GlyphId, EbdtFormatOneRecord>,
) {
    /**
     * Decodes one validated glyph into portable alpha pixels.
     *
     * @return `null` when the selected strike has no bitmap for [glyphId], a complete immutable
     * bitmap, or cancellation without partial pixels.
     */
    public fun decode(
        glyphId: GlyphId,
        cancellationToken: CancellationToken = CancellationToken.none,
    ): FontOperationResult<BitmapGlyphIR?> {
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        if (glyphId.value !in 0 until glyphCount) {
            return FontOperationResult.Failure(FontError.GlyphOutOfRange(glyphId.value, location = FontDiagnosticLocation.Glyph(glyphId.value)))
        }
        val record = records[glyphId] ?: return FontOperationResult.Success(null)
        val pixels = ByteArray(record.width * record.height)
        var output = 0
        for (row in 0 until record.height) {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            for (column in 0 until record.width) {
                val sourceByte = record.packedPixels[row * record.bytesPerRow + column / 8].toInt() and 0xFF
                pixels[output++] = if ((sourceByte and (0x80 ushr (column and 7))) != 0) 0xFF.toByte() else 0
            }
        }
        return FontOperationResult.Success(
            BitmapGlyphIR(
                glyphId = glyphId,
                strike = strike,
                width = record.width,
                height = record.height,
                originX = record.bearingX,
                originY = record.bearingY,
                metrics = BitmapGlyphMetrics(advanceX = record.advance, advanceY = 0),
                pixelFormat = BitmapPixelFormat.ALPHA_8,
                colorSpace = GlyphColorSpace.SRGB,
                decodedPixels = pixels,
            ),
        )
    }
}

/** Reads the declared EBDT format-1 bitmap route into portable bounded records. */
public object EbdtFormatOneReader {
    /**
     * Parses the exact strike requested by [profile].
     *
     * The operation validates every index subtable and every image record in the selected strike
     * before it returns. A different strike, another bit depth, an unsupported codec, truncated
     * data, or a limit breach is returned as a typed failure rather than deferred to a renderer.
     *
     * @param eblcTable exact bytes of the OpenType `EBLC` table.
     * @param ebdtTable exact bytes of the OpenType `EBDT` table.
     * @param glyphCount number of glyph identifiers declared by the face.
     * @param profile exact strike, format, color-space, and resource requirements.
     */
    public fun read(
        eblcTable: ByteArray,
        ebdtTable: ByteArray,
        glyphCount: Int,
        profile: BitmapProfile,
    ): FontOperationResult<EbdtFormatOneData> {
        if (glyphCount <= 0) return invalid("font.ebdt.invalid-glyph-count", "EBDT requires a positive face glyph count.", "EBLC")
        if (profile.schemaVersion != 1 || BitmapPixelFormat.ALPHA_8 !in profile.acceptedPixelFormats || GlyphColorSpace.SRGB !in profile.acceptedColorSpaces) {
            return unsupported("The selected bitmap profile does not accept EBDT format 1 alpha pixels.")
        }
        if (eblcTable.size > profile.limits.maxIndexTableBytes) {
            return limit("EBLC source-byte limit exceeded.", "EBLC")
        }
        if (ebdtTable.size > profile.limits.maxBitmapTableBytes) {
            return limit("EBDT source-byte limit exceeded.", "EBDT")
        }
        if (eblcTable.size < EBLC_HEADER_LENGTH) return invalid("font.eblc.truncated", "EBLC header is truncated.", "EBLC")
        if (readUInt32(eblcTable, 0) != EBLC_VERSION_2) return unsupported("Only EBLC version 2.0 is supported.")
        if (ebdtTable.size < EBDT_HEADER_LENGTH) return invalid("font.ebdt.truncated", "EBDT header is truncated.", "EBDT")
        if (readUInt32(ebdtTable, 0) != EBDT_VERSION_2) return unsupported("Only EBDT version 2.0 is supported.")
        val strikeCount = readUInt32(eblcTable, 4)?.toLong()
            ?: return invalid("font.eblc.truncated", "EBLC strike count is truncated.", "EBLC")
        if (strikeCount > profile.limits.maxStrikes.toLong()) return limit("EBLC strike limit exceeded.", "EBLC")
        checkedRangeEnd(EBLC_HEADER_LENGTH.toLong(), strikeCount * BITMAP_SIZE_TABLE_LENGTH, eblcTable.size)
            ?: return invalid("font.eblc.truncated", "EBLC bitmap size tables are truncated.", "EBLC")

        var selected: BitmapSizeTable? = null
        repeat(strikeCount.toInt()) { index ->
            val table = when (val parsed = readBitmapSizeTable(eblcTable, EBLC_HEADER_LENGTH + index * BITMAP_SIZE_TABLE_LENGTH)) {
                is FontOperationResult.Success -> parsed.value
                is FontOperationResult.Failure -> return parsed
                is FontOperationResult.Cancelled -> return parsed
            }
            if (table.strike == profile.strike) {
                if (selected != null) return invalid("font.eblc.duplicate-strike", "EBLC declares the requested strike more than once.", "EBLC")
                selected = table
            }
        }
        val size = selected ?: return unsupported("The exact requested bitmap strike is unavailable.")
        if (size.bitDepth != 1) return unsupported("Only one-bit EBDT image data is supported.")
        if (size.startGlyphId !in 0 until glyphCount || size.endGlyphId !in size.startGlyphId until glyphCount) {
            return invalid("font.eblc.invalid-glyph-range", "EBLC strike glyph range is outside the face.", "EBLC")
        }
        return readStrike(eblcTable, ebdtTable, glyphCount, size, profile)
    }

    private fun readStrike(
        eblc: ByteArray,
        ebdt: ByteArray,
        glyphCount: Int,
        size: BitmapSizeTable,
        profile: BitmapProfile,
    ): FontOperationResult<EbdtFormatOneData> {
        if (size.numberOfIndexSubTables > profile.limits.maxIndexSubtables) {
            return limit("EBLC index-subtable limit exceeded.", "EBLC")
        }
        checkedRangeEnd(size.indexSubTableArrayOffset, size.numberOfIndexSubTables.toLong() * INDEX_SUBTABLE_ARRAY_ENTRY_LENGTH, eblc.size)
            ?: return invalid("font.eblc.truncated", "EBLC index-subtable array is truncated.", "EBLC")
        val records = LinkedHashMap<GlyphId, EbdtFormatOneRecord>()
        var recordCount = 0
        var totalCompressedBytes = 0L
        var totalDecodedBytes = 0L
        repeat(size.numberOfIndexSubTables) { index ->
            val entryOffset = size.indexSubTableArrayOffset.toInt() + index * INDEX_SUBTABLE_ARRAY_ENTRY_LENGTH
            val firstGlyph = readUInt16(eblc, entryOffset)?.toInt()
                ?: return invalid("font.eblc.truncated", "EBLC subtable glyph range is truncated.", "EBLC")
            val lastGlyph = readUInt16(eblc, entryOffset + 2)?.toInt()
                ?: return invalid("font.eblc.truncated", "EBLC subtable glyph range is truncated.", "EBLC")
            val additionalOffset = readUInt32(eblc, entryOffset + 4)?.toLong()
                ?: return invalid("font.eblc.truncated", "EBLC subtable offset is truncated.", "EBLC")
            if (firstGlyph !in 0 until glyphCount || lastGlyph !in firstGlyph until glyphCount) {
                return invalid("font.eblc.invalid-glyph-range", "EBLC subtable range is outside the face.", "EBLC")
            }
            val subtableOffset = size.indexSubTableArrayOffset + additionalOffset
            checkedRangeEnd(subtableOffset, INDEX_SUBTABLE_HEADER_LENGTH, eblc.size)
                ?: return invalid("font.eblc.truncated", "EBLC index subtable is truncated.", "EBLC")
            val indexFormat = readUInt16(eblc, subtableOffset.toInt())?.toInt()
                ?: return invalid("font.eblc.truncated", "EBLC index subtable is truncated.", "EBLC")
            val imageFormat = readUInt16(eblc, subtableOffset.toInt() + 2)?.toInt()
                ?: return invalid("font.eblc.truncated", "EBLC image format is truncated.", "EBLC")
            val imageDataOffset = readUInt32(eblc, subtableOffset.toInt() + 4)?.toLong()
                ?: return invalid("font.eblc.truncated", "EBLC image-data offset is truncated.", "EBLC")
            if (indexFormat != 1 || imageFormat != 1) return unsupported("Only EBLC index format 1 and EBDT image format 1 are supported.")
            val glyphsInSubtable = lastGlyph - firstGlyph + 1
            if (glyphsInSubtable > profile.limits.maxRecordCount - recordCount) {
                return limit("EBLC bitmap-record limit exceeded.", "EBLC")
            }
            recordCount += glyphsInSubtable
            val offsetArrayStart = subtableOffset + INDEX_SUBTABLE_HEADER_LENGTH
            checkedRangeEnd(offsetArrayStart, (glyphsInSubtable + 1).toLong() * 4L, eblc.size)
                ?: return invalid("font.eblc.truncated", "EBLC image-offset array is truncated.", "EBLC")
            var previousOffset = -1L
            val offsets = LongArray(glyphsInSubtable + 1)
            repeat(glyphsInSubtable + 1) { offsetIndex ->
                val value = readUInt32(eblc, offsetArrayStart.toInt() + offsetIndex * 4)?.toLong()
                    ?: return invalid("font.eblc.truncated", "EBLC image offset is truncated.", "EBLC")
                if (value < previousOffset) return invalid("font.eblc.invalid-offset-order", "EBLC image offsets are not monotonic.", "EBLC")
                offsets[offsetIndex] = value
                previousOffset = value
            }
            repeat(glyphsInSubtable) { glyphOffset ->
                val length = offsets[glyphOffset + 1] - offsets[glyphOffset]
                if (length == 0L) return@repeat
                if (length > profile.limits.maxCompressedBytes.toLong()) return limit("EBDT compressed-byte limit exceeded.", "EBDT")
                if (exceedsCumulativeLimit(totalCompressedBytes, length, profile.limits.maxTotalCompressedBytes)) {
                    return limit("EBDT aggregate compressed-byte limit exceeded.", "EBDT")
                }
                val dataOffset = imageDataOffset + offsets[glyphOffset]
                val dataEnd = checkedRangeEnd(dataOffset, length, ebdt.size)
                    ?: return invalid("font.ebdt.truncated", "EBDT image data is truncated.", "EBDT")
                val parsedRecord = when (val parsed = readImageFormatOne(ebdt, dataOffset.toInt(), dataEnd, profile)) {
                    is FontOperationResult.Success -> parsed.value
                    is FontOperationResult.Failure -> return parsed
                    is FontOperationResult.Cancelled -> return parsed
                }
                if (exceedsCumulativeLimit(totalDecodedBytes, parsedRecord.decodedByteCount, profile.limits.maxTotalDecodedBytes)) {
                    return limit("EBDT aggregate decoded-byte limit exceeded.", "EBDT")
                }
                val record = EbdtFormatOneRecord(
                    width = parsedRecord.width,
                    height = parsedRecord.height,
                    bearingX = parsedRecord.bearingX,
                    bearingY = parsedRecord.bearingY,
                    advance = parsedRecord.advance,
                    bytesPerRow = parsedRecord.bytesPerRow,
                    packedPixels = ebdt.copyOfRange(parsedRecord.packedPixelsOffset, dataEnd),
                )
                val glyphId = GlyphId(firstGlyph + glyphOffset)
                if (records.put(glyphId, record) != null) return invalid("font.eblc.duplicate-glyph", "EBLC strike has overlapping glyph records.", "EBLC")
                totalCompressedBytes += length
                totalDecodedBytes += parsedRecord.decodedByteCount
            }
        }
        return FontOperationResult.Success(EbdtFormatOneData(size.strike, glyphCount, records))
    }

    private fun readImageFormatOne(
        data: ByteArray,
        start: Int,
        end: Int,
        profile: BitmapProfile,
    ): FontOperationResult<ParsedEbdtFormatOneRecord> {
        if (end - start < SMALL_GLYPH_METRICS_LENGTH) {
            return invalid("font.ebdt.truncated", "EBDT image format 1 metrics are truncated.", "EBDT")
        }
        val height = data[start].toInt() and 0xFF
        val width = data[start + 1].toInt() and 0xFF
        if (width == 0 || height == 0) {
            return invalid("font.ebdt.invalid-image", "EBDT image format 1 has zero dimensions.", "EBDT")
        }
        if (width > profile.limits.maxWidth || height > profile.limits.maxHeight) {
            return limit("EBDT image dimensions exceed the bitmap profile limit.", "EBDT")
        }
        val pixelCount = width.toLong() * height.toLong()
        if (pixelCount > profile.limits.maxPixels.toLong() || pixelCount > profile.limits.maxDecodedBytes.toLong()) {
            return limit("EBDT decoded-pixel limit exceeded.", "EBDT")
        }
        val bytesPerRow = (width + 7) / 8
        val expectedLength = SMALL_GLYPH_METRICS_LENGTH.toLong() + bytesPerRow.toLong() * height.toLong()
        if ((end - start).toLong() != expectedLength) {
            return invalid("font.ebdt.invalid-image", "EBDT image format 1 byte length is invalid.", "EBDT")
        }
        return FontOperationResult.Success(ParsedEbdtFormatOneRecord(
            width = width,
            height = height,
            bearingX = data[start + 2].toInt(),
            bearingY = data[start + 3].toInt(),
            advance = data[start + 4].toInt() and 0xFF,
            bytesPerRow = bytesPerRow,
            packedPixelsOffset = start + SMALL_GLYPH_METRICS_LENGTH,
            decodedByteCount = pixelCount,
        ))
    }

    private fun readBitmapSizeTable(bytes: ByteArray, offset: Int): FontOperationResult<BitmapSizeTable> {
        if (checkedRangeEnd(offset, BITMAP_SIZE_TABLE_LENGTH, bytes.size) == null) {
            return invalid("font.eblc.truncated", "EBLC bitmap size table is truncated.", "EBLC")
        }
        val indexSubTableArrayOffset = readUInt32(bytes, offset)?.toLong()
            ?: return invalid("font.eblc.truncated", "EBLC bitmap size table is truncated.", "EBLC")
        val numberOfIndexSubTables = readUInt32(bytes, offset + 8)?.toLong()
            ?: return invalid("font.eblc.truncated", "EBLC bitmap size table is truncated.", "EBLC")
        if (numberOfIndexSubTables > Int.MAX_VALUE) {
            return invalid("font.eblc.invalid-subtable-count", "EBLC subtable count is invalid.", "EBLC")
        }
        val startGlyphId = readUInt16(bytes, offset + 40)?.toInt()
            ?: return invalid("font.eblc.truncated", "EBLC bitmap size table is truncated.", "EBLC")
        val endGlyphId = readUInt16(bytes, offset + 42)?.toInt()
            ?: return invalid("font.eblc.truncated", "EBLC bitmap size table is truncated.", "EBLC")
        val ppemX = bytes[offset + 44].toInt() and 0xFF
        val ppemY = bytes[offset + 45].toInt() and 0xFF
        if (ppemX == 0 || ppemY == 0) {
            return invalid("font.eblc.invalid-strike", "EBLC strike ppem values must be positive.", "EBLC")
        }
        return FontOperationResult.Success(BitmapSizeTable(
            indexSubTableArrayOffset = indexSubTableArrayOffset,
            numberOfIndexSubTables = numberOfIndexSubTables.toInt(),
            startGlyphId = startGlyphId,
            endGlyphId = endGlyphId,
            strike = BitmapStrike(ppemX, ppemY),
            bitDepth = bytes[offset + 46].toInt() and 0xFF,
        ))
    }

    private fun invalid(code: String, message: String, table: String): FontOperationResult.Failure =
        FontOperationResult.Failure(FontError.FontDataFailure(code, message, FontDiagnosticLocation.Table(table)))

    private fun limit(message: String, table: String): FontOperationResult.Failure =
        FontOperationResult.Failure(FontError.ResourceLimitExceeded(message, FontDiagnosticLocation.Table(table)))

    private fun unsupported(message: String): FontOperationResult.Failure =
        FontOperationResult.Failure(FontError.UnsupportedRepresentationProfile(message, FontDiagnosticLocation.Table("EBLC")))
}

private fun exceedsCumulativeLimit(total: Long, increment: Long, maximum: Int): Boolean =
    increment > maximum.toLong() || total > maximum.toLong() - increment

private data class BitmapSizeTable(
    val indexSubTableArrayOffset: Long,
    val numberOfIndexSubTables: Int,
    val startGlyphId: Int,
    val endGlyphId: Int,
    val strike: BitmapStrike,
    val bitDepth: Int,
)

internal data class EbdtFormatOneRecord(
    val width: Int,
    val height: Int,
    val bearingX: Int,
    val bearingY: Int,
    val advance: Int,
    val bytesPerRow: Int,
    val packedPixels: ByteArray,
)

private data class ParsedEbdtFormatOneRecord(
    val width: Int,
    val height: Int,
    val bearingX: Int,
    val bearingY: Int,
    val advance: Int,
    val bytesPerRow: Int,
    val packedPixelsOffset: Int,
    val decodedByteCount: Long,
)

private const val EBLC_HEADER_LENGTH = 8
private const val EBDT_HEADER_LENGTH = 4
private const val BITMAP_SIZE_TABLE_LENGTH = 48
private const val INDEX_SUBTABLE_ARRAY_ENTRY_LENGTH = 8
private const val INDEX_SUBTABLE_HEADER_LENGTH = 8L
private const val SMALL_GLYPH_METRICS_LENGTH = 5
private const val EBLC_VERSION_2: UInt = 0x00020000u
private const val EBDT_VERSION_2: UInt = 0x00020000u

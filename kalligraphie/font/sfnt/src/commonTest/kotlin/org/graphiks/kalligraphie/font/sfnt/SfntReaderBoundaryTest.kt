@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SfntReaderBoundaryTest {
    @Test
    fun rejectsUnsupportedContainersWithTypedFailure() {
        val bytes = byteArrayOf(
            't'.code.toByte(),
            't'.code.toByte(),
            'c'.code.toByte(),
            'f'.code.toByte(),
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
        )

        val result = SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("unsupported.ttcf")))

        assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.UnsupportedContainer>(result.error)
    }

    @Test
    fun rejectsOutOfBoundsRequiredTablesWithTypedFailure() {
        val bytes = minimalTrueTypeFont(
            overrides = mapOf(
                "glyf" to TableBytes(bytes = byteArrayOf(1, 2, 3, 4), forcedOffset = 4096),
            ),
        )

        val result = SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("out-of-bounds.ttf")))

        assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.OutOfBounds>(result.error)
        assertEquals(FontDiagnosticLocation.Table("glyf"), result.error.location)
    }

    @Test
    fun ignoresNonUnicodeWindowsNameRecordsWhenChoosingEnglishNames() {
        val bytes = minimalTrueTypeFont(
            nameRecords = listOf(
                nameRecord(platformId = 3, encodingId = 0, languageId = 0x0409, nameId = 1, text = "Wrong Family"),
                nameRecord(platformId = 3, encodingId = 0, languageId = 0x0409, nameId = 2, text = "Wrong Style"),
                nameRecord(platformId = 3, encodingId = 1, languageId = 0x0409, nameId = 1, text = "Right Family"),
                nameRecord(platformId = 3, encodingId = 1, languageId = 0x0409, nameId = 2, text = "Right Style"),
            ),
        )

        val result = assertIs<FontOperationResult.Success<ParsedTrueTypeFont>>(
            SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("unicode-names.ttf"))),
        )

        assertEquals("Right Family", result.value.metadata.familyName)
        assertEquals("Right Style", result.value.metadata.styleName)
    }

    @Test
    fun acceptsMacintoshRomanNameRecordsWhenNoUnicodeRecordExists() {
        val bytes = minimalTrueTypeFont(
            nameRecords = listOf(
                nameRecord(platformId = 1, encodingId = 0, languageId = 0, nameId = 1, text = "Menlo"),
                nameRecord(platformId = 1, encodingId = 0, languageId = 0, nameId = 2, text = "Regular"),
            ),
        )

        val result = assertIs<FontOperationResult.Success<ParsedTrueTypeFont>>(
            SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("macintosh-names.ttf"))),
        )

        assertEquals("Menlo", result.value.metadata.familyName)
        assertEquals("Regular", result.value.metadata.styleName)
    }

    @Test
    fun prefersUnicodeNamesOverMacintoshRomanFallback() {
        val bytes = minimalTrueTypeFont(
            nameRecords = listOf(
                nameRecord(platformId = 1, encodingId = 0, languageId = 0, nameId = 1, text = "Wrong Family"),
                nameRecord(platformId = 1, encodingId = 0, languageId = 0, nameId = 2, text = "Wrong Style"),
                nameRecord(platformId = 3, encodingId = 1, languageId = 0x0409, nameId = 1, text = "Right Family"),
                nameRecord(platformId = 3, encodingId = 1, languageId = 0x0409, nameId = 2, text = "Right Style"),
            ),
        )

        val result = assertIs<FontOperationResult.Success<ParsedTrueTypeFont>>(
            SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("mixed-names.ttf"))),
        )

        assertEquals("Right Family", result.value.metadata.familyName)
        assertEquals("Right Style", result.value.metadata.styleName)
    }

    @Test
    fun decodesMacintoshRomanHighBytesInFallbackNames() {
        val bytes = minimalTrueTypeFont(
            nameRecords = listOf(
                nameRecord(platformId = 1, encodingId = 0, languageId = 0, nameId = 1, text = "Café Royal"),
                nameRecord(platformId = 1, encodingId = 0, languageId = 0, nameId = 2, text = "Bold"),
            ),
        )

        val result = assertIs<FontOperationResult.Success<ParsedTrueTypeFont>>(
            SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("macintosh-high-bytes.ttf"))),
        )

        assertEquals("Café Royal", result.value.metadata.familyName)
        assertEquals("Bold", result.value.metadata.styleName)
    }

    @Test
    fun fixedWidthReadsRejectExtremeOffsetsWithoutIndexingTheArray() {
        val bytes = ByteArray(8)

        assertNull(readUInt16(bytes, Int.MAX_VALUE))
        assertNull(readUInt32(bytes, Int.MAX_VALUE))
        assertEquals("", bytes.decodeAsciiTag(Int.MAX_VALUE))
    }

    @Test
    fun rejectsZeroUnitsPerEmAsLocalizedInvalidFontData() {
        val bytes = minimalTrueTypeFont(
            overrides = mapOf(
                "head" to TableBytes(headTable(unitsPerEm = 0, indexToLocFormat = 0)),
            ),
        )

        val result = SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("zero-upem.ttf")))

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.InvalidFontData>(failure.error)
        assertEquals(FontDiagnosticLocation.Table("head"), failure.error.location)
        assertEquals(0L, failure.diagnostics.single().data.observedValue)
    }

    @Test
    fun rejectsBlankRequiredNamesAsLocalizedInvalidFontData() {
        val bytes = minimalTrueTypeFont(
            nameRecords = listOf(
                nameRecord(platformId = 3, encodingId = 1, languageId = 0x0409, nameId = 1, text = ""),
                nameRecord(platformId = 3, encodingId = 1, languageId = 0x0409, nameId = 2, text = "   "),
            ),
        )

        val result = SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("blank-names.ttf")))

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.InvalidFontData>(failure.error)
        assertEquals(FontDiagnosticLocation.Table("name"), failure.error.location)
    }

    @Test
    fun rejectsTruncatedTrueTypeMaxpProfile() {
        val bytes = minimalTrueTypeFont(
            overrides = mapOf("maxp" to TableBytes(ByteArray(6).also { it.writeUInt16(4, 1) })),
        )

        val result = SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("truncated-maxp.ttf")))

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.InvalidFontData>(failure.error)
        assertEquals(FontDiagnosticLocation.Table("maxp"), failure.error.location)
    }

    @Test
    fun rejectsZeroGlyphCountBeforeConstructingPublicMetadata() {
        val bytes = minimalTrueTypeFont(
            overrides = mapOf("maxp" to TableBytes(maxpTable(glyphCount = 0))),
        )

        val result = SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("zero-glyphs.ttf")))

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.InvalidFontData>(failure.error)
        assertEquals(0L, failure.diagnostics.single().data.observedValue)
    }

    @Test
    fun parsedTableRecordsRejectMutationThroughMutableMapCast() {
        val parsed = assertIs<FontOperationResult.Success<ParsedTrueTypeFont>>(
            SfntReader.readMetadata(FontSource(minimalTrueTypeFont(), FontSourceProvenance("immutable.ttf"))),
        ).value

        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (parsed.tableRecords as MutableMap<String, TableRecord>).clear()
        }
        assertNotNull(parsed.tableRecords["head"])
    }
}

private data class NameRecord(
    val platformId: Int,
    val encodingId: Int,
    val languageId: Int,
    val nameId: Int,
    val text: String,
)

private data class TableBytes(
    val bytes: ByteArray,
    val forcedOffset: Int? = null,
)

private fun minimalTrueTypeFont(
    nameRecords: List<NameRecord> = listOf(
        nameRecord(platformId = 3, encodingId = 1, languageId = 0x0409, nameId = 1, text = "Test Family"),
        nameRecord(platformId = 3, encodingId = 1, languageId = 0x0409, nameId = 2, text = "Regular"),
    ),
    overrides: Map<String, TableBytes> = emptyMap(),
): ByteArray {
    val tables = linkedMapOf(
        "head" to TableBytes(headTable(unitsPerEm = 2048, indexToLocFormat = 0)),
        "maxp" to TableBytes(maxpTable(glyphCount = 3)),
        "name" to TableBytes(nameTable(nameRecords)),
        "cmap" to TableBytes(byteArrayOf(0, 0, 0, 0)),
        "hhea" to TableBytes(byteArrayOf(0, 0, 0, 0)),
        "hmtx" to TableBytes(byteArrayOf(0, 0, 0, 0)),
        "loca" to TableBytes(byteArrayOf(0, 0, 0, 0)),
        "glyf" to TableBytes(byteArrayOf(0, 0, 0, 0)),
    )
    for ((tag, table) in overrides) {
        tables[tag] = table
    }

    val orderedTags = tables.keys.toList()
    val directorySize = 12 + orderedTags.size * 16
    val assignedOffsets = LinkedHashMap<String, Int>()
    var nextOffset = directorySize
    for (tag in orderedTags) {
        val override = tables.getValue(tag).forcedOffset
        if (override != null) {
            assignedOffsets[tag] = override
        } else {
            assignedOffsets[tag] = nextOffset
            nextOffset += tables.getValue(tag).bytes.size
        }
    }

    val totalSize = assignedOffsets.entries.fold(directorySize) { acc, (tag, offset) ->
        val bytes = tables.getValue(tag).bytes
        if (tables.getValue(tag).forcedOffset == null) {
            maxOf(acc, offset + bytes.size)
        } else {
            acc
        }
    }
    val fontBytes = ByteArray(totalSize)
    fontBytes.writeUInt32(0, 0x00010000)
    fontBytes.writeUInt16(4, orderedTags.size)
    fontBytes.writeUInt16(6, 0)
    fontBytes.writeUInt16(8, 0)
    fontBytes.writeUInt16(10, 0)

    var directoryOffset = 12
    for (tag in orderedTags) {
        val table = tables.getValue(tag).bytes
        val tableOffset = assignedOffsets.getValue(tag)
        fontBytes.writeTag(directoryOffset, tag)
        fontBytes.writeUInt32(directoryOffset + 4, 0)
        fontBytes.writeUInt32(directoryOffset + 8, tableOffset)
        fontBytes.writeUInt32(directoryOffset + 12, table.size)
        if (tableOffset + table.size <= fontBytes.size) {
            table.copyInto(fontBytes, destinationOffset = tableOffset)
        }
        directoryOffset += 16
    }
    return fontBytes
}

private fun headTable(unitsPerEm: Int, indexToLocFormat: Int): ByteArray =
    ByteArray(54).also { bytes ->
        bytes.writeUInt16(18, unitsPerEm)
        bytes.writeInt16(50, indexToLocFormat)
    }

private fun maxpTable(glyphCount: Int): ByteArray =
    ByteArray(32).also { bytes ->
        bytes.writeUInt32(0, 0x00010000)
        bytes.writeUInt16(4, glyphCount)
        bytes.writeUInt16(6, 128)
        bytes.writeUInt16(8, 16)
        bytes.writeUInt16(10, 128)
        bytes.writeUInt16(12, 16)
        bytes.writeUInt16(28, 8)
        bytes.writeUInt16(30, 8)
    }

private fun nameTable(records: List<NameRecord>): ByteArray {
    val encodedStrings = records.map { record ->
        if (record.platformId == 1) record.text.encodeMacRoman() else record.text.encodeUtf16Be()
    }
    val stringOffset = 6 + records.size * 12
    val totalStringBytes = encodedStrings.sumOf { it.size }
    val bytes = ByteArray(stringOffset + totalStringBytes)
    bytes.writeUInt16(0, 0)
    bytes.writeUInt16(2, records.size)
    bytes.writeUInt16(4, stringOffset)

    var recordOffset = 6
    var textOffset = 0
    for ((index, record) in records.withIndex()) {
        val encoded = encodedStrings[index]
        bytes.writeUInt16(recordOffset, record.platformId)
        bytes.writeUInt16(recordOffset + 2, record.encodingId)
        bytes.writeUInt16(recordOffset + 4, record.languageId)
        bytes.writeUInt16(recordOffset + 6, record.nameId)
        bytes.writeUInt16(recordOffset + 8, encoded.size)
        bytes.writeUInt16(recordOffset + 10, textOffset)
        encoded.copyInto(bytes, destinationOffset = stringOffset + textOffset)
        textOffset += encoded.size
        recordOffset += 12
    }
    return bytes
}

private fun nameRecord(
    platformId: Int,
    encodingId: Int,
    languageId: Int,
    nameId: Int,
    text: String,
): NameRecord = NameRecord(platformId, encodingId, languageId, nameId, text)

private fun String.encodeUtf16Be(): ByteArray =
    ByteArray(length * 2).also { bytes ->
        forEachIndexed { index, char ->
            val value = char.code
            bytes[index * 2] = (value ushr 8).toByte()
            bytes[index * 2 + 1] = value.toByte()
        }
    }

/** Encodes [this] as Macintosh Roman, rejecting text the classic one-byte codec cannot represent. */
private fun String.encodeMacRoman(): ByteArray {
    val highReverse = buildMap {
        MAC_ROMAN_HIGH_BYTES.forEachIndexed { index, char -> put(char, 0x80 + index) }
    }
    return ByteArray(length) { index ->
        val char = this[index]
        if (char.code < 0x80) {
            char.code.toByte()
        } else {
            highReverse[char]?.toByte() ?: error("Character '$char' is not encodable in Macintosh Roman.")
        }
    }
}

private val MAC_ROMAN_HIGH_BYTES: CharArray = charArrayOf(
    '\u00C4', '\u00C5', '\u00C7', '\u00C9', '\u00D1', '\u00D6', '\u00DC', '\u00E1', '\u00E0',
    '\u00E2', '\u00E4', '\u00E3', '\u00E5', '\u00E7', '\u00E9', '\u00E8', '\u00EA', '\u00EB',
    '\u00ED', '\u00EC', '\u00EE', '\u00EF', '\u00F1', '\u00F3', '\u00F2', '\u00F4', '\u00F6',
    '\u00F5', '\u00FA', '\u00F9', '\u00FB', '\u00FC', '\u2020', '\u00B0', '\u00A2', '\u00A3',
    '\u00A7', '\u2022', '\u00B6', '\u00DF', '\u00AE', '\u00A9', '\u2122', '\u00B4', '\u00A8',
    '\u2260', '\u00C6', '\u00D8', '\u221E', '\u00B1', '\u2264', '\u2265', '\u00A5', '\u00B5',
    '\u2202', '\u2211', '\u220F', '\u03C0', '\u222B', '\u00AA', '\u00BA', '\u03A9', '\u00E6',
    '\u00F8', '\u00BF', '\u00A1', '\u00AC', '\u221A', '\u0192', '\u2248', '\u2206', '\u00AB',
    '\u00BB', '\u2026', '\u00A0', '\u00C0', '\u00C3', '\u00D5', '\u0152', '\u0153', '\u2013',
    '\u2014', '\u201C', '\u201D', '\u2018', '\u2019', '\u00F7', '\u25CA', '\u00FF', '\u0178',
    '\u2044', '\u20AC', '\u2039', '\u203A', '\uFB01', '\uFB02', '\u2021', '\u00B7', '\u201A',
    '\u201E', '\u2030', '\u00C2', '\u00CA', '\u00C1', '\u00CB', '\u00C8', '\u00CD', '\u00CE',
    '\u00CF', '\u00CC', '\u00D3', '\u00D4', '\uF8FF', '\u00D2', '\u00DA', '\u00DB', '\u00D9',
    '\u0131', '\u02C6', '\u02DC', '\u00AF', '\u02D8', '\u02D9', '\u02DA', '\u00B8', '\u02DD',
    '\u02DB', '\u02C7',
)

private fun ByteArray.writeTag(offset: Int, tag: String) {
    tag.forEachIndexed { index, char ->
        this[offset + index] = char.code.toByte()
    }
}

private fun ByteArray.writeUInt16(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

private fun ByteArray.writeInt16(offset: Int, value: Int) {
    writeUInt16(offset, value and 0xFFFF)
}

private fun ByteArray.writeUInt32(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

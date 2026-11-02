@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticData
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontDiagnosticSeverity
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontFaceMetadata
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.sortedDiagnostics
import org.graphiks.kalligraphie.api.toDiagnostic

/**
 * Reads the structural metadata of a supported TrueType SFNT font.
 *
 * The source is defensively copied before parsing, so the returned metadata
 * cannot observe later caller mutations. Parsing is read-only and publishes a
 * result only after required tables, ranges, names, and TrueType limits have
 * been validated.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public object SfntReader {
    private val requiredTables = setOf("head", "maxp", "name", "cmap", "hhea", "hmtx")

    /**
     * Parses table records and face metadata from [source].
     *
     * Unsupported containers, malformed data, and missing required tables are
     * returned as [FontOperationResult.Failure]. The successful parsed value
     * is immutable and may be shared safely between concurrent readers. The
     * accepted signatures are TrueType `0x00010000` and legacy `true`; this
     * entry point deliberately rejects collections and CFF-based containers.
     * The source is read by defensive copy and is never mutated.
     *
     * @param source source bytes and caller-declared provenance to inspect.
     * @return validated table locations and face metadata, or a typed failure
     * with diagnostics identifying the invalid container or table.
     */
    public fun readMetadata(source: FontSource): FontOperationResult<ParsedTrueTypeFont> {
        return readMetadataAt(source.copyBytes(), 0)
    }

    internal fun readMetadataAt(bytes: ByteArray, directoryOffset: Int): FontOperationResult<ParsedTrueTypeFont> {
        if (directoryOffset < 0 || directoryOffset.toLong() + 12L > bytes.size) {
            return failure(FontError.InvalidFontData("SFNT header is truncated."))
        }

        val scalerType = readUInt32(bytes, directoryOffset) ?: return failure(FontError.OutOfBounds("Could not read scaler type.", FontDiagnosticLocation.Source))
        val containerTag = bytes.decodeAsciiTag(directoryOffset)
        val isTrueType = scalerType == 0x00010000u || containerTag == "true"
        val isOpenTypeCff = containerTag == "OTTO"
        if (!isTrueType && !isOpenTypeCff) {
            val message = when (containerTag) {
                "ttcf", "typ1" -> "Unsupported SFNT container: $containerTag"
                else -> "Unsupported SFNT container."
            }
            return failure(FontError.UnsupportedContainer(message))
        }

        val numTables = readUInt16(bytes, directoryOffset + 4) ?: return failure(FontError.OutOfBounds("Could not read table count.", FontDiagnosticLocation.Source))
        val directoryLength = numTables.toLong() * 16L
        checkedRangeEnd(directoryOffset.toLong() + 12L, directoryLength, bytes.size)
            ?: return failure(
                FontError.OutOfBounds("SFNT directory exceeds source length.", FontDiagnosticLocation.Source),
                FontDiagnosticData(
                    offset = directoryOffset.toLong() + 12L,
                    length = directoryLength,
                    observedValue = directoryOffset.toLong() + 12L + directoryLength,
                    limit = bytes.size.toLong(),
                ),
            )

        val records = LinkedHashMap<String, TableRecord>(numTables.toInt())
        val diagnostics = mutableListOf<FontDiagnostic>()
        var offset = directoryOffset + 12
        repeat(numTables.toInt()) {
            val tag = bytes.decodeAsciiTag(offset)
            val tableOffset = readUInt32(bytes, offset + 8)?.toLong()
                ?: return failure(FontError.OutOfBounds("Could not read table offset for $tag.", FontDiagnosticLocation.Table(tag)))
            val tableLength = readUInt32(bytes, offset + 12)?.toLong()
                ?: return failure(FontError.OutOfBounds("Could not read table length for $tag.", FontDiagnosticLocation.Table(tag)))

            if (tag in requiredTables && records.containsKey(tag)) {
                diagnostics += FontDiagnostic(
                    code = "font.sfnt.duplicate-table",
                    severity = FontDiagnosticSeverity.ERROR,
                    location = FontDiagnosticLocation.Table(tag),
                    message = "Duplicate required table: $tag",
                )
            } else {
                records[tag] = TableRecord(tag, tableOffset, tableLength)
            }
            offset += 16
        }
        if (diagnostics.isNotEmpty()) {
            return failure(FontError.InvalidFontData("Duplicate required tables detected."), diagnostics)
        }
        for (tag in requiredTables.sorted()) {
            val record = records[tag] ?: return failure(FontError.MissingRequiredTable(tag))
            if (record.length == 0L) {
                return failure(FontError.MissingRequiredTable(tag, "Required table $tag has zero length."))
            }
        }
        for (record in records.values) {
            if (checkedRangeEnd(record.offset, record.length, bytes.size) == null) {
                return failure(
                    FontError.OutOfBounds(
                        message = "Table ${record.tag} exceeds source length.",
                        location = FontDiagnosticLocation.Table(record.tag),
                    ),
                    FontDiagnosticData(
                        offset = record.offset,
                        length = record.length,
                        observedValue = record.offset + record.length,
                        limit = bytes.size.toLong(),
                    ),
                )
            }
        }

        val flavor = when {
            records.containsKey("CFF2") -> FontFlavor.CFF2
            records.containsKey("CFF ") -> FontFlavor.CFF
            else -> FontFlavor.TRUETYPE
        }
        if (isOpenTypeCff && flavor == FontFlavor.TRUETYPE) {
            return failure(FontError.UnsupportedContainer("Unsupported SFNT container: OTTO"))
        }
        val unitsPerEm = parseUnitsPerEm(bytes, records.getValue("head")) ?: return failure(
            FontError.InvalidFontData("head table is truncated.", FontDiagnosticLocation.Table("head")),
        )
        if (unitsPerEm !in 16..16_384) {
            return failure(
                FontError.InvalidFontData(
                    "head.unitsPerEm must be between 16 and 16384.",
                    FontDiagnosticLocation.Table("head"),
                ),
                FontDiagnosticData(observedValue = unitsPerEm.toLong()),
            )
        }
        val indexToLocFormat = if (flavor == FontFlavor.TRUETYPE) {
            val value = parseIndexToLocFormat(bytes, records.getValue("head")) ?: return failure(
                FontError.InvalidFontData("head table is truncated.", FontDiagnosticLocation.Table("head")),
            )
            if (value !in 0..1) {
                return failure(
                    FontError.InvalidFontData(
                        "head.indexToLocFormat must be 0 or 1.",
                        FontDiagnosticLocation.Table("head"),
                    ),
                    FontDiagnosticData(observedValue = value.toLong()),
                )
            }
            value
        } else {
            0
        }
        val maxpTable = slice(bytes, records.getValue("maxp")) ?: return failure(
            FontError.InvalidFontData("maxp table is outside the source.", FontDiagnosticLocation.Table("maxp")),
        )
        val minimumMaxpLength = if (flavor == FontFlavor.TRUETYPE) TRUE_TYPE_MAXP_LENGTH else CFF_MAXP_LENGTH
        if (maxpTable.size < minimumMaxpLength) {
            return failure(
                FontError.InvalidFontData("maxp table is truncated.", FontDiagnosticLocation.Table("maxp")),
                FontDiagnosticData(
                    length = maxpTable.size.toLong(),
                    observedValue = maxpTable.size.toLong(),
                    limit = minimumMaxpLength.toLong(),
                ),
            )
        }
        val maxpVersion = readUInt32(maxpTable, 0) ?: return failure(
            FontError.InvalidFontData("maxp version is truncated.", FontDiagnosticLocation.Table("maxp")),
        )
        val expectedMaxpVersion = if (flavor == FontFlavor.TRUETYPE) TRUE_TYPE_MAXP_VERSION else CFF_MAXP_VERSION
        if (maxpVersion != expectedMaxpVersion) {
            return failure(
                FontError.InvalidFontData(
                    if (flavor == FontFlavor.TRUETYPE) "TrueType maxp version 1.0 is required."
                    else "CFF maxp version 0.5 is required.",
                    FontDiagnosticLocation.Table("maxp"),
                ),
                FontDiagnosticData(observedValue = maxpVersion.toLong(), limit = expectedMaxpVersion.toLong()),
            )
        }
        val glyphCount = readUInt16(maxpTable, 4)?.toInt() ?: return failure(
            FontError.InvalidFontData("maxp.numGlyphs is truncated.", FontDiagnosticLocation.Table("maxp")),
        )
        if (glyphCount <= 0) {
            return failure(
                FontError.InvalidFontData("maxp.numGlyphs must be positive.", FontDiagnosticLocation.Table("maxp")),
                FontDiagnosticData(observedValue = glyphCount.toLong()),
            )
        }
        val names = parseNameTable(bytes, records.getValue("name")) ?: return failure(
            FontError.InvalidFontData("name table is invalid.", FontDiagnosticLocation.Table("name")),
        )

        return FontOperationResult.Success(
            ParsedTrueTypeFont(
                tableRecords = records,
                metadata = FontFaceMetadata(
                    familyName = names.familyName,
                    styleName = names.styleName,
                    unitsPerEm = unitsPerEm,
                    glyphCount = glyphCount,
                ),
                indexToLocFormat = indexToLocFormat,
                flavor = flavor,
            ),
        )
    }

    private fun parseUnitsPerEm(bytes: ByteArray, head: TableRecord): Int? {
        val table = slice(bytes, head) ?: return null
        return readUInt16(table, 18)?.toInt()
    }

    private fun parseIndexToLocFormat(bytes: ByteArray, head: TableRecord): Int? {
        val table = slice(bytes, head) ?: return null
        return readInt16(table, 50)
    }

    private fun parseNameTable(bytes: ByteArray, name: TableRecord): ParsedNames? {
        val table = slice(bytes, name) ?: return null
        val count = readUInt16(table, 2)?.toInt() ?: return null
        val stringOffset = readUInt16(table, 4)?.toInt() ?: return null
        val recordsEnd = checkedRangeEnd(6, count * 12, table.size) ?: return null
        if (stringOffset !in recordsEnd..table.size) {
            return null
        }

        var familyEnglish: String? = null
        var styleEnglish: String? = null
        var familyFallback: String? = null
        var styleFallback: String? = null
        var familyMacintoshEnglish: String? = null
        var styleMacintoshEnglish: String? = null
        var familyMacintoshFallback: String? = null
        var styleMacintoshFallback: String? = null

        var cursor = 6
        repeat(count) {
            val platformId = readUInt16(table, cursor)?.toInt() ?: return null
            val encodingId = readUInt16(table, cursor + 2)?.toInt() ?: return null
            val languageId = readUInt16(table, cursor + 4)?.toInt() ?: return null
            val nameId = readUInt16(table, cursor + 6)?.toInt() ?: return null
            val length = readUInt16(table, cursor + 8)?.toInt() ?: return null
            val offset = readUInt16(table, cursor + 10)?.toInt() ?: return null
            if (isUnicodeNameRecord(platformId, encodingId)) {
                val bytesStart = stringOffset + offset
                val bytesEnd = checkedRangeEnd(bytesStart, length, table.size) ?: return@repeat
                val value = decodeUtf16Be(table, bytesStart, bytesEnd)
                    ?.takeIf(String::isNotBlank)
                    ?: return@repeat
                if (nameId == 1) {
                    if (isEnglishUnicodeNameRecord(platformId, languageId)) {
                        familyEnglish = familyEnglish ?: value
                    }
                    familyFallback = familyFallback ?: value
                }
                if (nameId == 2) {
                    if (isEnglishUnicodeNameRecord(platformId, languageId)) {
                        styleEnglish = styleEnglish ?: value
                    }
                    styleFallback = styleFallback ?: value
                }
            } else if (isMacintoshRomanNameRecord(platformId, encodingId)) {
                // Apple system faces such as the macOS Menlo, Helvetica or Times collections
                // publish family and style names only as Macintosh Roman records; they remain a
                // legitimate last resort after every Unicode record.
                val bytesStart = stringOffset + offset
                val bytesEnd = checkedRangeEnd(bytesStart, length, table.size) ?: return@repeat
                val value = decodeMacintoshRoman(table, bytesStart, bytesEnd)
                    .takeIf(String::isNotBlank)
                    ?: return@repeat
                if (nameId == 1) {
                    if (languageId == 0) {
                        familyMacintoshEnglish = familyMacintoshEnglish ?: value
                    }
                    familyMacintoshFallback = familyMacintoshFallback ?: value
                }
                if (nameId == 2) {
                    if (languageId == 0) {
                        styleMacintoshEnglish = styleMacintoshEnglish ?: value
                    }
                    styleMacintoshFallback = styleMacintoshFallback ?: value
                }
            }
            cursor += 12
        }

        val familyName = familyEnglish ?: familyFallback ?: familyMacintoshEnglish ?: familyMacintoshFallback ?: return null
        val styleName = styleEnglish ?: styleFallback ?: styleMacintoshEnglish ?: styleMacintoshFallback ?: return null
        return ParsedNames(familyName, styleName)
    }

    private fun decodeUtf16Be(bytes: ByteArray, start: Int, end: Int): String? {
        if (((end - start) and 1) != 0) {
            return null
        }
        val chars = CharArray((end - start) / 2)
        var sourceIndex = start
        var targetIndex = 0
        while (sourceIndex < end) {
            val codeUnit = ((bytes[sourceIndex].toInt() and 0xFF) shl 8) or (bytes[sourceIndex + 1].toInt() and 0xFF)
            chars[targetIndex++] = codeUnit.toChar()
            sourceIndex += 2
        }
        return chars.concatToString()
    }

    private fun decodeMacintoshRoman(bytes: ByteArray, start: Int, end: Int): String {
        val chars = CharArray(end - start)
        for (index in chars.indices) {
            val value = bytes[start + index].toInt() and 0xFF
            chars[index] = if (value < 0x80) value.toChar() else MAC_ROMAN_HIGH_BYTES[value - 0x80]
        }
        return chars.concatToString()
    }

    private fun isUnicodeNameRecord(platformId: Int, encodingId: Int): Boolean =
        when (platformId) {
            0 -> encodingId in 0..6
            3 -> encodingId == 1 || encodingId == 10
            else -> false
        }

    private fun isMacintoshRomanNameRecord(platformId: Int, encodingId: Int): Boolean =
        platformId == 1 && encodingId == 0

    private fun isEnglishUnicodeNameRecord(platformId: Int, languageId: Int): Boolean =
        when (platformId) {
            0 -> languageId == 0
            3 -> languageId == 0x0409
            else -> false
        }

    private fun failure(error: FontError, diagnostics: List<FontDiagnostic> = listOf(error.toDiagnostic())): FontOperationResult.Failure =
        FontOperationResult.Failure(error, diagnostics.sortedDiagnostics())

    private fun failure(error: FontError, data: FontDiagnosticData): FontOperationResult.Failure =
        failure(error, listOf(error.toDiagnostic(data)))
}

/**
 * Immutable structural representation of a parsed TrueType font.
 *
 * This value contains table locations and validated face metadata, not a
 * public mutable view of the source bytes. It may be copied for value-like
 * transformations and shared between threads. It does not own the source
 * buffer; a scaler or asset owner decides how long the corresponding bytes
 * and decoded tables remain retained.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public class ParsedTrueTypeFont(
    /** Validated table locations keyed by their four-byte SFNT tags. */
    tableRecords: Map<String, TableRecord>,
    /** Face metadata read from the font tables. */
    public val metadata: FontFaceMetadata,
    /** `head.indexToLocFormat` used to decode the `loca` table; `0` for CFF flavours. */
    public val indexToLocFormat: Int,
    /** Outline flavour that selects the decoder; defaults to TrueType. */
    public val flavor: FontFlavor = FontFlavor.TRUETYPE,
) {
    /** Immutable map of SFNT table records keyed by tag. */
    public val tableRecords: Map<String, TableRecord> = ImmutableSnapshotMap(tableRecords)

    /**
     * Whether the face carries a `VARC` variable-composite table.
     *
     * `VARC` is out of scope for the portable outline route, where a non-default instance would
     * otherwise render the static `glyf` composite silently. The flag is derived from the validated
     * table directory, so it costs one map lookup and never rescans a glyph.
     */
    public val hasVarcTable: Boolean
        get() = tableRecords.containsKey("VARC")

    /** Returns the table records for destructuring. */
    public operator fun component1(): Map<String, TableRecord> = tableRecords

    /** Returns the face metadata for destructuring. */
    public operator fun component2(): FontFaceMetadata = metadata

    /** Returns the location format for destructuring. */
    public operator fun component3(): Int = indexToLocFormat

    /** Returns the outline flavour for destructuring. */
    public operator fun component4(): FontFlavor = flavor

    /** Copies this parsed font with selected fields changed. */
    public fun copy(
        tableRecords: Map<String, TableRecord> = this.tableRecords,
        metadata: FontFaceMetadata = this.metadata,
        indexToLocFormat: Int = this.indexToLocFormat,
        flavor: FontFlavor = this.flavor,
    ): ParsedTrueTypeFont = ParsedTrueTypeFont(tableRecords, metadata, indexToLocFormat, flavor)

    /** Compares table records, metadata, location format, and flavour. */
    override fun equals(other: Any?): Boolean =
        this === other || other is ParsedTrueTypeFont &&
            tableRecords == other.tableRecords &&
            metadata == other.metadata &&
            indexToLocFormat == other.indexToLocFormat &&
            flavor == other.flavor

    /** Returns a hash derived from the parsed table structure. */
    override fun hashCode(): Int {
        var result = tableRecords.hashCode()
        result = 31 * result + metadata.hashCode()
        result = 31 * result + indexToLocFormat
        result = 31 * result + flavor.hashCode()
        return result
    }

    /** Returns a diagnostic representation of the parsed font structure. */
    override fun toString(): String =
        "ParsedTrueTypeFont(tableRecords=$tableRecords, metadata=$metadata, indexToLocFormat=$indexToLocFormat, flavor=$flavor)"
}

/**
 * Location and size of one SFNT table in the source bytes.
 *
 * Records are immutable values. Offsets and lengths are expressed in bytes
 * and are validated against the source only when consumed by [slice] or the
 * metadata reader.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public data class TableRecord(
    /** Four-character table tag. */
    public val tag: String,
    /** Absolute byte offset in the source. */
    public val offset: Long,
    /** Table length in bytes. */
    public val length: Long,
)

private data class ParsedNames(
    val familyName: String,
    val styleName: String,
)

/**
 * Returns a defensive copy of a table's bytes, or `null` for an invalid range.
 *
 * Hot paths should retain one validated snapshot instead of repeatedly
 * copying the same table. The returned array belongs to the caller and can be
 * mutated without changing [bytes].
 *
 * @param bytes source buffer to read without mutation.
 * @param record table range to copy.
 * @return an independent table copy, or `null` when the range overflows or is
 * outside [bytes].
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public fun slice(bytes: ByteArray, record: TableRecord): ByteArray? {
    val end = checkedRangeEnd(record.offset, record.length, bytes.size) ?: return null
    return bytes.copyOfRange(record.offset.toInt(), end)
}

/**
 * Safely computes an exclusive range end for 32-bit range values.
 *
 * @return the exclusive end when the non-negative range fits [sourceSize], or
 * `null` on overflow or out-of-bounds input.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public fun checkedRangeEnd(offset: Int, length: Int, sourceSize: Int): Int? {
    return checkedRangeEnd(offset.toLong(), length.toLong(), sourceSize)
}

/**
 * Safely computes an exclusive range end without overflow.
 *
 * @param offset non-negative start offset.
 * @param length non-negative range length.
 * @param sourceSize available source size.
 * @return the exclusive end, or `null` when the range is invalid.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public fun checkedRangeEnd(offset: Long, length: Long, sourceSize: Int): Int? {
    if (offset < 0L || length < 0L || offset > sourceSize.toLong()) {
        return null
    }
    if (offset > Long.MAX_VALUE - length) {
        return null
    }
    val end = offset + length
    if (end > sourceSize.toLong()) {
        return null
    }
    return end.toInt()
}

/**
 * Reads one big-endian unsigned 16-bit value, or `null` if truncated.
 *
 * @param bytes source buffer, which is not modified.
 * @param offset byte offset of the value.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public fun readUInt16(bytes: ByteArray, offset: Int): UInt? {
    checkedRangeEnd(offset.toLong(), 2L, bytes.size) ?: return null
    return (((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)).toUInt()
}

/** Reads one big-endian signed 16-bit value, or `null` if truncated. */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public fun readInt16(bytes: ByteArray, offset: Int): Int? = readUInt16(bytes, offset)?.toShort()?.toInt()

/**
 * Reads one big-endian unsigned 32-bit value, or `null` if truncated.
 *
 * The result uses Kotlin's unsigned integer type and never throws for an
 * invalid offset.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public fun readUInt32(bytes: ByteArray, offset: Int): UInt? {
    checkedRangeEnd(offset.toLong(), 4L, bytes.size) ?: return null
    return (((bytes[offset].toUInt() and 0xFFu) shl 24) or
        ((bytes[offset + 1].toUInt() and 0xFFu) shl 16) or
        ((bytes[offset + 2].toUInt() and 0xFFu) shl 8) or
        (bytes[offset + 3].toUInt() and 0xFFu))
}

/**
 * Decodes four bytes as an ASCII SFNT tag, or returns an empty string if
 * truncated. The receiver is read-only; non-ASCII byte values are preserved
 * as one code unit rather than normalized.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public fun ByteArray.decodeAsciiTag(offset: Int): String {
    checkedRangeEnd(offset.toLong(), 4L, size) ?: return ""
    return CharArray(4) { index -> (this[offset + index].toInt() and 0xFF).toChar() }.concatToString()
}

private class ImmutableSnapshotMap<Key, Value>(source: Map<Key, Value>) : AbstractMutableMap<Key, Value>() {
    private val snapshotEntries = source.entries.map { entry -> ImmutableEntry(entry.key, entry.value) }

    override val entries: MutableSet<MutableMap.MutableEntry<Key, Value>> = ImmutableEntrySet(snapshotEntries)

    override fun put(key: Key, value: Value): Value? = immutableMutation()

    private fun <Result> immutableMutation(): Result =
        throw UnsupportedOperationException("Immutable map snapshot.")
}

private class ImmutableEntry<Key, Value>(
    override val key: Key,
    override val value: Value,
) : MutableMap.MutableEntry<Key, Value> {
    override fun setValue(newValue: Value): Value =
        throw UnsupportedOperationException("Immutable map entry.")

    override fun equals(other: Any?): Boolean =
        other is Map.Entry<*, *> && key == other.key && value == other.value

    override fun hashCode(): Int = (key?.hashCode() ?: 0) xor (value?.hashCode() ?: 0)
}

private class ImmutableEntrySet<Element>(source: List<Element>) : AbstractMutableSet<Element>() {
    private val elements = source.toList()

    override val size: Int
        get() = elements.size

    override fun add(element: Element): Boolean = immutableMutation()

    override fun iterator(): MutableIterator<Element> = object : MutableIterator<Element> {
        private var index = 0

        override fun hasNext(): Boolean = index < elements.size

        override fun next(): Element = elements[index++]

        override fun remove(): Unit = immutableMutation()
    }

    private fun <Result> immutableMutation(): Result =
        throw UnsupportedOperationException("Immutable entry set.")
}

/** Macintosh Roman characters for the byte values 0x80 through 0xFF. */
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


private const val TRUE_TYPE_MAXP_LENGTH = 32
private const val TRUE_TYPE_MAXP_VERSION: UInt = 0x00010000u
private const val CFF_MAXP_LENGTH = 6
private const val CFF_MAXP_VERSION: UInt = 0x00005000u

/**
 * Outline flavour of a parsed SFNT face.
 *
 * A TrueType face carries `glyf`/`loca` quadratic outlines; a CFF face carries a
 * `CFF ` Type 2 charstring table; a CFF2 face carries a `CFF2` table with
 * variation data. The flavour selects the outline decoder, never the shared
 * `cmap`/`hmtx`/`name`/`head` metadata.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public enum class FontFlavor {
    /** TrueType `glyf`/`loca` quadratic outlines. */
    TRUETYPE,

    /** OpenType CFF1 (`CFF `) cubic charstring outlines. */
    CFF,

    /** OpenType CFF2 cubic charstring outlines with variation data. */
    CFF2,
}

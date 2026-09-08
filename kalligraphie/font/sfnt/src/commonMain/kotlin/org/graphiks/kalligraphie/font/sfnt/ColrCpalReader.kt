package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphId

/** Limits applied before decoding a COLR version 0 and CPAL version 0 table pair. */
public data class ColrCpalV0Limits(
    /** Maximum CPAL palettes retained by one decoded table pair. */
    public val maxPalettes: Int,
    /** Maximum colors in every retained CPAL palette. */
    public val maxPaletteEntries: Int,
    /** Maximum CPAL color records decoded from the source. */
    public val maxColorRecords: Int,
    /** Maximum bytes retained by expanded CPAL palettes after source record sharing is resolved. */
    public val maxDecodedPaletteBytes: Int = maxColorRecords.coerceAtMost(Int.MAX_VALUE / 4) * 4,
    /** Maximum COLR base-glyph records decoded from the source. */
    public val maxBaseGlyphRecords: Int,
    /** Maximum COLR layer records decoded from the source. */
    public val maxLayerRecords: Int,
) {
    init {
        require(maxPalettes > 0) { "maxPalettes must be positive." }
        require(maxPaletteEntries > 0) { "maxPaletteEntries must be positive." }
        require(maxColorRecords > 0) { "maxColorRecords must be positive." }
        require(maxDecodedPaletteBytes > 0) { "maxDecodedPaletteBytes must be positive." }
        require(maxBaseGlyphRecords > 0) { "maxBaseGlyphRecords must be positive." }
        require(maxLayerRecords > 0) { "maxLayerRecords must be positive." }
    }

    /** Conservative limits for direct, bounded OpenType table decoding. */
    public companion object {
        /** Default limits used unless a render asset supplies stricter bounds. */
        public val default: ColrCpalV0Limits = ColrCpalV0Limits(
            maxPalettes = 256,
            maxPaletteEntries = 4_096,
            maxColorRecords = 65_536,
            maxDecodedPaletteBytes = 262_144,
            maxBaseGlyphRecords = 65_536,
            maxLayerRecords = 65_536,
        )
    }
}

/** One COLR version 0 layer, in source paint order. */
public data class ColrV0Layer(
    /** Glyph painted by this layer. */
    public val glyphId: GlyphId,
    /** CPAL entry index, or [foregroundColorIndex] for the selected foreground color. */
    public val paletteIndex: Int,
) {
    init {
        require(paletteIndex in 0..0xFFFF) { "paletteIndex must be an unsigned 16-bit value." }
    }

    /** COLR version 0 palette sentinel denoting the render variant's foreground color. */
    public companion object {
        /** Value used in COLR layer records for the selected foreground color. */
        public const val foregroundColorIndex: Int = 0xFFFF
    }
}

/**
 * Fully validated, portable interpretation of one COLR version 0 and CPAL version 0 table pair.
 *
 * The value contains no source buffer and no platform handle. It exposes palette colors and layer
 * records only after every table range, palette reference, and base glyph range has been checked.
 * Every returned list is a fresh caller-owned snapshot, so concurrent consumers cannot mutate the
 * decoded state held by the font asset.
 */
public class ColrCpalV0Data internal constructor(
    palettes: List<List<GlyphColor>>,
    layersByGlyph: Map<GlyphId, List<ColrV0Layer>>,
) {
    private val capturedPalettes: List<List<GlyphColor>> = palettes.map { palette -> palette.toList() }.toList()
    private val capturedLayersByGlyph: Map<GlyphId, List<ColrV0Layer>> = layersByGlyph.mapValues { (_, layers) -> layers.toList() }.toMap()

    /** Number of colors in every decoded CPAL palette. */
    public val paletteEntryCount: Int = capturedPalettes.firstOrNull()?.size ?: 0

    /** Number of CPAL palettes captured from the source table. */
    public val paletteCount: Int = capturedPalettes.size

    /** Returns one palette in CPAL entry order as a caller-owned snapshot. */
    public fun palette(index: Int): List<GlyphColor> = capturedPalettes.getOrNull(index)?.toList()
        ?: throw IllegalArgumentException("CPAL palette index $index is unavailable.")

    /** Returns the COLR layers for [glyphId], or an empty snapshot when the glyph has no color layers. */
    public fun layersFor(glyphId: GlyphId): List<ColrV0Layer> = capturedLayersByGlyph[glyphId]?.toList() ?: emptyList()
}

/**
 * Decodes exactly CPAL version 0 and COLR version 0 into portable palette and layer records.
 *
 * Later COLR versions, gradients, transforms, clips, composite modes, SVG, bitmap tables, and
 * native rendering are deliberately unsupported by this reader. Malformed tables and any layer
 * that cannot be resolved against the complete CPAL palette domain fail before a glyph route is
 * made available.
 */
public object ColrCpalReader {
    /**
     * Returns whether both tables declare the only versions this reader implements.
     *
     * This inexpensive header check is not sufficient to publish a face capability because it
     * does not validate offsets or palette references. Use
     * [hasStructurallyValidVersionZeroTables] when publishing a capability; [read] still applies
     * the consumer's stricter resource limits before an asset is acquired.
     */
    public fun hasSupportedVersionZeroHeaders(
        colrTable: ByteArray,
        cpalTable: ByteArray,
    ): Boolean =
        colrTable.size >= COLR_V0_HEADER_LENGTH &&
            cpalTable.size >= CPAL_V0_HEADER_LENGTH &&
            readUInt16(colrTable, 0)?.toInt() == 0 &&
            readUInt16(cpalTable, 0)?.toInt() == 0

    /**
     * Returns whether the complete COLR/CPAL version 0 table pair is structurally readable.
     *
     * This validation checks every offset, palette and layer reference without retaining decoded
     * palettes or paint records. Catalogues use it before publishing a paint capability; [read]
     * still applies the consumer's stricter resource limits before an asset is acquired.
     *
     * @param colrTable exact bytes of the OpenType `COLR` table.
     * @param cpalTable exact bytes of the OpenType `CPAL` table.
     * @param glyphCount optional face glyph count used to reject invalid COLR glyph references.
     */
    public fun hasStructurallyValidVersionZeroTables(
        colrTable: ByteArray,
        cpalTable: ByteArray,
        glyphCount: Int? = null,
    ): Boolean {
        if (glyphCount != null && glyphCount <= 0) return false
        val paletteEntryCount = validateCpalStructure(cpalTable) ?: return false
        return validateColrStructure(colrTable, paletteEntryCount, glyphCount)
    }

    /**
     * Parses one COLR/CPAL version 0 pair with [limits].
     *
     * @param colrTable exact bytes of the OpenType `COLR` table.
     * @param cpalTable exact bytes of the OpenType `CPAL` table.
     * @param limits resource bounds enforced before allocating decoded records.
     * @param glyphCount optional face glyph count used to reject invalid COLR glyph references.
     * @return complete portable data or a typed unsupported-version, malformed-data, or limit failure.
     */
    public fun read(
        colrTable: ByteArray,
        cpalTable: ByteArray,
        limits: ColrCpalV0Limits = ColrCpalV0Limits.default,
        glyphCount: Int? = null,
    ): FontOperationResult<ColrCpalV0Data> {
        if (glyphCount != null && glyphCount <= 0) {
            return invalid("font.colr.invalid-glyph-count", "COLR glyph count must be positive.", "COLR")
        }
        val palettes = when (val parsed = readCpal(cpalTable, limits)) {
            is FontOperationResult.Success -> parsed.value
            is FontOperationResult.Failure -> return parsed
            is FontOperationResult.Cancelled -> return parsed
        }
        val layersByGlyph = when (val parsed = readColr(colrTable, palettes.first().size, limits, glyphCount)) {
            is FontOperationResult.Success -> parsed.value
            is FontOperationResult.Failure -> return parsed
            is FontOperationResult.Cancelled -> return parsed
        }
        return FontOperationResult.Success(ColrCpalV0Data(palettes, layersByGlyph))
    }

    private fun readCpal(
        table: ByteArray,
        limits: ColrCpalV0Limits,
    ): FontOperationResult<List<List<GlyphColor>>> {
        if (table.size < CPAL_V0_HEADER_LENGTH) return invalid("font.cpal.truncated", "CPAL version 0 header is truncated.", "CPAL")
        val version = readUInt16(table, 0)?.toInt() ?: return invalid("font.cpal.truncated", "CPAL version is truncated.", "CPAL")
        if (version != 0) return invalid("font.cpal.unsupported-version", "Only CPAL version 0 is supported.", "CPAL")
        val entryCount = readUInt16(table, 2)?.toInt() ?: return invalid("font.cpal.truncated", "CPAL entry count is truncated.", "CPAL")
        val paletteCount = readUInt16(table, 4)?.toInt() ?: return invalid("font.cpal.truncated", "CPAL palette count is truncated.", "CPAL")
        val colorRecordCount = readUInt16(table, 6)?.toInt() ?: return invalid("font.cpal.truncated", "CPAL color-record count is truncated.", "CPAL")
        val colorRecordsOffset = readUInt32(table, 8)?.toLong() ?: return invalid("font.cpal.truncated", "CPAL color-record offset is truncated.", "CPAL")
        if (entryCount == 0 || paletteCount == 0) return invalid("font.cpal.invalid-table", "CPAL must contain at least one non-empty palette.", "CPAL")
        limit(entryCount, limits.maxPaletteEntries, "CPAL palette entry limit exceeded.", "CPAL")?.let { return it }
        limit(paletteCount, limits.maxPalettes, "CPAL palette limit exceeded.", "CPAL")?.let { return it }
        limit(colorRecordCount, limits.maxColorRecords, "CPAL color-record limit exceeded.", "CPAL")?.let { return it }
        val decodedPaletteBytes = paletteCount.toLong() * entryCount.toLong() * COLOR_RECORD_LENGTH
        if (decodedPaletteBytes > limits.maxDecodedPaletteBytes.toLong()) {
            return FontOperationResult.Failure(
                FontError.ResourceLimitExceeded("CPAL decoded-palette byte limit exceeded.", FontDiagnosticLocation.Table("CPAL")),
            )
        }

        val paletteIndicesEnd = checkedRangeEnd(CPAL_V0_HEADER_LENGTH, paletteCount * 2, table.size)
            ?: return invalid("font.cpal.truncated", "CPAL palette indices are truncated.", "CPAL")
        val colorRecordsEnd = checkedRangeEnd(colorRecordsOffset, colorRecordCount.toLong() * COLOR_RECORD_LENGTH, table.size)
            ?: return invalid("font.cpal.truncated", "CPAL color records are truncated.", "CPAL")
        if (colorRecordsEnd < paletteIndicesEnd) return invalid("font.cpal.invalid-table", "CPAL color records overlap the palette-index header.", "CPAL")

        val palettes = ArrayList<List<GlyphColor>>(paletteCount)
        repeat(paletteCount) { paletteIndex ->
            val firstColorRecord = readUInt16(table, CPAL_V0_HEADER_LENGTH + paletteIndex * 2)?.toInt()
                ?: return invalid("font.cpal.truncated", "CPAL palette index is truncated.", "CPAL")
            if (firstColorRecord > colorRecordCount || entryCount > colorRecordCount - firstColorRecord) {
                return invalid("font.cpal.invalid-palette-index", "CPAL palette references unavailable color records.", "CPAL")
            }
            val colors = ArrayList<GlyphColor>(entryCount)
            repeat(entryCount) { entryIndex ->
                val offset = colorRecordsOffset + (firstColorRecord + entryIndex).toLong() * COLOR_RECORD_LENGTH
                colors += GlyphColor(
                    red = table[offset.toInt() + 2].toInt() and 0xFF,
                    green = table[offset.toInt() + 1].toInt() and 0xFF,
                    blue = table[offset.toInt()].toInt() and 0xFF,
                    alpha = table[offset.toInt() + 3].toInt() and 0xFF,
                )
            }
            palettes += colors
        }
        return FontOperationResult.Success(palettes)
    }

    private fun validateCpalStructure(table: ByteArray): Int? {
        if (table.size < CPAL_V0_HEADER_LENGTH) return null
        if (readUInt16(table, 0)?.toInt() != 0) return null
        val entryCount = readUInt16(table, 2)?.toInt() ?: return null
        val paletteCount = readUInt16(table, 4)?.toInt() ?: return null
        val colorRecordCount = readUInt16(table, 6)?.toInt() ?: return null
        val colorRecordsOffset = readUInt32(table, 8)?.toLong() ?: return null
        if (entryCount == 0 || paletteCount == 0) return null
        val paletteIndicesEnd = checkedRangeEnd(CPAL_V0_HEADER_LENGTH, paletteCount * 2, table.size) ?: return null
        val colorRecordsEnd = checkedRangeEnd(colorRecordsOffset, colorRecordCount.toLong() * COLOR_RECORD_LENGTH, table.size) ?: return null
        if (colorRecordsEnd < paletteIndicesEnd) return null
        repeat(paletteCount) { paletteIndex ->
            val firstColorRecord = readUInt16(table, CPAL_V0_HEADER_LENGTH + paletteIndex * 2)?.toInt() ?: return null
            if (firstColorRecord > colorRecordCount || entryCount > colorRecordCount - firstColorRecord) return null
        }
        return entryCount
    }

    private fun readColr(
        table: ByteArray,
        paletteEntryCount: Int,
        limits: ColrCpalV0Limits,
        glyphCount: Int?,
    ): FontOperationResult<Map<GlyphId, List<ColrV0Layer>>> {
        if (table.size < COLR_V0_HEADER_LENGTH) return invalid("font.colr.truncated", "COLR version 0 header is truncated.", "COLR")
        val version = readUInt16(table, 0)?.toInt() ?: return invalid("font.colr.truncated", "COLR version is truncated.", "COLR")
        if (version != 0) return invalid("font.colr.unsupported-version", "Only COLR version 0 is supported.", "COLR")
        val baseGlyphCount = readUInt16(table, 2)?.toInt() ?: return invalid("font.colr.truncated", "COLR base-glyph count is truncated.", "COLR")
        val baseGlyphOffset = readUInt32(table, 4)?.toLong() ?: return invalid("font.colr.truncated", "COLR base-glyph offset is truncated.", "COLR")
        val layerOffset = readUInt32(table, 8)?.toLong() ?: return invalid("font.colr.truncated", "COLR layer offset is truncated.", "COLR")
        val layerCount = readUInt16(table, 12)?.toInt() ?: return invalid("font.colr.truncated", "COLR layer count is truncated.", "COLR")
        limit(baseGlyphCount, limits.maxBaseGlyphRecords, "COLR base-glyph record limit exceeded.", "COLR")?.let { return it }
        limit(layerCount, limits.maxLayerRecords, "COLR layer-record limit exceeded.", "COLR")?.let { return it }
        checkedRangeEnd(baseGlyphOffset, baseGlyphCount.toLong() * BASE_GLYPH_RECORD_LENGTH, table.size)
            ?: return invalid("font.colr.truncated", "COLR base-glyph records are truncated.", "COLR")
        checkedRangeEnd(layerOffset, layerCount.toLong() * LAYER_RECORD_LENGTH, table.size)
            ?: return invalid("font.colr.truncated", "COLR layer records are truncated.", "COLR")

        val layers = ArrayList<ColrV0Layer>(layerCount)
        repeat(layerCount) { layerIndex ->
            val offset = layerOffset + layerIndex.toLong() * LAYER_RECORD_LENGTH
            val glyphId = readUInt16(table, offset.toInt())?.toInt()
                ?: return invalid("font.colr.truncated", "COLR layer glyph is truncated.", "COLR")
            if (glyphCount != null && glyphId !in 0 until glyphCount) {
                return invalid("font.colr.invalid-glyph-reference", "COLR layer references a glyph outside the face.", "COLR")
            }
            val paletteIndex = readUInt16(table, offset.toInt() + 2)?.toInt()
                ?: return invalid("font.colr.truncated", "COLR layer palette index is truncated.", "COLR")
            if (paletteIndex != ColrV0Layer.foregroundColorIndex && paletteIndex !in 0 until paletteEntryCount) {
                return invalid("font.colr.invalid-palette-index", "COLR layer references an unavailable CPAL entry.", "COLR")
            }
            layers += ColrV0Layer(GlyphId(glyphId), paletteIndex)
        }

        val layersByGlyph = LinkedHashMap<GlyphId, List<ColrV0Layer>>(baseGlyphCount)
        repeat(baseGlyphCount) { recordIndex ->
            val offset = baseGlyphOffset + recordIndex.toLong() * BASE_GLYPH_RECORD_LENGTH
            val glyphId = readUInt16(table, offset.toInt())?.toInt()
                ?: return invalid("font.colr.truncated", "COLR base glyph is truncated.", "COLR")
            if (glyphCount != null && glyphId !in 0 until glyphCount) {
                return invalid("font.colr.invalid-glyph-reference", "COLR base record references a glyph outside the face.", "COLR")
            }
            val firstLayer = readUInt16(table, offset.toInt() + 2)?.toInt()
                ?: return invalid("font.colr.truncated", "COLR first layer index is truncated.", "COLR")
            val layerCountForGlyph = readUInt16(table, offset.toInt() + 4)?.toInt()
                ?: return invalid("font.colr.truncated", "COLR layer count is truncated.", "COLR")
            if (layerCountForGlyph == 0 || firstLayer > layers.size || layerCountForGlyph > layers.size - firstLayer) {
                return invalid("font.colr.invalid-layer-range", "COLR base glyph references an unavailable layer range.", "COLR")
            }
            val key = GlyphId(glyphId)
            if (layersByGlyph.containsKey(key)) return invalid("font.colr.duplicate-base-glyph", "COLR contains duplicate base-glyph records.", "COLR")
            layersByGlyph[key] = layers.subList(firstLayer, firstLayer + layerCountForGlyph).toList()
        }
        return FontOperationResult.Success(layersByGlyph)
    }

    private fun validateColrStructure(
        table: ByteArray,
        paletteEntryCount: Int,
        glyphCount: Int?,
    ): Boolean {
        if (table.size < COLR_V0_HEADER_LENGTH) return false
        if (readUInt16(table, 0)?.toInt() != 0) return false
        val baseGlyphCount = readUInt16(table, 2)?.toInt() ?: return false
        val baseGlyphOffset = readUInt32(table, 4)?.toLong() ?: return false
        val layerOffset = readUInt32(table, 8)?.toLong() ?: return false
        val layerCount = readUInt16(table, 12)?.toInt() ?: return false
        if (checkedRangeEnd(baseGlyphOffset, baseGlyphCount.toLong() * BASE_GLYPH_RECORD_LENGTH, table.size) == null) return false
        if (checkedRangeEnd(layerOffset, layerCount.toLong() * LAYER_RECORD_LENGTH, table.size) == null) return false

        repeat(layerCount) { layerIndex ->
            val offset = (layerOffset + layerIndex.toLong() * LAYER_RECORD_LENGTH).toInt()
            val glyphId = readUInt16(table, offset)?.toInt() ?: return false
            val paletteIndex = readUInt16(table, offset + 2)?.toInt() ?: return false
            if (glyphCount != null && glyphId !in 0 until glyphCount) return false
            if (paletteIndex != ColrV0Layer.foregroundColorIndex && paletteIndex !in 0 until paletteEntryCount) return false
        }

        val baseGlyphIds = HashSet<Int>(baseGlyphCount)
        repeat(baseGlyphCount) { recordIndex ->
            val offset = (baseGlyphOffset + recordIndex.toLong() * BASE_GLYPH_RECORD_LENGTH).toInt()
            val glyphId = readUInt16(table, offset)?.toInt() ?: return false
            val firstLayer = readUInt16(table, offset + 2)?.toInt() ?: return false
            val layerCountForGlyph = readUInt16(table, offset + 4)?.toInt() ?: return false
            if (glyphCount != null && glyphId !in 0 until glyphCount) return false
            if (layerCountForGlyph == 0 || firstLayer > layerCount || layerCountForGlyph > layerCount - firstLayer) return false
            if (!baseGlyphIds.add(glyphId)) return false
        }
        return true
    }

    private fun limit(observed: Int, maximum: Int, message: String, tag: String): FontOperationResult.Failure? =
        if (observed > maximum) {
            FontOperationResult.Failure(FontError.ResourceLimitExceeded(message, FontDiagnosticLocation.Table(tag)))
        } else {
            null
        }

    private fun invalid(code: String, message: String, tag: String): FontOperationResult.Failure =
        FontOperationResult.Failure(FontError.FontDataFailure(code, message, FontDiagnosticLocation.Table(tag)))
}

private const val CPAL_V0_HEADER_LENGTH = 12
private const val COLR_V0_HEADER_LENGTH = 14
private const val COLOR_RECORD_LENGTH = 4L
private const val BASE_GLYPH_RECORD_LENGTH = 6L
private const val LAYER_RECORD_LENGTH = 4L

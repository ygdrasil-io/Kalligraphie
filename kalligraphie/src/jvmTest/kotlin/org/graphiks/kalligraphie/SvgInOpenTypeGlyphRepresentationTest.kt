package org.graphiks.kalligraphie

import java.util.Base64
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontMaterializationCachePolicy
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintNodeKind
import org.graphiks.kalligraphie.api.GlyphPaintPathCommand
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.PaintGraphLimits
import org.graphiks.kalligraphie.api.PaintGraphProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SvgInOpenTypeGlyphRepresentationTest {
    @Test
    fun normalizesTheVersionedSvgInOpenTypeGlyphIntoPortableCubicPaths() {
        val catalog = success(
            Kalligraphie.embedded(
                fixtureBytes(),
                FontSourceProvenance("TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf"),
                FontMaterializationCachePolicy(maxEvictableBytesPerFace = 10_000),
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val resolver = success(catalog.openAssetResolver())
        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val paint = assertIs<GlyphRepresentation.Paint>(success(asset.resolveGlyph(FontGlyphRequest(GlyphId(1))))).paint
                val warmPaint = assertIs<GlyphRepresentation.Paint>(success(asset.resolveGlyph(FontGlyphRequest(GlyphId(1))))).paint
                repeat(5) { index ->
                    val pressureRequirements = FontAccessRequirementsSnapshot.renderable(
                        listOf(paintProfile(maxSourceBytes = 16 * 1024 + index + 1)),
                    )
                    val pressureAsset = success(
                        instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, pressureRequirements),
                    )
                    try {
                        assertIs<GlyphRepresentation.Paint>(success(pressureAsset.resolveGlyph(FontGlyphRequest(GlyphId(1)))))
                    } finally {
                        pressureAsset.close()
                    }
                }
                val afterPressurePaint = assertIs<GlyphRepresentation.Paint>(success(asset.resolveGlyph(FontGlyphRequest(GlyphId(1))))).paint

                assertTrue(catalog.faces.single().capabilities.paintGraph)
                assertEquals(paint, warmPaint)
                assertEquals(paint, afterPressurePaint)
                assertEquals(1, paint.schemaVersion)
                assertEquals(2, paint.rootNode)
                assertEquals(3, paint.nodes.size)
                assertEquals(GlyphPaintNode.Group(listOf(0, 1)), paint.nodes[2])
                val firstPath = assertIs<GlyphPaintNode.Path>(paint.nodes[0])
                val secondPath = assertIs<GlyphPaintNode.Path>(paint.nodes[1])
                assertEquals(GlyphColor(49, 55, 61), firstPath.color)
                assertEquals(GlyphColor(49, 55, 61), secondPath.color)

                val move = assertIs<GlyphPaintPathCommand.MoveTo>(firstPath.path.commands.first())
                assertEquals(18.0 * 56.888888888888886, move.x, absoluteTolerance = 0.000_000_1)
                assertEquals(-6.75 - 1638.4, move.y, absoluteTolerance = 0.000_000_1)
                assertIs<GlyphPaintPathCommand.CubicTo>(firstPath.path.commands[1])
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun materializesAnInkBearingGlyphOutsideTheSvgRangeFromItsTrueTypeOutline() {
        val catalog = success(
            Kalligraphie.embedded(
                liberationSansWithAnAuditedSvgTable(),
                FontSourceProvenance("LiberationSans-Regular.ttf with audited SVG-in-OpenType table"),
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfileWithOutlineFallback()))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val resolver = success(catalog.openAssetResolver())
        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val paint = assertIs<GlyphRepresentation.Paint>(success(asset.resolveGlyph(FontGlyphRequest(GlyphId(36))))).paint

                assertEquals(1, paint.nodes.size)
                val outline = assertIs<GlyphPaintNode.SolidOutline>(paint.nodes.single())
                assertEquals(GlyphColor(0, 0, 0), outline.color)
                assertEquals(36, outline.outline.glyphId)
                assertEquals(2, outline.outline.contours.size)
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun refusesAnOutOfRangeSvgGlyphWhenTheProfileCannotRepresentItsOutlineFallback() {
        val catalog = success(
            Kalligraphie.embedded(
                liberationSansWithAnAuditedSvgTable(),
                FontSourceProvenance("LiberationSans-Regular.ttf with audited SVG-in-OpenType table"),
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val resolver = success(catalog.openAssetResolver())
        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val failure = assertIs<FontOperationResult.Failure>(asset.resolveGlyph(FontGlyphRequest(GlyphId(36))))

                assertIs<FontError.UnsupportedRepresentationProfile>(failure.error)
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun selectsTheFirstProfileThatCanCertifyTheCompleteSvgRoute() {
        val catalog = success(
            Kalligraphie.embedded(
                fixtureBytes(),
                FontSourceProvenance("TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf"),
            ),
        )
        val incompatible = PaintGraphProfile(
            acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE),
            acceptedCompositionModes = listOf(org.graphiks.kalligraphie.api.GlyphPaintCompositionMode.SOURCE_OVER),
            limits = PaintGraphLimits(maxNodes = 4, maxReferences = 4, maxDepth = 2, maxSourceBytes = 16 * 1024),
            outlineProfile = OutlineProfile(
                maxBytes = 16 * 1024,
                maxContours = 8,
                maxPoints = 64,
                maxCompositeDepth = 1,
                maxCompositeComponents = 1,
            ),
        )
        val compatible = paintProfile()
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(incompatible, compatible))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val resolver = success(catalog.openAssetResolver())
        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                assertEquals(compatible, asset.key.representationProfile)
                assertIs<GlyphRepresentation.Paint>(success(asset.resolveGlyph(FontGlyphRequest(GlyphId(1)))))
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun reportsTheExactSvgTableTagWhenTheProfileSourceLimitRejectsTheRoute() {
        val catalog = success(
            Kalligraphie.embedded(
                fixtureBytes(),
                FontSourceProvenance("TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf"),
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile(maxSourceBytes = 64)))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val resolver = success(catalog.openAssetResolver())
        try {
            val failure = assertIs<FontOperationResult.Failure>(
                instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements),
            )

            assertIs<FontError.ResourceLimitExceeded>(failure.error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(failure.error.location).tag)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun rejectsAnOutOfRangeGlyphInsteadOfReportingNoInk() {
        val catalog = success(
            Kalligraphie.embedded(
                fixtureBytes(),
                FontSourceProvenance("TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf"),
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val resolver = success(catalog.openAssetResolver())
        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val failure = assertIs<FontOperationResult.Failure>(asset.resolveGlyph(FontGlyphRequest(GlyphId(2))))

                assertEquals(2, assertIs<FontError.GlyphOutOfRange>(failure.error).glyphId)
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    private fun paintProfile(maxSourceBytes: Int = 16 * 1024): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = listOf(GlyphPaintNodeKind.PATH, GlyphPaintNodeKind.GROUP),
        acceptedCompositionModes = listOf(org.graphiks.kalligraphie.api.GlyphPaintCompositionMode.SOURCE_OVER),
        limits = PaintGraphLimits(
            maxNodes = 4,
            maxReferences = 4,
            maxDepth = 2,
            maxSourceBytes = maxSourceBytes,
            maxPaths = 2,
        ),
        outlineProfile = OutlineProfile(
            maxBytes = 16 * 1024,
            maxContours = 8,
            maxPoints = 64,
            maxCompositeDepth = 1,
            maxCompositeComponents = 1,
        ),
    )

    private fun paintProfileWithOutlineFallback(): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = listOf(
            GlyphPaintNodeKind.SOLID_OUTLINE,
            GlyphPaintNodeKind.PATH,
            GlyphPaintNodeKind.GROUP,
        ),
        acceptedCompositionModes = listOf(org.graphiks.kalligraphie.api.GlyphPaintCompositionMode.SOURCE_OVER),
        limits = PaintGraphLimits(
            maxNodes = 4,
            maxReferences = 4,
            maxDepth = 2,
            maxSourceBytes = 16 * 1024,
            maxPaths = 2,
        ),
        outlineProfile = OutlineProfile(
            maxBytes = 16 * 1024,
            maxContours = 8,
            maxPoints = 64,
            maxCompositeDepth = 1,
            maxCompositeComponents = 1,
        ),
    )

    private fun fixtureBytes(): ByteArray =
        javaClass.getResourceAsStream("/fonts/twemoji-svginot-glyph5/TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf.base64")
            ?.bufferedReader()
            ?.use { reader -> Base64.getMimeDecoder().decode(reader.readText()) }
            ?: error("Missing SVG-in-OpenType fixture resource.")

    /**
     * Builds a valid test-only SFNT from two audited fixtures. The SVG table is byte-for-byte from
     * the SVG-in-OpenType specimen and covers only glyph 1; Liberation Sans glyph 36 (`A`) is
     * therefore deliberately outside the SVG range. Its two contours are independently audited in
     * the Liberation Sans provenance record.
     */
    private fun liberationSansWithAnAuditedSvgTable(): ByteArray =
        sfntWithAdditionalTable(
            source = liberationSansFixtureBytes(),
            tag = "SVG ",
            bytes = svgTableBytes(),
        )

    private fun liberationSansFixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")) {
            "Missing Liberation Sans fixture resource."
        }.use { stream -> stream.readBytes() }

    private fun svgTableBytes(): ByteArray {
        val source = fixtureBytes()
        val tableCount = readUInt16(source, 4)
        repeat(tableCount) { index ->
            val recordOffset = 12 + index * SFNT_TABLE_RECORD_BYTES
            if (readTag(source, recordOffset) == "SVG ") {
                val offset = readUInt32(source, recordOffset + 8)
                val length = readUInt32(source, recordOffset + 12)
                return source.copyOfRange(offset, offset + length)
            }
        }
        error("The SVG-in-OpenType fixture has no SVG table.")
    }

    private fun sfntWithAdditionalTable(source: ByteArray, tag: String, bytes: ByteArray): ByteArray {
        val sourceTables = readTables(source)
        check(sourceTables.none { it.tag == tag }) { "Source SFNT already has a $tag table." }
        val tables = (sourceTables + SfntTable(tag, bytes, openTypeChecksum(bytes))).sortedBy { it.tag }
        val headerSize = SFNT_HEADER_BYTES + tables.size * SFNT_TABLE_RECORD_BYTES
        val offsets = ArrayList<Int>(tables.size)
        var totalSize = headerSize
        tables.forEach { table ->
            totalSize = alignToWord(totalSize)
            offsets += totalSize
            totalSize += table.bytes.size
        }
        totalSize = alignToWord(totalSize)

        val result = ByteArray(totalSize)
        source.copyInto(result, destinationOffset = 0, startIndex = 0, endIndex = 4)
        writeUInt16(result, 4, tables.size)
        val largestPowerOfTwo = Integer.highestOneBit(tables.size)
        writeUInt16(result, 6, largestPowerOfTwo * SFNT_TABLE_RECORD_BYTES)
        writeUInt16(result, 8, Integer.numberOfTrailingZeros(largestPowerOfTwo))
        writeUInt16(result, 10, tables.size * SFNT_TABLE_RECORD_BYTES - largestPowerOfTwo * SFNT_TABLE_RECORD_BYTES)

        tables.forEachIndexed { index, table ->
            val recordOffset = SFNT_HEADER_BYTES + index * SFNT_TABLE_RECORD_BYTES
            writeTag(result, recordOffset, table.tag)
            writeUInt32(result, recordOffset + 4, table.checksum)
            writeUInt32(result, recordOffset + 8, offsets[index].toUInt())
            writeUInt32(result, recordOffset + 12, table.bytes.size.toUInt())
            table.bytes.copyInto(result, destinationOffset = offsets[index])
        }

        val headOffset = offsets[tables.indexOfFirst { it.tag == "head" }]
        writeUInt32(result, headOffset + HEAD_CHECKSUM_ADJUSTMENT_OFFSET, 0u)
        writeUInt32(result, headOffset + HEAD_CHECKSUM_ADJUSTMENT_OFFSET, OPEN_TYPE_CHECKSUM_MAGIC - openTypeChecksum(result))
        return result
    }

    private fun readTables(source: ByteArray): List<SfntTable> {
        val tableCount = readUInt16(source, 4)
        return List(tableCount) { index ->
            val recordOffset = SFNT_HEADER_BYTES + index * SFNT_TABLE_RECORD_BYTES
            val offset = readUInt32(source, recordOffset + 8)
            val length = readUInt32(source, recordOffset + 12)
            require(offset >= 0 && length >= 0 && offset <= source.size - length) { "Invalid SFNT table range in fixture." }
            SfntTable(
                tag = readTag(source, recordOffset),
                bytes = source.copyOfRange(offset, offset + length),
                checksum = readUInt32(source, recordOffset + 4).toUInt(),
            )
        }
    }

    private fun readTag(bytes: ByteArray, offset: Int): String =
        CharArray(4) { index -> bytes[offset + index].toInt().toChar() }.concatToString()

    private fun writeTag(bytes: ByteArray, offset: Int, tag: String) {
        require(tag.length == 4) { "An SFNT tag must have exactly four characters." }
        tag.forEachIndexed { index, character -> bytes[offset + index] = character.code.toByte() }
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun writeUInt16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun writeUInt32(bytes: ByteArray, offset: Int, value: UInt) {
        bytes[offset] = (value shr 24).toByte()
        bytes[offset + 1] = (value shr 16).toByte()
        bytes[offset + 2] = (value shr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun openTypeChecksum(bytes: ByteArray): UInt {
        var checksum = 0u
        bytes.indices.step(4).forEach { offset ->
            val word = (bytes[offset].toUInt() and 0xffu) shl 24 or
                ((bytes.getOrElse(offset + 1) { 0 }.toUInt() and 0xffu) shl 16) or
                ((bytes.getOrElse(offset + 2) { 0 }.toUInt() and 0xffu) shl 8) or
                (bytes.getOrElse(offset + 3) { 0 }.toUInt() and 0xffu)
            checksum += word
        }
        return checksum
    }

    private fun alignToWord(value: Int): Int = (value + 3) and 3.inv()

    private data class SfntTable(
        val tag: String,
        val bytes: ByteArray,
        val checksum: UInt,
    )

    private companion object {
        const val SFNT_HEADER_BYTES: Int = 12
        const val SFNT_TABLE_RECORD_BYTES: Int = 16
        const val HEAD_CHECKSUM_ADJUSTMENT_OFFSET: Int = 8
        val OPEN_TYPE_CHECKSUM_MAGIC: UInt = 0xB1B0AFBAu
    }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}

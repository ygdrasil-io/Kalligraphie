package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphPaintCompositionMode
import org.graphiks.kalligraphie.api.GlyphPaintNodeKind
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.PaintGraphLimits
import org.graphiks.kalligraphie.api.PaintGraphProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SvgOpenTypeReaderTest {
    @Test
    fun rejectsScriptBeforePublishingSvgRouteData() {
        val result = SvgOpenTypeReader.read(
            svgTable("<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>"),
            glyphCount = 2,
            profile = profile(),
        )

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.UnsupportedRepresentationProfile>(failure.error)
        assertEquals("SVG ", assertIs<org.graphiks.kalligraphie.api.FontDiagnosticLocation.Table>(failure.error.location).tag)
    }

    @Test
    fun rejectsAValidButOverlyDeepSvgBeforePublishingSvgRouteData() {
        val result = SvgOpenTypeReader.read(
            svgTable(
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><g><g><path fill=\"#000000\" d=\"M0 0L1 0Z\"/></g></g></svg>",
            ),
            glyphCount = 2,
            profile = profile(maxDepth = 2),
        )

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.ResourceLimitExceeded>(failure.error)
        assertEquals("SVG ", assertIs<org.graphiks.kalligraphie.api.FontDiagnosticLocation.Table>(failure.error.location).tag)
    }

    @Test
    fun rejectsOverlappingDocumentsWhoseCumulativeSourceBytesExceedTheProfileLimit() {
        val document = "<svg xmlns=\"http://www.w3.org/2000/svg\"><path fill=\"#000000\" d=\"M0 0L1 0Z\"/></svg>"
        val table = overlappingSvgTable(document)

        val result = SvgOpenTypeReader.read(
            svgTable = table,
            glyphCount = 3,
            profile = profile(maxSourceBytes = table.size, maxSvgDocuments = 2),
        )

        assertIs<FontError.ResourceLimitExceeded>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun rejectsTransformsWhoseTotalAcrossDocumentsExceedsTheProfileLimit() {
        val document = "<svg xmlns=\"http://www.w3.org/2000/svg\"><g transform=\"scale(1)\"><path fill=\"#000000\" d=\"M0 0L1 0Z\"/></g></svg>"

        val result = SvgOpenTypeReader.read(
            svgTable = overlappingSvgTable(document),
            glyphCount = 3,
            profile = profile(maxSvgDocuments = 2, maxSvgTransformOperations = 1),
        )

        assertIs<FontError.ResourceLimitExceeded>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun rejectsAnSvgTableWithNoDocumentList() {
        val result = SvgOpenTypeReader.read(
            svgTable = ByteArray(10),
            glyphCount = 2,
            profile = profile(),
        )

        assertIs<FontError.FontDataFailure>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun rejectsAnSvgTableWithAnEmptyDocumentList() {
        val table = ByteArray(12).also { it.writeUInt32(2, 10u) }

        val result = SvgOpenTypeReader.read(
            svgTable = table,
            glyphCount = 2,
            profile = profile(),
        )

        assertIs<FontError.FontDataFailure>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun rejectsAnSvgTableWhoseReservedHeaderFieldIsNotZero() {
        val table = svgTable("<svg xmlns=\"http://www.w3.org/2000/svg\"><path fill=\"#000000\" d=\"M0 0L1 0Z\"/></svg>")
            .also { it.writeUInt32(6, 1u) }

        val result = SvgOpenTypeReader.read(
            svgTable = table,
            glyphCount = 2,
            profile = profile(),
        )

        assertIs<FontError.FontDataFailure>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun rejectsATransformWhoseFiniteOperandsOverflowDuringComposition() {
        val result = SvgOpenTypeReader.read(
            svgTable(
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><g transform=\"scale(1e308) scale(1e308)\"><path fill=\"#000000\" d=\"M0 0L1 0Z\"/></g></svg>",
            ),
            glyphCount = 2,
            profile = profile(),
        )

        assertIs<FontError.FontDataFailure>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun acceptsOnePathWhenTheProfileAllowsExactlyOnePaintNode() {
        val result = SvgOpenTypeReader.read(
            svgTable("<svg xmlns=\"http://www.w3.org/2000/svg\"><path fill=\"#000000\" d=\"M0 0L1 0Z\"/></svg>"),
            glyphCount = 2,
            profile = profile(maxNodes = 1),
        )

        assertIs<FontOperationResult.Success<SvgOpenTypeData>>(result)
    }

    private fun profile(
        maxDepth: Int = 4,
        maxSourceBytes: Int = 4_096,
        maxSvgDocuments: Int = 1,
        maxSvgTransformOperations: Int = 4,
        maxNodes: Int = 4,
    ): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = listOf(GlyphPaintNodeKind.PATH, GlyphPaintNodeKind.GROUP),
        acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
        limits = PaintGraphLimits(
            maxNodes = maxNodes,
            maxReferences = 4,
            maxDepth = maxDepth,
            maxSourceBytes = maxSourceBytes,
            maxPaths = 2,
            maxSvgDocuments = maxSvgDocuments,
            maxSvgTransformOperations = maxSvgTransformOperations,
        ),
        outlineProfile = OutlineProfile(
            maxBytes = 4_096,
            maxContours = 2,
            maxPoints = 8,
            maxCompositeDepth = 1,
            maxCompositeComponents = 1,
        ),
    )

    private fun svgTable(document: String): ByteArray {
        val documentBytes = document.encodeToByteArray()
        val documentListOffset = 10
        val documentOffset = 14
        return ByteArray(documentListOffset + documentOffset + documentBytes.size).also { table ->
            table.writeUInt32(2, documentListOffset.toUInt())
            table.writeUInt16(documentListOffset, 1)
            val record = documentListOffset + 2
            table.writeUInt16(record, 1)
            table.writeUInt16(record + 2, 1)
            table.writeUInt32(record + 4, documentOffset.toUInt())
            table.writeUInt32(record + 8, documentBytes.size.toUInt())
            documentBytes.copyInto(table, destinationOffset = documentListOffset + documentOffset)
        }
    }

    private fun overlappingSvgTable(document: String): ByteArray {
        val documentBytes = document.encodeToByteArray()
        val documentListOffset = 10
        val documentOffset = 26
        return ByteArray(documentListOffset + documentOffset + documentBytes.size).also { table ->
            table.writeUInt32(2, documentListOffset.toUInt())
            table.writeUInt16(documentListOffset, 2)
            repeat(2) { index ->
                val record = documentListOffset + 2 + index * 12
                table.writeUInt16(record, index + 1)
                table.writeUInt16(record + 2, index + 1)
                table.writeUInt32(record + 4, documentOffset.toUInt())
                table.writeUInt32(record + 8, documentBytes.size.toUInt())
            }
            documentBytes.copyInto(table, destinationOffset = documentListOffset + documentOffset)
        }
    }
}

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

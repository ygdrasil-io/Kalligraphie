package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphPaintCompositionMode
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintNodeKind
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.PaintGraphLimits
import org.graphiks.kalligraphie.api.PaintGraphProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class ColrV0PaletteVariantTest {
    @Test
    fun reopensTheExactSecondCpalPaletteWithoutChangingTheSelectedGlyphOrMetrics() {
        val catalog = catalog(bungeeFixtureBytes())
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(1_000f))))

        try {
            val glyph = success(instance.resolveGlyph('A'.code)).glyphId
            val metrics = success(instance.metrics(glyph))
            assertEquals(GlyphId(43), glyph)

            val paletteZero = success(
                instance.acquireRenderAsset(resolver, FontRenderVariantSnapshot(cpalPaletteIndex = 0), requirements),
            )
            val paletteZeroPaint = try {
                paint(paletteZero, glyph)
            } finally {
                paletteZero.close()
            }

            val paletteOne = success(
                instance.acquireRenderAsset(resolver, FontRenderVariantSnapshot(cpalPaletteIndex = 1), requirements),
            )
            val paletteOneKey = paletteOne.key
            paletteOne.close()

            val reopened = success(resolver.reopen(paletteOneKey))
            val paletteOnePaint = try {
                paint(reopened, glyph)
            } finally {
                reopened.close()
            }

            assertEquals(listOf(292, 293), solidOutlines(paletteZeroPaint).map { it.outline.glyphId })
            assertEquals(
                listOf(GlyphColor(201, 9, 0), GlyphColor(255, 149, 128)),
                solidOutlines(paletteZeroPaint).map { it.color },
            )
            assertEquals(
                listOf(GlyphColor(255, 255, 255), GlyphColor(232, 232, 231)),
                solidOutlines(paletteOnePaint).map { it.color },
            )
            assertNotEquals(solidOutlines(paletteZeroPaint).map { it.color }, solidOutlines(paletteOnePaint).map { it.color })
            assertEquals(GlyphId(43), success(instance.resolveGlyph('A'.code)).glyphId)
            assertEquals(metrics, success(instance.metrics(glyph)))
        } finally {
            resolver.close()
        }
    }

    @Test
    fun appliesTheColrForegroundSentinelWithoutTreatingItAsACpalIndex() {
        val catalog = catalog(bungeeFixtureBytes().withColrLayerPaletteIndex(layerIndex = 86, paletteIndex = 0xFFFF))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(1_000f))))
        val foreground = GlyphColor(17, 34, 51, 68)

        try {
            val asset = success(
                instance.acquireRenderAsset(
                    resolver,
                    FontRenderVariantSnapshot(cpalPaletteIndex = 0, foregroundColor = foreground),
                    requirements,
                ),
            )
            try {
                val paint = paint(asset, GlyphId(43))
                assertEquals(
                    listOf(foreground, GlyphColor(255, 149, 128)),
                    solidOutlines(paint).map { it.color },
                )
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    private fun catalog(bytes: ByteArray) = success(
        Kalligraphie.embedded(bytes, FontSourceProvenance("Bungee Color Regular")),
    )

    private fun paintProfile(): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE, GlyphPaintNodeKind.GROUP),
        acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
        limits = PaintGraphLimits(
            maxNodes = 3,
            maxReferences = 2,
            maxDepth = 2,
            maxSourceBytes = 16_384,
            maxPaths = 2,
            maxPalettes = 9,
            maxPaletteEntries = 2,
            maxColorRecords = 16,
            maxDecodedPaletteBytes = 72,
            maxBaseGlyphRecords = 288,
            maxLayerRecords = 576,
        ),
        outlineProfile = OutlineProfile(
            maxBytes = 1_000_000,
            maxContours = 1_024,
            maxPoints = 65_536,
            maxCompositeDepth = 16,
            maxCompositeComponents = 256,
        ),
    )

    private fun paint(asset: org.graphiks.kalligraphie.api.FontRenderAssetHandle, glyph: GlyphId) =
        assertIs<GlyphRepresentation.Paint>(success(asset.resolveGlyph(FontGlyphRequest(glyph)))).paint

    private fun solidOutlines(paint: org.graphiks.kalligraphie.api.GlyphPaintIR): List<GlyphPaintNode.SolidOutline> =
        paint.nodes.filterIsInstance<GlyphPaintNode.SolidOutline>()

    private fun bungeeFixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/bungee-color/BungeeColor-Regular.ttf")) {
            "Bungee Color COLR version 0 fixture is missing"
        }.use { input -> input.readBytes() }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}

private fun ByteArray.withColrLayerPaletteIndex(layerIndex: Int, paletteIndex: Int): ByteArray = copyOf().also { bytes ->
    val tableCount = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
    val recordOffset = (0 until tableCount)
        .map { index -> 12 + index * 16 }
        .first { offset -> bytes.decodeToString(offset, offset + 4) == "COLR" }
    val tableOffset =
        ((bytes[recordOffset + 8].toInt() and 0xFF) shl 24) or
            ((bytes[recordOffset + 9].toInt() and 0xFF) shl 16) or
            ((bytes[recordOffset + 10].toInt() and 0xFF) shl 8) or
            (bytes[recordOffset + 11].toInt() and 0xFF)
    val layerOffset =
        ((bytes[tableOffset + 8].toInt() and 0xFF) shl 24) or
            ((bytes[tableOffset + 9].toInt() and 0xFF) shl 16) or
            ((bytes[tableOffset + 10].toInt() and 0xFF) shl 8) or
            (bytes[tableOffset + 11].toInt() and 0xFF)
    val paletteOffset = tableOffset + layerOffset + layerIndex * 4 + 2
    bytes[paletteOffset] = (paletteIndex ushr 8).toByte()
    bytes[paletteOffset + 1] = paletteIndex.toByte()
}

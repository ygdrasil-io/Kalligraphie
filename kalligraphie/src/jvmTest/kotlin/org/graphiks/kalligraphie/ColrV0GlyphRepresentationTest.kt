package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
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

class ColrV0GlyphRepresentationTest {
    @Test
    fun materializesEmojiTwoGrinningFaceIntoTheAuditedLayeredPaintGraph() {
        val catalog = success(
            Kalligraphie.embedded(
                fixtureBytes(),
                FontSourceProvenance("EmojiTwo COLRv0 4.0"),
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2_048f))))

        try {
            val glyph = success(instance.resolveGlyph(0x1F600)).glyphId
            assertEquals(GlyphId(1_443), glyph)

            val asset = success(
                instance.acquireRenderAsset(
                    resolver,
                    FontRenderVariantSnapshot(cpalPaletteIndex = 0),
                    requirements,
                ),
            )
            try {
                val paint = assertIs<GlyphRepresentation.Paint>(success(asset.resolveGlyph(org.graphiks.kalligraphie.api.FontGlyphRequest(glyph)))).paint
                val group = assertIs<GlyphPaintNode.Group>(paint.nodes[paint.rootNode])

                assertEquals(listOf(0, 1, 2, 3, 4, 5), group.children)
                assertEquals(
                    listOf(2_650, 10_717, 10_718, 10_719, 10_720, 10_721),
                    paint.nodes.take(6).map { node -> assertIs<GlyphPaintNode.SolidOutline>(node).outline.glyphId },
                )
                assertEquals(
                    listOf(
                        GlyphColor(255, 221, 103),
                        GlyphColor(102, 78, 39),
                        GlyphColor(76, 53, 38),
                        GlyphColor(255, 113, 127),
                        GlyphColor(255, 255, 255),
                        GlyphColor(102, 78, 39),
                    ),
                    paint.nodes.take(6).map { node -> assertIs<GlyphPaintNode.SolidOutline>(node).color },
                )
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    private fun paintProfile(): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE, GlyphPaintNodeKind.GROUP),
        acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
        limits = PaintGraphLimits(
            maxNodes = 8,
            maxReferences = 6,
            maxDepth = 2,
            maxSourceBytes = 200_000,
            maxPaths = 6,
            maxPalettes = 2,
            maxPaletteEntries = 2_000,
            maxColorRecords = 2_000,
            maxBaseGlyphRecords = 3_000,
            maxLayerRecords = 30_000,
        ),
        outlineProfile = OutlineProfile(
            maxBytes = 1_000_000,
            maxContours = 256,
            maxPoints = 16_384,
            maxCompositeDepth = 16,
            maxCompositeComponents = 256,
        ),
    )

    private fun fixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/emoji-two-colr-v0/EmojiTwoCOLRv0.ttf")) {
            "EmojiTwo COLR version 0 fixture is missing"
        }.use { input -> input.readBytes() }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}

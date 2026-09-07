package org.graphiks.kalligraphie

import java.util.Base64
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
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

                assertTrue(catalog.faces.single().capabilities.paintGraph)
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

    private fun fixtureBytes(): ByteArray =
        javaClass.getResourceAsStream("/fonts/twemoji-svginot-glyph5/TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf.base64")
            ?.bufferedReader()
            ?.use { reader -> Base64.getMimeDecoder().decode(reader.readText()) }
            ?: error("Missing SVG-in-OpenType fixture resource.")

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}

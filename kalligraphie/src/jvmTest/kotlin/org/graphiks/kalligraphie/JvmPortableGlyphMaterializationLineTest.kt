package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.BitmapGlyphIR
import org.graphiks.kalligraphie.api.BitmapLimits
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphColorSpace
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMaterializationRoute
import org.graphiks.kalligraphie.api.GlyphPaintCompositionMode
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintNodeKind
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineLayout
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.PaintGraphLimits
import org.graphiks.kalligraphie.api.PaintGraphProfile
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class JvmPortableGlyphMaterializationLineTest {
    @Test
    fun certifiesTwoCpalPaletteSelectionsWithoutChangingShapingOrLineGeometry() {
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))
        val fixture = openFixture(
            bytes = resourceBytes("/fonts/bungee-color/BungeeColor-Regular.ttf"),
            provenance = "Bungee Color Regular",
            requirements = requirements,
            layoutSize = 1_000f,
        )
        val snapshot = Kalligraphie.decodeUtf8(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf8(byteArrayOf('A'.code.toByte()))),
        ).snapshot

        try {
            val paletteZero = paragraphLine(snapshot, fixture, FontRenderVariantSnapshot(cpalPaletteIndex = 0), requirements)
            val paletteOne = paragraphLine(snapshot, fixture, FontRenderVariantSnapshot(cpalPaletteIndex = 1), requirements)
            val zeroGlyph = paletteZero.positionedGlyphRuns.single().glyphs.single()
            val oneGlyph = paletteOne.positionedGlyphRuns.single().glyphs.single()
            val zeroCertificate = checkNotNull(zeroGlyph.materializationCertificate)
            val oneCertificate = checkNotNull(oneGlyph.materializationCertificate)

            assertEquals(GlyphId(43), zeroGlyph.shapedGlyph.glyphId)
            assertEquals(zeroGlyph.shapedGlyph.glyphId, oneGlyph.shapedGlyph.glyphId)
            assertEquals(zeroGlyph.origin, oneGlyph.origin)
            assertEquals(zeroGlyph.advance, oneGlyph.advance)
            assertEquals(
                paletteZero.allCaretCandidates.map { caret -> caret.position to caret.geometry },
                paletteOne.allCaretCandidates.map { caret -> caret.position to caret.geometry },
            )
            assertEquals(GlyphMaterializationRoute.PAINT_GRAPH, zeroCertificate.route)
            assertEquals(GlyphMaterializationRoute.PAINT_GRAPH, oneCertificate.route)
            assertNotEquals(zeroCertificate.assetKey, oneCertificate.assetKey)

            assertEquals(
                listOf(GlyphColor(201, 9, 0), GlyphColor(255, 149, 128)),
                paintColors(fixture.resolver, zeroCertificate),
            )
            assertEquals(
                listOf(GlyphColor(255, 255, 255), GlyphColor(232, 232, 231)),
                paintColors(fixture.resolver, oneCertificate),
            )
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun certifiesAnEmbeddedBitmapGlyphWhoseReopenedAssetReturnsTheDecodedPixels() {
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile()))
        val fixture = openFixture(
            bytes = resourceBytes("/fonts/skia-ebdt-format1/ebdt_fmt1.ttf"),
            provenance = "Skia EBDT format 1",
            requirements = requirements,
            layoutSize = 16f,
        )
        val snapshot = Kalligraphie.decodeUtf8(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf8("😀".encodeToByteArray())),
        ).snapshot

        try {
            val line = layout(snapshot, fixture, FontRenderVariantSnapshot.default, requirements)
            val glyph = line.positionedGlyphRuns.single().glyphs.single()
            val certificate = checkNotNull(glyph.materializationCertificate)

            assertEquals(GlyphId(3), glyph.shapedGlyph.glyphId)
            assertEquals(GlyphMaterializationRoute.BITMAP, certificate.route)
            val reopened = success(fixture.resolver.reopen(certificate.assetKey))
            try {
                val bitmap = assertIs<GlyphRepresentation.Bitmap>(
                    success(reopened.resolveGlyph(org.graphiks.kalligraphie.api.FontGlyphRequest(certificate.glyphId))),
                ).bitmap
                assertDecodedBitmap(bitmap)
            } finally {
                reopened.close()
            }
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    private fun layout(
        snapshot: org.graphiks.kalligraphie.api.TextSnapshot,
        fixture: Fixture,
        renderVariant: FontRenderVariantSnapshot,
        requirements: FontAccessRequirementsSnapshot,
    ) = assertIs<EditableLineResult.Success>(
        JvmEditableLineFacade.layout(
            JvmEditableLineFacadeRequest(
                snapshot = snapshot,
                font = fixture.font,
                baseDirection = BaseDirection.LEFT_TO_RIGHT,
                language = "en",
                featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
                features = emptyList(),
                verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
                materialization = EditableLineMaterialization.Renderable(
                    resolver = fixture.resolver,
                    renderVariant = renderVariant,
                    requirements = requirements,
                ),
            ),
        ),
    ).line

    private fun paragraphLine(
        snapshot: org.graphiks.kalligraphie.api.TextSnapshot,
        fixture: Fixture,
        renderVariant: FontRenderVariantSnapshot,
        requirements: FontAccessRequirementsSnapshot,
    ): LineLayout {
        val face = fixture.catalog.faces.single().id
        return assertIs<ParagraphLayoutResult.Success>(
            JvmEditableParagraphFacade.layout(
                JvmEditableParagraphFacadeRequest(
                    snapshot = snapshot,
                    constraints = HorizontalParagraphConstraints(
                        region = LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(2_000f), LayoutUnit(1_000f)),
                        lineMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
                    ),
                    baseDirection = BaseDirection.LEFT_TO_RIGHT,
                    language = "en",
                    fontCatalog = fixture.catalog,
                    resolutionPolicy = FontResolutionPolicySnapshot(
                        generation = fixture.catalog.generation,
                        policyId = "portable-glyph-materialization-fixture",
                        version = "1",
                        candidates = listOf(FontResolutionCandidate(face)),
                        lastResortFace = face,
                    ),
                    fontInstanceDescriptor = fixture.descriptor,
                    materialization = EditableLineMaterialization.Renderable(
                        resolver = fixture.resolver,
                        renderVariant = renderVariant,
                        requirements = requirements,
                    ),
                ),
            ),
        ).layout.lines.single()
    }

    private fun paintColors(
        resolver: FontAssetResolverHandle,
        certificate: org.graphiks.kalligraphie.api.GlyphMaterializationCertificate,
    ): List<GlyphColor> {
        val reopened = success(resolver.reopen(certificate.assetKey))
        try {
            val paint = assertIs<GlyphRepresentation.Paint>(
                success(reopened.resolveGlyph(org.graphiks.kalligraphie.api.FontGlyphRequest(certificate.glyphId))),
            ).paint
            return paint.nodes.filterIsInstance<GlyphPaintNode.SolidOutline>().map(GlyphPaintNode.SolidOutline::color)
        } finally {
            reopened.close()
        }
    }

    private fun assertDecodedBitmap(bitmap: BitmapGlyphIR) {
        assertEquals(BitmapStrike(16, 16), bitmap.strike)
        assertEquals(13, bitmap.width)
        assertEquals(13, bitmap.height)
        assertEquals(0, bitmap.originX)
        assertEquals(13, bitmap.originY)
        assertEquals(BitmapPixelFormat.ALPHA_8, bitmap.pixelFormat)
        assertEquals(GlyphColorSpace.SRGB, bitmap.colorSpace)
        assertContentEquals(
            byteArrayOf(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, -1, -1, -1, -1, -1, 0, 0, 0, 0,
            ),
            bitmap.copyDecodedPixels().copyOfRange(0, 26),
        )
    }

    private fun openFixture(
        bytes: ByteArray,
        provenance: String,
        requirements: FontAccessRequirementsSnapshot,
        layoutSize: Float,
    ): Fixture {
        val catalog = success(Kalligraphie.embedded(bytes, FontSourceProvenance(provenance)))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val descriptor = FontInstanceDescriptor(LayoutUnit(layoutSize))
        val font = success(face.instantiate(descriptor))
        return Fixture(catalog, font, resolver, descriptor)
    }

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
        outlineProfile = outlineProfile(),
    )

    private fun bitmapProfile(): BitmapProfile = BitmapProfile(
        strike = BitmapStrike(16, 16),
        acceptedPixelFormats = listOf(BitmapPixelFormat.ALPHA_8),
        acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
        limits = BitmapLimits(
            maxStrikes = 3,
            maxIndexSubtables = 16,
            maxRecordCount = 16,
            maxIndexTableBytes = 16_384,
            maxBitmapTableBytes = 16_384,
            maxWidth = 16,
            maxHeight = 16,
            maxPixels = 256,
            maxCompressedBytes = 64,
            maxTotalCompressedBytes = 1_024,
            maxDecodedBytes = 256,
            maxTotalDecodedBytes = 1_024,
        ),
    )

    private fun outlineProfile(): OutlineProfile = OutlineProfile(
        maxBytes = 1_000_000,
        maxContours = 1_024,
        maxPoints = 65_536,
        maxCompositeDepth = 16,
        maxCompositeComponents = 256,
    )

    private fun resourceBytes(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) { "Fixture font resource is missing: $path" }.use { it.readBytes() }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value

    private data class Fixture(
        val catalog: FontCatalogSnapshot,
        val font: FontInstance,
        val resolver: FontAssetResolverHandle,
        val descriptor: FontInstanceDescriptor,
    )
}

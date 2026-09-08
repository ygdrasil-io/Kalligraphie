package org.graphiks.kalligraphie.font.core

import org.graphiks.kalligraphie.api.BitmapGlyphIR
import org.graphiks.kalligraphie.api.BitmapGlyphMetrics
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.FontCatalogGeneration
import org.graphiks.kalligraphie.api.FontDataInterpretationVersion
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontGeometryParameters
import org.graphiks.kalligraphie.api.FontInstanceKey
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontProviderId
import org.graphiks.kalligraphie.api.FontRenderAssetKey
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSourceId
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphColorSpace
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintPath
import org.graphiks.kalligraphie.api.GlyphPaintPathCommand
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.GlyphRepresentationKey
import org.graphiks.kalligraphie.api.GlyphRepresentationProfileKey
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlyphRepresentationRetentionWeightTest {
    @Test
    fun countsEveryDecodedBitmapPixelInTheRetainedRepresentationWeight() {
        val onePixel = GlyphRepresentation.Bitmap(bitmap(width = 1, height = 1, pixels = byteArrayOf(1)))
        val fourPixels = GlyphRepresentation.Bitmap(bitmap(width = 2, height = 2, pixels = byteArrayOf(1, 2, 3, 4)))

        assertEquals(3L, fourPixels.estimatedRetainedBytes() - onePixel.estimatedRetainedBytes())
    }

    @Test
    fun countsEveryNormalizedPaintPathCommandInTheRetainedRepresentationWeight() {
        val triangle = GlyphRepresentation.Paint(paint(path = listOf(
            GlyphPaintPathCommand.MoveTo(0.0, 0.0),
            GlyphPaintPathCommand.LineTo(1.0, 0.0),
            GlyphPaintPathCommand.LineTo(0.0, 1.0),
            GlyphPaintPathCommand.Close,
        )))
        val rectangle = GlyphRepresentation.Paint(paint(path = listOf(
            GlyphPaintPathCommand.MoveTo(0.0, 0.0),
            GlyphPaintPathCommand.LineTo(1.0, 0.0),
            GlyphPaintPathCommand.LineTo(1.0, 1.0),
            GlyphPaintPathCommand.LineTo(0.0, 1.0),
            GlyphPaintPathCommand.Close,
        )))

        assertEquals(16L, rectangle.estimatedRetainedBytes() - triangle.estimatedRetainedBytes())
    }

    @Test
    fun countsEveryPaintGroupChildReferenceAtItsIntegerWidth() {
        val oneChild = GlyphRepresentation.Paint(groupedPaint(children = listOf(0)))
        val twoChildren = GlyphRepresentation.Paint(groupedPaint(children = listOf(0, 1)))

        assertEquals(4L, twoChildren.estimatedRetainedBytes() - oneChild.estimatedRetainedBytes())
    }

    @Test
    fun countsRetainedKeyParametersAndTheCacheEntryEnvelope() {
        val result: FontOperationResult.Success<GlyphRepresentation> = FontOperationResult.Success(GlyphRepresentation.Empty)
        val compact = representationKey(parameters = "p", routeParameters = "ebdt")
        val expanded = representationKey(parameters = "p".repeat(100), routeParameters = "ebdt-format-one")

        val compactWeight = cachedRepresentationRetainedBytes(compact, result)
        val expandedWeight = cachedRepresentationRetainedBytes(expanded, result)

        assertEquals(220L, expandedWeight - compactWeight)
        assertTrue(compactWeight > result.value.estimatedRetainedBytes())
    }

    @Test
    fun evictsAnEarlierCompleteRepresentationWhenItsFullKeyWeightExceedsTheBudget() {
        val result: FontOperationResult.Success<GlyphRepresentation> = FontOperationResult.Success(GlyphRepresentation.Empty)
        val firstKey = representationKey(parameters = "profile", routeParameters = "ebdt-format-one")
        val secondKey = firstKey.copy(glyphId = GlyphId(2))
        val entryWeight = cachedRepresentationRetainedBytes(firstKey, result)
        val cache = WeightedEvictableCache<GlyphRepresentationKey, FontOperationResult.Success<GlyphRepresentation>>(
            maximumRetainedBytes = entryWeight,
        )

        cache.put(firstKey, result, entryWeight)
        cache.put(secondKey, result, cachedRepresentationRetainedBytes(secondKey, result))

        assertNull(cache.get(firstKey))
        assertEquals(result, cache.get(secondKey))
    }

    private fun bitmap(
        width: Int,
        height: Int,
        pixels: ByteArray,
    ): BitmapGlyphIR = BitmapGlyphIR(
        glyphId = GlyphId(1),
        strike = BitmapStrike(16, 16),
        width = width,
        height = height,
        originX = 0,
        originY = 0,
        metrics = BitmapGlyphMetrics(advanceX = width, advanceY = 0),
        pixelFormat = BitmapPixelFormat.ALPHA_8,
        colorSpace = GlyphColorSpace.SRGB,
        decodedPixels = pixels,
    )

    private fun paint(path: List<GlyphPaintPathCommand>): GlyphPaintIR = GlyphPaintIR(
        schemaVersion = 1,
        rootNode = 0,
        nodes = listOf(
            GlyphPaintNode.Path(
                path = GlyphPaintPath(path),
                color = GlyphColor(0, 0, 0),
            ),
        ),
    )

    private fun groupedPaint(children: List<Int>): GlyphPaintIR = GlyphPaintIR(
        schemaVersion = 1,
        rootNode = 2,
        nodes = listOf(
            GlyphPaintNode.Path(
                path = GlyphPaintPath(
                    listOf(
                        GlyphPaintPathCommand.MoveTo(0.0, 0.0),
                        GlyphPaintPathCommand.LineTo(1.0, 0.0),
                        GlyphPaintPathCommand.LineTo(0.0, 1.0),
                        GlyphPaintPathCommand.Close,
                    ),
                ),
                color = GlyphColor(0, 0, 0),
            ),
            GlyphPaintNode.Path(
                path = GlyphPaintPath(
                    listOf(
                        GlyphPaintPathCommand.MoveTo(0.0, 0.0),
                        GlyphPaintPathCommand.LineTo(1.0, 0.0),
                        GlyphPaintPathCommand.LineTo(0.0, 1.0),
                        GlyphPaintPathCommand.Close,
                    ),
                ),
                color = GlyphColor(1, 1, 1),
            ),
            GlyphPaintNode.Group(children),
        ),
    )

    private fun representationKey(
        parameters: String,
        routeParameters: String,
    ): GlyphRepresentationKey {
        val profile = OutlineProfile(
            maxBytes = 1_024,
            maxContours = 8,
            maxPoints = 64,
            maxCompositeDepth = 4,
            maxCompositeComponents = 8,
        )
        val variant = FontRenderVariantKey.default
        return GlyphRepresentationKey(
            assetKey = FontRenderAssetKey(
                fontInstanceKey = FontInstanceKey(
                    face = FontFaceId(
                        source = FontSourceId.Opaque("test-provider", "test-generation", "test-source"),
                        faceIndex = 0,
                    ),
                    interpretation = FontDataInterpretationVersion("test-pipeline", "1"),
                    layoutSize = LayoutUnit(16f),
                    geometry = FontGeometryParameters(),
                ),
                variant = variant,
                representationProfile = profile,
                generation = FontCatalogGeneration(FontProviderId("test-provider"), "test-generation"),
            ),
            glyphId = GlyphId(1),
            variant = variant,
            profile = GlyphRepresentationProfileKey(
                kind = org.graphiks.kalligraphie.api.GlyphRepresentationProfileKind.OUTLINE,
                schemaVersion = 1,
                parameters = parameters,
            ),
            routeParameters = routeParameters,
        )
    }
}

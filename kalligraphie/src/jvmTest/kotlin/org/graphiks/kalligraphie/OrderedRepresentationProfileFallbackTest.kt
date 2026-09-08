package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphPaintCompositionMode
import org.graphiks.kalligraphie.api.GlyphPaintNodeKind
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.PaintGraphLimits
import org.graphiks.kalligraphie.api.PaintGraphProfile
import kotlin.test.Test
import kotlin.test.assertIs

class OrderedRepresentationProfileFallbackTest {
    @Test
    fun selectsTheFirstSupportedProfileInConsumerOrderWithoutInventingAPaintRoute() {
        val catalog = success(
            Kalligraphie.embedded(
                sourceBytes = liberationFixtureBytes(),
                provenance = FontSourceProvenance("Liberation Sans Regular"),
            ),
        )
        val outlineProfile = outlineProfile()
        val requirements = FontAccessRequirementsSnapshot.renderable(
            listOf(paintProfile(), outlineProfile),
        )
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2_048f))))

        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                assertIs<OutlineProfile>(asset.key.representationProfile)
                val glyph = success(instance.resolveGlyph('A'.code)).glyphId
                assertIs<GlyphRepresentation.Outline>(success(asset.resolveGlyph(FontGlyphRequest(glyph))))
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    private fun paintProfile(): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE),
        acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
        limits = PaintGraphLimits(maxNodes = 1, maxReferences = 0, maxDepth = 1),
        outlineProfile = outlineProfile(),
    )

    private fun outlineProfile(): OutlineProfile = OutlineProfile(
        maxBytes = 1_000_000,
        maxContours = 256,
        maxPoints = 16_384,
        maxCompositeDepth = 8,
        maxCompositeComponents = 256,
    )

    private fun liberationFixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")) {
            "Liberation Sans fixture is missing"
        }.use { input -> input.readBytes() }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}

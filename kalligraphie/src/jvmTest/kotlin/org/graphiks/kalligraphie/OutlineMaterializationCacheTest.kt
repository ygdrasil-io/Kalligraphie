package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontMaterializationCachePolicy
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OutlineMaterializationCacheTest {
    @Test
    fun keepsThePortableOutlineIdenticalWithBoundedRepresentationRetention() {
        val requirements = FontAccessRequirementsSnapshot.renderable(outlineProfile())
        val catalog = success(
            Kalligraphie.embedded(
                sourceBytes = fixtureBytes(),
                provenance = FontSourceProvenance("Liberation Sans outline cache fixture"),
                cachePolicy = FontMaterializationCachePolicy(maxEvictableBytesPerFace = 1_024),
            ),
        )
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(org.graphiks.kalligraphie.api.FontInstanceDescriptor(LayoutUnit(16f))))
        val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
        try {
            val initial = assertIs<GlyphRepresentation.Outline>(success(asset.resolveGlyph(FontGlyphRequest(GlyphId(36)))))
            val warm = assertIs<GlyphRepresentation.Outline>(success(asset.resolveGlyph(FontGlyphRequest(GlyphId(36)))))
            success(asset.resolveGlyph(FontGlyphRequest(GlyphId(7))))
            val afterPressure = assertIs<GlyphRepresentation.Outline>(success(asset.resolveGlyph(FontGlyphRequest(GlyphId(36)))))

            assertEquals(initial, warm)
            assertEquals(initial, afterPressure)
        } finally {
            asset.close()
            resolver.close()
        }
    }

    private fun outlineProfile(): OutlineProfile = OutlineProfile(
        maxBytes = 1_000_000,
        maxContours = 256,
        maxPoints = 16_384,
        maxCompositeDepth = 8,
        maxCompositeComponents = 256,
    )

    private fun fixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")) {
            "fixture font resource is missing"
        }.use { stream -> stream.readBytes() }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}

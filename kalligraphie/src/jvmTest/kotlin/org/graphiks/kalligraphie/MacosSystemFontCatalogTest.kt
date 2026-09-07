package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MacosSystemFontCatalogTest {
    @Test
    fun capturesRealMacosFontsInDistinctGenerationsAndRejectsTheWrongResolver() {
        if (!System.getProperty("os.name").startsWith("Mac")) return

        val first = success(MacosSystemFontCatalog.open())
        val second = success(MacosSystemFontCatalog.open())
        val requirements = FontAccessRequirementsSnapshot.renderable(outlineProfile())

        assertEquals("macos-system-opentype", first.generation.provider.value)
        assertNotEquals(first.generation, second.generation)
        assertTrue(first.faces.map { it.id }.toSet().intersect(second.faces.map { it.id }.toSet()).isNotEmpty())

        val face = success(first.resolveFace(first.faces.first().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val firstResolver = success(first.openAssetResolver())
        val secondResolver = success(second.openAssetResolver())
        try {
            val asset = success(instance.acquireRenderAsset(firstResolver, FontRenderVariantKey.default, requirements))
            try {
                assertIs<FontError.IncompatibleCatalogGeneration>(
                    assertIs<FontOperationResult.Failure>(secondResolver.reopen(asset.key)).error,
                )
            } finally {
                asset.close()
            }
        } finally {
            firstResolver.close()
            secondResolver.close()
        }
    }

    private fun outlineProfile(): OutlineProfile = OutlineProfile(
        maxBytes = 1_000_000,
        maxContours = 16_384,
        maxPoints = 1_000_000,
        maxCompositeDepth = 32,
        maxCompositeComponents = 16_384,
    )

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}

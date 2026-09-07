package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class JvmSystemFontCatalogTest {
    @Test
    fun opensActualJvmSystemFontFilesAndRejectsAnAssetKeyFromAnotherSnapshotGeneration() {
        val first = success(JvmFontCatalogs.openSystemFontCatalog(maxFaces = 1))
        val second = success(JvmFontCatalogs.openSystemFontCatalog(maxFaces = 1))

        assertEquals("jvm-system-files", first.generation.provider.value)
        assertNotEquals(first.generation, second.generation)

        val requirements = FontAccessRequirementsSnapshot.renderable(outlineProfile())
        val firstResolver = success(first.openAssetResolver())
        val secondResolver = success(second.openAssetResolver())
        val firstFace = success(first.resolveFace(first.faces.single().id, requirements))
        val instance = success(firstFace.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))

        try {
            val asset = success(instance.acquireRenderAsset(firstResolver, FontRenderVariantKey.default, requirements))
            try {
                success(asset.resolveGlyph(FontGlyphRequest(GlyphId(0))))
                val failure = assertIs<FontOperationResult.Failure>(secondResolver.reopen(asset.key))
                assertIs<FontError.IncompatibleCatalogGeneration>(failure.error)
            } finally {
                asset.close()
            }
        } finally {
            firstResolver.close()
            secondResolver.close()
        }
    }

    private fun outlineProfile(): OutlineProfile = OutlineProfile(
        maxBytes = 65_536,
        maxContours = 4_096,
        maxPoints = 65_536,
        maxCompositeDepth = 32,
        maxCompositeComponents = 4_096,
    )

    private fun <T> success(result: FontOperationResult<T>): T = when (result) {
        is FontOperationResult.Success -> result.value
        is FontOperationResult.Failure -> error("Unexpected font failure ${result.error.code}: ${result.error.message}")
        is FontOperationResult.Cancelled -> error("Unexpected font cancellation")
    }
}

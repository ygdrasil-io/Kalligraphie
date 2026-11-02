package org.graphiks.kalligraphie

import java.nio.file.Files
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.OutlineProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MacosSystemFontCatalogTest {
    @Test
    fun retainedMacosGlyphsSurviveSourceRemovalAndRecapture() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Mac"))
        withSources(mapOf("actual.ttf" to collectionFixture("/fonts/liberation/LiberationSans-Regular.ttf"))) { root ->
            val options = MacosSystemFontCatalogOptions(roots = listOf(root))
            val first = catalogSuccess(MacosSystemFontCatalog.open(options))
            val second = catalogSuccess(MacosSystemFontCatalog.open(options))
            val resolver = catalogSuccess(first.openAssetResolver())
            val secondResolver = catalogSuccess(second.openAssetResolver())
            val font = collectionInstance(first, "Liberation Sans")
            val asset = catalogSuccess(font.acquireRenderAsset(resolver, FontRenderVariantKey.default, collectionRequirements()))
            try {
                assertIs<FontError.IncompatibleCatalogGeneration>(assertIs<FontOperationResult.Failure>(secondResolver.reopen(asset.key)).error)
                Files.delete(java.nio.file.Path.of(root, "actual.ttf"))
                assertIs<FontOperationResult.Failure>(MacosSystemFontCatalog.open(options))
                resolver.close()
                assertEquals(1366, catalogSuccess(font.metrics(org.graphiks.kalligraphie.api.GlyphId(36))).advanceWidthDesignUnits)
                assertAuditedOutline(catalogSuccess(asset.resolveGlyph(FontGlyphRequest(36))), "Liberation Sans")
                assertAuditedA(second, "Liberation Sans")
            } finally { asset.close(); resolver.close(); secondResolver.close() }
        }
    }

    @Test
    fun stopsBeforeReadingAnActualFontWhenTheDiscoveryBudgetEndsAtTheRoot() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Mac"))
        withSources(mapOf("actual.ttf" to collectionFixture("/fonts/liberation/LiberationSans-Regular.ttf"))) { root ->
            val result = MacosSystemFontCatalog.open(MacosSystemFontCatalogOptions(roots = listOf(root), maxPathsToVisit = 1))
            assertIs<FontError.ResourceLimitExceeded>(assertIs<FontOperationResult.Failure>(result).error)
        }
    }

    @Test
    fun parsesStockSystemFacesThatPublishOnlyMacintoshRomanNames() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Mac"))
        val menlo = java.nio.file.Path.of("/System/Library/Fonts/Menlo.ttc")
        org.junit.Assume.assumeTrue(java.nio.file.Files.exists(menlo))
        val catalog = catalogSuccess(FontDirectoryCatalog.open(FontDirectoryCatalogOptions(listOf(menlo.toString()))))
        assertTrue(catalog.faces.any { it.metadata.familyName == "Menlo" })
    }

    @Test
    fun defaultLimitsCoverAStockSystem() {
        val options = MacosSystemFontCatalogOptions()
        assertEquals(2048, options.maxPathsToVisit)
        assertEquals(256, options.maxFaces)
        assertEquals(16 * 1024 * 1024, options.maxSourceBytes)
        assertEquals(256 * 1024 * 1024, options.maxTotalSourceBytes)
        assertEquals(1024, options.maxFacesToExamine)
    }

    @Test
    fun defaultOptionsRetainStockSystemClassicFaces() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Mac"))
        org.junit.Assume.assumeTrue(java.nio.file.Files.isDirectory(java.nio.file.Path.of("/System/Library/Fonts")))
        val catalog = catalogSuccess(MacosSystemFontCatalog.open())
        val families = catalog.faces.map { it.metadata.familyName }.toSet()
        assertTrue("Menlo" in families, "Menlo is missing from the default macOS catalog: ${families.sorted()}")
        assertTrue("Helvetica" in families, "Helvetica is missing from the default macOS catalog: ${families.sorted()}")
    }

    @Test
    fun doesNotAdvertiseAnOutlineRouteWhenAParsedFontLacksGlyfData() {
        val source = minimalTrueTypeFont(glyphCount = 1, tables = emptyMap()).also { bytes ->
            replaceTableTag(bytes, "glyf", "JUNK")
        }
        val catalog = success(Kalligraphie.embedded(source, FontSourceProvenance("No glyf route")))
        val result = catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.renderable(outlineProfile()))

        assertFalse(catalog.faces.single().capabilities.outline)
        assertIs<FontError.UnsupportedRepresentationProfile>(assertIs<FontOperationResult.Failure>(result).error)
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

    private fun replaceTableTag(font: ByteArray, expected: String, replacement: String) {
        val tableCount = ((font[4].toInt() and 0xFF) shl 8) or (font[5].toInt() and 0xFF)
        repeat(tableCount) { index ->
            val offset = 12 + index * 16
            if ((0 until 4).all { tagIndex -> font[offset + tagIndex].toInt().toChar() == expected[tagIndex] }) {
                replacement.forEachIndexed { tagIndex, character -> font[offset + tagIndex] = character.code.toByte() }
                return
            }
        }
        error("Missing $expected table in synthetic source.")
    }
}
